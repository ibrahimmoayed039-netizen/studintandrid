package com.attendance.nfc.data

import android.content.Context
import androidx.room.withTransaction
import com.attendance.nfc.DAY_NAMES
import com.attendance.nfc.entryEnd
import com.attendance.nfc.fromMin
import com.attendance.nfc.toMin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

/** حالة الخادم المعروضة في الإعدادات. */
object LanState {
    val running = MutableStateFlow(false)
    val address = MutableStateFlow("")
    val lastClient = MutableStateFlow("")
    val error = MutableStateFlow("")
}

/** إعدادات التحكم من الحاسوب. منفصلة عن AppSettings حتى لا يدخل الرمز في النسخ الاحتياطية. */
class LanPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("lan", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }
    val port: Int get() = sp.getInt("port", DEFAULT_PORT)
    val token: String
        get() {
            val t = sp.getString("token", "") ?: ""
            if (t.length == 6) return t
            return regenerate()
        }
    fun regenerate(): String {
        val r = SecureRandom()
        val t = (0 until 6).joinToString("") { r.nextInt(10).toString() }
        sp.edit().putString("token", t).apply()
        return t
    }
    companion object { const val DEFAULT_PORT = 8765 }
}

fun lanLocalIp(): String? = try {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
        ?.hostAddress
} catch (_: Exception) { null }

private class HttpError(val code: Int, msg: String) : Exception(msg)

/**
 * خادم HTTP صغير (بلا مكتبات) يتيح للحاسوب قراءة الجدول وتعديله عبر الشبكة المحلية.
 * كل الطلبات عدا /api/ping تتطلب الرمز في ترويسة X-Token. بعد 5 محاولات خاطئة يُقفل 60 ثانية.
 */
class LanServer(private val ctx: Context, private val port: Int, private val tokenProvider: () -> String) {
    private val db = AppDatabase.get(ctx)
    private val audit = AuditStore.get(ctx)
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var failures = 0
    @Volatile private var lockedUntil = 0L

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        pool.execute {
            while (!ss.isClosed) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (_: Exception) { if (ss.isClosed) break }
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        pool.shutdownNow()
    }

    // ---------- HTTP ----------
    private fun readLine(i: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = i.read()
            if (c == -1) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 8192) throw HttpError(431, "الترويسة طويلة")
        }
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            var status = 200
            var out: JSONObject = JSONObject()
            val ip = s.inetAddress?.hostAddress ?: "?"
            try {
                s.soTimeout = 10_000
                val inp = s.getInputStream().buffered()
                val first = readLine(inp) ?: return
                val parts = first.split(" ")
                if (parts.size < 2) throw HttpError(400, "طلب غير صالح")
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = HashMap<String, String>()
                while (true) {
                    val l = readLine(inp) ?: break
                    if (l.isEmpty()) break
                    val k = l.substringBefore(':').trim().lowercase()
                    headers[k] = l.substringAfter(':', "").trim()
                }
                val len = headers["content-length"]?.toIntOrNull() ?: 0
                if (len < 0 || len > 1_000_000) throw HttpError(413, "الطلب كبير")
                val raw = ByteArray(len)
                var got = 0
                while (got < len) {
                    val n = inp.read(raw, got, len - got)
                    if (n < 0) throw HttpError(400, "جسم الطلب ناقص")
                    got += n
                }
                val body = if (len > 0) String(raw, Charsets.UTF_8) else ""
                out = route(method, path, headers["x-token"] ?: "", body, ip)
            } catch (e: HttpError) {
                status = e.code
                out = JSONObject().put("ok", false).put("error", e.message ?: "خطأ")
            } catch (e: Exception) {
                status = 500
                out = JSONObject().put("ok", false).put("error", "خطأ داخلي: ${e.message}")
            }
            try {
                val bytes = out.toString().toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 $status ${reason(status)}\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                val os = s.getOutputStream()
                os.write(head.toByteArray(Charsets.UTF_8)); os.write(bytes); os.flush()
            } catch (_: Exception) {}
        }
    }

    private fun reason(c: Int) = when (c) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 409 -> "Conflict"
        413 -> "Payload Too Large"; 429 -> "Too Many Requests"; else -> "Error"
    }

    // ---------- المسارات ----------
    private fun route(method: String, path: String, token: String, body: String, ip: String): JSONObject {
        if (method == "GET" && path == "/api/ping") {
            return JSONObject().put("ok", true).put("app", "attendance-android").put("version", 1)
                .put("school", SettingsStore(ctx).flow.value.schoolName)
        }
        if (!path.startsWith("/api/")) throw HttpError(404, "غير موجود")
        val now = System.currentTimeMillis()
        if (now < lockedUntil) throw HttpError(429, "محاولات كثيرة خاطئة، انتظر دقيقة")
        val ok = MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), tokenProvider().toByteArray(Charsets.UTF_8))
        if (!ok) {
            failures++
            if (failures >= 5) { failures = 0; lockedUntil = now + 60_000 }
            throw HttpError(401, "رمز الاتصال غير صحيح")
        }
        failures = 0
        LanState.lastClient.value = ip
        val who = "الحاسوب ($ip)"
        return runBlocking {
            when {
                method == "GET" && path == "/api/live" -> live()
                method == "POST" && path == "/api/report" -> report(JSONObject(body), who)
                method == "GET" && path == "/api/schedule" -> listAll()
                method == "POST" && path == "/api/schedule/save" -> save(JSONObject(body), who)
                method == "POST" && path == "/api/schedule/delete" -> delete(JSONObject(body), who)
                method == "POST" && path == "/api/schedule/replace" -> replace(JSONObject(body), who)
                else -> throw HttpError(404, "مسار غير معروف")
            }
        }
    }

    private fun lectureMin() = SettingsStore(ctx).flow.value.lectureMin

    private fun itemJson(s: ScheduleItem, lm: Int) = JSONObject()
        .put("id", s.id).put("ownerId", s.ownerId).put("day", s.day).put("subject", s.subject)
        .put("group", s.grp).put("start", s.startTime).put("end", s.endTime)
        .put("endEff", entryEnd(s, lm)).put("lateMin", s.lateMin).put("auto", s.auto)

    private suspend fun listAll(): JSONObject {
        val lm = lectureMin()
        val users = JSONArray()
        db.userDao().all().forEach { users.put(JSONObject().put("id", it.id).put("name", it.name).put("role", it.role)) }
        val items = JSONArray()
        db.scheduleDao().all().sortedWith(compareBy<ScheduleItem>({ it.ownerId }, { it.day }, { it.startTime }))
            .forEach { items.put(itemJson(it, lm)) }
        return JSONObject().put("ok", true).put("school", SettingsStore(ctx).flow.value.schoolName)
            .put("lectureMin", lm).put("users", users).put("items", items)
    }

    /** الحالة المباشرة: المحاضرات النشطة وأعداد الحضور والحصة القادمة ومراحل الطلاب في هذا الجهاز. */
    private suspend fun live(): JSONObject {
        val lm = lectureMin()
        val now = java.time.LocalDateTime.now()
        val nowMin = now.hour * 60 + now.minute
        val students = db.studentDao().all()
        val schedule = db.scheduleDao().all()
        val sessions = JSONArray()
        db.sessionDao().all().filter { it.active }.sortedBy { it.startTs }.forEach { ses ->
            val recs = db.attendanceDao().forSession(ses.id)
            val expected = students.count { ses.grp.isBlank() || it.grade == ses.grp }
            val sched = schedule.find { it.id == ses.scheduleId }
            val end = if (sched != null) entryEnd(sched, lm) else fromMin(toMin(ses.startTime) + lm)
            val recent = JSONArray()
            recs.take(5).forEach { recent.put(JSONObject().put("name", it.studentName).put("time", it.time).put("status", it.status)) }
            sessions.put(JSONObject()
                .put("id", ses.id).put("subject", ses.subject).put("group", ses.grp)
                .put("start", ses.startTime).put("startTs", ses.startTs).put("end", end)
                .put("teacherId", ses.createdBy).put("teacher", ses.createdByName)
                .put("present", recs.count { it.status == "present" }).put("late", recs.count { it.status == "late" })
                .put("expected", expected).put("recent", recent))
        }
        val today = now.dayOfWeek.value % 7   // الأحد = 0
        val next = schedule.filter { it.day == today && toMin(it.startTime) > nowMin }.minByOrNull { toMin(it.startTime) }
        val nextJson = next?.let {
            JSONObject().put("subject", it.subject).put("group", it.grp).put("start", it.startTime)
                .put("teacher", db.userDao().byId(it.ownerId)?.name ?: "")
        }
        val grades = JSONArray()
        students.filter { it.grade.isNotBlank() }.groupingBy { it.grade }.eachCount().toSortedMap()
            .forEach { (g, c) -> grades.put(JSONObject().put("name", g).put("count", c)) }
        return JSONObject().put("ok", true).put("school", SettingsStore(ctx).flow.value.schoolName)
            .put("nowTs", System.currentTimeMillis()).put("now", fromMin(nowMin))
            .put("totalStudents", students.size).put("grades", grades)
            .put("sessions", sessions).put("next", nextJson ?: JSONObject.NULL)
    }

    private val dateRe = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** بيانات خام لتقرير الحضور في نطاق تواريخ: الطلاب والمحاضرات والسجلات. يحسب الحاسوب النِّسَب بنفس منطق التقرير الشهري. */
    private suspend fun report(o: JSONObject, who: String): JSONObject {
        val from = o.optString("from"); val to = o.optString("to")
        if (!dateRe.matches(from) || !dateRe.matches(to) || from > to) throw HttpError(400, "نطاق التاريخ غير صالح")
        val sessions = db.sessionDao().all().filter { it.date in from..to }
        val ids = sessions.map { it.id }.toHashSet()
        val att = db.attendanceDao().between(from, to).filter { it.sessionId in ids }
        val students = JSONArray()
        db.studentDao().all().forEach { students.put(JSONObject().put("id", it.id).put("name", it.name).put("grade", it.grade)) }
        val ses = JSONArray()
        sessions.forEach {
            ses.put(JSONObject().put("id", it.id).put("subject", it.subject).put("group", it.grp)
                .put("date", it.date).put("start", it.startTime).put("teacher", it.createdByName))
        }
        val recs = JSONArray()
        att.forEach { recs.put(JSONArray().put(it.sessionId).put(it.studentId).put(if (it.status == "late") "l" else "p").put(it.time)) }
        audit.add(who, "سحب تقرير", "$from ← $to: ${students.length()} طالب، ${ses.length()} محاضرة، ${recs.length()} سجل")
        return JSONObject().put("ok", true).put("school", SettingsStore(ctx).flow.value.schoolName)
            .put("from", from).put("to", to).put("students", students).put("sessions", ses).put("att", recs)
    }

    private val timeRe = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    private suspend fun parse(o: JSONObject, forceOwner: String? = null): ScheduleItem {
        val day = o.optInt("day", -1)
        if (day !in 0..6) throw HttpError(400, "اليوم غير صالح")
        val subject = o.optString("subject").trim()
        if (subject.isEmpty()) throw HttpError(400, "اسم المادة مطلوب")
        val start = o.optString("start")
        if (!timeRe.matches(start)) throw HttpError(400, "وقت البدء غير صالح (HH:mm)")
        val end = o.optString("end")
        if (end.isNotEmpty()) {
            if (!timeRe.matches(end)) throw HttpError(400, "وقت الانتهاء غير صالح (HH:mm)")
            if (toMin(end) <= toMin(start)) throw HttpError(400, "وقت الانتهاء يجب أن يكون بعد وقت البدء")
        }
        val owner = forceOwner ?: o.optString("ownerId")
        if (db.userDao().byId(owner) == null) throw HttpError(400, "المستخدم صاحب الجدول غير موجود")
        return ScheduleItem(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            day = day, subject = subject, grp = o.optString("group").trim(),
            startTime = start, endTime = end,
            lateMin = o.optInt("lateMin", 10).coerceIn(0, 600),
            auto = o.optBoolean("auto", true), ownerId = owner
        )
    }

    private fun overlap(a: ScheduleItem, b: ScheduleItem, lm: Int): Boolean {
        if (a.day != b.day) return false
        val s1 = toMin(a.startTime); val e1 = toMin(entryEnd(a, lm))
        val s2 = toMin(b.startTime); val e2 = toMin(entryEnd(b, lm))
        return s1 < e2 && s2 < e1   // التلامس عند الحافة مسموح
    }

    private fun label(s: ScheduleItem) = "${DAY_NAMES[s.day]} ${s.startTime} — ${s.subject}"

    private fun conflictMsg(c: ScheduleItem, lm: Int) =
        "تعارض مع حصة «${c.subject}» يوم ${DAY_NAMES[c.day]} (${c.startTime} – ${entryEnd(c, lm)})"

    private suspend fun save(o: JSONObject, who: String): JSONObject {
        val item = parse(o.optJSONObject("item") ?: o)
        val lm = lectureMin()
        val others = db.scheduleDao().all().filter { it.ownerId == item.ownerId && it.id != item.id }
        others.firstOrNull { overlap(item, it, lm) }?.let { throw HttpError(409, conflictMsg(it, lm)) }
        val old = db.scheduleDao().all().find { it.id == item.id }
        db.scheduleDao().upsert(item)
        audit.add(who, if (old == null) "إضافة حصة" else "تعديل حصة",
            if (old != null && label(old) != label(item)) "${label(old)} ← ${label(item)}" else label(item))
        return JSONObject().put("ok", true).put("item", itemJson(item, lm))
    }

    private suspend fun delete(o: JSONObject, who: String): JSONObject {
        val id = o.optString("id")
        val old = db.scheduleDao().all().find { it.id == id } ?: throw HttpError(404, "الحصة غير موجودة")
        db.scheduleDao().delete(id)
        audit.add(who, "حذف حصة", label(old))
        return JSONObject().put("ok", true)
    }

    private suspend fun replace(o: JSONObject, who: String): JSONObject {
        val owner = o.optString("ownerId")
        val u = db.userDao().byId(owner) ?: throw HttpError(400, "المستخدم صاحب الجدول غير موجود")
        val arr = o.optJSONArray("items") ?: throw HttpError(400, "items مطلوبة")
        if (arr.length() > 500) throw HttpError(400, "عدد الحصص كبير")
        val lm = lectureMin()
        val list = ArrayList<ScheduleItem>()
        for (i in 0 until arr.length()) {
            val it0 = parse(arr.getJSONObject(i), forceOwner = owner).copy(id = UUID.randomUUID().toString())
            list.firstOrNull { overlap(it0, it, lm) }?.let { throw HttpError(409, conflictMsg(it, lm)) }
            list.add(it0)
        }
        db.withTransaction {
            db.scheduleDao().deleteByOwner(owner)
            db.scheduleDao().upsertAll(list)
        }
        audit.add(who, "استبدال الجدول", "جدول ${u.name}: ${list.size} حصة")
        return JSONObject().put("ok", true).put("count", list.size)
    }
}

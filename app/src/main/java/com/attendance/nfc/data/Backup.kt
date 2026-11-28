package com.attendance.nfc.data

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** نسخة احتياطية كاملة بصيغة JSON (الطلاب مع صورهم، الجدول، الجلسات، الحضور، المستخدمون، الإعدادات). */
object Backup {
    private const val APP_ID = "attendance-android"

    private fun photoToB64(path: String): String =
        if (path.isBlank()) "" else try { Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP) } catch (_: Exception) { "" }

    private fun b64ToPhoto(ctx: Context, b64: String): String {
        if (b64.isBlank()) return ""
        return try {
            val f = File(ctx.filesDir, "photo_${UUID.randomUUID()}.jpg")
            f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
            f.absolutePath
        } catch (_: Exception) { "" }
    }

    suspend fun export(db: AppDatabase, settings: AppSettings, ctx: Context): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", APP_ID)
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("settings", JSONObject()
            .put("school", settings.schoolName).put("auto", settings.autoSchedule)
            .put("lecture", settings.lectureMin).put("break", settings.breakMin).put("late", settings.defaultLate).put("backupDays", settings.backupDays).put("logo", photoToB64(settings.logoPath)).put("autoReceive", settings.autoReceive).put("autoLogout", settings.autoLogout))

        val students = JSONArray()
        db.studentDao().all().forEach { s ->
            students.put(JSONObject().put("id", s.id).put("name", s.name).put("grade", s.grade)
                .put("cardUid", s.cardUid).put("photo", photoToB64(s.photoPath)).put("createdBy", s.createdBy))
        }
        root.put("students", students)

        val schedule = JSONArray()
        db.scheduleDao().all().forEach { s ->
            schedule.put(JSONObject().put("id", s.id).put("day", s.day).put("subject", s.subject).put("grp", s.grp)
                .put("start", s.startTime).put("end", s.endTime).put("late", s.lateMin).put("auto", s.auto).put("owner", s.ownerId))
        }
        root.put("schedule", schedule)

        val sessions = JSONArray()
        db.sessionDao().all().forEach { s ->
            sessions.put(JSONObject().put("id", s.id).put("subject", s.subject).put("grp", s.grp).put("late", s.lateMin)
                .put("date", s.date).put("time", s.startTime).put("startTs", s.startTs).put("endTs", s.endTs)
                .put("active", s.active).put("autoEnd", s.autoEnd).put("scheduleId", s.scheduleId)
                .put("by", s.createdBy).put("byName", s.createdByName))
        }
        root.put("sessions", sessions)

        val att = JSONArray()
        db.attendanceDao().all().forEach { a ->
            att.put(JSONObject().put("id", a.id).put("sessionId", a.sessionId).put("studentId", a.studentId)
                .put("studentName", a.studentName).put("grade", a.grade).put("subject", a.subject)
                .put("date", a.date).put("time", a.time).put("status", a.status).put("manual", a.manual))
        }
        root.put("attendance", att)

        val users = JSONArray()
        db.userDao().all().forEach { u ->
            users.put(JSONObject().put("id", u.id).put("username", u.username).put("name", u.name)
                .put("role", u.role).put("salt", u.salt).put("hash", u.hash).put("createdAt", u.createdAt).put("cardUid", u.cardUid).put("canStudents", u.canStudents))
        }
        root.put("users", users)
        root.toString(2)
    }

    /** يستبدل كل البيانات الحالية بمحتوى النسخة. يُرجع الإعدادات المستوردة. */
    suspend fun restore(db: AppDatabase, ctx: Context, json: String): AppSettings = withContext(Dispatchers.IO) {
        val root = try { JSONObject(json) } catch (_: Exception) { throw IllegalArgumentException("الملف ليس نسخة احتياطية صالحة") }
        if (root.optString("app") != APP_ID) throw IllegalArgumentException("هذا الملف ليس نسخة احتياطية من تطبيق الأندرويد")

        fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
            if (this == null) return emptyList()
            return (0 until length()).map { f(getJSONObject(it)) }
        }

        val students = root.optJSONArray("students").mapObjects { o ->
            Student(o.getString("id"), o.getString("name"), o.optString("grade"), o.optString("cardUid"), b64ToPhoto(ctx, o.optString("photo")), o.optString("createdBy"))
        }
        val schedule = root.optJSONArray("schedule").mapObjects { o ->
            ScheduleItem(o.getString("id"), o.getInt("day"), o.getString("subject"), o.optString("grp"), o.getString("start"),
                o.optString("end"), o.optInt("late", 10), o.optBoolean("auto", true), o.optString("owner"))
        }
        val sessions = root.optJSONArray("sessions").mapObjects { o ->
            LectureSession(o.getString("id"), o.getString("subject"), o.optString("grp"), o.optInt("late", 10), o.getString("date"),
                o.optString("time"), o.optLong("startTs"), o.optLong("endTs"), o.optBoolean("active"), o.optBoolean("autoEnd"),
                o.optString("scheduleId"), o.optString("by"), o.optString("byName"))
        }
        val att = root.optJSONArray("attendance").mapObjects { o ->
            Attendance(o.getString("id"), o.getString("sessionId"), o.getString("studentId"), o.optString("studentName"), o.optString("grade"),
                o.optString("subject"), o.getString("date"), o.optString("time"), o.optString("status", "present"), o.optBoolean("manual"))
        }
        val users = root.optJSONArray("users").mapObjects { o ->
            User(o.getString("id"), o.getString("username"), o.optString("name"), o.optString("role", "teacher"),
                o.getString("salt"), o.getString("hash"), o.optLong("createdAt"), o.optString("cardUid"), o.optBoolean("canStudents", false))
        }

        db.withTransaction {
            db.attendanceDao().clear(); db.sessionDao().clear(); db.scheduleDao().clear()
            db.studentDao().clear(); db.userDao().clear()
            db.studentDao().upsertAll(students); db.scheduleDao().upsertAll(schedule)
            db.sessionDao().upsertAll(sessions); db.attendanceDao().upsertAll(att)
            if (users.isNotEmpty()) db.userDao().upsertAll(users)
        }

        val st = root.optJSONObject("settings")
        if (st == null) AppSettings() else AppSettings(
            st.optString("school", "حضور الطلبة"), st.optBoolean("auto"), st.optInt("lecture", 45), st.optInt("break", 10), st.optInt("late", 10), st.optInt("backupDays", 30), b64ToPhoto(ctx, st.optString("logo")), st.optBoolean("autoReceive", true), st.optBoolean("autoLogout", true)
        )
    }
}

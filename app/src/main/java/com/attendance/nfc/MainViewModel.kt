package com.attendance.nfc

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.attendance.nfc.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class ScanKind { IDLE, OK, LATE, DUP, BAD }

data class ScanUi(
    val kind: ScanKind = ScanKind.IDLE,
    val title: String = "",
    val sub: String = "",
    val photoPath: String = "",
    val nonce: Long = 0
)

data class Picked(val name: String, val grade: String, val photoPath: String, val remaining: Int)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)
    private val settingsStore = SettingsStore(app)
    private val authPrefs = app.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    val settings: StateFlow<AppSettings> = settingsStore.flow
    val auditStore = AuditStore(app)
    val audit: StateFlow<List<AuditEntry>> = auditStore.flow
    private fun log(action: String, detail: String = "") =
        auditStore.add(_currentUser.value?.name ?: "النظام", action, detail)
    fun purgeAudit(days: Int) {
        val n = auditStore.purgeOlderThan(days)
        log("حذف سجلات التدقيق القديمة", "$n سجل (أقدم من $days يوماً)")
        toast("تم حذف $n سجل")
    }

    fun saveSettings(v: AppSettings) {
        val o = settingsStore.flow.value
        val ch = buildList {
            if (o.schoolName != v.schoolName) add("الاسم: ${o.schoolName} ← ${v.schoolName}")
            if (o.autoSchedule != v.autoSchedule) add("التشغيل التلقائي: ${if (v.autoSchedule) "مفعّل" else "معطّل"}")
            if (o.autoReceive != v.autoReceive) add("شاشة الاستقبال التلقائية: ${if (v.autoReceive) "مفعّلة" else "معطّلة"}")
            if (o.lectureMin != v.lectureMin) add("مدة الحصة: ${o.lectureMin} ← ${v.lectureMin}")
            if (o.breakMin != v.breakMin) add("مدة الاستراحة: ${o.breakMin} ← ${v.breakMin}")
            if (o.defaultLate != v.defaultLate) add("التأخير: ${o.defaultLate} ← ${v.defaultLate}")
            if (o.backupDays != v.backupDays) add("أيام النسخ: ${o.backupDays} ← ${v.backupDays}")
        }
        settingsStore.save(v)
        if (ch.isNotEmpty()) log("تعديل الإعدادات", ch.joinToString(" • "))
    }

    // ---------------- المصادقة ----------------
    private val _currentUser = MutableStateFlow<User?>(null)
    val currentUser: StateFlow<User?> = _currentUser
    private val _authReady = MutableStateFlow(false)
    val authReady: StateFlow<Boolean> = _authReady
    val users = db.userDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun allFlag(u: User) = if (u.role == "admin") 1 else 0

    // ---------------- البيانات (تتبع المستخدم الحالي وصلاحياته) ----------------
    val students = db.studentDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val schedule = _currentUser
        .flatMapLatest { u -> if (u == null) flowOf(emptyList<ScheduleItem>()) else db.scheduleDao().observeByOwner(u.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val activeSession = _currentUser
        .flatMapLatest { u -> if (u == null) flowOf<LectureSession?>(null) else db.sessionDao().observeActiveFor(u.id, allFlag(u)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val allSessions = db.sessionDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** المدير يرى كل المحاضرات، والمدرّس يرى محاضراته فقط. */
    val sessions: StateFlow<List<LectureSession>> = combine(allSessions, _currentUser) { list, u ->
        when {
            u == null -> emptyList()
            u.role == "admin" -> list
            else -> list.filter { it.createdBy == u.id }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val sessionAttendance = activeSession
        .flatMapLatest { s -> if (s == null) flowOf(emptyList()) else db.attendanceDao().observeForSession(s.id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _scan = MutableStateFlow(ScanUi())
    val scanState: StateFlow<ScanUi> = _scan
    private var resetJob: Job? = null

    /** عند تفعيلها، تُوجَّه أول بطاقة تُمسح إلى نموذج الطالب بدل تسجيل الحضور. */
    val cardCapture = MutableStateFlow(false)
    private val _captured = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val captured: SharedFlow<String> = _captured

    /** يُطلق لحظة بدء حصة جديدة (يدوياً أو من الجدول) لفتح شاشة استقبال البطاقات تلقائياً. */
    private val _sessionStarted = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sessionStarted: SharedFlow<String> = _sessionStarted

    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private fun toast(msg: String) { toasts.tryEmit(msg) }
    fun showToast(msg: String) = toast(msg)

    private val pickedBy = mutableMapOf<String, MutableSet<String>>()
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }

    init {
        viewModelScope.launch {
            ensureAdmin()
            authPrefs.getString("uid", null)?.let { _currentUser.value = db.userDao().byId(it) }
            _authReady.value = true
            AutoBackup.runIfNeeded(db, settings.value, getApplication())
            // فاحص الجدول التلقائي كل 15 ثانية أثناء عمل التطبيق
            while (true) {
                try { autoTick() } catch (_: Exception) {}
                delay(15_000)
            }
        }
    }

    private suspend fun ensureAdmin() {
        if (db.userDao().count() == 0) {
            val salt = Passwords.newSalt()
            val hash = withContext(Dispatchers.Default) { Passwords.hash("1234", salt) }
            db.userDao().upsert(User("admin", "admin", "المدير", "admin", salt, hash, System.currentTimeMillis()))
        }
    }

    fun login(username: String, password: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val u = db.userDao().byUsername(username.trim())
            val ok = u != null && withContext(Dispatchers.Default) { Passwords.verify(password, u.salt, u.hash) }
            if (ok && u != null) {
                _currentUser.value = u
                authPrefs.edit().putString("uid", u.id).apply()
                log("تسجيل دخول")
            }
            onResult(ok)
        }
    }

    fun logout() {
        log("تسجيل خروج")
        _currentUser.value = null
        authPrefs.edit().remove("uid").apply()
        cardCapture.value = false
        _scan.value = ScanUi()
    }

    // ---------------- المستخدمون (للمدير) ----------------
    private fun roleOf(r: String) = if (r == "admin") "admin" else "teacher"

    fun createUser(username: String, password: String, name: String, role: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val un = username.trim()
            if (un.isEmpty() || password.isEmpty()) return@launch onResult("أدخل اسم المستخدم وكلمة المرور")
            if (db.userDao().byUsername(un) != null) return@launch onResult("اسم المستخدم مستخدم بالفعل")
            val salt = Passwords.newSalt()
            val hash = withContext(Dispatchers.Default) { Passwords.hash(password, salt) }
            db.userDao().upsert(User(UUID.randomUUID().toString(), un, name.trim().ifEmpty { un }, roleOf(role), salt, hash, System.currentTimeMillis()))
            log("إنشاء مستخدم", "@$un (${if (roleOf(role) == "admin") "مدير" else "مدرّس"})")
            onResult(null)
        }
    }

    fun updateUser(id: String, name: String, role: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            val u = db.userDao().byId(id) ?: return@launch onResult("المستخدم غير موجود")
            val newRole = roleOf(role)
            if (u.role == "admin" && newRole != "admin" && db.userDao().adminCount() <= 1) return@launch onResult("لا يمكن إزالة صلاحية آخر مدير")
            val nu = u.copy(name = name.trim().ifEmpty { u.username }, role = newRole)
            db.userDao().upsert(nu)
            if (_currentUser.value?.id == id) _currentUser.value = nu
            log("تعديل مستخدم", "@${u.username}: ${u.name} (${u.role}) ← ${nu.name} (${nu.role})")
            onResult(null)
        }
    }

    fun resetPassword(id: String, password: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            if (password.isEmpty()) return@launch onResult("أدخل كلمة المرور الجديدة")
            val u = db.userDao().byId(id) ?: return@launch onResult("المستخدم غير موجود")
            val salt = Passwords.newSalt()
            val hash = withContext(Dispatchers.Default) { Passwords.hash(password, salt) }
            db.userDao().upsert(u.copy(salt = salt, hash = hash))
            log("تغيير كلمة مرور", "المستخدم @${u.username}")
            onResult(null)
        }
    }

    fun deleteUser(id: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            if (_currentUser.value?.id == id) return@launch onResult("لا يمكنك حذف حسابك الحالي")
            val u = db.userDao().byId(id) ?: return@launch onResult("المستخدم غير موجود")
            if (u.role == "admin" && db.userDao().adminCount() <= 1) return@launch onResult("لا يمكن حذف آخر مدير")
            db.userDao().delete(id)
            log("حذف مستخدم", "@${u.username} (${u.name})")
            onResult(null)
        }
    }

    // ---------------- المسح ----------------
    fun onCardUid(uid: String) {
        viewModelScope.launch {
            val u = _currentUser.value ?: return@launch
            if (cardCapture.value) {
                cardCapture.value = false
                _captured.emit(uid)
                feedback(true)
                return@launch
            }
            val ses = db.sessionDao().getActiveFor(u.id, allFlag(u))
            if (ses == null) {
                show(ScanUi(ScanKind.BAD, "لا توجد محاضرة نشطة", "ابدأ محاضرة ثم مرّر البطاقة"))
                feedback(false); return@launch
            }
            val st = db.studentDao().byCard(uid)
            if (st == null) {
                show(ScanUi(ScanKind.BAD, "بطاقة غير مسجّلة", uid))
                feedback(false); return@launch
            }
            if (ses.grp.isNotBlank() && st.grade != ses.grp) {
                show(ScanUi(ScanKind.BAD, st.name, "ليس من مجموعة ${ses.grp}", st.photoPath))
                feedback(false); return@launch
            }
            register(ses, st, false)
        }
    }

    private suspend fun register(ses: LectureSession, st: Student, manual: Boolean) {
        val dao = db.attendanceDao()
        val already = dao.find(ses.id, st.id)
        if (already != null) {
            show(ScanUi(ScanKind.DUP, st.name, "سُجّل مسبقاً الساعة ${already.time}", st.photoPath))
            feedback(false); return
        }
        val late = System.currentTimeMillis() - ses.startTs > ses.lateMin * 60_000L
        dao.upsert(
            Attendance(
                id = UUID.randomUUID().toString(), sessionId = ses.id, studentId = st.id,
                studentName = st.name, grade = st.grade, subject = ses.subject, date = ses.date,
                time = LocalTime.now().format(timeFmt), status = if (late) "late" else "present", manual = manual
            )
        )
        if (manual) log("حضور يدوي", "${st.name} — ${ses.subject} ${ses.date}")
        val label = if (late) "متأخر" else if (manual) "حضور يدوي" else "تم تسجيل الحضور"
        show(ScanUi(if (late) ScanKind.LATE else ScanKind.OK, st.name, "${st.grade} — $label", st.photoPath))
        feedback(true)
    }

    private fun show(ui: ScanUi) {
        _scan.value = ui.copy(nonce = System.nanoTime())
        resetJob?.cancel()
        resetJob = viewModelScope.launch { delay(4000); _scan.value = ScanUi() }
    }

    private fun feedback(ok: Boolean) {
        try { tone.startTone(if (ok) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_NACK, 150) } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            val v = getApplication<Application>().getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createOneShot(if (ok) 60 else 200, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    fun manualAttend(st: Student) {
        viewModelScope.launch {
            val u = _currentUser.value ?: return@launch
            val ses = db.sessionDao().getActiveFor(u.id, allFlag(u)) ?: return@launch toast("ابدأ محاضرة أولاً")
            register(ses, st, true)
        }
    }

    /** تسجيل حضور طالب في أي محاضرة (من تبويب التقارير) دون نتيجة مسح. */
    fun markPresent(ses: LectureSession, st: Student) {
        viewModelScope.launch {
            if (db.attendanceDao().find(ses.id, st.id) != null) return@launch
            db.attendanceDao().upsert(
                Attendance(UUID.randomUUID().toString(), ses.id, st.id, st.name, st.grade, ses.subject, ses.date,
                    LocalTime.now().format(timeFmt), "present", true)
            )
            log("حضور يدوي", "${st.name} — ${ses.subject} ${ses.date}")
            toast("تم تسجيل حضور ${st.name}")
        }
    }

    // ---------------- الجلسات ----------------
    fun startSession(subject: String, grp: String, lateMin: Int, scheduleId: String = "", autoEnd: Boolean = false) {
        viewModelScope.launch { doStart(subject, grp, lateMin, scheduleId, autoEnd, System.currentTimeMillis()) }
    }

    private suspend fun doStart(subject: String, grp: String, lateMin: Int, scheduleId: String, autoEnd: Boolean, startTs: Long) {
        val u = _currentUser.value ?: return
        if (db.sessionDao().getMine(u.id) != null) { toast("هناك محاضرة نشطة بالفعل"); return }
        val dt = Instant.ofEpochMilli(startTs).atZone(ZoneId.systemDefault())
        db.sessionDao().upsert(
            LectureSession(
                id = UUID.randomUUID().toString(), subject = subject, grp = grp, lateMin = lateMin,
                date = dt.toLocalDate().toString(), startTime = dt.format(timeFmt), startTs = startTs,
                active = true, autoEnd = autoEnd, scheduleId = scheduleId, createdBy = u.id, createdByName = u.name
            )
        )
        log("بدء محاضرة", subject + (if (grp.isNotBlank()) " ($grp)" else "") + (if (scheduleId.isNotEmpty()) " — من الجدول" else ""))
        if (settings.value.autoReceive) _sessionStarted.tryEmit(subject)
    }

    fun endSession() {
        viewModelScope.launch {
            val u = _currentUser.value ?: return@launch
            val s = db.sessionDao().getActiveFor(u.id, allFlag(u)) ?: return@launch
            db.sessionDao().upsert(s.copy(active = false, endTs = System.currentTimeMillis()))
            log("إنهاء محاضرة", s.subject)
        }
    }

    private suspend fun autoTick() {
        val u = _currentUser.value ?: return
        if (!settings.value.autoSchedule) return
        val now = LocalDateTime.now()
        val nm = now.hour * 60 + now.minute
        val lm = settings.value.lectureMin
        val today = now.toLocalDate().toString()
        val sdao = db.sessionDao()

        // إنهاء تلقائي لمحاضرة هذا المستخدم
        val mine = sdao.getMine(u.id)
        if (mine != null && mine.autoEnd && mine.scheduleId.isNotEmpty()) {
            val item = schedule.value.find { it.id == mine.scheduleId }
            if (item != null && (mine.date != today || nm >= toMin(entryEnd(item, lm)))) {
                sdao.upsert(mine.copy(active = false, endTs = System.currentTimeMillis()))
                log("إنهاء محاضرة تلقائياً", mine.subject)
                toast("انتهت محاضرة «${mine.subject}» تلقائياً")
            }
        }
        // بدء تلقائي
        if (sdao.getMine(u.id) == null) {
            val day = now.dayOfWeek.value % 7   // الأحد = 0
            val cand = schedule.value.filter { it.day == day && it.auto }.sortedBy { it.startTime }
                .firstOrNull { nm >= toMin(it.startTime) && nm < toMin(entryEnd(it, lm)) && sdao.countFor(it.id, today) == 0 }
            if (cand != null) {
                val startTs = now.toLocalDate().atTime(LocalTime.of(toMin(cand.startTime) / 60, toMin(cand.startTime) % 60))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                doStart(cand.subject, cand.grp, cand.lateMin, cand.id, true, startTs)
                toast("بدأت محاضرة «${cand.subject}» تلقائياً")
            }
        }
    }

    // ---------------- الطلاب ----------------
    fun saveStudent(s: Student, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            if (s.cardUid.isNotBlank()) {
                val ex = db.studentDao().byCard(s.cardUid)
                if (ex != null && ex.id != s.id) { toast("هذه البطاقة مسجّلة للطالب ${ex.name}"); onResult(false); return@launch }
            }
            val old = db.studentDao().all().find { it.id == s.id }
            db.studentDao().upsert(s)
            if (old == null) log("إضافة طالب", "${s.name} — ${s.grade.ifBlank { "بدون صف" }}" + if (s.cardUid.isNotBlank()) " — بطاقة ${s.cardUid}" else "")
            else {
                val ch = buildList {
                    if (old.name != s.name) add("الاسم: ${old.name} ← ${s.name}")
                    if (old.grade != s.grade) add("الصف: ${old.grade} ← ${s.grade}")
                    if (old.cardUid != s.cardUid) add("البطاقة: ${old.cardUid.ifBlank { "—" }} ← ${s.cardUid.ifBlank { "—" }}")
                    if (old.photoPath != s.photoPath) add("الصورة")
                }
                if (ch.isNotEmpty()) log("تعديل طالب", "${old.name}: " + ch.joinToString(" • "))
            }
            onResult(true)
        }
    }

    /** استيراد طلاب من Excel/CSV. تُهمل أرقام البطاقات المكررة (يُضاف الطالب بدون بطاقة). */
    fun importStudents(uri: Uri) {
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { StudentImport.parse(getApplication(), uri) }
                if (rows.isEmpty()) { toast("لم يُعثر على أي صف باسم طالب في الملف"); return@launch }
                val used = db.studentDao().all().map { it.cardUid }.filter { it.isNotBlank() }.toMutableSet()
                var conflicts = 0
                val list = rows.map { r ->
                    var uid = r.cardUid
                    if (uid.isNotBlank() && !used.add(uid)) { uid = ""; conflicts++ }
                    Student(UUID.randomUUID().toString(), r.name, r.grade, uid)
                }
                db.studentDao().upsertAll(list)
                log("استيراد طلاب", "${list.size} طالب من ملف")
                toast("تمت إضافة ${list.size} طالب" + if (conflicts > 0) " (تم تجاهل رقم البطاقة لـ $conflicts بسبب التكرار)" else "")
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "ملف غير صالح")
            } catch (e: Exception) {
                toast("تعذّر قراءة الملف")
            }
        }
    }

    // ---------------- شعار المدرسة وتقارير PDF/Excel ----------------
    fun setLogo(uri: Uri) {
        viewModelScope.launch {
            val p = importPhoto(uri)
            if (p.isBlank()) { toast("تعذّر قراءة الصورة"); return@launch }
            val old = settings.value.logoPath
            settingsStore.save(settings.value.copy(logoPath = p))
            if (old.isNotBlank()) File(old).delete()
            log("تغيير شعار المدرسة")
            toast("تم حفظ الشعار")
        }
    }
    fun clearLogo() {
        val old = settings.value.logoPath
        settingsStore.save(settings.value.copy(logoPath = ""))
        if (old.isNotBlank()) File(old).delete()
        log("حذف شعار المدرسة")
    }

    fun exportMonthlyPdf(uri: Uri, month: String, group: String, rows: List<MonthRow>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = settings.value
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    ReportPdf.monthly(it, st.schoolName, st.logoPath, month, group, rows)
                }
                toast("تم تصدير تقرير PDF")
            } catch (e: Exception) { toast("تعذّر تصدير PDF") }
        }
    }

    fun exportMonthlyXlsx(uri: Uri, month: String, group: String, rows: List<MonthRow>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val st = settings.value
                val data = mutableListOf<List<Any?>>(
                    listOf(st.schoolName), listOf("التقرير الشهري للحضور — $month" + if (group.isNotBlank()) " — $group" else ""), listOf(),
                    listOf("#", "اسم الطالب", "الصف", "عدد الحصص", "حاضر", "متأخر", "غائب", "نسبة الحضور")
                )
                rows.forEachIndexed { i, r ->
                    data.add(listOf(i + 1, r.name, r.grade, r.total, r.present, r.late, r.absent, if (r.total > 0) "${r.pct}%" else "-"))
                }
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    XlsxWriter.write(it, "التقرير الشهري", data, listOf(6, 30, 18, 12, 10, 10, 10, 14), setOf(0, 1, 3))
                }
                toast("تم تصدير ملف Excel")
            } catch (e: Exception) { toast("تعذّر تصدير Excel") }
        }
    }

    // ---------------- النسخ الاحتياطي التلقائي ----------------
    fun autoBackupFiles() = AutoBackup.list(getApplication())
    fun autoBackupDir() = AutoBackup.dir(getApplication()).absolutePath
    fun backupNow(onDone: () -> Unit) {
        viewModelScope.launch {
            try {
                val dest = java.io.File(AutoBackup.dir(getApplication()), "backup-${java.time.LocalDate.now()}.json")
                withContext(Dispatchers.IO) { dest.writeText(Backup.export(db, settings.value, getApplication()), Charsets.UTF_8) }
                AutoBackup.prune(getApplication(), settings.value.backupDays)
                toast("تم حفظ نسخة اليوم")
            } catch (e: Exception) { toast("تعذّر حفظ النسخة") }
            onDone()
        }
    }

    fun deleteStudent(id: String) {
        val st = students.value.find { it.id == id }
        viewModelScope.launch {
            db.studentDao().delete(id)
            log("حذف طالب", st?.let { "${it.name} — ${it.grade}" } ?: id)
        }
    }

    suspend fun importPhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val ctx = getApplication<Application>()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > 1024) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext ""
            val scale = 256f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true)
            else bmp
            val f = File(ctx.filesDir, "photo_${UUID.randomUUID()}.jpg")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f.absolutePath
        } catch (e: Exception) { "" }
    }

    // ---------------- الجدول ----------------
    fun saveSchedule(item: ScheduleItem) {
        viewModelScope.launch {
            val owner = item.ownerId.ifBlank { _currentUser.value?.id ?: "" }
            val old = schedule.value.find { it.id == item.id }
            db.scheduleDao().upsert(item.copy(ownerId = owner))
            val what = "${DAY_NAMES[item.day]} ${item.startTime} — ${item.subject}"
            log(if (old == null) "إضافة حصة" else "تعديل حصة", if (old != null && "${DAY_NAMES[old.day]} ${old.startTime} — ${old.subject}" != what) "${DAY_NAMES[old.day]} ${old.startTime} — ${old.subject} ← $what" else what)
        }
    }
    fun deleteSchedule(id: String) {
        val it0 = schedule.value.find { it.id == id }
        viewModelScope.launch {
            db.scheduleDao().delete(id)
            log("حذف حصة", it0?.let { "${DAY_NAMES[it.day]} ${it.startTime} — ${it.subject}" } ?: id)
        }
    }

    // ---------------- السجل والتقارير ----------------
    private fun visibleOnly(rows: Flow<List<Attendance>>): Flow<List<Attendance>> =
        combine(rows, sessions) { list, ses -> val ids = ses.map { it.id }.toSet(); list.filter { it.sessionId in ids } }

    fun attendanceByDate(date: String): Flow<List<Attendance>> = visibleOnly(db.attendanceDao().observeByDate(date))
    fun attendanceByMonth(month: String): Flow<List<Attendance>> = visibleOnly(db.attendanceDao().observeByMonth(month))
    fun deleteAttendance(id: String, label: String = "") {
        viewModelScope.launch { db.attendanceDao().delete(id); log("حذف سجل حضور", label) }
    }

    fun writeCsv(uri: Uri, rows: List<Attendance>) = writeText(uri, buildCsv(rows))

    fun writeText(uri: Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                toast("تم تصدير الملف")
            } catch (e: Exception) { toast("تعذّر التصدير") }
        }
    }

    // ---------------- النسخ الاحتياطي (للمدير) ----------------
    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = Backup.export(db, settings.value, getApplication())
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                log("تصدير نسخة احتياطية")
                toast("تم تصدير النسخة الاحتياطية")
            } catch (e: Exception) { toast("تعذّر تصدير النسخة الاحتياطية") }
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } ?: throw IllegalArgumentException("تعذّر قراءة الملف")
                val st = Backup.restore(db, getApplication(), text)
                settingsStore.save(st)
                ensureAdmin()
                pickedBy.clear(); _scan.value = ScanUi()
                val cu = _currentUser.value
                if (cu != null) {
                    val fresh = db.userDao().byId(cu.id)
                    if (fresh == null) logout() else _currentUser.value = fresh
                }
                log("استيراد نسخة احتياطية", "استبدال كل البيانات")
                toast("تم استيراد النسخة الاحتياطية")
            } catch (e: IllegalArgumentException) {
                toast(e.message ?: "ملف غير صالح")
            } catch (e: Exception) {
                toast("تعذّر استيراد النسخة الاحتياطية")
            }
        }
    }

    // ---------------- الاختيار العشوائي ----------------
    fun pickRandom(): Picked? {
        val ses = activeSession.value ?: return null
        val att = sessionAttendance.value
        if (att.isEmpty()) return null
        val done = pickedBy.getOrPut(ses.id) { mutableSetOf() }
        var pool = att.filter { it.studentId !in done }
        if (pool.isEmpty()) { done.clear(); pool = att }
        val w = pool.random()
        done.add(w.studentId)
        val st = students.value.find { it.id == w.studentId }
        return Picked(w.studentName, w.grade, st?.photoPath ?: "", pool.size - 1)
    }
}

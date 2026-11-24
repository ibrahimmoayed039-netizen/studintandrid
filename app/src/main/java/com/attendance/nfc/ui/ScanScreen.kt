package com.attendance.nfc.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.attendance.nfc.*
import com.attendance.nfc.data.*
import kotlinx.coroutines.delay
import java.time.LocalDateTime

data class Counts(val present: Int, val late: Int, val absent: Int)

fun counts(ses: LectureSession, students: List<Student>, att: List<Attendance>): Counts {
    val target = students.filter { ses.grp.isBlank() || it.grade == ses.grp }
    val ids = att.map { it.studentId }.toSet()
    return Counts(att.count { it.status == "present" }, att.count { it.status == "late" }, target.count { it.id !in ids })
}

fun kindColor(k: ScanKind): Color = when (k) {
    ScanKind.OK -> OkC
    ScanKind.LATE -> WarnC
    ScanKind.DUP -> InfoC
    ScanKind.BAD -> BadC
    ScanKind.IDLE -> Pri
}

@Composable
fun ScanScreen(vm: MainViewModel, nfcStatus: Int, onKiosk: () -> Unit) {
    val ses by vm.activeSession.collectAsState()
    val att by vm.sessionAttendance.collectAsState()
    val students by vm.students.collectAsState()
    val schedule by vm.schedule.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val settings by vm.settings.collectAsState()
    val scan by vm.scanState.collectAsState()
    val now by rememberTicker()
    var showPicker by remember { mutableStateOf(false) }
    var confirmEnd by remember { mutableStateOf(false) }
    val s = ses

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (nfcStatus != 0) item { NfcWarning(nfcStatus) }
        if (s == null) {
            idleSection(vm, students, schedule, sessions, settings, now)
        } else {
            activeSection(vm, s, att, students, scan, onKiosk, { confirmEnd = true }, { showPicker = true })
        }
    }

    if (confirmEnd && s != null) {
        val c = counts(s, students, att)
        ConfirmDialog("إنهاء المحاضرة؟ عدد من لم يحضر: ${c.absent}", "إنهاء",
            onOk = { vm.endSession(); confirmEnd = false }, onDismiss = { confirmEnd = false })
    }
    if (showPicker) PickerDialog(vm) { showPicker = false }
}

@Composable
private fun NfcWarning(status: Int) {
    val ctx = LocalContext.current
    GlassCard {
        Text(if (status == 1) "هذا الجهاز لا يدعم NFC" else "NFC مُعطّل — فعّله لتتمكن من قراءة البطاقات", fontWeight = FontWeight.Bold, color = WarnC)
        if (status == 2) Btn3D("فتح إعدادات NFC", { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }, color = WarnC)
    }
}

// ============================ لا توجد محاضرة نشطة ============================
private fun LazyListScope.idleSection(
    vm: MainViewModel, students: List<Student>, schedule: List<ScheduleItem>,
    sessions: List<LectureSession>, settings: AppSettings, now: LocalDateTime
) {
    val today = now.toLocalDate().toString()
    val day = now.dayOfWeek.value % 7
    val list = schedule.filter { it.day == day }.sortedBy { it.startTime }

    if (settings.autoSchedule) item { BreakBanner(list.filter { it.auto }, settings, now) }

    item { StartCard(vm, students, settings) }

    item { SectionTitle("حصص اليوم — ${DAY_NAMES[day]}") }
    if (list.isEmpty()) item { Text("لا توجد حصص اليوم. أضفها من تبويب «الجدول».", color = Mut) }
    list.forEachIndexed { i, it ->
        item(key = it.id) {
            val prev = list.getOrNull(i - 1)
            val gap = if (prev != null) toMin(it.startTime) - toMin(entryEnd(prev, settings.lectureMin)) else 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (prev != null && gap > 0) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Badge("☕ استراحة $gap د (${entryEnd(prev, settings.lectureMin)} ← ${it.startTime})", WarnC)
                    }
                }
                val started = sessions.any { s -> s.scheduleId == it.id && s.date == today }
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it.subject, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text(
                                "${it.startTime} – ${entryEnd(it, settings.lectureMin)} • ${it.grp.ifBlank { "كل الطلاب" }}" +
                                    if (settings.autoSchedule && it.auto) " • ⚡ تلقائي" else "",
                                color = Mut, fontSize = 13.sp
                            )
                        }
                        Btn3D(if (started) "✅ بدأت" else "▶ بدء", { vm.startSession(it.subject, it.grp, it.lateMin, it.id, it.auto) },
                            enabled = !started, small = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakBanner(list: List<ScheduleItem>, settings: AppSettings, now: LocalDateTime) {
    val nm = now.hour * 60 + now.minute
    val prev = list.lastOrNull { toMin(entryEnd(it, settings.lectureMin)) <= nm }
    val next = list.firstOrNull { toMin(it.startTime) > nm } ?: return
    val wait = toMin(next.startTime) - nm
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(Brush.linearGradient(listOf(WarnC.copy(alpha = .45f), Pri2.copy(alpha = .25f))))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(if (prev != null) "☕" else "⏳", fontSize = 32.sp)
        Column {
            Text(if (prev != null) "استراحة" else "المحاضرة القادمة: ${next.subject}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(
                if (prev != null) "تنتهي بعد $wait دقيقة — المحاضرة القادمة «${next.subject}» الساعة ${next.startTime}"
                else "تبدأ تلقائياً الساعة ${next.startTime} (بعد $wait دقيقة)",
                color = Color(0xFFFFECCB), fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun StartCard(vm: MainViewModel, students: List<Student>, settings: AppSettings) {
    var subject by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf("") }
    var late by rememberSaveable { mutableStateOf("") }
    val grades = students.map { it.grade }.filter { it.isNotBlank() }.distinct()
    GlassCard {
        SectionTitle("بدء محاضرة جديدة")
        AppField(subject, { subject = it }, "اسم المادة")
        AppField(group, { group = it }, "المجموعة / الصف (اختياري)")
        GradeChips(grades, group) { group = it }
        AppField(late, { late = it.filter { c -> c.isDigit() } }, "التأخير بعد (دقيقة) — الافتراضي ${settings.defaultLate}", keyboard = KeyboardType.Number)
        Btn3D("▶ ابدأ المحاضرة", {
            vm.startSession(subject.trim(), group.trim(), late.toIntOrNull() ?: settings.defaultLate)
            subject = ""
        }, Modifier.fillMaxWidth(), enabled = subject.isNotBlank())
    }
}

// ============================ محاضرة نشطة ============================
private fun LazyListScope.activeSection(
    vm: MainViewModel, s: LectureSession, att: List<Attendance>, students: List<Student>,
    scan: ScanUi, onKiosk: () -> Unit, onEnd: () -> Unit, onPick: () -> Unit
) {
    item {
        GlassCard {
            Text(s.subject, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text("${s.grp.ifBlank { "كل الطلاب" }} • بدأت ${s.startTime} • تأخير بعد ${s.lateMin} د", color = Mut, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn3D("⛶ ملء الشاشة", onKiosk, Modifier.weight(1f), small = true)
                Btn3D("⏹ إنهاء", onEnd, Modifier.weight(1f), color = BadC, small = true)
            }
        }
    }
    item { ResultCard(scan) }
    item {
        val c = counts(s, students, att)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatBox(c.present, "حاضر", OkC, Modifier.weight(1f))
            StatBox(c.late, "متأخر", WarnC, Modifier.weight(1f))
            StatBox(c.absent, "لم يحضر بعد", BadC, Modifier.weight(1f))
        }
    }
    item { Btn3D("🎲 اختيار طالب عشوائي من الحاضرين", onPick, Modifier.fillMaxWidth()) }
    item { ManualCard(vm, s, att, students) }
    item { SectionTitle("آخر التسجيلات") }
    if (att.isEmpty()) item { Text("لا توجد تسجيلات بعد", color = Mut) }
    items(att.take(30), key = { it.id }) { a ->
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(a.studentName, fontWeight = FontWeight.Bold)
                    Text("${a.time} • ${a.grade}", color = Mut, fontSize = 12.sp)
                }
                if (a.manual) Badge("يدوي", InfoC)
                Badge(if (a.status == "late") "متأخر" else "حاضر", if (a.status == "late") WarnC else OkC)
            }
        }
    }
}

@Composable
private fun ResultCard(scan: ScanUi) {
    val color by animateColorAsState(kindColor(scan.kind), label = "resultColor")
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(Color(0xFF161D42))
            .background(Brush.linearGradient(listOf(color.copy(alpha = if (scan.kind == ScanKind.IDLE) .18f else .6f), color.copy(alpha = .12f))))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val icon = when (scan.kind) { ScanKind.OK -> "✓"; ScanKind.LATE -> "⏰"; ScanKind.DUP -> "↺"; ScanKind.BAD -> "✕"; ScanKind.IDLE -> "📇" }
        StudentAvatar(scan.photoPath, 110.dp, fallback = icon, ringColor = color)
        Text(
            if (scan.kind == ScanKind.IDLE) "قرّب البطاقة من ظهر الهاتف" else scan.title,
            fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
        )
        if (scan.sub.isNotBlank()) Text(scan.sub, color = Color(0xFFE9ECFF), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ManualCard(vm: MainViewModel, s: LectureSession, att: List<Attendance>, students: List<Student>) {
    var q by rememberSaveable { mutableStateOf("") }
    val ids = att.map { it.studentId }.toSet()
    val pool = students.filter {
        (s.grp.isBlank() || it.grade == s.grp) && it.id !in ids &&
            (q.isBlank() || it.name.contains(q, true) || it.grade.contains(q, true))
    }
    GlassCard {
        SectionTitle("✋ حضور يدوي (لمن نسي بطاقته)")
        AppField(q, { q = it }, "ابحث بالاسم أو الصف")
        if (pool.isEmpty()) Text(if (q.isBlank()) "كل الطلاب مسجّلون ✅" else "لا توجد نتائج", color = Mut)
        pool.take(6).forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StudentAvatar(st.photoPath, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(st.name, fontWeight = FontWeight.SemiBold)
                    Text(st.grade, color = Mut, fontSize = 12.sp)
                }
                Btn3D("✓ حاضر", { vm.manualAttend(st) }, small = true)
            }
        }
        if (pool.size > 6) Text("يُعرض 6 من ${pool.size} — اكتب للبحث", color = Mut, fontSize = 12.sp)
    }
}

// ============================ اختيار عشوائي ============================
@Composable
fun PickerDialog(vm: MainViewModel, onClose: () -> Unit) {
    var shown by remember { mutableStateOf("🎲") }
    var result by remember { mutableStateOf<Picked?>(null) }
    var round by remember { mutableIntStateOf(0) }

    LaunchedEffect(round) {
        val names = vm.sessionAttendance.value.map { it.studentName }
        if (names.isEmpty()) { vm.showToast("لا يوجد طلاب حاضرون بعد"); onClose(); return@LaunchedEffect }
        result = null
        val winner = vm.pickRandom()
        for (i in 1..22) { shown = names.random(); delay(60L + (i * i * 0.55).toLong()) }
        result = winner
    }

    Dialog(onDismissRequest = onClose) {
        GlassCard {
            Text("🎲 اختيار طالب عشوائي", color = Mut, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StudentAvatar(result?.photoPath ?: "", 130.dp, fallback = if (result == null) "🎲" else "🎉", ringColor = if (result == null) Pri else WarnC)
            }
            Text(
                result?.name ?: shown, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                color = if (result == null) Txt else Color(0xFFFFD27A), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Text(
                result?.let { "${it.grade} — متبقٍ ${it.remaining} لم يُختاروا" } ?: " ",
                color = Mut, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn3D("🎲 اختيار آخر", { round++ }, Modifier.weight(1f), enabled = result != null)
                Btn3D("إغلاق", onClose, Modifier.weight(1f), color = Color(0xFF4A5180))
            }
        }
    }
}

// ============================ وضع ملء الشاشة ============================
@Composable
fun KioskScreen(vm: MainViewModel, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val ses by vm.activeSession.collectAsState()
    val att by vm.sessionAttendance.collectAsState()
    val students by vm.students.collectAsState()
    val scan by vm.scanState.collectAsState()
    val settings by vm.settings.collectAsState()
    val now by rememberTicker()
    var showPicker by remember { mutableStateOf(false) }
    val view = LocalView.current

    DisposableEffect(Unit) {
        val window = view.context.findActivity()?.window
        val ctrl = window?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctrl?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // إذا انتهت المحاضرة تلقائياً نخرج من الوضع
    LaunchedEffect(ses) { if (ses == null) onExit() }

    val s = ses ?: return
    val color by animateColorAsState(kindColor(scan.kind), label = "kioskColor")
    val c = counts(s, students, att)
    val clock = "%02d:%02d".format(now.hour, now.minute)

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF080C1F), Color(0xFF141B3D))))
            .background(Brush.radialGradient(listOf(color.copy(alpha = .45f), Color.Transparent)))
            .padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column {
                Text(settings.schoolName, color = Mut, fontSize = 14.sp)
                Text(s.subject, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text("${s.grp.ifBlank { "كل الطلاب" }} • بدأت ${s.startTime}", color = Mut, fontSize = 13.sp)
            }
            Text(clock, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Txt)
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val icon = when (scan.kind) { ScanKind.OK -> "✓"; ScanKind.LATE -> "⏰"; ScanKind.DUP -> "↺"; ScanKind.BAD -> "✕"; ScanKind.IDLE -> "📇" }
            StudentAvatar(scan.photoPath, 190.dp, fallback = icon, ringColor = color)
            Text(
                if (scan.kind == ScanKind.IDLE) "مرّر بطاقتك على ظهر الهاتف" else scan.title,
                fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
            )
            Text(scan.sub, fontSize = 20.sp, color = Txt, textAlign = TextAlign.Center)
        }
        Column(Modifier.align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // المهلة المتبقية قبل احتساب التأخير + آخر 4 طلاب سُجّلوا
            val lateLeft = s.lateMin - ((System.currentTimeMillis() - s.startTs) / 60_000L).toInt()
            Text(
                if (lateLeft > 0) "⏳ باقي $lateLeft د قبل احتساب التأخير" else "⏰ انتهت مهلة الحضور — التسجيل الآن «متأخر»",
                color = if (lateLeft > 0) Mut else WarnC, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
            )
            if (att.isNotEmpty()) {
                Text(
                    "آخر المسجّلين: " + att.take(4).joinToString(" • ") { it.studentName },
                    color = Mut, fontSize = 13.sp, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox(c.present, "حاضر", OkC, Modifier.weight(1f))
                StatBox(c.late, "متأخر", WarnC, Modifier.weight(1f))
                StatBox(c.absent, "لم يحضر", BadC, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn3D("🎲 اختيار عشوائي", { showPicker = true }, Modifier.weight(1f))
                Btn3D("✕ خروج", onExit, Modifier.weight(1f), color = Color(0xFF4A5180))
            }
        }
    }
    if (showPicker) PickerDialog(vm) { showPicker = false }
}

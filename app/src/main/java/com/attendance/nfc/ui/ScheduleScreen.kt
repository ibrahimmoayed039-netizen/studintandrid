package com.attendance.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.*
import com.attendance.nfc.data.ScheduleItem
import java.util.UUID

@Composable
fun ScheduleScreen(vm: MainViewModel) {
    val schedule by vm.schedule.collectAsState()
    val students by vm.students.collectAsState()
    val settings by vm.settings.collectAsState()
    var editing by remember { mutableStateOf<ScheduleItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ScheduleItem?>(null) }
    val grades = students.map { it.grade }.filter { it.isNotBlank() }.distinct()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الجدول الأسبوعي", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Btn3D("➕ إضافة حصة", { creating = true })
            }
        }
        item {
            Text(
                if (settings.autoSchedule) "⚡ التشغيل التلقائي مفعّل: تبدأ الحصص وتنتهي وحدها حسب هذا الجدول." else "التشغيل التلقائي معطّل — فعّله من الإعدادات.",
                color = Mut, fontSize = 13.sp
            )
        }
        if (schedule.isEmpty()) item { Text("لا توجد حصص بعد.", color = Mut) }
        (0..6).forEach { d ->
            val dayItems = schedule.filter { it.day == d }
            if (dayItems.isNotEmpty()) {
                item(key = "h$d") { SectionTitle(DAY_NAMES[d]) }
                items(dayItems, key = { it.id }) { s ->
                    GlassCard {
                        Text(s.subject, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(
                            "${s.startTime} – ${entryEnd(s, settings.lectureMin)} • ${s.grp.ifBlank { "كل الطلاب" }} • تأخير بعد ${s.lateMin} د" +
                                if (s.auto) " • ⚡" else "",
                            color = Mut, fontSize = 13.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Btn3D("تعديل", { editing = s }, Modifier.weight(1f), small = true)
                            Btn3D("حذف", { deleting = s }, Modifier.weight(1f), color = BadC, small = true)
                        }
                    }
                }
            }
        }
    }

    if (creating) ScheduleDialog(vm, null, grades, schedule, settings.lectureMin, settings.breakMin, settings.defaultLate) { creating = false }
    editing?.let { ScheduleDialog(vm, it, grades, schedule, settings.lectureMin, settings.breakMin, settings.defaultLate) { editing = null } }
    deleting?.let { s ->
        ConfirmDialog("حذف حصة «${s.subject}»؟", "حذف", onOk = { vm.deleteSchedule(s.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun ScheduleDialog(
    vm: MainViewModel, initial: ScheduleItem?, grades: List<String>, all: List<ScheduleItem>,
    lectureMin: Int, breakMin: Int, defaultLate: Int, onDismiss: () -> Unit
) {
    var day by remember { mutableIntStateOf(initial?.day ?: 0) }
    var subject by remember { mutableStateOf(initial?.subject ?: "") }
    var grp by remember { mutableStateOf(initial?.grp ?: "") }
    var start by remember { mutableStateOf(initial?.startTime ?: "") }
    var end by remember { mutableStateOf(initial?.endTime ?: "") }
    var late by remember { mutableStateOf((initial?.lateMin ?: defaultLate).toString()) }
    var auto by remember { mutableStateOf(initial?.auto ?: true) }
    var error by remember { mutableStateOf("") }

    // اقتراح وقت البدء = نهاية آخر حصة في اليوم + الاستراحة
    fun suggest(d: Int) {
        if (initial != null) return
        val last = all.filter { it.day == d }.maxByOrNull { it.startTime }
        start = if (last != null) fromMin(toMin(entryEnd(last, lectureMin)) + breakMin) else ""
    }

    // أول حصة في نفس اليوم يتداخل وقتها مع الحصة الحالية (التلامس عند الحافة مسموح)
    fun conflict(): ScheduleItem? {
        if (start.isBlank()) return null
        val s1 = toMin(start)
        val e1 = if (end.isNotBlank()) toMin(end) else s1 + lectureMin
        return all.filter { it.day == day && it.id != initial?.id }.sortedBy { toMin(it.startTime) }.firstOrNull { o ->
            val s2 = toMin(o.startTime)
            val e2 = toMin(entryEnd(o, lectureMin))
            s1 < e2 && s2 < e1
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        title = { Text(if (initial == null) "إضافة حصة" else "تعديل حصة") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(7) { d -> FilterChip(selected = day == d, onClick = { day = d; suggest(d) }, label = { Text(DAY_NAMES[d]) }) }
                }
                AppField(subject, { subject = it }, "اسم المادة")
                AppField(grp, { grp = it }, "المجموعة / الصف (اختياري)")
                GradeChips(grades, grp) { grp = it }
                TimeField("البدء", start, { start = it }, Modifier.fillMaxWidth())
                TimeField("الانتهاء (اختياري)", end, { end = it }, Modifier.fillMaxWidth(), clearable = true)
                Text("إن تُرك الانتهاء فارغاً يُحسب بمدة الحصة الافتراضية ($lectureMin د).", color = Mut, fontSize = 12.sp)
                AppField(late, { late = it.filter { c -> c.isDigit() } }, "التأخير بعد (دقيقة)", keyboard = KeyboardType.Number)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("بدء وإنهاء تلقائي لهذه الحصة", modifier = Modifier.weight(1f))
                    Switch(checked = auto, onCheckedChange = { auto = it })
                }
                conflict()?.let { c ->
                    Text("⚠️ يتعارض مع «${c.subject}» (${c.startTime} – ${entryEnd(c, lectureMin)})", color = WarnC, fontSize = 13.sp)
                }
                if (error.isNotBlank()) Text(error, color = BadC)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    subject.isBlank() -> error = "أدخل اسم المادة"
                    start.isBlank() -> error = "اختر وقت البدء"
                    end.isNotBlank() && toMin(end) <= toMin(start) -> error = "وقت الانتهاء يجب أن يكون بعد البدء"
                    conflict() != null -> {
                        val c = conflict()!!
                        error = "تعارض مع حصة «${c.subject}» يوم ${DAY_NAMES[c.day]} (${c.startTime} – ${entryEnd(c, lectureMin)}). غيّر الوقت أو اليوم."
                    }
                    else -> {
                        vm.saveSchedule(ScheduleItem(initial?.id ?: UUID.randomUUID().toString(), day, subject.trim(), grp.trim(), start, end, late.toIntOrNull() ?: defaultLate, auto, initial?.ownerId ?: ""))
                        onDismiss()
                    }
                }
            }) { Text("حفظ", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

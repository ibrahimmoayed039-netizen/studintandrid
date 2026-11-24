package com.attendance.nfc.ui

import android.app.DatePickerDialog as AndroidDatePicker
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.Attendance
import java.time.LocalDate

@Composable
fun LogScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var q by rememberSaveable { mutableStateOf("") }
    var selSession by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Attendance?>(null) }
    val rows by remember(date) { vm.attendanceByDate(date) }.collectAsState(initial = emptyList())
    val sessions by vm.sessions.collectAsState()
    val students by vm.students.collectAsState()
    val me by vm.currentUser.collectAsState()
    val daySessions = sessions.filter { it.date == date }

    val filtered = rows.filter {
        (selSession.isBlank() || it.sessionId == selSession) && (q.isBlank() || it.studentName.contains(q, true))
    }
    // ---- تقسيم السجلات إلى صفحات لتبقى الشاشة سريعة مهما كثر عدد السجلات ----
    var page by rememberSaveable(date, q, selSession) { mutableStateOf(0) }
    val pages = maxOf(1, (filtered.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val cur = page.coerceIn(0, pages - 1)
    val pageRows = filtered.drop(cur * PAGE_SIZE).take(PAGE_SIZE)
    var absPage by rememberSaveable(date, selSession) { mutableStateOf(0) }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.writeCsv(uri, filtered)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn3D("▶", { date = LocalDate.parse(date).plusDays(1).toString(); selSession = "" }, small = true)
                OutlinedButton(onClick = {
                    val d = LocalDate.parse(date)
                    AndroidDatePicker(ctx, AndroidDatePicker.OnDateSetListener { _, y, m, day -> date = LocalDate.of(y, m + 1, day).toString(); selSession = "" },
                        d.year, d.monthValue - 1, d.dayOfMonth).show()
                }, modifier = Modifier.weight(1f)) { Text("📅 $date") }
                Btn3D("◀", { date = LocalDate.parse(date).minusDays(1).toString(); selSession = "" }, small = true)
            }
        }
        item { AppField(q, { q = it }, "🔍 بحث بالاسم") }
        if (daySessions.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = selSession.isBlank(), onClick = { selSession = "" }, label = { Text("كل المحاضرات") }) }
                items(daySessions, key = { it.id }) { s ->
                    FilterChip(selected = selSession == s.id, onClick = { selSession = s.id }, label = { Text("${s.subject} ${s.startTime}" + if (me?.role == "admin" && s.createdByName.isNotBlank()) " — ${s.createdByName}" else "") })
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("عدد السجلات: ${filtered.size}", color = Mut, modifier = Modifier.weight(1f))
                Btn3D("⬇ تصدير CSV", { export.launch("attendance_$date.csv") }, enabled = filtered.isNotEmpty(), small = true)
            }
        }
        if (filtered.isEmpty()) item { Text("لا توجد سجلات في هذا اليوم", color = Mut) }
        if (pages > 1) item { PageBar(cur, pages, filtered.size) { page = it } }
        items(pageRows, key = { it.id }) { a ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(a.studentName, fontWeight = FontWeight.Bold)
                        Text("${a.time} • ${a.grade} • ${a.subject}", color = Mut, fontSize = 12.sp)
                    }
                    if (a.manual) Badge("يدوي", InfoC)
                    Badge(if (a.status == "late") "متأخر" else "حاضر", if (a.status == "late") WarnC else OkC)
                }
                Btn3D("حذف السجل", { deleting = a }, Modifier.fillMaxWidth(), color = BadC, small = true)
            }
        }

        if (pages > 1) item { PageBar(cur, pages, filtered.size) { page = it } }

        // الغائبون في المحاضرة المحددة
        val chosen = daySessions.find { it.id == selSession }
        if (chosen != null) {
            val present = rows.filter { it.sessionId == chosen.id }.map { it.studentId }.toSet()
            val absent = students.filter { (chosen.grp.isBlank() || it.grade == chosen.grp) && it.id !in present }
            item { SectionTitle("الغائبون عن «${chosen.subject}» (${absent.size})") }
            if (absent.isEmpty()) item { Text("لا يوجد غائبون 🎉", color = Mut) }
            val absPages = maxOf(1, (absent.size + PAGE_SIZE - 1) / PAGE_SIZE)
            val absCur = absPage.coerceIn(0, absPages - 1)
            if (absPages > 1) item { PageBar(absCur, absPages, absent.size) { absPage = it } }
            items(absent.drop(absCur * PAGE_SIZE).take(PAGE_SIZE), key = { "abs_" + it.id }) { st ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudentAvatar(st.photoPath, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(st.name, fontWeight = FontWeight.SemiBold)
                            Text(st.grade, color = Mut, fontSize = 12.sp)
                        }
                        Badge("غائب", BadC)
                    }
                }
            }
        }
    }

    deleting?.let { a ->
        AuthDialog(vm, "حذف سجل «${a.studentName}» — يتطلب تأكيد المدرّس",
            onGranted = { vm.deleteAttendance(a.id, "${a.studentName} — ${a.subject} ${a.date} ${a.time}"); deleting = null },
            onDismiss = { deleting = null })
    }
}

private const val PAGE_SIZE = 25

/** شريط التنقل بين الصفحات: السابق / رقم الصفحة / التالي. */
@Composable
private fun PageBar(current: Int, pages: Int, total: Int, onPage: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn3D("السابق ›", { onPage(current - 1) }, enabled = current > 0, small = true)
        Text(
            "صفحة ${current + 1} من $pages  ($total)", color = Mut, fontSize = 12.sp,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Btn3D("‹ التالي", { onPage(current + 1) }, enabled = current < pages - 1, small = true)
    }
}

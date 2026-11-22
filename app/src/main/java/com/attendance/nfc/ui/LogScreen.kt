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
        items(filtered, key = { it.id }) { a ->
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

        // الغائبون في المحاضرة المحددة
        val chosen = daySessions.find { it.id == selSession }
        if (chosen != null) {
            val present = rows.filter { it.sessionId == chosen.id }.map { it.studentId }.toSet()
            val absent = students.filter { (chosen.grp.isBlank() || it.grade == chosen.grp) && it.id !in present }
            item { SectionTitle("الغائبون عن «${chosen.subject}» (${absent.size})") }
            if (absent.isEmpty()) item { Text("لا يوجد غائبون 🎉", color = Mut) }
            items(absent, key = { "abs_" + it.id }) { st ->
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
        ConfirmDialog("حذف سجل «${a.studentName}»؟", "حذف", onOk = { vm.deleteAttendance(a.id, "${a.studentName} — ${a.subject} ${a.date} ${a.time}"); deleting = null }, onDismiss = { deleting = null })
    }
}

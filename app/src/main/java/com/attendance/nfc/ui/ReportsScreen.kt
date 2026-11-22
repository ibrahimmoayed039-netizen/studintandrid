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
import com.attendance.nfc.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun ReportsScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val me by vm.currentUser.collectAsState()
    val sessions by vm.sessions.collectAsState()
    val students by vm.students.collectAsState()

    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var selSession by rememberSaveable { mutableStateOf("") }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var group by rememberSaveable { mutableStateOf("") }
    var pendingText by remember { mutableStateOf("") }

    val dayRows by remember(date) { vm.attendanceByDate(date) }.collectAsState(initial = emptyList())
    val monthRows by remember(month) { vm.attendanceByMonth(month) }.collectAsState(initial = emptyList())

    val daySessions = sessions.filter { it.date == date }.sortedBy { it.startTs }
    val chosen = daySessions.find { it.id == selSession } ?: daySessions.firstOrNull()
    val grades = students.map { it.grade }.filter { it.isNotBlank() }.distinct().sorted()
    val monthStats = monthlyStats(month, group, students, sessions, monthRows)

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.writeText(uri, pendingText)
    }

    val exportPdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.exportMonthlyPdf(uri, month, group, monthStats)
    }
    val exportXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri != null) vm.exportMonthlyXlsx(uri, month, group, monthStats)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ======================= تقرير اليوم والمحاضرة =======================
        item { SectionTitle("تقرير المحاضرة والغياب") }
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
        if (daySessions.isEmpty()) {
            item { Text("لا توجد محاضرات في هذا اليوم", color = Mut) }
        } else {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(daySessions, key = { it.id }) { s ->
                        FilterChip(
                            selected = chosen?.id == s.id, onClick = { selSession = s.id },
                            label = { Text("${s.subject} ${s.startTime}" + if (me?.role == "admin" && s.createdByName.isNotBlank()) " — ${s.createdByName}" else "") }
                        )
                    }
                }
            }
        }
        if (chosen != null) {
            val rows = dayRows.filter { it.sessionId == chosen.id }
            val ids = rows.map { it.studentId }.toSet()
            val absent = students.filter { (chosen.grp.isBlank() || it.grade == chosen.grp) && it.id !in ids }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBox(rows.count { it.status == "present" }, "حاضر", OkC, Modifier.weight(1f))
                    StatBox(rows.count { it.status == "late" }, "متأخر", WarnC, Modifier.weight(1f))
                    StatBox(absent.size, "غائب", BadC, Modifier.weight(1f))
                }
            }
            item {
                Btn3D("⬇ تصدير حضور المحاضرة CSV", {
                    pendingText = buildCsv(rows); export.launch("attendance_${chosen.date}_${chosen.subject}.csv")
                }, Modifier.fillMaxWidth(), enabled = rows.isNotEmpty(), small = true)
            }
            item { Text("قائمة الغائبين (${absent.size})", fontWeight = FontWeight.Bold) }
            if (absent.isEmpty()) item { Text("لا يوجد غائبون 🎉", color = Mut) }
            items(absent, key = { "abs_" + it.id }) { st ->
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StudentAvatar(st.photoPath, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(st.name, fontWeight = FontWeight.SemiBold)
                            Text(st.grade, color = Mut, fontSize = 12.sp)
                        }
                        Btn3D("تسجيل حضور", { vm.markPresent(chosen, st) }, small = true)
                    }
                }
            }
        }

        // ======================= التقرير الشهري =======================
        item { Spacer(Modifier.height(8.dp)); SectionTitle("تقرير شهري لكل طالب") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn3D("▶", { month = YearMonth.parse(month).plusMonths(1).toString() }, small = true)
                Text(month, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Btn3D("◀", { month = YearMonth.parse(month).minusMonths(1).toString() }, small = true)
            }
        }
        item { GradeChips(grades, group) { group = it } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn3D("📄 PDF للطباعة", { exportPdf.launch("monthly_$month.pdf") }, Modifier.weight(1f), enabled = monthStats.isNotEmpty(), small = true, color = BadC)
                Btn3D("📊 Excel", { exportXlsx.launch("monthly_$month.xlsx") }, Modifier.weight(1f), enabled = monthStats.isNotEmpty(), small = true, color = OkC)
                Btn3D("CSV", {
                    pendingText = buildMonthlyCsv(monthStats); export.launch("monthly_$month.csv")
                }, Modifier.weight(1f), enabled = monthStats.isNotEmpty(), small = true)
            }
        }
        if (monthStats.isEmpty()) item { Text("لا توجد بيانات لهذا الشهر", color = Mut) }
        items(monthStats.size) { i ->
            val r = monthStats[i]
            val c = when { r.total == 0 -> Mut; r.pct >= 90 -> OkC; r.pct >= 75 -> WarnC; else -> BadC }
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("${i + 1}. ${r.name}", fontWeight = FontWeight.Bold)
                        Text(r.grade.ifBlank { "بدون صف" }, color = Mut, fontSize = 12.sp)
                    }
                    Badge(if (r.total > 0) "${r.pct}%" else "—", c)
                }
                Text("الحصص ${r.total} • حاضر ${r.present} • متأخر ${r.late} • غائب ${r.absent}", color = Mut, fontSize = 13.sp)
            }
        }
    }
}

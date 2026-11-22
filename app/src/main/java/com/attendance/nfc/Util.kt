package com.attendance.nfc

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.attendance.nfc.data.Attendance
import com.attendance.nfc.data.LectureSession
import com.attendance.nfc.data.ScheduleItem
import com.attendance.nfc.data.Student

val DAY_NAMES = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

fun toMin(t: String): Int {
    val p = t.split(":")
    return (p.getOrNull(0)?.trim()?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.trim()?.toIntOrNull() ?: 0)
}

fun fromMin(n: Int): String {
    val m = ((n % 1440) + 1440) % 1440
    return "%02d:%02d".format(m / 60, m % 60)
}

fun entryEnd(item: ScheduleItem, lectureMin: Int): String =
    if (item.endTime.isNotBlank()) item.endTime else fromMin(toMin(item.startTime) + lectureMin)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun buildCsv(rows: List<Attendance>): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("\uFEFF")
    sb.append("الاسم,الصف,المادة,التاريخ,الوقت,الحالة,يدوي\n")
    rows.forEach {
        sb.append(listOf(q(it.studentName), q(it.grade), q(it.subject), it.date, it.time,
            if (it.status == "late") "متأخر" else "حاضر", if (it.manual) "نعم" else "لا").joinToString(","))
        sb.append("\n")
    }
    return sb.toString()
}

data class MonthRow(val name: String, val grade: String, val total: Int, val present: Int, val late: Int, val absent: Int, val pct: Int)

/** نفس معادلة نسخة الحاسوب: الحصص المنطبقة على الطالب = حصص مجموعته أو حصص بلا مجموعة. */
fun monthlyStats(
    month: String, group: String, students: List<Student>, sessions: List<LectureSession>, rows: List<Attendance>
): List<MonthRow> {
    val monthSessions = sessions.filter { it.date.take(7) == month }
    val byKey = rows.associateBy { it.sessionId + "|" + it.studentId }
    return students.filter { group.isBlank() || it.grade == group }.map { s ->
        val applicable = monthSessions.filter { it.grp.isBlank() || it.grp == s.grade }
        val recs = applicable.mapNotNull { byKey[it.id + "|" + s.id] }
        val present = recs.count { it.status == "present" }
        val late = recs.count { it.status == "late" }
        val total = applicable.size
        val pct = if (total > 0) Math.round((present + late) * 100f / total) else 0
        MonthRow(s.name, s.grade, total, present, late, total - present - late, pct)
    }
}

fun buildMonthlyCsv(rows: List<MonthRow>): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("\uFEFF")
    sb.append("الاسم,الصف,عدد الحصص,حاضر,متأخر,غائب,نسبة الحضور\n")
    rows.forEach { sb.append("${q(it.name)},${q(it.grade)},${it.total},${it.present},${it.late},${it.absent},${if (it.total > 0) "${it.pct}%" else "-"}\n") }
    return sb.toString()
}

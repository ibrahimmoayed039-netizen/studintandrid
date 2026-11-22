package com.attendance.nfc.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.AuditEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm")
private fun fmt(ts: Long) = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(FMT)

private fun actionColor(a: String) = when {
    a.contains("حذف") -> BadC
    a.contains("إنشاء") || a.contains("إضافة") || a.contains("استيراد") -> OkC
    a.contains("دخول") || a.contains("خروج") -> InfoC
    a.contains("كلمة") || a.contains("استرجاع") -> WarnC
    else -> Pri
}

@Composable
fun AuditScreen(vm: MainViewModel) {
    val all by vm.audit.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var confirmPurge by remember { mutableStateOf(false) }
    val users = all.map { it.userName }.distinct().sorted()
    val filtered = all.filter {
        (user.isBlank() || it.userName == user) &&
            (q.isBlank() || it.action.contains(q, true) || it.detail.contains(q, true) || it.userName.contains(q, true))
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) vm.writeText(uri, auditCsv(filtered))
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("سجل التدقيق") }
        item { Text("يسجّل من أضاف أو عدّل أو حذف ماذا ومتى (الطلاب، الحضور، الجدول، المستخدمين، الإعدادات، النسخ الاحتياطي).", color = Mut, fontSize = 12.sp) }
        item { AppField(q, { q = it }, "🔍 بحث في السجل") }
        if (users.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = user.isBlank(), onClick = { user = "" }, label = { Text("كل المستخدمين") }) }
                items(users) { u -> FilterChip(selected = user == u, onClick = { user = if (user == u) "" else u }, label = { Text(u) }) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("العدد: ${filtered.size}", color = Mut, modifier = Modifier.weight(1f))
                Btn3D("⬇ CSV", { export.launch("audit_log.csv") }, enabled = filtered.isNotEmpty(), small = true)
                Btn3D("🗑 حذف الأقدم", { confirmPurge = true }, color = BadC, small = true)
            }
        }
        if (filtered.isEmpty()) item { Text("لا توجد سجلات.", color = Mut) }
        items(filtered.take(300)) { e -> AuditRow(e) }
        if (filtered.size > 300) item { Text("يُعرض أحدث 300 من ${filtered.size} — استخدم البحث أو التصدير.", color = Mut, fontSize = 12.sp) }
    }

    if (confirmPurge) {
        ConfirmDialog("حذف سجلات التدقيق الأقدم من 90 يوماً؟", "حذف",
            onOk = { vm.purgeAudit(90); confirmPurge = false }, onDismiss = { confirmPurge = false })
    }
}

@Composable
private fun AuditRow(e: AuditEntry) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Badge(e.action, actionColor(e.action))
            Text(e.userName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(fmt(e.ts), color = Mut, fontSize = 11.sp)
        }
        if (e.detail.isNotBlank()) Text(e.detail, fontSize = 13.sp)
    }
}

private fun auditCsv(rows: List<AuditEntry>): String {
    fun q(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("﻿").append("الوقت,المستخدم,الإجراء,التفاصيل\n")
    rows.forEach { sb.append("${q(fmt(it.ts))},${q(it.userName)},${q(it.action)},${q(it.detail)}\n") }
    return sb.toString()
}

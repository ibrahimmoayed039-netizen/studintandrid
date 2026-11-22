package com.attendance.nfc.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.AppSettings
import java.time.LocalDate

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    var school by remember(s) { mutableStateOf(s.schoolName) }
    var auto by remember(s) { mutableStateOf(s.autoSchedule) }
    var lecture by remember(s) { mutableStateOf(s.lectureMin.toString()) }
    var brk by remember(s) { mutableStateOf(s.breakMin.toString()) }
    var late by remember(s) { mutableStateOf(s.defaultLate.toString()) }
    var bkDays by remember(s) { mutableStateOf(s.backupDays.toString()) }
    var bkTick by remember { mutableStateOf(0) }
    var restoreFile by remember { mutableStateOf<java.io.File?>(null) }

    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    val logoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setLogo(uri) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pendingImport = uri
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard {
            SectionTitle("الإعدادات العامة")
            AppField(school, { school = it }, "اسم المدرسة / المعهد")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudentAvatar(s.logoPath, 56.dp, fallback = "🏫")
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("شعار المدرسة (يظهر في تقارير PDF)", fontSize = 13.sp, color = Mut)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn3D("🖼 اختيار شعار", { logoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, small = true)
                        if (s.logoPath.isNotBlank()) Btn3D("إزالة", { vm.clearLogo() }, color = BadC, small = true)
                    }
                }
            }
            AppField(late, { late = it.filter { c -> c.isDigit() } }, "التأخير الافتراضي (دقيقة)", keyboard = KeyboardType.Number)
        }
        GlassCard {
            SectionTitle("التشغيل التلقائي للمحاضرات (اختياري)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("بدء وإنهاء المحاضرات تلقائياً حسب الجدول", modifier = Modifier.weight(1f))
                Switch(checked = auto, onCheckedChange = { auto = it })
            }
            AppField(lecture, { lecture = it.filter { c -> c.isDigit() } }, "مدة الحصة الافتراضية (دقيقة)", keyboard = KeyboardType.Number)
            AppField(brk, { brk = it.filter { c -> c.isDigit() } }, "مدة الاستراحة الافتراضية (دقيقة)", keyboard = KeyboardType.Number)
            Text(
                "عند التفعيل تبدأ الحصة في وقتها وتنتهي في وقت انتهائها، وتظهر لافتة الاستراحة بين الحصص. يعمل ذلك أثناء فتح التطبيق.",
                color = Mut, fontSize = 12.sp
            )
        }
        Btn3D("💾 حفظ الإعدادات", {
            vm.saveSettings(s.copy(
                schoolName = school.trim().ifBlank { "حضور الطلبة" }, autoSchedule = auto,
                lectureMin = (lecture.toIntOrNull() ?: 45).coerceAtLeast(5), breakMin = (brk.toIntOrNull() ?: 10).coerceAtLeast(0),
                defaultLate = late.toIntOrNull() ?: 10, backupDays = (bkDays.toIntOrNull() ?: 30).coerceAtLeast(1)))
            vm.showToast("تم حفظ الإعدادات")
        }, Modifier.fillMaxWidth())
        GlassCard {
            SectionTitle("النسخ الاحتياطي التلقائي")
            Text("يأخذ التطبيق نسخة احتياطية تلقائياً مرة كل يوم عند فتحه، ويحذف القديم منها. (اضغط «حفظ الإعدادات» بعد تغيير عدد الأيام)", color = Mut, fontSize = 12.sp)
            AppField(bkDays, { bkDays = it.filter { c -> c.isDigit() } }, "عدد الأيام المحتفظ بنسخها", keyboard = KeyboardType.Number)
            Text("المجلد: ${vm.autoBackupDir()}", color = Mut, fontSize = 11.sp)
            Btn3D("💾 حفظ نسخة الآن", { vm.backupNow { bkTick++ } }, Modifier.fillMaxWidth(), small = true)
            val files = remember(bkTick, s) { vm.autoBackupFiles() }
            if (files.isEmpty()) Text("لا توجد نسخ تلقائية بعد.", color = Mut, fontSize = 12.sp)
            files.take(7).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.name.removePrefix("backup-").removeSuffix(".json") + "  (${f.length() / 1024} KB)", modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Btn3D("استرجاع", { restoreFile = f }, color = WarnC, small = true)
                }
            }
            if (files.size > 7) Text("يوجد ${files.size - 7} نسخ أقدم في المجلد.", color = Mut, fontSize = 11.sp)
        }
        GlassCard {
            SectionTitle("نسخ احتياطي يدوي")
            Text("يشمل الطلاب (مع صورهم) والجدول والمحاضرات والحضور والمستخدمين والإعدادات. احفظ الملف في مكان آمن لأنه يحتوي بيانات الطلاب.", color = Mut, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn3D("⬆ تصدير نسخة", { exportLauncher.launch("attendance_backup_${LocalDate.now()}.json") }, Modifier.weight(1f))
                Btn3D("⬇ استيراد نسخة", { importLauncher.launch(arrayOf("*/*")) }, Modifier.weight(1f), color = WarnC)
            }
        }
        GlassCard {
            SectionTitle("عن التطبيق")
            Text("كل البيانات (الطلاب، الجدول، الحضور) محفوظة محلياً على هذا الهاتف في قاعدة بيانات Room وتبقى بعد إغلاق التطبيق. لا يتم رفع أي شيء إلى الإنترنت.", color = Mut, fontSize = 13.sp)
        }
    }
    restoreFile?.let { f ->
        ConfirmDialog("سيتم استبدال كل البيانات الحالية بنسخة ${f.name}. متابعة؟", "استرجاع",
            onOk = { vm.importBackup(Uri.fromFile(f)); restoreFile = null }, onDismiss = { restoreFile = null })
    }
    pendingImport?.let { uri ->
        ConfirmDialog("سيتم استبدال كل البيانات الحالية بالنسخة المستوردة. متابعة؟", "استبدال",
            onOk = { vm.importBackup(uri); pendingImport = null }, onDismiss = { pendingImport = null })
    }
}

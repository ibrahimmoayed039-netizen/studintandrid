package com.attendance.nfc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.LanService
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.LanPrefs
import com.attendance.nfc.data.LanState

/**
 * نافذة «الربط بالحاسوب» السريعة من الشاشة الرئيسية: تفعيل/تعطيل التحكم من الحاسوب،
 * وعرض العنوان ورمز الاتصال ليكتبهما المستخدم في برنامج الحاسوب. نفس إعدادات بطاقة الإعدادات.
 */
@Composable
fun LanLinkDialog(vm: MainViewModel, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { LanPrefs(ctx) }
    var on by remember { mutableStateOf(prefs.enabled) }
    var token by remember { mutableStateOf(prefs.token) }
    val addr by LanState.address.collectAsState()
    val running by LanState.running.collectAsState()
    val err by LanState.error.collectAsState()
    val client by LanState.lastClient.collectAsState()
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        icon = { Text("📡", fontSize = 32.sp) },
        title = { Text("الربط بالحاسوب (الشبكة المحلية)", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (on) (if (running) "🟢 الربط مفعّل" else "🟡 جارٍ التشغيل...") else "⚪ الربط متوقف",
                        modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                    Switch(checked = on, onCheckedChange = { v ->
                        on = v; prefs.enabled = v
                        if (v && android.os.Build.VERSION.SDK_INT >= 33 &&
                            androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        LanService.sync(ctx)
                        vm.auditStore.add(vm.currentUser.value?.name ?: "النظام", "التحكم من الحاسوب", if (v) "تفعيل (الزر السريع)" else "تعطيل (الزر السريع)")
                    })
                }
                if (on) {
                    Text("اكتب هذا في برنامج الحاسوب ← تبويب «الأجهزة»:", color = Mut, fontSize = 12.sp)
                    Text("العنوان:", color = Mut, fontSize = 12.sp)
                    Text(if (addr.isBlank()) "جارٍ التشغيل..." else addr, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("رمز الاتصال:", color = Mut, fontSize = 12.sp)
                    Text(token, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                    Btn3D("🔄 رمز جديد", { token = prefs.regenerate() }, color = WarnC, small = true)
                    if (err.isNotBlank()) Text(err, color = BadC, fontSize = 12.sp)
                    if (client.isNotBlank()) Text("آخر اتصال من: $client", color = Mut, fontSize = 12.sp)
                } else {
                    Text("فعّل الخيار ليتمكن الحاسوب من عرض الجدول والمستخدمين وتعديلهما. يجب أن يكون الهاتف والحاسوب على نفس شبكة Wi-Fi.", color = Mut, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إغلاق") } }
    )
}

/**
 * زر «الربط بالحاسوب» لشاشة الدخول. يطلب رمزاً ثابتاً قبل فتح النافذة،
 * لأن من يملك العنوان والرمز يستطيع تعديل الجداول والمستخدمين من الحاسوب.
 */
@Composable
fun LanLinkEntry(vm: MainViewModel, modifier: Modifier = Modifier) {
    val running by LanState.running.collectAsState()
    var askPass by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf(false) }
    Btn3D(if (running) "📡 الربط بالحاسوب — مفعّل 🟢" else "📡 الربط بالحاسوب (الشبكة المحلية)",
        { askPass = true }, modifier.fillMaxWidth(), color = if (running) OkC else Pri2)
    if (askPass) AdminPasswordDialog(vm, onOk = { askPass = false; open = true }, onDismiss = { askPass = false })
    if (open) LanLinkDialog(vm) { open = false }
}

@Composable
private fun AdminPasswordDialog(vm: MainViewModel, onOk: () -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        if (busy) return
        busy = true
        vm.confirmLanCode(password) { err -> busy = false; if (err == null) onOk() else { error = err; password = "" } }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        icon = { Text("🔐", fontSize = 32.sp) },
        title = { Text("رمز الدخول", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(), fontSize = 17.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("أدخل رمز الدخول لإعدادات الربط بالحاسوب.", color = Mut, fontSize = 13.sp)
                AppField(password, { password = it; error = "" }, "رمز الدخول",
                    keyboard = KeyboardType.Password, visual = PasswordVisualTransformation())
                if (error.isNotBlank()) Text(error, color = BadC, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = !busy && password.isNotEmpty()) { Text("متابعة", fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

package com.attendance.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.User

@Composable
fun UsersScreen(vm: MainViewModel) {
    val users by vm.users.collectAsState()
    val me by vm.currentUser.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<User?>(null) }
    var resetting by remember { mutableStateOf<User?>(null) }
    var deleting by remember { mutableStateOf<User?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("المستخدمون", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                Btn3D("➕ مستخدم جديد", { creating = true })
            }
        }
        item {
            Text("المدير يرى كل شيء ويدير الطلاب والإعدادات. المدرّس يرى جدوله ومحاضراته فقط.", color = Mut, fontSize = 13.sp)
        }
        items(users, key = { it.id }) { u ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name + if (u.id == me?.id) " (أنت)" else "", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("@${u.username}", color = Mut, fontSize = 13.sp)
                    }
                    Badge(if (u.role == "admin") "مدير" else "مدرّس", if (u.role == "admin") Pri2 else InfoC)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn3D("تعديل", { editing = u }, Modifier.weight(1f), small = true)
                    Btn3D("كلمة المرور", { resetting = u }, Modifier.weight(1f), color = WarnC, small = true)
                    Btn3D("حذف", { deleting = u }, Modifier.weight(1f), color = BadC, small = true, enabled = u.id != me?.id)
                }
            }
        }
    }

    if (creating) UserDialog(vm, null) { creating = false }
    editing?.let { UserDialog(vm, it) { editing = null } }
    resetting?.let { u -> PasswordDialog(vm, u) { resetting = null } }
    deleting?.let { u ->
        ConfirmDialog("حذف المستخدم «${u.name}»؟", "حذف", onOk = {
            vm.deleteUser(u.id) { err -> if (err != null) vm.showToast(err) }
            deleting = null
        }, onDismiss = { deleting = null })
    }
}

@Composable
private fun UserDialog(vm: MainViewModel, initial: User?, onDismiss: () -> Unit) {
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: "teacher") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        title = { Text(if (initial == null) "مستخدم جديد" else "تعديل مستخدم") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial == null) AppField(username, { username = it.filter { c -> !c.isWhitespace() } }, "اسم المستخدم (للدخول)")
                else Text("@${initial.username}", color = Mut)
                AppField(name, { name = it }, "الاسم الظاهر")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == "teacher", onClick = { role = "teacher" }, label = { Text("مدرّس") })
                    FilterChip(selected = role == "admin", onClick = { role = "admin" }, label = { Text("مدير") })
                }
                if (initial == null) AppField(password, { password = it }, "كلمة المرور", visual = PasswordVisualTransformation())
                if (error.isNotBlank()) Text(error, color = BadC)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val done: (String?) -> Unit = { err -> if (err == null) onDismiss() else error = err }
                if (initial == null) vm.createUser(username, password, name, role, done)
                else vm.updateUser(initial.id, name, role, done)
            }) { Text("حفظ", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun PasswordDialog(vm: MainViewModel, user: User, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        title = { Text("كلمة مرور جديدة لـ ${user.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppField(password, { password = it }, "كلمة المرور الجديدة", visual = PasswordVisualTransformation())
                if (error.isNotBlank()) Text(error, color = BadC)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.resetPassword(user.id, password) { err ->
                    if (err == null) { vm.showToast("تم تغيير كلمة المرور"); onDismiss() } else error = err
                }
            }) { Text("حفظ", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

package com.attendance.nfc.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import com.attendance.nfc.cardJoin
import com.attendance.nfc.cardList
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
            Text("المدير يرى كل شيء ويدير الطلاب والإعدادات. المدرّس يرى جدوله ومحاضراته فقط. يمكن تخصيص بطاقة لكل مدرّس تؤكّد بها عمليات الحذف بدل كلمة المرور.", color = Mut, fontSize = 13.sp)
        }
        items(users, key = { it.id }) { u ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name + if (u.id == me?.id) " (أنت)" else "", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("@${u.username}" + if (u.cardUid.isNotBlank()) "  •  💳 ${cardList(u.cardUid).size} بطاقة" else "", color = Mut, fontSize = 13.sp)
                    }
                    Badge(if (u.role == "admin") "مدير" else "مدرّس" + (if (u.canStudents) " 🎓" else ""), if (u.role == "admin") Pri2 else InfoC)
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
    val cards = remember { mutableStateListOf<String>().apply { addAll(cardList(initial?.cardUid ?: "")) } }
    var card by remember { mutableStateOf("") }
    fun addCard(raw: String) {
        val c = raw.uppercase().filter { it.isLetterOrDigit() }
        if (c.isNotBlank() && c !in cards) cards.add(c)
        card = ""
    }
    var error by remember { mutableStateOf("") }
    var canSt by remember { mutableStateOf(initial?.canStudents ?: false) }
    val waiting by vm.cardCapture.collectAsState()
    val cardFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // عند بدء القراءة يُركَّز حقل الرقم ليستقبل القارئ الخارجي دون ظهور لوحة المفاتيح
    LaunchedEffect(waiting) {
        if (waiting) { try { cardFocus.requestFocus() } catch (_: Exception) {}; keyboard?.hide() }
    }
    LaunchedEffect(Unit) { vm.captured.collect { addCard(it) } }
    DisposableEffect(Unit) { onDispose { vm.cardCapture.value = false } }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        title = { Text(if (initial == null) "مستخدم جديد" else "تعديل مستخدم") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (initial == null) AppField(username, { username = it.filter { c -> !c.isWhitespace() } }, "اسم المستخدم (للدخول)")
                else Text("@${initial.username}", color = Mut)
                AppField(name, { name = it }, "الاسم الظاهر")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = role == "teacher", onClick = { role = "teacher" }, label = { Text("مدرّس") })
                    FilterChip(selected = role == "admin", onClick = { role = "admin" }, label = { Text("مدير") })
                }
                if (role != "admin") Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = canSt, onCheckedChange = { canSt = it })
                    Text("يسمح له بإضافة طلابه وبطاقاتهم (يرى ويعدّل من أضافهم فقط)", fontSize = 13.sp)
                }
                if (initial == null) AppField(password, { password = it }, "كلمة المرور", visual = PasswordVisualTransformation())
                Text("بطاقات المدرّسين (اختياري) — يمكن إضافة أكثر من بطاقة لنفس المستخدم، وأي بطاقة منها تؤكّد الحذف", color = Mut, fontSize = 12.sp)
                cards.toList().forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💳 $c", Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(onClick = { cards.remove(c) }) { Text("إزالة", color = BadC) }
                    }
                }
                AppField(
                    card, { card = it.uppercase().filter { c -> c.isLetterOrDigit() } }, "رقم بطاقة (UID)",
                    Modifier.focusRequester(cardFocus).onPreviewKeyEvent { e ->
                        if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown) {
                            if (card.isNotBlank()) addCard(card)
                            vm.cardCapture.value = false
                            true
                        } else false
                    }
                )
                if (card.isNotBlank()) TextButton(onClick = { addCard(card) }) { Text("➕ إضافة هذا الرقم") }
                CardReadButton(waiting, false, { vm.cardCapture.value = !waiting })
                if (error.isNotBlank()) Text(error, color = BadC)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val done: (String?) -> Unit = { err -> if (err == null) onDismiss() else error = err }
                if (card.isNotBlank()) addCard(card)
                val joined = cardJoin(cards.toList())
                if (initial == null) vm.createUser(username, password, name, role, joined, canSt, done)
                else vm.updateUser(initial.id, name, role, joined, canSt, done)
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

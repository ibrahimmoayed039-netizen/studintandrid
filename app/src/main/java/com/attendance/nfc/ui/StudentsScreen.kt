package com.attendance.nfc.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel
import com.attendance.nfc.data.Student
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun StudentsScreen(vm: MainViewModel) {
    val students by vm.students.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Student?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Student?>(null) }
    val active by vm.activeSession.collectAsState()
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importStudents(uri) }
    val grades = students.map { it.grade }.filter { it.isNotBlank() }.distinct()
    val list = students.filter { q.isBlank() || it.name.contains(q, true) || it.grade.contains(q, true) || it.cardUid.contains(q, true) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppField(q, { q = it }, "🔍 بحث عن طالب", Modifier.weight(1f))
                Btn3D("➕ إضافة", { creating = true })
            }
        }
        item {
            Btn3D("📥 استيراد من Excel / CSV", {
                importer.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
            }, Modifier.fillMaxWidth(), small = true)
            Text("الأعمدة: اسم الطالب، الصف، رقم البطاقة (الصف الأول عناوين).", color = Mut, fontSize = 11.sp)
        }
        item { Text("عدد الطلاب: ${students.size}", color = com.attendance.nfc.ui.Mut) }
        if (list.isEmpty()) item { Text("لا يوجد طلاب. اضغط «إضافة» لتسجيل أول طالب وربط بطاقته.", color = Mut) }
        items(list, key = { it.id }) { st ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudentAvatar(st.photoPath, 52.dp)
                    Column(Modifier.weight(1f)) {
                        Text(st.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(st.grade.ifBlank { "بدون صف" }, color = Mut, fontSize = 13.sp)
                        Text(if (st.cardUid.isBlank()) "⚠️ بدون بطاقة" else "💳 ${st.cardUid}", color = if (st.cardUid.isBlank()) WarnC else OkC, fontSize = 12.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    active?.let { ses -> Btn3D("حضور يدوي", { vm.markPresent(ses, st) }, Modifier.weight(1f), color = OkC, small = true) }
                    Btn3D("تعديل", { editing = st }, Modifier.weight(1f), small = true)
                    Btn3D("حذف", { deleting = st }, Modifier.weight(1f), color = BadC, small = true)
                }
            }
        }
    }

    if (creating) StudentDialog(vm, null, grades) { creating = false }
    editing?.let { StudentDialog(vm, it, grades) { editing = null } }
    deleting?.let { st ->
        ConfirmDialog("حذف الطالب «${st.name}»؟ (يبقى سجل حضوره السابق)", "حذف",
            onOk = { vm.deleteStudent(st.id); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun StudentDialog(vm: MainViewModel, initial: Student?, grades: List<String>, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var grade by remember { mutableStateOf(initial?.grade ?: "") }
    var uid by remember { mutableStateOf(initial?.cardUid ?: "") }
    var photo by remember { mutableStateOf(initial?.photoPath ?: "") }
    val waiting by vm.cardCapture.collectAsState()
    val scope = rememberCoroutineScope()
    val uidFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // عند بدء القراءة يُركَّز حقل الرقم ليستقبل القارئ الخارجي (لوحة مفاتيح) دون ظهور لوحة المفاتيح
    LaunchedEffect(waiting) {
        if (waiting) { try { uidFocus.requestFocus() } catch (_: Exception) {}; keyboard?.hide() }
    }
    LaunchedEffect(Unit) { vm.captured.collect { uid = it } }
    DisposableEffect(Unit) { onDispose { vm.cardCapture.value = false } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { val p = vm.importPhoto(uri); if (p.isNotBlank()) photo = p }
    }

    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Color(0xFF1A2150),
        title = { Text(if (initial == null) "إضافة طالب" else "تعديل طالب") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StudentAvatar(photo, 72.dp)
                    Btn3D("📷 اختيار صورة", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, small = true)
                    if (photo.isNotBlank()) TextButton(onClick = { photo = "" }) { Text("إزالة") }
                }
                AppField(name, { name = it }, "اسم الطالب")
                AppField(grade, { grade = it }, "الصف / المجموعة")
                GradeChips(grades, grade) { grade = it }
                AppField(
                    uid, { uid = it.uppercase().filter { c -> c.isLetterOrDigit() } }, "رقم البطاقة (UID)",
                    Modifier.focusRequester(uidFocus).onPreviewKeyEvent { e ->
                        // القارئ الخارجي يرسل Enter بعد الرقم: نُنهي وضع الانتظار
                        if ((e.key == Key.Enter || e.key == Key.NumPadEnter) && e.type == KeyEventType.KeyDown) {
                            if (uid.isNotBlank()) vm.cardCapture.value = false
                            true
                        } else false
                    }
                )
                CardReadButton(waiting, uid.isNotBlank(), { if (!waiting) uid = ""; vm.cardCapture.value = !waiting })
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                vm.saveStudent(Student(initial?.id ?: UUID.randomUUID().toString(), name.trim(), grade.trim(), uid.trim(), photo)) { ok ->
                    if (ok) onDismiss()
                }
            }) { Text("حفظ", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

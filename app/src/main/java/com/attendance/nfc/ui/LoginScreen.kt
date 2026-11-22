package com.attendance.nfc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.MainViewModel

@Composable
fun LoginScreen(vm: MainViewModel) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                Modifier.size(88.dp).shadow(16.dp, RoundedCornerShape(28.dp)).clip(RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFA0A9FF), Pri))),
                contentAlignment = Alignment.Center
            ) { Text("🎓", fontSize = 44.sp) }
            Text("حضور الطلبة", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Text("سجّل الدخول للمتابعة", color = Mut)
            GlassCard {
                AppField(username, { username = it; error = "" }, "اسم المستخدم")
                AppField(password, { password = it; error = "" }, "كلمة المرور", keyboard = KeyboardType.Password, visual = PasswordVisualTransformation())
                if (error.isNotBlank()) Text(error, color = BadC, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Btn3D(if (busy) "..." else "دخول", {
                    busy = true
                    vm.login(username, password) { ok ->
                        busy = false
                        if (!ok) error = "اسم المستخدم أو كلمة المرور غير صحيحة"
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && username.isNotBlank() && password.isNotEmpty())
                Text("أول تشغيل: اسم المستخدم admin وكلمة المرور 1234 — غيّرها بعد الدخول من تبويب «المستخدمون».", color = Mut, fontSize = 12.sp)
            }
        }
    }
}

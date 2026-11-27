package com.attendance.nfc

import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.attendance.nfc.data.User
import com.attendance.nfc.ui.*

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var nfc: NfcAdapter? = null
    private var nfcStatus by mutableIntStateOf(0)   // 0 جاهز، 1 غير مدعوم، 2 معطّل

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        nfc = NfcAdapter.getDefaultAdapter(this)
        if (com.attendance.nfc.data.LanPrefs(this).enabled) LanService.sync(this)
        setContent { AttendanceTheme { AppRoot(vm, nfcStatus) } }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc
        nfcStatus = when {
            adapter == null -> 1
            !adapter.isEnabled -> 2
            else -> 0
        }
        if (adapter != null && adapter.isEnabled) {
            try {
                adapter.enableReaderMode(
                    this,
                    NfcAdapter.ReaderCallback { tag ->
                        val uid = tag.id.joinToString("") { "%02X".format(it) }
                        vm.onCardUid(uid)
                    },
                    NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                        NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V or
                        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
                    null
                )
            } catch (_: Exception) {}
        }
    }

    // ---- قارئ NFC/RFID خارجي (USB OTG أو بلوتوث) يعمل كلوحة مفاتيح: يكتب الرقم ثم Enter ----
    private val keyBuf = StringBuilder()
    private var lastKeyAt = 0L

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val dev = e.device
        // لوحة المفاتيح الافتراضية (على الشاشة) وأي جهاز غير لوحة مفاتيح تُترك كما هي
        if (dev == null || dev.isVirtual || (dev.sources and InputDevice.SOURCE_KEYBOARD) != InputDevice.SOURCE_KEYBOARD)
            return super.dispatchKeyEvent(e)
        // إن كان المستخدم يكتب في حقل نصي فالنص يذهب للحقل (مثل خانة رقم البطاقة في نموذج الطالب)
        if (currentFocus?.onCheckIsTextEditor() == true) return super.dispatchKeyEvent(e)

        val isEnter = e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        val ch = e.unicodeChar
        val isChar = ch > 0 && Character.isLetterOrDigit(ch)
        if (!isEnter && !isChar) return super.dispatchKeyEvent(e)
        if (e.action == KeyEvent.ACTION_DOWN) {
            if (e.eventTime - lastKeyAt > 400) keyBuf.setLength(0)
            lastKeyAt = e.eventTime
            if (isEnter) {
                if (keyBuf.length >= 4) vm.onCardUid(keyBuf.toString().uppercase())
                keyBuf.setLength(0)
            } else keyBuf.append(ch.toChar())
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        try { nfc?.disableReaderMode(this) } catch (_: Exception) {}
    }
}

private data class TabDef(val id: String, val label: String, val icon: String, val adminOnly: Boolean)

private val ALL_TABS = listOf(
    TabDef("scan", "المسح", "📇", false),
    TabDef("schedule", "الجدول", "🗓", false),
    TabDef("students", "الطلاب", "🎓", true),
    TabDef("log", "السجل", "📋", false),
    TabDef("reports", "التقارير", "📊", false),
    TabDef("users", "المستخدمون", "🔑", true),
    TabDef("audit", "التدقيق", "🛡", true),
    TabDef("settings", "الإعدادات", "⚙️", true)
)

@Composable
fun AppRoot(vm: MainViewModel, nfcStatus: Int) {
    val ready by vm.authReady.collectAsState()
    val user by vm.currentUser.collectAsState()
    val u = user
    AppBackground {
        when {
            !ready -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            u == null -> LoginScreen(vm)
            else -> MainShell(vm, nfcStatus, u)
        }
    }
}

@Composable
private fun MainShell(vm: MainViewModel, nfcStatus: Int, user: User) {
    val isAdmin = user.role == "admin"
    val tabs = remember(isAdmin) { ALL_TABS.filter { isAdmin || !it.adminOnly } }
    var tabId by rememberSaveable { mutableStateOf("scan") }
    val current = if (tabs.any { it.id == tabId }) tabId else "scan"
    var kiosk by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var showLan by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val active by vm.activeSession.collectAsState()
    val settings by vm.settings.collectAsState()
    val view = LocalView.current

    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }
    // لحظة بدء أي حصة (يدوي أو من الجدول) تُفتح شاشة استقبال البطاقات تلقائياً
    LaunchedEffect(Unit) { vm.sessionStarted.collect { kiosk = true } }
    SideEffect { view.keepScreenOn = active != null }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { AppHeader(settings.schoolName, user, nfcStatus, onLan = if (isAdmin) ({ showLan = true }) else null) { confirmLogout = true } },
            bottomBar = { BottomTabs(tabs, current) { tabId = it } }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (current) {
                    "scan" -> ScanScreen(vm, nfcStatus) { kiosk = true }
                    "schedule" -> ScheduleScreen(vm)
                    "students" -> StudentsScreen(vm)
                    "log" -> LogScreen(vm)
                    "reports" -> ReportsScreen(vm)
                    "users" -> UsersScreen(vm)
                    "audit" -> AuditScreen(vm)
                    else -> SettingsScreen(vm)
                }
            }
        }
        if (kiosk && active != null) KioskScreen(vm) { kiosk = false }
    }

    if (showLan) LanLinkDialog(vm) { showLan = false }

    if (confirmLogout) {
        ConfirmDialog("تسجيل الخروج من حساب «${user.name}»؟", "خروج",
            onOk = { confirmLogout = false; kiosk = false; vm.logout() }, onDismiss = { confirmLogout = false })
    }
}

@Composable
private fun BottomTabs(tabs: List<TabDef>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xE60E1330)).navigationBarsPadding()
            .horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        tabs.forEach { t ->
            val sel = t.id == selected
            Column(
                Modifier.widthIn(min = 72.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (sel) Pri.copy(alpha = .35f) else Color.Transparent)
                    .clickable { onSelect(t.id) }.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(t.icon, fontSize = 20.sp)
                Text(t.label, fontSize = 11.sp, color = if (sel) Color.White else Mut)
            }
        }
    }
}

@Composable
private fun AppHeader(school: String, user: User, nfcStatus: Int, onLan: (() -> Unit)? = null, onLogout: () -> Unit) {
    val now by rememberTicker()
    val lanRunning by com.attendance.nfc.data.LanState.running.collectAsState()
    val shape = RoundedCornerShape(bottomStart = 22.dp, bottomEnd = 22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Pri.copy(alpha = .6f), Pri2.copy(alpha = .45f))))
            .statusBarsPadding().padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(school, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Text("${user.name} (${if (user.role == "admin") "مدير" else "مدرّس"})", fontSize = 12.sp, color = Color(0xFFE9ECFF), maxLines = 1)
        }
        Badge(if (nfcStatus == 0) "NFC ✓" else "NFC ✕", if (nfcStatus == 0) OkC else WarnC)
        Text("%02d:%02d".format(now.hour, now.minute), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (onLan != null) Btn3D("📡", onLan, color = if (lanRunning) OkC else Color(0xFF4A5180), small = true)
        Btn3D("🚪", onLogout, color = Color(0xFF4A5180), small = true)
    }
}

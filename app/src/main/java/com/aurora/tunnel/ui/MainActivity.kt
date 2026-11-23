package com.aurora.tunnel.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Menu
import android.os.Handler
import android.os.Looper
import com.aurora.tunnel.core.Tunnel
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.log.AppLog
import com.aurora.tunnel.vpn.TunnelCtl

class MainActivity : ComponentActivity() {
    private var pendingProfile: String? = null

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val id = pendingProfile
        pendingProfile = null
        if (r.resultCode == RESULT_OK && id != null) TunnelCtl.start(this, id)
        else AppLog.w("APP", "Izin VPN ditolak")
    }
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            AuroraTheme(Settings.theme) {
                Root(onConnect = ::connect, onDisconnect = { TunnelCtl.stop(this) }, onReconnect = ::reconnect)
            }
        }
    }

    private fun reconnect(id: String) {
        if (Tunnel.isActive) {
            TunnelCtl.stop(this)
            Handler(Looper.getMainLooper()).postDelayed({ connect(id) }, 1500)
        } else connect(id)
    }

    private fun connect(id: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) { pendingProfile = id; vpnPermission.launch(intent) }
        else TunnelCtl.start(this, id)
    }
}

@Composable
private fun Root(onConnect: (String) -> Unit, onDisconnect: () -> Unit, onReconnect: (String) -> Unit) {
    val p = LocalAurora.current
    var tab by remember { mutableStateOf(0) }          // 0 = HOME, 1 = LOG
    var settings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Profile?>(null) }

    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = !editing && settings) { settings = false }

    val barColor = if (p.blobs.isEmpty()) p.card else p.card.copy(alpha = if (p.glass) 0.16f else 0.62f)

    Column(Modifier.fillMaxSize()) {
        if (editing) {
            Box(Modifier.weight(1f).statusBarsPadding().navigationBarsPadding()) {
                EditorScreen(editTarget) { editing = false }
            }
        } else {
            Column(Modifier.fillMaxWidth().background(barColor).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ settings = !settings }) {
                        Icon(Icons.Filled.Menu, "Pengaturan", tint = if (settings) p.accent else p.sub)
                    }
                    listOf("HOME", "LOG").forEachIndexed { i, label ->
                        val on = !settings && tab == i
                        Column(
                            Modifier.weight(1f).clickable { tab = i; settings = false },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.height(50.dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (on) p.accent else p.sub, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                            Box(Modifier.fillMaxWidth(0.8f).height(3.dp)
                                .background(if (on) p.accent else Color.Transparent))
                        }
                    }
                    Box(Modifier.width(48.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder.copy(0.7f)))
            }
            Box(Modifier.weight(1f)) {
                when {
                    settings -> Box(Modifier.navigationBarsPadding()) { SettingsScreen() }
                    tab == 0 -> HomeScreen(onConnect, onDisconnect, onReconnect) { target -> editTarget = target; editing = true }
                    else -> Box(Modifier.navigationBarsPadding()) { LogScreen() }
                }
            }
        }
    }
}

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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.foundation.border
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
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

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun Root(onConnect: (String) -> Unit, onDisconnect: () -> Unit, onReconnect: (String) -> Unit) {
    val p = LocalAurora.current
    val tabs = listOf(
        Tab("Beranda", Icons.Filled.Home),
        Tab("Profil", Icons.Filled.Person),
        Tab("Log", Icons.AutoMirrored.Filled.List),
        Tab("Pengaturan", Icons.Filled.Settings)
    )
    val pager = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Profile?>(null) }

    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = !editing && pager.currentPage != 0) { scope.launch { pager.animateScrollToPage(0) } }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).statusBarsPadding()) {
            if (editing) {
                EditorScreen(editTarget) { editing = false }
            } else {
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    when (page) {
                        0 -> HomeScreen(onConnect, onDisconnect, onReconnect) { scope.launch { pager.animateScrollToPage(1) } }
                        1 -> ProfilesScreen { target -> editTarget = target; editing = true }
                        2 -> LogScreen()
                        else -> SettingsScreen()
                    }
                }
            }
        }
        if (!editing) {
            NavigationBar(
                containerColor = if (p.blobs.isEmpty()) p.card else p.card.copy(alpha = if (p.glass) 0.16f else 0.62f),
                modifier = Modifier.border(1.dp, p.cardBorder.copy(alpha = 0.5f), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            ) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = pager.currentPage == i, onClick = { scope.launch { pager.animateScrollToPage(i) } },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF04131A), selectedTextColor = p.accent,
                            indicatorColor = p.accent, unselectedIconColor = p.sub, unselectedTextColor = p.sub
                        )
                    )
                }
            }
        }
    }
}

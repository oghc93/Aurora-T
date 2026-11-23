package com.aurora.tunnel.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aurora.tunnel.data.Importer
import com.aurora.tunnel.data.Mode
import com.aurora.tunnel.data.Profile
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.engine.Ping
import com.aurora.tunnel.engine.Rotate
import com.aurora.tunnel.engine.XrayConfig
import kotlinx.coroutines.launch
import java.util.UUID

fun protoLabel(p: Profile): String =
    if (p.mode == Mode.XRAY) (runCatching { XrayConfig.nodeOf(p).protocol }.getOrDefault("xray")).uppercase()
    else p.mode.label.uppercase()

fun transportLabel(p: Profile): String = when (p.mode) {
    Mode.XRAY -> runCatching {
        val n = XrayConfig.nodeOf(p)
        n.network.uppercase() + if (n.tls != "none") "+" + n.tls.uppercase() else ""
    }.getOrDefault("Xray")
    Mode.SSH, Mode.UDPCUSTOM -> buildList {
        add("SSH")
        if (p.enableSni) add("TLS")
        if (p.enablePayload) add("PAYLOAD")
        if (p.proxyHost.isNotBlank()) add("PROXY")
    }.joinToString("+")
    Mode.SLOWDNS -> "DNSTT+SSH"
    Mode.OVPN -> "OVPN"
}

fun shareText(p: Profile): String = when (p.mode) {
    Mode.XRAY -> if (XrayConfig.isJson(p.xrayLink)) p.xrayLink
        else runCatching { XrayConfig.toLink(XrayConfig.nodeOf(p)) }.getOrDefault("")
    Mode.SSH, Mode.UDPCUSTOM -> "${p.host}:${p.port}@${p.username}:${p.password}"
    else -> p.name
}

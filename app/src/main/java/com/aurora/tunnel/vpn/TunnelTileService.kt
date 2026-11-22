package com.aurora.tunnel.vpn

import android.content.Intent
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.aurora.tunnel.core.Status
import com.aurora.tunnel.core.Tunnel
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.ui.MainActivity

class TunnelTileService : TileService() {

    override fun onStartListening() { refresh() }

    override fun onClick() {
        if (Tunnel.isActive) {
            TunnelCtl.stop(this)
        } else {
            val id = Settings.selectedProfileId ?: ProfileStore.profiles.value.firstOrNull()?.id
            if (id == null || VpnService.prepare(this) != null) {
                val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                @Suppress("DEPRECATION")
                startActivityAndCollapse(i)
            } else {
                TunnelCtl.start(this, id)
            }
        }
        refresh()
    }

    private fun refresh() {
        val t = qsTile ?: return
        t.state = if (Tunnel.status.value == Status.CONNECTED) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Aurora Tunnel"
        t.updateTile()
    }
}

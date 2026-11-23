package com.aurora.tunnel

import android.app.Application
import com.aurora.tunnel.data.ProfileStore
import com.aurora.tunnel.data.Settings
import com.aurora.tunnel.log.AppLog

class AuroraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        ProfileStore.init(this)
        AppLog.debugEnabled = Settings.debugLog.v
    }
}

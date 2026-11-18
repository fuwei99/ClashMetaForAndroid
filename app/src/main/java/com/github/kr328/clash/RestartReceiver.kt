package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.github.kr328.clash.api.UiLock
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.util.startClashService

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 两套自启开关取或：API 的 autostart_vpn + 旧 StatusProvider 开机自启
                if (UiLock.autostart(context) || StatusProvider.shouldStartClashOnBoot)
                    runCatching { context.startClashService() }
            }
        }
    }
}
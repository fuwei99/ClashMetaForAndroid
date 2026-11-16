package com.github.kr328.clash.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 应用升级后把 9091 控制面重新拉起来，不用人手点开 App。 */
class ApiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                ApiControlService.launch(context.applicationContext)
            }
        }
    }
}

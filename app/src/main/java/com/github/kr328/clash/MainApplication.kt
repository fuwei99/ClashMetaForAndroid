package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.api.ApiControlService
import com.github.kr328.clash.api.UiLock
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.clashDir
import java.io.File
import java.io.FileOutputStream

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (processName == packageName) {
            Remote.launch()
            // 进程一启动就把 9091 合并控制面挂起来，不依赖任何 Activity / VPN 是否已连
            ApiControlService.launch(this)
            // 自启 VPN：由 POST /api/autostart 配；App 一启动（含开机/覆盖装）就拉，
            // 不依赖用户点开 UI。没授权 VpnService.prepare 会返回 pending，静默跳过不炸。
            if (UiLock.autostart(this)) {
                runCatching { startClashService() }
            }
        } else {
            sendServiceRecreated()
        }
    }

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }

        val bundleMRSFile = File(clashDir, "BundleMRS.7z")
        if (bundleMRSFile.exists() && bundleMRSFile.lastModified() < updateDate) {
            bundleMRSFile.delete()
        }
        if (!bundleMRSFile.exists()) {
            FileOutputStream(bundleMRSFile).use {
                assets.open("BundleMRS.7z").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}

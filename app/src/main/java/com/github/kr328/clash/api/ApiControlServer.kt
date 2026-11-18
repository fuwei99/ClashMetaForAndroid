package com.github.kr328.clash.api

import android.app.Application
import android.content.Context
import android.net.VpnService
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.core.model.ConfigurationOverride
import com.github.kr328.clash.design.store.UiStore
import com.github.kr328.clash.remote.Service as RemoteServiceConnection
import com.github.kr328.clash.remote.StatusClient
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IClashManager
import com.github.kr328.clash.service.remote.IProfileManager
import com.github.kr328.clash.service.remote.IRemoteService
import com.github.kr328.clash.util.startClashService
import com.github.kr328.clash.util.stopClashService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.thread as ktThread
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * CMFA 合并控制面：App 进程一启动就监听 127.0.0.1:9091。
 *
 * 不是 9090 的简单透传：
 *   - /api/vpn/...    → 直接调 App 自己的 startClashService / stopClashService（真实 VpnService 开关）
 *   - /api/subscription → 走 IProfileManager 建/拉/激活订阅（真实 App 数据库操作）
 *   - /api/proxy/select → 走 IClashManager.patchSelector（会持久化选中项）
 *   - /api/status     → 一次性合并「App 状态 + 激活配置 + 内核 /version」
 *   - /api/clash/...  → 才是对 mihomo 9090 的代理，且自动注入 Bearer secret（调用方无需知道密钥）
 *
 * 端口只绑 loopback；内核 external-controller 强制 127.0.0.1:9090 + 随机 secret + allow-lan:false。
 */
class ApiControlServer(private val app: Application) {

    companion object {
        const val PORT = 9091
        const val CORE_PORT = 9090
        private const val PREFS = "api_control"
        private const val CRLF = "\r\n"
    }

    private val prefs by lazy { app.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    /** mihomo external-controller 的密钥：首启随机生成并落盘，不硬编码。 */
    val secret: String by lazy {
        prefs.getString("core_secret", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("core_secret", it).apply()
        }
    }

    /** 9091 自身的可选鉴权；空 = 只信 loopback（默认）。 */
    val apiToken: String get() = prefs.getString("api_token", "") ?: ""

    private val connection = RemoteServiceConnection(app) {
        Log.w("CMFA API: RemoteService crashed")
    }

    @Volatile
    private var remote: IRemoteService? = null

    @Volatile
    private var running = true

    private var serverSocket: ServerSocket? = null

    // 连接快照缓冲：key = mihomo connection id，value = 最新一次快照（带首见/末见时间）
    private val historyStore = ConcurrentHashMap<String, JSONObject>()
    // 被拉黑的 app：同时存包名与 uid 字符串，匹配 metadata.process / metadata.uid 命中即掐
    // newKeySet() 要 API 24，本仓 minSdk=21；用 newSetFromMap 兼容到 API 1
    private val blocked = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        // 重启 App 后恢复封杀名单（规则层 REJECT 靠 override 持久化）
        runCatching {
            prefs.getStringSet("deny_packages", null)?.let { blocked.addAll(it) }
        }
    }
    @Volatile
    private var lastConns: JSONArray = JSONArray()

    // uid → 包名缓存：mihomo CMFA 分支只写 metadata.Process 不写 Uid 之外的包名，
    // 实测 process 恒空但 uid 有效，故用 PackageManager 反查，供 traffic/history/kill/block 统一使用。
    private val uidPkg = ConcurrentHashMap<String, String>()
    private val pkgUid = ConcurrentHashMap<String, String>()
    @Volatile
    private var uidPkgLoadedAt = 0L

    /** 30s 刷新一次 uid↔包名表（getInstalledPackages 有一定开销，别每条连接都扫）。 */
    private fun ensureUidMap() {
        val now = System.currentTimeMillis()
        if (now - uidPkgLoadedAt < 30_000 && uidPkg.isNotEmpty()) return
        try {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            val list = pm.getInstalledPackages(0)
            val u2p = HashMap<String, String>(list.size)
            val p2u = HashMap<String, String>(list.size)
            for (pi in list) {
                val ai = pi.applicationInfo ?: continue
                val u = ai.uid.toString()
                // 同一个 uid 可能被多个包共享，优先保留先出现的（与 AppListCacheModule 一致）
                if (!u2p.containsKey(u)) u2p[u] = pi.packageName
                p2u[pi.packageName] = u
            }
            uidPkg.clear(); uidPkg.putAll(u2p)
            pkgUid.clear(); pkgUid.putAll(p2u)
            uidPkgLoadedAt = now
        } catch (_: Throwable) {
        }
    }

    /** 内核 process 为空时用 uid 反查包名；再不行退回 uid（至少不是空串）。 */
    private fun resolveApp(proc: String, uid: String): String {
        if (proc.isNotEmpty()) return proc
        if (uid.isNotEmpty() && uid != "0") {
            ensureUidMap()
            uidPkg[uid]?.let { return it }
        }
        return uid
    }

    /** 统一匹配：支持传包名 / uid / 应用名关键字，命中即真能掐到连接。 */
    private fun matchApp(target: String, proc: String, uid: String): Boolean {
        if (target.isBlank()) return false
        val resolved = resolveApp(proc, uid)
        if (resolved == target || uid == target || proc == target) return true
        if (resolved.contains(target, true) || proc.contains(target, true)) return true
        // 传的是 uid 但连接里只有包名，或反过来
        ensureUidMap()
        pkgUid[target]?.let { if (it == uid) return true }
        uidPkg[uid]?.let { if (it == target) return true }
        return false
    }

    fun start() {
        connection.bind()
        thread("cmfa-api-bind") {
            try {
                remote = runBlocking(Dispatchers.IO) {
                    withTimeout(15_000) { connection.remote.get() }
                }
                injectController()
            } catch (e: Throwable) {
                Log.w("CMFA API bind remote failed: ${e.message}")
            }
        }
        thread("cmfa-api-accept") { acceptLoop() }
        // 每 2s 拉一次 /connections：写访问历史 + 持续掐掉被拉黑 app 的新连接
        thread("cmfa-api-watch") { watchLoop() }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        try { connection.unbind() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------ HTTP server

    private fun thread(name: String, block: () -> Unit) {
        ktThread(isDaemon = true, name = name) { block() }
    }

    private fun acceptLoop() {
        try {
            serverSocket = ServerSocket(PORT, 64, InetAddress.getByName("127.0.0.1"))
            Log.d("CMFA API listening on 127.0.0.1:$PORT")
            while (running) {
                val client = serverSocket!!.accept()
                thread("cmfa-api-conn") { handle(client) }
            }
        } catch (e: Throwable) {
            if (running) Log.w("CMFA API server stopped: ${e.message}")
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 20_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 8 * 1024 * 1024) ?: 0
            val bodyBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(bodyBytes, read, length - read)
                if (n < 0) break
                read += n
            }
            val body = String(bodyBytes, 0, read, Charsets.UTF_8)

            // 可选鉴权：设置了 api_token 就要求 Bearer / X-Api-Key
            val tk = apiToken
            if (tk.isNotEmpty()) {
                val auth = headers["authorization"] ?: headers["x-api-key"] ?: ""
                val provided = if (auth.startsWith("Bearer ", true)) auth.substring(7) else auth
                if (provided != tk) {
                    write(output, Resp(401, jo("ok" to false, "error" to "unauthorized").toString()))
                    return
                }
            }

            val qIndex = target.indexOf('?')
            val path = if (qIndex >= 0) target.substring(0, qIndex) else target
            val query = if (qIndex >= 0) target.substring(qIndex + 1) else ""

            val resp = try {
                route(method, path, query, body)
            } catch (e: Throwable) {
                err(500, e)
            }
            write(output, resp)
        } catch (e: Throwable) {
            Log.w("CMFA API conn error: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    private data class Resp(
        val code: Int,
        val body: String,
        val type: String = "application/json; charset=utf-8",
    )

    private fun write(out: OutputStream, resp: Resp) {
        val reason = when (resp.code) {
            200 -> "OK"; 201 -> "Created"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 409 -> "Conflict"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "OK"
        }
        val bytes = resp.body.toByteArray(Charsets.UTF_8)
        val head =
            "HTTP/1.1 ${resp.code} $reason$CRLF" +
                "Content-Type: ${resp.type}$CRLF" +
                "Content-Length: ${bytes.size}$CRLF" +
                "Cache-Control: no-store$CRLF" +
                "Access-Control-Allow-Origin: *$CRLF" +
                "Connection: close$CRLF$CRLF"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    // ------------------------------------------------------------ routes

    private fun route(method: String, path: String, query: String, body: String): Resp {
        return when {
            path == "/" || path == "/api" || path == "/api/hello" -> hello()
            path == "/api/status" && method == "GET" -> status()
            path == "/api/secret" && method == "GET" ->
                Resp(200, jo("ok" to true, "core_secret" to secret, "core_port" to CORE_PORT).toString())

            // ---- 流量可观测 / 精准封杀 ----
            path == "/api/connections" && method == "GET" -> connectionsRaw()
            path == "/api/traffic" && method == "GET" -> traffic()
            path == "/api/apps" && method == "GET" -> apps(query)
            path == "/api/history" && method == "GET" -> history(query)
            path == "/api/history/clear" && method == "POST" -> historyClear()
            path == "/api/app/kill" && method == "POST" -> appKill(body)
            path == "/api/app/block" && method == "POST" -> appBlock(body, true)
            path == "/api/app/unblock" && method == "POST" -> appBlock(body, false)
            path == "/api/blocks" && method == "GET" ->
                Resp(200, jo("ok" to true, "blocked" to JSONArray(blocked.sorted())).toString())
            path == "/api/capture" && method == "GET" -> captureInfo()
            path == "/api/capture/http" && method == "POST" -> captureHttp(body)
            path == "/api/observe" && method == "GET" -> observeInfo()
            path == "/api/observe" && method == "POST" -> observeSet(body)
            path == "/api/group/nodes" && method == "GET" -> groupNodes(query)
            path == "/api/subscription/import" && method == "POST" -> configImport(body)
            path.startsWith("/_raw/") && method == "GET" -> rawConfig(path)

            path == "/api/vpn/start" && method == "POST" -> vpnStart()
            path == "/api/vpn/stop" && method == "POST" -> vpnStop()
            path == "/api/vpn/toggle" && method == "POST" -> if (isRunning()) vpnStop() else vpnStart()

            path == "/api/profiles" && method == "GET" -> profiles()
            path == "/api/profiles/activate" && method == "POST" -> profileActivate(body)
            path.startsWith("/api/profiles/") && method == "DELETE" ->
                profileDelete(path.removePrefix("/api/profiles/"))
            path == "/api/subscription" && method == "POST" -> subscription(body)
            path == "/api/subscription/refresh" && method == "POST" -> subscriptionRefresh()

            // ---- UI 锁 / 自启（只锁 UI，API 全畅通） ----
            path == "/api/lock" && method == "GET" -> Resp(200, UiLock.asJson(app).toString())
            path == "/api/lock" && method == "POST" -> lockSet(body)
            path == "/api/unlock" && method == "POST" -> {
                UiLock.clear(app)
                Resp(200, jo("ok" to true, "unlocked" to true, "lock" to UiLock.asJson(app)).toString())
            }
            path == "/api/autostart" && method == "GET" ->
                Resp(200, jo("ok" to true, "autostart_vpn" to UiLock.autostart(app)).toString())
            path == "/api/autostart" && method == "POST" -> autostartSet(body)

            path == "/api/groups" && method == "GET" -> groups()
            path == "/api/proxy/select" && method == "POST" -> proxySelect(body)
            path == "/api/mode" && method == "POST" -> setMode(body)
            path == "/api/controller/inject" && method == "POST" -> {
                injectController()
                status()
            }

            path == "/api/token" && method == "GET" ->
                Resp(200, jo(
                    "ok" to true,
                    "auth_required" to apiToken.isNotEmpty(),
                    "hint" to "POST /api/token {\"token\":\"...\"} 设置；置空关闭鉴权",
                ).toString())
            path == "/api/token" && method == "POST" -> {
                val t = try { JSONObject(body).optString("token", "") } catch (_: Throwable) { "" }
                prefs.edit().putString("api_token", t).apply()
                Resp(200, jo("ok" to true, "auth_required" to t.isNotEmpty()).toString())
            }

            path == "/api/clash" || path.startsWith("/api/clash/") -> {
                val suffix = path.removePrefix("/api/clash").ifEmpty { "/" }
                val coreTarget = suffix + if (query.isEmpty()) "" else "?$query"
                val payload = body.takeIf { it.isNotEmpty() && method != "GET" && method != "HEAD" }
                try {
                    val (code, text) = coreRaw(method, coreTarget, payload)
                    Resp(code, text)
                } catch (e: Throwable) {
                    Resp(502, jo("ok" to false, "error" to "core unreachable: ${e.message}").toString())
                }
            }

            else -> Resp(404, jo("ok" to false, "error" to "unknown endpoint", "path" to path).toString())
        }
    }

    private fun hello(): Resp = Resp(200, jo(
        "ok" to true,
        "app" to "cmfa-api",
        "version" to 1,
        "api_port" to PORT,
        "core_port" to CORE_PORT,
        "endpoints" to JSONArray().apply {
            put("GET  /api/status          合并状态（App + 配置 + 内核）")
            put("POST /api/vpn/start|stop|toggle   App 级 VPN 开关")
            put("POST /api/subscription   建/拉/激活订阅 {\"url\",\"name\",\"interval_min\",\"activate\"}")
            put("POST /api/subscription/refresh   刷新当前订阅")
            put("GET  /api/profiles / DELETE /api/profiles/{uuid}")
            put("GET  /api/groups / POST /api/proxy/select {\"group\",\"name\"}")
            put("POST /api/mode {\"mode\":\"rule|global|direct\"}")
            put("POST /api/controller/inject   重注入 9090 密钥")
            put("ANY  /api/clash/{path}     代理 mihomo 9090（自动带 Bearer）")
            put("GET  /api/token / POST /api/token   可选鉴权")
            put("GET  /api/connections     内核实时连接原始快照")
            put("GET  /api/traffic         按 app 聚合：连接数/上下行/访问域名")
            put("GET  /api/apps            uid 到包名/应用名映射")
            put("GET  /api/history        访问历史（域名级 URL + app + 字节）")
            put("POST /api/history/clear  清空历史")
            put("POST /api/app/kill {package}   立刻掐该 app 现有连接")
            put("POST /api/app/block|unblock {package}   持续封杀/解封")
            put("GET  /api/blocks / GET /api/capture   名单 / 抓包能力边界")
            put("POST /api/capture/http {enable,port}  可选：开本地 HTTP 明文代理（URL/Header 可见）")
            put("GET|POST /api/observe {find_process}  流量观测开关（always 才能拿到 uid/包名）")
            put("GET  /api/group/nodes?group=  列组内节点 + 当前选中 + 延迟")
            put("POST /api/subscription/import {yaml,name}  直接导入配置文本（非订阅链接）")
        },
    ).toString())

    // ------------------------------------------------------------ App 操作

    private fun isRunning(): Boolean = try {
        StatusClient(app).currentProfile() != null
    } catch (_: Throwable) {
        false
    }

    private fun vpnStart(): Resp {
        injectController()
        return try {
            val pending = app.startClashService()
            if (pending != null) {
                Resp(409, jo(
                    "ok" to false,
                    "error" to "vpn_permission_required",
                    "hint" to "打开一次 CMFA 授予 VPN 权限；之后 /api/vpn/start 可无 UI 直接拉起",
                ).toString())
            } else {
                Resp(200, jo("ok" to true, "action" to "start", "status" to merged()).toString())
            }
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    private fun vpnStop(): Resp {
        return try {
            app.stopClashService()
            Resp(200, jo("ok" to true, "action" to "stop", "status" to merged()).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    // ------------------------------------------------------------ 流量观测 / 精准封杀

    /** 每 2s 拉一次 /connections：写访问历史（域名级 URL）+ 持续掐被拉黑 app 的新连接。 */
    private fun watchLoop() {
        while (running) {
            try {
                val (code, text) = coreRaw("GET", "/connections", null)
                if (code in 200..299 && text.isNotEmpty()) {
                    val arr = try { JSONObject(text).optJSONArray("connections") ?: JSONArray() } catch (_: Throwable) { JSONArray() }
                    lastConns = arr
                    val now = System.currentTimeMillis()
                    val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(now))
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        val id = c.optString("id", "")
                        if (id.isEmpty()) continue
                        val meta = c.optJSONObject("metadata") ?: JSONObject()
                        val rawProc = meta.optString("process", "").ifEmpty { meta.optString("processPath", "") }
                        val uid = meta.optString("uid", "")
                        val proc = resolveApp(rawProc, uid)
                        val host = meta.optString("sniffHost", "").ifEmpty { meta.optString("host", "") }
                        // 访问历史：每个连接 id 一条，含首见/末见/累计字节/域名/协议/规则/链路
                        val rec = historyStore[id] ?: JSONObject().also {
                            it.put("id", id)
                            it.put("first_seen", ts)
                            it.put("first_seen_ms", now)
                            it.put("app", proc)
                            it.put("uid", uid)
                            it.put("host", host)
                            it.put("network", meta.optString("network", ""))
                            it.put("dest_port", meta.optString("destinationPort", ""))
                            it.put("rule", c.optString("rule", ""))
                            it.put("chains", c.optJSONArray("chains") ?: JSONArray())
                            historyStore[id] = it
                        }
                        rec.put("last_seen", ts)
                        rec.put("last_seen_ms", now)
                        rec.put("upload", c.optLong("upload", 0))
                        rec.put("download", c.optLong("download", 0))
                        // 持续封杀：命中包名或 uid 就把这条连接掐掉
                        if (blocked.isNotEmpty() &&
                            blocked.any { matchApp(it, proc, uid) }) {
                            runCatching { coreRaw("DELETE", "/connections/$id", null) }
                            rec.put("killed_by_block", true)
                        }
                    }
                    // 防止无限膨胀
                    if (historyStore.size > 4000) {
                        val sorted = historyStore.values.sortedBy { it.optLong("first_seen_ms", 0) }
                        sorted.take(2000).forEach { historyStore.remove(it.optString("id", "")) }
                    }
                }
            } catch (_: Throwable) {
                // 内核没起来/隧道抽风都正常，下一轮再拉
            }
            try { Thread.sleep(2_000) } catch (_: Throwable) { break }
        }
    }

    /** 内核实时连接原始快照（仅透传，给排障用）。 */
    private fun connectionsRaw(): Resp = try {
        val (code, text) = coreRaw("GET", "/connections", null)
        Resp(code, if (text.isEmpty()) jo("ok" to false, "error" to "core unreachable").toString() else text)
    } catch (e: Throwable) {
        Resp(502, jo("ok" to false, "error" to e.message).toString())
    }

    /** 按 app 聚合：连接数 / 上下行 / 访问过的域名，一次看清「哪个 app 在出流量」。 */
    private fun traffic(): Resp {
        val map = LinkedHashMap<String, JSONObject>()
        for (i in 0 until lastConns.length()) {
            val c = lastConns.optJSONObject(i) ?: continue
            val meta = c.optJSONObject("metadata") ?: JSONObject()
            val uid = meta.optString("uid", "")
            val proc = resolveApp(meta.optString("process", "").ifEmpty { meta.optString("processPath", "") }, uid)
            val a = map[proc] ?: JSONObject().also {
                it.put("app", proc)
                it.put("uid", uid)
                it.put("connections", 0)
                it.put("upload", 0L)
                it.put("download", 0L)
                it.put("hosts", JSONArray())
                map[proc] = it
            }
            a.put("connections", a.optInt("connections", 0) + 1)
            a.put("upload", a.optLong("upload", 0) + c.optLong("upload", 0))
            a.put("download", a.optLong("download", 0) + c.optLong("download", 0))
            val h = meta.optString("sniffHost", "").ifEmpty { meta.optString("host", "") }
            if (h.isNotEmpty()) {
                val hs = a.optJSONArray("hosts")!!
                var found = false
                for (k in 0 until hs.length()) if (hs.optString(k) == h) { found = true; break }
                if (!found) hs.put(h)
            }
        }
        val arr = JSONArray(map.values.sortedByDescending { it.optLong("upload", 0) + it.optLong("download", 0) })
        return Resp(200, jo("ok" to true, "apps" to arr, "total_connections" to lastConns.length()).toString())
    }

    /** uid → 包名 / 应用名映射（App 自己的 PackageManager，不靠内核）。 */
    private fun apps(query: String): Resp {
        val filter = parseQuery(query)["uid"]?.trim().orEmpty()
        val arr = JSONArray()
        return try {
            val pm = app.packageManager
            @Suppress("DEPRECATION")
            for (pi in pm.getInstalledPackages(0)) {
                val ai = pi.applicationInfo ?: continue
                val uid = ai.uid.toString()
                if (filter.isNotEmpty() && uid != filter && !pi.packageName.contains(filter, true)) continue
                arr.put(jo(
                    "uid" to uid,
                    "package" to pi.packageName,
                    "label" to pm.getApplicationLabel(ai).toString(),
                    "system" to ((ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0),
                ))
            }
            Resp(200, jo("ok" to true, "apps" to arr, "count" to arr.length()).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    /** 访问历史：域名级 URL + app + 字节。支持 ?limit=N / ?app=包名 / ?host=关键字。 */
    private fun history(query: String): Resp {
        val q = parseQuery(query)
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 4000) ?: 200
        val appFilter = q["app"]?.trim().orEmpty()
        val hostFilter = q["host"]?.trim().orEmpty()
        val list = historyStore.values.filter {
            val recApp = it.optString("app", "")
            val recUid = it.optString("uid", "")
            (appFilter.isEmpty() || recApp.contains(appFilter, true) ||
                recUid == appFilter || resolveApp("", recUid).contains(appFilter, true)) &&
                (hostFilter.isEmpty() || it.optString("host", "").contains(hostFilter, true))
        }.sortedByDescending { it.optLong("last_seen_ms", 0) }
            .take(limit)
        return Resp(200, jo("ok" to true, "total" to historyStore.size, "shown" to list.size,
            "records" to JSONArray(list)).toString())
    }

    private fun historyClear(): Resp {
        historyStore.clear()
        return Resp(200, jo("ok" to true, "cleared" to true).toString())
    }

    /** 立刻掐掉某个 app 的所有现有连接（一次性的，不写黑名单）。 */
    private fun appKill(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val target = o.optString("package", "").ifEmpty { o.optString("app", "") }
        if (target.isBlank()) return Resp(400, jo("ok" to false, "error" to "package/app required").toString())
        var killed = 0
        for (i in 0 until lastConns.length()) {
            val c = lastConns.optJSONObject(i) ?: continue
            val meta = c.optJSONObject("metadata") ?: continue
            val proc = meta.optString("process", "")
            val uid = meta.optString("uid", "")
            if (matchApp(target, proc, uid)) {
                val id = c.optString("id", "")
                if (id.isNotEmpty()) {
                    runCatching { coreRaw("DELETE", "/connections/$id", null) }
                    killed++
                }
            }
        }
        return Resp(200, jo("ok" to true, "app" to target, "killed" to killed).toString())
    }

    /** 把封杀名单写进 override → patchOverride 发 ACTION_OVERRIDE_CHANGED →
     *  ConfigurationModule 自动 Clash.load() 重载 → Go patchDenyRules prepend REJECT 规则（事前拒绝）。 */
    private fun applyDenyRules() {
        val cm = clashManager() ?: return
        try {
            val ov = cm.queryOverride(Clash.OverrideSlot.Persist)
            ov.externalController = "127.0.0.1:$CORE_PORT"
            ov.secret = secret
            ov.allowLan = false
            ov.denyPackages = blocked.sorted().takeIf { it.isNotEmpty() }
            cm.patchOverride(Clash.OverrideSlot.Persist, ov)
            Log.d("CMFA API: deny rules applied (${blocked.size})")
        } catch (e: Throwable) {
            Log.w("CMFA API applyDenyRules failed: ${e.message}")
        }
    }

    /** 持续封杀 / 解封：规则层 REJECT（事前拒绝） + 内存黑名单（watchLoop 兜底掐漏网连接）。 */
    private fun appBlock(body: String, on: Boolean): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val target = o.optString("package", "").ifEmpty { o.optString("app", "") }
        if (target.isBlank()) return Resp(400, jo("ok" to false, "error" to "package/app required").toString())
        if (on) blocked.add(target) else blocked.remove(target)
        prefs.edit().putStringSet("deny_packages", HashSet(blocked)).apply()
        applyDenyRules()   // 触发内核重载，REJECT 规则立即生效
        // 顺手把现有连接也断一次（重载前已建的连接规则管不到）
        if (on) runCatching { appKill(JSONObject().put("package", target).toString()) }
        return Resp(200, jo("ok" to true, "app" to target, "blocked" to on,
            "enforced_by" to "rule:REJECT (prepend, survives reconnect)",
            "blocked_list" to JSONArray(blocked.sorted())).toString())
    }

    /** 抓包能力边界：不撒谎，说清能拿什么、拿不到什么。 */
    private fun captureInfo(): Resp = Resp(200, jo(
        "ok" to true,
        "reverse_proxy" to false,
        "note" to "9091 是控制面，不承载流量；数据流在 tun0/内核，本 API 只读连接元数据 + 发指令。",
        "can" to JSONArray().apply {
            put("按 app 看实时流量：GET /api/traffic（uid/包名/连接数/上下行/域名）")
            put("看访问过哪个域名：GET /api/history（host = SNI sniff / HTTP Host，域名级 URL）")
            put("精准毙掉 app：POST /api/app/kill（掐现有）或 /api/app/block（持续封杀）")
            put("抓明文 HTTP：POST /api/capture/http 开本地 mixed-port 代理，配 sniffer 后明文 URL/Header 可见")
        },
        "cannot" to JSONArray().apply {
            put("HTTPS 的 path / body / payload：TLS 加密，非 root 无法做 MITM（Android 7+ 不信任用户 CA）")
            put("小黄鸟式全量抓包：需要系统 CA + 独立 MITM 栈，不是改几行 API 能平替")
            put("9091 自己当反向代理：不承载流量，只是指挥面")
        },
        "https_visibility" to "仅域名（SNI/HTTP CONNECT host），无 path 无 payload",
    ).toString())

    /** 可选：注入一个本地 mixed-port 代理，给 curl -x / 小黄鸟等工具导流量用。
     *  开 sniffer 后，明文 HTTP 的完整 URL 与 Header 在内核侧可见；HTTPS 仍只有 CONNECT host。 */
    private fun captureHttp(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val enable = o.optBoolean("enable", true)
        val port = o.optInt("port", 7890)
        val cm = clashManager() ?: return unavailable()
        return try {
            val ov = cm.queryOverride(Clash.OverrideSlot.Persist)
            ov.mixedPort = if (enable) port else null
            ov.externalController = "127.0.0.1:$CORE_PORT"
            ov.secret = secret
            ov.allowLan = false
            cm.patchOverride(Clash.OverrideSlot.Persist, ov)
            Resp(200, jo(
                "ok" to true,
                "http_proxy" to if (enable) "127.0.0.1:$port" else JSONObject.NULL,
                "enabled" to enable,
                "hint" to if (enable)
                    "把工具/浏览器代理指到 127.0.0.1:$port；明文 HTTP 可看完整 URL，HTTPS 只有 CONNECT 里的域名"
                else "已关闭 mixed-port",
            ).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    /** 流量观测开关：GET 看状态，POST {"find_process":"always|strict"} 切换。
     *  改 override 会发 ACTION_OVERRIDE_CHANGED → ConfigurationModule 自动重载内核，无需重启 VPN。 */
    private fun observeInfo(): Resp {
        val (code, text) = try { coreRaw("GET", "/configs", null) } catch (_: Throwable) { 502 to "" }
        val cur = try { JSONObject(text).optString("find-process-mode", "unknown") } catch (_: Throwable) { "unknown" }
        return Resp(200, jo(
            "ok" to true,
            "find_process_pref" to if (findProcessAlways()) "always" else "strict",
            "find_process_core" to cur,
            "hint" to "always 才会对每条连接解析 uid/包名；strict 仅在命中 PROCESS 规则时解析",
        ).toString())
    }

    private fun observeSet(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val v = o.optString("find_process", "").ifEmpty { o.optString("mode", "") }
        if (v !in setOf("always", "strict", "off"))
            return Resp(400, jo("ok" to false, "error" to "find_process must be always|strict|off").toString())
        val always = v == "always"
        prefs.edit().putBoolean("find_process_always", always).apply()
        injectController()   // 重载内核使生效
        return Resp(200, jo(
            "ok" to true,
            "find_process" to v,
            "note" to "override 已写入，ConfigurationModule 会自动重载内核；新连接即可带包名/uid",
        ).toString())
    }

    /** 列组内节点：当前选中 + 节点名 + 类型 + 延迟（走 App 的 queryProxyGroup，非裸透传）。 */
    private fun groupNodes(query: String): Resp {
        val q = parseQuery(query)
        val name = q["group"]?.trim().orEmpty()
        val cm = clashManager() ?: return unavailable()
        return try {
            if (name.isBlank()) {
                // 不指定组就只列组名（和 /api/groups 一致，省一次往返）
                val gs = JSONArray()
                cm.queryProxyGroupNames(false).forEach { gs.put(it) }
                return Resp(200, jo("ok" to true, "groups" to gs).toString())
            }
            val g = cm.queryProxyGroup(name, com.github.kr328.clash.core.model.ProxySort.Default)
            val arr = JSONArray()
            g.proxies.forEach { p ->
                arr.put(jo(
                    "name" to p.name,
                    "type" to p.type,
                    "delay" to p.delay,
                    "selected" to (p.name == g.now),
                    "is_group" to p.isGroup,
                ))
            }
            Resp(200, jo("ok" to true, "group" to name, "type" to g.type,
                "now" to g.now, "count" to arr.length(), "nodes" to arr,
                "hint" to "选节点：cmfa use \"$name\" \"<节点名>\"").toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    /** 一次性本地 HTTP 源：给 configImport 里的 Url profile 提供 YAML 文本。
     *  token 随机、只存活到本次导入结束；不落盘、不写日志、不进 profiles 历史。 */
    private val rawStore = ConcurrentHashMap<String, String>()

    private fun rawConfig(path: String): Resp {
        val id = path.removePrefix("/_raw/")
        val text = rawStore.remove(id)
            ?: return Resp(404, jo("ok" to false, "error" to "raw config not found").toString())
        return Resp(200, text, "text/yaml; charset=utf-8")
    }

    /** 直接贴 YAML / 配置文本导入（非订阅链接）：
     *  建一个指向 _raw 的 Url profile → CMFA 原生 fetchAndValid 拉文本 → 校验 → 激活。 */
    private fun configImport(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val yaml = o.optString("yaml", "").ifEmpty { o.optString("config", "") }
        if (yaml.isBlank()) return Resp(400, jo("ok" to false, "error" to "yaml/config required").toString())
        val name = o.optString("name", "API 导入配置")
        val activate = o.optBoolean("activate", true)
        val pm = profileManager() ?: return unavailable()
        val id = UUID.randomUUID().toString()
        rawStore[id] = yaml
        val src = "http://127.0.0.1:$PORT/_raw/$id"
        var createdUuid: UUID? = null
        return try {
            val uuid = runBlocking(Dispatchers.IO) {
                val u = pm.create(Profile.Type.Url, name, src, null)
                createdUuid = u
                pm.patch(u, name, src, 0L, null)
                pm.commit(u, null)
                if (activate) pm.queryByUUID(u)?.let { pm.setActive(it) }
                u
            }
            rawStore.remove(id)
            injectController()
            Resp(200, jo("ok" to true, "uuid" to uuid.toString(),
                "name" to name, "bytes" to yaml.toByteArray(Charsets.UTF_8).size,
                "status" to merged()).toString())
        } catch (e: Throwable) {
            rawStore.remove(id)
            // commit/fetchAndValid 失败会留下 pending=true 的脏 profile，必须回滚
            createdUuid?.let {
                runCatching { runBlocking(Dispatchers.IO) { pm.delete(it) } }
            }
            err(502, e)
        }
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (kv in q.split("&")) {
            val i = kv.indexOf('=')
            if (i < 0) continue
            try {
                m[java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8")] =
                    java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8")
            } catch (_: Throwable) {}
        }
        return m
    }

    private fun status(): Resp = Resp(200, merged().toString())

    private fun merged(): JSONObject {
        val j = jo(
            "ok" to true,
            "vpn_running" to isRunning(),
            "vpn_permission_granted" to (VpnService.prepare(app) == null),
            "enable_vpn" to UiStore(app).enableVpn,
            "active_profile" to StatusClient(app).currentProfile(),
            "api_port" to PORT,
            "core_controller" to "127.0.0.1:$CORE_PORT",
            "api_auth" to apiToken.isNotEmpty(),
        )
        try {
            profileManager()?.let { pm ->
                runBlocking(Dispatchers.IO) {
                    val active = pm.queryActive()
                    j.put("active_profile_uuid", active?.uuid?.toString() ?: JSONObject.NULL)
                    j.put("active_profile_source", active?.let { maskUrl(it.source) } ?: JSONObject.NULL)
                    j.put("profile_count", pm.queryAll().size)
                }
            }
        } catch (e: Throwable) {
            j.put("profile_error", e.message ?: "unknown")
        }
        val (code, text) = try {
            coreRaw("GET", "/version", null)
        } catch (_: Throwable) {
            0 to ""
        }
        j.put("core_reachable", code in 200..299)
        j.put("core_version", if (code in 200..299) text else JSONObject.NULL)
        j.put("lock", UiLock.asJson(app))
        return j
    }

    private fun profiles(): Resp {
        val pm = profileManager() ?: return unavailable()
        return try {
            val arr = JSONArray()
            val list = runBlocking(Dispatchers.IO) { pm.queryAll() }
            list.forEach { p ->
                arr.put(jo(
                    "uuid" to p.uuid.toString(),
                    "name" to p.name,
                    "type" to p.type.name,
                    "source" to maskUrl(p.source),
                    "active" to p.active,
                    "interval" to p.interval,
                    "imported" to p.imported,
                    "pending" to p.pending,
                    "updated_at" to p.updatedAt,
                ))
            }
            Resp(200, jo("ok" to true, "profiles" to arr).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    private fun profileDelete(id: String): Resp {
        val pm = profileManager() ?: return unavailable()
        return try {
            runBlocking(Dispatchers.IO) { pm.delete(UUID.fromString(id)) }
            Resp(200, jo("ok" to true, "deleted" to id, "status" to merged()).toString())
        } catch (e: Throwable) {
            err(400, e)
        }
    }

    /** 真实 App 操作：建 Url profile → 拉取订阅 → 设为 active，不是 9090 的透传。 */
    private fun subscription(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val url = o.optString("url", "")
        if (url.isBlank()) return Resp(400, jo("ok" to false, "error" to "url required").toString())
        val name = o.optString("name", "API Profile")
        val intervalMin = o.optLong("interval_min", 0L)
        val intervalMs = if (intervalMin > 0) TimeUnit.MINUTES.toMillis(intervalMin) else 0L
        val activate = o.optBoolean("activate", true)
        val pm = profileManager() ?: return unavailable()
        return try {
            val uuid = runBlocking(Dispatchers.IO) {
                val id = pm.create(Profile.Type.Url, name, url, null)
                pm.patch(id, name, url, intervalMs, null)
                pm.commit(id, null)
                if (activate) pm.queryByUUID(id)?.let { pm.setActive(it) }
                id
            }
            injectController()
            Resp(200, jo("ok" to true, "uuid" to uuid.toString(), "status" to merged()).toString())
        } catch (e: Throwable) {
            err(502, e)
        }
    }

    private fun subscriptionRefresh(): Resp {
        val pm = profileManager() ?: return unavailable()
        return try {
            val active = runBlocking(Dispatchers.IO) { pm.queryActive() }
                ?: return Resp(409, jo("ok" to false, "error" to "no active profile").toString())
            runBlocking(Dispatchers.IO) { pm.update(active.uuid) }
            Resp(200, jo("ok" to true, "refreshed" to active.uuid.toString()).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    private fun groups(): Resp {
        val cm = clashManager() ?: return unavailable()
        return try {
            val arr = JSONArray()
            cm.queryProxyGroupNames(false).forEach { arr.put(it) }
            Resp(200, jo("ok" to true, "groups" to arr).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    /** 走 App 的 patchSelector：会写 SelectionDao，重开 VPN 后仍然记得，比裸 9090 PUT 强。 */
    private fun proxySelect(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val group = o.optString("group", "")
        val name = o.optString("name", "")
        if (group.isBlank() || name.isBlank())
            return Resp(400, jo("ok" to false, "error" to "group/name required").toString())
        val cm = clashManager() ?: return unavailable()
        return try {
            val ok = cm.patchSelector(group, name)
            Resp(200, jo("ok" to ok, "group" to group, "name" to name).toString())
        } catch (e: Throwable) {
            err(500, e)
        }
    }

    /** 切换已有配置为 active（不用重新建 Url profile）。锁了 UI 后靠这个 + API 完成换配置。 */
    private fun profileActivate(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val id = o.optString("uuid", "")
        if (id.isBlank()) return Resp(400, jo("ok" to false, "error" to "uuid required").toString())
        val pm = profileManager() ?: return unavailable()
        return try {
            val uuid = UUID.fromString(id)
            val profile = runBlocking(Dispatchers.IO) { pm.queryByUUID(uuid) }
                ?: return Resp(404, jo("ok" to false, "error" to "profile not found").toString())
            runBlocking(Dispatchers.IO) { pm.setActive(profile) }
            injectController()
            Resp(200, jo("ok" to true, "uuid" to id, "name" to profile.name,
                "status" to merged()).toString())
        } catch (e: Throwable) {
            err(400, e)
        }
    }

    /** POST /api/lock：整份替换规则（不带 deny = ALL 全锁）。绝不检查当前锁 —— API 永远畅通。 */
    private fun lockSet(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { JSONObject() }
        val arr = o.optJSONArray("deny")
        val deny: Collection<String> = if (arr == null) {
            val single = o.optString("deny", "")
            if (single.isBlank()) listOf("ALL") else listOf(single)
        } else {
            (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
        }
        val applied = UiLock.replace(app, deny)
        return Resp(200, jo("ok" to true, "mode" to "replace",
            "applied" to JSONArray(applied.sorted()),
            "lock" to UiLock.asJson(app)).toString())
    }

    /** 开机/覆盖装后自动拉起 VPN 的开关。 */
    private fun autostartSet(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val on = o.optBoolean("enable", true)
        UiLock.setAutostart(app, on)
        return Resp(200, jo("ok" to true, "autostart_vpn" to on,
            "hint" to "App 进程启动（含开机/覆盖装广播）后自动 startClashService").toString())
    }

    /** 改内核 mode 后直接回吐合并状态，一次调用拿全。 */
    private fun setMode(body: String): Resp {
        val o = try { JSONObject(body) } catch (e: Throwable) { return err(400, e) }
        val mode = o.optString("mode", "")
        if (mode !in setOf("rule", "global", "direct"))
            return Resp(400, jo("ok" to false, "error" to "mode must be rule/global/direct").toString())
        return try {
            val (code, text) = coreRaw("PATCH", "/configs", JSONObject().put("mode", mode).toString())
            if (code !in 200..299)
                Resp(502, jo("ok" to false, "error" to "core returned $code", "body" to text).toString())
            else
                Resp(200, jo("ok" to true, "mode" to mode, "status" to merged()).toString())
        } catch (e: Throwable) {
            Resp(502, jo("ok" to false, "error" to "core unreachable: ${e.message}").toString())
        }
    }

    // ------------------------------------------------------------ 内核控制器注入

    /** 流量观测开关：默认开。always 才能让 mihomo 每条连接都解析包名/uid；strict 只在命中 PROCESS 规则时解析。 */
    private fun findProcessAlways(): Boolean = prefs.getBoolean("find_process_always", true)

    /** 把 external-controller 强制成 127.0.0.1:9090 + 随机 secret；allow-lan 恒 false；
     *  同时按观测开关注入 find-process-mode（改 override 会触发 ConfigurationModule 自动重载内核）。 */
    private fun injectController() {
        val cm = clashManager() ?: return
        try {
            val ov = cm.queryOverride(Clash.OverrideSlot.Persist)
            ov.externalController = "127.0.0.1:$CORE_PORT"
            ov.secret = secret
            ov.allowLan = false
            ov.findProcessMode = if (findProcessAlways())
                ConfigurationOverride.FindProcessMode.Always
            else
                ConfigurationOverride.FindProcessMode.Strict
            ov.denyPackages = blocked.sorted().takeIf { it.isNotEmpty() }
            cm.patchOverride(Clash.OverrideSlot.Persist, ov)
            Log.d("CMFA API: override injected (find-process=${if (findProcessAlways()) "always" else "strict"})")
        } catch (e: Throwable) {
            Log.w("CMFA API inject failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------ binder

    private fun remoteOrNull(): IRemoteService? {
        remote?.let { return it }
        return try {
            runBlocking(Dispatchers.IO) {
                withTimeout(4_000) { connection.remote.get() }
            }.also { remote = it }
        } catch (_: Throwable) {
            null
        }
    }

    private fun profileManager(): IProfileManager? = try { remoteOrNull()?.profile() } catch (_: Throwable) { null }
    private fun clashManager(): IClashManager? = try { remoteOrNull()?.clash() } catch (_: Throwable) { null }

    // ------------------------------------------------------------ 原始 HTTP（支持 PATCH，绕开 cleartext 限制）

    private fun coreRaw(method: String, target: String, body: String?): Pair<Int, String> {
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", CORE_PORT), 2_000)
            s.soTimeout = 8_000
            val out = s.getOutputStream()
            val payload = body?.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
                .append(method).append(' ').append(target).append(" HTTP/1.1").append(CRLF)
                .append("Host: 127.0.0.1:").append(CORE_PORT).append(CRLF)
                .append("Authorization: Bearer ").append(secret).append(CRLF)
                .append("Content-Type: application/json").append(CRLF)
                .append("Accept: application/json").append(CRLF)
                .append("Connection: close").append(CRLF)
            if (payload != null) sb.append("Content-Length: ").append(payload.size).append(CRLF)
            sb.append(CRLF)
            out.write(sb.toString().toByteArray(Charsets.US_ASCII))
            if (payload != null) out.write(payload)
            out.flush()

            val raw = s.getInputStream().readBytes()
            if (raw.isEmpty()) return 502 to ""
            val idx = indexOfSeq(raw, "$CRLF$CRLF".toByteArray(Charsets.US_ASCII))
            if (idx < 0) return 502 to String(raw, Charsets.UTF_8)
            val head = String(raw, 0, idx, Charsets.US_ASCII)
            val statusLine = head.lineSequence().firstOrNull() ?: return 502 to ""
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 502
            val rest = raw.copyOfRange(idx + 4, raw.size)
            val text = if (head.lowercase().contains("transfer-encoding: chunked"))
                dechunk(rest) else String(rest, Charsets.UTF_8)
            return code to text
        } finally {
            try { s.close() } catch (_: Throwable) {}
        }
    }

    private fun indexOfSeq(data: ByteArray, seq: ByteArray, from: Int = 0): Int {
        var i = from
        while (i <= data.size - seq.size) {
            var ok = true
            for (j in seq.indices) {
                if (data[i + j] != seq[j]) { ok = false; break }
            }
            if (ok) return i
            i++
        }
        return -1
    }

    private fun dechunk(data: ByteArray): String {
        val out = ByteArrayOutputStream()
        var i = 0
        val crlf = CRLF.toByteArray(Charsets.US_ASCII)
        while (i < data.size) {
            val eol = indexOfSeq(data, crlf, i)
            if (eol < 0) break
            val line = String(data, i, eol - i, Charsets.US_ASCII).substringBefore(';').trim()
            val size = line.toIntOrNull(16) ?: break
            if (size <= 0) break
            val start = eol + 2
            if (start + size > data.size) { out.write(data, start, data.size - start); break }
            out.write(data, start, size)
            i = start + size + 2
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    // ------------------------------------------------------------ 小工具

    /** 订阅链接含身份 token：API 响应一律脱敏，只留 scheme://host/path 前 12 字符。 */
    private fun maskUrl(s: String): String = try {
        val u = java.net.URI(s)
        val host = u.host ?: "?"
        val path = u.path?.take(12) ?: ""
        "${u.scheme}://${host}${path}…"
    } catch (_: Throwable) {
        "***"
    }

    private fun jo(vararg pairs: Pair<String, Any?>): JSONObject {
        val o = JSONObject()
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o
    }

    private fun err(code: Int, e: Throwable): Resp =
        Resp(code, jo("ok" to false, "error" to (e.message ?: e.toString())).toString())

    private fun unavailable(): Resp = Resp(503, jo(
        "ok" to false,
        "error" to "RemoteService not bound yet",
        "hint" to "稍等 1-2s 重试；App 进程刚起来时 binder 还没连上",
    ).toString())
}

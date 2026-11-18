package com.github.kr328.clash.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 锁状态：9091 控制面与所有 Activity / TileService / Receiver 都在 App 主进程，
 * 共用 "api_control" SharedPreferences，天然互通。
 *
 * 语义（用户 2026-11-18 定的规矩）：
 *   - POST /api/lock  传的 deny 数组 = **整份替换**旧规则，不是叠加；不带 deny = ALL（全锁）
 *   - POST /api/unlock = 清空全部规则
 *   - 只锁 UI 入口；API 路径永远不检查锁，保证「锁了 UI 也能通过 api 干所有事」
 */
object UiLock {
    private const val PREFS = "api_control"
    private const val KEY_RULES = "ui_lock_rules"
    private const val KEY_AUTOSTART = "autostart_vpn"

    /** 可锁的能力位。ALL 是「整个 UI 点不了」的最严档。 */
    val KEYS = listOf("ALL", "VPN", "PROFILE", "PROXY", "SETTINGS", "RULES")

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 读取当前规则集（未锁时空集）。 */
    fun rules(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_RULES, null)?.toSet() ?: emptySet()

    /** 整份替换：新规则覆盖旧规则。非法值忽略；ALL 与其它并存时归一成纯 ALL。 */
    fun replace(ctx: Context, raw: Collection<String>): Set<String> {
        val valid = raw.map { it.uppercase() }.filter { it in KEYS }.toSet()
        val final = if (valid.contains("ALL")) setOf("ALL") else valid
        prefs(ctx).edit().putStringSet(KEY_RULES, final).apply()
        return final
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_RULES).apply()
    }

    /** 某个能力是否锁了。ALL 一锁全锁。 */
    fun isBlocked(ctx: Context, key: String): Boolean {
        val r = rules(ctx)
        return r.contains("ALL") || r.contains(key.uppercase())
    }

    /** UI 是否完全点不了（最严档）。 */
    fun isAllLocked(ctx: Context): Boolean = rules(ctx).contains("ALL")

    fun asJson(ctx: Context): JSONObject = JSONObject().apply {
        put("locked", rules(ctx).isNotEmpty())
        put("rules", JSONArray(rules(ctx).sorted()))
        put("all", isAllLocked(ctx))
        put("vpn_blocked", isBlocked(ctx, "VPN"))
        put("profile_blocked", isBlocked(ctx, "PROFILE"))
        put("proxy_blocked", isBlocked(ctx, "PROXY"))
        put("settings_blocked", isBlocked(ctx, "SETTINGS"))
        put("rules_blocked", isBlocked(ctx, "RULES"))
        put("autostart_vpn", autostart(ctx))
    }

    fun autostart(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTOSTART, false)
    fun setAutostart(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_AUTOSTART, on).apply()
    }
}

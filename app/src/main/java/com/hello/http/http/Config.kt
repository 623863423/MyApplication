package com.hello.http

import android.content.Context

object Cfg {
    // 全局唯一默认端口 —— 改这里即可
    const val DEFAULT_PORT: Int = 19960

    // 统一的 SP 名与 Key，避免魔法字符串散落
    const val PREFS = "conf"
    const val KEY_ROOT_URI = "root_uri"
    const val KEY_PIN = "pin"
    const val KEY_PORT = "port"
    const val KEY_PORT_USER_SET = "port_user_set"

    // 运行状态（给 UI 使用）
    const val KEY_STATE = "state"

    enum class SrvState { IDLE, STARTING, RUNNING }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 统一取端口：只有当用户显式设置(port_user_set=true)时，才用保存的端口；
     * 否则一律返回 DEFAULT_PORT，避免旧值卡住新默认。
     */
    fun appPort(ctx: Context): Int {
        val sp = prefs(ctx)
        val userSet = sp.getBoolean(KEY_PORT_USER_SET, false)
        val p = sp.getInt(KEY_PORT, -1)
        return if (userSet && p in 1024..65535) p else DEFAULT_PORT
    }

    fun setState(ctx: Context, s: SrvState) {
        prefs(ctx).edit().putString(KEY_STATE, s.name).apply()
    }
    fun getState(ctx: Context): SrvState {
        val v = prefs(ctx).getString(KEY_STATE, SrvState.IDLE.name) ?: SrvState.IDLE.name
        return runCatching { SrvState.valueOf(v) }.getOrDefault(SrvState.IDLE)
    }
}

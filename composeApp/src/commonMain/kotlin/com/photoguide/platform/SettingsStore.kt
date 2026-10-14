package com.photoguide.platform

/**
 * 跨平台配置存储。
 * Android -> SharedPreferences；iOS -> NSUserDefaults。
 */
interface SettingsStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
}

expect fun createSettingsStore(): SettingsStore

/**
 * 平台日志。
 * 设计原则 3「绝不静默失败」：任何降级至少要在 logcat / Xcode console 里留痕。
 */
expect fun platformLog(tag: String, message: String)

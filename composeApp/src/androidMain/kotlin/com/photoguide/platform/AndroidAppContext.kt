package com.photoguide.platform

import android.content.Context

/**
 * Android 侧需要一个 Context 才能拿到 SharedPreferences，
 * 而 commonMain 的 createSettingsStore() 是无参的。
 * 用这个 holder 在 Application/Activity 启动时注入一次，避免把 Context 一路透传。
 */
object AndroidAppContext {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun require(): Context = appContext
        ?: error("AndroidAppContext 未初始化：必须在 Activity/Application 启动时调用 init()")
}

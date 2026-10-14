package com.photoguide.platform

import android.content.Context
import android.util.Log

actual fun createSettingsStore(): SettingsStore =
    SharedPrefsSettingsStore(AndroidAppContext.require())

private class SharedPrefsSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("photo_guide", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

actual fun platformLog(tag: String, message: String) {
    Log.d("PhotoGuide/$tag", message)
}

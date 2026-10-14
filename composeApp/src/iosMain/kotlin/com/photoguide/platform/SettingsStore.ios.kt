package com.photoguide.platform

import platform.Foundation.NSUserDefaults

actual fun createSettingsStore(): SettingsStore = NsUserDefaultsSettingsStore()

private class NsUserDefaultsSettingsStore : SettingsStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String?) {
        if (value == null) {
            defaults.removeObjectForKey(key)
        } else {
            defaults.setObject(value, forKey = key)
        }
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }
}

actual fun platformLog(tag: String, message: String) {
    // Xcode console 里会显示为 PhotoGuide/<tag>: <message>
    println("PhotoGuide/$tag: $message")
}

package com.autolaunch.appscheduler

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

/**
 * 配置存储 - 保存用户选择的目标应用、时间和 PIN 码
 */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var targetPackage: String
        get() = sp.getString(KEY_PKG, "") ?: ""
        set(value) = sp.edit().putString(KEY_PKG, value).apply()

    var targetAppName: String
        get() = sp.getString(KEY_APP_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_NAME, value).apply()

    var targetAppIcon: String
        get() = sp.getString(KEY_APP_ICON, "") ?: ""
        set(value) = sp.edit().putString(KEY_APP_ICON, value).apply()

    var serviceRunning: Boolean
        get() = sp.getBoolean(KEY_RUNNING, false)
        set(value) = sp.edit().putBoolean(KEY_RUNNING, value).apply()

    /** PIN 码的 SHA-256 hash，空字符串表示未设置 */
    var pinHash: String
        get() = sp.getString(KEY_PIN_HASH, "") ?: ""
        set(value) = sp.edit().putString(KEY_PIN_HASH, value).apply()

    /** 设置 PIN 码（自动 hash）*/
    fun setPin(pin: String) {
        pinHash = sha256(pin)
    }

    /** 验证 PIN 码是否正确 */
    fun verifyPin(pin: String): Boolean {
        val h = pinHash
        if (h.isEmpty()) return true // 没设置 PIN 就直接通过
        return sha256(pin) == h
    }

    /** 清除 PIN 码 */
    fun clearPin() {
        pinHash = ""
    }

    // 四个时间点，格式 "HH:mm"
    fun getTimes(): List<String> {
        val list = mutableListOf<String>()
        for (i in 1..4) {
            list.add(sp.getString("time_$i", DEFAULT_TIMES[i - 1]) ?: DEFAULT_TIMES[i - 1])
        }
        return list
    }

    fun setTime(index: Int, time: String) {
        sp.edit().putString("time_${index + 1}", time).apply()
    }

    companion object {
        private const val NAME = "auto_launch_prefs"
        private const val KEY_PKG = "target_pkg"
        private const val KEY_APP_NAME = "target_app_name"
        private const val KEY_APP_ICON = "target_app_icon"
        private const val KEY_RUNNING = "service_running"
        private const val KEY_PIN_HASH = "pin_hash"
        val DEFAULT_TIMES = listOf("08:10", "11:45", "14:10", "17:45")

        private fun sha256(input: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

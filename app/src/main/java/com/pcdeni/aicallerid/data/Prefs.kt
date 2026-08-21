package com.pcdeni.aicallerid.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class ScanMode { AUTO, ON_DEMAND }

enum class Provider { GEMINI, GROQ }

class Prefs(context: Context) {

    private val store: SharedPreferences = createStore(context.applicationContext)

    var apiKey: String?
        get() = getKey(KEY_API_KEY)
        set(value) = setKey(KEY_API_KEY, value)

    var apiKeyGroq: String?
        get() = getKey(KEY_API_KEY_GROQ)
        set(value) = setKey(KEY_API_KEY_GROQ, value)

    var provider: Provider
        get() {
            val stored = store.getString(KEY_PROVIDER, null) ?: return Provider.GEMINI
            return Provider.entries.firstOrNull { it.name == stored } ?: Provider.GEMINI
        }
        set(value) {
            store.edit().putString(KEY_PROVIDER, value.name).apply()
        }

    var activeApiKey: String?
        get() = if (provider == Provider.GROQ) apiKeyGroq else apiKey
        set(value) {
            if (provider == Provider.GROQ) apiKeyGroq = value else apiKey = value
        }

    private fun getKey(prefKey: String): String? =
        store.getString(prefKey, null)?.takeIf { it.isNotBlank() }

    private fun setKey(prefKey: String, value: String?) {
        val editor = store.edit()
        if (value.isNullOrBlank()) {
            editor.remove(prefKey)
        } else {
            editor.putString(prefKey, value.trim())
        }
        editor.apply()
    }

    var scanMode: ScanMode
        get() {
            val stored = store.getString(KEY_SCAN_MODE, null) ?: return ScanMode.AUTO
            return ScanMode.entries.firstOrNull { it.name == stored } ?: ScanMode.AUTO
        }
        set(value) {
            store.edit().putString(KEY_SCAN_MODE, value.name).apply()
        }

    private fun createStore(context: Context): SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        // Keystore can be unavailable or corrupted on some devices; degrade to plain storage.
        Log.e(TAG, "Encrypted preferences unavailable, falling back to plain preferences", e)
        context.getSharedPreferences(FALLBACK_FILE_NAME, Context.MODE_PRIVATE)
    }

    private companion object {
        const val TAG = "Prefs"
        const val SECURE_FILE_NAME = "prefs_secure"
        const val FALLBACK_FILE_NAME = "prefs_fallback"
        const val KEY_API_KEY = "api_key"
        const val KEY_API_KEY_GROQ = "api_key_groq"
        const val KEY_PROVIDER = "provider"
        const val KEY_SCAN_MODE = "scan_mode"
    }
}

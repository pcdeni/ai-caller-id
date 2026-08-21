package com.pcdeni.aicallerid

import android.app.Application
import com.pcdeni.aicallerid.ai.GeminiClient
import com.pcdeni.aicallerid.ai.GroqClient
import com.pcdeni.aicallerid.ai.LookupClient
import com.pcdeni.aicallerid.billing.PlayBillingManager
import com.pcdeni.aicallerid.data.IntelRepository
import com.pcdeni.aicallerid.data.Prefs
import com.pcdeni.aicallerid.data.Provider
import com.pcdeni.aicallerid.notify.NotificationHelper

class App : Application() {

    val prefs: Prefs by lazy { Prefs(this) }
    val gemini: GeminiClient by lazy { GeminiClient() }
    val groq: GroqClient by lazy { GroqClient() }
    val repository: IntelRepository by lazy { IntelRepository(this, prefs) { activeClient() } }
    val billing: PlayBillingManager by lazy { PlayBillingManager(this, prefs) }

    fun activeClient(): LookupClient =
        if (prefs.provider == Provider.GROQ) groq else gemini

    override fun onCreate() {
        super.onCreate()
        instance = this
        NotificationHelper.ensureChannel(this)
        // Debug builds keep the stub switch; release builds restore the real entitlement early
        // so an incoming call right after process start is gated correctly.
        if (!BuildConfig.DEBUG) {
            billing.connect()
        }
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

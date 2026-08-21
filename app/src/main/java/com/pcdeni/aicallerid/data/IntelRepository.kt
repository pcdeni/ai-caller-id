package com.pcdeni.aicallerid.data

import android.content.Context
import android.util.Log
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.ai.LookupClient
import com.pcdeni.aicallerid.model.CallerIntel
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

class IntelRepository(
    private val context: Context,
    private val prefs: Prefs,
    private val clientProvider: () -> LookupClient,
) {

    private val historyFile = File(context.filesDir, "history.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loaded = false

    private val _history = MutableStateFlow<List<CallerIntel>>(emptyList())
    val history: StateFlow<List<CallerIntel>> = _history.asStateFlow()

    init {
        scope.launch { ensureLoaded() }
    }

    suspend fun lookup(rawNumber: String, forceFresh: Boolean = false): Result<CallerIntel> {
        val digits = rawNumber.filter { it.isDigit() }
        if (digits.isEmpty()) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.error_bad_number)))
        }
        ensureLoaded()
        if (!forceFresh) {
            val cached = mutex.withLock {
                _history.value.firstOrNull { cacheKey(it) == digits }
            }
            if (cached != null) {
                val hit = cached.copy(fromCache = true)
                updateHistory { list -> listOf(hit) + list.filter { cacheKey(it) != digits } }
                return Result.success(hit)
            }
        }
        val apiKey = prefs.activeApiKey
            ?: return Result.failure(IllegalStateException("API key missing"))
        return clientProvider().lookup(rawNumber.trim(), apiKey).onSuccess { intel ->
            updateHistory { list -> listOf(intel) + list.filter { cacheKey(it) != digits } }
        }
    }

    fun clearHistory() {
        scope.launch {
            ensureLoaded()
            updateHistory { emptyList() }
        }
    }

    private fun cacheKey(intel: CallerIntel): String = intel.phoneNumber.filter { it.isDigit() }

    private suspend fun ensureLoaded() {
        mutex.withLock {
            if (loaded) return
            val list = withContext(Dispatchers.IO) { readHistoryFile() }
            _history.value = list
            loaded = true
        }
    }

    private suspend fun updateHistory(transform: (List<CallerIntel>) -> List<CallerIntel>) {
        mutex.withLock {
            val updated = transform(_history.value).take(MAX_HISTORY)
            _history.value = updated
            withContext(Dispatchers.IO) { writeHistoryFile(updated) }
        }
    }

    private fun readHistoryFile(): List<CallerIntel> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(historyFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { CallerIntel.fromJson(it) }
            }.take(MAX_HISTORY)
        } catch (e: JSONException) {
            Log.e(TAG, "History file malformed, starting empty", e)
            emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read history file", e)
            emptyList()
        }
    }

    private fun writeHistoryFile(list: List<CallerIntel>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            historyFile.writeText(arr.toString())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to persist history", e)
        }
    }

    private companion object {
        const val TAG = "IntelRepository"
        const val MAX_HISTORY = 200
    }
}

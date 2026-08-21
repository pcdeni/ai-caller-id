package com.pcdeni.aicallerid.ai

import android.util.Log
import com.pcdeni.aicallerid.App
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.model.CallerIntel
import com.pcdeni.aicallerid.model.SourceRef
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class GeminiClient : LookupClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(phoneNumber: String, apiKey: String): Result<CallerIntel> =
        withContext(Dispatchers.IO) {
            val number = phoneNumber.trim()
            if (!LookupPrompt.isValidNumber(number)) {
                return@withContext Result.failure(
                    IllegalArgumentException(string(R.string.error_bad_number)),
                )
            }
            when (val first = execute(number, apiKey, withTools = true)) {
                is Outcome.Success -> Result.success(first.intel)
                is Outcome.Failure -> Result.failure(first.error)
                Outcome.RetryWithoutTools -> {
                    // Brief pause so an RPM-limit 429 has a chance to clear before the retry.
                    delay(1_500)
                    when (val second = execute(number, apiKey, withTools = false)) {
                        is Outcome.Success -> Result.success(second.intel)
                        is Outcome.Failure -> Result.failure(second.error)
                        Outcome.RetryWithoutTools ->
                            Result.failure(IOException(string(R.string.error_network)))
                    }
                }
            }
        }

    private fun execute(number: String, apiKey: String, withTools: Boolean): Outcome {
        val request = Request.Builder()
            .url(ENDPOINT)
            // The key travels in a header, never in the URL, so it cannot leak into logs.
            .header("x-goog-api-key", apiKey)
            .post(buildRequestBody(number, withTools).toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    parseSuccess(bodyText, number, grounded = withTools)
                } else {
                    mapHttpError(response.code, bodyText, withTools)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.javaClass.simpleName}")
            Outcome.Failure(IOException(string(R.string.error_network)))
        }
    }

    private fun parseSuccess(bodyText: String, number: String, grounded: Boolean): Outcome {
        val candidate = try {
            JSONObject(bodyText).optJSONArray("candidates")?.optJSONObject(0)
        } catch (_: JSONException) {
            null
        }
        val text = extractText(candidate)
        if (text.isBlank()) {
            Log.e(TAG, "Empty model response")
            return Outcome.Failure(IOException(string(R.string.error_network)))
        }
        val intel = CallerIntel.fromModelOutput(text, number, extractSources(candidate), grounded)
        return Outcome.Success(intel)
    }

    private fun mapHttpError(code: Int, bodyText: String, withTools: Boolean): Outcome {
        val errorMessage = extractErrorMessage(bodyText)
        // Error detail is safe to log: it never contains the key or the number.
        Log.e(TAG, "HTTP $code: ${errorMessage.take(200)}")
        if (withTools && (code == 429 || (code in 400..499 &&
                (errorMessage.contains("tool", ignoreCase = true) ||
                    errorMessage.contains("search", ignoreCase = true))))
        ) {
            // 429 on a grounded call usually means the search-grounding quota specifically
            // (free-tier keys may have none); degrade to an ungrounded lookup.
            Log.e(TAG, "Grounded attempt rejected (HTTP $code), retrying without live search")
            return Outcome.RetryWithoutTools
        }
        val message = when (code) {
            400, 401, 403 -> string(R.string.error_bad_key)
            429 -> string(R.string.error_quota)
            else -> "${string(R.string.error_network)} (HTTP $code)"
        }
        return Outcome.Failure(IOException(message))
    }

    private fun buildRequestBody(number: String, withTools: Boolean): String {
        val body = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", LookupPrompt.SYSTEM_TEXT)),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", LookupPrompt.promptFor(number))),
                    ),
                ),
            )
            .put("generationConfig", JSONObject().put("temperature", 0.2))
        if (withTools) {
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        return body.toString()
    }

    private fun extractText(candidate: JSONObject?): String {
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        return buildString {
            for (i in 0 until parts.length()) {
                append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
    }

    private fun extractSources(candidate: JSONObject?): List<SourceRef> {
        val chunks = candidate?.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")
            ?: return emptyList()
        return (0 until chunks.length()).mapNotNull { i ->
            chunks.optJSONObject(i)?.optJSONObject("web")?.let { web ->
                val uri = web.optString("uri")
                if (uri.isBlank()) null else SourceRef(web.optString("title").ifBlank { uri }, uri)
            }
        }
    }

    private fun extractErrorMessage(bodyText: String): String = try {
        JSONObject(bodyText).optJSONObject("error")?.optString("message").orEmpty()
            .ifBlank { bodyText }
    } catch (_: JSONException) {
        bodyText
    }

    private fun string(resId: Int): String = App.instance.getString(resId)

    private sealed interface Outcome {
        data class Success(val intel: CallerIntel) : Outcome
        data class Failure(val error: Exception) : Outcome
        data object RetryWithoutTools : Outcome
    }

    private companion object {
        const val TAG = "GeminiClient"
        const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent"
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    }
}

package com.pcdeni.aicallerid.ai

import android.util.Log
import com.pcdeni.aicallerid.App
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.model.CallerIntel
import com.pcdeni.aicallerid.model.SourceRef
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class GroqClient : LookupClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    override suspend fun lookup(phoneNumber: String, apiKey: String): Result<CallerIntel> =
        withContext(Dispatchers.IO) {
            val number = phoneNumber.trim()
            if (!LookupPrompt.isValidNumber(number)) {
                return@withContext Result.failure(
                    IllegalArgumentException(string(R.string.error_bad_number)),
                )
            }
            when (val first = execute(number, apiKey, MODEL_PRIMARY)) {
                is Outcome.Success -> Result.success(first.intel)
                is Outcome.Failure -> Result.failure(first.error)
                Outcome.RetryWithFallbackModel ->
                    when (val second = execute(number, apiKey, MODEL_FALLBACK)) {
                        is Outcome.Success -> Result.success(second.intel)
                        is Outcome.Failure -> Result.failure(second.error)
                        Outcome.RetryWithFallbackModel ->
                            Result.failure(IOException(string(R.string.error_network)))
                    }
            }
        }

    private fun execute(number: String, apiKey: String, model: String): Outcome {
        val request = Request.Builder()
            .url(ENDPOINT)
            // The key travels in a header, never in the URL, so it cannot leak into logs.
            .header("Authorization", "Bearer $apiKey")
            .post(buildRequestBody(number, model).toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val bodyText = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    parseSuccess(bodyText, number)
                } else {
                    mapHttpError(response.code, bodyText, model)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.javaClass.simpleName}")
            Outcome.Failure(IOException(string(R.string.error_network)))
        }
    }

    private fun parseSuccess(bodyText: String, number: String): Outcome {
        val message = try {
            JSONObject(bodyText).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")
        } catch (_: JSONException) {
            null
        }
        val text = message?.optString("content").orEmpty()
        if (text.isBlank()) {
            Log.e(TAG, "Empty model response")
            return Outcome.Failure(IOException(string(R.string.error_network)))
        }
        val toolCount = message?.optJSONArray("executed_tools")?.length() ?: 0
        val intel = CallerIntel.fromModelOutput(
            text,
            number,
            extractSources(message),
            grounded = toolCount > 0,
        )
        return Outcome.Success(intel)
    }

    private fun mapHttpError(code: Int, bodyText: String, model: String): Outcome {
        val errorMessage = extractErrorMessage(bodyText)
        // Error detail is safe to log: it never contains the key or the number.
        Log.e(TAG, "HTTP $code: ${errorMessage.take(200)}")
        val modelMissing = errorMessage.contains("model", ignoreCase = true) &&
            (errorMessage.contains("not found", ignoreCase = true) ||
                errorMessage.contains("does not exist", ignoreCase = true) ||
                errorMessage.contains("decommissioned", ignoreCase = true))
        if (model == MODEL_PRIMARY && (code == 404 || modelMissing)) {
            Log.e(TAG, "Primary model unavailable, retrying with $MODEL_FALLBACK")
            return Outcome.RetryWithFallbackModel
        }
        val message = when (code) {
            401, 403 -> string(R.string.error_bad_key)
            429 -> string(R.string.error_quota)
            else -> "${string(R.string.error_network)} (HTTP $code)"
        }
        return Outcome.Failure(IOException(message))
    }

    private fun buildRequestBody(number: String, model: String): String =
        JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject().put("role", "system")
                            .put("content", LookupPrompt.SYSTEM_TEXT),
                    )
                    .put(
                        JSONObject().put("role", "user")
                            .put("content", LookupPrompt.promptFor(number)),
                    ),
            )
            .toString()

    private fun extractSources(message: JSONObject?): List<SourceRef> {
        val tools = message?.optJSONArray("executed_tools") ?: return emptyList()
        val sources = mutableListOf<SourceRef>()
        for (i in 0 until tools.length()) {
            val results = tools.optJSONObject(i)
                ?.optJSONObject("search_results")
                ?.optJSONArray("results") ?: continue
            for (j in 0 until results.length()) {
                val result = results.optJSONObject(j) ?: continue
                val url = result.optString("url")
                if (url.isNotBlank()) {
                    sources.add(SourceRef(result.optString("title").ifBlank { url }, url))
                }
            }
        }
        return sources
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
        data object RetryWithFallbackModel : Outcome
    }

    private companion object {
        const val TAG = "GroqClient"
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val MODEL_PRIMARY = "groq/compound-mini"
        const val MODEL_FALLBACK = "groq/compound"
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
    }
}

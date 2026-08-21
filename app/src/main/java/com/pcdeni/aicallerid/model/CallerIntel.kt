package com.pcdeni.aicallerid.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class SourceRef(val title: String, val uri: String)

data class CallerIntel(
    val phoneNumber: String,
    val callerName: String,
    val callerCategory: String,
    val riskLevel: String,
    val isSpam: Boolean,
    val spamScore: Int,
    val location: String,
    val conciseSummary: String,
    val keyHighlights: List<String>,
    val recommendedAction: String,
    val eInkText: String,
    val sources: List<SourceRef>,
    val grounded: Boolean,
    val timestampMs: Long,
    val fromCache: Boolean = false,
) {

    fun toJson(): JSONObject = JSONObject()
        .put("phoneNumber", phoneNumber)
        .put("callerName", callerName)
        .put("callerCategory", callerCategory)
        .put("riskLevel", riskLevel)
        .put("isSpam", isSpam)
        .put("spamScore", spamScore)
        .put("location", location)
        .put("conciseSummary", conciseSummary)
        .put("keyHighlights", JSONArray(keyHighlights))
        .put("recommendedAction", recommendedAction)
        .put("eInkText", eInkText)
        .put("sources", JSONArray().also { arr ->
            sources.forEach { source ->
                arr.put(JSONObject().put("title", source.title).put("uri", source.uri))
            }
        })
        .put("grounded", grounded)
        .put("timestampMs", timestampMs)
        .put("fromCache", fromCache)

    companion object {

        fun fromJson(o: JSONObject): CallerIntel? {
            return try {
                val phone = o.optString("phoneNumber")
                if (phone.isBlank()) return null
                val isSpam = o.optBoolean("isSpam", false)
                CallerIntel(
                    phoneNumber = phone,
                    callerName = o.optString("callerName").ifBlank { "Number $phone" },
                    callerCategory = o.optString("callerCategory").ifBlank { "Unknown" },
                    riskLevel = o.optString("riskLevel").ifBlank { defaultRiskLevel(isSpam) },
                    isSpam = isSpam,
                    spamScore = o.optInt("spamScore", 0).coerceIn(0, 100),
                    location = o.optString("location"),
                    conciseSummary = o.optString("conciseSummary"),
                    keyHighlights = stringList(o.optJSONArray("keyHighlights")),
                    recommendedAction = o.optString("recommendedAction")
                        .ifBlank { defaultAction(o.optString("riskLevel").ifBlank { defaultRiskLevel(isSpam) }) },
                    eInkText = o.optString("eInkText"),
                    sources = sourceList(o.optJSONArray("sources")),
                    grounded = o.optBoolean("grounded", true),
                    timestampMs = o.optLong("timestampMs", System.currentTimeMillis()),
                    fromCache = o.optBoolean("fromCache", false),
                )
            } catch (_: JSONException) {
                null
            }
        }

        fun fromModelOutput(
            raw: String,
            phoneNumber: String,
            sources: List<SourceRef>,
            grounded: Boolean,
        ): CallerIntel {
            val cleaned = stripCodeFences(raw)
            val obj = extractJsonObject(cleaned)
            val fallbackName = "Number $phoneNumber"
            if (obj == null) {
                return CallerIntel(
                    phoneNumber = phoneNumber,
                    callerName = fallbackName,
                    callerCategory = "Unknown",
                    riskLevel = "Low Risk",
                    isSpam = false,
                    spamScore = 0,
                    location = "",
                    conciseSummary = cleaned.take(200),
                    keyHighlights = emptyList(),
                    recommendedAction = "Caution",
                    eInkText = fallbackName,
                    sources = sources,
                    grounded = grounded,
                    timestampMs = System.currentTimeMillis(),
                )
            }
            val isSpam = obj.optBoolean("isSpam", false)
            val callerName = obj.optString("callerName").ifBlank { fallbackName }
            val riskLevel = obj.optString("riskLevel").ifBlank { defaultRiskLevel(isSpam) }
            return CallerIntel(
                phoneNumber = phoneNumber,
                callerName = callerName,
                callerCategory = obj.optString("callerCategory").ifBlank { "Unknown" },
                riskLevel = riskLevel,
                isSpam = isSpam,
                spamScore = obj.optInt("spamScore", if (isSpam) 80 else 0).coerceIn(0, 100),
                location = obj.optString("location"),
                conciseSummary = obj.optString("conciseSummary").ifBlank { cleaned.take(200) },
                keyHighlights = stringList(obj.optJSONArray("keyHighlights")),
                recommendedAction = obj.optString("recommendedAction").ifBlank { defaultAction(riskLevel) },
                eInkText = obj.optString("eInkOptimizedText").ifBlank { "$callerName | $riskLevel" },
                sources = sources,
                grounded = grounded,
                timestampMs = System.currentTimeMillis(),
            )
        }

        private fun defaultRiskLevel(isSpam: Boolean): String =
            if (isSpam) "High Risk / Scam" else "Safe"

        private fun defaultAction(riskLevel: String): String = when (riskLevel) {
            "High Risk / Scam" -> "Block/Decline"
            "Medium Risk" -> "Caution"
            else -> "Answer"
        }

        private fun stringList(arr: JSONArray?): List<String> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }
        }

        private fun sourceList(arr: JSONArray?): List<SourceRef> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val uri = o.optString("uri")
                    if (uri.isBlank()) null else SourceRef(o.optString("title").ifBlank { uri }, uri)
                }
            }
        }

        private fun stripCodeFences(raw: String): String {
            var text = raw.trim()
            if (text.startsWith("```")) {
                text = text.removePrefix("```")
                text = text.removePrefix("json").removePrefix("JSON")
                val closing = text.lastIndexOf("```")
                if (closing >= 0) {
                    text = text.substring(0, closing)
                }
            }
            return text.trim()
        }

        private fun extractJsonObject(text: String): JSONObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return try {
                JSONObject(text.substring(start, end + 1))
            } catch (_: JSONException) {
                null
            }
        }
    }
}

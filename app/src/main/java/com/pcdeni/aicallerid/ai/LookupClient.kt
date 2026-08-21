package com.pcdeni.aicallerid.ai

import com.pcdeni.aicallerid.model.CallerIntel

interface LookupClient {
    suspend fun lookup(phoneNumber: String, apiKey: String): Result<CallerIntel>
}

object LookupPrompt {

    const val SYSTEM_TEXT =
        "You are an automated caller ID intelligence engine. Perform live web research on " +
            "the phone number and return strict JSON only, no prose."

    // Anti-prompt-injection gate: only plausible phone numbers may reach a prompt.
    fun isValidNumber(number: String): Boolean {
        val digits = number.filter { it.isDigit() }
        return !number.any { it.isLetter() } && number.length <= 25 && digits.length in 4..15
    }

    fun promptFor(number: String): String = """
        You are a real-time incoming caller ID intelligence engine for mobile phones.
        Analyze and reverse lookup this incoming phone number using live Google search results, directory listings, spam databases, consumer reports, and official business listings:

        PHONE NUMBER TO SEARCH: "$number"

        Your primary target is to extract accurate caller identity, business name, spam risk level, and a CONCISE SUMMARY (15-25 words) suitable for an incoming call screen or E-Ink phone display.

        Respond ONLY with valid JSON matching this structure:
        {
          "callerName": "Full Business Name OR Caller Title OR 'Unidentified Robocall' OR 'Unknown Caller'",
          "callerCategory": "Business" | "Telemarketer/Spam" | "Financial/Bank" | "Medical/Clinic" | "Government/Public" | "Courier/Delivery" | "Personal" | "Scam/Fraud" | "Unknown",
          "riskLevel": "Safe" | "Low Risk" | "Medium Risk" | "High Risk / Scam",
          "isSpam": true or false,
          "spamScore": number between 0 and 100,
          "location": "City, State, Country or Carrier/Toll Free details",
          "conciseSummary": "15 to 25 words executive summary explaining who this caller is and why they might be calling.",
          "keyHighlights": ["Point 1", "Point 2", "Point 3"],
          "recommendedAction": "Answer" | "Caution" | "Block/Decline" | "Mute",
          "eInkOptimizedText": "Short high-contrast 1-line text for e-paper displays"
        }
    """.trimIndent()
}

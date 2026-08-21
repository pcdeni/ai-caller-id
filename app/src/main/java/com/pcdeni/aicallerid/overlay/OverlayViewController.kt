package com.pcdeni.aicallerid.overlay

import android.content.Context
import android.content.res.ColorStateList
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.model.CallerIntel

class OverlayViewController(
    context: Context,
    private val onScan: () -> Unit,
    private val onRetry: () -> Unit,
    private val onDismiss: () -> Unit
) {

    private val themedContext = ContextThemeWrapper(context, R.style.Theme_AICallerID)

    val root: View =
        LayoutInflater.from(themedContext).inflate(R.layout.view_overlay_card, null, false)

    private val numberView: TextView = root.findViewById(R.id.overlayNumber)
    private val statusView: TextView = root.findViewById(R.id.overlayStatus)
    private val nameView: TextView = root.findViewById(R.id.overlayName)
    private val badgeView: TextView = root.findViewById(R.id.overlayBadge)
    private val categoryView: TextView = root.findViewById(R.id.overlayCategory)
    private val summaryView: TextView = root.findViewById(R.id.overlaySummary)
    private val sourcesView: TextView = root.findViewById(R.id.overlaySources)
    private val scanButton: Button = root.findViewById(R.id.overlayScanButton)
    private val retryButton: Button = root.findViewById(R.id.overlayRetryButton)
    private val dismissButton: Button = root.findViewById(R.id.overlayDismissButton)

    init {
        scanButton.setOnClickListener { onScan() }
        retryButton.setOnClickListener { onRetry() }
        dismissButton.setOnClickListener { onDismiss() }
    }

    fun showPrompt(number: String) {
        numberView.text = number
        setStatus(themedContext.getString(R.string.overlay_privacy_prompt))
        hideResultViews()
        scanButton.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
    }

    fun showScanning(number: String) {
        numberView.text = number
        setStatus(themedContext.getString(R.string.overlay_scanning))
        hideResultViews()
        scanButton.visibility = View.GONE
        retryButton.visibility = View.GONE
    }

    fun showResult(intel: CallerIntel) {
        numberView.text = intel.phoneNumber

        val statusParts = mutableListOf<String>()
        if (intel.eInkText.isNotBlank()) statusParts.add(intel.eInkText)
        if (intel.fromCache) statusParts.add(themedContext.getString(R.string.overlay_cached_tag))
        if (!intel.grounded) {
            statusParts.add(themedContext.getString(R.string.overlay_ungrounded_tag))
        }
        setStatus(statusParts.joinToString(" · "))

        nameView.text = intel.callerName
        nameView.visibility = View.VISIBLE

        badgeView.text = intel.riskLevel
        badgeView.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(themedContext, riskColorRes(intel.riskLevel))
        )
        badgeView.visibility = View.VISIBLE

        val category = listOf(intel.callerCategory, intel.location)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        categoryView.text = category
        categoryView.visibility = if (category.isBlank()) View.GONE else View.VISIBLE

        summaryView.text = intel.conciseSummary
        summaryView.visibility = if (intel.conciseSummary.isBlank()) View.GONE else View.VISIBLE

        val sources = intel.sources
            .map { it.title.ifBlank { it.uri } }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        sourcesView.text = sources
        sourcesView.visibility = if (sources.isBlank()) View.GONE else View.VISIBLE

        scanButton.visibility = View.GONE
        retryButton.visibility = View.GONE
    }

    fun showError(number: String, message: String) {
        numberView.text = number
        setStatus(message)
        hideResultViews()
        scanButton.visibility = View.GONE
        retryButton.visibility = View.VISIBLE
    }

    private fun setStatus(text: String) {
        statusView.text = text
        statusView.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    private fun hideResultViews() {
        nameView.visibility = View.GONE
        badgeView.visibility = View.GONE
        categoryView.visibility = View.GONE
        summaryView.visibility = View.GONE
        sourcesView.visibility = View.GONE
    }

    @ColorRes
    private fun riskColorRes(riskLevel: String): Int = when (riskLevel) {
        "High Risk / Scam" -> R.color.risk_red
        "Medium Risk" -> R.color.risk_amber
        "Low Risk" -> R.color.risk_gray
        "Safe" -> R.color.risk_green
        else -> R.color.risk_gray
    }
}

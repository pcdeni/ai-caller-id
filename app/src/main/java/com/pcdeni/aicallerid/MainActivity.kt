package com.pcdeni.aicallerid

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pcdeni.aicallerid.data.Provider
import com.pcdeni.aicallerid.data.ScanMode
import com.pcdeni.aicallerid.databinding.ActivityMainBinding
import com.pcdeni.aicallerid.history.HistoryAdapter
import com.pcdeni.aicallerid.model.CallerIntel
import com.pcdeni.aicallerid.overlay.OverlayService
import com.pcdeni.aicallerid.util.RoleHelper
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val historyAdapter = HistoryAdapter()

    private val roleLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshChecklist()
        }

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshChecklist()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupChecklist()
        setupSettings()
        setupLookup()
        setupPreview()
        setupHistory()
    }

    override fun onResume() {
        super.onResume()
        refreshChecklist()
    }

    private fun setupChecklist() {
        binding.roleButton.setOnClickListener {
            roleLauncher.launch(RoleHelper.screeningRoleIntent(this))
        }
        binding.overlayButton.setOnClickListener {
            startActivity(RoleHelper.overlayPermissionIntent(this))
        }
        binding.notifButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.keyButton.setOnClickListener {
            showApiKeyDialog()
        }
    }

    private fun refreshChecklist() {
        val prefs = App.instance.prefs
        bindChecklistRow(binding.roleDot, binding.roleButton, RoleHelper.hasScreeningRole(this))
        bindChecklistRow(binding.overlayDot, binding.overlayButton, RoleHelper.hasOverlayPermission(this))
        bindChecklistRow(binding.notifDot, binding.notifButton, !RoleHelper.needsNotificationPermission(this))
        binding.keyLabel.text = getString(R.string.key_row_label_fmt, providerName())
        val keyOk = !prefs.activeApiKey.isNullOrBlank()
        bindChecklistRow(binding.keyDot, binding.keyButton, keyOk)
        binding.keyButton.isEnabled = true
        binding.keyButton.text =
            getString(if (keyOk) R.string.key_action_change else R.string.key_action_set)
    }

    private fun bindChecklistRow(dot: TextView, actionButton: Button, done: Boolean) {
        dot.setTextColor(
            ContextCompat.getColor(this, if (done) R.color.risk_green else R.color.risk_red),
        )
        dot.contentDescription =
            getString(if (done) R.string.status_done else R.string.status_missing)
        actionButton.isEnabled = !done
    }

    private fun providerName(): String = getString(
        if (App.instance.prefs.provider == Provider.GROQ) {
            R.string.provider_groq
        } else {
            R.string.provider_gemini
        },
    )

    private fun showApiKeyDialog() {
        val prefs = App.instance.prefs
        val padding = resources.getDimensionPixelSize(R.dimen.card_padding)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
            setText(prefs.activeApiKey.orEmpty())
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, 0)
            addView(
                input,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.key_row_label_fmt, providerName()))
            .setMessage(
                if (App.instance.prefs.provider == Provider.GROQ) {
                    R.string.key_dialog_help_groq
                } else {
                    R.string.key_dialog_help
                },
            )
            .setView(container)
            .setPositiveButton(R.string.key_dialog_save) { _, _ ->
                prefs.activeApiKey = input.text.toString().trim()
                refreshChecklist()
            }
            .setNegativeButton(R.string.key_dialog_clear) { _, _ ->
                prefs.activeApiKey = null
                refreshChecklist()
            }
            .show()
    }

    private fun setupSettings() {
        val prefs = App.instance.prefs
        binding.providerGroup.check(
            if (prefs.provider == Provider.GROQ) R.id.providerGroq else R.id.providerGemini,
        )
        binding.providerGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.provider =
                if (checkedId == R.id.providerGroq) Provider.GROQ else Provider.GEMINI
            refreshChecklist()
        }
        binding.scanModeGroup.check(
            if (prefs.scanMode == ScanMode.AUTO) R.id.scanModeAuto else R.id.scanModeOnDemand,
        )
        binding.scanModeGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.scanMode =
                if (checkedId == R.id.scanModeAuto) ScanMode.AUTO else ScanMode.ON_DEMAND
        }
    }

    private fun setupLookup() {
        binding.lookupButton.setOnClickListener {
            val number = binding.lookupInput.text?.toString()?.trim().orEmpty()
            binding.lookupButton.isEnabled = false
            showLookupLoading()
            lifecycleScope.launch {
                val result = App.instance.repository.lookup(number, forceFresh = false)
                binding.lookupButton.isEnabled = true
                result.fold(
                    onSuccess = { showLookupResult(it) },
                    onFailure = { showLookupError(lookupErrorMessage(it)) },
                )
            }
        }
    }

    private fun lookupErrorMessage(error: Throwable): String =
        if (error is IllegalStateException && error.message == "API key missing") {
            getString(R.string.error_no_key)
        } else {
            error.message ?: getString(R.string.error_network)
        }

    private fun showLookupLoading() {
        binding.lookupResult.visibility = View.VISIBLE
        binding.resultName.visibility = View.GONE
        binding.resultBadgeRow.visibility = View.GONE
        binding.resultSources.visibility = View.GONE
        binding.resultSummary.text = getString(R.string.overlay_scanning)
    }

    private fun showLookupResult(intel: CallerIntel) {
        binding.lookupResult.visibility = View.VISIBLE
        binding.resultName.visibility = View.VISIBLE
        binding.resultBadgeRow.visibility = View.VISIBLE
        binding.resultSources.visibility = View.VISIBLE
        binding.resultName.text = intel.callerName
        binding.resultBadge.text = intel.riskLevel
        binding.resultBadge.background?.mutate()?.setTint(
            ContextCompat.getColor(this, riskColorRes(intel.riskLevel)),
        )
        binding.resultCategory.text =
            listOf(intel.callerCategory, intel.location)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
        binding.resultSummary.text = intel.conciseSummary
        binding.resultSources.text = sourcesLine(intel)
    }

    private fun showLookupError(message: String) {
        binding.lookupResult.visibility = View.VISIBLE
        binding.resultName.visibility = View.GONE
        binding.resultBadgeRow.visibility = View.GONE
        binding.resultSources.visibility = View.GONE
        binding.resultSummary.text = message
    }

    private fun sourcesLine(intel: CallerIntel): String {
        val parts = mutableListOf(intel.sources.size.toString())
        if (intel.fromCache) parts += getString(R.string.overlay_cached_tag)
        if (!intel.grounded) parts += getString(R.string.overlay_ungrounded_tag)
        return parts.joinToString(" · ")
    }

    private fun riskColorRes(riskLevel: String): Int = when (riskLevel) {
        "High Risk / Scam" -> R.color.risk_red
        "Medium Risk" -> R.color.risk_amber
        "Low Risk" -> R.color.risk_gray
        "Safe" -> R.color.risk_green
        else -> R.color.risk_gray
    }

    private fun setupPreview() {
        binding.previewButton.setOnClickListener {
            OverlayService.startPreview(this)
        }
    }

    private fun setupHistory() {
        binding.historyList.layoutManager = LinearLayoutManager(this)
        binding.historyList.adapter = historyAdapter
        // E-ink friendliness: no add/remove/change animations on the list.
        binding.historyList.itemAnimator = null
        binding.historyClearButton.setOnClickListener {
            App.instance.repository.clearHistory()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                App.instance.repository.history.collect { list ->
                    historyAdapter.submitList(list)
                    binding.historyEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.historyList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }
}

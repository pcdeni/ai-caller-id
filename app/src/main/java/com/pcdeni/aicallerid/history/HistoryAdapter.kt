package com.pcdeni.aicallerid.history

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.pcdeni.aicallerid.R
import com.pcdeni.aicallerid.databinding.ItemHistoryBinding
import com.pcdeni.aicallerid.model.CallerIntel

class HistoryAdapter : ListAdapter<CallerIntel, HistoryAdapter.HistoryViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HistoryViewHolder(
        private val binding: ItemHistoryBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(intel: CallerIntel) {
            binding.historyName.text = intel.callerName
            binding.historyNumber.text = intel.phoneNumber
            binding.historyBadge.text = intel.riskLevel
            val color = ContextCompat.getColor(binding.root.context, riskColorRes(intel.riskLevel))
            binding.historyBadge.background?.mutate()?.setTint(color)
            binding.historySummary.text = intel.conciseSummary
            binding.historyTime.text = DateUtils.getRelativeTimeSpanString(
                intel.timestampMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
            )
        }

        private fun riskColorRes(riskLevel: String): Int = when (riskLevel) {
            "High Risk / Scam" -> R.color.risk_red
            "Medium Risk" -> R.color.risk_amber
            "Low Risk" -> R.color.risk_gray
            "Safe" -> R.color.risk_green
            else -> R.color.risk_gray
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<CallerIntel>() {
            override fun areItemsTheSame(oldItem: CallerIntel, newItem: CallerIntel): Boolean =
                oldItem.phoneNumber == newItem.phoneNumber

            override fun areContentsTheSame(oldItem: CallerIntel, newItem: CallerIntel): Boolean =
                oldItem == newItem
        }
    }
}

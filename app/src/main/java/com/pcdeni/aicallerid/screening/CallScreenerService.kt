package com.pcdeni.aicallerid.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import com.pcdeni.aicallerid.App
import com.pcdeni.aicallerid.data.ScanMode
import com.pcdeni.aicallerid.overlay.OverlayService

class CallScreenerService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        // Respond before doing anything else: the AI lookup must never block or delay the call.
        respondToCall(callDetails, CallResponse.Builder().build())

        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            return
        }
        val number = callDetails.handle?.schemeSpecificPart
        if (number.isNullOrBlank()) {
            return
        }
        val prefs = App.instance.prefs
        OverlayService.startIncoming(this, number, auto = prefs.scanMode == ScanMode.AUTO)
    }
}

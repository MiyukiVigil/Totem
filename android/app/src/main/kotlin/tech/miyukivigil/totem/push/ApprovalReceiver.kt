package tech.miyukivigil.totem.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Approve / Deny taps from the request notification. Not exported. */
class ApprovalReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val req = intent.getStringExtra(EXTRA_REQ) ?: return
        Approvals.decide(req, intent.getBooleanExtra(EXTRA_APPROVE, false))
    }

    companion object {
        const val EXTRA_REQ = "req"
        const val EXTRA_APPROVE = "approve"
    }
}

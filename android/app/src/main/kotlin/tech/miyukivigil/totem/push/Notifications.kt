package tech.miyukivigil.totem.push

import android.app.Notification
import tech.miyukivigil.totem.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object Notifications {
    private const val CH_SERVICE = "service"
    private const val CH_APPROVAL = "approval"
    private const val CH_SENT = "sent"
    private const val CH_ALERT = "alert"
    const val SERVICE_ID = 1
    private const val SENT_ID = 2

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_SERVICE, "Answering your PC", NotificationManager.IMPORTANCE_MIN),
                NotificationChannel(CH_APPROVAL, "Code requests to approve", NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_SENT, "Codes sent to your PC", NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CH_ALERT, "Security warnings", NotificationManager.IMPORTANCE_HIGH),
            ),
        )
    }

    fun service(context: Context): Notification =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_totem)
            .setContentTitle("Totem is answering your PC")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun codeSent(context: Context, pcName: String, accountName: String) = notify(context, SENT_ID) {
        NotificationCompat.Builder(context, CH_SENT)
            .setSmallIcon(R.drawable.ic_stat_totem)
            .setContentTitle("Code sent to $pcName")
            .setContentText(accountName)
            .setTimeoutAfter(60_000)
            .build()
    }

    /** Something a paired PC did looks wrong. Tapping opens Totem. */
    fun alert(context: Context, title: String, text: String) = notify(context, ("alert:" + title).hashCode()) {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CH_ALERT).setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("Totem security warning").setContentText("Unlock to see details").build(),
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    fun showApproval(context: Context, req: String, pcName: String, accountName: String) = notify(context, req.hashCode()) {
        fun action(approve: Boolean) = PendingIntent.getBroadcast(
            context,
            req.hashCode() * 2 + if (approve) 1 else 0,
            Intent(context, ApprovalReceiver::class.java).putExtra(ApprovalReceiver.EXTRA_REQ, req).putExtra(ApprovalReceiver.EXTRA_APPROVE, approve),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Approving from the lock screen requires unlocking first; denying doesn't.
        val approve = NotificationCompat.Action.Builder(0, "Approve", action(true)).setAuthenticationRequired(true).build()
        val deny = NotificationCompat.Action.Builder(0, "Deny", action(false)).build()
        fun base() = NotificationCompat.Builder(context, CH_APPROVAL)
            .setSmallIcon(R.drawable.ic_stat_totem)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setTimeoutAfter(Approvals.TIMEOUT_MS)
            .setOngoing(true)
            .addAction(approve)
            .addAction(deny)
        base()
            .setContentTitle("$pcName wants a code")
            .setContentText(accountName)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            // Lock-screen version: no account or PC names, but the buttons still work.
            .setPublicVersion(base().setContentTitle("Your PC wants a 2FA code").setContentText("Unlock to see which account").build())
            .build()
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    private inline fun notify(context: Context, id: Int, build: () -> Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (nm.areNotificationsEnabled()) {
            try {
                nm.notify(id, build())
            } catch (e: SecurityException) {
                // Notification permission revoked between the check and the call.
            }
        }
    }
}

/** Waits for the user to approve or deny "Ask first" requests. */
object Approvals {
    const val TIMEOUT_MS = 40_000L
    private val pending = ConcurrentHashMap<String, CompletableFuture<Boolean>>()

    /** Blocking: true approved, false denied, null no answer in time (or notifications are off). */
    fun ask(context: Context, req: String, pcName: String, accountName: String): Boolean? {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return null
        val future = CompletableFuture<Boolean>()
        pending[req] = future
        Notifications.showApproval(context, req, pcName, accountName)
        return try {
            future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        } finally {
            pending.remove(req)
            Notifications.cancel(context, req.hashCode())
        }
    }

    fun decide(req: String, approved: Boolean) {
        pending[req]?.complete(approved)
    }
}

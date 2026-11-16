package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.ui.pages.chat.HezamVoiceController
import me.rerere.rikkahub.ui.pages.chat.HezamVoicePhase
import org.koin.android.ext.android.inject
import kotlin.uuid.Uuid

/**
 * Keeps the "Hezam" wake word listening while the app is in the background or the screen is
 * off. Runs as a microphone foreground service with a persistent notification; a spoken
 * command is sent to the chat that is open in the app, or to a dedicated voice conversation
 * when none is, and the reply is read aloud.
 *
 * Android only lets a microphone foreground service start while the app is visible, so it is
 * started from the mic button in the chat and keeps running until stopped there or from the
 * notification. It does not restart itself after a reboot.
 */
class HezamVoiceService : Service() {
    private val chatService: ChatService by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var voice: HezamVoiceController? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** Conversation that receives commands spoken while no chat is open in the app. */
    private val ownConversationId = Uuid.random()
    private var ownConversationReady = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (voice == null) {
            val created = HezamVoiceController(this) { command -> handleCommand(command) }
            voice = created
            controller = created
            created.start()
            if (created.phase == HezamVoicePhase.Unavailable) {
                Toast.makeText(this, R.string.hezam_voice_unavailable, Toast.LENGTH_LONG).show()
                stopSelf()
                return START_NOT_STICKY
            }
            acquireWakeLock()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        controller = null
        voice?.release()
        voice = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        if (ownConversationReady) {
            runCatching { chatService.removeConversationReference(ownConversationId) }
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun handleCommand(command: String) {
        scope.launch {
            val reply = try {
                val conversationId = activeConversationId ?: ownConversation()
                val pendingReply = chatService.enqueueVoiceMessage(conversationId, command)
                awaitReplyOrApproval(conversationId, pendingReply)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "Voice command failed", t)
                t.message
            }
            voice?.onReply(reply.orEmpty())
        }
    }

    private suspend fun ownConversation(): Uuid {
        if (!ownConversationReady) {
            chatService.addConversationReference(ownConversationId)
            chatService.initializeConversation(ownConversationId)
            ownConversationReady = true
        }
        return ownConversationId
    }

    /**
     * Waits for the spoken command's reply. A tool that needs the user's approval cannot be
     * answered by voice, so instead of waiting on it forever this returns a spoken hint to
     * open the app; the turn itself stays pending there.
     */
    private suspend fun awaitReplyOrApproval(
        conversationId: Uuid,
        pendingReply: kotlinx.coroutines.Deferred<String?>,
    ): String? = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
        coroutineScope {
            val needsApproval = async {
                chatService.getConversationFlow(conversationId).first { conversation ->
                    conversation.currentMessages.lastOrNull()?.parts
                        ?.any { it is UIMessagePart.Tool && it.isPending } == true
                }
                getString(R.string.hezam_voice_needs_approval)
            }
            val result = select<String?> {
                pendingReply.onAwait { it }
                needsApproval.onAwait { it }
            }
            needsApproval.cancel()
            result
        }
    }

    private fun promoteToForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.hezam_voice_toggle),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Could not start the microphone foreground service", t)
            false
        }
    }

    private fun buildNotification(): Notification {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, RouteActivity::class.java), immutable,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HezamVoiceService::class.java).setAction(ACTION_STOP), immutable,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.hezam_voice_notification_title))
            .setContentText(getString(R.string.hezam_voice_notification_text))
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.hezam_voice_notification_stop), stop)
            .build()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hezam:voice").also {
            it.setReferenceCounted(false)
            runCatching { it.acquire() }
        }
    }

    companion object {
        private const val TAG = "HezamVoiceService"
        private const val CHANNEL_ID = "hezam_voice"
        private const val NOTIFICATION_ID = 0x4845
        private const val ACTION_STOP = "me.rerere.rikkahub.action.HEZAM_VOICE_STOP"
        private const val REPLY_TIMEOUT_MS = 10 * 60 * 1000L

        /** The running service's controller, or null while voice control is off. Compose state,
         *  so the chat top bar follows it. */
        var controller: HezamVoiceController? by mutableStateOf<HezamVoiceController?>(null)
            private set

        /** Chat currently open in the app; spoken commands go there so the user sees them. */
        @Volatile
        var activeConversationId: Uuid? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, HezamVoiceService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HezamVoiceService::class.java))
        }
    }
}

package me.rerere.rikkahub.service

import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
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
import kotlinx.coroutines.Job
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
import me.rerere.rikkahub.data.ai.CommandShortcuts
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
    private var commandJob: Job? = null
    private var lastCommandConversationId: Uuid? = null

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
            val created = HezamVoiceController(
                context = this,
                onCommand = { command -> handleCommand(command) },
                onQuickCommand = { command -> handleQuickCommand(command) },
            )
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

    /**
     * Commands that must work instantly and while the assistant is busy, so they never go
     * through the model: stop, play, next/previous track and scrolling.
     */
    private fun handleQuickCommand(command: String): Boolean {
        val text = command.lowercase().trim().trim('.', '!', '?', ',').trim()
        when {
            STOP_COMMAND.matches(text) -> stopEverything()
            PLAY_COMMAND.matches(text) -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            NEXT_COMMAND.matches(text) -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            PREVIOUS_COMMAND.matches(text) -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            SCROLL_UP_COMMAND.matches(text) -> scroll(down = false)
            SCROLL_DOWN_COMMAND.matches(text) -> scroll(down = true)
            else -> return false
        }
        return true
    }

    /** Pauses whatever is playing and cancels the command the assistant is working on. */
    private fun stopEverything() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        commandJob?.cancel()
        commandJob = null
        val conversationId = lastCommandConversationId
        if (conversationId != null) {
            scope.launch { runCatching { chatService.stopGeneration(conversationId) } }
        }
        voice?.resumeListening()
    }

    private fun sendMediaKey(keyCode: Int) {
        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        runCatching {
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /** Swipes the screen through the accessibility service: [down] reveals content below. */
    private fun scroll(down: Boolean) {
        val accessibility = RikkaAccessibilityService.instance
        if (accessibility == null) {
            voice?.announce(getString(R.string.hezam_voice_need_accessibility))
            return
        }
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val low = metrics.heightPixels * 0.72f
        val high = metrics.heightPixels * 0.28f
        val path = Path().apply {
            moveTo(x, if (down) low else high)
            lineTo(x, if (down) high else low)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 300L))
            .build()
        runCatching { accessibility.dispatchGesture(gesture, null, null) }
    }

    private fun handleCommand(command: String) {
        commandJob = scope.launch {
            val reply = try {
                val conversationId = activeConversationId ?: ownConversation()
                lastCommandConversationId = conversationId
                val pendingReply = chatService.enqueueVoiceMessage(conversationId, CommandShortcuts.expand(command))
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

        private val STOP_COMMAND = Regex(
            "(?:stop|tigil|tumigil|hinto|huminto|tama na|pause|" +
                "i-?stop|i-?pause|itigil|ihinto)" +
                "(?: ka)?(?: mo)?(?: na)?(?: (?:ang |the )?(?:video|music|kanta|tugtog))?"
        )
        private val PLAY_COMMAND = Regex(
            "(?:play|i-?play|ituloy|tuloy|resume|i-?resume)" +
                "(?: mo)?(?: na)?(?: (?:ang |the )?(?:video|music|kanta|tugtog))?"
        )
        private val NEXT_COMMAND = Regex("(?:next|i-?next|susunod|sunod)(?: mo)?(?: na)?")
        private val PREVIOUS_COMMAND = Regex("(?:previous|i-?previous|nakaraan|balik)(?: mo)?(?: na)?")
        private val SCROLL_UP_COMMAND = Regex(
            "(?:(?:mag-?|i-?)?scroll(?: mo)?(?: ka)? )?(?:up|pataas|paakyat|taas|sa taas|akyat)"
        )
        private val SCROLL_DOWN_COMMAND = Regex(
            "(?:mag-?|i-?)?scroll(?: mo)?(?: ka)?(?: (?:down|pababa|baba|sa baba))?|" +
                "down|pababa|baba|sa baba"
        )

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

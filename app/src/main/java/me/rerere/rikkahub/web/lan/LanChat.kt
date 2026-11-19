package me.rerere.rikkahub.web.lan

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * LAN chat: every phone or computer on the same Wi-Fi that opens this phone's web server at
 * /lan can send text and pictures to everyone else there. The phone running the web server
 * relays the messages; they are kept in memory only and disappear when the server stops.
 */
object LanChat {
    private const val MAX_MESSAGES = 300
    private const val MAX_IMAGES = 60
    const val MAX_TEXT = 4000
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    @Serializable
    data class Message(
        val id: String,
        val name: String,
        val text: String,
        val imageId: String? = null,
        val time: Long,
    )

    @Serializable
    data class SendRequest(
        val name: String = "",
        val text: String = "",
        /** Optional picture as a data URL, e.g. "data:image/jpeg;base64,...". */
        val image: String? = null,
    )

    @Serializable
    data class Info(val addresses: List<String>, val port: Int)

    private class Image(val bytes: ByteArray, val mime: String)

    private val lock = Any()
    private val messages = ArrayDeque<Message>()
    private val images = LinkedHashMap<String, Image>()
    private val updates = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    val newMessages = updates.asSharedFlow()

    fun history(): List<Message> = synchronized(lock) { messages.toList() }

    fun image(id: String): Pair<ByteArray, String>? =
        synchronized(lock) { images[id]?.let { it.bytes to it.mime } }

    /** Stores a message; returns null with a reason in [error] when it is rejected. */
    fun post(request: SendRequest): Result<Message> {
        val text = request.text.trim().take(MAX_TEXT)
        val name = request.name.trim().take(40).ifBlank { "Bisita" }
        var imageId: String? = null
        var decoded: Image? = null
        val dataUrl = request.image
        if (!dataUrl.isNullOrBlank()) {
            val match = Regex("^data:(image/(?:jpeg|png|webp|gif));base64,(.+)$", RegexOption.DOT_MATCHES_ALL)
                .find(dataUrl)
                ?: return Result.failure(IllegalArgumentException("Hindi suportadong larawan"))
            val bytes = runCatching { Base64.getDecoder().decode(match.groupValues[2]) }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Sirang larawan"))
            if (bytes.size > MAX_IMAGE_BYTES) {
                return Result.failure(IllegalArgumentException("Masyadong malaki ang larawan (max 4 MB)"))
            }
            decoded = Image(bytes, match.groupValues[1])
            imageId = UUID.randomUUID().toString()
        }
        if (text.isEmpty() && decoded == null) {
            return Result.failure(IllegalArgumentException("Walang laman ang mensahe"))
        }
        val message = Message(
            id = UUID.randomUUID().toString(),
            name = name,
            text = text,
            imageId = imageId,
            time = System.currentTimeMillis(),
        )
        synchronized(lock) {
            if (decoded != null && imageId != null) {
                images[imageId] = decoded
                while (images.size > MAX_IMAGES) images.remove(images.keys.first())
            }
            messages.addLast(message)
            while (messages.size > MAX_MESSAGES) messages.removeFirst()
        }
        updates.tryEmit(message)
        return Result.success(message)
    }

    /** This phone's Wi-Fi/LAN IPv4 addresses, so the page can tell others what to open. */
    fun lanAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

private val lanJson = Json { encodeDefaults = true }

/** Page at /lan. Registered outside the access-password guard so guests can join. */
fun Route.lanChatPage() {
    get("/lan") {
        call.respondText(LAN_CHAT_HTML, ContentType.Text.Html)
    }
}

/** API under /api/lan. Registered outside the access-password guard so guests can join. */
fun Route.lanChatRoutes(port: Int) {
    get("/lan/info") {
        call.respond(LanChat.Info(LanChat.lanAddresses(), port))
    }
    get("/lan/messages") {
        call.respond(LanChat.history())
    }
    post("/lan/messages") {
        val request = call.receive<LanChat.SendRequest>()
        LanChat.post(request).fold(
            onSuccess = { call.respond(HttpStatusCode.Created, it) },
            onFailure = { call.respondText(it.message.orEmpty(), status = HttpStatusCode.BadRequest) },
        )
    }
    get("/lan/images/{id}") {
        val id = call.parameters["id"].orEmpty()
        val image = LanChat.image(id)
        if (image == null) {
            call.respond(HttpStatusCode.NotFound)
        } else {
            call.respondBytes(image.first, ContentType.parse(image.second))
        }
    }
    sse("/lan/stream") {
        heartbeat { period = 15.seconds }
        LanChat.newMessages.collect { message ->
            send(data = lanJson.encodeToString(LanChat.Message.serializer(), message), event = "message")
        }
    }
}

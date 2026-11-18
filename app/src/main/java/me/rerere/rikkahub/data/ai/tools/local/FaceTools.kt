package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import java.io.File
import java.io.FileOutputStream

/*
 * Face tools: the user registers a person's face from a photo, then asks the assistant to
 * find that person in the phone's gallery.
 *
 * Face DETECTION runs on the device (ML Kit) and only cropped faces leave it: the reference
 * face plus numbered contact sheets of candidate faces are handed to the chat model, which
 * does the "is this the same person" comparison with its own vision. Registered faces are
 * stored in the app's private files and never leave the phone except as part of a search.
 */

private const val PEOPLE_DIR = "people"
private const val CROP_SIZE = 160
private const val SHEET_COLUMNS = 6
private const val MAX_FACES_PER_SEARCH = 60
private const val DEFAULT_SCAN = 300
private const val MAX_SCAN = 1500
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")

private fun peopleRoot(context: Context) = File(context.filesDir, PEOPLE_DIR).apply { mkdirs() }

private fun slug(name: String): String =
    name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "person" }

private fun personDir(context: Context, name: String) = File(peopleRoot(context), slug(name))

private fun error(code: String, detail: String): List<UIMessagePart> = listOf(
    UIMessagePart.Text(buildJsonObject { put("error", code); put("detail", detail) }.toString())
)

private fun newDetector(): FaceDetector = FaceDetection.getClient(
    FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setMinFaceSize(0.08f)
        .build()
)

/** Decodes [file] scaled down to about [maxSide] pixels, upright per its EXIF orientation. */
private fun decodeUpright(file: File, maxSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null
    val degrees = runCatching {
        when (ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) return bitmap
    val rotated = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true,
    )
    if (rotated !== bitmap) bitmap.recycle()
    return rotated
}

/** Square crop around [box] with some margin, scaled to [CROP_SIZE]. */
private fun cropFace(source: Bitmap, box: Rect): Bitmap {
    val side = (maxOf(box.width(), box.height()) * 1.4f).toInt()
    val cx = box.centerX()
    val cy = box.centerY()
    val left = (cx - side / 2).coerceIn(0, maxOf(0, source.width - 1))
    val top = (cy - side / 2).coerceIn(0, maxOf(0, source.height - 1))
    val width = side.coerceAtMost(source.width - left).coerceAtLeast(1)
    val height = side.coerceAtMost(source.height - top).coerceAtLeast(1)
    val crop = Bitmap.createBitmap(source, left, top, width, height)
    return Bitmap.createScaledBitmap(crop, CROP_SIZE, CROP_SIZE, true)
}

private fun saveJpeg(bitmap: Bitmap, file: File) {
    file.parentFile?.mkdirs()
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
}

private suspend fun detectFaces(detector: FaceDetector, bitmap: Bitmap): List<Rect> =
    runCatching { detector.process(InputImage.fromBitmap(bitmap, 0)).await() }
        .getOrDefault(emptyList())
        .map { it.boundingBox }

/** Newest image the user attached in chat (attachments are copied into files/upload). */
private fun latestAttachment(context: Context): File? =
    File(context.filesDir, "upload").listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
        ?.maxByOrNull { it.lastModified() }

private fun hasGalleryAccess(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

/** Gallery images, newest first, from the camera roll and Pictures (skipping hidden dirs). */
private fun galleryImages(): List<File> {
    val root = Environment.getExternalStorageDirectory()
    val roots = listOf(File(root, "DCIM"), File(root, "Pictures"), File(root, "Download"))
    return roots.filter { it.isDirectory }
        .flatMap { dir ->
            dir.walkTopDown()
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
                .toList()
        }
        .sortedByDescending { it.lastModified() }
}

fun rememberPersonTool(context: Context): Tool = Tool(
    name = "remember_person",
    description = "Remember a person's face so they can be found in the gallery later with " +
        "find_person_photos. Use when the user says things like 'ito ako', 'this is me', or " +
        "'tandaan mo ang mukha ni X'. Uses photo_path if given, otherwise the newest image the " +
        "user attached in this chat. Call it again with more photos of the same name to improve " +
        "matching. Use name 'me' for the user themselves unless they give their name.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Who this is, e.g. 'me' or 'Maria'")
                })
                put("photo_path", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional absolute path to a photo. Omit to use the latest chat attachment.")
                })
            },
            required = listOf("name"),
        )
    },
    execute = execute@{ input: JsonElement ->
        val args = input.jsonObject
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (name.isNullOrBlank()) return@execute error("missing_name", "name is required")
        val rawPath = args["photo_path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val source = if (rawPath != null) {
            File(AgentWorkspace.expand(rawPath.removePrefix("file://")))
        } else {
            latestAttachment(context)
                ?: return@execute error(
                    "no_photo",
                    "No photo_path given and no image attached in chat. Ask the user to attach a clear photo of the face.",
                )
        }
        if (!source.isFile) return@execute error("not_found", "Photo not found: ${source.absolutePath}")

        withContext<List<UIMessagePart>>(Dispatchers.Default) {
            val bitmap = decodeUpright(source, 1280)
                ?: return@withContext error("unreadable", "Could not read the photo.")
            val detector = newDetector()
            try {
                val faces = detectFaces(detector, bitmap)
                if (faces.isEmpty()) {
                    return@withContext error("no_face", "No face found. Ask for a clear, front-facing photo.")
                }
                // The biggest face is the subject; others are background people.
                val face = faces.maxBy { it.width() * it.height() }
                val crop = cropFace(bitmap, face)
                val out = File(personDir(context, name), "ref_${System.currentTimeMillis()}.jpg")
                saveJpeg(crop, out)
                val count = personDir(context, name).listFiles()?.count { it.name.startsWith("ref_") } ?: 1
                listOf(
                    UIMessagePart.Image(url = "file://${out.absolutePath}"),
                    UIMessagePart.Text(buildJsonObject {
                        put("success", true)
                        put("name", name)
                        put("reference_photos", count)
                        put("faces_in_photo", faces.size)
                        put(
                            "note",
                            if (faces.size > 1) "Several faces were in the photo; the largest one was saved. Confirm with the user that the saved face is right."
                            else "Face saved. More photos of the same person improve matching.",
                        )
                    }.toString()),
                )
            } finally {
                detector.close()
                bitmap.recycle()
            }
        }
    },
)

fun forgetPersonTool(context: Context): Tool = Tool(
    name = "forget_person",
    description = "Delete a remembered person's saved face photos.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
            },
            required = listOf("name"),
        )
    },
    execute = execute@{ input: JsonElement ->
        val name = input.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            ?: return@execute error("missing_name", "name is required")
        val dir = personDir(context, name)
        val existed = dir.exists()
        dir.deleteRecursively()
        listOf(UIMessagePart.Text(buildJsonObject {
            put("success", existed)
            put("name", name)
        }.toString()))
    },
)

fun findPersonPhotosTool(context: Context): Tool = Tool(
    name = "find_person_photos",
    description = "Find photos of a remembered person (see remember_person) in the phone's gallery " +
        "(DCIM, Pictures, Download), newest first. Faces are detected on the device; the result " +
        "shows the person's reference face and numbered contact sheets of faces found. Compare " +
        "each numbered face with the reference yourself, then tell the user which photos match " +
        "and call show_image on the matching paths. If none match, call again with skip_photos " +
        "set to the returned next_skip to search older photos.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("name", buildJsonObject {
                    put("type", "string")
                    put("description", "Name used with remember_person, e.g. 'me'")
                })
                put("max_photos", buildJsonObject {
                    put("type", "integer")
                    put("description", "How many gallery photos to scan (default $DEFAULT_SCAN, max $MAX_SCAN)")
                })
                put("skip_photos", buildJsonObject {
                    put("type", "integer")
                    put("description", "Skip this many newest photos (to continue a previous search)")
                })
            },
            required = listOf("name"),
        )
    },
    execute = execute@{ input: JsonElement ->
        val args = input.jsonObject
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (name.isNullOrBlank()) return@execute error("missing_name", "name is required")
        val references = personDir(context, name).listFiles()
            ?.filter { it.name.startsWith("ref_") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        if (references.isEmpty()) {
            val known = peopleRoot(context).listFiles()?.filter { it.isDirectory }?.map { it.name }.orEmpty()
            return@execute error(
                "unknown_person",
                "No saved face for '$name'. Known: ${known.ifEmpty { listOf("none") }}. Use remember_person first.",
            )
        }
        if (!hasGalleryAccess()) {
            return@execute error(
                "no_storage_access",
                "All-files access is off. Ask the user to allow it in Settings → Apps → HezamV.0 → Files and media.",
            )
        }
        val maxPhotos = (args["max_photos"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SCAN).coerceIn(1, MAX_SCAN)
        val skip = (args["skip_photos"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)

        withContext<List<UIMessagePart>>(Dispatchers.Default) {
            val all = galleryImages()
            val batch = all.drop(skip).take(maxPhotos)
            val detector = newDetector()
            val candidates = mutableListOf<Pair<File, Bitmap>>()
            var scanned = 0
            try {
                for (file in batch) {
                    if (candidates.size >= MAX_FACES_PER_SEARCH) break
                    scanned++
                    val bitmap = decodeUpright(file, 900) ?: continue
                    try {
                        for (box in detectFaces(detector, bitmap)) {
                            if (candidates.size >= MAX_FACES_PER_SEARCH) break
                            candidates += file to cropFace(bitmap, box)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            } finally {
                detector.close()
            }

            val outDir = File(context.filesDir, "tool_outputs/faces").apply {
                deleteRecursively(); mkdirs()
            }
            val parts = mutableListOf<UIMessagePart>()
            references.take(3).forEach { parts += UIMessagePart.Image(url = "file://${it.absolutePath}") }

            val perSheet = SHEET_COLUMNS * 5
            candidates.chunked(perSheet).forEachIndexed { sheetIndex, chunk ->
                val sheet = buildSheet(chunk.map { it.second }, startNumber = sheetIndex * perSheet + 1)
                val out = File(outDir, "sheet_${sheetIndex + 1}.jpg")
                saveJpeg(sheet, out)
                sheet.recycle()
                parts += UIMessagePart.Image(url = "file://${out.absolutePath}")
            }
            candidates.forEach { it.second.recycle() }

            parts += UIMessagePart.Text(buildJsonObject {
                put("person", name)
                put("reference_images", minOf(references.size, 3))
                put("photos_scanned", scanned)
                put("faces_found", candidates.size)
                put("next_skip", skip + scanned)
                put("more_photos_left", skip + scanned < all.size)
                put("faces", buildJsonArray {
                    candidates.forEachIndexed { index, (file, _) ->
                        add(buildJsonObject {
                            put("number", index + 1)
                            put("path", file.absolutePath)
                        })
                    }
                })
                put(
                    "instructions",
                    "The first ${minOf(references.size, 3)} image(s) are reference faces of '$name'. " +
                        "The numbered sheets are faces found in the gallery. Decide which numbers are " +
                        "the same person, then show the matching photos with show_image.",
                )
            }.toString())
            parts
        }
    },
)

/** Lays [faces] out in a numbered grid so the model can refer to each one by number. */
private fun buildSheet(faces: List<Bitmap>, startNumber: Int): Bitmap {
    val rows = (faces.size + SHEET_COLUMNS - 1) / SHEET_COLUMNS
    val sheet = Bitmap.createBitmap(SHEET_COLUMNS * CROP_SIZE, rows * CROP_SIZE, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sheet)
    canvas.drawColor(Color.BLACK)
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 30f
        isFakeBoldText = true
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }
    faces.forEachIndexed { index, face ->
        val x = (index % SHEET_COLUMNS) * CROP_SIZE.toFloat()
        val y = (index / SHEET_COLUMNS) * CROP_SIZE.toFloat()
        canvas.drawBitmap(face, x, y, null)
        canvas.drawText("${startNumber + index}", x + 6f, y + 32f, label)
    }
    return sheet
}

package com.memex.android.data.repository

import com.memex.android.data.api.MemexApiService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.InputStream
import java.time.Instant
import java.util.Base64

@Serializable
data class ClipboardItem(
    val id: String,
    val kind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("size_bytes") val sizeBytes: Int,
    @SerialName("can_keep") val canKeep: Boolean = false,
    val filename: String? = null,
    val text: String? = null,
    @SerialName("promotion_capture_id") val promotionCaptureId: String? = null
) {
    fun isLive(now: Instant = Instant.now()): Boolean =
        runCatching { Instant.parse(expiresAt).isAfter(now) }.getOrDefault(false)
}

@Serializable
data class ClipboardRequest(
    val kind: String,
    val text: String? = null,
    @SerialName("data_base64") val dataBase64: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val filename: String? = null,
    val source: String = "android"
)
@Serializable data class ClipboardResponse(val item: ClipboardItem)
@Serializable data class ClipboardPage(val items: List<ClipboardItem>, @SerialName("next_before") val nextBefore: String? = null)
@Serializable data class ClipboardKeepResponse(@SerialName("capture_id") val captureId: String, val status: String, @SerialName("note_id") val noteId: String? = null)
@Serializable data class ClipboardDeleted(val deleted: String)

interface ClipboardRepository {
    suspend fun list(): List<ClipboardItem>
    suspend fun send(request: ClipboardRequest): ClipboardItem
    suspend fun get(id: String): ClipboardItem
    suspend fun content(id: String): ByteArray
    suspend fun delete(id: String)
    suspend fun keep(id: String): ClipboardKeepResponse
}

class ApiClipboardRepository(private val api: MemexApiService) : ClipboardRepository {
    override suspend fun list(): List<ClipboardItem> {
        val live = mutableListOf<ClipboardItem>()
        var cursor: String? = null
        do {
            val page = api.clipboard(before = cursor)
            live += page.items.filter { it.isLive() }
            cursor = page.nextBefore
        } while (live.size < 50 && cursor != null)
        return live.take(50)
    }
    override suspend fun send(request: ClipboardRequest) = api.clipboardCreate(request).item
    override suspend fun get(id: String) = api.clipboardGet(id).item
    override suspend fun content(id: String): ByteArray = api.clipboardContent(id).use {
        it.byteStream().use { stream -> readClipboardBytes(stream) }
    }
    override suspend fun delete(id: String) { api.clipboardDelete(id) }
    override suspend fun keep(id: String) = api.clipboardKeep(id)
}

fun readClipboardBytes(stream: InputStream, limit: Int = 10 * 1024 * 1024): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        require(output.size() + read <= limit) { "File exceeds 10 MiB" }
        output.write(buffer, 0, read)
    }
    require(output.size() > 0) { "The shared file is empty" }
    return output.toByteArray()
}

fun clipboardText(text: String): ClipboardRequest {
    require(text.isNotEmpty() && text.toByteArray(Charsets.UTF_8).size <= 65536) { "Text must be 1 byte to 64 KiB" }
    val url = runCatching { java.net.URI(text) }.getOrNull()
    val isUrl = url?.scheme in listOf("http", "https") && !url?.host.isNullOrEmpty()
    return ClipboardRequest(kind = if (isUrl) "url" else "text", text = text)
}

fun clipboardFile(bytes: ByteArray, mime: String, filename: String?): ClipboardRequest {
    require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "File must be 1 byte to 10 MiB" }
    return ClipboardRequest(kind = if (mime.startsWith("image/")) "image" else "file",
        dataBase64 = Base64.getEncoder().encodeToString(bytes), mimeType = mime, filename = filename)
}

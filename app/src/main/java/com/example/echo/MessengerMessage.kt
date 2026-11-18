package com.example.echo

import org.json.JSONException
import org.json.JSONObject

/**
 * A single chat message (text or image).
 */
data class MessengerMessage(
    val sender: String,
    val text: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val imageData: ByteArray? = null,
    val imageMimeType: String? = null,
    val localId: Long? = null,
    val isPending: Boolean = false
) {
    val isImage: Boolean get() = imageData != null && imageData.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessengerMessage) return false
        return sender == other.sender &&
            text == other.text &&
            timestamp == other.timestamp &&
            imageMimeType == other.imageMimeType &&
            localId == other.localId &&
            isPending == other.isPending &&
            ((imageData == null && other.imageData == null) ||
                (imageData != null && other.imageData != null && imageData.contentEquals(other.imageData)))
    }

    override fun hashCode(): Int {
        var result = sender.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + (imageData?.contentHashCode() ?: 0)
        result = 31 * result + (imageMimeType?.hashCode() ?: 0)
        result = 31 * result + (localId?.hashCode() ?: 0)
        result = 31 * result + isPending.hashCode()
        return result
    }
}

/**
 * Parses a BLE text payload into a [MessengerMessage].
 */
object MessengerMessageParser {

    fun parse(raw: String, fallbackSender: String): MessengerMessage {
        val trimmed = raw.trim()
        parseJson(trimmed, fallbackSender)?.let { return it }
        parseDelimited(trimmed, fallbackSender)?.let { return it }
        return MessengerMessage(sender = fallbackSender, text = trimmed)
    }

    private fun parseJson(raw: String, fallbackSender: String): MessengerMessage? {
        if (!raw.startsWith("{")) return null
        return try {
            val json = JSONObject(raw)

            val sender = sequenceOf("from", "sender", "user", "nickname")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: fallbackSender

            val text = sequenceOf("text", "message", "msg", "body")
                .map { json.optString(it) }
                .firstOrNull { it.isNotBlank() }
                ?: return null

            val rawTimestamp = json.optLong("timestamp", 0L)
            val timestamp = when {
                rawTimestamp <= 0L -> System.currentTimeMillis()
                rawTimestamp < 1_000_000_000_000L -> rawTimestamp * 1000L
                else -> rawTimestamp
            }

            MessengerMessage(sender = sender, text = text, timestamp = timestamp)
        } catch (_: JSONException) {
            null
        }
    }

    private fun parseDelimited(raw: String, fallbackSender: String): MessengerMessage? {
        val separator = when {
            raw.contains('|') -> '|'
            raw.contains(": ") -> ':'
            else -> return null
        }
        val index = raw.indexOf(separator)
        if (index <= 0 || index >= raw.length - 1) return null

        val sender = raw.substring(0, index).trim()
        val text = raw.substring(index + 1).trim()
        if (sender.isEmpty() || text.isEmpty()) return null

        if (separator == ':' && (sender.contains(' ') || sender.length > 32)) return null

        return MessengerMessage(sender = sender, text = text)
    }
}

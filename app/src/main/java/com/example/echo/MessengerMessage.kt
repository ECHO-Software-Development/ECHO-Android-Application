package com.example.echo

import org.json.JSONException
import org.json.JSONObject

/**
 * A single message received from (or sent to) the ESP32.
 */
data class MessengerMessage(
    val sender: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Parses a BLE payload into a [MessengerMessage].
 *
 * Supported UTF-8 payloads:
 *  1. JSON:  {"from":"alice","text":"hello","timestamp":1699999999999}
 *     Field aliases: from/sender/user/nickname, text/message/msg/body.
 *  2. Pipe:  "alice|hello"
 *  3. Colon: "alice: hello"
 *  4. Plain: "hello"  -> sender falls back to the BLE device name/address.
 *
 * If the timestamp is missing it defaults to "now". Timestamps in seconds are
 * converted to milliseconds automatically.
 */
object MessengerMessageParser {

    fun parse(raw: String, fallbackSender: String): MessengerMessage {
        val trimmed = raw.trim()
        parseJson(trimmed, fallbackSender)?.let { return it }
        parseDelimited(trimmed, fallbackSender)?.let { return it }
        return MessengerMessage(fallbackSender, trimmed)
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

            MessengerMessage(sender, text, timestamp)
        } catch (exception: JSONException) {
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

        // For "sender: text" avoid treating ordinary sentences like "note: buy milk"
        // as a sender prefix. Pipe-delimited messages may contain spaces.
        if (separator == ':' && (sender.contains(' ') || sender.length > 32)) return null

        return MessengerMessage(sender, text)
    }
}

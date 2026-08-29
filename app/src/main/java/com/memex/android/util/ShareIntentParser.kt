package com.memex.android.util

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.util.regex.Pattern

/**
 * Representation of structured content received via Android's share sheet (ACTION_SEND).
 */
sealed interface IncomingShare {
    data class Link(
        val url: String,
        val title: String? = null,
        val note: String? = null
    ) : IncomingShare

    data class Text(
        val text: String
    ) : IncomingShare

    data class Image(
        val uri: Uri,
        val caption: String? = null
    ) : IncomingShare
}

/**
 * Utility for parsing and sanitizing incoming [Intent.ACTION_SEND] intents into [IncomingShare].
 */
object ShareIntentParser {

    private val URL_PATTERN = Pattern.compile(
        "https?://[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]+",
        Pattern.CASE_INSENSITIVE
    )

    fun parse(intent: Intent?): IncomingShare? {
        if (intent == null || intent.action != Intent.ACTION_SEND) {
            return null
        }

        val mimeType = intent.type.orEmpty().lowercase()

        // 1. Handle image / screenshot share
        if (mimeType.startsWith("image/") || isImageIntent(intent)) {
            val uri = extractImageUri(intent) ?: return null
            val caption = extractText(intent, Intent.EXTRA_TEXT)
                ?: extractText(intent, Intent.EXTRA_SUBJECT)
            return IncomingShare.Image(
                uri = uri,
                caption = caption?.trim()?.ifBlank { null }
            )
        }

        // 2. Handle text / URL share
        val rawText = extractText(intent, Intent.EXTRA_TEXT)
        val subject = extractText(intent, Intent.EXTRA_SUBJECT)
            ?: extractText(intent, Intent.EXTRA_TITLE)

        if (!rawText.isNullOrBlank()) {
            val trimmedText = rawText.take(100_000).trim()
            val matcher = URL_PATTERN.matcher(trimmedText)

            if (matcher.find()) {
                val rawUrl = matcher.group()
                val url = cleanUrl(rawUrl)
                val noteRemainder = trimmedText.replace(rawUrl, "")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { null }
                val title = subject?.trim()?.ifBlank { null }

                return IncomingShare.Link(
                    url = url,
                    title = title,
                    note = noteRemainder
                )
            }

            return IncomingShare.Text(text = trimmedText)
        }

        return null
    }

    /**
     * Strips sentence-ending punctuation and unmatched closing delimiters in O(N) linear time
     * while preserving valid URL characters (e.g. semicolons, colons, exclamation marks, balanced quotes).
     */
    fun cleanUrl(rawUrl: String): String {
        if (rawUrl.isEmpty()) return ""

        var openParens = 0
        var closeParens = 0
        var openBrackets = 0
        var closeBrackets = 0
        var singleQuotes = 0
        var doubleQuotes = 0
        var openAngle = 0
        var closeAngle = 0

        for (i in 0 until rawUrl.length) {
            when (rawUrl[i]) {
                '(' -> openParens++
                ')' -> closeParens++
                '[' -> openBrackets++
                ']' -> closeBrackets++
                '\'' -> singleQuotes++
                '"' -> doubleQuotes++
                '<' -> openAngle++
                '>' -> closeAngle++
            }
        }

        var excessCloseParens = (closeParens - openParens).coerceAtLeast(0)
        var excessCloseBrackets = (closeBrackets - openBrackets).coerceAtLeast(0)
        var excessSingleQuotes = singleQuotes % 2
        var excessDoubleQuotes = doubleQuotes % 2
        var excessCloseAngle = (closeAngle - openAngle).coerceAtLeast(0)

        var end = rawUrl.length
        while (end > 0) {
            val lastChar = rawUrl[end - 1]
            if (lastChar == ')' && excessCloseParens > 0) {
                excessCloseParens--
                end--
            } else if (lastChar == ']' && excessCloseBrackets > 0) {
                excessCloseBrackets--
                end--
            } else if (lastChar == '>' && excessCloseAngle > 0) {
                excessCloseAngle--
                end--
            } else if (lastChar == '\'' && excessSingleQuotes > 0) {
                excessSingleQuotes--
                end--
            } else if (lastChar == '"' && excessDoubleQuotes > 0) {
                excessDoubleQuotes--
                end--
            } else if (lastChar == '.' || lastChar == ',') {
                end--
            } else {
                break
            }
        }

        return rawUrl.substring(0, end)
    }

    private fun isImageIntent(intent: Intent): Boolean {
        val streamUri = extractImageUri(intent)
        return streamUri != null
    }

    private fun extractImageUri(intent: Intent): Uri? {
        // Try IntentCompat getParcelableExtra first
        try {
            val streamUri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (streamUri != null) return streamUri
        } catch (_: Exception) {}

        // Fallback for mock environments or legacy intents
        @Suppress("DEPRECATION")
        val legacyStream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (legacyStream != null) return legacyStream

        // Try ClipData
        val clipData = intent.clipData
        if (clipData != null && clipData.itemCount > 0) {
            val clipUri = clipData.getItemAt(0).uri
            if (clipUri != null) return clipUri
        }

        // Try Intent Data
        return intent.data
    }

    private fun extractText(intent: Intent, extraName: String): String? {
        val str = intent.getStringExtra(extraName)
        if (!str.isNullOrBlank()) return str
        val charSeq = intent.getCharSequenceExtra(extraName)
        return charSeq?.toString()
    }
}

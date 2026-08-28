package com.memex.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memex.android.ui.theme.CodeBlockBackground
import com.memex.android.ui.theme.CodeBlockText

private sealed interface MarkdownBlock {
    data class Header(val level: Int, val text: String) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
    data class CodeBlock(val language: String?, val code: String) : MarkdownBlock
    data class BulletItem(val text: String) : MarkdownBlock
    data class NumberedItem(val number: String, val text: String) : MarkdownBlock
    data class Blockquote(val text: String) : MarkdownBlock
    data object Divider : MarkdownBlock
}

private fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val rawLine = lines[i]
        val trimmed = rawLine.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Code block start
        if (trimmed.startsWith("```")) {
            val language = trimmed.removePrefix("```").trim().ifBlank { null }
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size && lines[i].trim().startsWith("```")) {
                i++
            }
            blocks.add(MarkdownBlock.CodeBlock(language, codeLines.joinToString("\n")))
            continue
        }

        // Horizontal rule
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // Headings
        if (trimmed.startsWith("#")) {
            val level = trimmed.takeWhile { it == '#' }.length
            val headingText = trimmed.drop(level).trim()
            blocks.add(MarkdownBlock.Header(level, headingText))
            i++
            continue
        }

        // Blockquotes
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.Blockquote(quoteLines.joinToString(" ")))
            continue
        }

        // Bullet lists
        if (trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("+ ")) {
            val bulletText = trimmed.substring(2).trim()
            blocks.add(MarkdownBlock.BulletItem(bulletText))
            i++
            continue
        }

        // Numbered lists (e.g. "1. ", "23. ")
        val numberMatch = Regex("""^(\d+)\.\s+(.*)$""").find(trimmed)
        if (numberMatch != null) {
            val num = numberMatch.groupValues[1]
            val itemText = numberMatch.groupValues[2]
            blocks.add(MarkdownBlock.NumberedItem(num, itemText))
            i++
            continue
        }

        // Standard paragraph (accumulate consecutive non-empty lines)
        val paraLines = mutableListOf<String>()
        while (i < lines.size &&
            lines[i].trim().isNotEmpty() &&
            !lines[i].trim().startsWith("#") &&
            !lines[i].trim().startsWith("```") &&
            !lines[i].trim().startsWith(">") &&
            !lines[i].trim().startsWith("* ") &&
            !lines[i].trim().startsWith("- ") &&
            !lines[i].trim().startsWith("+ ") &&
            !lines[i].trim().matches(Regex("""^\d+\.\s+.*""")) &&
            lines[i].trim() != "---"
        ) {
            paraLines.add(lines[i].trim())
            i++
        }
        blocks.add(MarkdownBlock.Paragraph(paraLines.joinToString(" ")))
    }

    return blocks
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = parseMarkdownBlocks(markdown)

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(8.dp))
            }
            when (block) {
                is MarkdownBlock.Header -> {
                    val headerStyle = when (block.level) {
                        1 -> MaterialTheme.typography.headlineMedium
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    val annotated = buildInlineMarkdown(
                        raw = block.text,
                        linkColor = MaterialTheme.colorScheme.primary,
                        codeBackground = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = annotated,
                        style = headerStyle.copy(
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
                is MarkdownBlock.Paragraph -> {
                    val annotated = buildInlineMarkdown(
                        raw = block.text,
                        linkColor = MaterialTheme.colorScheme.primary,
                        codeBackground = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Text(
                        text = annotated,
                        style = style.copy(color = color)
                    )
                }
                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(CodeBlockBackground)
                            .padding(12.dp)
                    ) {
                        Column {
                            if (block.language != null) {
                                Text(
                                    text = block.language.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CodeBlockText.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            Text(
                                text = block.code,
                                color = CodeBlockText,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            )
                        }
                    }
                }
                is MarkdownBlock.BulletItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "•",
                            style = style.copy(fontWeight = FontWeight.Bold, color = color),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        val annotated = buildInlineMarkdown(
                            raw = block.text,
                            linkColor = MaterialTheme.colorScheme.primary,
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = annotated,
                            style = style.copy(color = color)
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${block.number}.",
                            style = style.copy(fontWeight = FontWeight.Medium, color = color),
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        val annotated = buildInlineMarkdown(
                            raw = block.text,
                            linkColor = MaterialTheme.colorScheme.primary,
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = annotated,
                            style = style.copy(color = color)
                        )
                    }
                }
                is MarkdownBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(vertical = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(4.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val annotated = buildInlineMarkdown(
                            raw = block.text,
                            linkColor = MaterialTheme.colorScheme.primary,
                            codeBackground = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Text(
                            text = annotated,
                            style = style.copy(fontStyle = FontStyle.Italic, color = color)
                        )
                    }
                }
                is MarkdownBlock.Divider -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
        }
    }
}

/**
 * Builds an AnnotatedString parsing inline formatting:
 * - Bold: `**text**` or `__text__`
 * - Italic: `*text*` or `_text_`
 * - Strikethrough: `~~text~~`
 * - Inline code: `` `code` ``
 * - Markdown links: `[label](url)`
 * - Raw URLs: `https://...`
 */
fun buildInlineMarkdown(
    raw: String,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString {
    return buildAnnotatedString {
        val regex = Regex(
            """(\[(.*?)\]\((https?://[^\s\)]+)\))|(\*\*(.*?)\*\*)|(\*(.*?)\*)|(`(.*?)`)|(~~(.*?)~~)|(https?://[^\s\)]+)"""
        )

        var lastIndex = 0
        val matches = regex.findAll(raw)

        for (match in matches) {
            if (match.range.first > lastIndex) {
                append(raw.substring(lastIndex, match.range.first))
            }

            val linkGroup = match.groups[1]
            val boldGroup = match.groups[4]
            val italicGroup = match.groups[6]
            val codeGroup = match.groups[8]
            val strikeGroup = match.groups[10]
            val rawUrlGroup = match.groups[12]

            when {
                linkGroup != null -> {
                    val linkText = match.groups[2]?.value ?: ""
                    val linkUrl = match.groups[3]?.value ?: ""
                    val start = length
                    append(linkText)
                    val end = length
                    addLink(
                        url = LinkAnnotation.Url(
                            url = linkUrl,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        ),
                        start = start,
                        end = end
                    )
                }
                boldGroup != null -> {
                    val boldText = match.groups[5]?.value ?: ""
                    val start = length
                    append(boldText)
                    val end = length
                    addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                }
                italicGroup != null -> {
                    val italicText = match.groups[7]?.value ?: ""
                    val start = length
                    append(italicText)
                    val end = length
                    addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                }
                codeGroup != null -> {
                    val codeText = match.groups[9]?.value ?: ""
                    val start = length
                    append(codeText)
                    val end = length
                    addStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeBackground,
                            fontSize = 13.sp
                        ),
                        start,
                        end
                    )
                }
                strikeGroup != null -> {
                    val strikeText = match.groups[11]?.value ?: ""
                    val start = length
                    append(strikeText)
                    val end = length
                    addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                }
                rawUrlGroup != null -> {
                    val urlText = rawUrlGroup.value
                    val start = length
                    append(urlText)
                    val end = length
                    addLink(
                        url = LinkAnnotation.Url(
                            url = urlText,
                            styles = TextLinkStyles(
                                style = SpanStyle(
                                    color = linkColor,
                                    textDecoration = TextDecoration.Underline
                                )
                            )
                        ),
                        start = start,
                        end = end
                    )
                }
                else -> {
                    append(match.value)
                }
            }

            lastIndex = match.range.last + 1
        }

        if (lastIndex < raw.length) {
            append(raw.substring(lastIndex))
        }
    }
}

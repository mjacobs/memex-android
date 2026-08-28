package com.memex.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memex.android.data.model.TraceEvent
import com.memex.android.ui.theme.CodeBlockBackground
import com.memex.android.ui.theme.CodeBlockText
import com.memex.android.ui.theme.RoleModel
import com.memex.android.ui.theme.RoleModelContainer
import com.memex.android.ui.theme.RoleSystem
import com.memex.android.ui.theme.RoleSystemContainer
import com.memex.android.ui.theme.RoleTool
import com.memex.android.ui.theme.RoleToolContainer
import com.memex.android.ui.theme.RoleUser
import com.memex.android.ui.theme.RoleUserContainer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.Locale

private val prettyJson = Json { prettyPrint = true }

@Composable
fun AgentTraceView(
    trace: List<TraceEvent>,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    title: String = "Agent Trace Replay"
) {
    if (trace.isEmpty()) return

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Accordion Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${trace.size} step${if (trace.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse trace" else "Expand trace",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Accordion Body
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    trace.forEachIndexed { index, event ->
                        TraceEventItem(index = index + 1, event = event)
                    }
                }
            }
        }
    }
}

@Composable
fun TraceEventItem(
    index: Int,
    event: TraceEvent,
    modifier: Modifier = Modifier
) {
    val (roleIcon, roleColor, roleBg, roleLabel) = when (event.role.lowercase(Locale.ROOT)) {
        "user" -> Quad(Icons.Default.Person, RoleUser, RoleUserContainer, "User")
        "model" -> Quad(Icons.Default.SmartToy, RoleModel, RoleModelContainer, "Agent")
        "tool" -> Quad(Icons.Default.Build, RoleTool, RoleToolContainer, "Tool: ${event.tool ?: "call"}")
        else -> Quad(Icons.Default.Psychology, RoleSystem, RoleSystemContainer, event.role)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Step #, Role Badge, Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(roleBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = roleIcon,
                                contentDescription = null,
                                tint = roleColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = roleLabel,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = roleColor
                            )
                        }
                    }
                }

                if (event.t.isNotBlank()) {
                    Text(
                        text = formatTimestamp(event.t),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }

            // Body content
            if (!event.text.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownText(
                    markdown = event.text,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Tool Call Arguments
            if (event.args != null && event.args.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Arguments:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                CodeSnippetBox(code = formatJson(event.args))
            }

            // Tool Call Result
            if (event.result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Result:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(2.dp))
                CodeSnippetBox(code = formatJson(event.result))
            }
        }
    }
}

@Composable
private fun CodeSnippetBox(code: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(CodeBlockBackground)
            .padding(8.dp)
    ) {
        Text(
            text = code,
            color = CodeBlockText,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState())
        )
    }
}

private fun formatJson(element: JsonElement): String {
    return try {
        prettyJson.encodeToString(JsonElement.serializer(), element)
    } catch (_: Exception) {
        element.toString()
    }
}

private fun formatTimestamp(isoString: String): String {
    return try {
        // e.g. "2026-08-28T12:00:01Z" -> "12:00:01"
        if (isoString.contains("T")) {
            isoString.substringAfter("T").take(8)
        } else {
            isoString
        }
    } catch (_: Exception) {
        isoString
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

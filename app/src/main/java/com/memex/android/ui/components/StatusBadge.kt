package com.memex.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.memex.android.ui.theme.KindCapture
import com.memex.android.ui.theme.KindCaptureContainer
import com.memex.android.ui.theme.KindDigest
import com.memex.android.ui.theme.KindDigestContainer
import com.memex.android.ui.theme.KindLink
import com.memex.android.ui.theme.KindLinkContainer
import com.memex.android.ui.theme.KindResearch
import com.memex.android.ui.theme.KindResearchContainer
import com.memex.android.ui.theme.KindReview
import com.memex.android.ui.theme.KindReviewContainer
import java.util.Locale

data class BadgeColors(
    val containerColor: Color,
    val contentColor: Color
)

@Composable
fun getKindBadgeColors(kind: String): BadgeColors {
    val isDark = MaterialTheme.colorScheme.background.red < 0.5f // or dark theme detection
    return when (kind.lowercase(Locale.ROOT)) {
        "capture" -> if (isDark) {
            BadgeColors(containerColor = Color(0xFF1E3A8A), contentColor = Color(0xFF93C5FD))
        } else {
            BadgeColors(containerColor = KindCaptureContainer, contentColor = KindCapture)
        }
        "digest" -> if (isDark) {
            BadgeColors(containerColor = Color(0xFF4C1D95), contentColor = Color(0xFFC4B5FD))
        } else {
            BadgeColors(containerColor = KindDigestContainer, contentColor = KindDigest)
        }
        "review" -> if (isDark) {
            BadgeColors(containerColor = Color(0xFF78350F), contentColor = Color(0xFFFDE68A))
        } else {
            BadgeColors(containerColor = KindReviewContainer, contentColor = KindReview)
        }
        "link" -> if (isDark) {
            BadgeColors(containerColor = Color(0xFF064E3B), contentColor = Color(0xFF6EE7B7))
        } else {
            BadgeColors(containerColor = KindLinkContainer, contentColor = KindLink)
        }
        "research" -> if (isDark) {
            BadgeColors(containerColor = Color(0xFF831843), contentColor = Color(0xFFF9A8D4))
        } else {
            BadgeColors(containerColor = KindResearchContainer, contentColor = KindResearch)
        }
        else -> BadgeColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    modifier: Modifier = Modifier,
    containerColor: Color? = null,
    contentColor: Color? = null
) {
    val defaultColors = getKindBadgeColors(text)
    val bg = containerColor ?: defaultColors.containerColor
    val fg = contentColor ?: defaultColors.contentColor

    val displayText = text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = displayText,
            color = fg,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}

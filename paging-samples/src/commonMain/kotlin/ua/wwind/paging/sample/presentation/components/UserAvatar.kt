package ua.wwind.paging.sample.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Reusable avatar component with activity status indicator
 */
@Composable
fun UserAvatar(
    avatarUrl: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // Avatar circle with initials (placeholder for actual image loading)
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .border(
                    width = if (isActive) 2.dp else 0.dp,
                    color = if (isActive) Color.Green else Color.Transparent,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            // For now, show first letter of hash as placeholder
            // In real app, you would load image from avatarUrl
            Text(
                text = avatarUrl.hashCode().toString().first().toString(),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // Activity status indicator
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(Color.Green)
                    .align(Alignment.BottomEnd)
            )
        }
    }
}
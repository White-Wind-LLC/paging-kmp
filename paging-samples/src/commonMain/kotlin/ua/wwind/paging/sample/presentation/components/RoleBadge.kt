package ua.wwind.paging.sample.presentation.components

import androidx.compose.foundation.background
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
import ua.wwind.paging.sample.domain.model.UserRole

/**
 * Component for displaying user role as a colored badge
 */
@Composable
fun RoleBadge(
    role: UserRole,
    modifier: Modifier = Modifier,
) {
    val (backgroundColor, textColor) = getRoleColors(role)

    Text(
        text = role.name,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = textColor,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

/**
 * Returns appropriate colors for different user roles
 */
@Composable
private fun getRoleColors(role: UserRole): Pair<Color, Color> {
    return when (role) {
        UserRole.ADMIN -> Color(0xFFE53E3E) to Color.White // Red
        UserRole.MODERATOR -> Color(0xFF3182CE) to Color.White // Blue  
        UserRole.USER -> Color(0xFF38A169) to Color.White // Green
        UserRole.GUEST -> Color(0xFF718096) to Color.White // Gray
    }
}
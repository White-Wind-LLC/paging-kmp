package ua.wwind.paging.sample.scrollbar.material3

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visual configuration for the scrollbar.
 */
data class ScrollbarStyle(
    val thickness: Dp,
    val minimalLength: Dp,
    val thumbColor: Color,
    val trackColor: Color,
    val thumbShape: Shape,
)

@Composable
fun defaultMaterialScrollbarStyle(): ScrollbarStyle = ScrollbarStyle(
    thickness = 6.dp,
    minimalLength = 24.dp,
    thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    thumbShape = RoundedCornerShape(999.dp),
)

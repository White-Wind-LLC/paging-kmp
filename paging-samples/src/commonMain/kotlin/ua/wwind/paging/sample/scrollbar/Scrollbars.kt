package ua.wwind.paging.sample.scrollbar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import ua.wwind.paging.sample.scrollbar.material3.ScrollbarStyle
import ua.wwind.paging.sample.scrollbar.material3.defaultMaterialScrollbarStyle
import kotlin.math.max

/**
 * Simple vertical scrollbar indicator (non-interactive).
 */
@Composable
fun VerticalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = defaultMaterialScrollbarStyle(),
    enablePressToScroll: Boolean = false, // Reserved for future interactive behavior
) {
    Canvas(modifier = modifier.fillMaxHeight()) {
        val widthPx = style.thickness.toPx()
        val trackLeft = size.width - widthPx
        // Track
        drawRect(
            color = style.trackColor,
            topLeft = Offset(trackLeft, 0f),
            size = Size(widthPx, size.height)
        )
        // Thumb size and position
        val thumbHeight = max(style.minimalLength.toPx(), size.height * adapter.thumbSizeRatio)
        val available = (size.height - thumbHeight).coerceAtLeast(0f)
        val top = available * adapter.positionRatio
        // Thumb
        drawIntoCanvas {
            // Using translate to draw rounded rect via clip not strictly necessary here; drawRect is OK.
        }
        drawRoundRect(
            color = style.thumbColor,
            topLeft = Offset(trackLeft, top),
            size = Size(widthPx, thumbHeight),
            cornerRadius = CornerRadius(x = widthPx / 2f, y = widthPx / 2f)
        )
    }
}

/**
 * Simple horizontal scrollbar indicator (non-interactive).
 */
@Composable
fun HorizontalScrollbar(
    adapter: ScrollbarAdapter,
    modifier: Modifier = Modifier,
    style: ScrollbarStyle = defaultMaterialScrollbarStyle(),
    enablePressToScroll: Boolean = false,
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val heightPx = style.thickness.toPx()
        val trackTop = size.height - heightPx
        // Track
        drawRect(
            color = style.trackColor,
            topLeft = Offset(0f, trackTop),
            size = Size(size.width, heightPx)
        )
        val thumbWidth = max(style.minimalLength.toPx(), size.width * adapter.thumbSizeRatio)
        val available = (size.width - thumbWidth).coerceAtLeast(0f)
        val left = available * adapter.positionRatio
        drawRoundRect(
            color = style.thumbColor,
            topLeft = Offset(left, trackTop),
            size = Size(thumbWidth, heightPx),
            cornerRadius = CornerRadius(x = heightPx / 2f, y = heightPx / 2f)
        )
    }
}

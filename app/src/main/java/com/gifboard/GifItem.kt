package com.gifboard

/**
 * Data class representing a GIF item with its URL and dimensions.
 */
data class GifItem(
    val url: String,
    val thumbnailUrl: String?,
    val width: Int,
    val height: Int,
    val title: String? = null,
    val subtitle: String? = null
) {
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1f
    
    // Mutable state for tracking load failures (used when live previews enabled)
    var isFullLoadFailed: Boolean = false
    
    // Mutable state for tracking if item is displaying fallback image instead of gif
    var isFallbackImage: Boolean = false
}

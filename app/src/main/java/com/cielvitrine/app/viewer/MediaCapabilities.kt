package com.cielvitrine.app

/** Capability describes a viewing path, never the completeness/protection of a resource set. */
internal enum class MediaViewCapability { PREPARED_TILES, LOCAL_ANIMATION, PLATFORM_DECODER, OPAQUE_ONLY, COMPOUND_RESOURCES, COMPOUND_UNRESOLVED }
internal object MediaCapabilities {
    fun photo(mime: String, compoundKnown: Boolean = false): MediaViewCapability = when (mime.lowercase()) {
        "image/gif", "image/webp" -> MediaViewCapability.LOCAL_ANIMATION
        "image/jpeg", "image/png" -> MediaViewCapability.PREPARED_TILES
        "image/heif", "image/heic", "image/avif", "image/bmp" -> MediaViewCapability.PLATFORM_DECODER
        "application/vnd.apple.live-photo", "application/x-live-photo" -> if(compoundKnown) MediaViewCapability.COMPOUND_RESOURCES else MediaViewCapability.COMPOUND_UNRESOLVED
        else -> MediaViewCapability.OPAQUE_ONLY
    }
    fun notice(mime:String):String? = when(photo(mime)) {
        MediaViewCapability.LOCAL_ANIMATION -> "Анимация использует исходный файл; статические фрагменты не заменяют её кадры."
        MediaViewCapability.PLATFORM_DECODER -> "Просмотр зависит от декодера устройства. Оригинал сохраняется без изменения."
        MediaViewCapability.OPAQUE_ONLY -> "Формат сохраняется точно; поддержка просмотра не подтверждена."
        MediaViewCapability.COMPOUND_RESOURCES, MediaViewCapability.COMPOUND_UNRESOLVED -> "Полнота Live Photo не подтверждена; один кадр не защищает весь набор."
        MediaViewCapability.PREPARED_TILES -> null
    }
}

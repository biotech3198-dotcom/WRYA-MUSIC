package com.example.util

import android.net.Uri
import java.net.URI
import java.net.URL
import java.net.URLDecoder

object UrlHelper {

    /**
     * Normalizes and properly encodes audio & image URLs.
     * Fixes:
     * - Protocol issues (e.g. //dl.hawrami.ir -> https://dl.hawrami.ir)
     * - Spaces and special characters in paths (e.g. ' ' -> '%20', '[' -> '%5B', ']' -> '%5D')
     * - Trailing whitespace before extension (e.g. ' .mp3' or '%20.mp3')
     * - Non-ASCII Persian / Kurdish characters
     */
    fun normalizeAudioUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        
        var url = rawUrl.trim()
        
        // Fix trailing space issues like "%20.mp3" or " .mp3"
        url = url.replace(Regex("""(?i)\s+\.mp3"""), ".mp3")
        url = url.replace(Regex("""(?i)%20\.mp3"""), ".mp3")

        // Fix protocol
        if (url.startsWith("//")) {
            url = "https:$url"
        } else if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }

        return try {
            // First decode in case it's partially or doubly encoded, then cleanly encode
            val decoded = try {
                URLDecoder.decode(url, "UTF-8")
            } catch (e: Exception) {
                url
            }

            val parsedUrl = URL(decoded)
            val uri = URI(
                parsedUrl.protocol,
                parsedUrl.userInfo,
                parsedUrl.host,
                parsedUrl.port,
                parsedUrl.path,
                parsedUrl.query,
                parsedUrl.ref
            )
            uri.toASCIIString()
        } catch (e: Exception) {
            // Fallback manual replacement if URI constructor fails on malformed characters
            url.replace(" ", "%20")
                .replace("[", "%5B")
                .replace("]", "%5D")
                .replace("(", "%28")
                .replace(")", "%29")
                .replace("+", "%2B")
        }
    }
}

package com.example.util

import com.example.data.local.SongEntity

data class FormattedMetadata(
    val title: String,
    val artist: String,
    val displayTitle: String,
    val displayArtist: String,
    val displayAlbum: String
)

object SongMetadataFormatter {

    private val NOISE_PATTERNS = listOf(
        // Site / Download prefixes
        Regex("(?i)دانلود\\s+موزیک\\s+ویدیو(?:\\s+جدید)?"),
        Regex("(?i)دانلود\\s+آهنگ\\s+جدید"),
        Regex("(?i)دانلود\\s+اهنگ\\s+جدید"),
        Regex("(?i)دانلود\\s+موزیک\\s+جدید"),
        Regex("(?i)دانلود\\s+ویدیو(?:\\s+جدید)?"),
        Regex("(?i)دانلود\\s+آهنگ"),
        Regex("(?i)دانلود\\s+اهنگ"),
        Regex("(?i)دانلود\\s+موزیک"),
        Regex("(?i)دانلود\\s+ترانه"),
        Regex("(?i)دانلود\\s+ریمیکس(?:\\s+جدید)?"),
        Regex("(?i)دانلود\\s+پادکست"),
        Regex("(?i)دانلود"),
        // Quality & AI tags
        Regex("(?i)با\\s+کیفیت\\s+(?:320|128|۳۲۰|۱۲۸)"),
        Regex("(?i)کیفیت\\s+(?:320|128|۳۲۰|۱۲۸)"),
        Regex("(?i)کیفیت\\s+عالی"),
        Regex("(?i)کیفیت\\s+اصلی"),
        Regex("(?i)کیفیت\\s+اورجینال"),
        Regex("(?i)با\\s+هوش\\s+مصنوعی"),
        Regex("(?i)هوش\\s+مصنوعی"),
        Regex("(?i)\\+\\s*متن\\s+ترانه"),
        Regex("(?i)\\+\\s*متن"),
        Regex("(?i)متن\\s+ترانه"),
        Regex("(?i)متن\\s+کامل"),
        Regex("(?i)موزیک\\s+ویدیو"),
        Regex("(?i)ویدیو\\s+کلیپ"),
        // Common domains
        Regex("(?i)musickordi\\.com"),
        Regex("(?i)hawrami\\.ir"),
        Regex("(?i)gitarmuzic\\.com"),
        Regex("(?i)gitar\\s*music"),
        Regex("(?i)گیتار\\s*موزیک")
    )

    private val GENERIC_ARTISTS = setOf(
        "هنرمند", "نامشخص", "unknown", "artist", "آهنگ", "موزیک", "ترانه", "خواننده",
        "کردی", "فارسی", "گیتار موزیک", "هورامی", "موزیک کردی", "موزیک فارسی"
    )

    fun format(song: SongEntity, networkStatus: String? = null): FormattedMetadata {
        return format(
            rawTitle = song.title,
            rawArtist = song.artist,
            language = song.language,
            sourceNumber = song.sourceNumber,
            networkStatus = networkStatus
        )
    }

    fun format(
        rawTitle: String,
        rawArtist: String,
        language: String = "کوردی",
        sourceNumber: Int = 1,
        networkStatus: String? = null
    ): FormattedMetadata {
        val cleanTitle = cleanNoise(rawTitle)
        val cleanArtist = cleanNoise(rawArtist)

        val defaultLanguageLabel = if (language.contains("فارس", ignoreCase = true)) "موزیک فارسی" else "موزیک کوردی"
        val sourceLabel = if (!networkStatus.isNullOrBlank()) {
            networkStatus.trim()
        } else {
            when (sourceNumber) {
                1 -> "موزیک کردی (سورس ۱)"
                2 -> "هورامی موزیک (سورس ۲)"
                3 -> "گیتار موزیک (سورس ۳)"
                else -> defaultLanguageLabel
            }
        }

        val isArtistGeneric = cleanArtist.isBlank() ||
                GENERIC_ARTISTS.contains(cleanArtist.trim().lowercase()) ||
                cleanArtist.equals(cleanTitle, ignoreCase = true)

        var finalArtist = if (!isArtistGeneric) cleanArtist.trim() else ""
        var finalTitle = cleanTitle.trim()

        if (finalArtist.isEmpty() || isArtistGeneric) {
            // Try extracting from Title
            when {
                // "سنا برزنجه به نام نه هاتی" or "سنا برزنجه بنام نه هاتی"
                finalTitle.contains(" به نام ") -> {
                    val parts = finalTitle.split(" به نام ", limit = 2)
                    finalArtist = parts[0].trim()
                    finalTitle = parts[1].trim()
                }
                finalTitle.contains(" بنام ") -> {
                    val parts = finalTitle.split(" بنام ", limit = 2)
                    finalArtist = parts[0].trim()
                    finalTitle = parts[1].trim()
                }
                // "نه هاتی از سنا برزنجه"
                finalTitle.contains(" از ") -> {
                    val parts = finalTitle.split(" از ", limit = 2)
                    finalTitle = parts[0].trim()
                    finalArtist = parts[1].trim()
                }
                // "سنا برزنجه - نه هاتی" or "سنا برزنجه – نه هاتی"
                finalTitle.contains(" - ") || finalTitle.contains(" – ") || finalTitle.contains(" — ") -> {
                    val delimiter = when {
                        finalTitle.contains(" - ") -> " - "
                        finalTitle.contains(" – ") -> " – "
                        else -> " — "
                    }
                    val parts = finalTitle.split(delimiter, limit = 2)
                    finalArtist = parts[0].trim()
                    finalTitle = parts[1].trim()
                }
                // Check if title has 3-5 words like "سنا برزنجه نه هاتی"
                else -> {
                    val words = finalTitle.split("\\s+".toRegex()).filter { it.isNotBlank() }
                    if (words.size in 3..5) {
                        finalArtist = "${words[0]} ${words[1]}".trim()
                        finalTitle = words.drop(2).joinToString(" ").trim()
                    } else if (words.size == 2) {
                        finalArtist = finalTitle
                    }
                }
            }
        }

        // Fallback for artist if still missing or generic
        if (finalArtist.isBlank() || GENERIC_ARTISTS.contains(finalArtist.trim().lowercase())) {
            finalArtist = defaultLanguageLabel
        }

        if (finalTitle.isBlank()) {
            finalTitle = cleanTitle.ifBlank { "آهنگ" }
        }

        val displayTitle = if (finalArtist != defaultLanguageLabel && !finalTitle.contains(finalArtist)) {
            "$finalArtist - $finalTitle"
        } else {
            finalTitle
        }

        return FormattedMetadata(
            title = finalTitle,
            artist = finalArtist,
            displayTitle = displayTitle,
            displayArtist = finalArtist,
            displayAlbum = sourceLabel
        )
    }

    private fun cleanNoise(input: String): String {
        var result = input
        for (pattern in NOISE_PATTERNS) {
            result = pattern.replace(result, " ")
        }
        // Remove brackets with quality info e.g. (320), [128], (MP3)
        result = result.replace(Regex("[\\[\\(][^\\]\\)]*?(?:320|128|کیفیت|ریمیکس|mp3|اصلی|کامل)[^\\]\\)]*?[\\]\\)]", RegexOption.IGNORE_CASE), " ")
        // Remove trailing/leading punctuation
        result = result.replace(Regex("^[-–—:،, ]+"), "")
        result = result.replace(Regex("[-–—:،, ]+$"), "")
        // Normalize multiple spaces
        result = result.replace(Regex("\\s+"), " ")
        return result.trim()
    }
}

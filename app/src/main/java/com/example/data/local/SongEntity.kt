package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey
    val id: Long, // Numeric WordPress post ID extracted via regex (e.g. 28444)
    val title: String,
    val artist: String,
    val coverUrl: String?,
    val streamUrl: String, // Extracted strictly from audio[src] or audio source[src]
    val publishDate: Long, // Unix timestamp for chronological sorting
    val tags: String, // Comma-separated list e.g. "شاد, غمگین, هلپرکی"
    val isFavorite: Boolean = false,
    val isAvailable: Boolean = true,
    val downloadedUri: String? = null, // Local MediaStore Content URI if favorited and downloaded
    val language: String = "کوردی", // "کوردی" or "فارسی"
    val sourceNumber: Int = 1 // 1: موزیک کردی, 2: هورامی, 3: گیتار موزیک
)

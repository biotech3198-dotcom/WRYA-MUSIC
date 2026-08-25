package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.SongEntity
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.HeartRed
import com.example.ui.theme.OfflineGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

private data class SongSourceBadge(val label: String, val bg: Color, val border: Color, val textColor: Color)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SongItem(
    song: SongEntity,
    rank: Int,
    isPlaying: Boolean,
    isCurrentSong: Boolean,
    onSongClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onSongClick)
            .testTag("song_item_${song.id}"),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentSong) DarkSurfaceVariant.copy(alpha = 0.9f) else DarkSurface.copy(alpha = 0.8f)
        ),
        border = BorderStroke(
            1.dp,
            if (isCurrentSong) ElectricCyan.copy(alpha = 0.5f) else GlassBorder
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Album Cover / Placeholder with ambient shadow styling
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (!song.coverUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = "Cover for ${song.title}",
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(28.dp)
                    )
                }

                // Overlay playing equalizer animation badge
                if (isCurrentSong) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.GraphicEq else Icons.Default.PlayArrow,
                            contentDescription = "Now Playing",
                            tint = ElectricCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Song Info (Title, Artist, Tags)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "${song.title} (#$rank)",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrentSong) ElectricCyan else TextPrimary,
                            fontSize = 15.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // Downloaded / Offline badge
                    if (!song.downloadedUri.isNullOrEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = OfflineGreen.copy(alpha = 0.2f),
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DownloadDone,
                                contentDescription = "Offline Available",
                                tint = OfflineGreen,
                                modifier = Modifier
                                    .padding(3.dp)
                                    .size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = song.artist,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextSecondary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    // Source Origin Badge (سورس ۱, سورس ۲, سورس ۳)
                    val (sourceLabel, badgeBg, badgeBorder, badgeTextColor) = when (song.sourceNumber) {
                        1 -> SongSourceBadge("سورس ۱", Color(0xFF0284C7).copy(alpha = 0.2f), Color(0xFF0284C7).copy(alpha = 0.6f), Color(0xFF38BDF8))
                        2 -> SongSourceBadge("سورس ۲", Color(0xFF059669).copy(alpha = 0.2f), Color(0xFF059669).copy(alpha = 0.6f), Color(0xFF34D399))
                        3 -> SongSourceBadge("سورس ۳", Color(0xFFD97706).copy(alpha = 0.2f), Color(0xFFD97706).copy(alpha = 0.6f), Color(0xFFFBBF24))
                        else -> SongSourceBadge("سورس ${song.sourceNumber}", Color(0xFF475569).copy(alpha = 0.2f), Color(0xFF475569).copy(alpha = 0.6f), Color(0xFF94A3B8))
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeBg,
                        border = BorderStroke(0.5.dp, badgeBorder),
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = badgeTextColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (song.tags.isNotBlank()) {
                        song.tags.split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .take(2)
                            .forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = DarkSurfaceVariant,
                                    border = BorderStroke(0.5.dp, GlassBorder),
                                    modifier = Modifier.padding(vertical = 1.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = ElectricCyan.copy(alpha = 0.9f),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium
                                        ),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Favorite Button (Instant download / remove toggle)
            IconButton(
                onClick = onFavoriteClick,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("favorite_button_${song.id}")
            ) {
                Icon(
                    imageVector = if (song.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (song.isFavorite) "Remove from Favorites" else "Add to Favorites",
                    tint = if (song.isFavorite) HeartRed else TextSecondary.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}


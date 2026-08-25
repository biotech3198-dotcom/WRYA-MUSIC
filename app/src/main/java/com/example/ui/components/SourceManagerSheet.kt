package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.SourceState
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GoldenAmber
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagerSheet(
    viewModel: MainViewModel,
    kordiSources: List<SourceState>,
    farsiSources: List<SourceState>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF0F172A),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "مدیریت منابع همگام‌سازی",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    )
                    Text(
                        text = "Music Sync Sources",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    )
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = DarkSurfaceVariant,
                    border = BorderStroke(0.5.dp, GlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val totalSongs = (kordiSources + farsiSources).sumOf { it.songCount }
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "$totalSongs آهنگ کل",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Kurdish Sources Section
            Text(
                text = "منابع موسیقی کوردی (Kurdish Sources)",
                color = ElectricCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            kordiSources.forEach { source ->
                SourceCard(source = source, viewModel = viewModel)
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 14.dp),
                color = GlassBorder
            )

            // Persian Sources Section
            Text(
                text = "منابع موسیقی فارسی (Persian Sources)",
                color = GoldenAmber,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            farsiSources.forEach { source ->
                SourceCard(source = source, viewModel = viewModel)
            }
            
            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun SourceCard(source: SourceState, viewModel: MainViewModel) {
    val (badgeText, badgeBg, badgeBorder, badgeTextColor) = when (source.sourceNumber) {
        1 -> SheetSourceBadge("سورس ۱", Color(0xFF0284C7).copy(alpha = 0.2f), Color(0xFF0284C7).copy(alpha = 0.7f), Color(0xFF38BDF8))
        2 -> SheetSourceBadge("سورس ۲", Color(0xFF059669).copy(alpha = 0.2f), Color(0xFF059669).copy(alpha = 0.7f), Color(0xFF34D399))
        3 -> SheetSourceBadge("سورس ۳", Color(0xFFD97706).copy(alpha = 0.2f), Color(0xFFD97706).copy(alpha = 0.7f), Color(0xFFFBBF24))
        else -> SheetSourceBadge("سورس ${source.sourceNumber}", Color(0xFF475569).copy(alpha = 0.2f), Color(0xFF475569).copy(alpha = 0.7f), Color(0xFF94A3B8))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        border = BorderStroke(1.dp, GlassBorder),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Title and Badges Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Source Number Badge (سورس ۱ / ۲ / ۳)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = badgeBg,
                        border = BorderStroke(1.dp, badgeBorder)
                    ) {
                        Text(
                            text = badgeText,
                            color = badgeTextColor,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = source.title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Diagnostics Icon Button
                IconButton(
                    onClick = { viewModel.runDiagnostics(source.url) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TravelExplore,
                        contentDescription = "Diagnose Source",
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL Row
            Text(
                text = source.url,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Fetched Songs Counter Badge (تعداد آهنگ‌های استخراج شده)
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DarkSurfaceVariant.copy(alpha = 0.7f),
                border = BorderStroke(0.5.dp, GlassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LibraryMusic,
                            contentDescription = null,
                            tint = badgeTextColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تعداد آهنگ‌های استخراج شده (FETCH):",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }

                    Surface(
                        shape = CircleShape,
                        color = badgeBg,
                        border = BorderStroke(0.5.dp, badgeBorder)
                    ) {
                        Text(
                            text = "${source.songCount} آهنگ",
                            color = badgeTextColor,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row (Sync / Resume / Stop / Rescan All)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status Text
                Text(
                    text = source.statusText,
                    color = if (source.isSyncing) ElectricCyan else GoldenAmber,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (source.lastCompletedPage > 0 && !source.isSyncing) {
                        OutlinedButton(
                            onClick = { viewModel.triggerSyncForSource(source, fromStart = true) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                            border = BorderStroke(0.5.dp, GlassBorder),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Rescan", modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("اسکن مجدد", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    if (source.isSyncing) {
                        Button(
                            onClick = { viewModel.stopSyncForSource(source) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCF6679)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("توقف", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Button(
                            onClick = { viewModel.triggerSyncForSource(source, fromStart = false) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF003549)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = "Sync", modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (source.lastCompletedPage > 0) "ادامه همگام‌سازی" else "شروع همگام‌سازی",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class SheetSourceBadge(val first: String, val second: Color, val third: Color, val fourth: Color)

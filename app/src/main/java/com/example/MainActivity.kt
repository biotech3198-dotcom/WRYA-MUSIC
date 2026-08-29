package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.io.File
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.MainViewModel
import com.example.ui.PlaybackUiState
import com.example.ui.SyncUiState
import com.example.ui.components.FullPlayerSheet
import com.example.ui.components.SongItem
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.GoldenAmber
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val selectedFilter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val kordiSources by viewModel.kordiSources.collectAsStateWithLifecycle()
    val farsiSources by viewModel.farsiSources.collectAsStateWithLifecycle()
    val showSourceManager by viewModel.showSourceManager.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val diagnosticsState by viewModel.diagnosticsState.collectAsStateWithLifecycle()
    val selectedLanguage by viewModel.selectedLanguage.collectAsStateWithLifecycle()

    var showFullPlayer by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showAutoDiagnosticsDialog by remember { mutableStateOf(false) }
    var crashLogContent by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    // Check for previous crash log
    LaunchedEffect(Unit) {
        try {
            val crashFile = File(context.filesDir, WryaMusicApplication.CRASH_LOG_FILE)
            if (crashFile.exists()) {
                val text = crashFile.readText()
                if (text.isNotBlank()) {
                    crashLogContent = text
                }
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    // Notification Permission for Android 13+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* result handled */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val playButtonSize = maxWidth * (1f / 6f)

        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(DarkBackground),
            topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = { showAboutDialog = true },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("about_menu_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "About Wrya Music",
                                tint = TextPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(ElectricCyan.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "WRYA MUSIC",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.showSourceManager.value = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF003549)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.testTag("source_manager_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = "Sync Sources",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "Sync Sources",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(2.dp))

                    // Android Auto Diagnostics & Telemetry Button
                    IconButton(
                        onClick = { showAutoDiagnosticsDialog = true },
                        modifier = Modifier.size(36.dp).testTag("auto_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = "Android Auto Diagnostics",
                            tint = ElectricCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    // Site Diagnostics Button
                    IconButton(
                        onClick = { viewModel.runDiagnostics() },
                        enabled = !diagnosticsState.isRunning,
                        modifier = Modifier.size(36.dp).testTag("diagnostics_button")
                    ) {
                        if (diagnosticsState.isRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = GoldenAmber,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.TravelExplore,
                                contentDescription = "Site HTML Diagnostics",
                                tint = GoldenAmber,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkBackground
                )
            )
        },
        floatingActionButton = {
            val songToPlay = playbackState.currentSong ?: songs.firstOrNull()?.song
            if (songToPlay != null) {
                Surface(
                    onClick = {
                        try {
                            if (playbackState.currentSong == null) {
                                viewModel.playSong(songToPlay, songs.map { it.song })
                            } else {
                                viewModel.togglePlayPause()
                            }
                            showFullPlayer = true
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Error handling play click: ${e.message}", e)
                        }
                    },
                    modifier = Modifier
                        .size(playButtonSize)
                        .padding(bottom = 6.dp)
                        .testTag("floating_play_square_button"),
                    shape = RoundedCornerShape(16.dp),
                    color = ElectricCyan.copy(alpha = 0.22f),
                    border = BorderStroke(1.5.dp, ElectricCyan.copy(alpha = 0.70f)),
                    shadowElevation = 10.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        ElectricCyan.copy(alpha = 0.35f),
                                        Color(0xFF0284C7).copy(alpha = 0.20f)
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            // Play triangle
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                tint = if (playbackState.isPlaying) TextSecondary else ElectricCyan,
                                modifier = Modifier.size(playButtonSize * 0.44f)
                            )
                            Spacer(modifier = Modifier.width(1.dp))
                            // Pause double bar
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause",
                                tint = if (playbackState.isPlaying) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(playButtonSize * 0.40f)
                            )
                        }
                    }
                }
            }
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Language Tabs (Kurdish / Persian)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val languages = listOf("Kurdish", "Persian")
                languages.forEach { language ->
                    val isSelected = selectedLanguage == language || (selectedLanguage == "کوردی" && language == "Kurdish") || (selectedLanguage == "فارسی" && language == "Persian")
                    Button(
                        onClick = { viewModel.setLanguage(language) },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) ElectricCyan else DarkSurface,
                            contentColor = if (isSelected) Color(0xFF003549) else TextSecondary
                        ),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(
                            text = language,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            fontSize = 15.sp
                        )
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search songs, artists, tags...", color = TextSecondary, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = TextSecondary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear Search",
                                tint = TextSecondary
                            )
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .testTag("search_bar"),
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = DarkSurface,
                    unfocusedContainerColor = DarkSurface,
                    focusedBorderColor = ElectricCyan,
                    unfocusedBorderColor = Color(0xFF1E283D),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            // Filter Chips Row (All in 1 Row without horizontal scroll)
            val filterOptions = listOf("All", "Favorite", "Calm", "New", "Old", "Upbeat")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                filterOptions.forEach { filter ->
                    val isSelected = selectedFilter == filter ||
                            (selectedFilter == "Favorites" && filter == "Favorite") ||
                            (selectedFilter == "Newest" && filter == "New") ||
                            (selectedFilter == "Oldest" && filter == "Old") ||
                            (selectedFilter == "همه" && filter == "All") ||
                            (selectedFilter == "جدیدترین" && filter == "New") ||
                            (selectedFilter == "شاد" && filter == "Upbeat") ||
                            (selectedFilter == "غمگین" && filter == "Calm") ||
                            (selectedFilter == "علاقه‌مندی‌ها" && filter == "Favorite") ||
                            (selectedFilter == "قدیمی‌ترین" && filter == "Old")

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { viewModel.setFilter(filter) }
                            .testTag("filter_chip_$filter"),
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) ElectricCyan else DarkSurface,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) ElectricCyan else Color(0xFF1E283D)
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                    fontSize = 11.sp,
                                    color = if (isSelected) Color(0xFF003549) else TextSecondary
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Header info with song count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = when (selectedFilter) {
                        "Favorite", "Favorites", "علاقه‌مندی‌ها" -> "Favorite Tracks (${songs.size})"
                        "Upbeat", "شاد" -> "Upbeat & Energetic (${songs.size})"
                        "Calm", "غمگین" -> "Calm & Relaxing (${songs.size})"
                        "New", "Newest", "جدیدترین" -> "Latest Releases (${songs.size})"
                        "Old", "Oldest", "قدیمی‌ترین" -> "Classic Library (${songs.size})"
                        else -> "Library Tracks (${songs.size})"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                )

                if (songs.isNotEmpty()) {
                    Text(
                        text = "Ready for Offline Play",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = ElectricCyan.copy(alpha = 0.85f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            // Songs List
            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(DarkSurface),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MusicNote,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = if (searchQuery.isNotEmpty()) "No songs found matching query" else "Your music library is currently empty",
                            style = MaterialTheme.typography.titleMedium.copy(color = TextPrimary)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Tap the sync button below to automatically fetch songs from online sources.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.showSourceManager.value = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color(0xFF003549)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.CloudSync, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Music Sources", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 4.dp,
                        bottom = if (playbackState.currentSong != null) 90.dp else 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = songs,
                        key = { it.song.id }
                    ) { songItem ->
                        val isCurrent = playbackState.currentSong?.id == songItem.song.id
                        SongItem(
                            song = songItem.song,
                            rank = songItem.rank,
                            isPlaying = playbackState.isPlaying && isCurrent,
                            isCurrentSong = isCurrent,
                            onSongClick = { viewModel.playSong(songItem.song, songs.map { it.song }) },
                            onFavoriteClick = { viewModel.toggleFavorite(songItem.song) }
                        )
                    }
                }
            }
        }
    }

    // Modal Full Player Sheet
    val activeSong = playbackState.currentSong ?: songs.firstOrNull()?.song
    if (showFullPlayer && activeSong != null) {
        val effectiveState = if (playbackState.currentSong != null) playbackState else playbackState.copy(currentSong = activeSong)
        FullPlayerSheet(
            state = effectiveState,
            onDismiss = { showFullPlayer = false },
            onTogglePlayPause = { viewModel.togglePlayPause() },
            onNext = { viewModel.skipToNext() },
            onPrevious = { viewModel.skipToPrevious() },
            onSeek = { viewModel.seekTo(it) },
            onToggleShuffle = { viewModel.toggleShuffle() },
            onCustomShuffle = { includeKordi, includeFarsi -> viewModel.applyCustomShuffle(includeKordi, includeFarsi) },
            onToggleRepeat = { viewModel.toggleRepeat() },
            onToggleFavorite = { viewModel.toggleFavorite(activeSong) },
            onPlayQueueIndex = { viewModel.playQueueItem(it) },
            onMoveQueueItem = { from, to -> viewModel.moveQueueItem(from, to) },
            onRemoveFromQueue = { viewModel.removeFromQueue(it) }
        )
    }

    // About / Tribute Dialog
    if (showAboutDialog) {
        Dialog(onDismissRequest = { showAboutDialog = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp)),
                color = DarkSurface,
                border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Glowing Music Icon
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(ElectricCyan.copy(alpha = 0.35f), Color(0xFF0F172A))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Text 1: Dedication
                    Text(
                        text = "پێشکەش بە دڵخوازانی مۆسیقای کوردستان",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Text 2: Creator
                    Text(
                        text = "دروستکراو لە لایەن وریا",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = ElectricCyan,
                            fontSize = 14.sp
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Text 3: Feedback note
                    Text(
                        text = "بۆ پێشنیارەکانتان لە ئایدی تێلێگرام کۆمێنت دابنێن:",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        ),
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Telegram ID Pill Button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/Hi_zrebar"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    clipboardManager.setText(AnnotatedString("@Hi_zrebar"))
                                    Toast.makeText(context, "ئایدی کۆپی کرا: @Hi_zrebar", Toast.LENGTH_SHORT).show()
                                }
                            },
                        color = Color(0xFF0284C7).copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Telegram",
                                tint = ElectricCyan,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "@Hi_zrebar",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = ElectricCyan,
                                    fontSize = 14.sp
                                )
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy ID",
                                tint = TextSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Close Button
                    Button(
                        onClick = { showAboutDialog = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF003549)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(0.65f)
                    ) {
                        Text(
                            text = "داخستن",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    // Crash Log Dialog (if previous run crashed)
    if (crashLogContent != null) {
        AlertDialog(
            onDismissRequest = {
                try {
                    File(context.filesDir, WryaMusicApplication.CRASH_LOG_FILE).delete()
                } catch (e: Exception) { }
                crashLogContent = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Crash Report",
                    tint = GoldenAmber,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Application Crash Log",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    Text(
                        text = "The application stopped during a previous run. You can copy the crash details below:",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = crashLogContent ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        crashLogContent?.let {
                            clipboardManager.setText(AnnotatedString(it))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color(0xFF003549))
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Crash Log")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        try {
                            File(context.filesDir, WryaMusicApplication.CRASH_LOG_FILE).delete()
                        } catch (e: Exception) { }
                        crashLogContent = null
                    }
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Dismiss & Delete Log", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Diagnostics Dialog for HTML Structure Inspection
    if (diagnosticsState.report != null || diagnosticsState.errorMessage != null) {
        val report = diagnosticsState.report
        AlertDialog(
            onDismissRequest = { viewModel.clearDiagnostics() },
            icon = {
                Icon(
                    imageVector = Icons.Default.TravelExplore,
                    contentDescription = "Diagnostics",
                    tint = ElectricCyan,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    text = "Site HTML Diagnostics",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    if (diagnosticsState.errorMessage != null) {
                        Text(
                            text = diagnosticsState.errorMessage ?: "",
                            style = MaterialTheme.typography.bodyMedium.copy(color = Color(0xFFFF6B6B)),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    } else if (report != null) {
                        // Quick Check Indicators
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (report.containsMp3) Color(0xFF1B4332) else Color(0xFF491212)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = ".mp3 Links",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = if (report.containsMp3) "Found" else "None (0)",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (report.containsMp3) Color(0xFF52B788) else Color(0xFFFF6B6B)
                                    )
                                }
                            }

                            Card(
                                modifier = Modifier.weight(1f),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (report.containsAudioTag) Color(0xFF1B4332) else Color(0xFF333333)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "<audio> Tag",
                                        fontSize = 11.sp,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = if (report.containsAudioTag) "Yes" else "No",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (report.containsAudioTag) Color(0xFF52B788) else TextSecondary
                                    )
                                }
                            }
                        }

                        // Summary info
                        Text(
                            text = "Status: ${report.statusCode} | Title: ${report.title.take(35)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )

                        if (report.containsProtectionWarning) {
                            Text(
                                text = "⚠️ Warning: Potential security challenge (Cloudflare/Captcha) detected.",
                                style = MaterialTheme.typography.bodySmall.copy(color = GoldenAmber),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .background(DarkSurfaceVariant, RoundedCornerShape(8.dp))
                                .padding(8.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = report.sampleSnippet,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TextPrimary
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val textToCopy = report?.fullRawHtml ?: diagnosticsState.errorMessage ?: ""
                        if (textToCopy.isNotBlank()) {
                            clipboardManager.setText(AnnotatedString(textToCopy))
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ElectricCyan, contentColor = Color(0xFF003549))
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Report & HTML")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.clearDiagnostics() }
                ) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = DarkSurface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showSourceManager) {
        com.example.ui.components.SourceManagerSheet(
            viewModel = viewModel,
            kordiSources = kordiSources,
            farsiSources = farsiSources,
            onDismiss = { viewModel.showSourceManager.value = false }
        )
    }

    if (showAutoDiagnosticsDialog) {
        com.example.ui.components.AutoDiagnosticsDialog(
            onDismiss = { showAutoDiagnosticsDialog = false }
        )
    }
    }
}

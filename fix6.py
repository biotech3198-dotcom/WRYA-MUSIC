import re

with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "r") as f:
    text = f.read()

# Fix the duplicate block issue
# We have ` CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {` inserted.
# The original structure was:
# @Composable fun FullPlayerSheet(...) { Box(...) { Column(...) { ... Progress Slider ... Media Buttons } } }

# We can replace everything from CompositionLocalProvider down to the end of the file.
match = re.search(r'CompositionLocalProvider', text)
if match:
    start_idx = match.start()
    clean_text = text[:start_idx] + """
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Progress Slider and Time Labels
                    var isDragging by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    var dragPosition by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

                    val currentPos = if (isDragging) dragPosition.toLong() else state.currentPositionMs
                    val duration = state.durationMs.coerceAtLeast(1L)
                    val sliderValue = (currentPos.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

                    androidx.compose.material3.Slider(
                        value = sliderValue,
                        onValueChange = { fraction ->
                            isDragging = true
                            dragPosition = fraction * duration
                        },
                        onValueChangeFinished = {
                            isDragging = false
                            onSeek(dragPosition.toLong())
                        },
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = ElectricCyan,
                            activeTrackColor = ElectricCyan,
                            inactiveTrackColor = Color(0xFF1E283D)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatTimeMs(currentPos),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                        Text(
                            text = formatTimeMs(state.durationMs),
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Media Control Buttons (Shuffle, Previous, Play/Pause, Next, Repeat)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Shuffle Button
                        androidx.compose.material3.IconButton(
                            onClick = onToggleShuffle,
                            modifier = Modifier
                                .size(48.dp)
                                .androidx.compose.ui.platform.testTag("full_player_shuffle")
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "پخش تصادفی",
                                tint = if (state.isShuffle) ElectricCyan else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Previous Button (On the left in LTR)
                        androidx.compose.material3.IconButton(
                            onClick = onPrevious,
                            modifier = Modifier
                                .size(54.dp)
                                .androidx.compose.ui.platform.testTag("full_player_prev")
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "قبلی",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Main Play/Pause Button
                        androidx.compose.material3.FilledIconButton(
                            onClick = onPlayPause,
                            modifier = Modifier
                                .size(72.dp)
                                .androidx.compose.ui.platform.testTag("full_player_main_play_pause"),
                            colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF003549)
                            ),
                            shape = CircleShape
                        ) {
                            if (state.isBuffering) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = Color(0xFF003549),
                                    strokeWidth = 3.dp
                                )
                            } else {
                                androidx.compose.material3.Icon(
                                    imageVector = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (state.isPlaying) "توقف" else "پخش",
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        // Next Button (On the right in LTR)
                        androidx.compose.material3.IconButton(
                            onClick = onNext,
                            modifier = Modifier
                                .size(54.dp)
                                .androidx.compose.ui.platform.testTag("full_player_next")
                        ) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "بعدی",
                                tint = TextPrimary,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // Repeat Mode Button
                        androidx.compose.material3.IconButton(
                            onClick = onToggleRepeat,
                            modifier = Modifier
                                .size(48.dp)
                                .androidx.compose.ui.platform.testTag("full_player_repeat")
                        ) {
                            val (icon, tint) = when (state.repeatMode) {
                                androidx.media3.common.Player.REPEAT_MODE_ALL -> Icons.Default.Repeat to ElectricCyan
                                androidx.media3.common.Player.REPEAT_MODE_ONE -> Icons.Default.RepeatOne to ElectricCyan
                                else -> Icons.Default.Repeat to TextSecondary
                            }
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = "حالت تکرار",
                                tint = tint,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimeMs(millis: Long): String {
    if (millis <= 0) return "00:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(java.util.Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
"""
    with open("/app/applet/app/src/main/java/com/example/ui/components/FullPlayerSheet.kt", "w") as f:
        f.write(clean_text)

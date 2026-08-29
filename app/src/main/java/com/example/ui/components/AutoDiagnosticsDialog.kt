package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.theme.DarkBackground
import com.example.ui.theme.DarkSurface
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.GoldenAmber
import com.example.ui.theme.HeartRed
import com.example.ui.theme.OfflineGreen
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.util.AutoDiagnosticsLogger
import com.example.util.AutoLogCategory
import com.example.util.AutoLogEntry
import com.example.util.SelfTestReport
import kotlinx.coroutines.launch

@Composable
fun AutoDiagnosticsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var logEntries by remember { mutableStateOf(AutoDiagnosticsLogger.getEntries()) }
    var selfTestReport by remember { mutableStateOf<SelfTestReport?>(null) }
    var isRunningSelfTest by remember { mutableStateOf(false) }
    var selectedCategoryFilter by remember { mutableStateOf<AutoLogCategory?>(null) }

    fun refreshLogs() {
        logEntries = AutoDiagnosticsLogger.getEntries()
    }

    val errorCount = logEntries.count { it.category == AutoLogCategory.ERROR }
    val connectCount = logEntries.count { it.category == AutoLogCategory.CONNECT }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("auto_diagnostics_dialog"),
            color = DarkSurface,
            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(ElectricCyan.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DirectionsCar,
                                contentDescription = null,
                                tint = ElectricCyan,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Android Auto Diagnostics",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 16.sp
                                )
                            )
                            Text(
                                text = "Live telemetry, logs & health verification",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp).testTag("close_auto_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Metric Badges & Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Total Events Chip
                    Surface(
                        color = DarkSurfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Events:", color = TextSecondary, fontSize = 11.sp)
                            Text("${logEntries.size}", fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 12.sp)
                        }
                    }

                    // Connects Chip
                    Surface(
                        color = if (connectCount > 0) Color(0xFF0F392B) else DarkSurfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Car Connects:", color = TextSecondary, fontSize = 11.sp)
                            Text("$connectCount", fontWeight = FontWeight.Bold, color = if (connectCount > 0) OfflineGreen else TextPrimary, fontSize = 12.sp)
                        }
                    }

                    // Errors Chip
                    Surface(
                        color = if (errorCount > 0) Color(0xFF4A151B) else DarkSurfaceVariant,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("Errors:", color = TextSecondary, fontSize = 11.sp)
                            Text("$errorCount", fontWeight = FontWeight.Bold, color = if (errorCount > 0) HeartRed else OfflineGreen, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Main Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Run Self Test Button
                    Button(
                        onClick = {
                            if (!isRunningSelfTest) {
                                isRunningSelfTest = true
                                scope.launch {
                                    val report = AutoDiagnosticsLogger.runSelfTest(context)
                                    selfTestReport = report
                                    isRunningSelfTest = false
                                    refreshLogs()
                                }
                            }
                        },
                        enabled = !isRunningSelfTest,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF003549)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("run_self_test_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        if (isRunningSelfTest) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF003549),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Testing...", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Run Self-Test", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Copy Log Button
                    Button(
                        onClick = {
                            val text = AutoDiagnosticsLogger.getFormattedLogs(context)
                            clipboardManager.setText(AnnotatedString(text))
                            Toast.makeText(context, "All Android Auto logs copied to clipboard!", Toast.LENGTH_LONG).show()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1E283D),
                            contentColor = ElectricCyan
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp)
                            .testTag("copy_auto_logs_button"),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Full Log", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Refresh Button
                    IconButton(
                        onClick = { refreshLogs() },
                        modifier = Modifier
                            .size(40.dp)
                            .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                            .testTag("refresh_auto_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Clear Button
                    IconButton(
                        onClick = {
                            AutoDiagnosticsLogger.clearLogs(context)
                            selfTestReport = null
                            refreshLogs()
                            Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .size(40.dp)
                            .background(DarkSurfaceVariant, RoundedCornerShape(12.dp))
                            .testTag("clear_auto_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear",
                            tint = HeartRed,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Self-Test Report View (if present)
                selfTestReport?.let { report ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (report.overallPassed) Color(0xFF0F2B1F) else Color(0xFF3B1417)
                        ),
                        border = BorderStroke(1.dp, if (report.overallPassed) OfflineGreen.copy(alpha = 0.6f) else HeartRed.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (report.overallPassed) Icons.Default.CheckCircle else Icons.Default.Error,
                                        contentDescription = null,
                                        tint = if (report.overallPassed) OfflineGreen else HeartRed,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = if (report.overallPassed) "Automotive Self-Test: ALL PASSED" else "Automotive Self-Test: ISSUES DETECTED",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = if (report.overallPassed) OfflineGreen else HeartRed
                                    )
                                }
                                Text(
                                    text = report.timestamp.takeLast(8),
                                    fontSize = 10.sp,
                                    color = TextSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            report.steps.forEach { step ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = if (step.isSuccess) "✓" else "✕",
                                        color = if (step.isSuccess) OfflineGreen else HeartRed,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.width(16.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${step.stepName}: ${step.message}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        if (!step.details.isNullOrBlank()) {
                                            Text(
                                                text = step.details,
                                                fontSize = 10.sp,
                                                color = TextSecondary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Log List Console Container
                Text(
                    text = "LIVE AUTOMOTIVE TELEMETRY STREAM:",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextTertiary,
                    modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF070A0F))
                        .border(BorderStroke(1.dp, Color(0xFF1E283D)), RoundedCornerShape(12.dp))
                        .padding(8.dp)
                ) {
                    val filteredList = if (selectedCategoryFilter != null) {
                        logEntries.filter { it.category == selectedCategoryFilter }
                    } else {
                        logEntries
                    }

                    if (filteredList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = TextTertiary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "No automotive events recorded yet.\nConnect phone to car or tap 'Run Self-Test'.",
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    color = TextSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    } else {
                        val listState = rememberLazyListState()
                        LaunchedEffect(filteredList.size) {
                            if (filteredList.isNotEmpty()) {
                                listState.animateScrollToItem(filteredList.size - 1)
                            }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredList) { entry ->
                                LogEntryItem(entry = entry)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom Quick Tip
                Surface(
                    color = DarkSurfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = GoldenAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Tip: In Android Auto phone settings, enable 'Developer Settings' and check 'Unknown Sources' so sideloaded media apps appear on car screen.",
                            fontSize = 10.sp,
                            color = TextSecondary,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryItem(entry: AutoLogEntry) {
    val categoryColor = when (entry.category) {
        AutoLogCategory.CONNECT -> ElectricCyan
        AutoLogCategory.SESSION -> Color(0xFF38BDF8)
        AutoLogCategory.COMMAND -> GoldenAmber
        AutoLogCategory.ROOT, AutoLogCategory.CHILDREN, AutoLogCategory.ITEM -> Color(0xFFA78BFA)
        AutoLogCategory.RESUME, AutoLogCategory.PLAYBACK -> OfflineGreen
        AutoLogCategory.ERROR -> HeartRed
        AutoLogCategory.WARNING -> GoldenAmber
        AutoLogCategory.SELF_TEST -> Color(0xFFF472B6)
        else -> TextSecondary
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D131E), RoundedCornerShape(6.dp))
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.timestamp,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextTertiary
                )
                Surface(
                    color = categoryColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${entry.category.icon} ${entry.category.label}",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = categoryColor,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
                if (!entry.caller.isNullOrBlank()) {
                    Text(
                        text = entry.caller.takeLast(20),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = TextTertiary
                    )
                }
            }

            if (entry.durationMs != null) {
                Text(
                    text = "${entry.durationMs}ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = GoldenAmber
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = entry.message,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextPrimary,
            lineHeight = 15.sp
        )

        if (!entry.details.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "↳ ${entry.details}",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (entry.category == AutoLogCategory.ERROR) HeartRed.copy(alpha = 0.9f) else TextSecondary,
                lineHeight = 13.sp
            )
        }
    }
}

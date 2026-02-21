package com.tuner.cdituner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class CdiLogEntry(
    val timestamp: String,
    val data: CdiData?
)

@Composable
fun TerminalView(cdiData: CdiData?, modifier: Modifier = Modifier) {
    // Keep a history of CDI data entries
    var logEntries by remember { mutableStateOf(listOf<CdiLogEntry>()) }
    val listState = rememberLazyListState()
    
    // Add new data to the log when it arrives
    LaunchedEffect(cdiData) {
        cdiData?.let {
            val timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            val newEntry = CdiLogEntry(timestamp, cdiData)
            logEntries = logEntries + newEntry
            
            // Keep only last 100 entries to prevent memory issues
            if (logEntries.size > 100) {
                logEntries = logEntries.takeLast(100)
            }
        }
    }
    
    // Auto-scroll to bottom when new data arrives
    LaunchedEffect(logEntries.size) {
        if (logEntries.isNotEmpty()) {
            listState.animateScrollToItem(logEntries.size - 1)
        }
    }
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Time",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(0.15f)
            )
            Text(
                text = "RPM",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(0.15f)
            )
            Text(
                text = "Battery",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(0.2f)
            )
            Text(
                text = "Status",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(0.25f)
            )
            Text(
                text = "Timing",
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.weight(0.25f)
            )
        }
        
        Divider(color = Color.Green, thickness = 1.dp)
        
        // Data rows
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            items(logEntries) { entry ->
                CdiDataRow(entry)
            }
        }
    }
}

@Composable
fun CdiDataRow(entry: CdiLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = entry.timestamp,
            color = Color.Green,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.weight(0.15f)
        )
        
        entry.data?.let { data ->
            Text(
                text = String.format("%4d", data.rpm),
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(0.15f)
            )
            Text(
                text = String.format("%.1fV", data.batteryVoltage),
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(0.2f)
            )
            Text(
                text = String.format("0x%02X (%3d)", data.statusByte, data.statusByte),
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(0.25f)
            )
            Text(
                text = String.format("0x%02X (%3d)", data.timingByte, data.timingByte),
                color = Color.Green,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(0.25f)
            )
        } ?: run {
            Text(
                text = "---",
                color = Color.Red,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier = Modifier.weight(0.85f)
            )
        }
    }
}
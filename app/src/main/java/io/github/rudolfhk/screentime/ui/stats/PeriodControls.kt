package io.github.rudolfhk.screentime.ui.stats

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.core.TimeRange
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.ui.components.Pill
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Day / Week / Month / All / Custom selector plus ‹ period › navigation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeriodControls(
    selection: PeriodSelection,
    range: TimeRange?,
    onSelect: (PeriodSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(RangeKind.DAY to "Day", RangeKind.WEEK to "Week", RangeKind.MONTH to "Month", RangeKind.ALL to "All time", RangeKind.CUSTOM to "Custom")
                .forEach { (kind, label) ->
                    Pill(label, selection.kind == kind) {
                        if (kind == RangeKind.CUSTOM) showPicker = true
                        else onSelect(PeriodSelection(kind, if (selection.kind == RangeKind.ALL) today else selection.anchor))
                    }
                }
        }
        if (range != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val prev = TimeRanges.shift(selection, -1)
                val next = TimeRanges.shift(selection, 1)
                val nextAllowed = next != null && range.end <= System.currentTimeMillis()
                IconButton(onClick = { prev?.let(onSelect) }, enabled = prev != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous period", tint = if (prev != null) Palette.Text else Palette.TextFaint)
                }
                Text(
                    TimeRanges.label(selection, range, zone, today),
                    style = NumberStyle.copy(fontSize = 14.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { next?.let(onSelect) }, enabled = nextAllowed) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next period", tint = if (nextAllowed) Palette.Text else Palette.TextFaint)
                }
            }
        }
    }

    if (showPicker) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = selection.customStart?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = selection.customEnd?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val s = state.selectedStartDateMillis?.let { utcDate(it) }
                        val e = state.selectedEndDateMillis?.let { utcDate(it) } ?: s
                        if (s != null) onSelect(PeriodSelection(RangeKind.CUSTOM, s, s, e))
                        showPicker = false
                    },
                ) { Text("Apply", color = Palette.Accent) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel", color = Palette.TextDim) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.fillMaxWidth(), title = {
                Text("Select range", modifier = Modifier.padding(start = 24.dp, top = 16.dp), color = Palette.TextDim)
            })
        }
    }
}

private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

package io.github.rudolfhk.screentime.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.DatePickerDefaults
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
import io.github.rudolfhk.screentime.ui.components.ControlShape
import io.github.rudolfhk.screentime.ui.components.SegmentedControl
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private val KINDS = listOf(
    RangeKind.DAY to "Day",
    RangeKind.WEEK to "Week",
    RangeKind.MONTH to "Month",
    RangeKind.ALL to "All",
    RangeKind.CUSTOM to "Custom",
)

/** Period kind selector plus a ‹ period › stepper bar. */
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
        SegmentedControl(
            options = KINDS,
            selected = selection.kind,
            onSelect = { kind ->
                if (kind == RangeKind.CUSTOM) showPicker = true
                else onSelect(PeriodSelection(kind, if (selection.kind == RangeKind.ALL || selection.kind == RangeKind.CUSTOM) today else selection.anchor))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        val prev = TimeRanges.shift(selection, -1)
        val next = TimeRanges.shift(selection, 1)
        val nextAllowed = next != null && range != null && range.end <= System.currentTimeMillis()
        Row(
            Modifier
                .fillMaxWidth()
                .border(1.dp, Palette.Outline, ControlShape)
                .background(Palette.Surface, ControlShape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { prev?.let(onSelect) }, enabled = prev != null) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous period", tint = if (prev != null) Palette.Text else Palette.TextFaint)
            }
            Text(
                range?.let { TimeRanges.label(selection, it, zone, today) } ?: "…",
                style = NumberStyle.copy(fontSize = 13.sp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { showPicker = true }) {
                Icon(Icons.Filled.DateRange, "Pick custom range", tint = Palette.TextDim)
            }
            IconButton(onClick = { next?.let(onSelect) }, enabled = nextAllowed) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next period", tint = if (nextAllowed) Palette.Text else Palette.TextFaint)
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
                ) { Text("APPLY", color = Palette.Accent) }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("CANCEL", color = Palette.TextDim) } },
            shape = ControlShape,
            colors = DatePickerDefaults.colors(containerColor = Palette.Surface),
        ) {
            DateRangePicker(
                state = state,
                modifier = Modifier.fillMaxWidth().height(520.dp),
                title = { Text("Select range", modifier = Modifier.padding(start = 24.dp, top = 16.dp), color = Palette.TextDim) },
                colors = DatePickerDefaults.colors(
                    containerColor = Palette.Surface,
                    selectedDayContainerColor = Palette.Accent,
                    selectedDayContentColor = Palette.Bg,
                    dayInSelectionRangeContainerColor = Palette.AccentDim,
                    todayDateBorderColor = Palette.Accent,
                ),
            )
        }
    }
}

private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

package io.github.rudolfhk.screentime.ui.tracker

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudolfhk.screentime.AppContainer
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.core.TimeRanges
import io.github.rudolfhk.screentime.data.Settings
import io.github.rudolfhk.screentime.data.StatsBundle
import io.github.rudolfhk.screentime.tracking.Permissions
import io.github.rudolfhk.screentime.ui.PermissionState
import io.github.rudolfhk.screentime.ui.components.DayTimeline
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Kpi
import io.github.rudolfhk.screentime.ui.components.KpiGrid
import io.github.rudolfhk.screentime.ui.components.PowerToggle
import io.github.rudolfhk.screentime.ui.components.ProportionBar
import io.github.rudolfhk.screentime.ui.components.SectionCard
import io.github.rudolfhk.screentime.ui.components.StatLine
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.components.UsageRow
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class TrackerViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val today: StateFlow<StatsBundle?> = c.stats
        .stats(PeriodSelection(RangeKind.DAY, LocalDate.now()), null, null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun toggle(enable: Boolean) = viewModelScope.launch {
        _busy.value = true
        try {
            if (enable) c.tracking.enable() else c.tracking.disable()
        } finally {
            _busy.value = false
        }
    }

    fun sync() = viewModelScope.launch {
        _busy.value = true
        try { c.tracking.sync() } finally { _busy.value = false }
    }
}

@Composable
fun TrackerScreen(permissions: PermissionState, onOpenApp: (String) -> Unit) {
    val context = LocalContext.current
    val vm: TrackerViewModel = viewModel { TrackerViewModel(context.container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s = settings ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SCREENTIME", style = LabelStyle.copy(color = Palette.Accent, letterSpacing = 3.sp))
                Text("Diagnostics", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            IconButton(onClick = { vm.sync() }, enabled = s.trackingEnabled && !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Sync now", tint = Palette.TextDim)
            }
        }

        if (!permissions.usageAccess) {
            SectionCard("Permission needed") {
                Text(
                    "To read screen, unlock and app events this app needs Usage access. " +
                        "Android keeps this log anyway – the app only copies it into a private database on this phone.",
                    fontSize = 14.sp, color = Palette.TextDim,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { Permissions.openUsageAccessSettings(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Bg),
                ) { Text("Grant usage access") }
            }
        }

        // Toggle
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PowerToggle(
                enabled = s.trackingEnabled,
                busy = busy || !permissions.usageAccess,
                onToggle = { vm.toggle(!s.trackingEnabled) },
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (s.trackingEnabled) Palette.Good else Palette.TextFaint)
                Spacer(Modifier.width(8.dp))
                Text(
                    if (s.trackingEnabled) "TRACKING ACTIVE" else "TRACKING PAUSED",
                    style = LabelStyle.copy(color = if (s.trackingEnabled) Palette.Text else Palette.TextDim),
                )
            }
            val since = today?.firstTrackedAt
            Text(
                when {
                    s.trackingEnabled && s.lastSyncAt > 0 ->
                        "last sync " + DateUtils.getRelativeTimeSpanString(s.lastSyncAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    s.trackingEnabled -> "syncing…"
                    !s.hasEverTracked -> "Tap to start. Recent history is backfilled automatically."
                    else -> "Data is kept. Tap to resume."
                },
                fontSize = 12.sp, color = Palette.TextFaint, fontFamily = Mono,
            )
            if (since != null) {
                Text(
                    "history since " + DateTimeFormatter.ofPattern("d MMM yyyy").format(TimeRanges.localDate(since, ZoneId.systemDefault())),
                    fontSize = 12.sp, color = Palette.TextFaint, fontFamily = Mono,
                )
            }
        }

        val bundle = today
        if (bundle != null) {
            val st = bundle.stats
            val sum = st.summary
            SectionCard("Today") {
                Text(Format.duration(sum.screenTime), style = NumberStyle.copy(fontSize = 44.sp))
                Text("screen time (unlocked)", fontSize = 12.sp, color = Palette.TextDim)
                val goalMs = s.dailyGoalMinutes * 60_000L
                if (goalMs > 0) {
                    Spacer(Modifier.height(12.dp))
                    val ratio = sum.screenTime.toFloat() / goalMs
                    ProportionBar(ratio, if (ratio > 1f) Palette.Bad else if (ratio > 0.8f) Palette.Warn else Palette.Accent, height = 6.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (ratio <= 1f) "${Format.duration(goalMs - sum.screenTime)} left of ${Format.duration(goalMs)} goal"
                        else "${Format.duration(sum.screenTime - goalMs)} over ${Format.duration(goalMs)} goal",
                        fontSize = 12.sp, color = Palette.TextDim, fontFamily = Mono,
                    )
                }
                Spacer(Modifier.height(16.dp))
                KpiGrid(
                    listOf(
                        { m -> Kpi("Unlocks", sum.unlocks.toString(), m) },
                        { m -> Kpi("Glances", sum.glances.toString(), m, sub = "screen on, no unlock") },
                        { m -> Kpi("Avg session", Format.durationPrecise(sum.avgSession), m) },
                        { m -> Kpi("Notifications", if (s.countNotifications) sum.notifications.toString() else "off", m) },
                    ),
                )
            }

            val dayRange = TimeRanges.dayRange(LocalDate.now(), ZoneId.systemDefault())
            SectionCard("Timeline") {
                val spans = bundle.timeline
                if (spans.isEmpty()) EmptyHint("No sessions yet today.")
                else DayTimeline(spans, dayRange.start, dayRange.end, System.currentTimeMillis())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Palette.Accent); Spacer(Modifier.width(6.dp))
                    Text("unlocked", fontSize = 11.sp, color = Palette.TextDim)
                    Spacer(Modifier.width(14.dp))
                    StatusDot(Palette.TextFaint); Spacer(Modifier.width(6.dp))
                    Text("glance", fontSize = 11.sp, color = Palette.TextDim)
                }
                Spacer(Modifier.height(8.dp))
                st.days.firstOrNull()?.let { d ->
                    StatLine("First unlock", d.firstUseMinute?.let(Format::minuteOfDay) ?: "–")
                    StatLine("Longest break", st.longestBreak?.let { Format.duration(it.duration) } ?: "–", hint = "between unlocked sessions")
                }
            }

            SectionCard("Top apps today") {
                if (st.apps.isEmpty()) EmptyHint("Nothing recorded yet.")
                val top = st.apps.take(5)
                val maxT = top.firstOrNull()?.total ?: 1
                top.forEach { a ->
                    UsageRow(
                        packageName = a.packageName,
                        label = bundle.apps[a.packageName]?.label ?: a.packageName,
                        value = Format.duration(a.total),
                        fraction = a.total.toFloat() / maxT,
                        sub = "${a.launches} launches",
                        onClick = { onOpenApp(a.packageName) },
                    )
                }
            }

            SectionCard("Tracking health") {
                val cov = st.coverage
                StatLine("Coverage today", if (cov.elapsedMs > 0) Format.percent(cov.ratio) else "–", hint = "share of the day tracking was on")
                StatLine("Data gaps", cov.dataGaps.size.toString(), hint = "periods the system log had already expired")
                StatLine("Battery optimisation", if (permissions.batteryUnrestricted) "unrestricted" else "optimised", hint = "optimised is fine – sync just runs a bit less precisely")
                if (!permissions.batteryUnrestricted) {
                    TextButton(onClick = { Permissions.openBatteryOptimizationSettings(context) }) { Text("Open battery settings", color = Palette.Accent) }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, null, tint = Palette.TextFaint, modifier = Modifier.padding(end = 6.dp).height(14.dp))
            Text("Local only · no internet permission", fontSize = 11.sp, color = Palette.TextFaint, fontFamily = Mono)
        }
    }
}

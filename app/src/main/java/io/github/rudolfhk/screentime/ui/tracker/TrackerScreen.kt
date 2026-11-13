package io.github.rudolfhk.screentime.ui.tracker

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
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
import io.github.rudolfhk.screentime.ui.components.AppRankRow
import io.github.rudolfhk.screentime.ui.components.DataRow
import io.github.rudolfhk.screentime.ui.components.DayTimeline
import io.github.rudolfhk.screentime.ui.components.Divider
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Gutter
import io.github.rudolfhk.screentime.ui.components.Legend
import io.github.rudolfhk.screentime.ui.components.MetricGrid
import io.github.rudolfhk.screentime.ui.components.MetricTile
import io.github.rudolfhk.screentime.ui.components.Panel
import io.github.rudolfhk.screentime.ui.components.PrimaryButton
import io.github.rudolfhk.screentime.ui.components.ProportionBar
import io.github.rudolfhk.screentime.ui.components.RecordingIndicator
import io.github.rudolfhk.screentime.ui.components.ScreenHeader
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
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
fun TrackerScreen(permissions: PermissionState, onOpenApp: (String) -> Unit, onSeeAllApps: () -> Unit) {
    val context = LocalContext.current
    val vm: TrackerViewModel = viewModel { TrackerViewModel(context.container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val s = settings ?: return
    TrackerContent(
        settings = s,
        today = today,
        permissions = permissions,
        busy = busy,
        now = System.currentTimeMillis(),
        onToggle = { vm.toggle(!s.trackingEnabled) },
        onSync = { vm.sync() },
        onGrantUsage = { Permissions.openUsageAccessSettings(context) },
        onOpenBattery = { Permissions.openBatteryOptimizationSettings(context) },
        onOpenApp = onOpenApp,
        onSeeAllApps = onSeeAllApps,
    )
}

@Composable
fun TrackerContent(
    settings: Settings,
    today: StatsBundle?,
    permissions: PermissionState,
    busy: Boolean,
    now: Long,
    onToggle: () -> Unit,
    onSync: () -> Unit,
    onGrantUsage: () -> Unit,
    onOpenBattery: () -> Unit,
    onOpenApp: (String) -> Unit,
    onSeeAllApps: () -> Unit,
) {
    val s = settings
    val zone = ZoneId.systemDefault()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Tracker",
            subtitle = DateTimeFormatter.ofPattern("EEEE, d MMMM").format(LocalDate.now(zone)),
            actions = {
                IconButton(onClick = onSync, enabled = s.trackingEnabled && !busy) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sync now", tint = if (s.trackingEnabled && !busy) Palette.Text else Palette.TextFaint)
                }
            },
        )
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!permissions.usageAccess) {
                Panel("Setup required") {
                    Text(
                        "Grant Usage access so the app can read screen, unlock and app events. " +
                            "Android records these anyway; the app only copies them into a private database on this phone.",
                        fontSize = 13.sp, color = Palette.TextDim,
                    )
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Grant usage access", onClick = onGrantUsage)
                }
            }

            StatusHero(s, today, permissions, busy, now, onToggle)

            val bundle = today
            if (bundle != null) {
                val st = bundle.stats
                val sum = st.summary
                val first = st.days.firstOrNull()?.firstUseMinute
                Panel("Today", subtitle = "Since 00:00", contentPadding = PaddingValues(0.dp)) {
                    MetricGrid(
                        listOf(
                            { m -> MetricTile("Unlocks", sum.unlocks.toString(), m, caption = "${sum.wakes} screen wakes") },
                            { m -> MetricTile("Glances", sum.glances.toString(), m, caption = "screen on, no unlock") },
                            { m -> MetricTile("Avg session", Format.durationPrecise(sum.avgSession), m, caption = "per unlock") },
                            { m -> MetricTile("Longest", Format.durationPrecise(st.longestSession), m, caption = "single session") },
                            { m -> MetricTile("First unlock", first?.let(Format::minuteOfDay) ?: "–", m, caption = "start of phone day") },
                            { m -> MetricTile("Notifications", if (s.countNotifications) sum.notifications.toString() else "off", m, caption = if (s.countNotifications) "received" else "enable in settings") },
                        ),
                    )
                }

                val dayRange = TimeRanges.dayRange(LocalDate.now(zone), zone)
                Panel("Timeline", subtitle = "Screen sessions, 00 – 24 h") {
                    if (bundle.timeline.isEmpty()) EmptyHint("No sessions yet today.")
                    else DayTimeline(bundle.timeline, dayRange.start, dayRange.end, now)
                    Spacer(Modifier.height(6.dp))
                    Legend(listOf(Palette.Accent to "unlocked", Palette.TextFaint to "glance", Palette.Text to "now"))
                    Spacer(Modifier.height(4.dp))
                    DataRow("Longest break", st.longestBreak?.let { Format.duration(it.duration) } ?: "–", hint = "Between unlocked sessions")
                }

                Panel(
                    "Top apps",
                    subtitle = "${st.apps.size} apps used today",
                    trailing = {
                        Text(
                            "ALL STATS →",
                            style = LabelStyle.copy(color = Palette.Accent),
                            modifier = Modifier.clickable(onClick = onSeeAllApps).padding(6.dp),
                        )
                    },
                    contentPadding = PaddingValues(top = 6.dp, bottom = 4.dp),
                ) {
                    if (st.apps.isEmpty()) EmptyHint("Nothing recorded yet.", Modifier.padding(horizontal = 14.dp))
                    val top = st.apps.take(5)
                    val maxT = top.firstOrNull()?.total ?: 1
                    val total = st.apps.sumOf { it.total }.coerceAtLeast(1)
                    top.forEachIndexed { i, a ->
                        if (i > 0) Divider(Modifier.padding(start = 14.dp))
                        AppRankRow(
                            rank = i + 1,
                            packageName = a.packageName,
                            label = bundle.apps[a.packageName]?.label ?: a.packageName,
                            value = Format.duration(a.total),
                            fraction = a.total.toFloat() / maxT,
                            share = Format.percent(a.total.toDouble() / total),
                            meta = "${a.launches} opens · ⌀ ${Format.durationPrecise(a.avgSession)}",
                            onClick = { onOpenApp(a.packageName) },
                        )
                    }
                }

                Panel("System status", contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    val cov = st.coverage
                    DataRow("Coverage today", if (cov.elapsedMs > 0) Format.percent(cov.ratio) else "–", hint = "Share of the day tracking was on")
                    Divider()
                    DataRow("Data gaps", cov.dataGaps.size.toString(), hint = "System log expired before a sync", valueColor = if (cov.dataGaps.isEmpty()) Palette.Text else Palette.Warn)
                    Divider()
                    DataRow(
                        "Usage access", if (permissions.usageAccess) "granted" else "missing",
                        valueColor = if (permissions.usageAccess) Palette.Good else Palette.Bad,
                    )
                    Divider()
                    Row(Modifier.fillMaxWidth().clickable(onClick = onOpenBattery)) {
                        DataRow(
                            "Battery optimisation",
                            if (permissions.batteryUnrestricted) "unrestricted" else "optimised",
                            hint = if (permissions.batteryUnrestricted) "Syncs run on schedule" else "Fine – syncs may run a little later. Tap to change.",
                            valueColor = if (permissions.batteryUnrestricted) Palette.Good else Palette.TextDim,
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Lock, null, tint = Palette.TextFaint, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(6.dp))
                Text("LOCAL ONLY · NO INTERNET PERMISSION", style = LabelStyle.copy(color = Palette.TextFaint, fontSize = 9.sp))
            }
        }
    }
}

@Composable
private fun StatusHero(
    s: Settings,
    today: StatsBundle?,
    permissions: PermissionState,
    busy: Boolean,
    now: Long,
    onToggle: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val sum = today?.stats?.summary
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingIndicator(s.trackingEnabled)
            Spacer(Modifier.width(8.dp))
            Text(
                if (s.trackingEnabled) "RECORDING" else "PAUSED",
                style = LabelStyle.copy(color = if (s.trackingEnabled) Palette.Accent else Palette.TextDim, fontSize = 11.sp),
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    s.trackingEnabled && s.lastSyncAt > 0 ->
                        "synced " + DateUtils.getRelativeTimeSpanString(s.lastSyncAt, now, DateUtils.MINUTE_IN_MILLIS)
                    s.trackingEnabled -> "syncing…"
                    else -> "not recording"
                },
                style = MetaStyle,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("SCREEN TIME TODAY", style = LabelStyle)
        Spacer(Modifier.height(2.dp))
        Text(Format.duration(sum?.screenTime ?: 0), style = NumberStyle.copy(fontSize = 46.sp, letterSpacing = (-1).sp))
        Text(
            "${Format.duration(sum?.screenOnTime ?: 0)} screen-on incl. lock screen · ${sum?.unlocks ?: 0} unlocks",
            style = MetaStyle,
        )

        val goalMs = s.dailyGoalMinutes * 60_000L
        if (goalMs > 0) {
            Spacer(Modifier.height(14.dp))
            val used = sum?.screenTime ?: 0
            val ratio = used.toFloat() / goalMs
            val c = when {
                ratio > 1f -> Palette.Bad
                ratio > 0.8f -> Palette.Warn
                else -> Palette.Accent
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("DAILY GOAL", style = LabelStyle, modifier = Modifier.weight(1f))
                Text("${Format.percent(ratio.toDouble())} of ${Format.duration(goalMs)}", style = MetaStyle.copy(color = c))
            }
            Spacer(Modifier.height(6.dp))
            ProportionBar(ratio, c, height = 6.dp)
            Spacer(Modifier.height(4.dp))
            Text(
                if (ratio <= 1f) "${Format.duration(goalMs - used)} remaining" else "${Format.duration(used - goalMs)} over",
                style = MetaStyle,
            )
        }

        Spacer(Modifier.height(18.dp))
        PrimaryButton(
            text = if (s.trackingEnabled) "Stop tracking" else "Start tracking",
            icon = if (s.trackingEnabled) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
            outlined = s.trackingEnabled,
            enabled = !busy && permissions.usageAccess,
            onClick = onToggle,
        )
        Spacer(Modifier.height(8.dp))
        val since = today?.firstTrackedAt
        Text(
            when {
                !s.hasEverTracked -> "Recent history is backfilled automatically on first start."
                since != null -> "History since " + DateTimeFormatter.ofPattern("d MMM yyyy").format(TimeRanges.localDate(since, zone)) +
                    if (!s.trackingEnabled) " · data is kept while paused" else ""
                else -> ""
            },
            style = MetaStyle,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

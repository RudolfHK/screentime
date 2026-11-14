package io.github.rudolfhk.screentime.ui.appdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.rudolfhk.screentime.AppContainer
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.AppCategories
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.data.StatsBundle
import io.github.rudolfhk.screentime.ui.components.AppIcon
import io.github.rudolfhk.screentime.ui.components.DataRow
import io.github.rudolfhk.screentime.ui.components.Divider
import io.github.rudolfhk.screentime.ui.components.EmptyHint
import io.github.rudolfhk.screentime.ui.components.Gutter
import io.github.rudolfhk.screentime.ui.components.Panel
import io.github.rudolfhk.screentime.ui.stats.AppFactsPanel
import io.github.rudolfhk.screentime.ui.stats.ComparisonPanel
import io.github.rudolfhk.screentime.ui.stats.DailyLogPanel
import io.github.rudolfhk.screentime.ui.stats.HeatmapPanel
import io.github.rudolfhk.screentime.ui.stats.HourlyPanel
import io.github.rudolfhk.screentime.ui.stats.PeriodControls
import io.github.rudolfhk.screentime.ui.stats.SessionLengthPanel
import io.github.rudolfhk.screentime.ui.stats.SummaryPanel
import io.github.rudolfhk.screentime.ui.stats.TrendPanel
import io.github.rudolfhk.screentime.ui.stats.WeekdayPanel
import io.github.rudolfhk.screentime.ui.theme.MetaStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailViewModel(c: AppContainer, val packageName: String, initial: PeriodSelection) : ViewModel() {
    val selection = MutableStateFlow(initial)
    val bundle: StateFlow<StatsBundle?> = selection
        .flatMapLatest { c.stats.stats(it, setOf(packageName), null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@Composable
fun AppDetailScreen(packageName: String, initial: PeriodSelection, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm: AppDetailViewModel = viewModel(key = "app-$packageName") { AppDetailViewModel(context.container, packageName, initial) }
    val selection by vm.selection.collectAsStateWithLifecycle()
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    AppDetailContent(packageName, selection, bundle, onSelect = { vm.selection.value = it }, onBack = onBack)
}

@Composable
fun AppDetailContent(
    packageName: String,
    selection: PeriodSelection,
    bundle: StatsBundle?,
    onSelect: (PeriodSelection) -> Unit,
    onBack: () -> Unit,
) {
    val info = bundle?.apps?.get(packageName)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(end = Gutter, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Palette.Text) }
            AppIcon(packageName, 42.dp, label = info?.label)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(info?.label ?: packageName, color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    AppCategories.label(info?.category ?: AppCategories.UNDEFINED).uppercase() + " · " + packageName,
                    style = MetaStyle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PeriodControls(selection, bundle?.stats?.range, onSelect = onSelect)
            val b = bundle ?: run { EmptyHint("Loading…"); return@Column }
            val st = b.stats
            SummaryPanel(st, b.previous, b.settings.countNotifications)
            AppFactsPanel(st, b.settings.countNotifications)
            TrendPanel(st, 0)
            ComparisonPanel(st, b.previous)
            HourlyPanel(st)
            HeatmapPanel(st)
            WeekdayPanel(st)
            SessionLengthPanel(st)
            DailyLogPanel(st, 0, b.apps)
            Spacer(Modifier.height(20.dp))
        }
    }
}

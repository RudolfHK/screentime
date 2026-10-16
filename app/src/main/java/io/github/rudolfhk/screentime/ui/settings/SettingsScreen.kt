package io.github.rudolfhk.screentime.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.rudolfhk.screentime.BuildConfig
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.BackupFormatException
import io.github.rudolfhk.screentime.core.Format
import io.github.rudolfhk.screentime.data.AppInfoEntity
import io.github.rudolfhk.screentime.data.Settings
import io.github.rudolfhk.screentime.tracking.Permissions
import io.github.rudolfhk.screentime.ui.PermissionState
import io.github.rudolfhk.screentime.ui.components.Pill
import io.github.rudolfhk.screentime.ui.components.SectionCard
import io.github.rudolfhk.screentime.ui.components.StatusDot
import io.github.rudolfhk.screentime.ui.stats.AppPickerSheet
import io.github.rudolfhk.screentime.ui.theme.Mono
import io.github.rudolfhk.screentime.ui.theme.NumberStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun consumeMessage() { _message.value = null }

    fun setInterval(min: Int) = viewModelScope.launch { c.tracking.reschedule(min) }
    fun setGoal(min: Int) = viewModelScope.launch { c.settings.setDailyGoal(min.coerceIn(0, 16 * 60)) }
    fun setRetention(days: Int) = viewModelScope.launch {
        c.settings.setRetentionDays(days)
        if (days > 0) c.tracking.sync()
    }
    fun setExcluded(p: Set<String>) = viewModelScope.launch { c.settings.setExcluded(p) }
    fun setCountNotifications(on: Boolean) = viewModelScope.launch { c.settings.setCountNotifications(on) }
    fun setAppLock(on: Boolean) = viewModelScope.launch { c.settings.setAppLock(on) }

    /** Apps seen in usage data plus launchable apps on the device, for the exclusion picker. */
    suspend fun pickerApps(): List<AppInfoEntity> = withContext(Dispatchers.IO) {
        val known = c.db.usageDao().apps().first().associateBy { it.packageName }.toMutableMap()
        val now = System.currentTimeMillis()
        (c.appInfo.launchablePackages() + c.settings.current().excludedPackages).forEach { pkg ->
            if (pkg !in known) known[pkg] = c.appInfo.resolve(pkg, now) ?: c.appInfo.fallback(pkg, now)
        }
        known.values.toList()
    }

    private fun runTask(label: String, block: suspend () -> String) = viewModelScope.launch {
        _busy.value = true
        _message.value = try {
            block()
        } catch (e: BackupFormatException) {
            "$label failed: ${e.message}"
        } catch (e: Exception) {
            "$label failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            _busy.value = false
        }
    }

    fun exportJson(uri: Uri) = runTask("Export") { "Exported ${c.backup.exportJson(uri)} records" }
    fun exportCsv(uri: Uri) = runTask("CSV export") { c.backup.exportCsvZip(uri); "CSV archive saved" }
    fun import(uri: Uri) = runTask("Import") {
        val r = c.backup.import(uri)
        c.tracking.refreshAppInfo()
        if (r.total == 0) "Nothing new – all records were already present"
        else "Imported ${r.screenSessions} screen sessions, ${r.appSessions} app sessions, ${r.unlocks} unlocks, ${r.notifications} notifications"
    }
    fun clearAll() = runTask("Delete") { c.tracking.clearAllData(); "All usage data deleted" }
}

@Composable
fun SettingsScreen(permissions: PermissionState) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(context.container) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var excludePicker by remember { mutableStateOf<List<AppInfoEntity>?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::exportJson) }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::exportCsv) }
    val importJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    val s = settings ?: return
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))

            SectionCard("Tracking") {
                Text("Sync interval", fontSize = 14.sp)
                Text("How often the system log is copied. Longer = marginally less battery.", fontSize = 11.sp, color = Palette.TextFaint)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        Pill(if (m < 60) "${m}m" else "${m / 60}h", s.syncIntervalMinutes == m) { vm.setInterval(m) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                StatusRow("Usage access", permissions.usageAccess, if (permissions.usageAccess) "granted" else "required") {
                    Permissions.openUsageAccessSettings(context)
                }
                StatusRow("Battery optimisation", permissions.batteryUnrestricted, if (permissions.batteryUnrestricted) "unrestricted" else "optimised (optional)") {
                    Permissions.openBatteryOptimizationSettings(context)
                }
            }

            SectionCard("Daily goal") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (s.dailyGoalMinutes == 0) "Off" else Format.duration(s.dailyGoalMinutes * 60_000L), style = NumberStyle.copy(fontSize = 24.sp))
                        Text("Shown as progress on the Tracker, widget and trend chart.", fontSize = 11.sp, color = Palette.TextFaint)
                    }
                    IconButton(onClick = { vm.setGoal(s.dailyGoalMinutes - 15) }, enabled = s.dailyGoalMinutes > 0) { Icon(Icons.Filled.Remove, "Decrease goal") }
                    IconButton(onClick = { vm.setGoal(s.dailyGoalMinutes + 15) }) { Icon(Icons.Filled.Add, "Increase goal") }
                }
            }

            SectionCard("Data") {
                Text("Keep data for", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Forever", 90 to "90d", 180 to "180d", 365 to "1y").forEach { (d, l) ->
                        Pill(l, s.retentionDays == d) { vm.setRetention(d) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                NavRow("Excluded apps", "${s.excludedPackages.size} hidden from rankings (launcher, keyboard…)") {
                    scope.launch { excludePicker = vm.pickerApps() }
                }
                NavRow("Delete all usage data", "Cannot be undone – export first", danger = true) { confirmDelete = true }
            }

            SectionCard("Optional features") {
                ToggleRow(
                    "Count notifications",
                    if (permissions.notificationAccess) "Counts per app. Only package + time are stored, never content."
                    else "Needs notification access – tap to grant",
                    s.countNotifications && permissions.notificationAccess,
                ) { on ->
                    if (on && !permissions.notificationAccess) Permissions.openNotificationAccessSettings(context)
                    vm.setCountNotifications(on)
                }
                ToggleRow("App lock", "Require fingerprint, face or device PIN to open the app", s.appLock) { on ->
                    if (on) {
                        val res = BiometricManager.from(context).canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                        )
                        if (res == BiometricManager.BIOMETRIC_SUCCESS) vm.setAppLock(true)
                        else scope.launch { snackbar.showSnackbar("Set up a screen lock on this device first") }
                    } else vm.setAppLock(false)
                }
            }

            SectionCard("Backup & transfer") {
                Text(
                    "Move your history to a new phone: export a backup here, copy the file over, then import it on the new device. " +
                        "Imports merge – nothing already on the device is overwritten.",
                    fontSize = 12.sp, color = Palette.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                val date = LocalDate.now().toString()
                NavRow("Export full backup", "JSON · re-importable", enabled = !busy) { exportJson.launch("screentime-backup-$date.json") }
                NavRow("Export for spreadsheets", "ZIP of CSV files · daily summary, sessions, apps", enabled = !busy) { exportCsv.launch("screentime-csv-$date.zip") }
                NavRow("Import backup", "Select a .json backup", enabled = !busy) { importJson.launch(arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")) }
            }

            SectionCard("About") {
                Text("Screentime Diagnostics ${BuildConfig.VERSION_NAME}", fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Privacy: the app has no internet permission. All data lives in a private database on this device, " +
                        "is excluded from cloud backups, and only leaves the phone when you export it yourself.",
                    fontSize = 12.sp, color = Palette.TextDim,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Battery: no background service. A short WorkManager job copies Android's own usage log every ${s.syncIntervalMinutes} min.",
                    fontSize = 12.sp, color = Palette.TextDim,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all data?") },
            text = { Text("All sessions, unlocks and notification counts will be removed from this device. Settings are kept.") },
            confirmButton = { TextButton(onClick = { vm.clearAll(); confirmDelete = false }) { Text("Delete", color = Palette.Bad) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = Palette.Surface,
        )
    }

    excludePicker?.let { apps ->
        AppPickerSheet(
            title = "Exclude from rankings",
            apps = apps,
            initial = s.excludedPackages,
            applyLabel = "Save",
            onDismiss = { excludePicker = null },
            onApply = { vm.setExcluded(it); excludePicker = null },
        )
    }
}

@Composable
private fun StatusRow(label: String, ok: Boolean, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(if (ok) Palette.Good else Palette.Warn)
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = Palette.TextDim, fontFamily = Mono)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextFaint)
    }
}

@Composable
private fun NavRow(label: String, hint: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = if (danger) Palette.Bad else if (enabled) Palette.Text else Palette.TextFaint)
            Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Palette.TextFaint)
    }
}

@Composable
private fun ToggleRow(label: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            Text(hint, fontSize = 11.sp, color = Palette.TextFaint)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Palette.Bg, checkedTrackColor = Palette.Accent),
        )
    }
}

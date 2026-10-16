package io.github.rudolfhk.screentime.ui

import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.rudolfhk.screentime.container
import io.github.rudolfhk.screentime.core.PeriodSelection
import io.github.rudolfhk.screentime.core.RangeKind
import io.github.rudolfhk.screentime.tracking.Permissions
import io.github.rudolfhk.screentime.ui.appdetail.AppDetailScreen
import io.github.rudolfhk.screentime.ui.settings.SettingsScreen
import io.github.rudolfhk.screentime.ui.stats.StatsScreen
import io.github.rudolfhk.screentime.ui.theme.LabelStyle
import io.github.rudolfhk.screentime.ui.theme.Palette
import io.github.rudolfhk.screentime.ui.theme.ScreenTimeTheme
import io.github.rudolfhk.screentime.ui.tracker.TrackerScreen
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PermissionState(
    val usageAccess: Boolean = false,
    val notificationAccess: Boolean = false,
    val batteryUnrestricted: Boolean = false,
)

/** FragmentActivity (not ComponentActivity) because BiometricPrompt needs it. */
class MainActivity : FragmentActivity() {

    private var permissions by mutableStateOf(PermissionState())
    private var unlocked by mutableStateOf(false)
    private val relockObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { unlocked = false }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Palette.Bg.toArgb()),
        )
        super.onCreate(savedInstanceState)
        unlocked = savedInstanceState?.getBoolean(KEY_UNLOCKED) ?: false

        // Re-lock whenever the whole app goes to the background.
        ProcessLifecycleOwner.get().lifecycle.addObserver(relockObserver)

        setContent {
            ScreenTimeTheme {
                val settings by container.settings.settings.collectAsStateWithLifecycle(null)
                val s = settings
                when {
                    s == null -> Box(Modifier.fillMaxSize().background(Palette.Bg))
                    s.appLock && !unlocked -> {
                        LaunchedEffect(Unit) { authenticate() }
                        LockScreen(onUnlock = ::authenticate)
                    }
                    else -> AppRoot(permissions)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissions = PermissionState(
            usageAccess = Permissions.hasUsageAccess(this),
            notificationAccess = Permissions.hasNotificationAccess(this),
            batteryUnrestricted = Permissions.isIgnoringBatteryOptimizations(this),
        )
        // Opening the app always shows fresh numbers.
        lifecycleScope.launch { container.tracking.sync() }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(relockObserver)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, unlocked)
    }

    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Screentime Diagnostics")
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }

    private companion object {
        const val KEY_UNLOCKED = "unlocked"
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Palette.Bg).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.Fingerprint, null, tint = Palette.Accent, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text("LOCKED", style = LabelStyle.copy(letterSpacing = 3.sp))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onUnlock, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent, contentColor = Palette.Bg)) {
            Text("Unlock")
        }
    }
}

private enum class Tab(val route: String, val label: String) {
    Tracker("tracker", "Tracker"),
    Stats("stats", "Stats"),
    Settings("settings", "Settings"),
}

@Composable
private fun AppRoot(permissions: PermissionState) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    fun openApp(pkg: String, sel: PeriodSelection) {
        nav.navigate(
            "app/$pkg?kind=${sel.kind.name}&anchor=${sel.anchor.toEpochDay()}" +
                "&cs=${sel.customStart?.toEpochDay() ?: Long.MIN_VALUE}&ce=${sel.customEnd?.toEpochDay() ?: Long.MIN_VALUE}",
        )
    }

    Scaffold(
        containerColor = Palette.Bg,
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar(containerColor = Palette.Surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        Tab.Tracker -> Icons.Filled.Timer
                                        Tab.Stats -> Icons.Filled.BarChart
                                        Tab.Settings -> Icons.Filled.Settings
                                    },
                                    contentDescription = null,
                                )
                            },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.Accent,
                                selectedTextColor = Palette.Accent,
                                indicatorColor = Palette.Accent.copy(alpha = 0.12f),
                                unselectedIconColor = Palette.TextFaint,
                                unselectedTextColor = Palette.TextFaint,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.Tracker.route, modifier = Modifier.padding(padding)) {
            composable(Tab.Tracker.route) {
                TrackerScreen(permissions, onOpenApp = { openApp(it, PeriodSelection(RangeKind.DAY, LocalDate.now())) })
            }
            composable(Tab.Stats.route) { StatsScreen(onOpenApp = ::openApp) }
            composable(Tab.Settings.route) { SettingsScreen(permissions) }
            composable(
                "app/{pkg}?kind={kind}&anchor={anchor}&cs={cs}&ce={ce}",
                arguments = listOf(
                    navArgument("pkg") { type = NavType.StringType },
                    navArgument("kind") { type = NavType.StringType; defaultValue = RangeKind.WEEK.name },
                    navArgument("anchor") { type = NavType.LongType; defaultValue = LocalDate.now().toEpochDay() },
                    navArgument("cs") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                    navArgument("ce") { type = NavType.LongType; defaultValue = Long.MIN_VALUE },
                ),
            ) { e ->
                val args = e.arguments!!
                val cs = args.getLong("cs").takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
                val ce = args.getLong("ce").takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
                val sel = PeriodSelection(
                    kind = RangeKind.valueOf(args.getString("kind") ?: RangeKind.WEEK.name),
                    anchor = LocalDate.ofEpochDay(args.getLong("anchor")),
                    customStart = cs,
                    customEnd = ce,
                )
                AppDetailScreen(args.getString("pkg")!!, sel, onBack = { nav.popBackStack() })
            }
        }
    }
}

package com.example

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AvTimer
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.outlined.AvTimer
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CoreSingletons
import com.example.earbud.EarbudChiplet
import com.example.faces.FacesChiplet
import com.example.standby.StandByChiplet
import com.example.stopwatch.StopwatchChiplet
import com.example.timer.TimerChiplet
import com.example.ui.theme.AuraClockTheme
import com.example.ui.theme.OledBlack

enum class AppTab(
  val label: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
) {
  TIMER("Timer", Icons.Filled.HourglassBottom, Icons.Outlined.HourglassBottom, "tab_timer"),
  CLOCK("Clock", Icons.Filled.Watch, Icons.Outlined.Watch, "tab_clock"),
  STOPWATCH("Stopwatch", Icons.Filled.AvTimer, Icons.Outlined.AvTimer, "tab_stopwatch"),
  SETTINGS("Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "tab_settings"),
}

/**
 * Main activity hosting AuraClock's OLED pure-black adaptive navigation shell.
 */
class MainActivity : ComponentActivity() {
  override fun onStart() {
    super.onStart()
    EarbudChiplet.ensureInitialized(this).ensureStarted()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge(
      statusBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
      navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.BLACK),
    )
    setContent {
      val context = LocalContext.current
      val prefs = remember { CoreSingletons.prefs(context) }
      val accent by prefs.themeColor.collectAsStateWithLifecycle()

      AuraClockTheme(accent = accent) {
        AuraClockRoot()
      }
    }
  }
}

@Composable
private fun AuraClockRoot() {
  val context = LocalContext.current
  val view = LocalView.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val prefs = remember { CoreSingletons.prefs(context) }

  var currentTab by rememberSaveable { mutableStateOf(AppTab.TIMER) }
  var showStandByPreview by rememberSaveable { mutableStateOf(false) }
  var isTimerFullScreen by rememberSaveable { mutableStateOf(prefs.timerFullScreenByDefault.value) }

  val configuration = LocalConfiguration.current
  val useRail = configuration.screenWidthDp > configuration.screenHeightDp && configuration.screenWidthDp >= 600
  val immersiveTimer = !showStandByPreview && currentTab == AppTab.TIMER && isTimerFullScreen

  // Sync Android system bars with full-screen state and always restore on leave or ON_STOP.
  LaunchedEffect(immersiveTimer, view) {
    setSystemBarsVisible(context.findActivity(), view, visible = !immersiveTimer)
  }

  DisposableEffect(immersiveTimer, lifecycleOwner, view) {
    val observer = LifecycleEventObserver { _, event ->
      val activity = context.findActivity()
      when (event) {
        Lifecycle.Event.ON_STOP -> setSystemBarsVisible(activity, view, visible = true)
        Lifecycle.Event.ON_START,
        Lifecycle.Event.ON_RESUME -> setSystemBarsVisible(activity, view, visible = !immersiveTimer)
        else -> Unit
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
      setSystemBarsVisible(context.findActivity(), view, visible = true)
    }
  }

  val selectTab: (AppTab) -> Unit = { tab ->
    currentTab = tab
    isTimerFullScreen = tab == AppTab.TIMER && prefs.timerFullScreenByDefault.value
  }

  if (showStandByPreview) {
    StandByChiplet.PreviewScreen(
      onExit = { showStandByPreview = false },
      modifier = Modifier.fillMaxSize(),
    )
    return
  }

  Scaffold(
    containerColor = OledBlack,
    contentWindowInsets = if (immersiveTimer) WindowInsets(0) else WindowInsets.safeDrawing,
    bottomBar = {
      if (!useRail && !immersiveTimer) {
        NavigationBar(
          containerColor = Color(0xFF07090D),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
          AppTab.entries.forEach { tab ->
            val selected = currentTab == tab
            NavigationBarItem(
              selected = selected,
              onClick = { selectTab(tab) },
              icon = {
                Icon(
                  imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                  contentDescription = tab.label,
                )
              },
              label = { Text(tab.label) },
              modifier = Modifier.testTag(tab.testTag),
            )
          }
        }
      }
    },
  ) { innerPadding ->
    Row(
      modifier = Modifier
        .fillMaxSize()
        .background(OledBlack)
        .padding(innerPadding),
    ) {
      if (useRail && !immersiveTimer) {
        NavigationRail(
          containerColor = Color(0xFF07090D),
          contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
          AppTab.entries.forEach { tab ->
            val selected = currentTab == tab
            NavigationRailItem(
              selected = selected,
              onClick = { selectTab(tab) },
              icon = {
                Icon(
                  imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                  contentDescription = tab.label,
                )
              },
              label = { Text(tab.label) },
              modifier = Modifier.testTag(tab.testTag),
            )
          }
        }
      }

      Box(modifier = Modifier.weight(1f).fillMaxSize()) {
        when (currentTab) {
          AppTab.TIMER -> TimerChiplet.Screen(
            isFullScreen = isTimerFullScreen,
            onFullScreenChange = { isTimerFullScreen = it },
          )
          AppTab.CLOCK -> FacesChiplet.Screen()
          AppTab.STOPWATCH -> StopwatchChiplet.Screen()
          AppTab.SETTINGS -> SettingsScreen(onPreviewStandBy = { showStandByPreview = true })
        }
      }
    }
  }
}

private fun setSystemBarsVisible(
  activity: Activity?,
  view: android.view.View,
  visible: Boolean,
) {
  val window = activity?.window ?: return
  val controller = WindowCompat.getInsetsController(window, view)
  if (visible) {
    controller.show(WindowInsetsCompat.Type.systemBars())
  } else {
    controller.systemBarsBehavior =
      WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
private fun PlaceholderTab(name: String) {
  Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Text(text = name, style = MaterialTheme.typography.headlineMedium)
  }
}

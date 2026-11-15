package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.core.AuraThemeColor
import com.example.core.CoreSingletons

/**
 * Transparent home-screen clock widget using AnalogClock and TextClock so it ticks
 * natively without polling or background services. Tapping opens AuraClock.
 */
class AuraClockWidgetProvider : AppWidgetProvider() {

  override fun onUpdate(
    context: Context,
    appWidgetManager: AppWidgetManager,
    appWidgetIds: IntArray,
  ) {
    appWidgetIds.forEach { widgetId ->
      updateAppWidget(context, appWidgetManager, widgetId)
    }
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
    val prefs = CoreSingletons.prefs(context)
    appWidgetIds.forEach { prefs.deleteWidgetConfig(it) }
  }

  companion object {
    const val STYLE_MOTORSPORT = "motorsport_m"
    const val STYLE_SPEEDMASTER = "speedmaster_racing"
    const val STYLE_MINIMAL = "minimal_analog"
    const val STYLE_DIGITAL = "digital"

    /** Builds and pushes the transparent RemoteViews for a configured widget instance. */
    internal fun updateAppWidget(
      context: Context,
      appWidgetManager: AppWidgetManager,
      appWidgetId: Int,
    ) {
      val prefs = CoreSingletons.prefs(context)
      val styleId = prefs.getWidgetStyleId(appWidgetId)
      val color = AuraThemeColor.fromId(prefs.getWidgetColorId(appWidgetId))

      val layoutId = when (styleId) {
        STYLE_SPEEDMASTER -> R.layout.widget_speedmaster_racing
        STYLE_MINIMAL -> R.layout.widget_minimal_analog
        STYLE_DIGITAL -> R.layout.widget_digital
        else -> R.layout.widget_motorsport_m
      }

      val views = RemoteViews(context.packageName, layoutId)
      if (styleId == STYLE_DIGITAL) {
        views.setTextColor(R.id.widget_digital_time, color.primaryArgb)
      } else if (styleId == STYLE_MINIMAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        views.setColorStateList(
          R.id.widget_analog_clock,
          "setMinuteHandTintList",
          ColorStateList.valueOf(color.primaryArgb),
        )
      }

      val launchIntent = PendingIntent.getActivity(
        context,
        appWidgetId,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      views.setOnClickPendingIntent(R.id.widget_root, launchIntent)

      appWidgetManager.updateAppWidget(appWidgetId, views)
    }
  }
}

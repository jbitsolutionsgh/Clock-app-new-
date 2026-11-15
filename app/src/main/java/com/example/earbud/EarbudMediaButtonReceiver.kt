package com.example.earbud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import com.example.core.CoreSingletons

/**
 * Receives hardware ACTION_MEDIA_BUTTON broadcasts and routes them to MediaKeyRouter.
 */
class EarbudMediaButtonReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
    if (!CoreSingletons.prefs(context).earbudEnabled.value) return
    val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      ?: return
    if (EarbudChiplet.router(context).handleKeyEvent(event) && isOrderedBroadcast) {
      abortBroadcast()
    }
  }
}

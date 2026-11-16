package com.example.core

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uncaught exception handler that writes crash stack traces to an offline log file
 * so teachers can share diagnostics without network permissions.
 */
object CrashCatcher {

  private const val CRASH_FILE_NAME = "last_crash.txt"
  private var installed = false

  /** Installs the uncaught exception handler once at app startup. */
  fun install(context: Context) {
    if (installed) return
    installed = true
    val appContext = context.applicationContext
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      writeCrashLog(appContext, thread, throwable)
      previousHandler?.uncaughtException(thread, throwable)
    }
  }

  /** Records a non-fatal caught error (such as a calendar query failure) to the offline log file. */
  fun logNonFatal(context: Context, title: String, throwable: Throwable) {
    runCatching {
      val sw = StringWriter()
      val pw = PrintWriter(sw)
      val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
      pw.println("AuraClock Error Log: $title")
      pw.println("Time: $stamp")
      pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
      pw.println("---")
      throwable.printStackTrace(pw)
      pw.flush()
      getCrashFile(context).writeText(sw.toString())
    }
  }

  /** Writes the stack trace and device header to internal files storage. */
  private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
    runCatching {
      val sw = StringWriter()
      val pw = PrintWriter(sw)
      val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
      pw.println("AuraClock Crash Log")
      pw.println("Time: $stamp")
      pw.println("Thread: ${thread.name}")
      pw.println("Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
      pw.println("---")
      throwable.printStackTrace(pw)
      pw.flush()
      getCrashFile(context).writeText(sw.toString())
    }
  }

  /** Returns true if a crash log file exists on disk. */
  fun hasCrashLog(context: Context): Boolean =
    getCrashFile(context).let { it.exists() && it.length() > 0L }

  /** Reads the last saved crash log, or null if none exists. */
  fun readLastCrashLog(context: Context): String? {
    val file = getCrashFile(context)
    return if (file.exists() && file.length() > 0L) file.readText() else null
  }

  /** Builds a share chooser Intent containing the last crash log text. */
  fun createShareIntent(context: Context): Intent {
    val logText = readLastCrashLog(context) ?: "No crash log recorded."
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
      type = "text/plain"
      putExtra(Intent.EXTRA_SUBJECT, "AuraClock Crash Log")
      putExtra(Intent.EXTRA_TEXT, logText)
    }
    return Intent.createChooser(sendIntent, "Share last crash log")
  }

  private fun getCrashFile(context: Context): File =
    File(context.applicationContext.filesDir, CRASH_FILE_NAME)
}

package com.haobo.cleaner

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class HaoboApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        if (BuildConfig.DEBUG) runDiagnostics()
    }

    /**
     * Debug-only: prove whether the stats backend can see other apps' cache
     * without touching Android/data. Read the result with:
     *   adb logcat -s HaoboCleaner
     */
    private fun runDiagnostics() {
        scope.launch {
            val scanner = StatsScanner(this@HaoboApp)
            val hasAccess = scanner.hasUsageAccess()
            Log.d(TAG, "DIAG usageAccess=$hasAccess")
            val result = scanner.scan { }
            Log.d(TAG, "DIAG stats items=${result.items.size} error=${result.error}")
            result.items.take(8).forEach {
                Log.d(TAG, "DIAG stats ${it.label} = ${it.size} bytes")
            }
            val listable = File("/sdcard/Android/data").listFiles()
            Log.d(TAG, "DIAG androidDataListable=${listable?.size ?: -1}")
        }
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(filesDir, "crash")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, "crash-${System.currentTimeMillis()}.log")
                file.writeText(
                    buildString {
                        appendLine("thread: ${thread.name}")
                        appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("android: ${android.os.Build.VERSION.SDK_INT}")
                        appendLine("model: ${android.os.Build.MODEL}")
                        appendLine(Log.getStackTraceString(throwable))
                    }
                )
                Log.e(TAG, "uncaught exception written to ${file.absolutePath}", throwable)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private companion object {
        const val TAG = "HaoboCleaner"
    }
}

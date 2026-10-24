package com.haobo.cleaner

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Per-app storage numbers without touching Android/data.
 *
 * This is what system Settings > Storage shows, and what most Chinese
 * "cleaner" apps actually display. It never reads another app's files,
 * so it works with zero special access — only the usage-access toggle.
 */
class StatsScanner(private val context: Context) {

    fun hasUsageAccess(): Boolean = runCatching {
        val sm = context.getSystemService(StorageStatsManager::class.java)
        sm.queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
        true
    }.getOrElse { false }

    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    suspend fun scan(onStage: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        if (!hasUsageAccess()) {
            return@withContext ScanResult(
                emptyList(),
                "需要「使用情况访问」权限，点上方按钮开启后重试",
            )
        }

        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        val sm = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val items = mutableListOf<JunkItem>()

        for (info in packages) {
            val pkg = info.packageName
            onStage(pkg)
            val stats = runCatching {
                sm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
            }.getOrNull() ?: continue
            val cache = stats.cacheBytes
            if (cache <= 0) continue
            val label = info.applicationInfo?.let { ai ->
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
            } ?: pkg
            items += JunkItem(
                path = "stats:$pkg",
                label = label,
                size = cache,
                group = CleanGroup.APP,
                ruleName = "cache",
            )
        }
        ScanResult(items.sortedByDescending { it.size }.take(300))
    }

    companion object {
        val DEFAULT_UUID: UUID = StorageManager.UUID_DEFAULT
    }
}

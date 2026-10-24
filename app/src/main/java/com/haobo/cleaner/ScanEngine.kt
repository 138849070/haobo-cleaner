package com.haobo.cleaner

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val DATA_ROOT = "/sdcard/Android/data"
private const val MAX_RESULTS = 3000
private val WHITESPACE = Regex("\\s+")
private val KINDS = listOf("cache", "logs", "code_cache")

class ScanEngine(
    private val context: Context,
    private val direct: DirectBackend,
    private val shellClient: ShellClient,
    private val saf: SafBackend,
    private val stats: StatsScanner,
    private val analyzers: Analyzers,
    private val exclusions: Exclusions,
) {

    private val shell = ShellBackend(shellClient)

    private val shellReady: Boolean
        get() = shellClient.state.value is ShellState.Ready

    private val needShizuku = ScanResult(
        emptyList(),
        "这个模块要读 Android/data 内部，需要 Shizuku 或 SAF 授权",
    )

    private val needPermission = ScanResult(
        emptyList(),
        "需要授予「所有文件访问」权限，或安装 Shizuku",
    )

    suspend fun scan(group: CleanGroup, onStage: (String) -> Unit = {}): ScanResult =
        withContext(Dispatchers.IO) {
            val raw = when (group) {
                CleanGroup.APP -> when {
                    saf.granted -> ScanResult(saf.scanAppData(onStage))
                    shellReady -> scanAppCaches(onStage)
                    else -> stats.scan(onStage)
                }
                CleanGroup.RESIDUAL -> when {
                    saf.granted -> ScanResult(saf.scanResidual(installedPackages()))
                    shellReady -> scanResidual(onStage)
                    else -> needShizuku
                }
                CleanGroup.EMPTY_DIR -> if (anyBackend()) scanEmptyDirs(onStage) else needPermission
                CleanGroup.DUPLICATE -> analyzers.duplicates(onStage)
                CleanGroup.STORAGE -> analyzers.storage(onStage)
                CleanGroup.CATEGORY -> analyzers.categories(onStage)
                CleanGroup.APPS -> analyzers.apps(onStage)
                else -> if (anyBackend()) scanByRules(group, onStage) else needPermission
            }
            raw.copy(items = exclusions.filterOut(raw.items))
        }

    suspend fun delete(items: List<JunkItem>): CleanReport = withContext(Dispatchers.IO) {
        var freed = 0L
        val failed = mutableListOf<String>()
        val statsOnly = mutableListOf<String>()
        for (item in items) {
            if (item.path.startsWith("stats:")) {
                statsOnly += item.label
                continue
            }
            val ok = when {
                item.path.startsWith("content://") -> saf.delete(item.path)
                else -> backendFor(item.path)?.deletePath(item.path) ?: false
            }
            if (ok) freed += item.size else failed += item.label
        }
        if (statsOnly.isNotEmpty()) {
            failed += statsOnly.map { "$it（需要 Shizuku 才能直接清理）" }
        }
        CleanReport(freed, failed)
    }

    @Suppress("DEPRECATION")
    private fun installedPackages(): Set<String> = runCatching {
        context.packageManager.getInstalledPackages(0).map { it.packageName }.toHashSet()
    }.getOrDefault(emptySet())

    suspend fun trimSystemCaches(): ShellResult =
        shellClient.exec("pm trim-caches 999G")

    private fun anyBackend(): Boolean = direct.available || shellReady

    private fun backend(): FileBackend? = when {
        direct.available -> direct
        shellReady -> shell
        else -> null
    }

    private fun backendFor(path: String): FileBackend? {
        val insideAppData = path.startsWith("$DATA_ROOT/")
        return when {
            insideAppData && shellReady -> shell
            direct.available -> direct
            shellReady -> shell
            else -> null
        }
    }

    private suspend fun scanByRules(group: CleanGroup, onStage: (String) -> Unit): ScanResult {
        val backend = backend() ?: return needPermission
        val items = mutableListOf<JunkItem>()
        for (rule in BuiltinRules.rulesFor(group)) {
            if (items.size >= MAX_RESULTS) break
            onStage(rule.name)
            for (root in rule.roots) {
                if (items.size >= MAX_RESULTS) break
                val lines = backend.scanFiles(
                    root = root,
                    include = rule.include,
                    exclude = rule.exclude,
                    excludeDirs = rule.excludeDirs,
                    minSize = rule.minSize,
                    maxDepth = rule.maxDepth,
                    maxResults = MAX_RESULTS - items.size,
                )
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab <= 0) continue
                    val size = line.substring(0, tab).toLongOrNull() ?: continue
                    val path = line.substring(tab + 1)
                    items += JunkItem(
                        path = path,
                        label = path.substringAfterLast('/'),
                        size = size,
                        group = group,
                        ruleName = rule.name,
                    )
                }
            }
        }
        return ScanResult(items.sortedByDescending { it.size })
    }

    private suspend fun scanAppCaches(onStage: (String) -> Unit): ScanResult {
        onStage("读取应用缓存")
        val out = shellClient.exec(
            "du -sk $DATA_ROOT/*/cache $DATA_ROOT/*/logs $DATA_ROOT/*/code_cache 2>/dev/null"
        ).stdout
        if (out.isBlank()) return ScanResult(emptyList(), "无法读取 Android/data")

        val pm = context.packageManager
        val labels = HashMap<String, String>()
        val items = out.lineSequence()
            .mapNotNull { line ->
                val parts = line.trim().split(WHITESPACE, limit = 2)
                if (parts.size < 2) return@mapNotNull null
                val kb = parts[0].toLongOrNull() ?: return@mapNotNull null
                if (kb <= 0) return@mapNotNull null
                val path = parts[1].trim()
                val pkg = path.removePrefix("$DATA_ROOT/").substringBefore('/')
                val kind = path.substringAfterLast('/')
                if (pkg.isBlank()) return@mapNotNull null
                val label = labels.getOrPut(pkg) { labelOf(pm, pkg) }
                JunkItem(
                    path = path,
                    label = "$label · $kind",
                    size = kb * 1024,
                    group = CleanGroup.APP,
                    ruleName = kind,
                )
            }
            .sortedByDescending { it.size }
            .take(MAX_RESULTS)
            .toList()
        return ScanResult(items)
    }

    /**
     * No SAF, no Shizuku: try plain File API on Android/data.
     * On AOSP this returns nothing (scoped storage), but some vendor ROMs
     * relax the check for apps holding MANAGE_EXTERNAL_STORAGE.
     */
    private suspend fun scanAppCachesLocal(onStage: (String) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            onStage("尝试直接读取 Android/data")
            val root = File(DATA_ROOT)
            if (!root.isDirectory) {
                return@withContext ScanResult(emptyList(), "Android/data 不可读（系统限制，需要 Shizuku）")
            }
            val children = root.listFiles()
            if (children.isNullOrEmpty()) {
                return@withContext ScanResult(emptyList(), "Android/data 不可读（系统限制，需要 Shizuku）")
            }

            val pm = context.packageManager
            val labels = HashMap<String, String>()
            val items = mutableListOf<JunkItem>()
            for (pkgDir in children) {
                val pkg = pkgDir.name ?: continue
                for (kind in KINDS) {
                    val dir = File(pkgDir, kind)
                    if (!dir.isDirectory) continue
                    val size = FileWalker.sizeOf(dir.absolutePath)
                    if (size <= 0) continue
                    val label = labels.getOrPut(pkg) { labelOf(pm, pkg) }
                    items += JunkItem(
                        path = dir.absolutePath,
                        label = "$label · $kind",
                        size = size,
                        group = CleanGroup.APP,
                        ruleName = kind,
                    )
                }
            }
            if (items.isEmpty()) {
                ScanResult(emptyList(), "Android/data 可列出但没有可清理的缓存")
            } else {
                ScanResult(items.sortedByDescending { it.size }.take(MAX_RESULTS))
            }
        }

    private suspend fun scanResidual(onStage: (String) -> Unit): ScanResult {
        onStage("比对已安装应用")
        @Suppress("DEPRECATION")
        val installed = runCatching {
            context.packageManager.getInstalledPackages(0)
                .map { it.packageName }
                .toHashSet()
        }.getOrDefault(emptySet())

        val dirs = shellClient.exec("ls $DATA_ROOT").stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('.') }
            .filter { it !in installed }
            .toList()

        val items = mutableListOf<JunkItem>()
        for (pkg in dirs) {
            val path = "$DATA_ROOT/$pkg"
            val size = shell.sizeOf(path)
            if (size > 0) {
                items += JunkItem(
                    path = path,
                    label = pkg,
                    size = size,
                    group = CleanGroup.RESIDUAL,
                    ruleName = "已卸载",
                )
            }
        }
        return ScanResult(items.sortedByDescending { it.size })
    }

    private suspend fun scanEmptyDirs(onStage: (String) -> Unit): ScanResult {
        onStage("查找空文件夹")
        val paths: List<String> = if (shellReady) {
            shellClient.exec("find /sdcard -type d -empty 2>/dev/null").stdout
                .lineSequence()
                .map { it.trim() }
                .filter { it.startsWith("/sdcard/") }
                .toList()
        } else {
            FileWalker.findEmptyDirs("/sdcard", 8, MAX_RESULTS)
        }

        val items = paths
            .filterNot { it.startsWith("/sdcard/Android") }
            .distinct()
            .sortedByDescending { it.length }
            .take(MAX_RESULTS)
            .map { path ->
                JunkItem(
                    path = path,
                    label = path.removePrefix("/sdcard/"),
                    size = 0L,
                    group = CleanGroup.EMPTY_DIR,
                    ruleName = "空目录",
                )
            }
        return ScanResult(items)
    }

    private fun labelOf(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}

data class CleanReport(
    val freedBytes: Long,
    val failed: List<String>,
)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return if (index == 0) "${bytes} B" else String.format("%.1f %s", value, units[index])
}

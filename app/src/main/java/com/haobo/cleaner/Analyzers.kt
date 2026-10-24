package com.haobo.cleaner

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

/**
 * Read-only analysis modules: duplicates, folder sizes, file-type breakdown,
 * installed apps. None of them delete anything on their own.
 */
class Analyzers(
    private val context: Context,
    private val exclusions: Exclusions,
) {

    suspend fun duplicates(onStage: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        onStage("收集文件")
        val files = ArrayList<File>(4096)
        collectFiles(File(SD), files, 0)
        if (files.isEmpty()) {
            return@withContext ScanResult(emptyList(), "没有找到可分析的文件")
        }

        onStage("按大小预筛")
        val candidates = files
            .filter { it.length() >= MIN_DUP_SIZE }
            .filterNot { exclusions.contains(it.absolutePath) }
            .groupBy { it.length() }
            .filterValues { it.size > 1 }

        if (candidates.isEmpty()) {
            return@withContext ScanResult(emptyList(), "没有发现体积相近的候选文件")
        }

        val items = mutableListOf<JunkItem>()
        var index = 0
        for ((_, group) in candidates) {
            index++
            onStage("比对第 $index / ${candidates.size} 组")
            val byHash = group.groupBy { digestOf(it) }
            for ((hash, same) in byHash) {
                if (hash == null || same.size < 2) continue
                // 保留修改时间最新的一个，其余视为冗余
                val keep = same.maxByOrNull { it.lastModified() } ?: continue
                for (f in same) {
                    if (f.absolutePath == keep.absolutePath) continue
                    items += JunkItem(
                        path = f.absolutePath,
                        label = f.name,
                        size = f.length(),
                        group = CleanGroup.DUPLICATE,
                        ruleName = "与 ${keep.name} 相同",
                    )
                }
            }
        }
        if (items.isEmpty()) {
            ScanResult(emptyList(), "没有找到重复文件")
        } else {
            ScanResult(items.sortedByDescending { it.size }.take(500))
        }
    }

    suspend fun storage(onStage: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        val root = File(SD)
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (dirs.isEmpty()) return@withContext ScanResult(emptyList(), "无法读取存储目录")

        val items = mutableListOf<JunkItem>()
        for ((i, dir) in dirs.withIndex()) {
            if (exclusions.contains(dir.absolutePath)) continue
            onStage("统计 ${dir.name}（${i + 1}/${dirs.size}）")
            val size = FileWalker.sizeOf(dir.absolutePath)
            if (size <= 0) continue
            items += JunkItem(
                path = dir.absolutePath,
                label = dir.name,
                size = size,
                group = CleanGroup.STORAGE,
                ruleName = "文件夹",
            )
        }
        val total = items.sumOf { it.size }
        ScanResult(
            items.sortedByDescending { it.size },
            if (items.isEmpty()) "没有可统计的目录" else "合计 ${formatBytes(total)}",
        )
    }

    suspend fun categories(onStage: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        onStage("扫描文件类型")
        val files = ArrayList<File>(8192)
        collectFiles(File(SD), files, 0)
        if (files.isEmpty()) return@withContext ScanResult(emptyList(), "没有找到文件")

        val buckets = HashMap<String, LongArray>()
        for (f in files) {
            if (exclusions.contains(f.absolutePath)) continue
            val kind = kindOf(f.name)
            val slot = buckets.getOrPut(kind) { LongArray(2) }
            slot[0] += 1
            slot[1] += f.length()
        }
        val items = buckets.map { (kind, slot) ->
            JunkItem(
                path = "category:$kind",
                label = "$kind · ${slot[0]} 个",
                size = slot[1],
                group = CleanGroup.CATEGORY,
                ruleName = kind,
            )
        }.sortedByDescending { it.size }
        ScanResult(items, "合计 ${formatBytes(items.sumOf { it.size })}")
    }

    suspend fun apps(onStage: (String) -> Unit): ScanResult = withContext(Dispatchers.IO) {
        onStage("读取应用列表")
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        val stats = runCatching {
            context.getSystemService(StorageStatsManager::class.java)
        }.getOrNull()
        val user = Process.myUserHandle()
        val self = context.packageName

        val items = mutableListOf<JunkItem>()
        for (info in packages) {
            val pkg = info.packageName
            if (pkg == self) continue
            val size = runCatching {
                val s = stats?.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, user)
                (s?.appBytes ?: 0L) + (s?.dataBytes ?: 0L)
            }.getOrDefault(0L)
            val label = info.applicationInfo?.let { ai ->
                runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull()
            } ?: pkg
            items += JunkItem(
                path = "app:$pkg",
                label = label,
                size = size,
                group = CleanGroup.APPS,
                ruleName = pkg,
            )
        }
        ScanResult(items.sortedByDescending { it.size }, "共 ${items.size} 个应用")
    }

    // ---- helpers ----

    private fun collectFiles(root: File, out: MutableList<File>, depth: Int) {
        if (depth > MAX_DEPTH || out.size >= MAX_FILES) return
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to depth)
        while (stack.isNotEmpty() && out.size < MAX_FILES) {
            val (current, d) = stack.removeLast()
            if (!current.isDirectory) {
                out += current
                continue
            }
            if (d >= MAX_DEPTH) continue
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    if (child.name in SKIP_DIRS) continue
                    stack.addLast(child to d + 1)
                } else {
                    out += child
                }
            }
        }
    }

    private fun digestOf(file: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                md.update(buf, 0, read)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun kindOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "heic", "bmp", "tiff" -> "图片"
            "mp4", "mkv", "avi", "mov", "3gp", "webm", "flv", "ts" -> "视频"
            "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus" -> "音频"
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub" -> "文档"
            "zip", "rar", "7z", "tar", "gz", "xz" -> "压缩包"
            "apk", "apks", "xapk", "apkm" -> "安装包"
            "so", "bin", "dat", "db", "obb" -> "数据文件"
            else -> "其他"
        }
    }

    private companion object {
        const val SD = "/sdcard"
        const val MIN_DUP_SIZE = 1024L * 1024
        const val MAX_FILES = 60_000
        const val MAX_DEPTH = 12
        val SKIP_DIRS = setOf("Android", "obj")
    }
}

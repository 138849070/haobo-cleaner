package com.haobo.cleaner

enum class CleanGroup(val key: String, val title: String, val desc: String) {
    REGULAR("regular", "常规清理", "日志、临时文件、缩略图缓存"),
    APP("app", "应用清理", "各应用的缓存占用"),
    RESIDUAL("residual", "残留清理", "已卸载应用留下的目录"),
    EMPTY_DIR("empty", "空文件夹", "层层嵌套的空目录"),
    BIG_FILE("big", "大文件", "超过 10 MB 的文件"),
    APK("apk", "安装包", "本地 APK / APKS / XAPK"),
    DUPLICATE("duplicate", "重复文件", "内容完全相同的文件"),
    STORAGE("storage", "存储分析", "各文件夹的空间占用"),
    CATEGORY("category", "分类分析", "按文件类型统计"),
    APPS("apps", "应用管理", "查看应用并卸载"),
    ;

    /** 分析类模块只展示，不提供删除 */
    val isAnalysis: Boolean
        get() = this == STORAGE || this == CATEGORY || this == APPS

    companion object {
        fun of(key: String): CleanGroup? = entries.firstOrNull { it.key == key }
    }
}

data class CleanRule(
    val id: String,
    val name: String,
    val group: CleanGroup,
    val roots: List<String>,
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val excludeDirs: List<String> = emptyList(),
    val minSize: Long = 0L,
    val maxDepth: Int = 8,
)

data class JunkItem(
    val path: String,
    val label: String,
    val size: Long,
    val group: CleanGroup,
    val ruleName: String,
    val checked: Boolean = true,
)

data class ScanResult(
    val items: List<JunkItem>,
    val error: String? = null,
)

object BuiltinRules {

    private const val SD = "/sdcard"

    private val SKIP_DIRS = listOf(
        "Android", "DCIM", "Pictures", "Movies", "Music",
        "Ringtones", "Alarms", "Notifications", "Podcasts",
    )

    val regular: List<CleanRule> = listOf(
        CleanRule(
            id = "regular_log",
            name = "日志与崩溃记录",
            group = CleanGroup.REGULAR,
            roots = listOf(SD, "$SD/Android/media"),
            include = listOf("*.log", "*.tlog", "*.blog", "*.crash", "*.stacktrace"),
            excludeDirs = SKIP_DIRS,
            maxDepth = 7,
        ),
        CleanRule(
            id = "regular_tmp",
            name = "临时文件",
            group = CleanGroup.REGULAR,
            roots = listOf(SD, "$SD/Android/media"),
            include = listOf("*.tmp", "*.temp", "*.bak", "*.orig"),
            excludeDirs = SKIP_DIRS,
            maxDepth = 7,
        ),
        CleanRule(
            id = "regular_thumb",
            name = "缩略图缓存",
            group = CleanGroup.REGULAR,
            roots = listOf("$SD/DCIM/.thumbnails", "$SD/.thumbnails", "$SD/Pictures/.thumbnails"),
            include = listOf("*"),
            maxDepth = 2,
        ),
        CleanRule(
            id = "regular_download",
            name = "下载残片",
            group = CleanGroup.REGULAR,
            roots = listOf("$SD/Download"),
            include = listOf("*.tmp", "*.part", "*.crdownload", "*.download", "*.!ut"),
            maxDepth = 4,
        ),
        CleanRule(
            id = "regular_tencent",
            name = "腾讯系缓存",
            group = CleanGroup.REGULAR,
            roots = listOf("$SD/tencent", "$SD/Tencent"),
            include = listOf("*.log", "*.tmp", "*.cache", "*.dump"),
            maxDepth = 6,
        ),
    )

    val bigFiles: List<CleanRule> = listOf(
        CleanRule(
            id = "big_file",
            name = "大文件",
            group = CleanGroup.BIG_FILE,
            roots = listOf(SD),
            minSize = 10L * 1024 * 1024,
            excludeDirs = listOf("Android"),
            maxDepth = 10,
        ),
    )

    val apk: List<CleanRule> = listOf(
        CleanRule(
            id = "apk_file",
            name = "安装包",
            group = CleanGroup.APK,
            roots = listOf(SD),
            include = listOf("*.apk", "*.apks", "*.xapk", "*.apkm"),
            excludeDirs = listOf("Android"),
            maxDepth = 10,
        ),
    )

    fun rulesFor(group: CleanGroup): List<CleanRule> = when (group) {
        CleanGroup.REGULAR -> regular
        CleanGroup.BIG_FILE -> bigFiles
        CleanGroup.APK -> apk
        else -> emptyList()
    }
}

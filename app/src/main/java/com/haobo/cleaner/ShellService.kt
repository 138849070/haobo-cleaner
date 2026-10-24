package com.haobo.cleaner

import java.io.File

/**
 * Runs inside a process launched by Shizuku as uid 2000 (shell).
 * Keep it dependency free: no Context, no app resources, no DI.
 *
 * Every method here executes with shell privileges, which is what makes
 * /sdcard/Android/data/<other.package> readable at all.
 */
class ShellService : IShellService.Stub() {

    override fun exec(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            "__EXIT__$code\n$output"
        } catch (t: Throwable) {
            "__EXIT__-1\n${t.javaClass.simpleName}: ${t.message}"
        }
    }

    override fun sizeOf(path: String): Long = FileWalker.sizeOf(path)

    override fun listChildren(path: String): Array<String> =
        File(path).list() ?: emptyArray()

    override fun scanFiles(
        root: String,
        includeGlobs: Array<String>?,
        excludeGlobs: Array<String>?,
        excludeDirNames: Array<String>?,
        minSizeBytes: Long,
        maxDepth: Int,
        maxResults: Int,
    ): Array<String> = FileWalker.walk(
        root = root,
        includeGlobs = includeGlobs?.toList() ?: emptyList(),
        excludeGlobs = excludeGlobs?.toList() ?: emptyList(),
        skipDirNames = excludeDirNames?.toList() ?: emptyList(),
        minSize = minSizeBytes,
        maxDepth = maxDepth,
        maxResults = maxResults,
    ).toTypedArray()

    override fun deletePath(path: String): Boolean = FileWalker.deleteRecursively(path)

    override fun freeBytes(): Long = try {
        android.os.StatFs("/data").availableBytes
    } catch (t: Throwable) {
        0L
    }
}

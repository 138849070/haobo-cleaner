package com.haobo.cleaner

import java.io.File
import java.util.ArrayDeque

/**
 * Shared by both backends:
 *  - DirectBackend  → runs inside the app process, needs MANAGE_EXTERNAL_STORAGE
 *  - ShellService   → runs inside the Shizuku shell process, uid 2000
 *
 * Pure file logic, no Context, no Android framework beyond java.io.
 */
object FileWalker {

    private const val MAX_VISITS = 300_000
    private const val MAX_DEPTH = 48

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    /**
     * @return entries formatted as "size\tabsolutePath"
     */
    fun walk(
        root: String,
        includeGlobs: List<String>,
        excludeGlobs: List<String>,
        skipDirNames: List<String>,
        minSize: Long,
        maxDepth: Int,
        maxResults: Int,
    ): List<String> {
        val rootFile = File(root)
        if (!rootFile.exists()) return emptyList()

        val include = includeGlobs.takeIf { it.isNotEmpty() }?.map { globToRegex(it) }
        val exclude = excludeGlobs.map { globToRegex(it) }
        val skipDirs = skipDirNames.toHashSet().takeIf { it.isNotEmpty() }
        val results = ArrayList<String>(256)

        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(rootFile to 0)
        var visited = 0

        while (stack.isNotEmpty()) {
            if (results.size >= maxResults || visited >= MAX_VISITS) break
            val (current, depth) = stack.removeLast()
            visited++

            if (current.isFile) {
                if (matches(current.name, current.length(), include, exclude, minSize)) {
                    results.add("${current.length()}\t${current.absolutePath}")
                }
                continue
            }
            if (depth >= maxDepth) continue

            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) {
                    visited++
                    if (matches(child.name, child.length(), include, exclude, minSize)) {
                        results.add("${child.length()}\t${child.absolutePath}")
                        if (results.size >= maxResults) break
                    }
                } else {
                    if (skipDirs != null && child.name in skipDirs) continue
                    stack.addLast(child to depth + 1)
                }
            }
        }
        return results
    }

    fun sizeOf(path: String): Long {
        val root = File(path)
        if (!root.exists()) return 0L
        var total = 0L
        var visited = 0
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty() && visited < MAX_VISITS) {
            val (current, depth) = stack.removeLast()
            visited++
            if (current.isFile) {
                total += current.length()
                continue
            }
            if (depth >= MAX_DEPTH) continue
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isFile) {
                    total += child.length()
                    visited++
                } else {
                    stack.addLast(child to depth + 1)
                }
            }
        }
        return total
    }

    fun deleteRecursively(path: String): Boolean {
        val target = File(path)
        if (!target.exists()) return true
        return try {
            target.deleteRecursively()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * A directory counts as empty when it holds no files and every child
     * directory is also empty. Returns deepest paths first so callers can
     * delete in order without leaving parents behind.
     */
    fun findEmptyDirs(root: String, maxDepth: Int, maxResults: Int): List<String> {
        val rootFile = File(root)
        if (!rootFile.isDirectory) return emptyList()

        val allDirs = ArrayList<File>(1024)
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(rootFile to 0)
        while (stack.isNotEmpty() && allDirs.size < MAX_VISITS) {
            val (current, depth) = stack.removeLast()
            if (!current.isDirectory) continue
            allDirs.add(current)
            if (depth >= maxDepth) continue
            current.listFiles()?.forEach { child ->
                if (child.isDirectory) stack.addLast(child to depth + 1)
            }
        }

        val emptyPaths = HashSet<String>()
        val result = ArrayList<String>()
        for (dir in allDirs.sortedByDescending { it.absolutePath.length }) {
            val children = dir.listFiles() ?: continue
            val isEmpty = children.all { it.isDirectory && it.absolutePath in emptyPaths }
            if (isEmpty) {
                emptyPaths.add(dir.absolutePath)
                if (dir.absolutePath != rootFile.absolutePath) {
                    result.add(dir.absolutePath)
                }
            }
        }
        return result.take(maxResults)
    }

    private fun matches(
        name: String,
        size: Long,
        include: List<Regex>?,
        exclude: List<Regex>,
        minSize: Long,
    ): Boolean {
        if (size < minSize) return false
        if (exclude.any { it.matches(name) }) return false
        if (include == null) return true
        return include.any { it.matches(name) }
    }
}

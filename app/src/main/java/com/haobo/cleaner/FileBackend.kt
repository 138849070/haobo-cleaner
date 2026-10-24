package com.haobo.cleaner

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Two ways to touch the filesystem.
 *
 * DirectBackend runs in the app process and needs "All files access"
 * (MANAGE_EXTERNAL_STORAGE). It covers everything except other apps'
 * Android/data subdirectories — Google blocks those for every app,
 * permission or not.
 *
 * ShellBackend goes through Shizuku (uid 2000) and is the only way to
 * read/write Android/data/<other.package>.
 */
interface FileBackend {
    val name: String
    val canReachAppData: Boolean
    val available: Boolean

    suspend fun scanFiles(
        root: String,
        include: List<String>,
        exclude: List<String>,
        excludeDirs: List<String>,
        minSize: Long,
        maxDepth: Int,
        maxResults: Int,
    ): List<String>

    suspend fun sizeOf(path: String): Long

    suspend fun deletePath(path: String): Boolean
}

class DirectBackend(private val context: Context) : FileBackend {

    override val name: String = "本地"

    override val canReachAppData: Boolean = false

    override val available: Boolean
        get() = Environment.isExternalStorageManager()

    override suspend fun scanFiles(
        root: String,
        include: List<String>,
        exclude: List<String>,
        excludeDirs: List<String>,
        minSize: Long,
        maxDepth: Int,
        maxResults: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        FileWalker.walk(root, include, exclude, excludeDirs, minSize, maxDepth, maxResults)
    }

    override suspend fun sizeOf(path: String): Long = withContext(Dispatchers.IO) {
        FileWalker.sizeOf(path)
    }

    override suspend fun deletePath(path: String): Boolean = withContext(Dispatchers.IO) {
        FileWalker.deleteRecursively(path)
    }
}

class ShellBackend(private val shell: ShellClient) : FileBackend {

    override val name: String = "Shizuku"

    override val canReachAppData: Boolean = true

    override val available: Boolean = true

    override suspend fun scanFiles(
        root: String,
        include: List<String>,
        exclude: List<String>,
        excludeDirs: List<String>,
        minSize: Long,
        maxDepth: Int,
        maxResults: Int,
    ): List<String> = shell.scanFiles(
        root, include, exclude, excludeDirs, minSize, maxDepth, maxResults,
    ).toList()

    override suspend fun sizeOf(path: String): Long = shell.sizeOf(path)

    override suspend fun deletePath(path: String): Boolean = shell.deletePath(path)
}

/**
 * Any local directory that only needs normal filesystem access.
 * Kept here so both backends agree on what "local" means.
 */
fun localPath(relative: String): String =
    File(Environment.getExternalStorageDirectory(), relative).absolutePath

package com.haobo.cleaner

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * The loophole Google left open.
 *
 * Android 11 forbids picking Android/data and Android/obb in the SAF picker,
 * but it does NOT forbid picking their parent folder "Android". A tree grant
 * on "Android" is recursive, so data/ and obb/ come along with it.
 *
 * Works on Android 11–12. Android 13+ patched it, which is why Shizuku stays
 * around as a fallback.
 */
object Saf {
    const val AUTHORITY = "com.android.externalstorage.documents"
    const val INITIAL_URI = "content://com.android.externalstorage.documents/document/primary%3AAndroid"
    const val TREE_ID = "primary:Android"
}

class SafBackend(private val context: Context) {

    val granted: Boolean
        get() = treeUri() != null

    fun treeUri(): Uri? = context.contentResolver.persistedUriPermissions
        .asSequence()
        .map { it.uri }
        .firstOrNull { uri ->
            val decoded = Uri.decode(uri.toString())
            decoded.contains("/tree/") && decoded.contains(Saf.TREE_ID, ignoreCase = true)
        }

    fun requestIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(Saf.INITIAL_URI))
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
    }

    /**
     * Scans Android/data/<pkg>/{cache,logs,code_cache} through the SAF tree.
     * Returns items whose `path` is a content:// URI, not a filesystem path.
     */
    suspend fun scanAppData(onStage: (String) -> Unit): List<JunkItem> =
        withContext(Dispatchers.IO) {
            val tree = treeUri() ?: return@withContext emptyList()
            val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
            val data = root.findFile("data") ?: return@withContext emptyList()

            val pm = context.packageManager
            val items = mutableListOf<JunkItem>()
            for (pkgDir in data.listFiles()) {
                val pkg = pkgDir.name ?: continue
                onStage(pkg)
                for (kind in KINDS) {
                    val dir = pkgDir.findFile(kind) ?: continue
                    if (!dir.isDirectory) continue
                    val size = sizeOf(dir)
                    if (size > 0) {
                        items += JunkItem(
                            path = dir.uri.toString(),
                            label = "${labelOf(pm, pkg)} · $kind",
                            size = size,
                            group = CleanGroup.APP,
                            ruleName = kind,
                        )
                    }
                }
            }
            items.sortedByDescending { it.size }
        }

    suspend fun delete(uriString: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                Uri.parse(uriString),
            )
        }.getOrDefault(false)
    }

    /** Directories in Android/data whose package is no longer installed. */
    suspend fun scanResidual(installed: Set<String>): List<JunkItem> =
        withContext(Dispatchers.IO) {
            val tree = treeUri() ?: return@withContext emptyList()
            val root = DocumentFile.fromTreeUri(context, tree) ?: return@withContext emptyList()
            val data = root.findFile("data") ?: return@withContext emptyList()

            val items = mutableListOf<JunkItem>()
            for (dir in data.listFiles()) {
                val pkg = dir.name ?: continue
                if (!pkg.contains('.') || pkg in installed) continue
                val size = sizeOf(dir)
                if (size > 0) {
                    items += JunkItem(
                        path = dir.uri.toString(),
                        label = pkg,
                        size = size,
                        group = CleanGroup.RESIDUAL,
                        ruleName = "已卸载",
                    )
                }
            }
            items.sortedByDescending { it.size }
        }

    private fun sizeOf(dir: DocumentFile): Long {
        var total = 0L
        var visited = 0
        val stack = ArrayDeque<DocumentFile>()
        stack.addLast(dir)
        while (stack.isNotEmpty() && visited < MAX_VISITS) {
            val current = stack.removeLast()
            visited++
            if (current.isFile) {
                total += current.length()
                continue
            }
            for (child in current.listFiles()) {
                if (child.isFile) {
                    total += child.length()
                    visited++
                } else {
                    stack.addLast(child)
                }
            }
        }
        return total
    }

    private fun labelOf(pm: PackageManager, pkg: String): String = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private companion object {
        val KINDS = listOf("cache", "logs", "code_cache")
        const val MAX_VISITS = 200_000
    }
}

package com.haobo.cleaner

import android.content.Context

/**
 * User-maintained whitelist. Matching paths are skipped when scanning and
 * refused when deleting, so an accidental tap can't destroy something the
 * user marked as important.
 */
class Exclusions(context: Context) {

    private val prefs = context.getSharedPreferences("exclusions", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun add(path: String) {
        val next = all().toMutableSet()
        next.add(path.trim())
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun remove(path: String) {
        val next = all().toMutableSet()
        next.remove(path)
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun contains(path: String): Boolean {
        val list = all()
        if (list.isEmpty()) return false
        return list.any { path == it || path.startsWith(it.trimEnd('/') + "/") }
    }

    fun filterOut(items: List<JunkItem>): List<JunkItem> = items.filterNot { contains(it.path) }

    private companion object {
        const val KEY = "paths"
    }
}

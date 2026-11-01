package com.agentic.binning.move

import java.util.Locale

data class LayoutProblems(
    /** Binned items whose file isn't where the database says. */
    val missing: List<String>,
    /** Files in a category folder that no binned item points to. */
    val unexpected: List<String>,
) {
    val ok: Boolean get() = missing.isEmpty() && unexpected.isEmpty()
}

/**
 * Step 3 of the file-move engine (Save and Bin Files): after the moves, every binned item's path
 * must exist, and the category folders must hold nothing else. On any problem the engine stops
 * and moves nothing more.
 *
 * Two kinds of file are allowed in the folders without a binned item: anything under `.binning/`
 * (database, journal, thumbnails), and [ignored] paths. Those are files of items removed from the
 * project after they were binned. Removing forgets the item but leaves its file where it is
 * (decision Q2, Oct 31, 2026).
 */
object LayoutCheck {
    /**
     * [categoryFolders] are the folder names (not paths) of categories that have one;
     * [filesOnDisk] are absolute paths of every file found under those folders.
     */
    fun check(
        projectDir: String,
        categoryFolders: Set<String>,
        binnedPaths: Collection<String>,
        filesOnDisk: Collection<String>,
        ignored: Collection<String>,
    ): LayoutProblems {
        val root = projectDir.trimEnd('/') + "/"
        val folders = categoryFolders.mapTo(HashSet()) { it.lower() }
        fun inCategoryFolder(path: String): Boolean {
            if (!path.lower().startsWith(root.lower())) return false
            val rel = path.substring(root.length)
            val first = rel.substringBefore('/', missingDelimiterValue = "")
            return first.isNotEmpty() && first != ".binning" && first.lower() in folders
        }
        val disk = filesOnDisk.filter(::inCategoryFolder)
        val diskKeys = disk.mapTo(HashSet()) { it.lower() }
        val binnedKeys = binnedPaths.mapTo(HashSet()) { it.lower() }
        val ignoredKeys = ignored.mapTo(HashSet()) { it.lower() }
        return LayoutProblems(
            missing = binnedPaths.filter { it.lower() !in diskKeys }.sorted(),
            unexpected = disk.filter { it.lower() !in binnedKeys && it.lower() !in ignoredKeys }.sorted(),
        )
    }

    private fun String.lower() = lowercase(Locale.ROOT)
}

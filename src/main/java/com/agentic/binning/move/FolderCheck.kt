package com.agentic.binning.move

/** One item of the project as the folder check sees it. */
data class CheckItem(val path: String, val binned: Boolean)

/**
 * Step 3 of Bin Files and Save, also run before planning (B5): the category folders must hold the
 * binned items' files and nothing unknown.
 * - [LayoutProblems.missing]: binned items whose file is gone. The caller marks them Missing; they
 *   never block.
 * - [LayoutProblems.unexpected]: files in a category folder that no item points to (binned or not)
 *   and that weren't removed from the project or left there by the user (Q2). These block until
 *   the user adds them or leaves them.
 *
 * Hidden files (a name starting with ".") are never media and are ignored (ruling, handoff ledger).
 */
object FolderCheck {
    fun check(
        projectDir: String,
        categoryNames: Collection<String>,
        items: List<CheckItem>,
        removed: Collection<String>,
        filesOnDisk: Collection<String>,
    ): LayoutProblems {
        val root = projectDir.trimEnd('/') + "/"
        val visible = filesOnDisk.filterNot { path ->
            path.startsWith(root, ignoreCase = true) && path.substring(root.length).split('/').any { it.startsWith(".") }
        }
        val binned = items.filter { it.binned }.map { it.path }
        val others = items.filter { !it.binned }.map { it.path }
        return LayoutCheck.check(
            projectDir = projectDir,
            categoryFolders = categoryNames.mapTo(LinkedHashSet()) { MovePlanner.folderFor(it) },
            binnedPaths = binned,
            filesOnDisk = visible,
            ignored = removed + others,
        )
    }

    /** True when [problems] must stop Bin Files before anything moves: only unknown files block (B5). */
    fun blocks(problems: LayoutProblems): Boolean = problems.unexpected.isNotEmpty()

    /** Every file under each category folder, for [check]'s `filesOnDisk`. [list] lists one folder recursively. */
    fun categoryFiles(projectDir: String, categoryNames: Collection<String>, list: (dir: String) -> List<String>): List<String> {
        val root = projectDir.trimEnd('/')
        return categoryNames.map { MovePlanner.folderFor(it) }.distinct().flatMap { list("$root/$it") }
    }
}

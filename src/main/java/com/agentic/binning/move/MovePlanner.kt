package com.agentic.binning.move

import com.agentic.binning.project.ProjectFolders
import java.util.Locale

/** One item whose file should end up at `<project>/<folder>/<baseName><extension>`. */
data class MoveRequest(
    val itemId: Long,
    /** Absolute path of the file now. */
    val source: String,
    /** The category's name or its existing folder name; made safe here. */
    val folder: String,
    /** The friendly name; made safe here. Falls back to the source file's own name if it is empty. */
    val baseName: String,
    /** The original extension, with or without the dot (".jpg", "MP4"); kept as it is. */
    val extension: String,
)

data class PlannedMove(val itemId: Long, val source: String, val dest: String)

/**
 * Step 1 of the file-move engine shared by Save and Bin Files: works out every destination path
 * before anything moves. It never overwrites. A name already taken, on disk or earlier in the
 * same plan, gets " 2", " 3" and so on.
 *
 * Shared storage on Android is case-insensitive, so names are compared ignoring case. A file whose
 * target differs from its path only in case counts as already in place and isn't moved.
 * Every existing path counts as taken, including files that will move away later in the plan, so
 * the result doesn't depend on the order the moves run in.
 */
object MovePlanner {
    fun plan(projectDir: String, requests: List<MoveRequest>, existingPaths: Set<String>): List<PlannedMove> {
        val root = projectDir.trimEnd('/')
        val onDisk = existingPaths.mapTo(HashSet()) { it.lower() }
        val reserved = HashSet<String>()
        val sorted = requests.sortedBy { it.itemId }

        // Files already at their target keep their name, including a " 2"-style suffix they got
        // earlier: renaming them back once the plain name is free would be a move for nothing.
        // Plan them first so nothing else takes their name.
        val inPlace = HashSet<Long>()
        for (r in sorted) {
            if (isInPlace(root, r)) {
                inPlace += r.itemId
                reserved += r.source.lower()
            }
        }

        val moves = ArrayList<PlannedMove>()
        for (r in sorted) {
            if (r.itemId in inPlace) continue
            var n = 1
            var dest = target(root, r, n)
            while (dest.lower() in reserved || (dest.lower() in onDisk && dest.lower() != r.source.lower())) {
                n++
                dest = target(root, r, n)
            }
            reserved += dest.lower()
            if (dest.lower() != r.source.lower()) moves += PlannedMove(r.itemId, r.source, dest)
        }
        return moves
    }

    /** The folder name for a category, safe on Android's shared storage. */
    fun folderFor(categoryName: String): String =
        ProjectFolders.folderNameFor(categoryName).ifEmpty { "Category" }

    private fun target(root: String, r: MoveRequest, n: Int): String {
        val base = ProjectFolders.folderNameFor(r.baseName).ifEmpty { sourceStem(r.source) }
        val ext = extensionOf(r)
        val name = if (n == 1) base else "$base $n"
        return "$root/${folderFor(r.folder)}/$name$ext"
    }

    private fun isInPlace(root: String, r: MoveRequest): Boolean {
        val plain = target(root, r, 1)
        if (plain.lower() == r.source.lower()) return true
        val folder = plain.substringBeforeLast('/')
        if (r.source.substringBeforeLast('/').lower() != folder.lower()) return false
        val ext = extensionOf(r)
        val base = plain.substringAfterLast('/').dropLast(ext.length)
        val suffixed = Regex(Regex.escape(base) + " ([2-9]|[1-9][0-9]+)" + Regex.escape(ext), RegexOption.IGNORE_CASE)
        return suffixed.matches(r.source.substringAfterLast('/'))
    }

    private fun extensionOf(r: MoveRequest): String =
        r.extension.trim().let { if (it.isEmpty() || it.startsWith(".")) it else ".$it" }

    private fun sourceStem(path: String): String =
        path.substringAfterLast('/').substringBeforeLast('.').ifEmpty { "Item" }

    private fun String.lower() = lowercase(Locale.ROOT)
}

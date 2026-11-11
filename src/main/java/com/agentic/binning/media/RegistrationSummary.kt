package com.agentic.binning.media

import java.text.NumberFormat
import java.util.Locale

/** Running totals of a registration run, rendered as the one-line summary in the project sheet. */
/** A file another project holds, left out of a batch until the user chooses to copy it (P8). */
data class HeldFile(val path: String, val project: String, val listed: Boolean)

data class RegistrationSummary(
    val added: Int = 0,
    val duplicates: Int = 0,
    /** Count of files held per other project, in first-seen order. */
    val heldByProject: Map<String, Int> = emptyMap(),
    val unreadable: Int = 0,
    /** Picked items that never became candidates (unresolvable or unsupported); shown as unreadable. */
    val skipped: Int = 0,
    /** Each file another project holds, for the notice that offers to copy them (P8). */
    val held: List<HeldFile> = emptyList(),
) {
    operator fun plus(outcome: RegistrationOutcome): RegistrationSummary = when (outcome) {
        is RegistrationOutcome.Added -> copy(added = added + 1)
        is RegistrationOutcome.DuplicateInProject -> copy(duplicates = duplicates + 1)
        is RegistrationOutcome.HeldByOtherProject ->
            copy(heldByProject = heldByProject + (outcome.projectName to (heldByProject[outcome.projectName] ?: 0) + 1), held = held + HeldFile(outcome.path, outcome.projectName, outcome.listed))
        is RegistrationOutcome.Unreadable -> copy(unreadable = unreadable + 1)
    }

    fun text(locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getIntegerInstance(locale)
        val parts = buildList {
            if (added > 0) add("${format.format(added)} added")
            if (duplicates > 0) add("${format.format(duplicates)} already in this project")
            val held = heldByProject.values.sum()
            if (held > 0) {
                val names = heldByProject.keys.joinToString(", ")
                add(
                    if (heldByProject.size == 1) {
                        "${format.format(held)} in another project ($names)"
                    } else {
                        "${format.format(held)} in other projects ($names)"
                    },
                )
            }
            val unread = unreadable + skipped
            if (unread > 0) add("${format.format(unread)} couldn't be read")
        }
        return if (parts.isEmpty()) "No photos or videos found" else parts.joinToString(" · ")
    }
}

fun registrationProgressText(progress: RegistrationProgress, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getIntegerInstance(locale)
    return "Adding ${format.format(progress.done)} of ${format.format(progress.total)}…"
}

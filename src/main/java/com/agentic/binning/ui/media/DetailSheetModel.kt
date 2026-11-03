package com.agentic.binning.ui.media

import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.projectmedia.CategoryChoice
import com.agentic.binning.projectmedia.ItemDetail
import com.agentic.binning.projectmedia.canTakeACategory
import com.agentic.binning.projectmedia.isWaiting
import com.agentic.binning.ui.analyze.ActionRow
import com.agentic.binning.ui.analyze.actionTimeChip
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A category chosen in the picker and not saved yet. */
sealed interface CategoryPick {
    data class Existing(val id: Long, val name: String) : CategoryPick

    /** Made at Save (through the clash check), as the user's. */
    data class New(val name: String) : CategoryPick
}

/** The detail sheet's unsaved edits (Q6: Cancel or closing drops them). [pick] null keeps the category. */
data class DetailDraft(
    val name: String,
    val pick: CategoryPick?,
    val unlockName: Boolean,
    val unlockCategory: Boolean,
) {
    companion object {
        fun of(d: ItemDetail) = DetailDraft(d.item.friendlyName.orEmpty(), pick = null, unlockName = false, unlockCategory = false)
    }
}

enum class MarkerKind {
    /** The model's value. */
    FromSorting,

    /** Locked (Q5), with an Unlock link. */
    SetByYou,

    /** Unlocked in this draft: sorting may change it after Save (P2), with Undo. */
    WillUnlock,

    /** Edited in this draft: "Set by you when you save". */
    PendingYours,

    /** A waiting item's category: "Not placed yet". */
    NotPlaced,
}

/** The pill beside a field's label, and its link ("Unlock" or "Undo") if any. */
data class LockMarker(val kind: MarkerKind, val link: String?)

enum class StatusBadge { Sorted, Waiting }

data class Callout(val text: String, val warn: Boolean)

data class DetailUi(
    val id: Long,
    val title: String,
    val badge: StatusBadge,
    val isVideo: Boolean,
    val duration: String?,
    val name: String,
    val nameMarker: LockMarker,
    /** "File name when binned: …" and, when it differs, the model's suggestion. */
    val nameHint: String,
    /** The draft's category name; null shows "Choose a category". */
    val categoryLabel: String?,
    val categoryIsNew: Boolean,
    val categoryMarker: LockMarker,
    val callout: Callout,
    val description: String,
    val actions: List<ActionRow>,
    val tags: List<String>,
    val details: List<Pair<String, String>>,
    val saveEnabled: Boolean,
)

/** What Save sends to `ProjectMediaRepository.saveItem`. */
data class SaveRequest(
    val id: Long,
    val name: String,
    val category: CategoryChoice?,
    val unlockName: Boolean,
    val unlockCategory: Boolean,
)

private const val STORAGE_ROOT = "/storage/emulated/0/"
private val TAKEN = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

private fun ItemDetail.nameChanged(d: DetailDraft) = d.name.trim() != item.friendlyName.orEmpty()

private fun ItemDetail.categoryChanged(d: DetailDraft) = when (val p = d.pick) {
    null -> false
    is CategoryPick.Existing -> p.id != item.categoryId
    is CategoryPick.New -> true
}

/** An unlock only counts when the value itself is untouched: an edit locks it again. */
fun saveRequest(d: ItemDetail, draft: DetailDraft): SaveRequest {
    val catChanged = d.categoryChanged(draft)
    return SaveRequest(
        id = d.item.id,
        name = draft.name.trim(),
        category = if (!catChanged) {
            null
        } else {
            when (val p = draft.pick) {
                is CategoryPick.Existing -> CategoryChoice.Existing(p.id)
                is CategoryPick.New -> CategoryChoice.New(p.name)
                null -> null
            }
        },
        unlockName = draft.unlockName && !d.nameChanged(draft) && d.item.nameSetByUser,
        unlockCategory = draft.unlockCategory && !catChanged && d.item.categorySetByUser,
    )
}

private fun marker(locked: Boolean, changed: Boolean, unlocked: Boolean) = when {
    changed -> LockMarker(MarkerKind.PendingYours, null)
    locked && !unlocked -> LockMarker(MarkerKind.SetByYou, "Unlock")
    locked -> LockMarker(MarkerKind.WillUnlock, "Undo")
    else -> LockMarker(MarkerKind.FromSorting, null)
}

/** "DCIM/Camera/IMG_1.jpg" for a file on shared storage, else the full path. */
private fun shown(path: String) = path.removePrefix(STORAGE_ROOT)

/** The detail sheet (mockup state 3) for [d] under [draft]. */
fun detailUi(d: ItemDetail, draft: DetailDraft, zone: ZoneId): DetailUi {
    val item = d.item
    val waiting = isWaiting(item.status, item.categoryId, item.categorySetByUser)
    val nameChanged = d.nameChanged(draft)
    val catChanged = d.categoryChanged(draft)
    val ext = if (item.kind == ItemKind.Video) ".mp4" else ".jpg"
    val fileName = draft.name.trim().ifEmpty { item.friendlyName ?: item.originalName.substringBeforeLast('.') }
    val suggestion = d.suggestedName?.takeIf { it.isNotBlank() && it != draft.name.trim() }
    val pickName = when (val p = draft.pick) {
        is CategoryPick.Existing -> p.name
        is CategoryPick.New -> p.name
        null -> null
    }
    val request = saveRequest(d, draft)
    val folder = shown(item.currentPath).substringBeforeLast('/', missingDelimiterValue = "")
    return DetailUi(
        id = item.id,
        title = if (waiting) "Waiting to be categorized" else "Item details",
        badge = if (waiting) StatusBadge.Waiting else StatusBadge.Sorted,
        isVideo = item.kind == ItemKind.Video,
        duration = item.durationMs?.takeIf { item.kind == ItemKind.Video }?.let(ProjectMediaViewModel::durationText),
        name = draft.name,
        nameMarker = marker(item.nameSetByUser, nameChanged, draft.unlockName),
        nameHint = "File name when binned: $fileName$ext" + (suggestion?.let { " · The model suggested “$it”" } ?: ""),
        categoryLabel = pickName ?: d.categoryName,
        categoryIsNew = draft.pick is CategoryPick.New,
        categoryMarker = if (waiting && !catChanged) {
            LockMarker(MarkerKind.NotPlaced, null)
        } else {
            marker(item.categorySetByUser, catChanged, draft.unlockCategory)
        },
        callout = if (waiting) {
            Callout(
                "Sorting couldn't place this item. It tries again on the next run, or you can choose a category now. " +
                    "Bin Files waits until it has one.",
                warn = true,
            )
        } else {
            Callout("Not binned yet. The file stays in ${folder.ifEmpty { "its folder" }} until Bin Files.", warn = false)
        },
        description = d.description ?: "No analysis. Name and category were set by hand.",
        actions = d.actions.map { ActionRow(actionTimeChip(it.t, item.durationMs), it.text) },
        tags = d.tags,
        details = buildList {
            d.shotType?.let { add("Shot type" to ProjectMediaViewModel.shotLabel(it)) }
            item.takenAt?.let { add("Taken" to Instant.ofEpochMilli(it).atZone(zone).format(TAKEN)) }
            if (item.width != null && item.height != null) add("Size" to "${item.width} × ${item.height}")
            add("Original file" to item.originalName)
            add("Location" to shown(item.currentPath))
            d.categoryDescription?.takeIf { it.isNotBlank() && !catChanged }?.let { add("Category note" to it) }
        },
        saveEnabled = draft.name.isNotBlank() &&
            (nameChanged || catChanged || request.unlockName || request.unlockCategory),
    )
}

/** Whether a tap on a tile with [status] opens the detail sheet; failed and missing items open [opensFailedSheet]'s. */
fun opensDetail(status: ItemStatus): Boolean = canTakeACategory(status)

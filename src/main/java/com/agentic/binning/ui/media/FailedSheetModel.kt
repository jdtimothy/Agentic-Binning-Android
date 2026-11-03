package com.agentic.binning.ui.media

import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.pipeline.FailureReasons
import com.agentic.binning.projectmedia.CategoryChoice
import com.agentic.binning.projectmedia.ItemDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The failed item sheet (mockup state 8): why it failed, and Retry, Set by hand or Remove. */
data class FailedUi(
    val id: Long,
    val title: String,
    /** "Failed · decode" over the picture. */
    val badge: String,
    val isVideo: Boolean,
    val duration: String?,
    val fileName: String,
    val reason: String,
    val details: List<Pair<String, String>>,
    val canRetry: Boolean,
    val canSetByHand: Boolean,
    /** The Set name and category by hand form is open. */
    val manual: Boolean,
    val name: String,
    val nameHint: String,
    /** The draft's category name; null shows "Choose a category". */
    val categoryLabel: String?,
    val categoryIsNew: Boolean,
    val callout: String,
    val saveEnabled: Boolean,
)

/** What the form's Save sends to `ProjectMediaRepository.setByHand`: both values locked (Q19). */
data class SetByHandRequest(val id: Long, val name: String, val category: CategoryChoice)

private const val STORAGE_ROOT = "/storage/emulated/0/"
private val TAKEN = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.US)

/** Failed and missing items open the failed item sheet instead of the detail sheet. */
fun opensFailedSheet(status: ItemStatus): Boolean = status == ItemStatus.Failed || status == ItemStatus.Missing

/** The form's Save, or null until it has a name and a category. */
fun setByHandRequest(d: ItemDetail, draft: DetailDraft): SetByHandRequest? {
    val name = draft.name.trim().ifEmpty { return null }
    val category = when (val p = draft.pick) {
        is CategoryPick.Existing -> CategoryChoice.Existing(p.id)
        is CategoryPick.New -> CategoryChoice.New(p.name)
        null -> return null
    }
    return SetByHandRequest(d.item.id, name, category)
}

/** The failed item sheet for [d]; [manual] shows the by-hand form under [draft]. */
fun failedUi(d: ItemDetail, draft: DetailDraft, manual: Boolean, zone: ZoneId): FailedUi {
    val item = d.item
    val missing = item.status == ItemStatus.Missing
    val isVideo = item.kind == ItemKind.Video
    val noun = if (isVideo) "video" else "photo"
    val tries = when (item.attempts) {
        0 -> ""
        1 -> " Tried once."
        else -> " Tried ${item.attempts} times."
    }
    val reason = when {
        missing -> "The file is no longer at this location, so it can't be analyzed."
        item.failureReason == FailureReasons.DECODE ->
            "The phone couldn't decode this $noun, so the model never saw it. The file may be damaged or use a format " +
                "the phone's decoders can't read.$tries"
        else -> FailureReasons.describe(item.failureReason) + tries
    }
    val ext = if (isVideo) ".mp4" else ".jpg"
    val pick = draft.pick
    return FailedUi(
        id = item.id,
        title = if (missing) "File missing" else "Couldn't analyze",
        badge = when {
            missing -> "Missing"
            item.failureReason.isNullOrBlank() -> "Failed"
            else -> "Failed · ${item.failureReason}"
        },
        isVideo = isVideo,
        duration = item.durationMs?.takeIf { isVideo }?.let(ProjectMediaViewModel::durationText),
        fileName = item.originalName,
        reason = reason,
        details = buildList {
            item.takenAt?.let { add("Taken" to Instant.ofEpochMilli(it).atZone(zone).format(TAKEN)) }
            add("Location" to item.currentPath.removePrefix(STORAGE_ROOT))
            add("Bin Files" to "Not blocked. This item stays where it is.")
        },
        canRetry = !missing,
        canSetByHand = !missing,
        manual = manual && !missing,
        name = draft.name,
        nameHint = "File name when binned: ${draft.name.trim().ifEmpty { "…" }}$ext",
        categoryLabel = when (pick) {
            is CategoryPick.Existing -> pick.name
            is CategoryPick.New -> pick.name
            null -> null
        },
        categoryIsNew = pick is CategoryPick.New,
        callout = "With a name and category the ${if (isVideo) "clip" else "photo"} is binned like any other at the next " +
            "Bin Files. It has no description or tags, so search finds it only by name and category.",
        saveEnabled = setByHandRequest(d, draft) != null,
    )
}

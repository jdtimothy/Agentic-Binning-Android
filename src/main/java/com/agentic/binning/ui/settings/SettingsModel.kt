package com.agentic.binning.ui.settings

import com.agentic.binning.analysis.VisionBudget
import com.agentic.binning.model.DownloadState
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.model.ModelSpec
import com.agentic.binning.settings.ApiKeyInfo
import com.agentic.binning.settings.MediaPickerChoice
import com.agentic.binning.settings.SortingModelChoice
import kotlin.math.roundToInt

// The Settings screen's pure pieces: sorting model rows, the API key row, the frames and picker hints.

/** One sorting model row. [spec] is the on-device model to download, null for Claude. */
data class SortingOption(val choice: SortingModelChoice, val title: String, val spec: ModelSpec?)

val SortingOptions: List<SortingOption> = listOf(
    SortingOption(SortingModelChoice.Claude, "Claude (cloud)", spec = null),
    SortingOption(SortingModelChoice.Lfm3_1_7B, "LFM3-1.7B (on device)", ModelCatalog.LFM3_1_7B),
)

/** The on-device model behind [choice], or null for a cloud one. */
fun specOf(choice: SortingModelChoice): ModelSpec? = SortingOptions.first { it.choice == choice }.spec

/** The line under a sorting row's title; [progress] fills a bar while downloading. */
data class RowStatus(val text: String, val progress: Float? = null, val canRetry: Boolean = false)

private const val CLAUDE_SUBTITLE = "Sends descriptions and tags, never photos"

/** Claude says what it is sent; a local model shows its size, then the download, then "Downloaded". */
fun sortingRowStatus(choice: SortingModelChoice, download: DownloadState?): RowStatus {
    val spec = specOf(choice) ?: return RowStatus(CLAUDE_SUBTITLE)
    return when (download) {
        null, DownloadState.Absent -> RowStatus(spec.downloadSize.orEmpty())
        is DownloadState.Downloading -> RowStatus("Downloading · ${(download.fraction * 100).roundToInt()}%", progress = download.fraction)
        DownloadState.Ready -> RowStatus("Downloaded")
        // The code and message from GenieX are logged by ModelDownloader, not shown.
        is DownloadState.Failed -> RowStatus(DOWNLOAD_FAILED_TEXT, canRetry = true)
    }
}

const val DOWNLOAD_FAILED_TEXT = "Download failed. Check your connection and try again."

/**
 * What tapping a sorting row does. Only [Select] and a `select` flag pick a model, which also marks
 * the choice as the user's own; tapping the model already in use never does.
 */
sealed interface SortingPick {
    data object Nothing : SortingPick

    data class Select(val choice: SortingModelChoice) : SortingPick

    /** Confirm with [title], then download [choice]'s model, selecting it first if [select]. */
    data class AskDownload(val choice: SortingModelChoice, val title: String, val select: Boolean) : SortingPick

    /** Bring the key field into view and ask for a key ([NEED_KEY_TEXT]); select Claude first if [select]. */
    data class NeedKey(val select: Boolean) : SortingPick
}

const val SAVE_KEY_FAILED_TEXT = "Couldn't save the key. Try again."

/** The key field: the typed [text] stays until a save succeeds; [error] shows under it after a failed one. */
data class KeyFieldState(val text: String, val error: String? = null) {
    fun edit(text: String): KeyFieldState = KeyFieldState(text)

    fun afterSave(ok: Boolean): KeyFieldState = if (ok) KeyFieldState("") else copy(error = SAVE_KEY_FAILED_TEXT)
}

/**
 * Tapping [choice] while [current] is the sorting model. A local model that isn't downloaded (or
 * whose download failed) asks before downloading; Claude without a saved key asks for one. While
 * [download] or [hasKey] (null until the key is read) is unknown, the tap is a plain select. The
 * model already in use is never selected again.
 */
fun pickSorting(choice: SortingModelChoice, current: SortingModelChoice, download: DownloadState?, hasKey: Boolean?): SortingPick {
    val select = choice != current
    val spec = specOf(choice)
    val ask = when {
        spec == null -> hasKey == false
        else -> download == DownloadState.Absent || download is DownloadState.Failed
    }
    return when {
        !ask -> if (select) SortingPick.Select(choice) else SortingPick.Nothing
        spec == null -> SortingPick.NeedKey(select)
        else -> SortingPick.AskDownload(choice, "Download ${spec.displayName}" + (spec.downloadSize?.let { " ($it)" } ?: "") + "?", select)
    }
}

const val NEED_KEY_TEXT = "Add your API key to use Claude"
const val KEY_HELPER_TEXT = "Stored encrypted on this phone. Only used for sorting."

/** "Key saved (…abcd)", or just "Key saved" when the key is too short to show any of it. */
fun keySavedText(info: ApiKeyInfo): String = info.last4?.let { "Key saved (…$it)" } ?: "Key saved"

fun canSaveKey(text: String): Boolean = text.isNotBlank()

/** Under the frame chips: what the cap does to a 1-minute clip. */
fun frameHint(cap: Int): String = VisionBudget.hint(cap)

fun mediaPickerLabel(choice: MediaPickerChoice): String = when (choice) {
    MediaPickerChoice.AndroidPhotoPicker -> "Android"
    MediaPickerChoice.SamsungGallery -> "Samsung Gallery"
}

fun mediaPickerHint(choice: MediaPickerChoice): String = when (choice) {
    MediaPickerChoice.AndroidPhotoPicker -> "Android's photo picker. Up to 100 at a time."
    MediaPickerChoice.SamsungGallery -> "The Gallery app's picker. Up to 500 at a time."
}

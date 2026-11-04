package com.agentic.binning.ui.settings

import com.agentic.binning.analysis.VisionBudget
import com.agentic.binning.model.DownloadState
import com.agentic.binning.settings.ApiKeyInfo
import com.agentic.binning.settings.FRAME_CAP_OPTIONS
import com.agentic.binning.settings.MediaPickerChoice
import com.agentic.binning.settings.SortingModelChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelTest {

    @Test fun sortingOptions_labels() {
        assertEquals(listOf(SortingModelChoice.Claude, SortingModelChoice.Lfm3_1_7B), SortingOptions.map { it.choice })
        assertEquals(listOf("Claude (cloud)", "LFM3-1.7B (on device)"), SortingOptions.map { it.title })
    }

    @Test fun rowStatus_claude_saysWhatIsSent() {
        assertEquals(RowStatus("Sends descriptions and tags, never photos"), sortingRowStatus(SortingModelChoice.Claude, null))
    }

    @Test fun rowStatus_localModel_followsTheDownload() {
        val lfm = SortingModelChoice.Lfm3_1_7B
        assertEquals(RowStatus("1.05 GB"), sortingRowStatus(lfm, null))
        assertEquals(RowStatus("1.05 GB"), sortingRowStatus(lfm, DownloadState.Absent))
        assertEquals(RowStatus("Downloading · 42%", progress = 0.42f), sortingRowStatus(lfm, DownloadState.Downloading(0.42f)))
        assertEquals(RowStatus("Downloaded"), sortingRowStatus(lfm, DownloadState.Ready))
        // The raw code and message stay in the log.
        val failed = sortingRowStatus(lfm, DownloadState.Failed("Download failed (-3): no network"))
        assertEquals("Download failed. Check your connection and try again.", failed.text)
        assertTrue(failed.canRetry)
        assertFalse(sortingRowStatus(lfm, DownloadState.Ready).canRetry)
    }

    private val claude = SortingModelChoice.Claude
    private val lfm = SortingModelChoice.Lfm3_1_7B

    @Test fun pick_localModelNotDownloaded_asksFirst() {
        val ask = SortingPick.AskDownload(lfm, "Download LFM3-1.7B (1.05 GB)?", select = true)
        assertEquals(ask, pickSorting(lfm, current = claude, DownloadState.Absent, hasKey = false))
        assertEquals(ask, pickSorting(lfm, current = claude, DownloadState.Failed("x"), hasKey = false))
        assertEquals(SortingPick.Select(lfm), pickSorting(lfm, current = claude, DownloadState.Ready, hasKey = false))
        assertEquals(SortingPick.Select(lfm), pickSorting(lfm, current = claude, DownloadState.Downloading(0.1f), hasKey = false))
        // Unknown yet: selected; a second tap asks once the state is known.
        assertEquals(SortingPick.Select(lfm), pickSorting(lfm, current = claude, null, hasKey = false))
    }

    @Test fun pick_selectedLocalModelNotDownloaded_offersTheDownloadWithoutReselecting() {
        assertEquals(
            SortingPick.AskDownload(lfm, "Download LFM3-1.7B (1.05 GB)?", select = false),
            pickSorting(lfm, current = lfm, DownloadState.Absent, hasKey = null),
        )
    }

    @Test fun pick_alreadySelected_doesNothing() {
        assertEquals(SortingPick.Nothing, pickSorting(lfm, current = lfm, DownloadState.Ready, hasKey = false))
        assertEquals(SortingPick.Nothing, pickSorting(lfm, current = lfm, DownloadState.Downloading(0.5f), hasKey = false))
        assertEquals(SortingPick.Nothing, pickSorting(lfm, current = lfm, null, hasKey = false))
        assertEquals(SortingPick.Nothing, pickSorting(claude, current = claude, null, hasKey = true))
        assertEquals(SortingPick.Nothing, pickSorting(claude, current = claude, null, hasKey = null))
    }

    @Test fun pick_claudeWithoutKey_asksForTheKey() {
        assertEquals(SortingPick.NeedKey(select = true), pickSorting(claude, current = lfm, null, hasKey = false))
        // Already Claude: still asks for the key, without picking it again.
        assertEquals(SortingPick.NeedKey(select = false), pickSorting(claude, current = claude, null, hasKey = false))
        assertEquals(SortingPick.Select(claude), pickSorting(claude, current = lfm, null, hasKey = true))
        assertEquals("Add your API key to use Claude", NEED_KEY_TEXT)
    }

    @Test fun pick_claudeBeforeTheKeyIsRead_isAPlainSelect() {
        assertEquals(SortingPick.Select(claude), pickSorting(claude, current = lfm, null, hasKey = null))
    }

    @Test fun keyField_keepsTheTextUntilTheSaveSucceeds() {
        val typed = KeyFieldState("sk-ant-test-0000")
        assertEquals(KeyFieldState("sk-ant-test-0000", error = "Couldn't save the key. Try again."), typed.afterSave(ok = false))
        assertEquals(KeyFieldState(""), typed.afterSave(ok = true))
        // Editing clears the error.
        assertEquals(KeyFieldState("sk-ant-test-00001"), typed.afterSave(ok = false).edit("sk-ant-test-00001"))
    }

    @Test fun keySaved_showsLast4_orNothingForAShortKey() {
        assertEquals("Key saved (…abcd)", keySavedText(ApiKeyInfo("abcd")))
        assertEquals("Key saved", keySavedText(ApiKeyInfo(null)))
        assertEquals("Stored encrypted on this phone. Only used for sorting.", KEY_HELPER_TEXT)
        assertTrue(canSaveKey(" sk-ant-test-0000 "))
        assertFalse(canSaveKey("   "))
    }

    @Test fun frameHint_inSettings() {
        assertEquals("1-minute clip: 16 frames, one every 3.8 s", frameHint(16))
        assertEquals("1-minute clip: 32 frames, one every 1.9 s", frameHint(32))
        FRAME_CAP_OPTIONS.forEach { assertEquals(VisionBudget.hint(it), frameHint(it)) }
    }

    @Test fun mediaPicker_labelsAndHints() {
        assertEquals("Android", mediaPickerLabel(MediaPickerChoice.AndroidPhotoPicker))
        assertEquals("Samsung Gallery", mediaPickerLabel(MediaPickerChoice.SamsungGallery))
        assertEquals("Android's photo picker. Up to 100 at a time.", mediaPickerHint(MediaPickerChoice.AndroidPhotoPicker))
        assertEquals("The Gallery app's picker. Up to 500 at a time.", mediaPickerHint(MediaPickerChoice.SamsungGallery))
    }
}

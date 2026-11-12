package com.agentic.binning.ui.analyze

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.DownloadState
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AnalyzeMappingTest {
    private val trip = ProjectRef("Trip", "/sdcard/Agentic Binning/Trip")
    private val other = ProjectRef("Other", "/sdcard/Agentic Binning/Other")

    private fun card(
        id: Long,
        status: ItemStatus,
        kind: ItemKind = ItemKind.Photo,
        durationMs: Long? = null,
        failureReason: String? = null,
        categoryName: String? = null,
    ) = CardItem(
        id = id,
        name = "$id",
        kind = kind,
        status = status,
        meta = "",
        duration = null,
        durationMs = durationMs,
        image = File("/x/$id.jpg"),
        imageIsVideo = false,
        failureReason = failureReason,
        categoryName = categoryName,
    )

    private fun state(
        phase: PipelinePhase,
        project: ProjectRef? = trip,
        currentItemId: Long? = null,
        partial: PartialAnalysis? = null,
        profile: VisionProfile? = VisionProfile("eos", 500.0, 400, 77, 40.4),
    ) = PipelineState(
        phase = phase,
        project = project,
        currentItemId = currentItemId,
        partial = partial,
        lastProfile = profile,
        pending = 0,
        analyzed = 0,
        total = 0,
    )

    @Test fun idleFocus_isLastAnalyzed_skippingFailed() {
        val cards = listOf(card(1, ItemStatus.Analyzed), card(2, ItemStatus.Analyzed), card(3, ItemStatus.Failed), card(4, ItemStatus.Pending))
        assertEquals(1, focusIndex(cards, liveId = null))
    }

    @Test fun idleFocus_fallsBackToLastDone_thenFirst() {
        assertEquals(1, focusIndex(listOf(card(1, ItemStatus.Failed), card(2, ItemStatus.Missing), card(3, ItemStatus.Pending)), null))
        assertEquals(0, focusIndex(listOf(card(1, ItemStatus.Pending), card(2, ItemStatus.Pending)), null))
        assertEquals(-1, focusIndex(emptyList(), null))
    }

    @Test fun reviewFocus_staysOnSelectedClipWhileAnalysisMovesOn() {
        val cards = listOf(card(1, ItemStatus.Analyzed), card(2, ItemStatus.Analyzing), card(3, ItemStatus.Pending))
        assertEquals(0, focusIndex(cards, liveId = 2, selectedId = 1))
        assertEquals(0, focusIndex(cards, liveId = 3, selectedId = 1))
        assertEquals(1, focusIndex(cards, liveId = 2, selectedId = 99))
    }

    @Test fun liveFocus_isTheCurrentItem() {
        val cards = listOf(card(1, ItemStatus.Analyzed), card(2, ItemStatus.Analyzing), card(3, ItemStatus.Pending))
        assertEquals(1, focusIndex(cards, liveId = 2))
    }

    @Test fun liveState_isOnlyTheOpenProjects() {
        val running = state(PipelinePhase.Describing)
        assertSame(running, liveState(running, trip))
        // After a switch the old run's state (or the reset Idle with no project) is not live.
        assertNull(liveState(running, other))
        assertNull(liveState(state(PipelinePhase.Idle, project = null), other))
        assertNull(liveState(running, null))
    }

    @Test fun status_encodingUntilFirstToken_thenDescribing() {
        val video = card(5, ItemStatus.Analyzing, ItemKind.Video, 8000)
        val photo = card(6, ItemStatus.Analyzing)
        val pending = listOf(ItemStatus.Analyzing)
        assertEquals("Encoding frames", statusOf(true, pending, state(PipelinePhase.Describing, currentItemId = 5), video).first)
        assertEquals("Encoding frames", statusOf(true, pending, state(PipelinePhase.Preparing, currentItemId = 5), video).first)
        assertEquals("Reading image", statusOf(true, pending, state(PipelinePhase.Describing, currentItemId = 6), photo).first)
        val streaming = PartialAnalysis("A red", false, emptyList(), emptyList())
        assertEquals("Describing", statusOf(true, pending, state(PipelinePhase.Describing, currentItemId = 5, partial = streaming), video).first)
    }

    /** A Stop is remembered in the database; after a cold launch nothing is live but the row must say Stopped. */
    @Test fun status_stoppedByUserAfterColdLaunch() {
        val waiting = listOf(ItemStatus.Analyzed, ItemStatus.Pending)
        assertEquals("Stopped" to StatusTone.Quiet, statusOf(true, waiting, null, null, stoppedByUser = true))
        assertEquals("Stopped" to StatusTone.Quiet, statusOf(true, waiting, state(PipelinePhase.Idle), null, stoppedByUser = true))
        // Nothing waiting: a remembered Stop changes nothing.
        assertEquals("All caught up", statusOf(true, listOf(ItemStatus.Analyzed), null, null, stoppedByUser = true).first)
        assertEquals("No media yet", statusOf(true, emptyList(), null, null, stoppedByUser = true).first)
        // Lifted again by Resume or adding media.
        assertEquals("Waiting to analyze", statusOf(true, waiting, null, null, stoppedByUser = false).first)
        // A live run wins over the remembered flag.
        assertEquals("Describing", statusOf(true, waiting, state(PipelinePhase.Describing, partial = PartialAnalysis("A", false, emptyList(), emptyList())), null, stoppedByUser = true).first)
    }

    @Test fun status_whenNotLive_describesTheProject() {
        assertEquals("All caught up", statusOf(true, listOf(ItemStatus.Analyzed, ItemStatus.Failed), null, null).first)
        assertEquals("Waiting to analyze", statusOf(true, listOf(ItemStatus.Analyzed, ItemStatus.Pending), null, null).first)
        assertEquals("No media yet", statusOf(true, emptyList(), null, null).first)
        assertEquals("No project open", statusOf(false, emptyList(), null, null).first)
    }

    @Test fun speed_isTheLastReplys_hiddenUntilOneIsMeasured() {
        // Shown while items are analyzed, including while the next reply streams (it is the previous reply's speed).
        val streaming = PartialAnalysis("A red", false, emptyList(), emptyList())
        assertEquals("40 tok/s", speedText(state(PipelinePhase.Describing, currentItemId = 7, partial = streaming)))
        assertEquals("40 tok/s", speedText(state(PipelinePhase.Preparing, currentItemId = 8)))
        assertEquals("", speedText(state(PipelinePhase.LoadingModel, profile = null)))
        // Not live (e.g. another project's run, or right after a switch): nothing measured here.
        assertEquals("", speedText(null))
    }

    /** No model is generating: the last speed would only crowd the status row. */
    @Test fun speed_hiddenWhenNoModelIsGenerating() {
        for (phase in listOf(PipelinePhase.Idle, PipelinePhase.Stopped, PipelinePhase.Paused, PipelinePhase.Releasing, PipelinePhase.LoadingModel)) {
            assertEquals(phase.name, "", speedText(state(phase)))
        }
    }

    @Test fun output_hidesTimeChipsPastTheClipEnd() {
        val clip = card(8, ItemStatus.Analyzing, ItemKind.Video, 3400)
        val partial = PartialAnalysis("Waves.", true, listOf(ActionEntry("0:01", "Wave breaks"), ActionEntry("0:06", "Gull lands")), emptyList())
        val out = outputOf(true, false, clip, state(PipelinePhase.Describing, currentItemId = 8, partial = partial), null)
        assertEquals(listOf(ActionRow("0:01", "Wave breaks"), ActionRow(null, "Gull lands")), out.actions)
        assertEquals(8L, out.itemId)
    }

    // --- Final review fixes ---

    /** M4: the status row stays short; the run's error goes to the output card. */
    @Test fun stoppedWithError_statusRowSaysStopped() {
        val stopped = state(PipelinePhase.Stopped).copy(error = "The vision model failed: Generation failed: QNN error 1002")
        assertEquals("Stopped" to StatusTone.Quiet, statusOf(true, listOf(ItemStatus.Pending), stopped, null))
    }

    @Test fun stoppedWithError_outputCardShowsItInFull_overAnyCard() {
        val error = "Analysis stopped after repeated failures: Couldn't read this file."
        val stopped = state(PipelinePhase.Stopped).copy(error = error)
        val stored = StoredAnalysis(1, "A pier at dusk.", emptyList(), listOf("pier"))

        val analyzed = outputOf(true, false, card(1, ItemStatus.Analyzed), stopped, stored)
        assertEquals(error, analyzed.runError)
        assertEquals("A pier at dusk.", analyzed.description)

        val pending = outputOf(true, false, card(2, ItemStatus.Pending), stopped, null)
        assertEquals(error, pending.runError)
    }

    @Test fun noRunError_unlessStoppedWithOne() {
        val card = card(1, ItemStatus.Pending)
        assertNull(outputOf(true, false, card, state(PipelinePhase.Stopped), null).runError)
        assertNull(outputOf(true, false, card, state(PipelinePhase.Idle).copy(error = "old"), null).runError)
        assertNull(outputOf(true, false, card, null, null).runError)
    }

    /** M5: failure codes read as plain sentences. */
    @Test fun failureReasons_areWordedForPeople() {
        fun failure(reason: String?) = outputOf(true, false, card(1, ItemStatus.Failed, failureReason = reason), null, null).failure
        assertEquals("Couldn't read this file.", failure("decode"))
        assertEquals("The model's reply was cut off.", failure("length"))
        assertEquals("The model's reply wasn't readable.", failure("invalid-json"))
        assertEquals("Couldn't build the model's prompt for this file.", failure("prompt"))
        assertEquals("The vision model failed on this file.", failure("model"))
        assertEquals("Stopped here last time the app closed unexpectedly.", failure("interrupted"))
        assertEquals("Couldn't analyze this file.", failure("something-new"))
        assertEquals("Couldn't analyze this file.", failure(null))
        assertEquals("The file is missing.", outputOf(true, false, card(1, ItemStatus.Missing), null, null).failure)
    }

    @Test fun controls_whileAnalyzing_pauseIsOffered() {
        val c = controlsOf(state(PipelinePhase.Describing), hasProject = true, hasPending = true, mediaCount = 3)
        assertEquals(ControlsState(canStop = true, showResume = false, canToggle = true, mediaCount = 3), c)
    }

    @Test fun controls_whilePaused_resumeIsOffered() {
        val c = controlsOf(state(PipelinePhase.Paused), hasProject = true, hasPending = true, mediaCount = 3)
        assertEquals(ControlsState(canStop = true, showResume = true, canToggle = true, mediaCount = 3), c)
    }

    @Test fun controls_whileCategorizing_pauseIsDisabled_stopIsNot() {
        val c = controlsOf(state(PipelinePhase.Categorizing), hasProject = true, hasPending = false, mediaCount = 3)
        assertEquals(ControlsState(canStop = true, showResume = false, canToggle = false, mediaCount = 3), c)
    }

    @Test fun controls_whenIdle_resumeOnlyWithWaitingItems() {
        assertEquals(
            ControlsState(canStop = false, showResume = true, canToggle = true, mediaCount = 2),
            controlsOf(state(PipelinePhase.Idle), hasProject = true, hasPending = true, mediaCount = 2),
        )
        assertEquals(
            ControlsState(canStop = false, showResume = true, canToggle = false, mediaCount = 2),
            controlsOf(null, hasProject = true, hasPending = false, mediaCount = 2),
        )
    }

    /** A project analyzed in full but not categorized (or stopped while categorizing) can be started. */
    @Test fun controls_whenIdle_resumeWithOnlyUncategorizedItems() {
        val expected = ControlsState(canStop = false, showResume = true, canToggle = true, mediaCount = 46)
        assertEquals(expected, controlsOf(null, hasProject = true, hasPending = false, mediaCount = 46, uncategorized = 46))
        assertEquals(expected, controlsOf(state(PipelinePhase.Stopped), hasProject = true, hasPending = false, mediaCount = 46, uncategorized = 13))
        assertEquals(expected, controlsOf(state(PipelinePhase.Idle), hasProject = true, hasPending = false, mediaCount = 46, uncategorized = 10))
        assertEquals(false, controlsOf(null, hasProject = true, hasPending = false, mediaCount = 46, uncategorized = 0).canToggle)
        assertEquals(false, controlsOf(null, hasProject = false, hasPending = false, mediaCount = 0, uncategorized = 3).canToggle)
        // Still no Pause while categorizing.
        assertEquals(false, controlsOf(state(PipelinePhase.Categorizing), hasProject = true, hasPending = false, mediaCount = 46, uncategorized = 46).canToggle)
    }

    @Test fun modelChip_showsTheSortingModelWhileCategorizing() {
        val lfm = modelChipOf(state(PipelinePhase.Categorizing), DownloadState.Ready, SortingModelChoice.Lfm3_1_7B)
        assertEquals(ModelChipState("LFM3-1.7B", "NPU", ChipTone.Quiet), lfm)
        val claude = modelChipOf(state(PipelinePhase.Categorizing), DownloadState.Ready, SortingModelChoice.Claude)
        assertEquals(ModelChipState("Claude", "Cloud", ChipTone.Quiet), claude)
    }

    @Test fun modelChip_showsTheVisionModelOtherwise() {
        for (phase in listOf(PipelinePhase.Describing, PipelinePhase.Idle, PipelinePhase.Stopped, PipelinePhase.Releasing)) {
            assertEquals(phase.name, ModelChipState("LFM3-VL-2B", "NPU", ChipTone.Quiet), modelChipOf(state(phase), DownloadState.Ready, SortingModelChoice.Claude))
        }
        assertEquals(ModelChipState("LFM3-VL-2B", "NPU", ChipTone.Quiet), modelChipOf(null, DownloadState.Ready, SortingModelChoice.Lfm3_1_7B))
        assertEquals(
            ModelChipState("LFM3-VL-2B", "42%", ChipTone.Accent, downloadFraction = 0.42f),
            modelChipOf(state(PipelinePhase.LoadingModel), DownloadState.Downloading(0.42f), null),
        )
        assertEquals(ModelChipState("LFM3-VL-2B", "Download failed", ChipTone.Warn), modelChipOf(null, DownloadState.Failed("x"), null))
        assertEquals(ModelChipState("LFM3-VL-2B", "Not downloaded", ChipTone.Quiet), modelChipOf(null, DownloadState.Absent, null))
        assertEquals(ModelChipState("LFM3-VL-2B", "", ChipTone.Quiet), modelChipOf(null, null, null))
        // Settings not read yet: the vision model until it is known.
        assertEquals("LFM3-VL-2B", modelChipOf(state(PipelinePhase.Categorizing), DownloadState.Ready, null).name)
    }

    @Test fun badge_sortedShowsItsCategory() {
        assertEquals(CardBadge("Fire Performance", BadgeKind.Done), badgeOf(card(1, ItemStatus.Sorted, categoryName = "Fire Performance")))
        assertEquals(CardBadge("Sorted", BadgeKind.Done), badgeOf(card(1, ItemStatus.Sorted)))
        assertEquals(CardBadge("Beach", BadgeKind.Done), badgeOf(card(1, ItemStatus.Binned, categoryName = "Beach")))
        assertEquals(CardBadge("Analyzed", BadgeKind.Done), badgeOf(card(1, ItemStatus.Analyzed)))
        assertEquals(CardBadge("Failed", BadgeKind.Problem), badgeOf(card(1, ItemStatus.Failed)))
        assertEquals(CardBadge("Missing", BadgeKind.Problem), badgeOf(card(1, ItemStatus.Missing)))
        assertNull(badgeOf(card(1, ItemStatus.Pending)))
        assertNull(badgeOf(card(1, ItemStatus.Analyzing)))
    }

    @Test fun status_categorizing() {
        val s = state(PipelinePhase.Categorizing).copy(sortDone = 40, sortTotal = 120)
        val statuses = listOf(ItemStatus.Analyzed, ItemStatus.Sorted)
        assertEquals("Categorizing" to StatusTone.Active, statusOf(true, statuses, s, null))
        // The progress goes in the counts slot, and the vision model's speed is hidden meanwhile.
        assertEquals("40 of 120", countTextOf(true, statuses, s))
        assertEquals("", speedText(s))
        // Before the categorizer reports a total, the counts slot keeps the analyzed count.
        assertEquals("2 of 2 analyzed", countTextOf(true, statuses, state(PipelinePhase.Categorizing)))
    }

    @Test fun countText_analyzedAndFailed() {
        val statuses = listOf(ItemStatus.Analyzed, ItemStatus.Failed, ItemStatus.Pending)
        assertEquals("1 of 3 analyzed · 1 failed", countTextOf(true, statuses, null))
        assertEquals("", countTextOf(false, emptyList(), null))
    }

    @Test fun status_idleWithUncategorized() {
        val done = listOf(ItemStatus.Analyzed, ItemStatus.Sorted)
        // Short enough to fit beside "26 of 28 analyzed · 2 failed" at phone width.
        assertEquals(
            "12 to categorize" to StatusTone.Quiet,
            statusOf(true, done, state(PipelinePhase.Idle), null, uncategorized = 12),
        )
        // Also after a restart, with nothing live.
        assertEquals("12 to categorize" to StatusTone.Quiet, statusOf(true, done, null, null, uncategorized = 12))
        // Items still to analyze come first; nothing uncategorized is all caught up.
        assertEquals("Waiting to analyze", statusOf(true, done + ItemStatus.Pending, null, null, uncategorized = 12).first)
        assertEquals("All caught up", statusOf(true, done, null, null, uncategorized = 0).first)
    }

    @Test fun output_showsSortNotice() {
        val c = card(1, ItemStatus.Analyzed)
        val stored = StoredAnalysis(1, "A beach.", emptyList(), listOf("beach"))
        val notice = "Download the sorting model in Settings"
        val idle = state(PipelinePhase.Idle).copy(sortNotice = notice)
        val out = outputOf(true, false, c, idle, stored, uncategorized = 12)
        assertEquals(notice, out.sortNotice)
        assertEquals("A beach.", out.description)
        // Not once everything is categorized, nor while a run is going.
        assertNull(outputOf(true, false, c, idle, stored, uncategorized = 0).sortNotice)
        assertNull(outputOf(true, false, c, state(PipelinePhase.Categorizing).copy(sortNotice = notice), stored, uncategorized = 12).sortNotice)
        // Kept across a Stop, beside the run's own error.
        val stopped = state(PipelinePhase.Stopped).copy(sortNotice = notice, error = "The disk is full")
        val both = outputOf(true, false, c, stopped, stored, uncategorized = 12)
        assertEquals(notice, both.sortNotice)
        assertEquals("The disk is full", both.runError)
    }

    /** M4: after a cold launch or a project switch, the last run's stored notice stands in. */
    @Test fun output_fallsBackToTheStoredSortNotice() {
        val c = card(1, ItemStatus.Analyzed)
        val saved = "No connection; categorizing will try again after the next run"
        assertEquals(saved, outputOf(true, false, c, null, null, uncategorized = 3, storedNotice = saved).sortNotice)
        assertEquals(saved, outputOf(true, false, c, state(PipelinePhase.Idle), null, uncategorized = 3, storedNotice = saved).sortNotice)
        // The live notice wins; nothing shows while a run goes or once everything is categorized.
        val live = state(PipelinePhase.Idle).copy(sortNotice = "Download the sorting model in Settings")
        assertEquals("Download the sorting model in Settings", outputOf(true, false, c, live, null, uncategorized = 3, storedNotice = saved).sortNotice)
        assertNull(outputOf(true, false, c, state(PipelinePhase.Categorizing), null, uncategorized = 3, storedNotice = saved).sortNotice)
        assertNull(outputOf(true, false, c, null, null, uncategorized = 0, storedNotice = saved).sortNotice)
    }

    @Test fun frameStrip_upTo16Frames_oneTickEach() {
        assertEquals(listOf(1f, 1f, 1f, 0f, 0f), frameStripFills(5, 3))
        assertEquals(16, frameStripFills(16, 0).size)
        assertEquals(listOf(0f), frameStripFills(0, 0))
    }

    @Test fun frameStrip_64Frames_groupedInto16Ticks() {
        val fills = frameStripFills(64, 10)
        assertEquals(16, fills.size)
        // 4 frames a tick: two full ticks, one half full, the rest empty.
        assertEquals(listOf(1f, 1f, 0.5f) + List(13) { 0f }, fills)
        assertEquals(List(16) { 1f }, frameStripFills(64, 64))
    }

    @Test fun frameStrip_24Frames_fillsInProportion() {
        val fills = frameStripFills(24, 12)
        assertEquals(16, fills.size)
        assertEquals(8f, fills.sum(), 0.001f)
        assertEquals(1f, fills.first(), 0f)
        assertEquals(0f, fills.last(), 0f)
    }
}

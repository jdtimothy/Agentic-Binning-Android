package com.agentic.binning.ui.analyze

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.pipeline.PipelinePhase
import com.agentic.binning.pipeline.PipelineState
import com.agentic.binning.project.ProjectRef
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

    @Test fun status_whenNotLive_describesTheProject() {
        assertEquals("All caught up", statusOf(true, listOf(ItemStatus.Analyzed, ItemStatus.Failed), null, null).first)
        assertEquals("Waiting to analyze", statusOf(true, listOf(ItemStatus.Analyzed, ItemStatus.Pending), null, null).first)
        assertEquals("No media yet", statusOf(true, emptyList(), null, null).first)
        assertEquals("No project open", statusOf(false, emptyList(), null, null).first)
    }

    @Test fun speed_isTheLastReplys_hiddenUntilOneIsMeasured() {
        // Shown throughout, including while the next reply streams (it is the previous reply's speed).
        val streaming = PartialAnalysis("A red", false, emptyList(), emptyList())
        assertEquals("40 tok/s", speedText(state(PipelinePhase.Describing, currentItemId = 7, partial = streaming)))
        assertEquals("40 tok/s", speedText(state(PipelinePhase.Idle)))
        assertEquals("", speedText(state(PipelinePhase.LoadingModel, profile = null)))
        // Not live (e.g. another project's run, or right after a switch): nothing measured here.
        assertEquals("", speedText(null))
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
}

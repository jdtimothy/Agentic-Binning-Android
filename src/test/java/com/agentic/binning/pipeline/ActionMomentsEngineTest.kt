package com.agentic.binning.pipeline

import com.agentic.binning.analysis.ActionEntry
import com.agentic.binning.analysis.AnalysisParser
import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionReply
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.model.VisionModelException
import com.agentic.binning.db.ItemStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ActionMomentsEngineTest {
    @Test fun video_preservesFullFrameMainCallAndStoresTimestampedMoments() = runTest {
        val media = video()
        val model = MomentModel()
        val store = FakeStore(listOf(item(1, kind = ItemKind.Video)))
        val preparer = MomentPreparer(media)
        val partials = mutableListOf<PartialAnalysis>()
        val engine = AnalysisEngine(model, preparer, store, 16)

        val outcome = engine.analyze(item(1, kind = ItemKind.Video), partials::add)

        val expected = listOf(ActionEntry("0:00", "A person stands"), ActionEntry("0:06", "A wave breaks"))
        assertEquals(expected, (outcome as ItemOutcome.Analyzed).result.actions)
        assertEquals(listOf(media), model.mainCalls)
        assertEquals(media.imagePaths, model.momentCalls)
        assertEquals(expected, store.saved.getValue(1).actions)
        assertEquals(expected, AnalysisParser.parse(store.savedRaw.getValue(1), ItemKind.Video)!!.actions)
        assertTrue(partials.any { it.actions == expected.take(1) && it.tags == listOf("red", "square", "graphic") })
        assertEquals(expected, partials.last().actions)
        assertEquals(listOf(1L), preparer.cleaned)
    }

    @Test fun staticVideo_makesNoMomentCalls() = runTest {
        val fixture = Fixture(video().copy(frameChanges = listOf(0.0, 0.0)))
        val result = fixture.analyze() as ItemOutcome.Analyzed
        assertTrue(result.result.actions.isEmpty())
        assertTrue(fixture.model.momentCalls.isEmpty())
        assertEquals(1, fixture.model.mainCalls.size)
    }

    @Test fun absentOrMisalignedMotionData_neverGuessesFrameTimes() = runTest {
        for (media in listOf(
            video().copy(frameChanges = emptyList()),
            video().copy(frameChanges = listOf(40.0)),
            video().copy(frameTimesMs = listOf(0L)),
        )) {
            val fixture = Fixture(media)
            assertTrue((fixture.analyze() as ItemOutcome.Analyzed).result.actions.isEmpty())
            assertTrue(fixture.model.momentCalls.isEmpty())
        }
    }

    @Test fun photo_keepsExactRawAndNeverRequestsMoments() = runTest {
        val raw = "  $VALID_JSON\n"
        val fixture = Fixture(video().copy(kind = ItemKind.Photo))
        fixture.model.mainReply = reply(raw)
        fixture.analyze()
        assertEquals(raw, fixture.store.savedRaw[1])
        assertTrue(fixture.model.momentCalls.isEmpty())
    }

    @Test fun repeatedMoments_clearLoneActionFromFinalPartialAndRaw() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, _ -> reply("A person stands.") }
        val result = fixture.analyze() as ItemOutcome.Analyzed
        assertTrue(result.result.actions.isEmpty())
        assertTrue(fixture.partials.any { it.actions.size == 1 })
        assertTrue(fixture.partials.last().actions.isEmpty())
        assertTrue(AnalysisParser.parse(fixture.store.savedRaw.getValue(1), ItemKind.Video)!!.actions.isEmpty())
    }

    @Test fun momentLength_retriesSameImageOnceWithoutPublishingTruncatedPhrase() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, call -> when (call) {
            1 -> reply("A truncated phrase", "length")
            2 -> reply("A person stands.")
            else -> reply("A wave breaks.")
        } }
        val result = fixture.analyze() as ItemOutcome.Analyzed
        assertEquals(listOf("A person stands", "A wave breaks"), result.result.actions.map { it.text })
        assertEquals(listOf(video().imagePaths[0], video().imagePaths[0], video().imagePaths[1]), fixture.model.momentCalls)
        assertFalse(fixture.partials.any { p -> p.actions.any { "truncated" in it.text } })
        assertEquals(1, fixture.model.mainCalls.size)
    }

    @Test fun repeatedMomentLength_skipsUnusableMomentInsteadOfFailingItem() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, call ->
            if (call <= 2) reply("A truncated phrase", "length") else reply("A wave breaks.")
        }
        val result = fixture.analyze() as ItemOutcome.Analyzed
        assertTrue(result.result.actions.isEmpty()) // Only one usable moment is not a sequence.
        assertEquals(3, fixture.model.momentCalls.size)
        assertEquals(ItemStatus.Analyzed, fixture.store.status(1))
    }

    @Test fun invalidMoment_isSkippedWithoutSavingBlankPhrase() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, call -> reply(if (call == 1) "" else "A wave breaks.") }
        val result = fixture.analyze() as ItemOutcome.Analyzed
        assertTrue(result.result.actions.isEmpty())
        assertEquals(2, fixture.model.momentCalls.size)
    }

    @Test fun userStoppedMoment_cancelsWithoutSavingOrCallingNextMoment() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, _ -> reply("Partial", "user") }
        assertEquals(ItemOutcome.Cancelled, fixture.analyze())
        assertTrue(fixture.store.saved.isEmpty())
        assertEquals(1, fixture.model.momentCalls.size)
        assertEquals("user", fixture.engine.lastProfile!!.stopReason)
        assertEquals(listOf(1L), fixture.preparer.cleaned)
    }

    @Test fun cancelledMoment_propagatesAndCleansWorkFiles() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, _ -> throw CancellationException("Stopped") }
        try {
            fixture.analyze()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertTrue(fixture.store.saved.isEmpty())
        assertEquals(1, fixture.model.momentCalls.size)
        assertEquals(listOf(1L), fixture.preparer.cleaned)
    }

    @Test fun momentModelFailure_stopsRunWithoutFailingItem() = runTest {
        val fixture = Fixture()
        val failure = VisionModelException("NPU failed")
        fixture.model.moment = { _, _ -> throw failure }
        try {
            fixture.analyze()
            fail("Expected run to stop")
        } catch (e: StopRunException) {
            assertEquals(FailureReasons.MODEL_FAILED, e.message)
            assertSame(failure, e.cause)
        }
        assertEquals(ItemStatus.Pending, fixture.store.status(1))
        assertTrue(fixture.store.saved.isEmpty())
        assertEquals(listOf(1L), fixture.preparer.cleaned)
    }

    @Test fun momentPromptFailure_failsItemWithoutMainRetry() = runTest {
        val fixture = Fixture()
        fixture.model.moment = { _, _ -> throw IllegalStateException("Bad media markers") }
        assertEquals(ItemOutcome.Failed(FailureReasons.PROMPT), fixture.analyze())
        assertEquals(1, fixture.model.mainCalls.size)
        assertTrue(fixture.store.saved.isEmpty())
        assertEquals(listOf(1L), fixture.preparer.cleaned)
    }

    @Test fun lastProfile_tracksLatestMomentButSavedProfileRemainsMainCall() = runTest {
        val fixture = Fixture()
        val mainProfile = reply(VALID_JSON).profile.copy(generatedTokens = 101)
        val momentProfile = reply("A person stands.").profile.copy(generatedTokens = 7)
        fixture.model.mainReply = VisionReply(VALID_JSON, mainProfile)
        fixture.model.moment = { _, call ->
            VisionReply(if (call == 1) "A person stands." else "A wave breaks.", momentProfile)
        }
        fixture.analyze()
        assertEquals(momentProfile, fixture.engine.lastProfile)
        assertEquals(mainProfile, fixture.store.profile)
    }

    @Test fun logs_includeMomentFrameTimesAndTotalItemWallClock() = runTest {
        val messages = mutableListOf<String>()
        val fixture = Fixture(log = PipelineLog { message, _ -> messages += message })
        fixture.analyze()
        assertTrue(messages.toString(), messages.any { it.contains("400") })
        assertTrue(messages.toString(), messages.any { it.contains("5600") })
        assertTrue(messages.toString(), messages.any { it.contains("total", ignoreCase = true) && it.contains("ms") })
    }

    private inner class Fixture(media: PreparedMedia = video(), log: PipelineLog = PipelineLog.None) {
        val target = item(1, kind = media.kind)
        val model = MomentModel()
        val backingStore = FakeStore(listOf(target))
        val store = ProfileStore(backingStore)
        val preparer = MomentPreparer(media)
        val partials = mutableListOf<PartialAnalysis>()
        val engine = AnalysisEngine(model, preparer, store, 16, log = log)
        suspend fun analyze() = engine.analyze(target, partials::add)
    }

    private class ProfileStore(val backing: FakeStore) : AnalysisStore by backing {
        var profile: VisionProfile? = null
        val saved get() = backing.saved
        val savedRaw get() = backing.savedRaw
        fun status(id: Long) = backing.status(id)
        override suspend fun save(itemId: Long, result: com.agentic.binning.analysis.AnalysisResult, raw: String, modelId: String, profile: VisionProfile) {
            this.profile = profile
            backing.save(itemId, result, raw, modelId, profile)
        }
    }

    private fun video() = PreparedMedia(
        ItemKind.Video, listOf("/work/1/first.jpg", "/work/1/last.jpg"),
        listOf(400L, 5_600L), "/thumb.jpg", frameChanges = listOf(0.0, 40.0),
    )

    private class MomentPreparer(private val media: PreparedMedia) : Preparer {
        val cleaned = mutableListOf<Long>()
        override fun prepare(item: ItemEntity, maxFrames: Int, onFrame: (Int, Int) -> Unit) = media
        override fun cleanup(itemId: Long) { cleaned += itemId }
    }

    private class MomentModel : VisionModel {
        override val modelId = "Fake/Moments"
        override val imageEdge = 512
        val mainCalls = mutableListOf<PreparedMedia>()
        val momentCalls = mutableListOf<String>()
        var mainReply = reply(VALID_JSON)
        var moment: suspend (String, Int) -> VisionReply = { _, call ->
            reply(if (call == 1) "A person stands." else "A wave breaks.")
        }
        override suspend fun load(maxFrames: Int) = Unit
        override suspend fun generate(media: PreparedMedia, onToken: (String) -> Unit): VisionReply {
            mainCalls += media
            onToken(mainReply.text)
            return mainReply
        }
        override suspend fun describeMoment(imagePath: String): VisionReply {
            momentCalls += imagePath
            return moment(imagePath, momentCalls.size)
        }
        override suspend fun cancel() = Unit
        override suspend fun release() = Unit
    }
}

package com.agentic.binning.pipeline

import com.agentic.binning.analysis.PartialAnalysis
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModelException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AnalysisEngineTest {
    private val model = FakeModel()

    private fun engine(store: FakeStore, preparer: FakePreparer = FakePreparer()) = AnalysisEngine(model, preparer, store)

    @Test
    fun validReply_isSaved() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        val partials = mutableListOf<PartialAnalysis>()

        val outcome = engine(store).analyze(store.items.getValue(1)) { partials += it }

        assertTrue(outcome is ItemOutcome.Analyzed)
        val result = (outcome as ItemOutcome.Analyzed).result
        assertEquals("A red square on white.", result.description)
        assertEquals("Red square", result.suggestedName)
        assertEquals(result, store.saved[1])
        assertEquals(VALID_JSON, store.savedRaw[1])
        assertEquals(model.modelId, store.savedModelId[1])
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(1, model.generateCalls)
        assertEquals("A red square on white.", partials.last().description)
        assertEquals(listOf("red", "square", "graphic"), partials.last().tags)
    }

    @Test
    fun lengthStop_retriesOnce_thenSucceeds() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.script(1, reply("""{"description":"A red square on white, with a very long""", "length"), reply(VALID_JSON))

        val outcome = engine(store).analyze(store.items.getValue(1)) {}

        assertTrue(outcome is ItemOutcome.Analyzed)
        assertEquals(2, model.generateCalls)
        assertEquals(ItemStatus.Analyzed, store.status(1))
        assertEquals(VALID_JSON, store.savedRaw[1])
    }

    @Test
    fun lengthTwice_marksFailed_length() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.script(1, reply("""{"description":"abc""", "length"), reply("""{"description":"abd""", "length"))

        val outcome = engine(store).analyze(store.items.getValue(1)) {}

        assertEquals(ItemOutcome.Failed("length"), outcome)
        assertEquals(2, model.generateCalls)
        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("length", store.reason(1))
    }

    @Test
    fun invalidJsonTwice_marksFailed_invalidJson() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.script(1, reply("I see a red square."), reply("""{"description": ""}"""))

        val outcome = engine(store).analyze(store.items.getValue(1)) {}

        assertEquals(ItemOutcome.Failed("invalid-json"), outcome)
        assertEquals(2, model.generateCalls)
        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("invalid-json", store.reason(1))
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun missingFile_marksMissing() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))

        val outcome = engine(store, FakePreparer(missing = setOf(1))).analyze(store.items.getValue(1)) {}

        assertEquals(ItemOutcome.Missing, outcome)
        assertEquals(ItemStatus.Missing, store.status(1))
        assertEquals(0, model.generateCalls)
    }

    @Test
    fun preparerFailure_marksFailed_andContinues() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing), item(2, ItemStatus.Analyzing)))
        val engine = engine(store, FakePreparer(undecodable = setOf(1)))

        val first = engine.analyze(store.items.getValue(1)) {}
        val second = engine.analyze(store.items.getValue(2)) {}

        assertEquals(ItemOutcome.Failed("decode"), first)
        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("decode", store.reason(1))
        assertTrue(second is ItemOutcome.Analyzed)
        assertEquals(1, model.generateCalls)
    }

    @Test
    fun cleanup_alwaysCalled() = runTest {
        val store = FakeStore((1L..5L).map { item(it, ItemStatus.Analyzing) })
        val preparer = FakePreparer(missing = setOf(2), undecodable = setOf(3))
        val engine = engine(store, preparer)
        model.script(4, reply("nope"), reply("still nope"))

        engine.analyze(store.items.getValue(1)) {} // analyzed
        engine.analyze(store.items.getValue(2)) {} // missing
        engine.analyze(store.items.getValue(3)) {} // decode
        engine.analyze(store.items.getValue(4)) {} // invalid-json
        model.failure = CancellationException("stopped")
        try {
            engine.analyze(store.items.getValue(5)) {}
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
        }

        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), preparer.cleaned)
    }

    /** Prompt assembly and the marker check throw IllegalStateException; that fails the item, not the batch. */
    @Test
    fun promptAssemblyFailure_marksFailed_prompt() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.failure = IllegalStateException("Prompt has 0 media markers for 1 images")

        val outcome = engine(store).analyze(store.items.getValue(1)) {}

        assertEquals(ItemOutcome.Failed("prompt"), outcome)
        assertEquals(ItemStatus.Failed, store.status(1))
        assertEquals("prompt", store.reason(1))
    }

    /** CancellationException is an IllegalStateException; it must not be mistaken for a prompt failure. */
    @Test
    fun cancellation_propagates_andLeavesStatusAlone() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.failure = CancellationException("stopped")

        try {
            engine(store).analyze(store.items.getValue(1)) {}
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
        }

        assertEquals(ItemStatus.Analyzing, store.status(1))
        assertNull(store.reason(1))
    }

    /** An NPU or SDK error is not the item's fault: the run stops and the item is left for the caller. */
    @Test
    fun modelError_stopsTheRun_andLeavesTheItemAlone() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        val preparer = FakePreparer()
        model.failure = VisionModelException("Generation failed: boom")

        val error = try {
            engine(store, preparer).analyze(store.items.getValue(1)) {}
            fail("a model error should stop the run")
            null
        } catch (e: StopRunException) {
            e
        }

        assertTrue(error!!.message!!.startsWith("The vision model failed"))
        assertTrue(error.message!!.contains("Generation failed: boom"))
        assertEquals(ItemStatus.Analyzing, store.status(1))
        assertNull(store.reason(1))
        assertEquals(listOf(1L), preparer.cleaned)
    }

    /** A full disk while writing the app's own work files fails every item alike: stop instead. */
    @Test
    fun workFileWriteFailure_stopsTheRun_andLeavesTheItemAlone() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        val preparer = FakePreparer(unwritable = setOf(1))

        val error = try {
            engine(store, preparer).analyze(store.items.getValue(1)) {}
            fail("a storage error should stop the run")
            null
        } catch (e: StopRunException) {
            e
        }

        assertTrue(error!!.message!!.contains("storage"))
        assertEquals(ItemStatus.Analyzing, store.status(1))
        assertNull(store.reason(1))
        assertEquals(0, model.generateCalls)
        assertEquals(listOf(1L), preparer.cleaned)
    }

    @Test
    fun userStop_returnsCancelled_withoutRetryOrStatusChange() = runTest {
        val store = FakeStore(listOf(item(1, ItemStatus.Analyzing)))
        model.script(1, reply("""{"description":"A re""", "user"))

        val outcome = engine(store).analyze(store.items.getValue(1)) {}

        assertEquals(ItemOutcome.Cancelled, outcome)
        assertEquals(1, model.generateCalls)
        assertEquals(ItemStatus.Analyzing, store.status(1))
    }
}

package com.agentic.binning.model

import android.util.Log
import com.agentic.binning.db.ItemKind
import com.agentic.binning.sorting.SortItem
import com.agentic.binning.sorting.SortParser
import com.agentic.binning.sorting.SortPrompt
import com.agentic.binning.sorting.SortSchema
import com.agentic.binning.sorting.SortValidator
import com.agentic.binning.sorting.SortBudgets
import com.agentic.binning.sorting.Validation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Smoke test on the phone: LFM3-1.7B with Task 5's per-chunk grammar. Run one method at a time
 * with `am instrument` (never connectedDebugAndroidTest: it deletes the models).
 */
class GenieXTextModelTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    private val lfm = GenieXTextModel(ModelCatalog.LFM3_1_7B, SortBudgets.LFM3_1_7B.contextTokens)

    @After fun tearDown() {
        runBlocking { lfm.release() }
    }

    private val ids = items.map { it.id }
    private val format = ReplyFormat(SortSchema.assignJsonSchema(), SortSchema.assignGrammar(ids, emptyList()))
    private val maxTokens = 30 * items.size + 600

    /** The backend and Task 5's per-chunk grammar: `eos`, parseable JSON, exactly the chunk's ids in order. */
    @Test fun lfm_grammarHoldsOnTenItems() = runBlocking {
        lfm.load()
        val reply = lfm.complete(SortPrompt.system(), SortPrompt.assignUser(items, emptyList(), emptyList()), format, maxTokens)
        Log.i(TAG, "grammar: stop=${reply.stopReason} in=${reply.inputTokens} out=${reply.outputTokens} ${reply.millis} ms " + reply.text)
        assertEquals("eos", reply.stopReason)
        val parsed = SortParser.parse(reply.text)
        assertNotNull("grammar-bound reply did not parse: ${reply.text}", parsed)
        assertEquals(ids, parsed!!.assignments.map { it.id })
        assertTrue(parsed.newCategories.size <= SortSchema.MAX_NEW_CATEGORIES)
        lfm.release()
    }

    /** A caller that is already cancelled (Stop, project switch) must still free the NPU model. */
    @Test fun lfm_releaseFromCancelledCaller_stillDestroys() = runBlocking {
        lfm.load()
        launch(start = CoroutineStart.UNDISPATCHED) {
            cancel()
            lfm.release()
        }.join()
        val e = runCatching { lfm.complete(SortPrompt.system(), "x", format, 8) }.exceptionOrNull()
        assertTrue("expected 'not loaded' after release, got $e", e is IllegalStateException && e.message.orEmpty().contains("not loaded"))
    }

    /**
     * The full check: the validator accepts the reply, after one retry as the categorizer will do.
     * LFM3-1.7B often assigns categories it never listed; the validator adopts those, and on the
     * retry an item with an unusable category is left out alone (at least 8 of 10 must be assigned).
     */
    @Test fun lfm_sortsTenItems() = runBlocking {
        lfm.load()

        // As the categorizer will: one retry with the problems listed.
        var feedback = emptyList<String>()
        var outcome: Validation? = null
        for (attempt in 0..1) {
            val reply = lfm.complete(SortPrompt.system(), SortPrompt.assignUser(items, emptyList(), feedback), format, maxTokens)
            Log.i(TAG, "attempt $attempt: stop=${reply.stopReason} in=${reply.inputTokens} out=${reply.outputTokens} ${reply.millis} ms\n${reply.text}")
            assertEquals("eos", reply.stopReason)
            val parsed = SortParser.parse(reply.text)
            assertNotNull("grammar-bound reply did not parse: ${reply.text}", parsed)
            // The grammar fixes the ids, in order.
            assertEquals(ids, parsed!!.assignments.map { it.id })
            outcome = SortValidator.validate(parsed, items, emptyList(), projectItemCount = items.size, isRetry = attempt > 0)
            Log.i(TAG, "attempt $attempt: $outcome")
            if (outcome is Validation.Ok) break
            feedback = (outcome as Validation.Rejected).problems
        }
        assertTrue("validator rejected both attempts: $outcome", outcome is Validation.Ok)
        val ok = outcome as Validation.Ok
        Log.i(TAG, "accepted: ${ok.assignments.size} of ${items.size} assigned, left out ${ok.leftOut}, new ${ok.newCategories.map { it.name }}")
        assertTrue("only ${ok.assignments.size} of ${items.size} assigned", ok.assignments.size >= 8)
        assertEquals(ids.toSet(), ok.assignments.map { it.itemId }.toSet() + ok.leftOut.keys)
        lfm.release()
    }

    internal companion object {
        const val TAG = "TextModelTest"

        private fun photo(id: Long, name: String, tags: List<String>, description: String) =
            SortItem(id, ItemKind.Photo, name, tags, description, emptyList())

        private fun video(id: Long, name: String, tags: List<String>, description: String, actions: List<String>) =
            SortItem(id, ItemKind.Video, name, tags, description, actions)

        /** Ten made-up items in Task 5's line format: two beach, two food, two hiking, two dogs, a sailboat, a birthday. */
        internal val items = listOf(
            photo(101, "Sunset Over Beach", listOf("beach", "sunset", "ocean", "sand"), "The sun sets over a sandy beach with gentle waves and an orange sky."),
            video(102, "Kids Building Sandcastle", listOf("beach", "children", "sandcastle", "summer"), "Two children dig and shape a sandcastle near the water.", listOf("0:00 children dig sand", "0:06 a wave reaches the castle")),
            photo(103, "Pancake Stack Breakfast", listOf("food", "pancakes", "breakfast", "syrup"), "A stack of pancakes with berries and syrup on a white plate."),
            photo(104, "Bowl of Ramen", listOf("food", "ramen", "noodles", "dinner"), "A bowl of ramen with egg, sliced pork and green onions on a wooden table."),
            video(105, "Trail Through Pine Forest", listOf("hiking", "forest", "trail", "trees"), "A hiker walks a dirt trail between tall pine trees.", listOf("0:00 walking uphill", "0:10 stops at a viewpoint")),
            photo(106, "Mountain Summit View", listOf("hiking", "mountain", "summit", "clouds"), "A view from a rocky summit over valleys and low clouds."),
            video(107, "Dog Fetching Ball", listOf("dog", "park", "ball", "grass"), "A brown dog runs across a park lawn to fetch a tennis ball.", listOf("0:00 ball thrown", "0:03 dog catches it")),
            photo(108, "Puppy Asleep on Couch", listOf("dog", "puppy", "sleeping", "couch"), "A small puppy sleeps curled up on a grey couch cushion."),
            photo(109, "Sailboat in the Harbor", listOf("sailboat", "harbor", "boats", "water"), "A white sailboat moored in a calm harbor with other boats behind."),
            video(110, "Birthday Candles Blown Out", listOf("birthday", "cake", "party", "candles"), "A child blows out candles on a birthday cake while people clap.", listOf("0:00 singing", "0:08 candles blown out")),
        )
    }
}

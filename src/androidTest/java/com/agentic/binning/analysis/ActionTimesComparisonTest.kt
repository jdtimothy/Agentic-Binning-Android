package com.agentic.binning.analysis

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.GenieXVisionModel
import com.agentic.binning.model.ModelCatalog
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.pipeline.AnalysisEngine
import com.agentic.binning.pipeline.AnalysisStore
import com.agentic.binning.pipeline.ItemCounts
import com.agentic.binning.pipeline.ItemOutcome
import com.agentic.binning.pipeline.PipelineLog
import com.agentic.binning.pipeline.Preparer
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt-in phone benchmark. Reads Step 1 copies only; never opens a project or sorting backend. */
class ActionTimesComparisonTest {
    @Test fun sameRealClips_recordActionsAndElapsedTime() = runBlocking {
        org.junit.Assume.assumeTrue(
            "Opt in with -e action_times_comparison true",
            androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("action_times_comparison") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "action-times-comparison").apply { mkdirs() }
        val model = GenieXVisionModel(ModelCatalog.LFM3_VL_2B)
        val reports = JSONArray()
        try {
            val loadStart = System.nanoTime()
            model.load(16)
            val loadMs = (System.nanoTime() - loadStart) / 1_000_000
            val clips = listOf("20260919_120531.mp4" to 35003L, "20260919_121906.mp4" to 36705L, "20260920_174506.mp4" to 47362L)
            for ((index, clip) in clips.withIndex()) {
                val source = File("/sdcard/Movies/action-times-step1", clip.first)
                assertTrue("Missing Step 1 fixture: $source", source.isFile)
                val item = ItemEntity((index + 1).toLong(), "comparison-$index", source.path, source.name,
                    source.path, ItemKind.Video, null, clip.second, null, null, ItemStatus.Pending, addedAt = 0)
                val decoder = MediaPreparer(File(root, "work"))
                var prepared: PreparedMedia? = null
                val preparer = object : Preparer {
                    override fun prepare(item: ItemEntity, maxFrames: Int, onFrame: (Int, Int) -> Unit): PreparedMedia =
                        decoder.prepare(item, File(root, "thumbs"), model.imageEdge, maxFrames, onFrame).also { prepared = it }
                    override fun cleanup(itemId: Long) = decoder.cleanup(itemId)
                }
                val store = object : AnalysisStore {
                    override suspend fun nextPending(): ItemEntity? = null
                    override suspend fun setStatus(id: Long, status: ItemStatus, reason: String?) = Unit
                    override suspend fun markAnalyzing(id: Long) = Unit
                    override suspend fun returnToPending(id: Long) = Unit
                    override suspend fun recoverInterrupted(failAfter: Int) = 0
                    override suspend fun counts() = ItemCounts(0, 0, 0)
                    override suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile) {
                        reports.put(JSONObject().put("clip", source.name).put("durationMs", clip.second)
                            .put("promptVersion", AnalysisPrompt.PROMPT_VERSION).put("modelLoadMs", loadMs).put("analysis", JSONObject(raw))
                            .put("frameTimesMs", JSONArray(prepared!!.frameTimesMs))
                            .put("frameChanges", JSONArray(prepared!!.frameChanges)))
                    }
                }
                val engine = AnalysisEngine(model, preparer, store, 16, log = PipelineLog { message, error -> Log.i("ActionTimesComparison", message, error) })
                val start = System.nanoTime()
                val outcome = engine.analyze(item) {}
                assertTrue(outcome.toString(), outcome is ItemOutcome.Analyzed)
                reports.getJSONObject(reports.length() - 1).put("elapsedMs", (System.nanoTime() - start) / 1_000_000)
                File(root, "results.json").writeText(reports.toString(2))
                Log.i("ActionTimesComparison", reports.getJSONObject(reports.length() - 1).toString())
            }
        } finally {
            model.release()
        }
    }
}

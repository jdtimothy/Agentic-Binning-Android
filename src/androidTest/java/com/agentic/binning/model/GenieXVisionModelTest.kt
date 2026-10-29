package com.agentic.binning.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.agentic.binning.analysis.AnalysisParser
import com.agentic.binning.analysis.AnalysisPrompt
import com.agentic.binning.analysis.MediaPreparer
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GenieXVisionModelTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(300)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val spec = ModelCatalog.LFM3_VL_2B
    private val model = GenieXVisionModel(spec)
    private lateinit var root: File
    private lateinit var preparer: MediaPreparer
    private lateinit var thumbs: File
    private lateinit var photo: PreparedMedia

    @Before fun setUp() {
        root = File(context.cacheDir, "vision-test-${System.nanoTime()}").apply { mkdirs() }
        thumbs = File(root, "thumbs")
        preparer = MediaPreparer(context, File(root, "work"))
        val source = File(root, "hello.png")
        saveBitmap(helloBitmap(), source, Bitmap.CompressFormat.PNG)
        photo = preparer.prepare(photoItem(1L, source), thumbs, targetEdge = 512)
    }

    @After fun tearDown() {
        runBlocking { model.release() }
        root.deleteRecursively()
    }

    @Test fun photo_onNpu_returnsParseableJson() = runBlocking {
        model.load()
        val reply = model.generate(photo) {}
        Log.i(TAG, "photo reply ${reply.profile}: ${reply.text}")
        val result = AnalysisParser.parse(reply.text, ItemKind.Photo)
        assertNotNull(reply.text, result)
        assertEquals("eos", reply.profile.stopReason)
        assertTrue(reply.profile.generatedTokens < AnalysisPrompt.MAX_TOKENS_PHOTO)
        assertTrue(reply.text, reply.text.replace(Regex("\\s"), "").contains("\"actions\":[]"))
        assertTrue(reply.text, result!!.tags.size in 3..10)
        model.release()
    }

    @Test fun video_frames_interleaveWithTimestamps() = runBlocking {
        model.load()
        val times = (0L..3500L step 500L).toList()
        val frameDir = File(root, "frames").apply { mkdirs() }
        val paths = times.map { t ->
            val file = File(frameDir, "f_$t.jpg")
            saveBitmap(squareFrame(t), file, Bitmap.CompressFormat.JPEG)
            file.absolutePath
        }
        val video = PreparedMedia(ItemKind.Video, paths, times, paths.first())
        val tokens = StringBuilder()
        val reply = model.generate(video) { tokens.append(it) }
        Log.i(TAG, "video reply ${reply.profile}: ${reply.text}")
        val result = AnalysisParser.parse(reply.text, ItemKind.Video)
        assertNotNull(reply.text, result)
        assertTrue(reply.text, result!!.actions.isNotEmpty())
        // Guaranteed by the video grammar: 2-5 actions, m:ss times, 3-10 tags.
        assertTrue(reply.text, result.actions.size in 2..5)
        assertTrue(reply.text, result.actions.all { it.t.matches(Regex("""\d{1,2}:[0-5]\d""")) })
        assertTrue(reply.text, result.tags.size in 3..10)
        assertEquals(reply.text, tokens.toString())
        assertEquals("eos", reply.profile.stopReason)
        assertTrue(reply.profile.generatedTokens < AnalysisPrompt.MAX_TOKENS_VIDEO)
        model.release()
    }

    @Test fun preparer_photo_writesPaddedSquareAndUnpaddedThumbnail() {
        assertEquals(ItemKind.Photo, photo.kind)
        assertEquals(1, photo.imagePaths.size)
        assertTrue(photo.frameTimesMs.isEmpty())
        val image = BitmapFactory.decodeFile(photo.imagePaths[0])
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        assertEquals(File(thumbs, "1.jpg").absolutePath, photo.thumbnailPath)
        val thumb = BitmapFactory.decodeFile(photo.thumbnailPath)
        assertEquals(512, thumb.width)
        assertEquals(384, thumb.height)
        preparer.cleanup(1L)
        assertFalse(File(photo.imagePaths[0]).exists())
        assertTrue(File(photo.thumbnailPath).exists())
    }

    @Test fun preparer_missingFile_throwsFileNotFound() {
        assertThrows(FileNotFoundException::class.java) {
            preparer.prepare(photoItem(2L, File(root, "gone.jpg")), thumbs, targetEdge = 512)
        }
    }

    @Test fun modelId_namesRepoAndPrecision() {
        assertEquals("LiquidAI/LFM3-VL-2B-GGUF:Q4_0", model.modelId)
    }

    private fun photoItem(id: Long, file: File) = ItemEntity(
        id = id,
        contentHash = "hash-$id",
        originalPath = file.absolutePath,
        originalName = file.name,
        currentPath = file.absolutePath,
        kind = ItemKind.Photo,
        takenAt = null,
        durationMs = null,
        width = 1024,
        height = 768,
        status = ItemStatus.Pending,
        addedAt = 0L,
    )

    private fun helloBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 120f }
            drawText("HELLO S26", 160f, 220f, text)
            drawRect(160f, 360f, 420f, 620f, Paint().apply { color = Color.RED })
            drawRect(600f, 360f, 860f, 620f, Paint().apply { color = Color.BLUE })
        }
        return bmp
    }

    /**
     * A dark frame with a square sliding left to right that is white before 2 s and red from 2 s.
     * The motion matters: with a colour change alone LFM3-VL-2B reported the clip as static and
     * returned no actions (see the Task 10 report).
     */
    private fun squareFrame(tMs: Long): Bitmap {
        val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.DKGRAY)
            val x = 40f + tMs / 3500f * 300f
            drawRect(x, 156f, x + 160f, 316f, Paint().apply { color = if (tMs >= 2000L) Color.RED else Color.WHITE })
        }
        return bmp
    }

    private fun saveBitmap(bmp: Bitmap, file: File, format: Bitmap.CompressFormat) {
        file.outputStream().use { bmp.compress(format, 95, it) }
    }

    companion object {
        private const val TAG = "GenieXVisionModelTest"

        /** Pulls the model once for the whole class if it is not on the phone yet. */
        @BeforeClass @JvmStatic fun ensureModel() = runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val downloader = ModelDownloader(context)
            val spec = ModelCatalog.LFM3_VL_2B
            if (downloader.isReady(spec)) return@runBlocking
            val last = withTimeout(10 * 60 * 1000L) {
                downloader.pull(spec).first { it is DownloadState.Ready || it is DownloadState.Failed }
            }
            Log.i(TAG, "pull finished: $last")
            assertEquals(DownloadState.Ready, last)
        }
    }
}

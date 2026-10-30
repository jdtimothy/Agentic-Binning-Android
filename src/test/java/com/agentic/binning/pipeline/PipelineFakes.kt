package com.agentic.binning.pipeline

import com.agentic.binning.analysis.AnalysisResult
import com.agentic.binning.analysis.MediaDecodeException
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.model.VisionReply
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred

const val VALID_JSON =
    """{"description":"A red square on white.","actions":[],"tags":["red","square","graphic"],""" +
        """"suggested_name":"Red square","shot_type":"close"}"""

fun item(id: Long, status: ItemStatus = ItemStatus.Pending, kind: ItemKind = ItemKind.Photo, attempts: Int = 0) = ItemEntity(
    id = id,
    contentHash = "hash$id",
    originalPath = "/sdcard/DCIM/$id.jpg",
    originalName = "$id.jpg",
    currentPath = "/sdcard/DCIM/$id.jpg",
    kind = kind,
    takenAt = null,
    durationMs = null,
    width = null,
    height = null,
    status = status,
    addedAt = 0,
    attempts = attempts,
)

fun reply(text: String, stopReason: String = "eos") =
    VisionReply(text, VisionProfile(stopReason, ttftMs = 500.0, promptTokens = 400, generatedTokens = 77, decodingSpeed = 45.0))

/** One shared, ordered log of what the fakes were asked to do. */
class Events {
    val log = mutableListOf<String>()
    operator fun plusAssign(e: String) {
        log += e
    }
    fun count(e: String) = log.count { it == e }
}

class FakeStore(items: List<ItemEntity>, private val events: Events = Events()) : AnalysisStore {
    val items = LinkedHashMap<Long, ItemEntity>().apply { items.forEach { put(it.id, it) } }
    val saved = mutableMapOf<Long, AnalysisResult>()
    val savedRaw = mutableMapOf<Long, String>()
    val savedModelId = mutableMapOf<Long, String>()
    /** The failAfter of each [recoverInterrupted] call. */
    val recoveries = mutableListOf<Int>()

    fun status(id: Long) = items.getValue(id).status

    /** Registers a new item, as the registrar would while a run is in progress. */
    fun add(item: ItemEntity) {
        items[item.id] = item
    }
    fun reason(id: Long) = items.getValue(id).failureReason
    fun attempts(id: Long) = items.getValue(id).attempts

    override suspend fun nextPending(): ItemEntity? =
        items.values.filter { it.status == ItemStatus.Pending }.minByOrNull { it.id }

    override suspend fun setStatus(id: Long, status: ItemStatus, reason: String?) {
        items[id] = items.getValue(id).copy(status = status, failureReason = reason)
    }

    override suspend fun markAnalyzing(id: Long) {
        val item = items.getValue(id)
        items[id] = item.copy(status = ItemStatus.Analyzing, failureReason = null, attempts = item.attempts + 1)
    }

    override suspend fun returnToPending(id: Long) {
        items[id] = items.getValue(id).copy(status = ItemStatus.Pending, attempts = 0)
    }

    override suspend fun recoverInterrupted(failAfter: Int): Int {
        events += "reset"
        recoveries += failAfter
        val hits = items.values.filter { it.status == ItemStatus.Analyzing }
        hits.forEach {
            items[it.id] = if (it.attempts >= failAfter) {
                it.copy(status = ItemStatus.Failed, failureReason = FailureReasons.INTERRUPTED)
            } else {
                it.copy(status = ItemStatus.Pending)
            }
        }
        return hits.size
    }

    override suspend fun save(itemId: Long, result: AnalysisResult, raw: String, modelId: String, profile: VisionProfile) {
        saved[itemId] = result
        savedRaw[itemId] = raw
        savedModelId[itemId] = modelId
        items[itemId] = items.getValue(itemId).copy(status = ItemStatus.Analyzed, failureReason = null)
    }

    override suspend fun counts(): ItemCounts {
        val all = items.values
        return ItemCounts(
            pending = all.count { it.status == ItemStatus.Pending || it.status == ItemStatus.Analyzing },
            analyzed = all.count { it.status == ItemStatus.Analyzed },
            total = all.size,
            failed = all.count { it.status == ItemStatus.Failed || it.status == ItemStatus.Missing },
        )
    }
}

class FakePreparer(
    private val missing: Set<Long> = emptySet(),
    private val undecodable: Set<Long> = emptySet(),
    /** Items whose work files cannot be written, as on a full disk. */
    private val unwritable: Set<Long> = emptySet(),
    /** Frames a video "extracts", each reported through onFrame. */
    private val videoFrames: Int = 3,
) : Preparer {
    val prepared = mutableListOf<Long>()
    val cleaned = mutableListOf<Long>()

    override fun prepare(item: ItemEntity, onFrame: (ready: Int, total: Int) -> Unit): PreparedMedia {
        if (item.id in missing) throw FileNotFoundException(item.currentPath)
        if (item.id in undecodable) throw MediaDecodeException("Cannot decode ${item.originalName}")
        if (item.id in unwritable) throw IOException("Cannot write /cache/frames/${item.id}/image.jpg: ENOSPC (No space left on device)")
        if (item.kind == ItemKind.Video) (1..videoFrames).forEach { onFrame(it, videoFrames) }
        prepared += item.id
        return PreparedMedia(item.kind, listOf("/work/${item.id}/image.jpg"), emptyList(), "/thumbs/${item.id}.jpg")
    }

    override fun cleanup(itemId: Long) {
        cleaned += itemId
    }
}

/**
 * Answers each item from [replies] (item id → queue of replies, one per attempt), else with
 * [VALID_JSON]. An item with a gate in [gates] blocks until the gate completes; `true` (as
 * [cancel] does) makes it return a `user`-stopped partial reply.
 */
class FakeModel(private val events: Events = Events()) : VisionModel {
    override val modelId = "Fake/VL:Q4_0"
    override val imageEdge = 512
    val replies = mutableMapOf<Long, ArrayDeque<VisionReply>>()
    val gates = mutableMapOf<Long, CompletableDeferred<Boolean>>()
    var failure: Throwable? = null
    var loadFailure: Throwable? = null
    var generateCalls = 0
    private var waiting: CompletableDeferred<Boolean>? = null

    fun script(id: Long, vararg r: VisionReply) {
        replies[id] = ArrayDeque(r.toList())
    }

    fun gate(id: Long) = CompletableDeferred<Boolean>().also { gates[id] = it }

    override suspend fun load() {
        events += "load"
        loadFailure?.let { throw it }
    }

    override suspend fun generate(media: PreparedMedia, onToken: (String) -> Unit): VisionReply {
        val id = media.imagePaths.first().split('/')[2].toLong()
        generateCalls++
        events += "generate:$id"
        failure?.let { throw it }
        gates.remove(id)?.let { gate ->
            waiting = gate
            val cancelled = try {
                gate.await()
            } finally {
                waiting = null
            }
            if (cancelled) return reply("""{"description":"A re""", "user").also { onToken(it.text) }
        }
        val r = replies[id]?.removeFirstOrNull() ?: reply(VALID_JSON)
        r.text.chunked(16).forEach(onToken)
        return r
    }

    override suspend fun cancel() {
        events += "cancel"
        waiting?.complete(true)
    }

    /** When set, the next [release] suspends until it completes (one-shot). */
    var releaseGate: CompletableDeferred<Unit>? = null

    override suspend fun release() {
        events += "release"
        releaseGate?.let {
            releaseGate = null
            it.await()
        }
    }
}

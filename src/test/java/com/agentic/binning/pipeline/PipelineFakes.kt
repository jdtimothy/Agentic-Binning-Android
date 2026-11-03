package com.agentic.binning.pipeline

import com.agentic.binning.analysis.AnalysisResult
import com.agentic.binning.analysis.MediaDecodeException
import com.agentic.binning.analysis.PreparedMedia
import com.agentic.binning.db.Assignment
import com.agentic.binning.db.CategoryEntity
import com.agentic.binning.db.CategoryInfo
import com.agentic.binning.db.ItemEntity
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.db.SortRunEntity
import com.agentic.binning.db.UncategorizedItem
import com.agentic.binning.model.ReplyFormat
import com.agentic.binning.model.TextModel
import com.agentic.binning.model.TextReply
import com.agentic.binning.model.VisionModel
import com.agentic.binning.model.VisionProfile
import com.agentic.binning.model.VisionReply
import com.agentic.binning.project.ProjectRef
import com.agentic.binning.settings.SortingModelChoice
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
        // As AnalysisDao.markAnalyzed: a re-analyzed item that has a category stays Sorted.
        val item = items.getValue(itemId)
        items[itemId] = item.copy(status = if (item.categoryId == null) ItemStatus.Analyzed else ItemStatus.Sorted, failureReason = null)
    }

    override suspend fun counts(): ItemCounts {
        val all = items.values
        return ItemCounts(
            pending = all.count { it.status == ItemStatus.Pending || it.status == ItemStatus.Analyzing },
            analyzed = all.count { it.status == ItemStatus.Analyzed || it.status == ItemStatus.Sorted },
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
    /** The cap each [prepare] was given. */
    val capsSeen = mutableListOf<Int>()
    val cleaned = mutableListOf<Long>()

    override fun prepare(item: ItemEntity, maxFrames: Int, onFrame: (ready: Int, total: Int) -> Unit): PreparedMedia {
        capsSeen += maxFrames
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
    /** The cap of each [load], in order. */
    val loadCaps = mutableListOf<Int>()
    private var waiting: CompletableDeferred<Boolean>? = null

    fun script(id: Long, vararg r: VisionReply) {
        replies[id] = ArrayDeque(r.toList())
    }

    fun gate(id: Long) = CompletableDeferred<Boolean>().also { gates[id] = it }

    override suspend fun load(maxFrames: Int) {
        events += "load"
        loadCaps += maxFrames
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

/** A categorizer that only records that it ran; [outcome] is what it returns. */
class FakeCategorizer(private val events: Events = Events()) : Categorizer {
    var outcome: CategorizeOutcome = CategorizeOutcome.NothingToDo
    var failure: Throwable? = null
    val projects = mutableListOf<ProjectRef>()
    val choices = mutableListOf<SortingModelChoice?>()

    /** Runs at the start of [categorize], e.g. to register media meanwhile. */
    var onCategorize: () -> Unit = {}

    override suspend fun categorize(
        project: ProjectRef,
        choice: SortingModelChoice?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): CategorizeOutcome {
        events += "categorize"
        projects += project
        choices += choice
        onCategorize()
        failure?.let { throw it }
        return outcome
    }
}

/**
 * The sorting side of [FakeStore]: uncategorized items are its Analyzed items without a category, and
 * [applyChunk] writes categories back to it, all-or-nothing and guarded like `SortDao.applyChunk`.
 */
class FakeSortStore(private val store: FakeStore, private val events: Events = Events()) : SortStore {
    private val categoryRows = mutableListOf<CategoryEntity>()
    val runs = mutableListOf<SortRunEntity>()
    var applyCalls = 0

    /** Runs at the start of each [applyChunk], e.g. for the user to set a category meanwhile. */
    var beforeApply: () -> Unit = {}

    fun categoryNames(): List<String> = categoryRows.map { it.name }

    /** One past the highest id, so a category deleted by a Re-sort never leaves two rows sharing an id. */
    private fun nextCategoryId(): Long = (categoryRows.maxOfOrNull { it.id } ?: 0L) + 1L

    fun categoryOf(itemId: Long): String? =
        store.items.getValue(itemId).categoryId?.let { id -> categoryRows.single { it.id == id }.name }

    /** A category that already exists, as if from an earlier run. */
    fun seedCategory(name: String, description: String = "$name things", createdBy: String = "model"): Long {
        val id = nextCategoryId()
        categoryRows += CategoryEntity(id, name, description, createdBy, null)
        return id
    }

    override suspend fun categories(): List<CategoryInfo> = categoryRows.map { c ->
        CategoryInfo(c.id, c.name, c.description, c.createdBy, store.items.values.count { it.categoryId == c.id })
    }

    override suspend fun uncategorized(): List<UncategorizedItem> =
        store.items.values.filter { it.status == ItemStatus.Analyzed && it.categoryId == null && !it.categorySetByUser }.sortedBy { it.id }.map {
            UncategorizedItem(it.id, it.kind, "Description of ${it.id}", "[]", "Name ${it.id}", listOf("tag${it.id}"), false)
        }

    override suspend fun itemCount(): Int = store.items.size

    override suspend fun applyChunk(newCategories: List<CategoryEntity>, assignments: List<Assignment>): Int {
        applyCalls++
        events += "apply"
        beforeApply()
        val offered = newCategories.associateBy { it.name }
        // Checked up front so a bad assignment leaves nothing behind; a removed item is skipped, like SortDao.
        for (a in assignments) {
            if (a.itemId !in store.items) continue
            require(categoryRows.any { it.name == a.categoryName } || a.categoryName in offered) {
                "No category named '${a.categoryName}' for item ${a.itemId}"
            }
        }
        var applied = 0
        for (a in assignments) {
            val item = store.items[a.itemId] ?: continue
            if (item.categorySetByUser || item.categoryId != null) continue
            val category = categoryRows.firstOrNull { it.name == a.categoryName }
                ?: offered.getValue(a.categoryName).copy(id = nextCategoryId(), createdBy = "model").also { categoryRows += it }
            // Like SortDao.applyChunk, a name the user set is kept.
            val name = if (item.nameSetByUser) item.friendlyName else a.friendlyName
            store.items[a.itemId] = item.copy(categoryId = category.id, status = ItemStatus.Sorted, friendlyName = name)
            applied++
        }
        return applied
    }

    override suspend fun startRun(run: SortRunEntity): Long {
        val id = runs.size + 1L
        runs += run.copy(id = id)
        return id
    }

    override suspend fun finishRun(id: Long, finishedAt: Long, sorted: Int, newCategories: Int, outcome: String, message: String?) {
        val i = runs.indexOfFirst { it.id == id }
        runs[i] = runs[i].copy(finishedAt = finishedAt, sorted = sorted, newCategories = newCategories, outcome = outcome, message = message)
    }

    override suspend fun closeOpenRuns(finishedAt: Long, message: String): Int {
        var closed = 0
        runs.replaceAll { run ->
            if (run.finishedAt != null) run else run.copy(finishedAt = finishedAt, outcome = "partial", message = message).also { closed++ }
        }
        return closed
    }

    override suspend fun prepareResort(keepEdits: Boolean): Int {
        events += "prepareResort:$keepEdits"
        if (!keepEdits) {
            store.items.replaceAll { _, item -> item.copy(nameSetByUser = false, categorySetByUser = false) }
            categoryRows.replaceAll { it.copy(createdBy = "model") }
        }
        val unlocked = store.items.values.filter { it.status == ItemStatus.Sorted && !it.categorySetByUser }
        for (item in unlocked) {
            store.items[item.id] = item.copy(categoryId = null, status = ItemStatus.Analyzed)
        }
        categoryRows.removeAll { c -> c.createdBy == "model" && store.items.values.none { it.categoryId == c.id } }
        return unlocked.size
    }
}

/** The item ids of an assignment prompt, read from its item lines (each starts with the id and a tab). */
fun idsIn(user: String): List<Long> =
    user.substringAfter("Items to sort").lines().drop(1).takeWhile { it.isNotBlank() }.map { it.substringBefore('\t').toLong() }

/** A valid reply putting every item of [user] in [category], listed as a new category. */
fun assignAll(user: String, category: String = "Beach"): String {
    val a = idsIn(user).joinToString(",") { """{"id":$it,"category":"$category","name":"Item $it"}""" }
    return """{"new_categories":[{"name":"$category","description":"$category things"}],"assignments":[$a]}"""
}

/**
 * A sorting model. Replies come from [replies] in call order, then from [answer]. A call whose number
 * (from 1) has a gate in [gates] waits for it first.
 */
class FakeTextModel(private val events: Events = Events(), override val modelId: String = "Fake/Text:Q4_0") : TextModel {
    val prompts = mutableListOf<String>()
    val formats = mutableListOf<ReplyFormat>()
    val maxTokens = mutableListOf<Int>()
    val replies = ArrayDeque<String>()

    /** Stop reasons in call order, then [stopFor]'s. */
    val stops = ArrayDeque<String>()
    var stopFor: (user: String) -> String = { "eos" }
    var answer: (user: String) -> String = { assignAll(it) }
    var loadFailure: Throwable? = null

    /** Thrown by every [complete] while set. */
    var failure: Throwable? = null
    val gates = mutableMapOf<Int, CompletableDeferred<Unit>>()
    var calls = 0

    fun gate(call: Int) = CompletableDeferred<Unit>().also { gates[call] = it }

    override suspend fun load() {
        events += "text.load"
        loadFailure?.let { throw it }
    }

    override suspend fun complete(system: String, user: String, format: ReplyFormat, maxTokens: Int): TextReply {
        calls++
        events += "text.complete"
        prompts += user
        formats += format
        this.maxTokens += maxTokens
        gates.remove(calls)?.await()
        failure?.let { throw it }
        val text = replies.removeFirstOrNull() ?: answer(user)
        return TextReply(text, stops.removeFirstOrNull() ?: stopFor(user), inputTokens = 100, outputTokens = 50, millis = 5)
    }

    override suspend fun cancel() {
        events += "text.cancel"
    }

    override suspend fun release() {
        events += "text.release"
    }
}

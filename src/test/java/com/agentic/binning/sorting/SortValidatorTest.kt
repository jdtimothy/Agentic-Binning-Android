package com.agentic.binning.sorting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SortValidatorTest {
    private val chunk = listOf(item(1, name = "Eggs"), item(2, name = "Toast"), item(3, name = "Boat"))

    private fun ok(v: Validation) = v as? Validation.Ok ?: error("expected Ok but was $v")
    private fun rejected(v: Validation) = (v as? Validation.Rejected ?: error("expected Rejected but was $v")).problems

    private fun validate(
        r: SortReply,
        chunk: List<SortItem> = this.chunk,
        existing: List<com.agentic.binning.db.CategoryInfo> = emptyList(),
        projectItemCount: Int = 100,
        isRetry: Boolean = false,
    ) = SortValidator.validate(r, chunk, existing, projectItemCount, isRetry)

    @Test fun normalize_table() {
        val cases = mapOf(
            "Sea lion" to "sea lion",
            "sea lions" to "sea lion",
            "Sea-Lions" to "sea lion",
            "  Café   Scenes " to "cafe scene",
            "Parties" to "party",
            "Beaches" to "beach",
            "Boxes" to "box",
            "Buses" to "bus",
            "Glass" to "glass",
            "Cats & Dogs" to "cats dog",
            "Miscellaneous" to "miscellaneou",
        )
        for ((input, expected) in cases) assertEquals(input, expected, SortValidator.normalize(input))
    }

    @Test fun validate_acceptsCleanReply() {
        val r = reply(
            listOf("Food" to "Meals", "Boats" to "On water"),
            listOf(Triple(1L, "Food", "Fried eggs"), Triple(2L, "Food", "Toast"), Triple(3L, "Boats", "Boat at dock")),
        )
        val v = ok(validate(r))
        assertEquals(listOf("Food", "Boats"), v.newCategories.map { it.name })
        assertEquals("model", v.newCategories[0].createdBy)
        assertEquals("Meals", v.newCategories[0].description)
        assertEquals(3, v.assignments.size)
        assertEquals("Fried eggs", v.assignments[0].friendlyName)
    }

    @Test fun validate_rejectsMissingDuplicateForeignIds() {
        val r = reply(
            listOf("Food" to "Meals"),
            listOf(Triple(1L, "Food", "A"), Triple(1L, "Food", "B"), Triple(9L, "Food", "C")),
        )
        val text = rejected(validate(r)).joinToString("\n")
        assertTrue(text, Regex("""(?i)missing.*\b2\b.*\b3\b""").containsMatchIn(text.replace("\n", " ")))
        assertTrue(text, Regex("""(?i)duplicate.*\b1\b""").containsMatchIn(text))
        assertTrue(text, Regex("""(?i)not in this chunk.*\b9\b""").containsMatchIn(text))
    }

    @Test fun validate_adoptsUnknownCategory_asNewCategory() {
        val r = reply(
            listOf("Food" to "Meals"),
            listOf(Triple(1L, "Food", "A"), Triple(2L, "Drinks", "B"), Triple(3L, "Drinks", "C")),
        )
        val v = ok(validate(r))
        assertEquals(listOf("Food", "Drinks"), v.newCategories.map { it.name })
        val drinks = v.newCategories.single { it.name == "Drinks" }
        assertEquals("Drinks", drinks.description) // none was given
        assertEquals("model", drinks.createdBy)
        assertEquals(listOf("Food", "Drinks", "Drinks"), v.assignments.map { it.categoryName })
        assertTrue(v.leftOut.isEmpty())
    }

    @Test fun validate_adoptedCategories_areCleanedAndMerged_existingAndListedWin() {
        val items = (1L..6L).map { item(it) }
        val r = reply(
            listOf("Boats" to "On water"),
            listOf(
                Triple(1L, "sea lion", "A"), // existing user category wins
                Triple(2L, "boat", "B"), // the listed new category wins
                Triple(3L, "Busy: Harbors", "C"), // adopted, cleaned
                Triple(4L, "busy harbor", "D"), // near-duplicate of the adopted one
                Triple(5L, "Boats", "E"),
                Triple(6L, "Sea Lions", "F"),
            ),
        )
        val v = ok(validate(r, chunk = items, existing = listOf(category("Sea Lions", 9, createdBy = "user"))))
        assertEquals(listOf("Boats", "Busy Harbors"), v.newCategories.map { it.name })
        assertEquals(
            listOf("Sea Lions", "Boats", "Busy Harbors", "Busy Harbors", "Boats", "Sea Lions"),
            v.assignments.map { it.categoryName },
        )
    }

    @Test fun validate_adoptedCategories_countTowardTheTwelveCap() {
        val items = (1L..13L).map { item(it) }
        val names = (1..13).map { "Category${"abcdefghijklm"[it - 1]}" }
        val r = reply(emptyList(), items.mapIndexed { i, it -> Triple(it.id, names[i], "N") })
        val problems = rejected(validate(r, chunk = items))
        assertTrue(problems.toString(), problems.any { it.contains("12") && it.contains("new categories") })
    }

    /** I3: on the retry the 12 largest new categories are kept and the others' items left out. */
    @Test fun validate_tooManyNewCategories_onRetry_keepsTheTwelveLargest() {
        val items = (1L..20L).map { item(it) }
        // "Big" holds 8 items, then 12 one-item categories; the last of those (by order) loses.
        val names = (1..12).map { "Category${"abcdefghijkl"[it - 1]}" }
        val assignments = items.take(8).map { Triple(it.id, "Big", "N") } +
            items.drop(8).mapIndexed { i, it -> Triple(it.id, names[i], "N") }
        val retry = ok(validate(reply(emptyList(), assignments), chunk = items, isRetry = true))
        assertEquals(listOf("Big") + names.take(11), retry.newCategories.map { it.name })
        assertEquals(setOf(20L), retry.leftOut.keys)
        assertTrue(retry.leftOut.getValue(20L), retry.leftOut.getValue(20L).contains("12"))
        assertEquals((1L..19L).toList(), retry.assignments.map { it.itemId })
    }

    /** I3: id problems on the retry leave those items out instead of sinking the chunk. */
    @Test fun validate_idProblems_onRetry_leaveOnlyThoseItemsOut() {
        val items = (1L..5L).map { item(it) }
        val r = reply(
            listOf("Food" to "Meals"),
            listOf(
                Triple(1L, "Food", "A"), Triple(2L, "Food", "B"), Triple(2L, "Boats", "B2"),
                Triple(3L, "Food", "C"), Triple(99L, "Food", "X"),
            ),
        )
        val retry = ok(validate(r, chunk = items, isRetry = true))
        assertEquals(listOf(1L, 3L), retry.assignments.map { it.itemId })
        assertEquals(setOf(2L, 4L, 5L), retry.leftOut.keys)
        assertTrue(retry.leftOut.getValue(2L), retry.leftOut.getValue(2L).contains("more than once"))
        assertTrue(retry.leftOut.getValue(4L), retry.leftOut.getValue(4L).contains("missing", ignoreCase = true))
        assertTrue(retry.warnings.toString(), retry.warnings.any { it.contains("99") })
        // "Boats" was only used by the duplicate, so it isn't created.
        assertEquals(listOf("Food"), retry.newCategories.map { it.name })
    }

    /** I3: whatever the retry's reply, every item of the chunk ends assigned or left out, never both. */
    @Test fun validate_retry_everyItemEndsAssignedOrLeftOut() {
        val random = kotlin.random.Random(2026)
        val pool = listOf(
            "Food", "Boats", "Misc", "Photos", "", "///", "Nature no... let's see", "Sea Lions", "sea lion",
            "Hiking Trails", "One two three four five six", "Items to sort", "Beach", "Sunset Over the Sea",
        ) + (1..20).map { "Topic$it" }
        repeat(400) { round ->
            val items = (1L..random.nextLong(1, 30)).map { item(it) }
            val ids = items.map { it.id }
            val assignments = buildList {
                for (id in ids) {
                    when (random.nextInt(10)) {
                        0 -> Unit // missing
                        1 -> repeat(2) { add(Triple(id, pool.random(random), "N")) } // duplicate
                        else -> add(Triple(id, pool.random(random), "Name $id"))
                    }
                }
                repeat(random.nextInt(3)) { add(Triple(100L + random.nextInt(50), pool.random(random), "X")) } // foreign
            }.shuffled(random)
            val listed = List(random.nextInt(4)) { pool.random(random) to "d" }.filter { it.first.isNotEmpty() && '"' !in it.first }
            val existing = listOf(category("Beach", random.nextInt(8)), category("Misc", random.nextInt(8), id = 2))
            val maxNew = if (random.nextBoolean()) 12 else 3
            val v = SortValidator.validate(
                reply(listed, assignments), items, existing, projectItemCount = 40, isRetry = true, maxNewCategories = maxNew,
            )
            val okay = v as? Validation.Ok ?: error("round $round: retry was $v")
            val assigned = okay.assignments.map { it.itemId }
            assertEquals("round $round: assigned once", assigned.size, assigned.toSet().size)
            assertTrue("round $round: assigned and left out", assigned.none { it in okay.leftOut })
            assertEquals("round $round: every item", ids.toSet(), assigned.toSet() + okay.leftOut.keys)
            assertTrue("round $round: at most $maxNew new", okay.newCategories.size <= maxNew)
            val names = existing.map { it.name }.toSet() + okay.newCategories.map { it.name }
            assertTrue("round $round: known categories", okay.assignments.all { it.categoryName in names })
        }
    }

    @Test fun validate_adoptedCatchAll_countsTowardTheLimit() {
        val problems = rejected(validate(miscReply("Misc", 6, newCategory = false), chunk = miscChunk(6), projectItemCount = 40))
        assertTrue(problems.toString(), problems.any { it.startsWith("Too many items in a catch-all category") })
        val retry = ok(validate(miscReply("Misc", 6, newCategory = false), chunk = miscChunk(6), projectItemCount = 40, isRetry = true))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), retry.assignments.map { it.itemId })
        assertEquals(setOf(6L), retry.leftOut.keys)
    }

    @Test fun validate_adoptedSingletons_followTheOneItemRule() {
        val (items, listed) = singletonFixture()
        val r = SortReply(emptyList(), listed.assignments)
        val problems = rejected(validate(r, chunk = items))
        assertTrue(problems.toString(), problems.any { it.contains("one item", ignoreCase = true) })
        val retry = ok(validate(r, chunk = items, isRetry = true))
        assertEquals(6, retry.newCategories.size)
        assertTrue(retry.warnings.any { it.contains("one item", ignoreCase = true) })
    }

    @Test fun validate_unusableCategory_rejectsFirst_thenLeavesOnlyThatItemOut() {
        val r = reply(listOf("Food" to "Meals"), listOf(Triple(1L, "Food", "A"), Triple(2L, "Food", "B"), Triple(3L, "///", "C")))
        val first = rejected(validate(r))
        assertTrue(first.toString(), first.any { it.contains("3") && it.contains("usable category") })
        val retry = ok(validate(r, isRetry = true))
        assertEquals(listOf(1L, 2L), retry.assignments.map { it.itemId })
        assertEquals(setOf(3L), retry.leftOut.keys)
        assertTrue(retry.leftOut.getValue(3L).contains("usable category"))
        assertEquals(listOf("Food"), retry.newCategories.map { it.name })
    }

    @Test fun validate_missingIds_rejectTheFirstAttempt_andAreLeftOutOnRetry() {
        val r = reply(listOf("Food" to "Meals"), listOf(Triple(1L, "Food", "A"), Triple(2L, "///", "B")))
        assertTrue(rejected(validate(r)).any { it.startsWith("Missing ids") })
        val retry = ok(validate(r, isRetry = true))
        assertEquals(listOf(1L), retry.assignments.map { it.itemId })
        assertEquals(setOf(2L, 3L), retry.leftOut.keys)
    }

    @Test fun validate_matchesExistingCategoryCaseInsensitively() {
        val r = reply(emptyList(), chunk.map { Triple(it.id, "food", "N${it.id}") })
        val v = ok(validate(r, existing = listOf(category("Food", 5))))
        assertTrue(v.newCategories.isEmpty())
        assertEquals(setOf("Food"), v.assignments.map { it.categoryName }.toSet())
    }

    @Test fun normalize_mergesNearDuplicates_intoExisting() {
        val r = reply(
            listOf("Sea lion" to "x", "sea lions" to "y", "Sea-Lions" to "z"),
            listOf(Triple(1L, "Sea lion", "A"), Triple(2L, "sea lions", "B"), Triple(3L, "Sea-Lions", "C")),
        )
        val v = ok(validate(r, existing = listOf(category("Sea Lions", 9, createdBy = "user"))))
        assertTrue(v.newCategories.isEmpty())
        assertEquals(listOf("Sea Lions", "Sea Lions", "Sea Lions"), v.assignments.map { it.categoryName })
    }

    @Test fun normalize_mergesNewDuplicates_intoTheFirst() {
        val r = reply(
            listOf("Sea lion" to "x", "sea lions" to "y", "Sea-Lions" to "z"),
            listOf(Triple(1L, "Sea lion", "A"), Triple(2L, "sea lions", "B"), Triple(3L, "Sea-Lions", "C")),
        )
        val v = ok(validate(r))
        assertEquals(listOf("Sea lion"), v.newCategories.map { it.name })
        assertEquals(listOf("Sea lion", "Sea lion", "Sea lion"), v.assignments.map { it.categoryName })
    }

    @Test fun validate_dropsNewCategoriesNobodyUses() {
        val r = reply(
            listOf("Food" to "Meals", "Unused" to "None"),
            chunk.map { Triple(it.id, "Food", "N${it.id}") },
        )
        assertEquals(listOf("Food"), ok(validate(r)).newCategories.map { it.name })
    }

    @Test fun validate_categoryNamesAreCleanedAndCapped() {
        val long = "Supercalifragilisticexpialidocious Mountain Ranges"
        val r = reply(
            listOf("Sea/Lions: pics" to "d", long to "d"),
            listOf(Triple(1L, "Sea/Lions: pics", "A"), Triple(2L, long, "B"), Triple(3L, "Sea/Lions: pics", "C")),
        )
        val names = ok(validate(r)).newCategories.map { it.name }
        assertEquals("Sea Lions pics", names[0])
        assertTrue(names[1].length <= 40 && !names[1].endsWith(" "))
    }

    @Test fun validate_emptyCategoryNameRejected() {
        val r = reply(listOf("///" to "d"), chunk.map { Triple(it.id, "Food", "N") }.take(2) + Triple(3L, "///", "N"))
        val problems = rejected(validate(r, existing = listOf(category("Food", 1))))
        assertTrue(problems.toString(), problems.any { it.contains("empty", ignoreCase = true) })
        // On the retry the unusable listing is only a warning and item 3 alone is left out.
        val retry = ok(validate(r, existing = listOf(category("Food", 1)), isRetry = true))
        assertEquals(listOf(1L, 2L), retry.assignments.map { it.itemId })
        assertEquals(setOf(3L), retry.leftOut.keys)
        assertTrue(retry.warnings.toString(), retry.warnings.any { it.contains("empty", ignoreCase = true) })
    }

    @Test fun validate_emptyFriendlyName_fallsBackToSuggested() {
        val r = reply(
            listOf("Food" to "Meals"),
            listOf(Triple(1L, "Food", "  "), Triple(2L, "Food", "///"), Triple(3L, "Food", "Fine name")),
        )
        val v = ok(validate(r))
        assertEquals(listOf("Eggs", "Toast", "Fine name"), v.assignments.map { it.friendlyName })
    }

    @Test fun validate_friendlyNameCappedAt60() {
        val r = reply(listOf("Food" to "d"), chunk.map { Triple(it.id, "Food", "word ".repeat(30)) })
        assertTrue(ok(validate(r)).assignments.all { it.friendlyName.length <= 60 && !it.friendlyName.endsWith(" ") })
    }

    @Test fun validate_keepsUserNamedItemName() {
        val items = listOf(item(1, name = "Eggs", nameSetByUser = true), item(2, name = "Toast"))
        val r = reply(listOf("Food" to "d"), listOf(Triple(1L, "Food", "Model rename"), Triple(2L, "Food", "Model toast")))
        val v = ok(validate(r, chunk = items))
        assertEquals("Eggs", v.assignments.single { it.itemId == 1L }.friendlyName)
        assertEquals("Model toast", v.assignments.single { it.itemId == 2L }.friendlyName)
    }

    private fun miscChunk(n: Int) = (1L..n).map { item(it) }

    private fun miscReply(name: String, n: Int, newCategory: Boolean = true) =
        reply(if (newCategory) listOf(name to "d") else emptyList(), miscChunk(n).map { Triple(it.id, name, "N") })

    @Test fun validate_catchAllLimit() {
        for (n in listOf(4, 5)) ok(validate(miscReply("Misc", n), chunk = miscChunk(n), projectItemCount = 40))
        for (n in listOf(6, 12)) {
            val problems = rejected(validate(miscReply("Misc", n), chunk = miscChunk(n), projectItemCount = 40))
            assertTrue(problems.toString(), problems.any { it.startsWith("Too many items in a catch-all category") })
        }
    }

    @Test fun mediaKindNames_areCatchAll() {
        val kinds = listOf("photo", "video", "image", "picture", "clip", "footage", "media", "file")
        for (k in kinds + kinds.map { it.replaceFirstChar(Char::uppercase) + "s" } + listOf("VIDEOS", "Pictures", "Files")) {
            assertTrue(k, SortValidator.isCatchAll(k))
        }
        for (k in listOf("Photography", "Beach", "Video Games")) assertTrue(k, !SortValidator.isCatchAll(k))
    }

    @Test fun validate_mediaKindCategory_fallsUnderTheCatchAllLimit() {
        ok(validate(miscReply("Photos", 5, newCategory = false), chunk = miscChunk(5), projectItemCount = 40, isRetry = true))
        val problems = rejected(validate(miscReply("Photos", 6, newCategory = false), chunk = miscChunk(6), projectItemCount = 40))
        assertTrue(problems.toString(), problems.any { it.startsWith("Too many items in a catch-all category 'Photos'") })
        rejected(validate(miscReply("Video", 6), chunk = miscChunk(6), projectItemCount = 40))
    }

    @Test fun validate_catchAllLimitScalesWithProject() {
        ok(validate(miscReply("Other", 20), chunk = miscChunk(20), projectItemCount = 400))
        rejected(validate(miscReply("Other", 21), chunk = miscChunk(21), projectItemCount = 400))
    }

    @Test fun validate_catchAllCountsExistingItemsAndPlurals() {
        val three = miscChunk(3)
        val r = miscReply("Miscellaneous", 3, newCategory = false)
        rejected(validate(r, chunk = three, existing = listOf(category("Miscellaneous", 3)), projectItemCount = 40))
        ok(validate(r, chunk = three, existing = listOf(category("Miscellaneous", 2)), projectItemCount = 40))
        rejected(validate(miscReply("Others", 6), chunk = miscChunk(6), projectItemCount = 40))
    }

    @Test fun validate_findsExistingCategoryLongerThan40CharsByExactName() {
        val longName = "A category name that is far longer than forty characters"
        val three = miscChunk(3)
        val r = reply(emptyList(), three.map { Triple(it.id, longName, "N") })
        val v = ok(validate(r, chunk = three, existing = listOf(category(longName, 4))))
        assertEquals(setOf(longName), v.assignments.map { it.categoryName }.toSet())
        val shouty = reply(emptyList(), three.map { Triple(it.id, "  " + longName.uppercase() + " ", "N") })
        assertEquals(setOf(longName), ok(validate(shouty, chunk = three, existing = listOf(category(longName, 4)))).assignments.map { it.categoryName }.toSet())
    }

    @Test fun validate_rejectsMoreThanTwelveNewCategories() {
        val items = (1L..13L).map { item(it) }
        val names = (1..13).map { "Category${"abcdefghijklm"[it - 1]}" }
        val r = reply(names.map { it to "d" }, items.mapIndexed { i, it -> Triple(it.id, names[i], "N") })
        val problems = rejected(validate(r, chunk = items))
        assertTrue(problems.toString(), problems.any { it.contains("12") && it.contains("new categories") })
        val twelve = items.take(12)
        val r12 = reply(names.take(12).map { it to "d" }, twelve.mapIndexed { i, it -> Triple(it.id, names[i], "N") })
        assertTrue(rejected(validate(r12, chunk = twelve)).none { it.contains("at most 12") })
    }

    @Test fun validate_singletonProblemIsReportedAlongsideOtherProblems() {
        val (items, r) = singletonFixture()
        val bad = SortReply(r.newCategories, r.assignments.drop(1))
        val problems = rejected(validate(bad, chunk = items))
        assertTrue(problems.toString(), problems.any { it.startsWith("Missing ids") })
        assertTrue(problems.toString(), problems.any { it.contains("one item", ignoreCase = true) })
    }

    private fun singletonFixture(): Pair<List<SortItem>, SortReply> {
        val items = (1L..6L).map { item(it) }
        val names = listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")
        return items to reply(names.map { it to "d" }, items.mapIndexed { i, it -> Triple(it.id, names[i], "N") })
    }

    @Test fun validate_tooManySingletons_rejectedOnce() {
        val (items, r) = singletonFixture()
        val problems = rejected(validate(r, chunk = items))
        assertTrue(problems.toString(), problems.any { it.contains("one item", ignoreCase = true) && it.contains("broader") })
        val retry = ok(validate(r, chunk = items, isRetry = true))
        assertEquals(6, retry.newCategories.size)
        assertTrue(retry.warnings.any { it.contains("one item", ignoreCase = true) })
    }

    @Test fun validate_fewSingletonsAllowed() {
        val items = (1L..6L).map { item(it) }
        val r = reply(
            listOf("Alpha" to "d", "Bravo" to "d", "Charlie" to "d"),
            listOf(Triple(1L, "Alpha", "N"), Triple(2L, "Bravo", "N"), Triple(3L, "Charlie", "N"), Triple(4L, "Charlie", "N"), Triple(5L, "Charlie", "N"), Triple(6L, "Charlie", "N")),
        )
        ok(validate(r, chunk = items))
    }

    @Test fun validate_existingCategoriesNeverCountAsSingletons() {
        val items = (1L..3L).map { item(it) }
        val existing = listOf(category("A", 0), category("B", 0), category("C", 0), category("D", 0))
        val r = reply(emptyList(), listOf(Triple(1L, "A", "N"), Triple(2L, "B", "N"), Triple(3L, "C", "N")))
        ok(validate(r, chunk = items, existing = existing))
    }

    @Test fun mergeProposals_cleansDedupesAndDropsExisting() {
        val proposals = listOf(
            NewCategory("Sea lion", "x"),
            NewCategory("sea lions", "dup"),
            NewCategory("Food", "already there"),
            NewCategory("///", "empty"),
            NewCategory("Boats", "y"),
        )
        val out = SortValidator.mergeProposals(proposals, listOf(category("Foods", 2)))
        assertEquals(listOf("Sea lion", "Boats"), out.map { it.name })
        assertEquals("model", out[0].createdBy)
    }

    @Test fun mergeProposals_keepsAtMostTwelve() {
        val proposals = (1..20).map { NewCategory("Cat${"abcdefghijklmnopqrst"[it - 1]}", "d") }
        assertEquals(12, SortValidator.mergeProposals(proposals, emptyList()).size)
    }

    // Names that are not names: LFM leaks its reasoning or the prompt's own wording.

    private val leakedNames = listOf(
        "Nature no... let's see existing Nature",
        "Nature forest is natural elements, but",
        "Items to sort",
        "Beach…",
        "Sunny days, ",
        "Dogs and",
        "Pictures of",
        "Sunset with",
        "Beach is",
        "Lunch or",
        "Walk the",
        "Lets see Nature",
        "One two three four five six",
        "New Categories",
        "existing categories",
        "Category",
    )

    @Test fun badCategoryNames_areFlagged() {
        for (n in leakedNames) assertTrue(n, SortValidator.categoryNameProblem(n) != null)
        val fine = listOf("Sunset Over the Sea", "Fire Performance", "Mama's Fish House", "Cats and Dogs", "Day at the Beach", "Food", "Photo")
        for (n in fine) assertEquals(n, null, SortValidator.categoryNameProblem(n))
    }

    @Test fun validate_leakedCategoryName_rejectsFirst_thenLeavesItsItemsOut() {
        val items = (1L..5L).map { item(it) }
        val bad = "Nature no... let's see existing Nature"
        val r = reply(
            listOf("Food" to "Meals", bad to "d"),
            listOf(Triple(1L, "Food", "A"), Triple(2L, bad, "B"), Triple(3L, "Food", "C"), Triple(4L, "Items to sort", "D"), Triple(5L, "Food", "E")),
        )
        val first = rejected(validate(r, chunk = items))
        assertTrue(first.toString(), first.any { it.contains("2") && it.contains("not a usable category name") })
        assertTrue(first.toString(), first.any { it.contains("4") && it.contains("Items to sort") })
        val retry = ok(validate(r, chunk = items, isRetry = true))
        assertEquals(listOf(1L, 3L, 5L), retry.assignments.map { it.itemId })
        assertEquals(setOf(2L, 4L), retry.leftOut.keys)
        assertEquals(listOf("Food"), retry.newCategories.map { it.name })
    }

    @Test fun validate_leakedName_matchingAnExistingCategory_isNotAProblem() {
        // An existing category is matched before any name rule: the user's names are theirs.
        val r = reply(emptyList(), chunk.map { Triple(it.id, "Items to sort", "N") })
        val v = ok(validate(r, existing = listOf(category("Items to sort", 2, createdBy = "user"))))
        assertEquals(setOf("Items to sort"), v.assignments.map { it.categoryName }.toSet())
    }

    @Test fun validate_unusedLeakedListing_doesNotReject() {
        val r = reply(listOf("Food" to "Meals", "Dogs and" to "d"), chunk.map { Triple(it.id, "Food", "N") })
        assertEquals(listOf("Food"), ok(validate(r)).newCategories.map { it.name })
    }

    @Test fun mergeProposals_skipsLeakedNames() {
        val proposals = listOf(NewCategory("Nature forest is natural elements, but", "x"), NewCategory("Items to sort", "y"), NewCategory("Boats", "z"))
        assertEquals(listOf("Boats"), SortValidator.mergeProposals(proposals, emptyList()).map { it.name })
    }

    @Test fun validate_badFriendlyName_fallsBackToSuggested_withoutRetry() {
        val r = reply(
            listOf("Food" to "Meals"),
            listOf(
                Triple(1L, "Food", "Simple images of a photo subject like basketball, bus stop,"),
                Triple(2L, "Food", "Toast... let me think"),
                Triple(3L, "Food", "One two three four five six seven eight"),
            ),
        )
        val v = ok(validate(r))
        assertEquals(listOf("Eggs", "Toast", "One two three four five six seven eight"), v.assignments.map { it.friendlyName })
        assertTrue(v.leftOut.isEmpty())
        val ellipsis = reply(listOf("Food" to "Meals"), chunk.map { Triple(it.id, "Food", "Boat at dock…") })
        assertEquals(listOf("Eggs", "Toast", "Boat"), ok(validate(ellipsis)).assignments.map { it.friendlyName })
    }

    // A full catch-all must not sink the chunk on the retry.

    @Test fun validate_fullCatchAll_onRetry_takesWhatFits_andLeavesTheOverflowOut() {
        val items = (1L..10L).map { item(it) }
        val misc = setOf(2L, 4L, 6L, 8L)
        val r = reply(listOf("Food" to "Meals"), items.map { Triple(it.id, if (it.id in misc) "Misc" else "Food", "N${it.id}") })
        val existing = listOf(category("Misc", 4)) // limit max(5, 40/20) = 5: one slot left
        val first = rejected(validate(r, chunk = items, existing = existing, projectItemCount = 40))
        assertTrue(first.toString(), first.any { it.startsWith("Too many items in a catch-all category 'Misc'") })
        val retry = ok(validate(r, chunk = items, existing = existing, projectItemCount = 40, isRetry = true))
        assertEquals(7, retry.assignments.size)
        assertEquals(listOf(2L), retry.assignments.filter { it.categoryName == "Misc" }.map { it.itemId })
        assertEquals(setOf(4L, 6L, 8L), retry.leftOut.keys)
        assertTrue(retry.leftOut.values.toString(), retry.leftOut.values.all { it.contains("Misc") })
    }

    @Test fun validate_catchAllRoom_onRetry_newTakesWhatFits_fullExistingTakesNone() {
        val items = (1L..8L).map { item(it) }
        val r = reply(emptyList(), items.map { Triple(it.id, if (it.id <= 6) "Food" else "Clip", "N") })
        val retry = ok(validate(r, chunk = items, existing = listOf(category("Videos", 5)), projectItemCount = 40, isRetry = true))
        // "Clip" is a new catch-all: 2 items with a limit of 5 fit, so both are taken.
        assertEquals(listOf("Food", "Clip"), retry.newCategories.map { it.name })
        val full = ok(validate(r, chunk = items, existing = listOf(category("Clips", 5)), projectItemCount = 40, isRetry = true))
        assertEquals(setOf(7L, 8L), full.leftOut.keys)
        assertEquals(listOf("Food"), full.newCategories.map { it.name })
    }

    // Articles don't make a new category.

    @Test fun normalize_dropsArticles() {
        assertEquals(SortValidator.normalize("Sunset Over Sea"), SortValidator.normalize("Sunset Over the Sea"))
        assertEquals("day at beach", SortValidator.normalize("A Day at the Beach"))
        assertEquals("apple tree", SortValidator.normalize("An Apple Tree"))
        assertEquals("theater", SortValidator.normalize("Theater"))
        assertEquals("anchor", SortValidator.normalize("Anchors"))
        assertEquals("the", SortValidator.normalize("The"))
        // A trailing "a" is a name, not an article.
        assertEquals("vitamin a", SortValidator.normalize("Vitamin A"))
        assertTrue(SortValidator.normalize("Vitamin A") != SortValidator.normalize("Vitamin"))
        assertTrue(SortValidator.normalize("Group A") != SortValidator.normalize("Group"))
    }

    @Test fun validate_mergesNamesThatDifferOnlyByArticles() {
        val r = reply(emptyList(), chunk.map { Triple(it.id, "Sunset Over the Sea", "N") })
        val v = ok(validate(r, existing = listOf(category("Sunset Over Sea", 4))))
        assertTrue(v.newCategories.isEmpty())
        assertEquals(setOf("Sunset Over Sea"), v.assignments.map { it.categoryName }.toSet())
    }

    /** M9: the feedback states the same word limit the rule enforces. */
    @Test fun categoryNameProblem_wordLimitFeedbackMatchesTheRule() {
        assertEquals(null, SortValidator.categoryNameProblem("One two three four five"))
        val why = SortValidator.categoryNameProblem("One two three four five six")
        assertEquals("it has 6 words; use at most 5", why)
        val r = reply(emptyList(), chunk.map { Triple(it.id, "One two three four five six", "N") })
        val problems = rejected(validate(r)).joinToString(" | ")
        assertTrue(problems, problems.contains("use at most 5"))
        assertTrue(problems, !problems.contains("at most 3"))
    }
}

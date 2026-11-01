package com.agentic.binning.projectmedia

import com.agentic.binning.sorting.SortValidator
import com.agentic.binning.sorting.cleanName

/**
 * Category name rules for edits on Project Media: a new or renamed category may not normalize to
 * the same thing as another one, so "Sea Lions", "sea lion" and "the Sea-Lions!" are one category.
 *
 * Cleaning and the comparison key are Plan 2a's own ([cleanName] and [SortValidator.normalize]), so
 * sorting and editing always agree on what counts as the same name, and an edited name is as safe
 * for a folder as a name the sorter writes.
 */
object CategoryNames {
    /** Longest category name accepted: the sorter's limit, so both write names of the same length. */
    const val MAX_LENGTH = SortValidator.MAX_CATEGORY_NAME

    /**
     * Cleans a name the user typed the way the sorter cleans a model's: characters a folder name
     * can't hold become spaces, whitespace collapses, and it is cut at [MAX_LENGTH] with no trailing
     * dot or space.
     */
    fun clean(name: String): String = cleanName(name, MAX_LENGTH)

    /** The comparison key: [SortValidator.normalize]. */
    fun key(name: String): String = SortValidator.normalize(name)

    /**
     * The existing name that [name] clashes with, or null if it is free. [except] is the category
     * being renamed, so renaming "Beach" to "Beaches" is not a clash with itself.
     */
    fun clash(name: String, existing: Collection<String>, except: String? = null): String? {
        val k = key(name)
        if (k.isEmpty()) return null
        val exceptKey = except?.let(::key)
        return existing.firstOrNull { key(it) == k && key(it) != exceptKey }
    }
}

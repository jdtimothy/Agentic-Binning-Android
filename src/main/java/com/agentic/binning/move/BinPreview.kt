package com.agentic.binning.move

/** A folder files move out of, for the confirm sheet: "From Camera, DCIM/Camera, 192". */
data class BinSource(val folder: String, val path: String, val count: Int)

/** What a Bin Files would do now, for the confirm sheet (B7, mockup state 1). */
data class BinPreview(
    /** Files the run would move or copy. */
    val files: Int,
    /** Category folders they would go into. */
    val folders: Int,
    /** Folders on this storage, largest first; these files are renamed into place (B1). */
    val sources: List<BinSource>,
    /** Files on another volume, such as an SD card: copied, checked, then deleted (B1). */
    val otherStorage: Int,
    /** Files another project also holds: copied, and the original stays (P8). */
    val shared: Int,
    /** What the copies take while they run (B6). */
    val copyBytes: Long,
    val freeBytes: Long,
    /** Sorted items already at their target. */
    val inPlace: Int,
    /** Failed and Missing items, which stay where they are and don't block. */
    val leftOut: List<LeftOut>,
)

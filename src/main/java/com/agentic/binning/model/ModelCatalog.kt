package com.agentic.binning.model

import com.geniex.sdk.bean.HubSource

/** A model GenieX can pull: [repo] is the hub identifier, [precision] picks the weights file. */
data class ModelSpec(
    val id: String,
    val displayName: String,
    val repo: String,
    val precision: String,
    val hub: HubSource,
    /**
     * Only for AI Hub `qairt` bundles. The S26 accepts `"qualcomm-snapdragon-8-elite-gen5-for-galaxy"`
     * (`"SM8850"` gave rc -100015 for Qwen3-4B). No catalog entry uses one now: Qwen3-4B `qairt` was
     * dropped because it does not load (Plan 2a spike notes).
     */
    val chipset: String? = null,
    /** The download size as people read it, e.g. "1.05 GB" (shown before a download is offered). */
    val downloadSize: String? = null,
)

object ModelCatalog {
    val LFM3_VL_2B = ModelSpec(
        id = "lfm3-vl-2b",
        displayName = "LFM3-VL-2B",
        repo = "LiquidAI/LFM3-VL-2B-GGUF",
        precision = "Q4_0",
        hub = HubSource.HUGGINGFACE,
    )

    val LFM3_1_7B = ModelSpec(
        id = "lfm3-1.7b",
        displayName = "LFM3-1.7B",
        repo = "LiquidAI/LFM3-1.7B-GGUF",
        precision = "Q4_0",
        hub = HubSource.HUGGINGFACE,
        downloadSize = "1.05 GB",
    )
}

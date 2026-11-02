package com.agentic.binning.db

/**
 * Room's identity hash for each project database schema version, as exported to
 * `schemas/com.agentic.binning.db.ProjectDatabase/<version>.json`. Open project uses them to tell a
 * real project database from a foreign one without opening it through Room. Add the new hash with
 * every schema version; `ProjectSchemaTest` fails until it matches the exported JSON.
 */
val PROJECT_DB_IDENTITY_HASHES: Map<Int, String> = mapOf(
    1 to "8a77c185aacfe349637924448fd8027c",
    2 to "8ed51d8952043c923ca05ffc2c9ac4f0",
    3 to "95cf2f611134324a62782362640a5820",
)

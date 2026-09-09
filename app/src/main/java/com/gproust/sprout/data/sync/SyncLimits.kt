package com.gproust.sprout.data.sync

/**
 * What a file has to be under before Sprout will try to read it (ADR-0014).
 *
 * Everything else in this package parses a file that *arrived* — an invitation
 * or a replica, handed over by whatever app the other parent used. The manifest
 * filter that receives it is deliberately broad (there is nothing in a
 * messaging app's `content://` URI to match on), so "a file Sprout was asked to
 * open" means "bytes chosen by whoever sent them", and the first thing every
 * decoder does is decide whether they are worth parsing at all.
 *
 * Both ceilings are plausibility checks, not security boundaries: what actually
 * decides whether a replica is yours is the AES-GCM tag in [SyncCrypto]. These
 * only stop a file no Sprout ever wrote from being expensive to refuse.
 */
object SyncLimits {

    /**
     * The largest file Sprout will read into memory.
     *
     * The same ceiling [com.gproust.sprout.data.sync.nearby.SyncSession] puts on
     * the radio, and for the same reason: a replica of a year's tracking is a
     * few hundred kilobytes, so eight megabytes leaves room for a family that
     * logs far more than average while still refusing to allocate a buffer
     * because someone else's file claimed a ridiculous size. Spelled out again
     * here rather than shared, because the two paths are independent — the file
     * one has no radio in it — and a constant that travels between them would
     * make each look like it depends on the other.
     */
    const val MAX_FILE_BYTES: Int = 8 * 1024 * 1024

    /**
     * How deeply a Sprout document may nest.
     *
     * A replica is four levels deep at its worst — the document, a list, a row,
     * a value — so thirty-two is far more headroom than the format can use, and
     * still nowhere near enough to matter to a parser that recurses. Which
     * Android's `org.json` does: `JSONTokener` calls itself once per nested
     * value. Measured against the real runtime with an 8 MB stack, about twenty
     * thousand nested brackets — a forty-kilobyte file — is enough to exhaust
     * it.
     *
     * That has to be caught *before* parsing rather than around it, because
     * running out of stack raises `StackOverflowError` — an `Error`, not an
     * `Exception`, so the `catch (e: Exception)` every decoder wraps its parse
     * in lets it straight through and the app goes with it. Depth is the one
     * property of the input that says so in advance.
     */
    const val MAX_JSON_DEPTH: Int = 32

    /**
     * Whether [text] nests deeper than [MAX_JSON_DEPTH].
     *
     * Counts brackets rather than parsing, which makes it a scan of the string
     * and not a second implementation of JSON. Contents of a double-quoted
     * string don't count — a parent whose note is a row of brackets is writing
     * a note, not a document — and a backslash inside one escapes whatever
     * follows it, so `"\""` stays open.
     *
     * Everything outside such a string counts, including brackets in the places
     * Android's lenient parser also allows: single-quoted strings, comments,
     * unquoted names. That is the safe direction to be wrong in. Over-counting
     * refuses a file that no Sprout ever produced; under-counting would hand
     * the parser the very input this exists to keep away from it.
     */
    fun exceedsMaxJsonDepth(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_JSON_DEPTH) return true
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return false
    }
}

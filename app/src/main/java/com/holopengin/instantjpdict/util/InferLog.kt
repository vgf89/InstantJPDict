package com.holopengin.instantjpdict.util

/** In-memory ring of recent inference diagnostics (detect/rec shapes,
 * batch timings, infer failures) for on-device debugging without logcat.
 * Written from IO threads, read on Main — all access synchronized. */
object InferLog {
    private const val CAP = 400
    private val lines = ArrayDeque<String>(CAP)
    private var dropped = 0

    @Synchronized
    fun add(msg: String) {
        if (lines.size >= CAP) {
            lines.removeFirst()
            dropped++
        }
        lines.addLast(msg)
    }

    @Synchronized
    fun dump(): String = buildString {
        appendLine("infer-log lines=${lines.size} dropped=$dropped")
        for (l in lines) appendLine(l)
    }

    @Synchronized
    fun clear() {
        lines.clear()
        dropped = 0
    }
}

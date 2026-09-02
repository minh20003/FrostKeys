package helium314.keyboard.latin.utils

import helium314.keyboard.latin.BuildConfig
import java.time.LocalDateTime
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * Logger that does the android logging, but also allows reading the log in the app.
 * It's only a little slower than the android logger, but since both are used we end up at
 * half performance (still fast enough to not be noticeable, unless spamming thousands of log lines)
 */
object Log {
    /**
     * A diagnostic ring must never turn into an unbounded in-memory copy of user input.  A
     * byte bound (rather than a line bound) also protects us from a single server error or
     * stack trace containing a very large response.
     */
    private const val MAX_RELEASE_BUFFER_BYTES = 512 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024

    @JvmStatic
    fun wtf(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('F', tag, safeMessage))
        android.util.Log.wtf(tag, safeMessage)
    }

    @JvmStatic
    fun e(tag: String?, message: String, e: Throwable?) {
        val safeMessage = messageWithThrowable(message, e)
        log(LogLine('E', tag, safeMessage))
        android.util.Log.e(tag, safeMessage)
    }

    @JvmStatic
    fun e(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('E', tag, safeMessage))
        android.util.Log.e(tag, safeMessage)
    }

    @JvmStatic
    fun w(tag: String?, message: String, e: Throwable?) {
        val safeMessage = messageWithThrowable(message, e)
        log(LogLine('W', tag, safeMessage))
        android.util.Log.w(tag, safeMessage)
    }

    @JvmStatic
    fun w(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('W', tag, safeMessage))
        android.util.Log.w(tag, safeMessage)
    }

    @JvmStatic
    fun i(tag: String?, message: String, e: Throwable?) {
        val safeMessage = messageWithThrowable(message, e)
        log(LogLine('I', tag, safeMessage))
        if (BuildConfig.DEBUG) android.util.Log.i(tag, safeMessage)
    }

    @JvmStatic
    fun i(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('I', tag, safeMessage))
        if (BuildConfig.DEBUG) android.util.Log.i(tag, safeMessage)
    }

    @JvmStatic
    fun d(tag: String?, message: String, e: Throwable?) {
        val safeMessage = messageWithThrowable(message, e)
        log(LogLine('D', tag, safeMessage))
        if (BuildConfig.DEBUG) android.util.Log.d(tag, safeMessage)
    }

    @JvmStatic
    fun d(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('D', tag, safeMessage))
        if (BuildConfig.DEBUG) android.util.Log.d(tag, safeMessage)
    }

    @JvmStatic
    fun v(tag: String?, message: String) {
        val safeMessage = sanitize(message)
        log(LogLine('V', tag, safeMessage))
        if (BuildConfig.DEBUG) android.util.Log.v(tag, safeMessage)
    }

    private fun log(line: LogLine) {
        synchronized(logLines) {
            // Production diagnostics intentionally retain only faults.  Debug builds keep the
            // familiar verbose trace, but both use the same redacted, byte-capped ring.
            if (!BuildConfig.DEBUG && line.level !in RELEASE_LEVELS) return
            val bounded = line.copy(message = truncate(line.message))
            val lineBytes = byteSize(bounded)
            while (logLines.isNotEmpty() && bufferedBytes + lineBytes > MAX_RELEASE_BUFFER_BYTES) {
                bufferedBytes -= byteSize(logLines.removeFirst())
            }
            // A line can never exceed the ring by itself because [truncate] capped it.
            bufferedBytes += lineBytes
            logLines.addLast(bounded)
        }
    }

    private fun messageWithThrowable(message: String, throwable: Throwable?): String {
        val safeMessage = sanitize(message)
        if (throwable == null) return safeMessage
        // Stack traces and exception messages can embed HTTP bodies, editor content or query
        // parameters. Release diagnostics therefore retain only the exception class; debug keeps
        // the trace for development without bypassing sanitization.
        val summary = throwable.javaClass.name
        return if (BuildConfig.DEBUG) "$safeMessage\n${sanitize(throwable.stackTraceToString())}"
        else "$safeMessage\n${sanitize(summary)}"
    }

    private fun truncate(message: String): String {
        if (message.toByteArray(StandardCharsets.UTF_8).size <= MAX_LINE_BYTES) return message
        // Use a character boundary; an approximate cut is enough because the ring's purpose is
        // diagnostics, and avoids allocating another unbounded intermediate string.
        var end = message.length.coerceAtMost(MAX_LINE_BYTES)
        while (end > 0 && message.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > MAX_LINE_BYTES - 32) {
            end -= 1
        }
        return message.substring(0, end) + " … [truncated]"
    }

    private fun sanitize(message: String): String {
        var safe = message
        // URL query values are particularly easy to leak through OkHttp/exception messages.
        safe = URL_SECRET.replace(safe) { match -> "${match.groupValues[1]}<redacted>" }
        // Search and editor values can also be present as ordinary URL parameters (for example
        // Klipy's q parameter); redact their value without discarding the rest of the error.
        safe = PRIVATE_QUERY_VALUE.replace(safe) { match -> "${match.groupValues[1]}<redacted>" }
        // Klipy's documented endpoint places the customer key in the path rather than in a
        // query parameter. Network exceptions commonly reproduce the complete URL.
        safe = KLIPY_PATH_SECRET.replace(safe) { match -> "${match.groupValues[1]}<redacted>" }
        // Clipboard providers and image handling can surface content/file URIs in exception
        // messages. They are user data, not useful production diagnostics.
        safe = LOCAL_URI.replace(safe, "<redacted-uri>")
        // Handles common header/property forms without trying to parse arbitrary untrusted JSON.
        safe = ASSIGNMENT_SECRET.replace(safe) { match -> "${match.groupValues[1]}<redacted>" }
        safe = JSON_SECRET.replace(safe) { match -> "${match.groupValues[1]}<redacted>\"" }
        // Raw editor/cloud payload fields must never be copied to the diagnostic ring.
        safe = JSON_CONTENT.replace(safe) { match -> "${match.groupValues[1]}<redacted>\"" }
        return safe
    }

    private fun byteSize(line: LogLine): Int = line.toString().toByteArray(StandardCharsets.UTF_8).size

    private val logLines = ArrayDeque<LogLine>(256)
    private var bufferedBytes = 0

    /** returns a copy of [logLines] */
    fun getLog(maxLines: Int = logLines.size) = synchronized(logLines) {
        logLines.toList().takeLast(maxLines.coerceAtLeast(0))
    }

    private val RELEASE_LEVELS = setOf('F', 'E', 'W')
    private val URL_SECRET = Regex("(?i)([?&](?:api[_-]?key|key|token|access_token|authorization)=)[^&#\\s]+")
    private val PRIVATE_QUERY_VALUE = Regex("(?i)([?&](?:q|query|prompt|text|clipboard|response)=)[^&#\\s]+")
    private val KLIPY_PATH_SECRET = Regex("(?i)(https?://api\\.klipy\\.com/api/v1/)[^/?#\\s]+")
    private val LOCAL_URI = Regex("(?i)\\b(?:content|file)://[^\\s\"'<>]+")
    private val ASSIGNMENT_SECRET = Regex(
        "(?i)\\b((?:api[_-]?key|access[_-]?token|token|authorization|password|secret)\\s*[=:]\\s*)(?:(?:bearer)\\s+)?(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,}&]+)"
    )
    private val JSON_SECRET = Regex(
        "(?i)(\\\"(?:api[_-]?key|access[_-]?token|token|authorization|password|secret)\\\"\\s*:\\s*)\\\"(?:\\\\.|[^\\\"])*\\\""
    )
    private val JSON_CONTENT = Regex(
        "(?i)(\\\"(?:prompt|query|clipboard|response|text)\\\"\\s*:\\s*)\\\"(?:\\\\.|[^\\\"])*\\\""
    )
}

data class LogLine(val level: Char, val tag: String?, val message: String) {

    private val time = LocalDateTime.now()

    override fun toString(): String = // should look like a normal android log line, at least for api26+
        "${time.toString().replace('T', ' ')} $level $tag: $message"
}

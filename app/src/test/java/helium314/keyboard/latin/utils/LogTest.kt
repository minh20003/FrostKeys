package helium314.keyboard.latin.utils

import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LogTest {
    @Test
    fun releaseDiagnosticLineRedactsCloudAndEditorValues() {
        Log.e(
            "LogTest",
            "request=https://example.invalid/search?api_key=super-secret " +
                "klipy=https://api.klipy.com/api/v1/klipy-secret/gifs/search?q=ri%C3%AAng+t%C6%B0 " +
                "clipboard-uri=content://private/screenshot " +
                "Authorization: Bearer token-value " +
                "{\"prompt\":\"văn bản riêng tư\",\"text\":\"clipboard riêng tư\"}",
        )

        val message = Log.getLog(1).single().message
        assertFalse(message.contains("super-secret"))
        assertFalse(message.contains("klipy-secret"))
        assertFalse(message.contains("ri%C3%AAng+t%C6%B0"))
        assertFalse(message.contains("content://private/screenshot"))
        assertFalse(message.contains("token-value"))
        assertFalse(message.contains("văn bản riêng tư"))
        assertFalse(message.contains("clipboard riêng tư"))
        assertTrue(message.contains("<redacted>"))
    }

    @Test
    fun diagnosticLineHasABoundedSize() {
        Log.e("LogTest", "x".repeat(32 * 1024))

        val message = Log.getLog(1).single().message
        assertTrue(message.toByteArray(StandardCharsets.UTF_8).size <= 8 * 1024)
    }

    @Test
    fun releaseThrowableSummaryDoesNotRetainItsMessage() {
        val privateText = "đoạn văn người dùng không được ghi log"
        Log.e("LogTest", "Operation failed", IllegalStateException(privateText))

        val message = Log.getLog(1).single().message
        assertFalse(message.contains(privateText))
        assertTrue(message.contains(IllegalStateException::class.java.name))
    }
}

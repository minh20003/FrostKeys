// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.latin.utils.prefs
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CloudRequestGateTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        setCloudEnabled(false)
        CloudRequestGate.cancelAll()
    }

    @After
    fun tearDown() {
        setCloudEnabled(false)
        CloudRequestGate.cancelAll()
    }

    @Test
    fun disabledGateDoesNotReachTheNetworkClient() {
        val networkAttempts = AtomicInteger(0)
        val gate = CloudRequestGateImpl(
            OkHttpClient.Builder().addInterceptor {
                networkAttempts.incrementAndGet()
                error("The gate must block before this interceptor runs")
            }.build(),
        )
        try {
            gate.init(context)
            val request = Request.Builder().url("https://unit.test/disabled").build()

            assertFailsWith<SecurityException> {
                gate.execute(context, CloudFeature.KLIPY_MEDIA, request)
            }
            assertFailsWith<SecurityException> {
                gate.callFactory(context, CloudFeature.KLIPY_MEDIA).newCall(request)
            }
            assertEquals(0, networkAttempts.get())
        } finally {
            gate.closeForTests()
        }
    }

    @Test
    fun disablingCloudCancelsAnInFlightRequest() {
        setCloudEnabled(true)
        val requestStarted = CountDownLatch(1)
        val requestFailed = CountDownLatch(1)
        val gate = CloudRequestGateImpl(
            OkHttpClient.Builder().addInterceptor { chain ->
                requestStarted.countDown()
                val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (!chain.call().isCanceled() && System.nanoTime() < deadlineNanos) {
                    Thread.sleep(5)
                }
                throw IOException("test request cancelled")
            }.build(),
        )
        try {
            gate.init(context)
            val call = gate.enqueue(
                context,
                CloudFeature.AI_WRITING_TOOLS,
                Request.Builder().url("https://unit.test/pending").build(),
                object : Callback {
                    override fun onFailure(call: okhttp3.Call, e: IOException) {
                        requestFailed.countDown()
                    }

                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        response.close()
                    }
                },
            )

            assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
            setCloudEnabled(false)
            assertTrue(requestFailed.await(2, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
        } finally {
            gate.closeForTests()
        }
    }

    @Test
    fun disablingCloudPreventsAlreadyReturnedResponseFromBeingRead() {
        setCloudEnabled(true)
        val gate = CloudRequestGateImpl(
            OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("private response".toResponseBody())
                    .build()
            }.build(),
        )
        try {
            gate.init(context)
            val response = gate.execute(
                context,
                CloudFeature.KLIPY_MEDIA,
                Request.Builder().url("https://unit.test/response").build(),
            )
            setCloudEnabled(false)

            assertFailsWith<IOException> { response.body!!.string() }
            response.close()
        } finally {
            gate.closeForTests()
        }
    }

    private fun setCloudEnabled(enabled: Boolean) {
        check(context.prefs().edit()
            .putBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, enabled)
            .commit())
    }
}

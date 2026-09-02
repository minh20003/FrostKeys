// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.cloud

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.latin.utils.prefs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Optional network capability requested by a FrostKeys feature. */
enum class CloudFeature {
    TEST_CONNECTION,
    AI_WRITING_TOOLS,
    KLIPY_MEDIA,
    TRANSLATION,
}

/**
 * The only component allowed to create optional-cloud [Call] instances.
 *
 * It enforces the global opt-in both before a call is created and while a response is consumed.
 * Turning the switch off cancels every active call, prevents queued calls from reaching the
 * network, and prevents a response already handed to a caller from yielding more data. This is
 * deliberately separate from [CloudManager], which owns encrypted credentials only.
 */
object CloudRequestGate {
    private val delegate = CloudRequestGateImpl()

    /** Initializes preference observation only; this method never creates a network request. */
    fun init(context: Context) = delegate.init(context)

    /** True only when the global cloud switch is currently enabled. */
    fun isFeatureAllowed(context: Context, feature: CloudFeature): Boolean =
        delegate.isFeatureAllowed(context, feature)

    /** Executes a blocking request after enforcing the master switch and HTTPS-only transport. */
    @Throws(IOException::class, SecurityException::class)
    fun execute(context: Context, feature: CloudFeature, request: Request): Response =
        delegate.execute(context, feature, request)

    /** Enqueues a request which remains registered until it fails or its response body closes. */
    @Throws(SecurityException::class)
    fun enqueue(context: Context, feature: CloudFeature, request: Request, callback: Callback): Call =
        delegate.enqueue(context, feature, request, callback)

    /**
     * A Coil/OkHttp call factory that still routes image fetches through this gate. This avoids a
     * separate image-loader client silently reaching Klipy while the cloud switch is off.
     */
    fun callFactory(context: Context, feature: CloudFeature): Call.Factory =
        delegate.callFactory(context, feature)

    /** Cancels every optional-cloud call immediately. */
    fun cancelAll() = delegate.cancelAll()

    /** Cancels only calls belonging to a panel or feature that is closing. */
    fun cancelFeature(feature: CloudFeature) = delegate.cancelFeature(feature)
}

/**
 * Instance implementation kept testable with an injected client. Production code always uses
 * [CloudRequestGate]; this class exists so the no-request and cancellation guarantees can be
 * regression-tested without exposing a bypass in the app API.
 */
internal class CloudRequestGateImpl(
    private val suppliedClient: OkHttpClient? = null,
) : SharedPreferences.OnSharedPreferenceChangeListener {
    private val initialized = AtomicBoolean(false)
    private val cloudEnabled = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap<Call, CloudFeature>()

    private val httpClient: OkHttpClient by lazy {
        suppliedClient ?: OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Re-check immediately before OkHttp touches the network. The preflight check alone
            // cannot cover a preference change occurring in the tiny window after newCall().
            .addInterceptor { chain ->
                if (!cloudEnabled.get()) {
                    throw IOException("Cloud features are disabled")
                }
                chain.proceed(chain.request())
            }
            .build()
    }

    private lateinit var appContext: Context

    fun init(context: Context) {
        val applicationContext = context.applicationContext
        if (initialized.compareAndSet(false, true)) {
            appContext = applicationContext
            applicationContext.prefs().registerOnSharedPreferenceChangeListener(this)
        }
        refreshCloudEnabled(applicationContext)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == CloudManager.PREF_ENABLE_CLOUD_FEATURES && ::appContext.isInitialized) {
            refreshCloudEnabled(appContext)
        }
    }

    fun isFeatureAllowed(context: Context, feature: CloudFeature): Boolean {
        // [feature] intentionally remains part of the API: feature-scoped cancellation and audit
        // need the identity even though the current product has one master cloud switch.
        init(context)
        return refreshCloudEnabled(context.applicationContext)
    }

    @Throws(IOException::class, SecurityException::class)
    fun execute(context: Context, feature: CloudFeature, request: Request): Response {
        return createGuardedCall(context, feature, request).execute()
    }

    @Throws(SecurityException::class)
    fun enqueue(context: Context, feature: CloudFeature, request: Request, callback: Callback): Call {
        return createGuardedCall(context, feature, request).also { it.enqueue(callback) }
    }

    fun callFactory(context: Context, feature: CloudFeature): Call.Factory = Call.Factory { request ->
        createGuardedCall(context, feature, request)
    }

    fun cancelAll() {
        activeCalls.keys.toList().forEach { it.cancel() }
        activeCalls.clear()
    }

    fun cancelFeature(feature: CloudFeature) {
        activeCalls.entries
            .filter { it.value == feature }
            .forEach { (call, _) ->
                if (activeCalls.remove(call, feature)) {
                    call.cancel()
                }
            }
    }

    private fun newTrackedCall(context: Context, feature: CloudFeature, request: Request): Call {
        requireRequestAllowed(context, feature, request)
        val call = httpClient.newCall(request)
        // Do not leave a call in the tracking map if the switch changed after the preflight.
        if (!isFeatureAllowed(context, feature)) {
            call.cancel()
            throw SecurityException("Cloud features are disabled; request for $feature was blocked")
        }
        activeCalls[call] = feature
        return call
    }

    private fun createGuardedCall(context: Context, feature: CloudFeature, request: Request): Call {
        val rawCall = newTrackedCall(context, feature, request)
        return GuardedCall(
            rawCall = rawCall,
            transformResponse = { call, response -> trackedResponse(call, response) },
            onFinished = { call -> activeCalls.remove(call) },
            onClone = { createGuardedCall(context, feature, request) },
        )
    }

    private fun requireRequestAllowed(context: Context, feature: CloudFeature, request: Request) {
        if (!request.url.isHttps) {
            throw SecurityException("Cloud request for $feature must use HTTPS")
        }
        if (!isFeatureAllowed(context, feature)) {
            throw SecurityException("Cloud features are disabled; request for $feature was blocked")
        }
    }

    private fun refreshCloudEnabled(context: Context): Boolean {
        val enabled = context.prefs().getBoolean(CloudManager.PREF_ENABLE_CLOUD_FEATURES, false)
        val wasEnabled = cloudEnabled.getAndSet(enabled)
        if (wasEnabled && !enabled) {
            cancelAll()
        }
        return enabled
    }

    /** Keeps a call cancellable until its response body has actually been consumed or closed. */
    private fun trackedResponse(call: Call, response: Response): Response {
        val originalBody = response.body ?: run {
            activeCalls.remove(call)
            return response
        }
        var finished = false
        fun finish() {
            if (!finished) {
                finished = true
                activeCalls.remove(call)
            }
        }
        val trackedBody = object : ResponseBody() {
            private val trackedSource: BufferedSource = object : ForwardingSource(originalBody.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (!cloudEnabled.get()) {
                        call.cancel()
                        finish()
                        throw IOException("Cloud features were disabled while reading a response")
                    }
                    return super.read(sink, byteCount)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        finish()
                    }
                }
            }.buffer()

            override fun contentType() = originalBody.contentType()

            override fun contentLength() = originalBody.contentLength()

            override fun source(): BufferedSource = trackedSource

            override fun close() {
                try {
                    trackedSource.close()
                } finally {
                    finish()
                }
            }
        }
        return response.newBuilder().body(trackedBody).build()
    }

    /** Test-only lifecycle cleanup for independently constructed gates. */
    internal fun closeForTests() {
        if (initialized.compareAndSet(true, false) && ::appContext.isInitialized) {
            appContext.prefs().unregisterOnSharedPreferenceChangeListener(this)
        }
        cancelAll()
    }

    /** Bridges third-party clients such as Coil without giving them a raw, ungated [Call]. */
    private class GuardedCall(
        private val rawCall: Call,
        private val transformResponse: (Call, Response) -> Response,
        private val onFinished: (Call) -> Unit,
        private val onClone: () -> Call,
    ) : Call {
        override fun request(): Request = rawCall.request()

        @Throws(IOException::class)
        override fun execute(): Response = try {
            transformResponse(rawCall, rawCall.execute())
        } catch (e: Exception) {
            onFinished(rawCall)
            throw e
        }

        override fun enqueue(responseCallback: Callback) {
            try {
                rawCall.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        onFinished(rawCall)
                        responseCallback.onFailure(this@GuardedCall, e)
                    }

                    @Throws(IOException::class)
                    override fun onResponse(call: Call, response: Response) {
                        val guardedResponse = transformResponse(rawCall, response)
                        try {
                            responseCallback.onResponse(this@GuardedCall, guardedResponse)
                        } catch (e: Exception) {
                            guardedResponse.close()
                            throw e
                        }
                    }
                })
            } catch (e: Exception) {
                onFinished(rawCall)
                throw e
            }
        }

        override fun cancel() {
            onFinished(rawCall)
            rawCall.cancel()
        }

        override fun isExecuted(): Boolean = rawCall.isExecuted()

        override fun isCanceled(): Boolean = rawCall.isCanceled()

        override fun timeout(): Timeout = rawCall.timeout()

        override fun clone(): Call = onClone()
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 5L
        const val READ_TIMEOUT_SECONDS = 20L
        const val CALL_TIMEOUT_SECONDS = 25L
    }
}

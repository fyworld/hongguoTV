package com.hongguotv.core

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Identity-blocklist self-healing (upstream b597f28) + bytevc2 compat model
 * (upstream 265472f) for MultiVideoModelStreamSource.
 *
 * A "round" is one stream() call: tab endpoint (empty = blocked identity) then
 * the old POST path with its 3-attempt retry. Two fully-empty rounds arm the
 * rotation; the third round rotates BEFORE its requests go out.
 */
class MultiVideoModelRotationTest {

    /** Always-blocked fixture: registration OK, tab + POST both empty forever. */
    private fun blockedHttp(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            respond(request, "", setCookie = request.url.encodedPath == "/service/2/device_register/")
        }
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun source(http: OkHttpClient, session: PlaybackDeviceSession) =
        MultiVideoModelStreamSource(AppApiTransport(http), http, session, sleep = {})

    @Test
    fun twoConsecutiveEmptyRoundsRotateIdentityOnceAndTagSubsequentFailures() {
        val registrations = AtomicInteger()
        val session = PlaybackDeviceSession { registrations.incrementAndGet(); DeviceRegister.Registered("333", "444") }
        val http = blockedHttp()
        val source = source(http, session)

        // Round 1: tab empty + POST empty (3 attempts) - one empty round, no rotation yet.
        val first = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertFalse(first.detail.orEmpty().contains("rotated"))
        assertEquals(1, registrations.get())
        // Round 2: still empty - threshold (2) reached only after this round completes.
        val second = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertFalse(second.detail.orEmpty().contains("rotated"))
        assertEquals(1, registrations.get())
        // Round 3: rotation happens BEFORE this round; detail now carries rotated=1.
        val third = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertTrue(third.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
        // Round 4: this failure period already rotated - no second rotation.
        val fourth = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertTrue(fourth.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
    }

    @Test
    fun successfulPlaybackResetsIdentityRotationStateForTheNextIncident() {
        val registrations = AtomicInteger()
        val session = PlaybackDeviceSession { registrations.incrementAndGet(); DeviceRegister.Registered("333", "444") }
        // Tab recovers on the 3rd stream() call (identity unblocked), then blocked again.
        val tabCalls = AtomicInteger()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                when {
                    path == "/service/2/device_register/" ->
                        respond(request, """{"device_id":"333","install_id":"444"}""", setCookie = true)
                    path == MultiVideoModelStreamSource.TAB_PATH ->
                        respond(request, if (tabCalls.incrementAndGet() == 3) tabModel() else "")
                    else -> respond(request, "")
                }
            }
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val source = source(http, session)

        // Two empty rounds arm the rotation...
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertEquals(1, registrations.get())
        // ...then the tab endpoint recovers: success resets the healing state.
        val recovered = source.stream("123", 1080, ContentType.SHORT)
        assertEquals("https://example.test/video", recovered.url)
        assertEquals(1, registrations.get()) // success without rotation
        // New incident counts fresh: two empty rounds, then the third rotates again.
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertEquals(1, registrations.get())
        val third = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertTrue(third.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, registrations.get())
    }

    @Test
    fun rotateFailureStillCountsTheRoundAndNeverRetriesWithinThePeriod() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            if (calls.incrementAndGet() == 1) DeviceRegister.Registered("333", "444")
            else throw java.io.IOException("registration down")
        }
        val source = source(blockedHttp(), session)
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        // Round 3: rotation attempt fails (registration down) - the period is still marked
        // rotated: no retry, the old identity keeps serving (profile stays cached).
        val third = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertTrue(third.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, calls.get()) // 1 initial registration + 1 failed rotation attempt
        // Round 4: no further rotation attempts within this failure period.
        val fourth = assertThrows(EmptyPlaybackResponseException::class.java) { source.stream("123", 1080, ContentType.SHORT) }
        assertTrue(fourth.detail.orEmpty().contains("rotated=1"))
        assertEquals(2, calls.get())
    }

    @Test
    fun bytevc2OnlyFallbackRequestsOneCompatibleModelAndPreservesOtherParameters() {
        val fallbackRequests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                when (request.url.encodedPath) {
                    "/service/2/device_register/" -> respond(request, """{"device_id":"333","install_id":"444"}""", setCookie = true)
                    MultiVideoModelStreamSource.TAB_PATH -> respond(request, "")
                    MultiVideoModelStreamSource.PATH -> respond(request, fallbackOnlyModel())
                    else -> {
                        fallbackRequests.add(request)
                        // Original fallback: all bytevc2. Compat request: returns h264.
                        if (request.url.queryParameter("force_codec") == "h264") respond(request, compatModel())
                        else respond(request, bytevc2Model())
                    }
                }
            }
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val source = source(http, PlaybackDeviceSession())

        val info = source.stream("123", 1080, ContentType.SHORT)
        assertEquals("https://example.test/compat-video", info.url)
        assertEquals("720P", info.quality)
        assertEquals(2, fallbackRequests.size) // original + exactly one compat request
        // The compat request keeps the original URL's other parameters (token here).
        assertEquals("fixture", fallbackRequests.last().url.queryParameter("token"))
        assertEquals("0", fallbackRequests.last().url.queryParameter("codec_type"))
        assertEquals("h264", fallbackRequests.last().url.queryParameter("force_codec"))
    }

    @Test
    fun mixedCodecsSkipTheCompatRequest() {
        val fallbackRequests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                when (request.url.encodedPath) {
                    "/service/2/device_register/" -> respond(request, """{"device_id":"333","install_id":"444"}""", setCookie = true)
                    MultiVideoModelStreamSource.TAB_PATH -> respond(request, "")
                    MultiVideoModelStreamSource.PATH -> respond(request, fallbackOnlyModel())
                    else -> {
                        fallbackRequests.add(request)
                        respond(request, mixedModel())
                    }
                }
            }
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
        val source = source(http, PlaybackDeviceSession())

        val info = source.stream("123", 1080, ContentType.SHORT)
        assertEquals("https://example.test/h265-video", info.url)
        assertEquals(1, fallbackRequests.size) // h265 present - no compat request
    }

    // ---------- fixtures ----------

    private fun respond(request: Request, body: String, setCookie: Boolean = false): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(200).message("fixture")
            .apply { if (setCookie) addHeader("Set-Cookie", "ttreq=test; Path=/") }
            .body(body.toResponseBody())
            .build()

    /** POST response whose model has only fallback_api (no inline video_list). */
    private fun fallbackOnlyModel() = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("123", JSONObject()
            .put("video_model", JSONObject().put("fallback_api", "https://api.fqnovel.com/fallback?token=fixture"))))
        .toString()

    /** Fallback body: every offered variant is bytevc2 (TV-unplayable). */
    private fun bytevc2Model() = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
            .put("0", JSONObject()
                .put("main_url", "https://example.test/bytevc2-video")
                .put("codec_type", "bytevc2")
                .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "bytevc2"))))))
        .toString()

    /** Compat body: the h264 variant the compat request returns. */
    private fun compatModel() = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
            .put("0", JSONObject()
                .put("main_url", "https://example.test/compat-video")
                .put("codec_type", "h264")
                .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264"))))))
        .toString()

    /** Fallback body: h265 + bytevc2 mix - compat request must NOT trigger. */
    private fun mixedModel() = JSONObject()
        .put("video_info", JSONObject().put("data", JSONObject().put("video_list", JSONObject()
            .put("0", JSONObject()
                .put("main_url", "https://example.test/h265-video")
                .put("codec_type", "h265")
                .put("video_meta", JSONObject().put("definition", "1080p").put("codec_type", "h265")))
            .put("1", JSONObject()
                .put("main_url", "https://example.test/bytevc2-video")
                .put("codec_type", "bytevc2")
                .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "bytevc2"))))))
        .toString()

    /** Tab-endpoint success body (identity works again). */
    private fun tabModel() = JSONObject()
        .put("code", 0)
        .put("data", JSONObject().put("video_model_data", JSONObject().put("123", JSONObject()
            .put("video_model", JSONObject().put("video_list", JSONObject()
                .put("0", JSONObject()
                    .put("main_url", "https://example.test/video")
                    .put("codec_type", "h264")
                    .put("video_meta", JSONObject().put("definition", "720p").put("codec_type", "h264"))))))))
        .toString()
}

package com.hongguotv.core

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity rotation (upstream b597f28): rotate() re-registers, failure keeps the old identity. */
class PlaybackDeviceSessionTest {
    private val http = OkHttpClient()

    @Test
    fun rotateRegistersFreshIdentityAndKeepsItCachedWithoutReregistering() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "test-only"))
        }
        val first = session.profile(http)
        assertEquals(1, calls.get())
        assertTrue(session.rotate(http))
        assertEquals(2, calls.get())
        val second = session.profile(http)
        assertEquals(2, calls.get()) // rotate already registered and cached - no re-register
        assertNotSame(first, second)
        assertNotEquals(first.values["cdid"], second.values["cdid"]) // fresh cdid = fresh identity
        assertEquals("333", second.values["device_id"])
        assertEquals("ttreq=test-only", second.cookie)
    }

    @Test
    fun rotateFailureKeepsTheWorkingIdentity() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            if (calls.incrementAndGet() == 1)
                DeviceRegister.Registered("333", "444", cookies = mapOf("ttreq" to "keep"))
            else throw IOException("test-only")
        }
        val first = session.profile(http)
        assertFalse(session.rotate(http))
        assertEquals(2, calls.get())
        assertSame(first, session.profile(http)) // failed rotation keeps the identity, no re-register
        assertEquals(2, calls.get())
    }

    @Test
    fun rotateIgnoresInvalidRegistrationResults() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("0", "444") // device_id "0" fails the [1-9]... pattern
        }
        assertFalse(session.rotate(http))
        // Invalid result was not cached: profile re-registers and rejects as usual.
        try {
            session.profile(http)
            throw AssertionError("expected PlaybackDeviceRegistrationException")
        } catch (expected: PlaybackDeviceRegistrationException) {
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun rotateWorksBeforeFirstProfileRegistration() {
        val calls = AtomicInteger()
        val session = PlaybackDeviceSession {
            calls.incrementAndGet()
            DeviceRegister.Registered("333", "444")
        }
        assertTrue(session.rotate(http))
        assertEquals(1, calls.get())
        val profile = session.profile(http)
        assertEquals(1, calls.get()) // rotate's registration is reused, profile never re-registers
        assertEquals("333", profile.values["device_id"])
    }
}

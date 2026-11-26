package org.polyfrost.polyplus.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.polyfrost.polyplus.client.network.http.MinecraftLoginGate
import org.polyfrost.polyplus.client.network.http.PolyAuthorization
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class MinecraftLoginGateTest {
    private val noFallthrough = 60_000L

    private suspend fun authorizeWithin(millis: Long): String? =
        withTimeoutOrNull(millis) { MinecraftLoginGate.whileNotLoggingIn(noFallthrough) { "authorized" } }

    @Test
    fun `the gate serialises the game's login against our authorization`() = runBlocking {
        assertEquals("authorized", authorizeWithin(1_000L), "an idle gate must not block")

        val connection = Any()
        val outer = MinecraftLoginGate.begin(connection)
        val inner = MinecraftLoginGate.begin(connection)
        assertTrue(outer && inner, "a login nested on the same thread must not deadlock on itself")

        MinecraftLoginGate.end(connection, inner)
        assertNull(authorizeWithin(200L), "only the outermost end may hand the gate over")

        assertEquals(
            "authorized",
            withTimeoutOrNull(2_000L) { MinecraftLoginGate.whileNotLoggingIn(200L) { "authorized" } },
            "a login that never ends must not strand authorization",
        )

        MinecraftLoginGate.end(connection, outer)
        assertNull(authorizeWithin(200L), "must stay shut until the server is done with its hasJoined")

        MinecraftLoginGate.loginSettled(connection)
        assertEquals("authorized", authorizeWithin(1_000L), "the outcome signal must reopen the gate")
    }

    @Test
    fun `an interrupted login gives up the gate instead of killing the join`() = runBlocking {
        var held: Boolean? = null
        var interruptKept: Boolean? = null
        val connection = Any()

        val worker = Thread {
            Thread.currentThread().interrupt()
            held = MinecraftLoginGate.begin(connection)
            interruptKept = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(5_000L)

        assertEquals(false, held, "an interrupted login must report that it does not hold the gate")
        assertEquals(true, interruptKept, "the interrupt must survive so shutdown still proceeds")
        MinecraftLoginGate.end(connection, held == true)
        MinecraftLoginGate.loginSettled(connection)
        assertEquals("authorized", authorizeWithin(1_000L), "a permit must not have been consumed")
    }

    @Test
    fun `an outcome that arrives before the login ends is not lost`() = runBlocking {
        val connection = Any()
        val held = MinecraftLoginGate.begin(connection)
        assertTrue(held, "an idle gate must let the login in")

        MinecraftLoginGate.loginSettled(connection)
        MinecraftLoginGate.end(connection, held)

        assertEquals(
            "authorized",
            authorizeWithin(1_000L),
            "an outcome that beat end() must still open the gate, not wait out the stale sweep",
        )
    }

    @Test
    fun `an outcome with no login in flight leaves nothing behind`() = runBlocking {
        val connection = Any()
        MinecraftLoginGate.loginSettled(connection)

        val held = MinecraftLoginGate.begin(connection)
        assertTrue(held, "an idle gate must let the login in")
        MinecraftLoginGate.end(connection, held)
        assertNull(authorizeWithin(200L), "a settle with no login behind it must not clear a later one")

        MinecraftLoginGate.loginSettled(connection)
        assertEquals("authorized", authorizeWithin(1_000L), "the login's own outcome must reopen the gate")
    }

    @Test
    fun `an outcome that arrives during a failed acquire is not lost`() = runBlocking {
        val holder = Any()
        val kicked = Any()
        assertTrue(MinecraftLoginGate.begin(holder), "an idle gate must let the login in")

        var failedOpen: Boolean? = null
        val worker = Thread {
            failedOpen = !MinecraftLoginGate.begin(kicked)
            MinecraftLoginGate.end(kicked, false)
        }
        worker.start()
        delay(150L)
        MinecraftLoginGate.loginSettled(kicked)
        worker.join(5_000L)
        assertEquals(true, failedOpen, "the holder must still have the only permit")

        MinecraftLoginGate.end(holder, true)
        MinecraftLoginGate.loginSettled(holder)
        assertEquals(
            "authorized",
            authorizeWithin(1_000L),
            "the kicked login's outcome already arrived, so nothing may still be pending for it",
        )
    }

    @Test
    fun `a late outcome signal cannot leak a second permit`() = runBlocking {
        MinecraftLoginGate.loginSettled(Any())
        MinecraftLoginGate.loginSettled(Any())

        val connection = Any()
        val held = MinecraftLoginGate.begin(connection)
        assertTrue(held, "the gate must still be a single permit")
        assertNull(authorizeWithin(200L), "a stray signal must not have opened a second lane")
        MinecraftLoginGate.end(connection, held)
        MinecraftLoginGate.loginSettled(connection)
    }

    @Test
    fun `a login that authenticated without the gate is visible to the holder`() = runBlocking {
        val before = MinecraftLoginGate.failOpenGeneration
        val holderConnection = Any()
        val failedOpenConnection = Any()
        val held = MinecraftLoginGate.begin(holderConnection)
        assertTrue(held, "an idle gate must let the login in")
        assertEquals(before, MinecraftLoginGate.failOpenGeneration, "taking the gate is not a fail-open")

        var failedOpen: Boolean? = null
        val worker = Thread {
            val workerHeld = MinecraftLoginGate.begin(failedOpenConnection)
            failedOpen = !workerHeld
            MinecraftLoginGate.end(failedOpenConnection, workerHeld)
        }
        worker.start()
        worker.join(5_000L)

        assertEquals(true, failedOpen, "a second login must not be handed the gate")
        assertNotEquals(
            before,
            MinecraftLoginGate.failOpenGeneration,
            "a login that gave up waiting must be observable, so a holder can re-join the session service",
        )

        MinecraftLoginGate.end(holderConnection, held)
        MinecraftLoginGate.loginSettled(holderConnection)
        MinecraftLoginGate.loginSettled(failedOpenConnection)
    }

    @Test
    fun `a login that never settles cannot shut the gate for good`() = runBlocking {
        val connection = Any()
        val held = MinecraftLoginGate.begin(connection)
        assertTrue(held, "an idle gate must let the login in")
        MinecraftLoginGate.end(connection, held)
        assertNull(authorizeWithin(200L), "the hold must stand while the outcome is still pending")

        MinecraftLoginGate.releaseStaleHold(System.currentTimeMillis() + MinecraftLoginGate.MAX_HOLD_MS + 1)
        assertEquals("authorized", authorizeWithin(1_000L), "a hold past its deadline must be reclaimed")
    }

    @Test
    fun `a login that ends on the wrong thread does not strand the permit`() = runBlocking {
        val first = Any()
        val second = Any()
        val opened = CountDownLatch(1)
        val reclaimed = CountDownLatch(1)
        val reopenedGate = CountDownLatch(1)
        val mayEnd = CountDownLatch(1)
        val finished = CountDownLatch(1)
        var held: Boolean? = null
        var reopened: Boolean? = null

        val worker = Thread {
            held = MinecraftLoginGate.begin(first)
            opened.countDown()
            reclaimed.await()
            reopened = MinecraftLoginGate.begin(second)
            reopenedGate.countDown()
            mayEnd.await()
            MinecraftLoginGate.end(second, reopened == true)
            finished.countDown()
        }
        worker.start()
        opened.await()
        assertEquals(true, held, "an idle gate must let the login in")

        MinecraftLoginGate.end(first, true)
        assertNull(authorizeWithin(200L), "the permit is still out, so authorization must wait")
        MinecraftLoginGate.releaseStaleHold(System.currentTimeMillis() + MinecraftLoginGate.MAX_HOLD_MS + 1)
        assertEquals("authorized", authorizeWithin(1_000L), "the orphaned permit must be reclaimed")

        reclaimed.countDown()
        reopenedGate.await()
        assertEquals(true, reopened)
        assertNull(authorizeWithin(200L), "the reopened login must actually hold the gate")

        mayEnd.countDown()
        finished.await()
        worker.join(5_000L)
        MinecraftLoginGate.loginSettled(second)

        val third = Any()
        val onlyLane = MinecraftLoginGate.begin(third)
        assertTrue(onlyLane, "the permit must have come back")
        assertNull(authorizeWithin(200L), "a second lane must never have opened")
        MinecraftLoginGate.end(third, onlyLane)
        MinecraftLoginGate.releaseStaleHold(System.currentTimeMillis() + MinecraftLoginGate.MAX_HOLD_MS + 1)
    }

    @Test
    fun `an older outcome cannot clear a newer overlapping login`() = runBlocking {
        val firstConnection = Any()
        val secondConnection = Any()
        val held = MinecraftLoginGate.begin(firstConnection)
        assertTrue(held)
        MinecraftLoginGate.end(firstConnection, held)

        var failedOpen: Boolean? = null
        val worker = Thread {
            failedOpen = !MinecraftLoginGate.begin(secondConnection)
            MinecraftLoginGate.end(secondConnection, false)
        }
        worker.start()
        worker.join(5_000L)
        assertTrue(failedOpen == true)

        MinecraftLoginGate.loginSettled(firstConnection)
        assertNull(authorizeWithin(200L), "first outcome must not clear the overlapping login")
        MinecraftLoginGate.loginSettled(secondConnection)
        assertEquals("authorized", authorizeWithin(1_000L))
    }

    @Test
    fun `a late outcome for a reclaimed login cannot clear a newer login`() = runBlocking {
        val staleConnection = Any()
        val staleHeld = MinecraftLoginGate.begin(staleConnection)
        assertTrue(staleHeld)
        MinecraftLoginGate.end(staleConnection, staleHeld)
        MinecraftLoginGate.releaseStaleHold(System.currentTimeMillis() + MinecraftLoginGate.MAX_HOLD_MS + 1)

        val currentConnection = Any()
        val currentHeld = MinecraftLoginGate.begin(currentConnection)
        assertTrue(currentHeld)
        MinecraftLoginGate.end(currentConnection, currentHeld)

        MinecraftLoginGate.loginSettled(staleConnection)
        assertNull(authorizeWithin(200L), "the reclaimed login's late signal must not clear the current hold")
        MinecraftLoginGate.loginSettled(currentConnection)
        assertEquals("authorized", authorizeWithin(1_000L))
    }
}

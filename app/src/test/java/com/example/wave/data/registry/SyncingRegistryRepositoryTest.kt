package com.example.wave.data.registry

import com.example.wave.data.RegistryState
import com.example.wave.data.registryOrNull
import com.example.wave.model.Registry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class SyncingRegistryRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** "v3" -> genuine version 3, "v3-expired" -> genuine but expired, "bad" -> fails the signature check. */
    private fun signed(text: String) = SignedRegistry(text.toByteArray(), "wave-dev", "sig")

    private val fakeVerifier = RegistryVerifier { file ->
        val text = String(file.bytes)
        val version = Regex("^v(\\d+)").find(text)?.groupValues?.get(1)?.toInt()
            ?: return@RegistryVerifier RegistryState.Missing("signature doesn't match the file")
        val registry = Registry("wave-registry/1", version, "wave-dev", Instant.EPOCH, Instant.EPOCH, emptyList(), emptyList())
        if (text.endsWith("-expired")) RegistryState.Stale(registry) else RegistryState.Ready(registry)
    }

    private class FakeDownloader(var reply: () -> SignedRegistry) : RegistryDownloader {
        val calls = AtomicInteger()
        override suspend fun download(): SignedRegistry {
            calls.incrementAndGet()
            return reply()
        }
    }

    private val cache by lazy { RegistryCache(File(tmp.root, "registry")) }
    private var now = 1_000_000L

    private fun repo(bundled: String?, saved: String?, downloader: FakeDownloader = FakeDownloader { throw IOException("unused") }) =
        SyncingRegistryRepository(
            bundled = { bundled?.let(::signed) },
            cache = cache.also { c -> saved?.let { c.write(signed(it)) } },
            downloader = downloader,
            verifier = fakeVerifier,
            clock = { now },
        )

    private val SyncingRegistryRepository.version: Int? get() = state.value.registryOrNull?.version

    @Test
    fun startsWithHigherOfBundledAndSaved() = runBlocking {
        assertEquals(2, repo("v1", "v2").apply { loadLocal() }.version)
        tmp.root.resolve("registry").deleteRecursively()
        assertEquals(3, repo("v3", "v2").apply { loadLocal() }.version)
        tmp.root.resolve("registry").deleteRecursively()
        assertEquals(1, repo("v1", null).apply { loadLocal() }.version)
    }

    @Test
    fun newerDownloadReplacesAndIsSaved() = runBlocking {
        val r = repo("v1", null, FakeDownloader { signed("v2") }).apply { loadLocal() }
        assertEquals(RegistrySync.Updated(2), r.sync())
        assertEquals(2, r.version)
        assertEquals(signed("v2"), cache.read())
    }

    @Test
    fun olderDownloadIsRejected() = runBlocking {
        val r = repo("v1", "v2", FakeDownloader { signed("v1") }).apply { loadLocal() }
        val result = r.sync()
        assertTrue("$result", result is RegistrySync.Rejected && result.reason.contains("older version v1; this phone has v2"))
        assertEquals(2, r.version)
        assertEquals(signed("v2"), cache.read())
    }

    @Test
    fun sameVersionIsUpToDate() = runBlocking {
        val r = repo("v1", "v2", FakeDownloader { signed("v2") }).apply { loadLocal() }
        assertEquals(RegistrySync.UpToDate(2), r.sync())
    }

    @Test
    fun badSignatureIsRejectedAndKeepsCurrent() = runBlocking {
        val r = repo("v1", null, FakeDownloader { signed("bad") }).apply { loadLocal() }
        assertTrue(r.sync() is RegistrySync.Rejected)
        assertEquals(1, r.version)
        assertNull(cache.read())
    }

    @Test
    fun failedDownloadKeepsCurrent() = runBlocking {
        val r = repo("v1", null, FakeDownloader { throw IOException("HTTP 503") }).apply { loadLocal() }
        assertEquals(RegistrySync.Failed("HTTP 503"), r.sync())
        assertEquals(1, r.version)
    }

    @Test
    fun nonIoExceptionKeepsCurrent() = runBlocking {
        val r = repo("v1", null, FakeDownloader { throw SecurityException("blocked") }).apply { loadLocal() }
        assertTrue(r.sync() is RegistrySync.Failed)
        assertEquals(1, r.version)
    }

    @Test
    fun firstRunDownloadsWhenNothingLocal() = runBlocking {
        val r = repo(null, null, FakeDownloader { signed("v1") }).apply { loadLocal() }
        assertTrue(r.state.value is RegistryState.Missing)
        assertEquals(RegistrySync.Updated(1), r.sync())
        assertTrue(r.state.value is RegistryState.Ready)
    }

    @Test
    fun newerButExpiredIsAcceptedAsStale() = runBlocking {
        val r = repo("v1", null, FakeDownloader { signed("v2-expired") }).apply { loadLocal() }
        assertEquals(RegistrySync.Updated(2), r.sync())
        assertTrue(r.state.value is RegistryState.Stale)
    }

    @Test
    fun damagedSavedCopyFallsBackToBundled() = runBlocking {
        assertEquals(1, repo("v1", "bad").apply { loadLocal() }.version)
    }

    @Test
    fun automaticSyncIsThrottled() = runBlocking {
        val downloader = FakeDownloader { signed("v1") }
        val r = repo("v1", null, downloader).apply { loadLocal() }
        r.sync()
        assertEquals(RegistrySync.Skipped, r.sync())
        assertEquals(1, downloader.calls.get())
        r.sync(force = true)
        assertEquals(2, downloader.calls.get())
    }

    /** E.g. storage full: the verified registry is still used in memory, and nothing escapes to crash the app. */
    @Test
    fun saveFailureStillUsesNewRegistry() = runBlocking {
        val notADir = File(tmp.root, "blocked").apply { writeText("a file where the folder should be") }
        val r = SyncingRegistryRepository(
            bundled = { signed("v1") },
            cache = RegistryCache(notADir),
            downloader = FakeDownloader { signed("v2") },
            verifier = fakeVerifier,
            clock = { now },
        )
        r.loadLocal()
        assertEquals(RegistrySync.Updated(2), r.sync())
        assertEquals(2, r.version)
    }
}

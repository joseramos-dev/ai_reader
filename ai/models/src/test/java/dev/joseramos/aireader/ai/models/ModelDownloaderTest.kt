package dev.joseramos.aireader.ai.models

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val downloader = ModelDownloader(OkHttpClient())
    private val content = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        // Sirve el fichero completo o, si se pide un Range, solo el resto (206).
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == null) {
                    MockResponse().setBody(Buffer().write(content))
                } else {
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse().setResponseCode(
                        206
                    ).setBody(Buffer().write(content.copyOfRange(start, content.size)))
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun downloadsVerifiesAndReportsProgress() = runTest {
        val target = File(folder.root, "model.bin")
        var last = 0L

        downloader.download(server.url("/model.bin").toString(), target, sha) { done, _ -> last = done }

        assertArrayEquals(content, target.readBytes())
        assertEquals(content.size.toLong(), last)
        assertFalse(File(target.path + ".part").exists())
    }

    @Test
    fun resumesFromPartialFileWithRangeRequest() = runTest {
        val target = File(folder.root, "model.bin")
        File(target.path + ".part").writeBytes(content.copyOfRange(0, 100_000))

        downloader.download(server.url("/model.bin").toString(), target, sha) { _, _ -> }

        assertEquals("bytes=100000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(content, target.readBytes())
    }

    @Test
    fun corruptDownloadIsRejectedAndDiscarded() = runTest {
        val target = File(folder.root, "model.bin")
        try {
            downloader.download(server.url("/model.bin").toString(), target, "0".repeat(64)) { _, _ -> }
            fail("Debería haber detectado el sha256 incorrecto")
        } catch (e: ChecksumMismatchException) {
            assertTrue(e.message!!.contains("corrupta"))
        }
        assertFalse(target.exists())
        assertFalse(File(target.path + ".part").exists())
    }
}

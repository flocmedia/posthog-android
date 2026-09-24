package com.posthog.internal

import com.posthog.API_KEY
import com.posthog.PostHogConfig
import com.posthog.PostHogEvent
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GAME-1315. A batch is capped by event COUNT, not size, so 20 ordinary-looking replay
 * snapshots of an image-heavy screen can exceed the ingest size limit. Such a batch is
 * accepted with 200 and dropped downstream, and if it carried a replay's establishing full
 * snapshot the rest of that session is unplayable. These pin the byte cap.
 *
 * MUTATION EVIDENCE — each edit applied to PostHogQueue.capBatchByBytes, test watched go red:
 *
 *  M1  `if (capped.isNotEmpty() && total + length > MAX_BATCH_BYTES)` -> drop the
 *      `capped.isNotEmpty()` guard  => aSingleOversizedEventIsStillTaken FAILS, returning an
 *      EMPTY batch. That is the dangerous mutation: the queue would never drain, the event is
 *      never sent and never dropped, and no error is produced anywhere.
 *  M2  `total += length` deleted    => aBatchIsCutOnceTheByteCapIsReached FAILS (nothing
 *      accumulates, so every file is taken and the cap does nothing).
 *  M3  `break` -> `continue`        => aBatchIsCutOnceTheByteCapIsReached FAILS: it would skip
 *      the big event and keep taking later ones, reordering the queue and stranding that event.
 *  M4  `if (files.size < 2) return files` -> `return emptyList()`
 *                                   => aSingleOversizedEventIsStillTaken FAILS.
 *  M5  cap raised to Long.MAX_VALUE => aBatchIsCutOnceTheByteCapIsReached FAILS.
 */
internal class PostHogQueueByteCapTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val executor = Executors.newSingleThreadScheduledExecutor()

    private fun sut(): PostHogQueue<PostHogEvent> {
        val config =
            PostHogConfig(API_KEY, "http://localhost").apply {
                storagePrefix = tmpDir.newFolder().absolutePath
                httpClient = OkHttpClient()
            }
        val api = PostHogApi(config)
        return PostHogQueue(config, EndpointSpec.batch(config, api, config.storagePrefix), executor)
    }

    private fun fileOfSize(
        name: String,
        bytes: Int,
    ): File {
        val f = tmpDir.newFile(name)
        f.writeBytes(ByteArray(bytes))
        return f
    }

    @Test
    fun aBatchIsCutOnceTheByteCapIsReached() {
        val cap = PostHogQueue.MAX_BATCH_BYTES
        val third = (cap / 3).toInt()
        // Four events of ~1/3 the cap each: three fit, the fourth must not.
        val files = (1..4).map { fileOfSize("e$it.json", third) }

        val taken = sut().capBatchByBytesForTesting(files)

        assertEquals(3, taken.size, "the fourth event would push the request over the cap")
        assertTrue(taken.sumOf { it.length() } <= cap)
        assertEquals(listOf("e1.json", "e2.json", "e3.json"), taken.map { it.name }, "order preserved")
    }

    @Test
    fun aSingleOversizedEventIsStillTaken() {
        // Must never return an empty batch: the queue would stall forever on this event,
        // never sending it and never dropping it, with nothing logged anywhere.
        val huge = fileOfSize("huge.json", (PostHogQueue.MAX_BATCH_BYTES + 1).toInt())
        val next = fileOfSize("next.json", 10)

        val taken = sut().capBatchByBytesForTesting(listOf(huge, next))

        assertEquals(1, taken.size, "the oversized event goes alone rather than wedging the queue")
        assertEquals("huge.json", taken.single().name)
    }

    @Test
    fun aSmallBatchIsUntouched() {
        val files = (1..5).map { fileOfSize("s$it.json", 100) }

        assertEquals(files, sut().capBatchByBytesForTesting(files))
    }

    @Test
    fun anEmptyOrSingleBatchIsUntouched() {
        val queue = sut()
        assertTrue(queue.capBatchByBytesForTesting(emptyList()).isEmpty())
        val one = listOf(fileOfSize("one.json", 10))
        assertEquals(one, queue.capBatchByBytesForTesting(one))
    }
}

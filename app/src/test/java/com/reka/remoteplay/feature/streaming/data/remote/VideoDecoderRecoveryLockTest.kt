package com.reka.remoteplay.feature.streaming.data.remote

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.concurrent.thread

class VideoDecoderRecoveryLockTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `release does not wait for keyframe callback holding recovery lock`() {
        val decoder = VideoDecoder(monitorIndex = 0)
        val callbackEntered = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val decoderReleased = CountDownLatch(1)
        decoder.onKeyframeRequired = {
            callbackEntered.countDown()
            releaseCallback.await(5, TimeUnit.SECONDS)
            true
        }

        decoder.feedParsedFrame(
            VideoFrameParser.ParsedFrame(VideoFrameParser.FrameType.PFRAME, byteArrayOf(1))
        )
        assertTrue("Recovery callback was not invoked", callbackEntered.await(2, TimeUnit.SECONDS))

        thread(start = true, name = "release-during-keyframe-callback") {
            decoder.release()
            decoderReleased.countDown()
        }
        try {
            assertTrue(
                "release blocked on recoveryLock while keyframe callback was running",
                decoderReleased.await(1, TimeUnit.SECONDS)
            )
        } finally {
            releaseCallback.countDown()
            decoder.release()
        }
    }
}

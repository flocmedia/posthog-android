package com.posthog.android.replay.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.posthog.PostHogInterface
import com.posthog.android.API_KEY
import com.posthog.android.PostHogAndroidConfig
import com.posthog.internal.PostHogRemoteConfig
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.annotation.Config
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OWN_PID = 1234
private const val OTHER_PID = 5678

@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
internal class PostHogLogCatIntegrationTest {
    private val mockPostHog = mock<PostHogInterface>()
    private val mockRemoteConfig = mock<PostHogRemoteConfig>()

    private fun createConfig(captureLogcat: Boolean = true): PostHogAndroidConfig {
        return PostHogAndroidConfig(API_KEY).apply {
            sessionReplayConfig.captureLogcat = captureLogcat
        }
    }

    private fun getSut(config: PostHogAndroidConfig): PostHogLogCatIntegration {
        return PostHogLogCatIntegration(config)
    }

    @BeforeTest
    fun setUp() {
        // Reset static integrationInstalled state
        val config = createConfig()
        val dummy = PostHogLogCatIntegration(config)
        dummy.uninstall()
    }

    @Test
    fun `logcat command requests epoch output and an epoch start time`() {
        val sut = getSut(createConfig())

        val command = sut.logcatCommand(1721057445123L, OWN_PID)

        assertEquals(listOf("logcat", "--pid=$OWN_PID", "-v", "epoch", "-T", "1721057445.123", "*:E"), command)
    }

    // GAME-1703: on a shared log view (Huawei + GBox) other apps' lines, with OAuth
    // tokens and account emails in them, were captured into recordings.

    @Test
    fun `keeps an epoch line written by our own pid`() {
        val sut = getSut(createConfig())

        val event = sut.consoleEventFor("1721057445.123  $OWN_PID  4400 E MyTag: own line", LogcatParser(), OWN_PID)

        assertNotNull(event)
        val data = event.data as Map<*, *>
        assertEquals("rrweb/console@1", data["plugin"])
        val payload = data["payload"] as Map<*, *>
        assertEquals(listOf("MyTag: own line"), payload["payload"])
        assertEquals("error", payload["level"])
    }

    @Test
    fun `drops an epoch line written by another pid`() {
        val sut = getSut(createConfig())

        val event = sut.consoleEventFor("1721057445.123  $OTHER_PID  4400 E Finsky: foreign line", LogcatParser(), OWN_PID)

        assertNull(event)
    }

    @Test
    fun `drops a foreign line whose tid equals our pid`() {
        val sut = getSut(createConfig())

        val event = sut.consoleEventFor("1721057445.123  $OTHER_PID  $OWN_PID E Finsky: foreign line", LogcatParser(), OWN_PID)

        assertNull(event)
    }

    @Test
    fun `drops a foreign line whose message mentions our pid`() {
        val sut = getSut(createConfig())

        val event =
            sut.consoleEventFor(
                "1721057445.123  $OTHER_PID  4400 E Finsky: pid $OWN_PID  $OWN_PID E x: y",
                LogcatParser(),
                OWN_PID,
            )

        assertNull(event)
    }

    @Test
    fun `drops lines whose pid cannot be checked (fail closed)`() {
        val sut = getSut(createConfig())
        val malformed =
            listOf(
                // threadtime: our pid, but not the format the command asks for
                "12-31 23:59:59.987  $OWN_PID  4400 E MyTag: threadtime line",
                // brief
                "E/MyTag( $OWN_PID): brief line",
                // no pid column
                "1721057445.123 E MyTag: no pid",
                // non-numeric pid
                "1721057445.123  abc  4400 E MyTag: bad pid",
                // pid overflows an int
                "1721057445.123  99999999999999  4400 E MyTag: overflow",
                // free text continuation
                "    at com.example.Foo.bar(Foo.kt:12)",
                "--------- beginning of main",
                "",
            )

        for (line in malformed) {
            assertNull(sut.consoleEventFor(line, LogcatParser(), OWN_PID), "kept: $line")
        }
        assertNull(sut.consoleEventFor(null, LogcatParser(), OWN_PID))
    }

    @Test
    fun `install sets integrationInstalled to true`() {
        val config = createConfig()
        val sut = getSut(config)

        assertFalse(sut.isInstalled())

        sut.install(mockPostHog)

        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `install does nothing when captureLogcat is disabled`() {
        val config = createConfig(captureLogcat = false)
        val sut = getSut(config)

        sut.install(mockPostHog)

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `uninstall resets integrationInstalled to false`() {
        val config = createConfig()
        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        sut.uninstall()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig does nothing when remoteConfigHolder is null`() {
        val config = createConfig()
        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        // remoteConfigHolder is null by default
        sut.onRemoteConfig()

        // State unchanged — still installed
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig uninstalls when remote console log recording is disabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(false)
        sut.onRemoteConfig()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig keeps integration installed when remote console log recording is enabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        sut.onRemoteConfig()

        // Still installed — install is a no-op because already installed
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }

    @Test
    fun `onRemoteConfig does not install when postHog is null`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(true)

        // Don't call install(), so postHog stays null
        val sut = getSut(config)

        sut.onRemoteConfig()

        assertFalse(sut.isInstalled())
    }

    @Test
    fun `onRemoteConfig can re-install after being disabled`() {
        val config = createConfig()
        config.remoteConfigHolder = mockRemoteConfig
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(true)

        val sut = getSut(config)
        sut.install(mockPostHog)
        assertTrue(sut.isInstalled())

        // Disable remotely
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(false)
        sut.onRemoteConfig()
        assertFalse(sut.isInstalled())

        // Re-enable remotely — postHog reference is preserved, so install succeeds
        whenever(mockRemoteConfig.isConsoleLogRecordingEnabled()).thenReturn(true)
        sut.onRemoteConfig()
        assertTrue(sut.isInstalled())
        sut.uninstall()
    }
}

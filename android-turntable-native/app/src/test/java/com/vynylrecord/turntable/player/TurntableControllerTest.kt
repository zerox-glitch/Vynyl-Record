package com.vynylrecord.turntable.player

import android.net.Uri
import com.vynylrecord.turntable.audio.TransportSnapshot
import com.vynylrecord.turntable.graphics.CameraPreset
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.model.PlatterSpeed
import com.vynylrecord.turntable.model.RecordMetadata
import com.vynylrecord.turntable.model.RecordSide
import com.vynylrecord.turntable.model.VinylStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The controller is where the ordering guarantees live, so these tests check the *sequence* of calls
 * and not just the resulting state: audio must not start before the stylus lands, a stalled transport
 * must lift the needle, and a replacement renderer must be handed the complete current state — which
 * is what makes a lost EGL context survivable.
 *
 * The backend and the renderer are both fakes, so this runs on the JVM with no device and no Media3.
 */
class TurntableControllerTest {

    private class FakeBackend : PlaybackBackend {
        var playCalls = 0
        var pauseCalls = 0
        var releaseCalls = 0
        var loadCalls = 0
        var uriLoadCalls = 0
        var lastSeekMs = -1L
        var loadSucceeds = true
        var stageSucceeds = true
        var transport = TransportSnapshot(hasMedia = true, durationMs = 60_000L)

        override val isReady: Boolean get() = true

        override suspend fun prepareBundledSource(assetPath: String): File? {
            if (!stageSucceeds) return null
            return File.createTempFile("vynyl-demo", ".mp3").apply {
                writeBytes(ByteArray(32))
                deleteOnExit()
            }
        }

        override fun load(file: File, displayName: String): Boolean {
            loadCalls++
            transport = transport.copy(hasMedia = loadSucceeds, sourceName = displayName)
            return loadSucceeds
        }

        override fun loadUri(uri: Uri, displayName: String): Boolean {
            uriLoadCalls++
            transport = transport.copy(hasMedia = true, sourceName = displayName)
            return true
        }

        override fun play() {
            playCalls++
            transport = transport.copy(isPlaying = true)
        }

        override fun pause() {
            pauseCalls++
            transport = transport.copy(isPlaying = false)
        }

        override fun seekTo(positionMs: Long) {
            lastSeekMs = positionMs
            transport = transport.copy(positionMs = positionMs)
        }

        override fun snapshot(): TransportSnapshot = transport

        override fun release() {
            releaseCalls++
        }
    }

    /** Records every instruction the controller sends to the render thread. */
    private class FakeRenderer : RendererCommands {
        var style: VinylStyle? = null
        var metadata: RecordMetadata? = null
        var quality: RenderQuality? = null
        var reducedMotion: Boolean? = null
        var recordAvailable: Boolean? = null
        var playRequests = 0
        var pauseRequests = 0
        var errorRequests = 0
        var resetRequests = 0
        var cameraResets = 0
        var lastSeekProgress = -1f
        var lastSpeed: PlatterSpeed? = null
        var lastPreset: CameraPreset? = null
        var lastSyncedTransport: TransportSnapshot? = null
        var syncCount = 0

        override fun applyQuality(quality: RenderQuality) {
            this.quality = quality
        }

        override fun applyReducedMotion(enabled: Boolean) {
            reducedMotion = enabled
        }

        override fun applyVinylStyle(style: VinylStyle) {
            this.style = style
        }

        override fun applyMetadata(metadata: RecordMetadata) {
            this.metadata = metadata
        }

        override fun animatorSetRecordAvailable(available: Boolean) {
            recordAvailable = available
        }

        override fun animatorRequestPlay() {
            playRequests++
        }

        override fun animatorRequestPause() {
            pauseRequests++
        }

        override fun animatorRequestSeek(progress: Float) {
            lastSeekProgress = progress
        }

        override fun animatorRequestCompleted() = Unit

        override fun animatorRequestError() {
            errorRequests++
        }

        override fun animatorRequestReset() {
            resetRequests++
        }

        override fun animatorSetSpeed(speed: PlatterSpeed) {
            lastSpeed = speed
        }

        override fun syncAudio(transport: TransportSnapshot) {
            lastSyncedTransport = transport
            syncCount++
        }

        override fun resetCamera() {
            cameraResets++
        }

        override fun applyCameraPreset(preset: CameraPreset) {
            lastPreset = preset
        }
    }

    private val scope = TestScope(StandardTestDispatcher())
    private val backend = FakeBackend()
    private val renderer = FakeRenderer()
    private lateinit var controller: TurntableController

    private fun build(withRenderer: Boolean = true): TurntableController {
        val built = TurntableController(backend, scope)
        if (withRenderer) built.attachRenderer(renderer)
        controller = built
        scope.runCurrent()
        return built
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.dispose()
        scope.cancel()
    }

    /**
     * Loads a fake record, lets the staging coroutine run, and then lets one idle tick through so
     * the state carries the duration the fake transport reports.
     */
    private fun load() {
        controller.loadBundled()
        scope.runCurrent()
        scope.advanceTimeBy(300L)
        scope.runCurrent()
    }

    @Test
    fun playDoesNotStartAudioBeforeTheStylusLands() {
        build()
        load()
        assertEquals(0, backend.playCalls)
        assertEquals("the mechanism must be asked to start", 1, renderer.playRequests)

        controller.play()
        assertEquals("audio must wait for the needle", 0, backend.playCalls)
        assertTrue(controller.state.value.playRequested)

        controller.onNeedleContact()
        scope.runCurrent()
        assertEquals(1, backend.playCalls)
    }

    @Test
    fun playIsIgnoredWhenNoRecordIsLoaded() {
        backend.transport = TransportSnapshot(hasMedia = false)
        build()
        controller.play()
        assertEquals(0, renderer.playRequests)
        assertFalse(controller.state.value.playRequested)
    }

    @Test
    fun loadingARecordTellsTheMechanismAndPushesTheLabel() {
        build()
        val metadata = RecordMetadata("Test Pressing", "Tester", "Harness", side = RecordSide.B)
        val file = File.createTempFile("vynyl-load", ".mp3")
        controller.load(file, metadata)

        assertEquals(1, backend.loadCalls)
        assertEquals(true, renderer.recordAvailable)
        assertEquals(metadata, renderer.metadata)
        assertEquals("Test Pressing", controller.state.value.metadata.title)
        assertEquals("Test Pressing", controller.state.value.sourceName)
        assertNull(controller.state.value.errorMessage)
    }

    @Test
    fun loadUriGoesThroughTheBackend() {
        build()
        controller.loadUri(Uri.EMPTY, "voice-note.mp3")
        assertEquals(1, backend.uriLoadCalls)
        assertEquals("voice-note.mp3", controller.state.value.sourceName)
        assertTrue(controller.state.value.hasRecord)
    }

    @Test
    fun aFailedLoadReportsAnErrorInsteadOfPretendingToHaveARecord() {
        backend.loadSucceeds = false
        build()
        val file = File.createTempFile("vynyl-bad", ".mp3")
        controller.load(file)

        assertTrue("a failed load must not claim a record", renderer.recordAvailable != true)
        assertNotNull(controller.state.value.errorMessage)
        assertFalse(controller.state.value.hasRecord)
        assertEquals(1, renderer.errorRequests)
    }

    @Test
    fun aMissingBundledSampleReportsAnError() {
        backend.stageSucceeds = false
        build()
        load()
        assertNotNull(controller.state.value.errorMessage)
        assertFalse(controller.state.value.hasRecord)
    }

    @Test
    fun pauseStopsTheTransportAndLiftsTheNeedle() {
        build()
        load()
        controller.play()
        controller.onNeedleContact()
        scope.runCurrent()

        controller.pause()
        assertEquals(1, backend.pauseCalls)
        assertEquals(1, renderer.pauseRequests)
        assertFalse(controller.state.value.playRequested)
    }

    @Test
    fun toggleFlipsBetweenPlayAndPause() {
        build()
        load()
        controller.togglePlayPause()
        assertTrue(controller.state.value.playRequested)
        controller.togglePlayPause()
        assertFalse(controller.state.value.playRequested)
        assertEquals(1, backend.pauseCalls)
    }

    @Test
    fun seekingClampsToTheTrackAndMovesTheArm() {
        build()
        load()

        controller.seekTo(-5_000L)
        assertEquals(0L, backend.lastSeekMs)
        assertEquals(0f, renderer.lastSeekProgress, 1e-4f)

        controller.seekTo(999_000L)
        assertEquals(60_000L, backend.lastSeekMs)
        assertEquals(1f, renderer.lastSeekProgress, 1e-4f)

        controller.seekTo(30_000L)
        assertEquals(30_000L, backend.lastSeekMs)
        assertEquals(0.5f, renderer.lastSeekProgress, 1e-4f)
    }

    @Test
    fun seekingByProgressUsesTheDuration() {
        build()
        load()
        controller.seekToProgress(0.25f)
        assertEquals(15_000L, backend.lastSeekMs)
        controller.seekToProgress(2f)
        assertEquals(60_000L, backend.lastSeekMs)
    }

    @Test
    fun skippingIsRelativeAndClamped() {
        build()
        load()
        controller.seekTo(5_000L)
        controller.skipBy(10_000L)
        assertEquals(15_000L, backend.lastSeekMs)
        controller.skipBy(-10_000L)
        assertEquals(5_000L, backend.lastSeekMs)
        controller.skipBy(-60_000L)
        assertEquals(0L, backend.lastSeekMs)
    }

    @Test
    fun replayReturnsToTheTopAndPlays() {
        build()
        load()
        controller.seekTo(40_000L)
        controller.replay()
        assertEquals(0L, backend.lastSeekMs)
        assertTrue(controller.state.value.playRequested)
    }

    @Test
    fun styleAndSideChangesReachTheRendererAndTheState() {
        build()
        controller.setVinylStyle(VinylStyle.SMOKED_OBSIDIAN)
        assertEquals(VinylStyle.SMOKED_OBSIDIAN, renderer.style)
        assertEquals(VinylStyle.SMOKED_OBSIDIAN, controller.state.value.vinylStyle)

        controller.setPreviousVinylStyle()
        assertEquals(VinylStyle.VINTAGE_EMERALD, renderer.style)
        controller.setNextVinylStyle()
        assertEquals(VinylStyle.SMOKED_OBSIDIAN, renderer.style)

        controller.flipSide()
        assertEquals(RecordSide.B, controller.state.value.metadata.side)
        assertEquals(RecordSide.B, renderer.metadata?.side)

        controller.toggleSpeed()
        assertEquals(PlatterSpeed.FORTY_FIVE, controller.state.value.speed)
        assertEquals(PlatterSpeed.FORTY_FIVE, renderer.lastSpeed)
        controller.toggleSpeed()
        assertEquals(PlatterSpeed.THIRTY_THREE, controller.state.value.speed)
    }

    @Test
    fun setMetadataIgnoresAnUnchangedLabel() {
        build()
        val metadata = RecordMetadata("Same", "Text", "Exactly")
        controller.setMetadata(metadata)
        assertEquals(metadata, renderer.metadata)
        renderer.metadata = null
        controller.setMetadata(metadata.copy(title = " Same "))
        assertNull("an unchanged label must not be reprinted", renderer.metadata)
    }

    @Test
    fun theDeviceRecommendationOnlyWinsUntilTheUserChooses() {
        build()
        controller.onRendererReady("meshes=22 verts=23000 tris=30000", 30_000, RenderQuality.HIGH)
        assertEquals(RenderQuality.HIGH, controller.state.value.quality)
        assertFalse(controller.state.value.qualityChosenByUser)

        controller.setQuality(RenderQuality.LOW)
        assertEquals(RenderQuality.LOW, controller.state.value.quality)
        assertEquals(RenderQuality.LOW, renderer.quality)
        assertTrue(controller.state.value.qualityChosenByUser)

        controller.onRendererReady("meshes=22 verts=23000 tris=30000", 30_000, RenderQuality.HIGH)
        assertEquals("an explicit choice wins", RenderQuality.LOW, controller.state.value.quality)
    }

    @Test
    fun aFailedRendererSwitchesTheUiToTheStaticDeck() {
        build()
        controller.onRendererFailed("No OpenGL ES 3.0")
        assertFalse(controller.state.value.isEs3Available)
        assertEquals("No OpenGL ES 3.0", controller.state.value.glDescription)
    }

    @Test
    fun attachingAReplacementRendererRepushesTheWholeState() {
        build(withRenderer = false)
        controller.setVinylStyle(VinylStyle.MIDNIGHT_SAPPHIRE)
        controller.setQuality(RenderQuality.LOW)
        controller.setReducedMotion(true)
        controller.setMetadata(RecordMetadata("A", "B", "C"))
        controller.toggleSpeed()
        load()

        val replacement = FakeRenderer()
        controller.attachRenderer(replacement)

        assertEquals(VinylStyle.MIDNIGHT_SAPPHIRE, replacement.style)
        assertEquals(RenderQuality.LOW, replacement.quality)
        assertEquals(true, replacement.reducedMotion)
        assertEquals("A", replacement.metadata?.title)
        assertEquals(PlatterSpeed.FORTY_FIVE, replacement.lastSpeed)
        assertEquals(true, replacement.recordAvailable)
        assertNotNull("the transport must be resynced", replacement.lastSyncedTransport)
    }

    @Test
    fun detachingStopsTheRendererFromHearingAboutAnything() {
        build()
        load()
        controller.detachRenderer()
        renderer.syncCount = 0
        controller.setVinylStyle(VinylStyle.IMPERIAL_GOLD_MASTER)
        controller.play()
        assertEquals(0, renderer.syncCount)
        assertEquals(0, renderer.playRequests)
    }

    @Test
    fun completionAndErrorsAreReflectedInTheState() {
        build()
        load()
        controller.play()
        controller.onNeedleContact()
        scope.runCurrent()

        controller.onPlaybackCompleted()
        assertTrue(controller.state.value.isCompleted)
        assertFalse(controller.state.value.playRequested)
        assertEquals("This side has finished", controller.state.value.announcement)

        controller.onPlaybackError("Playback stopped unexpectedly.")
        assertEquals("Playback stopped unexpectedly.", controller.state.value.errorMessage)
        assertEquals(1, renderer.errorRequests)
        assertFalse(controller.state.value.showAsPlaying)
    }

    @Test
    fun aTransportThatStopsOnItsOwnLiftsTheNeedle() {
        build()
        load()
        controller.play()
        controller.onNeedleContact()
        scope.runCurrent()

        // The ticker samples the transport, so one playing tick is enough to publish it.
        scope.advanceTimeBy(200L)
        scope.runCurrent()
        assertTrue(controller.state.value.isPlaying)

        // Something took playback away: audio focus, a pulled headset, a stalled decoder.
        backend.transport = backend.transport.copy(isPlaying = false)
        scope.advanceTimeBy(400L)
        scope.runCurrent()

        assertEquals("the mechanism must be told to pause", 1, renderer.pauseRequests)
        assertFalse(controller.state.value.playRequested)
    }

    @Test
    fun theTransportIsSyncedToTheRendererOnEveryTick() {
        build()
        load()
        val before = renderer.syncCount
        scope.advanceTimeBy(600L)
        scope.runCurrent()
        assertTrue("the arm needs fresh progress", renderer.syncCount > before)
        assertEquals(60_000L, renderer.lastSyncedTransport?.durationMs)
    }

    @Test
    fun cameraCommandsReachTheRenderer() {
        build()
        controller.setCameraPreset(CameraPreset.NEEDLE)
        assertEquals(CameraPreset.NEEDLE, renderer.lastPreset)
        assertEquals(CameraPreset.NEEDLE, controller.state.value.cameraPreset)

        controller.resetCamera()
        assertEquals(1, renderer.cameraResets)
        assertEquals(CameraPreset.HERO, controller.state.value.cameraPreset)
    }

    @Test
    fun visualPhaseChangesArePublishedForTheUiAndForAccessibility() {
        build()
        controller.onVisualPhaseChanged(VisualPhase.NEEDLE_LOWERING)
        assertEquals(VisualPhase.NEEDLE_LOWERING, controller.state.value.visualPhase)
        assertEquals("Lowering the stylus", controller.state.value.phaseLabel)

        controller.onVisualPhaseChanged(VisualPhase.PLAYING)
        assertEquals("Playing", controller.state.value.announcement)

        controller.onVisualPhaseChanged(VisualPhase.PAUSED)
        assertEquals("Paused", controller.state.value.announcement)
        assertFalse(controller.state.value.playRequested)
    }

    @Test
    fun frameStatisticsArePublishedForTheDebugOverlay() {
        build()
        controller.onFrameStatistics(59.8f, 23, 30_120, 4)
        val stats = controller.state.value.frameStatistics
        assertEquals(59.8f, stats.fps, 1e-3f)
        assertEquals(23, stats.drawCalls)
        assertEquals(30_120, stats.triangles)
        assertEquals(4, stats.msaaSamples)
    }

    @Test
    fun disposingParksTheMechanismAndReleasesTheBackendExactlyOnce() {
        build()
        load()
        controller.dispose()
        assertEquals(1, backend.releaseCalls)
        assertEquals(1, renderer.resetRequests)
        controller.dispose()
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun unloadingParksTheArmAndForgetsTheRecord() {
        build()
        load()
        controller.unload()
        assertEquals(false, renderer.recordAvailable)
        assertFalse(controller.state.value.hasRecord)
    }

    @Test
    fun timeFormattingIsReadableAndClamped() {
        assertEquals("0:00", TurntableUiState.formatTime(0L))
        assertEquals("0:05", TurntableUiState.formatTime(5_400L))
        assertEquals("1:00", TurntableUiState.formatTime(60_600L))
        assertEquals("1:01", TurntableUiState.formatTime(61_000L))
        assertEquals("1:00:00", TurntableUiState.formatTime(3_600_000L))
        assertEquals("0:00", TurntableUiState.formatTime(-100L))
        assertEquals("3:19", TurntableUiState.formatTime(199_999L))
    }
}

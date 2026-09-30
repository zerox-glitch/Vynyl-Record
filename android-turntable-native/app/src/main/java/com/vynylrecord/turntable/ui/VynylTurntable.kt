package com.vynylrecord.turntable.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vynylrecord.turntable.graphics.RenderQuality
import com.vynylrecord.turntable.graphics.TurntableRenderer
import com.vynylrecord.turntable.graphics.TurntableSurface
import com.vynylrecord.turntable.graphics.animation.VisualPhase
import com.vynylrecord.turntable.player.RendererCallbacks
import com.vynylrecord.turntable.player.TurntableController
import com.vynylrecord.turntable.player.TurntableUiState

/**
 * The reusable record player.
 *
 * Drop this into any layout that can spare a box: it owns the `GLSurfaceView`, the renderer and the
 * gesture detector, mirrors the host lifecycle so the GL thread stops when the app is not visible,
 * and swaps itself for [StaticTurntableFallback] on devices without OpenGL ES 3.0. Everything it
 * draws is driven by [uiState], and everything it does goes through [controller].
 *
 * The composable is deliberately *decorative* for accessibility purposes: the scene is described, not
 * interacted with. Every control a person needs lives in the surrounding UI, which is why the demo
 * works with TalkBack and with a keyboard alone.
 *
 * @param uiState state published by [controller]; the scene does not animate if this stops updating.
 * @param controller the facade described in INTEGRATION.md.
 * @param onPlaybackCompleted host hook, called once when a side finishes (the controller already
 *   returns the arm on its own).
 * @param onError host hook, called after the controller records a playback error.
 */
@Composable
fun VynylTurntable(
    uiState: TurntableUiState,
    controller: TurntableController,
    modifier: Modifier = Modifier,
    onPlaybackCompleted: () -> Unit = {},
    onError: (String) -> Unit = {},
) {
    if (!uiState.isEs3Available) {
        StaticTurntableFallback(uiState = uiState, modifier = modifier)
        return
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var viewportHeight by remember { mutableIntStateOf(0) }

    val callbacks = remember(controller) {
        object : RendererCallbacks {
            override fun onRendererReady(description: String, triangles: Int, quality: RenderQuality) {
                controller.onRendererReady(description, triangles, quality)
            }

            override fun onRendererFailed(reason: String) {
                controller.onRendererFailed(reason)
            }

            override fun onNeedleContact() {
                controller.onNeedleContact()
            }

            override fun onVisualPhaseChanged(phase: VisualPhase) {
                controller.onVisualPhaseChanged(phase)
            }

            override fun onFrameStatistics(fps: Float, drawCalls: Int, triangles: Int, samples: Int) {
                controller.onFrameStatistics(fps, drawCalls, triangles, samples)
            }
        }
    }

    val renderer = remember(callbacks) { TurntableRenderer(context, callbacks) }
    val surface = remember(renderer) { TurntableSurface(context, renderer) }

    // The renderer is bound for as long as this composable is on screen, and it is handed the whole
    // current state on attach, so a surface that was rebuilt while the user was in another app comes
    // back with the right style, label, quality and speed.
    DisposableEffect(controller, renderer) {
        controller.attachRenderer(renderer)
        onDispose {
            controller.detachRenderer()
            surface.release()
        }
    }

    DisposableEffect(lifecycleOwner, surface) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> surface.resumeRendering()
                Lifecycle.Event.ON_PAUSE -> surface.pauseRendering()
                Lifecycle.Event.ON_STOP -> surface.pauseRendering()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Detaching the view is not enough on some OEM implementations: the GL thread can keep
            // running until the GC collects the view, which shows up as battery drain in the
            // background. Stop it explicitly.
            surface.pauseRendering()
        }
    }

    LaunchedEffect(uiState.visualPhase, uiState.errorMessage) {
        if (uiState.visualPhase == VisualPhase.COMPLETED) onPlaybackCompleted()
        uiState.errorMessage?.let(onError)
    }

    Box(
        modifier = modifier
            .onSizeChanged { viewportHeight = it.height }
            .semantics { contentDescription = "3D turntable. ${uiState.phaseLabel}." }
            .turntableGestures(renderer) { viewportHeight },
    ) {
        AndroidView(
            factory = { surface },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                view.pauseRendering()
                controller.detachRenderer()
            },
        )
    }
}

/**
 * One-finger orbit, pinch zoom, two-finger pan, double-tap reset.
 *
 * Hand-rolled rather than composed from `detectTransformGestures` because the pointer count decides
 * the meaning of the gesture: one finger orbits, two fingers zoom and pan. Deltas are handed to the
 * render thread as a batch, so a burst of touch events costs nothing when the next frame reads them.
 */
private fun Modifier.turntableGestures(
    renderer: TurntableRenderer,
    viewportHeight: () -> Int,
): Modifier = pointerInput(renderer) {
    // Lives outside awaitEachGesture on purpose: a tap counter declared inside it would be reset by
    // every gesture, and the double tap would never fire.
    var lastTapMillis = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        renderer.gestureBeginOrbit()

        var lastX = down.position.x
        var lastY = down.position.y
        var lastSpan = 0f
        var travelled = 0f
        var twoFinger = false

        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            var pressedCount = 0
            var centroidX = 0f
            var centroidY = 0f
            var singleIndex = -1
            for (index in event.changes.indices) {
                val change = event.changes[index]
                if (!change.pressed) continue
                pressedCount++
                centroidX += change.position.x
                centroidY += change.position.y
                singleIndex = index
            }
            if (pressedCount == 0) break
            val height = viewportHeight().coerceAtLeast(1)

            if (pressedCount == 1) {
                val change = event.changes[singleIndex]
                val dx = change.position.x - lastX
                val dy = change.position.y - lastY
                if (dx != 0f || dy != 0f) {
                    renderer.gestureOrbit(dx, dy, height)
                    travelled += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                    change.consume()
                }
                lastX = change.position.x
                lastY = change.position.y
            } else {
                twoFinger = true
                centroidX /= pressedCount
                centroidY /= pressedCount
                var span = 0f
                for (change in event.changes) {
                    if (!change.pressed) continue
                    val dx = change.position.x - centroidX
                    val dy = change.position.y - centroidY
                    span += kotlin.math.sqrt(dx * dx + dy * dy)
                    change.consume()
                }
                span /= pressedCount
                if (lastSpan > 0.5f && span > 0.5f) renderer.gestureZoom(span / lastSpan)
                renderer.gesturePan(centroidX - lastX, centroidY - lastY, height)
                lastSpan = span
                lastX = centroidX
                lastY = centroidY
            }
        }

        renderer.gestureEndOrbit()

        // A tap that neither moved nor used two fingers is a candidate for the double-tap reset.
        val now = System.currentTimeMillis()
        if (!twoFinger && travelled < viewConfiguration.touchSlop) {
            if (now - lastTapMillis <= DOUBLE_TAP_WINDOW_MS) {
                renderer.resetCamera()
                lastTapMillis = 0L
            } else {
                lastTapMillis = now
            }
        }
    }
}

private const val DOUBLE_TAP_WINDOW_MS = 320L

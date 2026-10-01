package com.vynylrecord.app.feature.player

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.vynylrecord.app.core.data.prefs.GraphicsQuality
import com.vynylrecord.app.core.design.VynylColors
import com.vynylrecord.app.core.design.VynylType
import com.vynylrecord.app.core.graphics.DeckPose
import com.vynylrecord.app.core.graphics.DeckState
import com.vynylrecord.app.core.graphics.RendererStats
import com.vynylrecord.app.core.graphics.TurntableRenderer
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId

/**
 * The bridge between Compose and OpenGL.
 *
 * This is the only file in the app that mentions `GLSurfaceView`, and it contains no OpenGL of its own: the
 * surface is created here, the renderer is handed to it, and the Compose side pushes state into the renderer
 * and reads statistics back. All of the drawing lives in `core/graphics`, which is why the deck can be
 * tested and reasoned about without a Compose preview.
 *
 * ## Gestures
 *
 * The viewer is driven by three gestures and nothing else: one finger orbits, two fingers pinch, a double
 * tap cycles the camera presets. The gestures are handled by Compose rather than by the `GLSurfaceView`,
 * because then they compose with the rest of the screen — a vertical drag on the transport controls below
 * does not reach the deck, and the gesture areas are laid out by the same system as everything else.
 */
@Composable
fun TurntableSurface(
    pose: DeckPose,
    state: DeckState,
    record: Record?,
    style: VinylStyleId,
    metadataRevision: Long,
    renderProgress: Float,
    quality: GraphicsQuality,
    shadows: Boolean,
    showStatistics: Boolean,
    camera: com.vynylrecord.app.core.graphics.TurntableCamera,
    onStatistics: (RendererStats) -> Unit,
    onUnavailable: (String) -> Unit,
    onCameraTouched: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    var renderer by remember { mutableStateOf<TurntableRenderer?>(null) }
    var statisticsText by remember { mutableStateOf("waiting for the first frame") }

    // The state the renderer reads is published on the GL thread from the frame loop, so the values that
    // arrive here are only ever read by the renderer itself.
    val published = remember { PublishedFrame() }
    published.pose = pose
    published.state = state
    published.record = record
    published.style = style
    published.metadataRevision = metadataRevision
    published.renderProgress = renderProgress
    published.quality = quality
    published.shadows = shadows
    published.showStatistics = showStatistics

    // The interop view draws the deck; the overlay above it takes the gestures. Compose hit-tests children
    // in reverse, so an overlay declared after the view receives a drag before the surface can claim it —
    // which is the only reliable way to get Compose gestures on top of a GLSurfaceView.
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val view = TurntableGLView(ctx, published, camera) { stats ->
                    statisticsText = stats.summary
                    onStatistics(stats)
                }
                val glRenderer = TurntableRenderer(ctx) { stats -> view.onStats(stats) }
                view.setRenderer(glRenderer)
                view.setEGLContextClientVersion(3)
                view.preserveEGLContextOnPause = false
                view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                surface = view
                renderer = glRenderer
                view
            },
            update = { view -> surface = view },
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var lastPosition = androidx.compose.ui.geometry.Offset.Zero
                    var dragging = false
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }
                            when {
                                pointers.size >= 2 -> {
                                    val first = pointers[0].position
                                    val second = pointers[1].position
                                    val distance = (first - second).getDistance()
                                    val previous = (pointers[0].previousPosition - pointers[1].previousPosition).getDistance()
                                    if (previous > 1f) {
                                        camera.zoom(distance / previous)
                                        published.touching = true
                                        onCameraTouched(true)
                                    }
                                    event.changes.forEach { it.consume() }
                                    dragging = false
                                }

                                pointers.size == 1 -> {
                                    val position = pointers[0].position
                                    if (dragging) {
                                        val delta = position - lastPosition
                                        camera.orbit(
                                            deltaYawDegrees = -delta.x * ORBIT_DEGREES_PER_PIXEL,
                                            deltaPitchDegrees = delta.y * ORBIT_DEGREES_PER_PIXEL * 0.75f,
                                        )
                                        published.touching = true
                                        onCameraTouched(true)
                                        pointers[0].consume()
                                    }
                                    lastPosition = position
                                    dragging = true
                                }

                                else -> {
                                    if (dragging) onCameraTouched(false)
                                    dragging = false
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            camera.nextPreset()
                            published.touching = false
                            onCameraTouched(false)
                        },
                    )
                },
        )

        if (showStatistics) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    statisticsText,
                    style = VynylType.monoSmall,
                    color = VynylColors.Amber,
                )
                Text(
                    "${record?.presetId?.displayName ?: "no preset"} · ${style.displayName}",
                    style = VynylType.monoSmall,
                    color = VynylColors.Muted,
                )
            }
        }
    }

    // The lifecycle observer pauses the surface when the screen goes away.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> surface?.onPause()
                Lifecycle.Event.ON_RESUME -> surface?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // The GL objects are released on the GL thread; asking for them to be freed from the UI thread
            // would delete buffers out from under a frame that is being drawn.
            surface?.queueEvent { renderer?.releaseGl() }
            surface = null
            renderer = null
        }
    }
}

/**
 * A `GLSurfaceView` that carries the current frame's state to the renderer and the renderer's statistics
 * back, without either side holding a reference to the other's objects.
 */
private class TurntableGLView(
    context: Context,
    private val published: PublishedFrame,
    private val camera: com.vynylrecord.app.core.graphics.TurntableCamera,
    private val onStats: (RendererStats) -> Unit,
) : GLSurfaceView(context) {

    private var renderer: TurntableRenderer? = null

    /** Called from the GL thread; hops to the UI thread before touching Compose state. */
    fun onStats(stats: RendererStats) {
        post { onStats(stats) }
    }

    override fun setRenderer(renderer: GLSurfaceView.Renderer?) {
        this.renderer = renderer as? TurntableRenderer
        // A wrapper is installed so the renderer receives this frame's state before it draws, without the
        // screen having to hold a lock around a mutable object the GL thread is reading.
        super.setRenderer(
            object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    config: javax.microedition.khronos.egl.EGLConfig?,
                ) {
                    this@TurntableGLView.renderer?.onSurfaceCreated(gl, config)
                }

                override fun onSurfaceChanged(
                    gl: javax.microedition.khronos.opengles.GL10?,
                    width: Int,
                    height: Int,
                ) {
                    this@TurntableGLView.renderer?.onSurfaceChanged(gl, width, height)
                }

                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    val target = this@TurntableGLView.renderer ?: return
                    target.userIsTouching = published.touching
                    target.cameraControl.copyFrom(camera)
                    target.publish(
                        pose = published.pose,
                        deckState = published.state,
                        record = published.record,
                        style = published.style,
                        metadataRevision = published.metadataRevision,
                        renderProgress = published.renderProgress,
                        quality = published.quality,
                        shadows = published.shadows,
                        showFps = published.showStatistics,
                    )
                    target.onDrawFrame(gl)
                }
            },
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}

/** How far one pixel of drag turns the camera. 0.30° is about one full turn across a phone's width. */
private const val ORBIT_DEGREES_PER_PIXEL = 0.30f

/** The frame the UI has most recently published. Fields are plain writes read once per frame by the renderer. */
private class PublishedFrame {
    var pose: DeckPose = DeckPose()
    var state: DeckState = DeckState.IDLE
    var record: Record? = null
    var style: VinylStyleId = VinylStyleId.DEFAULT
    var metadataRevision: Long = 0L
    var renderProgress: Float = 0f
    var quality: GraphicsQuality = GraphicsQuality.MEDIUM
    var shadows: Boolean = true
    var showStatistics: Boolean = false
    var touching: Boolean = false
}

/**
 * The 2D deck.
 *
 * Shown instead of the 3D surface on a device without OpenGL ES 3.0, and used as the player's fallback if the
 * program fails to build. It is not a spinner or a placeholder: it is the same record, drawn with the canvas,
 * turning at the speed the audio is playing, with the same controls underneath. A device that cannot run the
 * 3D view still gets a working record player.
 */
@Composable
fun StaticDeckFallback(
    record: Record?,
    isPlaying: Boolean,
    progress: Float,
    message: String,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
) {
    var rotation by remember { mutableStateOf(0f) }
    LaunchedEffect(isPlaying) {
        var last = 0L
        while (true) {
            val now = System.nanoTime()
            val delta = if (last == 0L) 0f else (now - last) / 1_000_000_000f
            last = now
            // A record turns at 33⅓ rpm: 200° per second. Reduced motion halves the apparent speed
            // rather than stopping the disc, so the player still shows that something is playing.
            if (isPlaying) rotation = (rotation + delta * if (reducedMotion) 90f else 200f) % 360f
            kotlinx.coroutines.delay(16)
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .background(VynylColors.Obsidian)
            .padding(24.dp),
        verticalAlignment = Alignment.CenterHorizontally,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Text(
            message,
            style = VynylType.caption,
            color = VynylColors.Muted,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize(0.7f)) {
            val radius = size.minDimension / 2f
            val centre = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val style = record?.styledId ?: VinylStyleId.DEFAULT
            drawCircle(style.baseColor, radius = radius, center = centre)
            // The grooves turn with the record, which is the whole of the 2D deck's motion.
            var ring = radius * 0.36f
            while (ring < radius * 0.98f) {
                drawCircle(
                    color = style.grooveColor,
                    radius = ring,
                    center = centre,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                )
                ring += radius * 0.045f
            }
            drawCircle(style.labelColor, radius = radius * 0.34f, center = centre)
            drawCircle(
                color = style.brassAccent,
                radius = radius * 0.34f,
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
            )
            // A marker that shows the rotation: without it a turning record looks still.
            val angle = Math.toRadians(rotation.toDouble())
            drawCircle(
                color = style.brassAccent,
                radius = radius * 0.045f,
                center = androidx.compose.ui.geometry.Offset(
                    centre.x + (kotlin.math.cos(angle) * radius * 0.55f).toFloat(),
                    centre.y + (kotlin.math.sin(angle) * radius * 0.55f).toFloat(),
                ),
            )
            drawCircle(VynylColors.Obsidian, radius = radius * 0.03f, center = centre)
            // Progress as a swept arc around the edge.
            drawArc(
                color = VynylColors.Amber,
                startAngle = -90f,
                sweepAngle = progress.coerceIn(0f, 1f) * 360f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(centre.x - radius, centre.y - radius),
                size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f),
            )
        }
    }
}

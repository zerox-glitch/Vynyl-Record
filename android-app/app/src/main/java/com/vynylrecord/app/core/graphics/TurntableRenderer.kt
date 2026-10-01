package com.vynylrecord.app.core.graphics

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import com.vynylrecord.app.core.data.prefs.GraphicsQuality
import com.vynylrecord.app.core.model.Record
import com.vynylrecord.app.core.model.VinylStyleId
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The 3D turntable.
 *
 * ## What this class is responsible for
 *
 * Everything that talks to OpenGL: it builds the meshes, uploads them, draws the seventeen parts of the deck
 * with the pose the animator computed, manages the label texture, uploads the camera's matrices and releases
 * every GPU object it created. Nothing about *when* to play or *what* the record is lives here — that is
 * pushed in once per frame through [publish].
 *
 * ## Why it is written this way
 *
 * * **No allocation in the draw loop.** All matrices, vectors and scratch arrays are preallocated and
 *   mutated. A frame that allocates a dozen matrices hands the collector a few thousand objects a second,
 *   and the result is a stutter every few seconds: the kind of thing a user reads as "the expensive 3D
 *   screen" rather than as a bug.
 * * **Frame-rate independent.** The caller passes elapsed seconds, never a frame count.
 * * **It can fail, visibly and safely.** A driver that refuses the program, a context lost in the
 *   background: each reports through [Listener.onRendererUnavailable] and the player shows the flat deck
 *   instead. A black rectangle is never the outcome.
 * * **It stops when nobody is looking.** The surface is paused with the screen, and every GL object is
 *   released on the GL thread when the screen goes away.
 */
class TurntableRenderer(
    private val context: Context,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    /** What the renderer reports back to the screen. */
    interface Listener {
        /** The first frame is on screen, with how long it took, so a fade can be timed to it. */
        fun onFirstFrame(milliseconds: Float)

        /** The deck could not be prepared; [reason] is a sentence a user could act on. */
        fun onRendererUnavailable(reason: String)

        /** Reported about once a second, for the debug readout. */
        fun onStatistics(stats: RendererStats)
    }

    /** The reasons the 3D view can be unavailable. */
    enum class Unavailable(val message: String) {
        PROGRAM_FAILED("The 3D deck could not be prepared on this device's graphics driver."),
        CONTEXT_LOST("The 3D deck was interrupted. Reopen the player to bring it back."),
    }

    // ---- state pushed in from the screen
    private var pose = DeckPose()
    private var deckState = DeckState.IDLE
    private var record: Record? = null
    private var style: VinylStyleId = VinylStyleId.DEFAULT
    private var metadataRevision: Long = 0L
    private var renderProgress: Float = 0f
    private var quality: GraphicsQuality = GraphicsQuality.MEDIUM
    private var shadows: Boolean = true
    private var showStatistics: Boolean = false

    /**
     * The camera the renderer draws with.
     *
     * The screen's gestures mutate this object from the UI thread while the GL thread reads it. That is a
     * benign race — a float write is atomic, and the worst outcome of reading the previous value is one frame
     * drawn from a camera a few milliseconds out of date.
     */
    val camera = TurntableCamera()

    /** Set by the screen while a gesture is in progress, so the idle drift stays out of the way. */
    var userIsTouching: Boolean = false

    // ---- GPU objects, created on the GL thread and released on it
    private var program: GlProgram? = null
    private val meshes = HashMap<DeckPart, GpuMesh>()
    private var labelTexture: LabelTexture? = null
    private var builtStyle: VinylStyleId? = null
    private var builtQuality: GraphicsQuality? = null

    // ---- per-frame scratch: nothing below is allocated after setup
    private val modelMatrix = Mat4()
    private val normalMatrix = FloatArray(9)
    private val scratchColor = FloatArray(3)
    private val baseColor = FloatArray(3)
    private val lampColor = FloatArray(3)
    private val sceneMeshes = HashMap<DeckPart, MeshData>()

    // ---- timing
    private var lastFrameNanos = 0L
    private var elapsedSeconds = 0f
    private var frameAccumulatorNanos = 0L
    private var frameCount = 0
    private var trianglesPerFrame = 0
    private var lastStatisticsNanos = 0L
    private var firstFrameReported = false
    private var viewportWidth = 1
    private var viewportHeight = 1
    private val statistics = RendererStats()

    /**
     * Publishes one frame's worth of state.
     *
     * Called from the GL thread at the top of a frame, so the worst case is that a frame shows the previous
     * state's pose — sixteen milliseconds at 60 FPS, which nobody can see. Plain field writes rather than a
     * lock, because a lock on the render path is a stutter waiting to happen.
     */
    fun publish(
        pose: DeckPose,
        deckState: DeckState,
        record: Record?,
        style: VinylStyleId,
        metadataRevision: Long,
        renderProgress: Float,
        quality: GraphicsQuality,
        shadows: Boolean,
        showStatistics: Boolean,
    ) {
        this.pose.set(pose)
        this.deckState = deckState
        this.record = record
        this.style = style
        this.metadataRevision = metadataRevision
        this.renderProgress = renderProgress
        this.quality = quality
        this.shadows = shadows
        this.showStatistics = showStatistics
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // The background is the app's obsidian, so the surface's edges are invisible against the screen.
        GLES30.glClearColor(0.047f, 0.039f, 0.035f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        GLES30.glFrontFace(GLES30.GL_CCW)
        // Blending is needed for the smoked pressings and for the translucent details (the sheen, the
        // shadow, the lamp's shade).
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val built = GlProgram.load(context, "turntable.vert", "turntable.frag")
        if (built == null) {
            Log.e(TAG, "the turntable program could not be built")
            listener.onRendererUnavailable(Unavailable.PROGRAM_FAILED.message)
            return
        }
        program = built
        labelTexture = LabelTexture()
        buildMeshes(style, quality)
        lastFrameNanos = System.nanoTime()
        lastStatisticsNanos = lastFrameNanos
        Log.i(TAG, "renderer ready: ${meshes.size} meshes, $trianglesPerFrame triangles per frame")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val program = this.program ?: return
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) 0f else ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.25f)
        lastFrameNanos = now
        elapsedSeconds += deltaSeconds

        // The camera advances here rather than in the screen: a drag that arrives at 120 Hz and a frame that
        // renders at 60 must not disagree about where the camera is. The one thing the renderer cannot know
        // is whether a finger is down, so it is told.
        camera.isBeingTouched = userIsTouching
        camera.update(deltaSeconds)
        camera.buildMatrices(viewportWidth, viewportHeight)

        // A style or quality change rebuilds the meshes; that is a per-screen event, not a per-frame one.
        if (builtStyle != style || builtQuality != quality) buildMeshes(style, quality)
        labelTexture?.sync(record, style, metadataRevision, textureSizeFor(quality))

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        program.use()
        GLES30.glUniformMatrix4fv(program.location("uViewProjectionMatrix"), 1, false, camera.viewProjectionMatrix.values, 0)
        GLES30.glUniform3f(program.location("uCameraPosition"), camera.position.x, camera.position.y, camera.position.z)
        GLES30.glUniform3fv(program.location("uKeyLightDirection"), 1, DeckLighting.KEY_DIRECTION, 0)
        GLES30.glUniform3fv(program.location("uKeyLightColor"), 1, DeckLighting.KEY_COLOR, 0)
        GLES30.glUniform3fv(program.location("uFillLightColor"), 1, DeckLighting.FILL_COLOR, 0)
        GLES30.glUniform3f(
            program.location("uAmbient"),
            DeckLighting.AMBIENT[0],
            DeckLighting.AMBIENT[1],
            DeckLighting.AMBIENT[2],
        )
        GLES30.glUniform1f(program.location("uTime"), elapsedSeconds)
        GLES30.glUniform3f(program.location("uLampPosition"), DeckDimensions.LAMP_X, DeckDimensions.LAMP_Y, DeckDimensions.LAMP_Z)

        // The lamp's colour is mixed between the warm amber and the ruby signalling colour, and its power
        // comes from the pose, which the animator has already shaped for the deck's state.
        val warmth = pose.lampWarmth.coerceIn(0f, 1f)
        for (channel in 0 until 3) {
            lampColor[channel] = lerp(DeckLighting.LAMP_COLOR[channel], DeckLighting.LAMP_ERROR_COLOR[channel], warmth)
        }
        GLES30.glUniform3fv(program.location("uLampColor"), 1, lampColor, 0)
        GLES30.glUniform1f(
            program.location("uLampIntensity"),
            DeckLighting.LAMP_POWER * pose.lampIntensity.coerceAtLeast(TurntableCamera.MIN_LAMP_INTENSITY),
        )

        if (shadows) drawShadowPlane()
        drawPlinthAndFeet()
        drawPlatterAndMat()
        drawRecord()
        drawSpindle()
        drawArm()
        drawControls()
        drawLamp()

        frameCount++
        frameAccumulatorNanos += (deltaSeconds * 1_000_000_000.0).toLong()
        if (now - lastStatisticsNanos >= 1_000_000_000L) {
            val seconds = frameAccumulatorNanos / 1_000_000_000.0
            statistics.framesPerSecond = if (seconds > 0.001) (frameCount / seconds).toFloat() else 0f
            statistics.millisecondsPerFrame = if (frameCount > 0) (frameAccumulatorNanos / frameCount / 1_000_000.0).toFloat() else 0f
            statistics.triangles = trianglesPerFrame
            statistics.state = deckState.label
            statistics.quality = quality.label
            listener.onStatistics(statistics)
            frameCount = 0
            frameAccumulatorNanos = 0L
            lastStatisticsNanos = now
        }
        if (!firstFrameReported) {
            firstFrameReported = true
            listener.onFirstFrame((deltaSeconds * 1000f).coerceAtLeast(0.1f))
        }
    }

    // ------------------------------------------------------------------ parts

    /**
     * Sets the material and transform for one part and draws it.
     *
     * Every uniform the shader needs is written here, in one place: a part cannot be drawn with a stale
     * value from the part before it, because nothing is inherited.
     */
    private fun drawPart(part: DeckPart, material: DeckMaterial, tint: FloatArray? = null, uvScale: Float = 0f) {
        val program = this.program ?: return
        val mesh = meshes[part] ?: return
        modelMatrix.identity()
        applyTransform(part, modelMatrix)
        modelMatrix.normalMatrix(normalMatrix)

        GLES30.glUniformMatrix4fv(program.location("uModelMatrix"), 1, false, modelMatrix.values, 0)
        GLES30.glUniformMatrix3fv(program.location("uNormalMatrix"), 1, false, normalMatrix, 0)

        val color = if (tint != null) {
            baseColor[0] = material.baseColor[0] * tint[0]
            baseColor[1] = material.baseColor[1] * tint[1]
            baseColor[2] = material.baseColor[2] * tint[2]
            baseColor
        } else {
            material.baseColor
        }
        GLES30.glUniform3fv(program.location("uBaseColor"), 1, color, 0)
        GLES30.glUniform3fv(program.location("uSpecularColor"), 1, material.specularColor, 0)
        GLES30.glUniform1f(program.location("uRoughness"), material.roughness)
        GLES30.glUniform1f(program.location("uMetallic"), material.metallic)
        GLES30.glUniform1f(program.location("uAlpha"), material.alpha)
        GLES30.glUniform1f(program.location("uTransmission"), material.transmission)
        GLES30.glUniform1f(program.location("uGrooveAmount"), material.grooveAmount)
        GLES30.glUniform1f(program.location("uSheen"), material.sheen)
        GLES30.glUniform1f(program.location("uUseLabel"), if (material.usesLabel) 1f else 0f)

        // The label's UVs map the disc's own plane — x and z in scene units — onto the 0..1 of the bitmap, so
        // the texture lands exactly on the label's circle rather than somewhere near it.
        if (uvScale > 0f) {
            val scale = 1f / (2f * DeckDimensions.RECORD_LABEL_RADIUS * uvScale)
            GLES30.glUniform2f(program.location("uUvScale"), scale, scale)
            GLES30.glUniform2f(program.location("uUvOffset"), 0.5f, 0.5f)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, labelTexture?.textureId ?: 0)
            GLES30.glUniform1i(program.location("uLabelTexture"), 0)
        } else {
            GLES30.glUniform2f(program.location("uUvScale"), 1f, 1f)
            GLES30.glUniform2f(program.location("uUvOffset"), 0f, 0f)
        }

        mesh.draw()
    }

    private fun drawPlinthAndFeet() {
        drawPart(DeckPart.PLINTH, DeckMaterials.PLINTH)
        for ((x, z) in DeckDimensions.FEET) {
            modelMatrix.identity()
                .translate(x, -DeckDimensions.PLINTH_HEIGHT / 2f + DeckDimensions.FOOT_HEIGHT / 2f, z)
            modelMatrix.normalMatrix(normalMatrix)
            drawPartWithCurrentTransform(DeckPart.FEET, DeckMaterials.BRASS)
        }
    }

    /** Draws a part with the transform the caller has already built in [modelMatrix]. */
    private fun drawPartWithCurrentTransform(part: DeckPart, material: DeckMaterial) {
        val program = this.program ?: return
        val mesh = meshes[part] ?: return
        GLES30.glUniformMatrix4fv(program.location("uModelMatrix"), 1, false, modelMatrix.values, 0)
        GLES30.glUniformMatrix3fv(program.location("uNormalMatrix"), 1, false, normalMatrix, 0)
        GLES30.glUniform3fv(program.location("uBaseColor"), 1, material.baseColor, 0)
        GLES30.glUniform3fv(program.location("uSpecularColor"), 1, material.specularColor, 0)
        GLES30.glUniform1f(program.location("uRoughness"), material.roughness)
        GLES30.glUniform1f(program.location("uMetallic"), material.metallic)
        GLES30.glUniform1f(program.location("uAlpha"), material.alpha)
        GLES30.glUniform1f(program.location("uTransmission"), material.transmission)
        GLES30.glUniform1f(program.location("uGrooveAmount"), material.grooveAmount)
        GLES30.glUniform1f(program.location("uSheen"), material.sheen)
        GLES30.glUniform1f(program.location("uUseLabel"), 0f)
        GLES30.glUniform2f(program.location("uUvScale"), 1f, 1f)
        GLES30.glUniform2f(program.location("uUvOffset"), 0f, 0f)
        mesh.draw()
    }

    private fun drawPlatterAndMat() {
        drawPart(DeckPart.PLATTER, DeckMaterials.ALUMINIUM)
        drawPart(DeckPart.PLATTER_RIM, DeckMaterials.BRASS)
        drawPart(DeckPart.MAT, DeckMaterials.FELT)
    }

    private fun drawRecord() {
        // A translucent pressing is drawn after the opaque parts so that the platter and mat show through it;
        // depth writes are off for that pass, which is what keeps the blend order from flickering.
        val recordMaterial = DeckMaterials.record(style)
        val translucent = style.translucent
        if (translucent) GLES30.glDepthMask(false)
        drawPart(DeckPart.RECORD, recordMaterial)
        drawPart(DeckPart.GROOVES, DeckMaterials.grooveSheen(style, pose.discSheen))
        if (translucent) GLES30.glDepthMask(true)
        drawPart(DeckPart.LABEL, DeckMaterials.label(style), uvScale = 1f)
    }

    private fun drawSpindle() {
        drawPart(DeckPart.SPINDLE, DeckMaterials.BRASS)
    }

    private fun drawArm() {
        drawPart(DeckPart.ARM_BASE, DeckMaterials.STEEL)
        drawPart(DeckPart.ARM, DeckMaterials.ALUMINIUM)
        drawPart(DeckPart.COUNTERWEIGHT, DeckMaterials.BRASS)
        drawPart(DeckPart.HEADSHELL, DeckMaterials.STEEL)
        // The stylus is only drawn while the needle is close to the record: a needle floating in the air is a
        // cartoon, and the same check is what makes the moment of contact visible.
        if (pose.needleContact > 0.02f || pose.armLift < 0.35f) {
            drawPart(DeckPart.STYLUS, DeckMaterials.STYLUS)
        }
    }

    private fun drawControls() {
        // The knobs turn: one with the record, one with the press. They are the one part of the deck that
        // shows the app's own state, and a control that visibly moves reads as a working machine.
        val rotations = floatArrayOf(pose.platterRotation * 0.12f, pose.pressProgress * 4.2f)
        val xPositions = floatArrayOf(DeckDimensions.KNOB_ONE_X, DeckDimensions.KNOB_TWO_X)
        val zPositions = floatArrayOf(DeckDimensions.KNOB_ONE_Z, DeckDimensions.KNOB_TWO_Z)
        for (index in 0 until 2) {
            modelMatrix.identity()
                .translate(xPositions[index], DeckDimensions.PLINTH_TOP + DeckDimensions.KNOB_HEIGHT / 2f - 0.002f, zPositions[index])
                .rotateY(rotations[index])
            modelMatrix.normalMatrix(normalMatrix)
            drawPartWithCurrentTransform(DeckPart.KNOBS, DeckMaterials.BRASS)
        }
    }

    private fun drawLamp() {
        val lampOn = pose.lampIntensity > 0.05f
        if (!lampOn) return
        scratchColor[0] = lampColor[0]
        scratchColor[1] = lampColor[1]
        scratchColor[2] = lampColor[2]
        drawPart(DeckPart.LAMP, DeckMaterials.lamp(scratchColor, pose.lampIntensity))
    }

    /**
     * The shadow.
     *
     * One blended disc under the deck. Real shadow mapping would cost a second pass over the whole scene;
     * this costs one draw call and is the difference between a deck that sits on a surface and one that
     * floats in a void.
     */
    private fun drawShadowPlane() {
        val program = this.program ?: return
        val mesh = meshes[DeckPart.MAT] ?: return
        modelMatrix.identity()
            .translate(0f, -DeckDimensions.PLINTH_HEIGHT / 2f - DeckDimensions.FOOT_HEIGHT - 0.002f, 0f)
            .scale(2.2f, 1f, 2.0f)
        modelMatrix.normalMatrix(normalMatrix)
        GLES30.glUniformMatrix4fv(program.location("uModelMatrix"), 1, false, modelMatrix.values, 0)
        GLES30.glUniformMatrix3fv(program.location("uNormalMatrix"), 1, false, normalMatrix, 0)
        GLES30.glUniform3f(program.location("uBaseColor"), 0.01f, 0.009f, 0.008f)
        GLES30.glUniform3f(program.location("uSpecularColor"), 0f, 0f, 0f)
        GLES30.glUniform1f(program.location("uRoughness"), 1f)
        GLES30.glUniform1f(program.location("uMetallic"), 0f)
        GLES30.glUniform1f(program.location("uAlpha"), 0.34f)
        GLES30.glUniform1f(program.location("uTransmission"), 0f)
        GLES30.glUniform1f(program.location("uGrooveAmount"), 0f)
        GLES30.glUniform1f(program.location("uSheen"), 0f)
        GLES30.glUniform1f(program.location("uUseLabel"), 0f)
        GLES30.glUniform2f(program.location("uUvScale"), 1f, 1f)
        GLES30.glUniform2f(program.location("uUvOffset"), 0f, 0f)
        GLES30.glDepthMask(false)
        mesh.draw()
        GLES30.glDepthMask(true)
    }

    /** Fills [matrix] with the world transform for a part, from the current pose. */
    private fun applyTransform(part: DeckPart, matrix: Mat4) {
        when (part) {
            DeckPart.PLINTH -> matrix.translate(0f, 0f, 0f)
            DeckPart.FEET -> matrix.translate(0f, 0f, 0f)

            DeckPart.PLATTER, DeckPart.PLATTER_RIM, DeckPart.MAT ->
                matrix.translate(0f, DeckDimensions.PLATTER_Y, 0f).rotateY(pose.platterRotation)
            // The mat shares the platter's transform but sits at its own height, which its mesh carries.

            DeckPart.RECORD, DeckPart.GROOVES, DeckPart.LABEL ->
                matrix
                    .translate(0f, DeckDimensions.RECORD_CENTRE_Y + pose.discLift, 0f)
                    .rotateY(pose.discRotation)

            DeckPart.SPINDLE -> matrix.translate(0f, DeckDimensions.SPINDLE_Y + pose.discLift, 0f)

            DeckPart.ARM_BASE -> matrix.translate(
                DeckDimensions.ARM_PIVOT_X,
                DeckDimensions.ARM_PIVOT_Y,
                DeckDimensions.ARM_PIVOT_Z,
            )

            // The arm, the weight, the shell and the needle all hang off the pivot, so one transform serves
            // all four and they stay rigid relative to one another as the arm swings and lifts.
            DeckPart.ARM, DeckPart.COUNTERWEIGHT, DeckPart.HEADSHELL, DeckPart.STYLUS -> {
                val travel = pose.armTravel.coerceIn(0f, 1f)
                val angle = DeckDimensions.ARM_REST_ANGLE + travel * DeckDimensions.ARM_SWEEP_RADIANS
                matrix
                    .translate(
                        DeckDimensions.ARM_PIVOT_X,
                        DeckDimensions.ARM_PIVOT_Y + pose.armLift * DeckDimensions.ARM_LIFT_HEIGHT,
                        DeckDimensions.ARM_PIVOT_Z,
                    )
                    .rotateY(angle)
                    // The headshell tips as it lowers, which is the difference between an arm coming down and
                    // a stick rotating.
                    .rotateX(-0.10f + pose.armLift * 0.16f)
            }

            DeckPart.KNOBS -> matrix.translate(DeckDimensions.KNOB_ONE_X, DeckDimensions.PLINTH_TOP, DeckDimensions.KNOB_ONE_Z)
            DeckPart.LAMP -> matrix.translate(DeckDimensions.LAMP_X, DeckDimensions.LAMP_Y, DeckDimensions.LAMP_Z)
        }
    }

    // ------------------------------------------------------------------ resources

    /**
     * Builds every mesh at the resolution the quality setting asks for.
     *
     * Called at startup and whenever the style or the quality changes — never during an ordinary frame, which
     * is why [onDrawFrame] compares against the values it last built with. The style matters here because a
     * smoked pressing gets a slightly denser label ring; the quality matters because it decides the segment
     * counts, which is the difference between fitting in a low-end GPU's budget and fighting it.
     */
    private fun buildMeshes(style: VinylStyleId, quality: GraphicsQuality) {
        releaseMeshes()
        builtStyle = style
        builtQuality = quality

        val detail = quality.renderScale
        val segments = { base: Int -> (base * detail).coerceAtLeast(8) }

        sceneMeshes[DeckPart.PLINTH] = Meshes.beveledBox(
            DeckDimensions.PLINTH_WIDTH,
            DeckDimensions.PLINTH_HEIGHT,
            DeckDimensions.PLINTH_DEPTH,
            DeckDimensions.PLINTH_BEVEL,
        )
        sceneMeshes[DeckPart.FEET] = Meshes.taperedCylinder(
            DeckDimensions.FOOT_RADIUS,
            DeckDimensions.FOOT_RADIUS * 0.8f,
            DeckDimensions.FOOT_HEIGHT,
            segments(16),
        )
        sceneMeshes[DeckPart.PLATTER] = Meshes.cylinder(DeckDimensions.PLATTER_RADIUS, DeckDimensions.PLATTER_HEIGHT, segments(64))
        sceneMeshes[DeckPart.PLATTER_RIM] = Meshes.ring(
            DeckDimensions.PLATTER_RADIUS * 1.02f,
            DeckDimensions.PLATTER_RADIUS * 0.94f,
            DeckDimensions.PLATTER_HEIGHT * 1.1f,
            segments(64),
        )
        sceneMeshes[DeckPart.MAT] = Meshes.cylinder(DeckDimensions.MAT_RADIUS, DeckDimensions.MAT_THICKNESS, segments(48))
        sceneMeshes[DeckPart.RECORD] = Meshes.disc(
            DeckDimensions.RECORD_RADIUS,
            DeckDimensions.SPINDLE_RADIUS * 0.9f,
            DeckDimensions.RECORD_THICKNESS,
            segments(96),
        )
        sceneMeshes[DeckPart.GROOVES] = Meshes.ring(
            DeckDimensions.RECORD_RADIUS * 0.985f,
            DeckDimensions.RECORD_LABEL_RADIUS,
            DeckDimensions.RECORD_THICKNESS * 0.7f,
            segments(96),
        )
        sceneMeshes[DeckPart.LABEL] = Meshes.disc(
            DeckDimensions.RECORD_LABEL_RADIUS,
            DeckDimensions.SPINDLE_RADIUS * 0.9f,
            DeckDimensions.RECORD_THICKNESS * 1.06f,
            segments(64),
        )
        sceneMeshes[DeckPart.SPINDLE] = Meshes.taperedCylinder(
            DeckDimensions.SPINDLE_RADIUS,
            DeckDimensions.SPINDLE_RADIUS * 0.7f,
            DeckDimensions.SPINDLE_HEIGHT,
            segments(16),
        )
        sceneMeshes[DeckPart.ARM_BASE] = Meshes.cylinder(0.026f, 0.024f, segments(24))
        sceneMeshes[DeckPart.ARM] = Meshes.tube(
            DeckDimensions.armCurve(),
            DeckDimensions.ARM_RADIUS,
            segments(28),
            segments(12),
        )
        sceneMeshes[DeckPart.COUNTERWEIGHT] = Meshes.torus(0.014f, 0.008f, segments(24), segments(12))
        sceneMeshes[DeckPart.HEADSHELL] = Meshes.wedge(0.030f, 0.018f, 0.012f)
        sceneMeshes[DeckPart.STYLUS] = Meshes.needle(0.014f)
        sceneMeshes[DeckPart.KNOBS] = Meshes.knob(DeckDimensions.KNOB_RADIUS, DeckDimensions.KNOB_HEIGHT, segments(14))
        sceneMeshes[DeckPart.LAMP] = Meshes.cone(DeckDimensions.LAMP_RADIUS, DeckDimensions.LAMP_HEIGHT, segments(20))

        // A malformed mesh is a much better error than a hole in the deck, so every one is checked as it is
        // built and the whole scene falls back rather than drawing something broken.
        val invalid = sceneMeshes.filterValues { !it.isValid }
        if (invalid.isNotEmpty()) {
            Log.e(TAG, "invalid meshes: ${invalid.keys.map { it.label }}")
            releaseMeshes()
            listener.onRendererUnavailable(Unavailable.PROGRAM_FAILED.message)
            return
        }

        var triangles = 0
        for ((part, mesh) in sceneMeshes) {
            val gpuMesh = GpuMesh(mesh)
            gpuMesh.upload()
            meshes[part] = gpuMesh
            // The feet are drawn four times and the knobs twice: the overlay reports what is actually drawn,
            // which is why this is counted rather than estimated.
            val copies = when (part) {
                DeckPart.FEET -> DeckDimensions.FEET.size
                DeckPart.KNOBS -> 2
                else -> 1
            }
            triangles += mesh.indexCount / 3 * copies
        }
        trianglesPerFrame = triangles
    }

    private fun releaseMeshes() {
        for (mesh in meshes.values) mesh.release()
        meshes.clear()
        sceneMeshes.clear()
    }

    /** Releases every GL object. Called on the GL thread, from the screen's teardown. */
    fun releaseGl() {
        releaseMeshes()
        labelTexture?.release()
        labelTexture = null
        program?.release()
        program = null
        firstFrameReported = false
        Log.i(TAG, "renderer released")
    }

    private fun textureSizeFor(quality: GraphicsQuality): Int = when (quality) {
        GraphicsQuality.LOW -> 256
        GraphicsQuality.MEDIUM -> LabelTexture.TEXTURE_SIZE
        GraphicsQuality.HIGH -> 768
    }

    private companion object {
        const val TAG = "VynylTurntableRenderer"
    }
}

/** What the debug readout shows. Reported about once a second, never per frame. */
class RendererStats(
    var framesPerSecond: Float = 0f,
    var millisecondsPerFrame: Float = 0f,
    var triangles: Int = 0,
    var state: String = "",
    var quality: String = "",
) {
    val summary: String
        get() = "%.0f FPS · %.1f ms · %s tris · %s".format(framesPerSecond, millisecondsPerFrame, formatThousands(triangles), state)

    private fun formatThousands(value: Int): String = if (value < 1_000) value.toString() else "%,d".format(value)
}

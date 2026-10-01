package com.vynylrecord.turntable.graphics.geometry

import com.vynylrecord.turntable.model.Mat4
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reusable procedural mesh generators.
 *
 * Everything the turntable is made of is generated here, so the project needs no imported
 * GLB/FBX asset. Three conventions keep the library coherent:
 *
 *  1. **Lathe convention.** Profiles are `(radius, y)` pairs traversed *counter clockwise* in
 *     the (radius, y) plane. For a segment with direction `(dr, dy)` the outward surface
 *     normal is `(dy, -dr)`, which puts the material on the correct side and makes every quad
 *     front-facing with OpenGL's default counter-clockwise winding.
 *  2. **Vertical origin.** Solids sit on `y = 0` and are centred on the Y axis (prisms are
 *     centred on the XZ origin), so stacking deck parts is a matter of adding heights.
 *  3. **Loft winding.** Every swept surface uses the pattern
 *     `quad(lower[i], upper[i], upper[i+1], lower[i+1])`, whose geometric normal is
 *     `up x tangent`. Outlines are therefore authored so that `up x tangent` points outwards.
 *
 * Smooth normals come from averaging adjacent profile segments; where two segments meet at
 * more than ~10 degrees the ring is duplicated so the edge stays crisp (a bevelled plinth edge
 * and a polished platter rim both depend on this).
 */
object MeshFactory {

    private const val SMOOTHING_DOT = 0.985f
    private const val POLE_EPSILON = 1e-5f

    /** Segment budgets per quality level. */
    data class GeometryDetail(
        val largeSegments: Int = 96,
        val mediumSegments: Int = 48,
        val smallSegments: Int = 24,
        val cornerSegments: Int = 10,
        val grooves: Boolean = true,
    ) {
        companion object {
            val LOW = GeometryDetail(largeSegments = 40, mediumSegments = 24, smallSegments = 14, cornerSegments = 5, grooves = false)
            val MEDIUM = GeometryDetail(largeSegments = 72, mediumSegments = 36, smallSegments = 18, cornerSegments = 8, grooves = true)
            val HIGH = GeometryDetail(largeSegments = 96, mediumSegments = 48, smallSegments = 24, cornerSegments = 12, grooves = true)
        }
    }

    // ------------------------------------------------------------------ lathe

    /**
     * Sweeps a 2D profile around the Y axis.
     *
     * @param profile flattened `(radius, y)` pairs traversed counter clockwise; radius may be
     *   zero at either end to close a solid with a pole (dome, spindle tip, disc centre).
     * @param segments radial subdivisions.
     * @param capStart add a flat fan at the first profile point (open-ended solids).
     * @param capEnd add a flat fan at the last profile point.
     * @param flutes number of vertical ridges (0 = smooth), used for machined knobs.
     * @param fluteDepth ridge depth as a fraction of the local radius.
     */
    @Suppress("LongParameterList", "LongMethod")
    fun revolve(
        name: String,
        profile: FloatArray,
        segments: Int,
        capStart: Boolean = false,
        capEnd: Boolean = false,
        uvScaleU: Float = 1f,
        uvScaleV: Float = 1f,
        flutes: Int = 0,
        fluteDepth: Float = 0f,
    ): Mesh {
        require(profile.size >= 4 && profile.size % 2 == 0) { "profile needs at least two points" }
        require(segments >= 3) { "segments must be at least 3" }

        val pointCount = profile.size / 2
        val builder = MeshBuilder(name, vertexEstimate = pointCount * segments * 2)

        val arcLength = FloatArray(pointCount)
        for (i in 1 until pointCount) {
            val dr = profile[i * 2] - profile[(i - 1) * 2]
            val dy = profile[i * 2 + 1] - profile[(i - 1) * 2 + 1]
            arcLength[i] = arcLength[i - 1] + sqrt(dr * dr + dy * dy)
        }
        val totalArc = if (arcLength[pointCount - 1] > 1e-6f) arcLength[pointCount - 1] else 1f

        val segmentNormalR = FloatArray(pointCount - 1)
        val segmentNormalY = FloatArray(pointCount - 1)
        for (i in 0 until pointCount - 1) {
            val dr = profile[(i + 1) * 2] - profile[i * 2]
            val dy = profile[(i + 1) * 2 + 1] - profile[i * 2 + 1]
            val length = sqrt(dr * dr + dy * dy)
            if (length < 1e-9f) {
                segmentNormalR[i] = if (profile[i * 2] > POLE_EPSILON) 1f else 0f
                segmentNormalY[i] = if (profile[i * 2] > POLE_EPSILON) 0f else 1f
            } else {
                segmentNormalR[i] = dy / length
                segmentNormalY[i] = -dr / length
            }
        }

        val rings = ArrayList<IntArray>(pointCount * 2)
        val ringBefore = IntArray(pointCount) { -1 }
        val ringAfter = IntArray(pointCount) { -1 }

        fun emitRing(radius: Float, y: Float, normalR: Float, normalY: Float, v: Float): IntArray {
            if (radius <= POLE_EPSILON) {
                val index = builder.addVertex(
                    0f, y, 0f,
                    0f, if (normalY >= 0f) 1f else -1f, 0f,
                    0f, v * uvScaleV,
                    1f, 0f, 0f,
                )
                return intArrayOf(index)
            }
            val ring = IntArray(segments)
            for (j in 0 until segments) {
                val angle = (2.0 * PI * j / segments).toFloat()
                val cx = cos(angle)
                val sz = sin(angle)
                val flute = if (flutes > 0 && fluteDepth > 0f) 1f + fluteDepth * cos(angle * flutes) else 1f
                val r = radius * flute
                var nx = normalR * cx
                val ny = normalY
                var nz = normalR * sz
                val nLength = sqrt(nx * nx + ny * ny + nz * nz)
                if (nLength > 1e-8f) {
                    nx /= nLength
                    nz /= nLength
                }
                ring[j] = builder.addVertex(
                    r * cx, y, r * sz,
                    nx, if (nLength > 1e-8f) ny / nLength else 1f, nz,
                    (j.toFloat() / segments) * uvScaleU, v * uvScaleV,
                    -sz, 0f, cx,
                )
            }
            return ring
        }

        for (i in 0 until pointCount) {
            val radius = profile[i * 2]
            val y = profile[i * 2 + 1]
            val v = arcLength[i] / totalArc
            val hasPrevious = i > 0
            val hasNext = i < pointCount - 1

            when {
                hasPrevious && hasNext -> {
                    val dot = segmentNormalR[i - 1] * segmentNormalR[i] + segmentNormalY[i - 1] * segmentNormalY[i]
                    if (dot > SMOOTHING_DOT) {
                        var nr = segmentNormalR[i - 1] + segmentNormalR[i]
                        var ny = segmentNormalY[i - 1] + segmentNormalY[i]
                        val length = sqrt(nr * nr + ny * ny)
                        if (length > 1e-8f) { nr /= length; ny /= length } else { nr = segmentNormalR[i]; ny = segmentNormalY[i] }
                        val index = rings.size
                        rings.add(emitRing(radius, y, nr, ny, v))
                        ringBefore[i] = index
                        ringAfter[i] = index
                    } else {
                        ringBefore[i] = rings.size
                        rings.add(emitRing(radius, y, segmentNormalR[i - 1], segmentNormalY[i - 1], v))
                        ringAfter[i] = rings.size
                        rings.add(emitRing(radius, y, segmentNormalR[i], segmentNormalY[i], v))
                    }
                }
                hasNext -> {
                    ringAfter[i] = rings.size
                    rings.add(emitRing(radius, y, segmentNormalR[i], segmentNormalY[i], v))
                }
                hasPrevious -> {
                    ringBefore[i] = rings.size
                    rings.add(emitRing(radius, y, segmentNormalR[i - 1], segmentNormalY[i - 1], v))
                }
            }
        }

        for (i in 0 until pointCount - 1) {
            val lower = rings[ringAfter[i]]
            val upper = rings[ringBefore[i + 1]]
            if (lower.size == 1 && upper.size == 1) continue
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                when {
                    lower.size == 1 -> builder.triangle(lower[0], upper[j], upper[next])
                    upper.size == 1 -> builder.triangle(lower[j], upper[0], lower[next])
                    else -> builder.quad(lower[j], upper[j], upper[next], lower[next])
                }
            }
        }

        if (capStart && rings[ringAfter[0]].size > 1) {
            val ring = rings[ringAfter[0]]
            val y = profile[1]
            val centre = builder.addVertex(0f, y, 0f, 0f, -1f, 0f, 0f, 0f, 1f, 0f, 0f)
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                builder.triangle(centre, ring[j], ring[next])
            }
        }
        if (capEnd && rings[ringBefore[pointCount - 1]].size > 1) {
            val ring = rings[ringBefore[pointCount - 1]]
            val y = profile[profile.size - 1]
            val centre = builder.addVertex(0f, y, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f)
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                builder.triangle(centre, ring[next], ring[j])
            }
        }

        return builder.build()
    }

    // ------------------------------------------------------------------ solids of revolution

    /** Straight cylinder (optionally tapered / chamfered) with flat caps. */
    fun cylinder(
        name: String,
        radius: Float,
        height: Float,
        segments: Int,
        topRadius: Float = radius,
        bottomChamfer: Float = 0f,
        topChamfer: Float = 0f,
    ): Mesh {
        val profile = ArrayList<Float>(16)
        if (bottomChamfer > 0f) {
            profile.add(radius - bottomChamfer); profile.add(0f)
            profile.add(radius); profile.add(bottomChamfer)
        } else {
            profile.add(radius); profile.add(0f)
        }
        if (topChamfer > 0f) {
            profile.add(topRadius); profile.add(height - topChamfer)
            profile.add(topRadius - topChamfer); profile.add(height)
        } else {
            profile.add(topRadius); profile.add(height)
        }
        return revolve(name, profile.toFloatArray(), segments, capStart = true, capEnd = true)
    }

    /** Thin disc with a rounded outer edge: platter, mat and record bodies all start here. */
    fun disc(
        name: String,
        radius: Float,
        thickness: Float,
        segments: Int,
        edgeBevelWidth: Float = 0f,
        edgeBevelHeight: Float = 0f,
    ): Mesh {
        val bevelWidth = edgeBevelWidth.coerceIn(0f, radius * 0.5f)
        val bevelHeight = edgeBevelHeight.coerceIn(0f, thickness * 0.45f)
        val profile = floatArrayOf(
            0f, 0f,
            radius - bevelWidth, 0f,
            radius, bevelHeight,
            radius, thickness - bevelHeight,
            radius - bevelWidth, thickness,
            0f, thickness,
        )
        return revolve(name, profile, segments)
    }

    /**
     * Closed annulus (ring / washer), used for the record body and machined collars.
     * Counter-clockwise closed profile: down the inner wall, out along the bottom, up the
     * outer wall and back in along the top.
     */
    fun annulus(
        name: String,
        innerRadius: Float,
        outerRadius: Float,
        thickness: Float,
        segments: Int,
        edgeBevel: Float = 0f,
    ): Mesh {
        val bevel = edgeBevel.coerceIn(0f, minOf((outerRadius - innerRadius) * 0.4f, thickness * 0.45f))
        val profile = floatArrayOf(
            innerRadius, thickness,
            innerRadius, 0f,
            outerRadius - bevel, 0f,
            outerRadius, bevel,
            outerRadius, thickness - bevel,
            outerRadius - bevel, thickness,
            innerRadius, thickness,
        )
        return revolve(name, profile, segments)
    }

    /**
     * Flat annulus whose UVs map the disc extent onto the unit square, so a canvas-drawn
     * bitmap lands exactly where the artwork expects it. Used for the record label.
     */
    fun planarAnnulus(
        name: String,
        innerRadius: Float,
        outerRadius: Float,
        thickness: Float,
        segments: Int,
        uvExtent: Float = outerRadius,
    ): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = segments * 8)
        val inverseExtent = 1f / (uvExtent * 2f)
        val topInner = IntArray(segments)
        val topOuter = IntArray(segments)
        val bottomInner = IntArray(segments)
        val bottomOuter = IntArray(segments)

        for (j in 0 until segments) {
            val angle = (2.0 * PI * j / segments).toFloat()
            val cx = cos(angle)
            val sz = sin(angle)
            fun u(radius: Float): Float = (radius * cx) * inverseExtent + 0.5f
            fun v(radius: Float): Float = (radius * sz) * inverseExtent + 0.5f

            topOuter[j] = builder.addVertex(
                outerRadius * cx, thickness, outerRadius * sz, 0f, 1f, 0f,
                u(outerRadius), v(outerRadius), -sz, 0f, cx,
            )
            topInner[j] = builder.addVertex(
                innerRadius * cx, thickness, innerRadius * sz, 0f, 1f, 0f,
                u(innerRadius), v(innerRadius), -sz, 0f, cx,
            )
            bottomOuter[j] = builder.addVertex(
                outerRadius * cx, 0f, outerRadius * sz, 0f, -1f, 0f,
                u(outerRadius), v(outerRadius), -sz, 0f, cx,
            )
            bottomInner[j] = builder.addVertex(
                innerRadius * cx, 0f, innerRadius * sz, 0f, -1f, 0f,
                u(innerRadius), v(innerRadius), -sz, 0f, cx,
            )
        }

        val wallTop = IntArray(segments)
        val wallBottom = IntArray(segments)
        for (j in 0 until segments) {
            val angle = (2.0 * PI * j / segments).toFloat()
            val cx = cos(angle)
            val sz = sin(angle)
            wallTop[j] = builder.addVertex(
                outerRadius * cx, thickness, outerRadius * sz, cx, 0f, sz,
                (outerRadius * cx) * inverseExtent + 0.5f, (outerRadius * sz) * inverseExtent + 0.5f,
                -sz, 0f, cx,
            )
            wallBottom[j] = builder.addVertex(
                outerRadius * cx, 0f, outerRadius * sz, cx, 0f, sz,
                (outerRadius * cx) * inverseExtent + 0.5f, (outerRadius * sz) * inverseExtent + 0.5f,
                -sz, 0f, cx,
            )
        }

        for (j in 0 until segments) {
            val next = (j + 1) % segments
            builder.quad(topInner[j], topInner[next], topOuter[next], topOuter[j])
            builder.quad(bottomOuter[j], bottomOuter[next], bottomInner[next], bottomInner[j])
            builder.quad(wallBottom[j], wallBottom[next], wallTop[next], wallTop[j])
        }
        return builder.build()
    }

    /**
     * Top surface of the record: a flat annulus whose V coordinate is the *normalised radius*.
     *
     * That mapping lets the fragment shader synthesise concentric grooves with a physically
     * meaningful pitch, while U (angle) keeps the pattern locked to the pressing so it visibly
     * rotates with the platter.
     */
    fun radialAnnulusTop(
        name: String,
        innerRadius: Float,
        outerRadius: Float,
        y: Float,
        segments: Int,
        rings: Int = 4,
    ): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = segments * (rings + 2))
        val span = outerRadius - innerRadius
        val ringIndices = Array(rings + 1) { IntArray(segments) }
        for (r in 0..rings) {
            val t = r.toFloat() / rings
            val radius = innerRadius + span * t
            for (j in 0 until segments) {
                val angle = (2.0 * PI * j / segments).toFloat()
                val cx = cos(angle)
                val sz = sin(angle)
                ringIndices[r][j] = builder.addVertex(
                    radius * cx, y, radius * sz,
                    0f, 1f, 0f,
                    j.toFloat() / segments, t,
                    -sz, 0f, cx,
                )
            }
        }
        for (r in 0 until rings) {
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                builder.quad(ringIndices[r][j], ringIndices[r + 1][j], ringIndices[r + 1][next], ringIndices[r][next])
            }
        }
        return builder.build()
    }

    /** Torus: polished bands, the counterweight collar and the pivot's gimbal ring. */
    fun torus(
        name: String,
        majorRadius: Float,
        minorRadius: Float,
        majorSegments: Int,
        minorSegments: Int,
    ): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = majorSegments * minorSegments)
        val grid = ArrayList<IntArray>(majorSegments)
        for (i in 0 until majorSegments) {
            val majorAngle = (2.0 * PI * i / majorSegments).toFloat()
            val row = IntArray(minorSegments)
            val cosMajor = cos(majorAngle)
            val sinMajor = sin(majorAngle)
            for (j in 0 until minorSegments) {
                val minorAngle = (2.0 * PI * j / minorSegments).toFloat()
                val cosMinor = cos(minorAngle)
                val sinMinor = sin(minorAngle)
                val radius = majorRadius + minorRadius * cosMinor
                row[j] = builder.addVertex(
                    radius * cosMajor, minorRadius * sinMinor, radius * sinMajor,
                    cosMinor * cosMajor, sinMinor, cosMinor * sinMajor,
                    i.toFloat() / majorSegments, j.toFloat() / minorSegments,
                    -sinMajor, 0f, cosMajor,
                )
            }
            grid.add(row)
        }
        for (i in 0 until majorSegments) {
            val current = grid[i]
            val next = grid[(i + 1) % majorSegments]
            for (j in 0 until minorSegments) {
                val jNext = (j + 1) % minorSegments
                builder.quad(current[j], next[j], next[jNext], current[jNext])
            }
        }
        return builder.build()
    }

    /**
     * Tube between two arbitrary points, used for the tonearm's straight sections and the arm
     * bearing housing. Caps are optional so tubes can be butted against other parts.
     */
    @Suppress("LongParameterList")
    fun tubeBetween(
        name: String,
        startX: Float, startY: Float, startZ: Float,
        endX: Float, endY: Float, endZ: Float,
        radius: Float,
        endRadius: Float = radius,
        segments: Int = 24,
        capStart: Boolean = true,
        capEnd: Boolean = true,
    ): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = segments * 4)
        var ax = endX - startX
        var ay = endY - startY
        var az = endZ - startZ
        var length = sqrt(ax * ax + ay * ay + az * az)
        if (length < 1e-7f) length = 1e-7f
        ax /= length; ay /= length; az /= length

        var upX = 0f; var upY = 1f; var upZ = 0f
        if (abs(ay) > 0.95f) { upX = 1f; upY = 0f; upZ = 0f }
        var sideX = ay * upZ - az * upY
        var sideY = az * upX - ax * upZ
        var sideZ = ax * upY - ay * upX
        val sideLength = sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ)
        sideX /= sideLength; sideY /= sideLength; sideZ /= sideLength
        // up2 = axis x side, so (side, up2, axis) is right handed and the ring winds CCW
        // when viewed from the tube's end.
        val up2X = ay * sideZ - az * sideY
        val up2Y = az * sideX - ax * sideZ
        val up2Z = ax * sideY - ay * sideX

        val startRing = IntArray(segments)
        val endRing = IntArray(segments)
        for (j in 0 until segments) {
            val angle = (2.0 * PI * j / segments).toFloat()
            val cx = cos(angle)
            val sz = sin(angle)
            val nx = sideX * cx + up2X * sz
            val ny = sideY * cx + up2Y * sz
            val nz = sideZ * cx + up2Z * sz
            val tx = -sideX * sz + up2X * cx
            val ty = -sideY * sz + up2Y * cx
            val tz = -sideZ * sz + up2Z * cx
            startRing[j] = builder.addVertex(
                startX + nx * radius, startY + ny * radius, startZ + nz * radius,
                nx, ny, nz, j.toFloat() / segments, 0f, tx, ty, tz,
            )
            endRing[j] = builder.addVertex(
                endX + nx * endRadius, endY + ny * endRadius, endZ + nz * endRadius,
                nx, ny, nz, j.toFloat() / segments, 1f, tx, ty, tz,
            )
        }
        for (j in 0 until segments) {
            val next = (j + 1) % segments
            builder.quad(startRing[j], endRing[j], endRing[next], startRing[next])
        }
        if (capStart) {
            val centre = builder.addVertex(startX, startY, startZ, -ax, -ay, -az, 0.5f, 0f, sideX, sideY, sideZ)
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                builder.triangle(centre, startRing[next], startRing[j])
            }
        }
        if (capEnd) {
            val centre = builder.addVertex(endX, endY, endZ, ax, ay, az, 0.5f, 1f, sideX, sideY, sideZ)
            for (j in 0 until segments) {
                val next = (j + 1) % segments
                builder.triangle(centre, endRing[j], endRing[next])
            }
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ prisms

    /**
     * Rectangular beam along an arbitrary segment with optional taper.
     * Used for the headshell, the speed selector's pointer and the gimbal yokes.
     */
    @Suppress("LongParameterList")
    fun beam(
        name: String,
        startX: Float, startY: Float, startZ: Float,
        endX: Float, endY: Float, endZ: Float,
        width: Float,
        height: Float,
        endWidth: Float = width,
        endHeight: Float = height,
    ): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = 32)
        var ax = endX - startX
        var ay = endY - startY
        var az = endZ - startZ
        var length = sqrt(ax * ax + ay * ay + az * az)
        if (length < 1e-7f) length = 1e-7f
        ax /= length; ay /= length; az /= length

        var upX = 0f; var upY = 1f; var upZ = 0f
        if (abs(ay) > 0.95f) { upX = 1f; upY = 0f; upZ = 0f }
        var sideX = ay * upZ - az * upY
        var sideY = az * upX - ax * upZ
        var sideZ = ax * upY - ay * upX
        val sideLength = sqrt(sideX * sideX + sideY * sideY + sideZ * sideZ)
        sideX /= sideLength; sideY /= sideLength; sideZ /= sideLength
        val up2X = ay * sideZ - az * sideY
        val up2Y = az * sideX - ax * sideZ
        val up2Z = ax * sideY - ay * sideX

        // Four corners in (side, up) space, counter clockwise around the axis.
        val signS = floatArrayOf(1f, -1f, -1f, 1f)
        val signU = floatArrayOf(-1f, -1f, 1f, 1f)
        val startRing = IntArray(4)
        val endRing = IntArray(4)
        for (c in 0 until 4) {
            val sw = (width * 0.5f) * signS[c]
            val sh = (height * 0.5f) * signU[c]
            val ew = (endWidth * 0.5f) * signS[c]
            val eh = (endHeight * 0.5f) * signU[c]
            val nx = sideX * signS[c] + up2X * signU[c]
            val ny = sideY * signS[c] + up2Y * signU[c]
            val nz = sideZ * signS[c] + up2Z * signU[c]
            val nl = sqrt(nx * nx + ny * ny + nz * nz)
            startRing[c] = builder.addVertex(
                startX + sideX * sw + up2X * sh,
                startY + sideY * sw + up2Y * sh,
                startZ + sideZ * sw + up2Z * sh,
                nx / nl, ny / nl, nz / nl,
                c.toFloat() / 4f, 0f,
                ax, ay, az,
            )
            endRing[c] = builder.addVertex(
                endX + sideX * ew + up2X * eh,
                endY + sideY * ew + up2Y * eh,
                endZ + sideZ * ew + up2Z * eh,
                nx / nl, ny / nl, nz / nl,
                c.toFloat() / 4f, 1f,
                ax, ay, az,
            )
        }
        for (c in 0 until 4) {
            val next = (c + 1) % 4
            builder.quad(startRing[c], endRing[c], endRing[next], startRing[next])
        }
        // End caps, wound counter clockwise as seen from outside the beam.
        val startCentre = builder.addVertex(startX, startY, startZ, -ax, -ay, -az, 0.5f, 0f, sideX, sideY, sideZ)
        val endCentre = builder.addVertex(endX, endY, endZ, ax, ay, az, 0.5f, 1f, sideX, sideY, sideZ)
        for (c in 0 until 4) {
            val next = (c + 1) % 4
            builder.triangle(startCentre, startRing[next], startRing[c])
            builder.triangle(endCentre, endRing[c], endRing[next])
        }
        return builder.build()
    }

    /** Axis-aligned box, centred on XZ with its base at `y = 0`. */
    fun box(name: String, width: Float, height: Float, depth: Float): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = 24)
        val hx = width * 0.5f
        val hz = depth * 0.5f
        // (right, up) bases chosen so that right x up equals the outward normal.
        addFacedQuad(builder, 0f, 0f, 0f, hx, hz, 1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f)
        addFacedQuad(builder, 0f, height, 0f, hz, hx, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
        addFacedQuad(builder, 0f, height * 0.5f, hz, hx, height * 0.5f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        addFacedQuad(builder, 0f, height * 0.5f, -hz, hx, height * 0.5f, -1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, -1f)
        addFacedQuad(builder, hx, height * 0.5f, 0f, hz, height * 0.5f, 0f, 0f, -1f, 0f, 1f, 0f, 1f, 0f, 0f)
        addFacedQuad(builder, -hx, height * 0.5f, 0f, hz, height * 0.5f, 0f, 0f, 1f, 0f, 1f, 0f, -1f, 0f, 0f)
        return builder.build()
    }

    /**
     * Extruded rounded rectangle with optional top and bottom bevels: the turntable plinth.
     *
     * The outline is authored counter clockwise in the XZ plane (right edge towards +Z, front
     * edge towards -X, ...) so `up x tangent` points outwards everywhere. The widest part of
     * the solid is the straight wall; both caps are inset by their bevel.
     */
    @Suppress("LongParameterList")
    fun roundedPrism(
        name: String,
        width: Float,
        depth: Float,
        height: Float,
        cornerRadius: Float,
        cornerSegments: Int = 8,
        topBevel: Float = 0f,
        bottomBevel: Float = 0f,
        edgeSegments: Int = 4,
    ): Mesh {
        val radius = cornerRadius.coerceIn(0f, minOf(width, depth) * 0.49f)
        val outline = buildRoundedOutline(width, depth, radius, cornerSegments, edgeSegments)
        val pointCount = outline.size / 4 // x, z, nx, nz
        val builder = MeshBuilder(name, vertexEstimate = pointCount * 14)

        val bottomInset = bottomBevel.coerceAtMost(height * 0.4f)
        val topInset = topBevel.coerceAtMost(height * 0.4f)
        val wallBottom = bottomInset
        val wallTop = height - topInset

        // Chamfer normals in the (outward, up) basis; the same rule as the lathe profiles:
        // direction (dr, dy) -> outward normal (dy, -dr).
        val bottomChamferR: Float
        val bottomChamferY: Float
        if (bottomInset > 1e-6f) {
            val length = sqrt(bottomInset * bottomInset + bottomInset * bottomInset)
            bottomChamferR = bottomInset / length
            bottomChamferY = -bottomInset / length
        } else {
            bottomChamferR = 0f
            bottomChamferY = -1f
        }
        val topChamferR: Float
        val topChamferY: Float
        if (topInset > 1e-6f) {
            val length = sqrt(topInset * topInset + topInset * topInset)
            topChamferR = topInset / length
            topChamferY = topInset / length
        } else {
            topChamferR = 0f
            topChamferY = 1f
        }

        val bottomFaceRim = IntArray(pointCount)
        val bottomChamferStart = IntArray(pointCount)
        val wallBottomRing = IntArray(pointCount)
        val wallTopRing = IntArray(pointCount)
        val topChamferStart = IntArray(pointCount)
        val topChamferEnd = IntArray(pointCount)
        val topFaceRim = IntArray(pointCount)

        val perimeter = outlinePerimeter(outline, pointCount)
        var travelled = 0f

        for (i in 0 until pointCount) {
            val x = outline[i * 4]
            val z = outline[i * 4 + 1]
            val nx = outline[i * 4 + 2]
            val nz = outline[i * 4 + 3]
            if (i > 0) {
                val px = outline[(i - 1) * 4]
                val pz = outline[(i - 1) * 4 + 1]
                travelled += sqrt((x - px) * (x - px) + (z - pz) * (z - pz))
            }
            val u = if (perimeter > 1e-5f) travelled / perimeter else 0f
            val tangentX = -nz
            val tangentZ = nx

            bottomFaceRim[i] = builder.addVertex(
                x - nx * bottomInset, 0f, z - nz * bottomInset,
                0f, -1f, 0f, u, 0f, tangentX, 0f, tangentZ,
            )
            bottomChamferStart[i] = builder.addVertex(
                x - nx * bottomInset, 0f, z - nz * bottomInset,
                nx * bottomChamferR, bottomChamferY, nz * bottomChamferR,
                u, 0.05f, tangentX, 0f, tangentZ,
            )
            wallBottomRing[i] = builder.addVertex(
                x, wallBottom, z,
                nx, 0f, nz,
                u, 0.12f, tangentX, 0f, tangentZ,
            )
            wallTopRing[i] = builder.addVertex(
                x, wallTop, z,
                nx, 0f, nz,
                u, 0.82f, tangentX, 0f, tangentZ,
            )
            topChamferStart[i] = builder.addVertex(
                x, wallTop, z,
                nx * topChamferR, topChamferY, nz * topChamferR,
                u, 0.90f, tangentX, 0f, tangentZ,
            )
            topChamferEnd[i] = builder.addVertex(
                x - nx * topInset, height, z - nz * topInset,
                nx * topChamferR, topChamferY, nz * topChamferR,
                u, 0.97f, tangentX, 0f, tangentZ,
            )
            topFaceRim[i] = builder.addVertex(
                x - nx * topInset, height, z - nz * topInset,
                0f, 1f, 0f, u, 1f, tangentX, 0f, tangentZ,
            )
        }

        val hasBottomChamfer = bottomInset > 1e-6f
        val hasTopChamfer = topInset > 1e-6f
        for (i in 0 until pointCount) {
            val next = (i + 1) % pointCount
            if (hasBottomChamfer) {
                builder.quad(bottomChamferStart[i], wallBottomRing[i], wallBottomRing[next], bottomChamferStart[next])
            }
            builder.quad(wallBottomRing[i], wallTopRing[i], wallTopRing[next], wallBottomRing[next])
            if (hasTopChamfer) {
                builder.quad(topChamferStart[i], topChamferEnd[i], topChamferEnd[next], topChamferStart[next])
            }
        }

        val topCentre = builder.addVertex(0f, height, 0f, 0f, 1f, 0f, 0.5f, 1f, 1f, 0f, 0f)
        val bottomCentre = builder.addVertex(0f, 0f, 0f, 0f, -1f, 0f, 0.5f, 0f, 1f, 0f, 0f)
        for (i in 0 until pointCount) {
            val next = (i + 1) % pointCount
            builder.triangle(topCentre, topFaceRim[next], topFaceRim[i])
            builder.triangle(bottomCentre, bottomFaceRim[i], bottomFaceRim[next])
        }
        return builder.build()
    }

    /** Single quad in the XZ plane with an upward normal, used for the studio floor. */
    fun groundPlane(name: String, size: Float, segments: Int = 4): Mesh {
        val builder = MeshBuilder(name, vertexEstimate = 8)
        val clamped = segments.coerceAtLeast(1)
        val half = size * 0.5f
        if (clamped == 1) {
            addFacedQuad(builder, 0f, 0f, 0f, half, half, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
            return builder.build()
        }
        // Slightly tessellated so per-fragment lighting gradients spread evenly across a very
        // large quad, and so the analytic contact shadow is sampled densely near the plinth.
        val step = size / clamped
        for (ix in 0 until clamped) {
            for (iz in 0 until clamped) {
                val x = -half + (ix + 0.5f) * step
                val z = -half + (iz + 0.5f) * step
                addFacedQuad(builder, x, 0f, z, step * 0.5f, step * 0.5f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f)
            }
        }
        return builder.build()
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Adds a quad from a centre plus orthonormal-ish (right, up) basis. The winding is derived
     * from the basis, so the emitted triangles are guaranteed counter clockwise when viewed
     * from the side the normal points to.
     */
    @Suppress("LongParameterList")
    fun addFacedQuad(
        builder: MeshBuilder,
        centreX: Float, centreY: Float, centreZ: Float,
        halfRight: Float, halfUp: Float,
        rightX: Float, rightY: Float, rightZ: Float,
        upX: Float, upY: Float, upZ: Float,
        normalX: Float, normalY: Float, normalZ: Float,
        uScale: Float = 1f,
        vScale: Float = 1f,
    ) {
        val a = builder.addVertex(
            centreX - rightX * halfRight - upX * halfUp,
            centreY - rightY * halfRight - upY * halfUp,
            centreZ - rightZ * halfRight - upZ * halfUp,
            normalX, normalY, normalZ, 0f, 0f, rightX, rightY, rightZ,
        )
        val b = builder.addVertex(
            centreX + rightX * halfRight - upX * halfUp,
            centreY + rightY * halfRight - upY * halfUp,
            centreZ + rightZ * halfRight - upZ * halfUp,
            normalX, normalY, normalZ, uScale, 0f, rightX, rightY, rightZ,
        )
        val c = builder.addVertex(
            centreX + rightX * halfRight + upX * halfUp,
            centreY + rightY * halfRight + upY * halfUp,
            centreZ + rightZ * halfRight + upZ * halfUp,
            normalX, normalY, normalZ, uScale, vScale, rightX, rightY, rightZ,
        )
        val d = builder.addVertex(
            centreX - rightX * halfRight + upX * halfUp,
            centreY - rightY * halfRight + upY * halfUp,
            centreZ - rightZ * halfRight + upZ * halfUp,
            normalX, normalY, normalZ, 0f, vScale, rightX, rightY, rightZ,
        )
        builder.quad(a, b, c, d)
    }

    /**
     * Adds a planar quad from four explicit corners with a shared normal. Corners must be given
     * counter clockwise as seen from the side the normal points to.
     */
    @Suppress("LongParameterList")
    fun addPlanarQuad(
        builder: MeshBuilder,
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        nx: Float, ny: Float, nz: Float,
        uScale: Float = 1f,
        vScale: Float = 1f,
    ) {
        val tx = x1 - x0
        val ty = y1 - y0
        val tz = z1 - z0
        val tangentLength = sqrt(tx * tx + ty * ty + tz * tz)
        val tanX = if (tangentLength > 1e-8f) tx / tangentLength else 1f
        val tanY = if (tangentLength > 1e-8f) ty / tangentLength else 0f
        val tanZ = if (tangentLength > 1e-8f) tz / tangentLength else 0f

        val a = builder.addVertex(x0, y0, z0, nx, ny, nz, 0f, 0f, tanX, tanY, tanZ)
        val b = builder.addVertex(x1, y1, z1, nx, ny, nz, uScale, 0f, tanX, tanY, tanZ)
        val c = builder.addVertex(x2, y2, z2, nx, ny, nz, uScale, vScale, tanX, tanY, tanZ)
        val d = builder.addVertex(x3, y3, z3, nx, ny, nz, 0f, vScale, tanX, tanY, tanZ)
        builder.quad(a, b, c, d)
    }

    /** Column-major matrix for a translation plus optional Y rotation. */
    fun translationMatrix(translateX: Float, translateY: Float, translateZ: Float, rotateYDegrees: Float = 0f): FloatArray {
        val matrix = FloatArray(16)
        Mat4.setTranslation(translateX, translateY, translateZ, matrix, 0)
        if (rotateYDegrees != 0f) {
            val rotation = FloatArray(16)
            Mat4.setRotationY(rotateYDegrees, rotation, 0)
            Mat4.multiply(matrix, 0, rotation, 0, matrix, 0)
        }
        return matrix
    }

    /**
     * Builds the counter-clockwise rounded rectangle outline as `x, z, nx, nz` records.
     *
     * Order: right edge towards +Z, front-right arc, front edge towards -X, front-left arc,
     * left edge towards -Z, back-left arc, back edge towards +X, back-right arc.
     */
    private fun buildRoundedOutline(
        width: Float,
        depth: Float,
        cornerRadius: Float,
        cornerSegments: Int,
        edgeSegments: Int,
    ): FloatArray {
        val hx = width * 0.5f
        val hz = depth * 0.5f
        val r = cornerRadius
        val points = ArrayList<Float>(96)

        fun straight(fromX: Float, fromZ: Float, toX: Float, toZ: Float, nx: Float, nz: Float) {
            for (i in 0 until edgeSegments) {
                val t = i.toFloat() / edgeSegments
                points.add(fromX + (toX - fromX) * t)
                points.add(fromZ + (toZ - fromZ) * t)
                points.add(nx)
                points.add(nz)
            }
        }

        fun arc(cx: Float, cz: Float, startDeg: Float, endDeg: Float) {
            for (i in 0 until cornerSegments) {
                val t = i.toFloat() / cornerSegments
                val angle = Math.toRadians((startDeg + (endDeg - startDeg) * t).toDouble())
                val nx = cos(angle).toFloat()
                val nz = sin(angle).toFloat()
                points.add(cx + nx * r)
                points.add(cz + nz * r)
                points.add(nx)
                points.add(nz)
            }
        }

        straight(hx, -hz + r, hx, hz - r, 1f, 0f)
        arc(hx - r, hz - r, 0f, 90f)
        straight(hx - r, hz, -hx + r, hz, 0f, 1f)
        arc(-hx + r, hz - r, 90f, 180f)
        straight(-hx, hz - r, -hx, -hz + r, -1f, 0f)
        arc(-hx + r, -hz + r, 180f, 270f)
        straight(-hx + r, -hz, hx - r, -hz, 0f, -1f)
        arc(hx - r, -hz + r, 270f, 360f)

        return points.toFloatArray()
    }

    private fun outlinePerimeter(outline: FloatArray, pointCount: Int): Float {
        var perimeter = 0f
        for (i in 0 until pointCount) {
            val next = (i + 1) % pointCount
            val dx = outline[next * 4] - outline[i * 4]
            val dz = outline[next * 4 + 1] - outline[i * 4 + 1]
            perimeter += sqrt(dx * dx + dz * dz)
        }
        return perimeter
    }
}

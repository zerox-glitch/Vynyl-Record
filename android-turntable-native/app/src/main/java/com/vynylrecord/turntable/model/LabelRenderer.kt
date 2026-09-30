package com.vynylrecord.turntable.model

/**
 * Geometry of the printed label in normalised bitmap coordinates (`0..1` on both axes, the label
 * circle inscribed in the square).
 *
 * Kept next to [LabelTextLayout] so the layout maths is shared between the Canvas renderer and the
 * unit tests, and so integrators can re-target the artwork without touching drawing code.
 */
object LabelRendererSpec {

    /** Radius of the printable label area, in normalised units. */
    const val LABEL_RADIUS = 0.5f

    const val RING_OUTER_RADIUS = 0.470f
    const val RING_INNER_RADIUS = 0.436f
    const val RING_THIN_RADIUS = 0.150f

    /** Spindle hole, drawn as a ring because the mesh already has the physical hole. */
    const val HOLE_RADIUS = TurntableSpec.LABEL_HOLE_RADIUS / TurntableSpec.LABEL_RADIUS

    /** Vertical anchors, as a fraction of the bitmap height. */
    const val MARK_Y = 0.170f
    const val TITLE_FIRST_LINE_Y = 0.330f
    const val TITLE_LINE_SPACING = 0.078f
    const val DEDICATION_Y = 0.505f
    const val SIDE_BADGE_Y = 0.650f
    const val SIGNATURE_Y = 0.735f
    const val DATE_Y = 0.790f
    const val CATALOGUE_Y = 0.150f

    /** Widest text run allowed, as a fraction of the bitmap width. */
    const val TEXT_WIDTH_BUDGET = 0.62f

    /** Font sizes as a fraction of the bitmap size. */
    const val TITLE_SIZE = 0.121f
    const val DEDICATION_SIZE = 0.050f
    const val SIGNATURE_SIZE = 0.045f
    const val SIDE_SIZE = 0.052f
    const val MARK_SIZE = 0.036f
    const val CATALOGUE_SIZE = 0.032f

    /** Bottom line printed on every label. */
    const val WORDMARK = "VYNVL RECORD"

    /** Right-hand corner mark. */
    const val SLEEVE_NUMBER_PREFIX = "VR-"

    /** Suffix printed after the side letter, e.g. "SIDE A". */
    fun sideLabel(side: RecordSide): String = "SIDE ${side.shortName}"
}

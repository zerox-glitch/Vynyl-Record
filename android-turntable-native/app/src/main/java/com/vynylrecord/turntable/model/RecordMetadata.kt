package com.vynylrecord.turntable.model

/** Which side of the record a voice note was pressed onto. */
enum class RecordSide(val displayName: String, val shortName: String) {
    A("Side A", "A"),
    B("Side B", "B");

    fun flipped(): RecordSide = if (this == A) B else A
}

/**
 * Everything the label artwork needs.
 *
 * The label is generated locally on an Android Canvas (see
 * [com.vynylrecord.turntable.graphics.material.LabelTextureFactory]); this type carries no
 * Android dependency so it can be unit tested and passed across threads as an immutable
 * snapshot.
 */
data class RecordMetadata(
    val title: String,
    val recipient: String,
    val sender: String,
    val side: RecordSide = RecordSide.A,
    /** Optional free-form date printed under the sender, e.g. "14 Feb 2026". */
    val date: String? = null,
    /** Optional catalogue line printed around the rim of the label. */
    val catalogue: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }

    /** Line printed under the title on the label. */
    fun dedicationLine(): String = "for $recipient"

    /** Line printed at the bottom of the label. */
    fun signatureLine(): String = "from $sender"

    fun normalized(): RecordMetadata = copy(
        title = title.trim(),
        recipient = recipient.trim(),
        sender = sender.trim(),
        date = date?.trim()?.takeIf { it.isNotEmpty() },
        catalogue = catalogue?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** True when two snapshots would render identically, so the label texture can be cached. */
    fun rendersSameLabelAs(other: RecordMetadata?): Boolean =
        other != null &&
            other.title.trim() == title.trim() &&
            other.recipient.trim() == recipient.trim() &&
            other.sender.trim() == sender.trim() &&
            other.side == side &&
            other.date?.trim() == date?.trim() &&
            other.catalogue?.trim() == catalogue?.trim()

    companion object {
        val DEFAULT = RecordMetadata(
            title = "Side A · Voice Note",
            recipient = "Someone Special",
            sender = "Vynyl Record",
            side = RecordSide.A,
        )
    }
}

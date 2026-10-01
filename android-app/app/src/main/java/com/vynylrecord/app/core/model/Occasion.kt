package com.vynylrecord.app.core.model

/**
 * What a record is for.
 *
 * The ids match the web application's `OccasionType` exactly, so a `.vynyl` bundle exported from one
 * is imported by the other without a translation table. The prompts are the emotional copy from the
 * web Studio — they are the reason this screen exists rather than a bare text field, and they are
 * shown next to the field the user is about to type into.
 *
 * [ordinal] is not used as a storage key anywhere: bundles and the database store [id], so the order
 * of this enum can change without breaking existing records.
 */
enum class Occasion(
    val id: String,
    val label: String,
    val prompt: String,
) {
    WEDDING(
        id = "wedding",
        label = "Wedding",
        prompt = "Write like you\u2019re standing next to the person you\u2019re marrying.",
    ),
    ANNIVERSARY(
        id = "anniversary",
        label = "Anniversary",
        prompt = "What do you want them to know after fifty more years together?",
    ),
    BIRTHDAY(
        id = "birthday",
        label = "Birthday",
        prompt = "A birthday wish they\u2019ll play on their morning coffee.",
    ),
    LOVE_LETTER(
        id = "love_letter",
        label = "Love Letter",
        prompt = "The things you\u2019d say if they were sitting across from you.",
    ),
    LONG_DISTANCE(
        id = "long_distance",
        label = "Long Distance",
        prompt = "You\u2019re far apart right now. What do you want them to feel tonight?",
    ),
    FAMILY(
        id = "family",
        label = "Family Memory",
        prompt = "The kind of family story they\u2019ll tell their own kids one day.",
    ),
    GRANDPARENTS(
        id = "grandparents",
        label = "For Grandparents",
        prompt = "Say it now while they can still hear you.",
    ),
    BABY(
        id = "baby",
        label = "For a Baby",
        prompt = "Things to tell a child when they\u2019re old enough to listen.",
    ),
    MEMORIAL(
        id = "memorial",
        label = "Memorial",
        prompt = "Speak to the person who isn\u2019t here, the way you wish you could.",
    ),
    SOMETHING_ELSE(
        id = "something_else",
        label = "Something Else",
        prompt = "No category needed \u2014 just say it.",
    ),
    ;

    companion object {
        val all: List<Occasion> = entries.toList()

        fun fromId(id: String?): Occasion =
            entries.firstOrNull { it.id == id } ?: SOMETHING_ELSE
    }
}

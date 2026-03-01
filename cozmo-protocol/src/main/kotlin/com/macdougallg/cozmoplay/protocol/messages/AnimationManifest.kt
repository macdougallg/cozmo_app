package com.macdougallg.cozmoplay.protocol.messages

/**
 * Known Cozmo animation names verified against the PyCozmo animation manifest.
 *
 * These names are passed directly to the robot via PlayAnimation messages.
 * Agent 1 MUST cross-reference these against actual PyCozmo anim/ directory
 * before shipping to confirm they exist on target firmware.
 *
 * Minimum 20 animations required across categories (Protocol PRD FR-03).
 */
object AnimationManifest {

    val HAPPY = listOf(
        "anim_happy_01",
        "anim_happy_03",
        "anim_greeting_happy_01",
        "anim_excited_01",
    )

    val SILLY = listOf(
        "anim_hiccup_01",
        "anim_dizzy_shakeonce_01",
        "anim_sneeze_01",
        "anim_bored_01",
    )

    val ANGRY = listOf(
        "anim_angry_01",
        "anim_angry_frustrated_01",
        "anim_frustrated_01",
    )

    val SAD = listOf(
        "anim_sad_01",
        "anim_whimper_01",
        "anim_bored_event_01",
    )

    val SURPRISED = listOf(
        "anim_surprised_01",
        "anim_startled_01",
        "anim_scared_01",
    )

    val SPECIAL = listOf(
        "anim_gotosleep_01",
        "anim_sleeping_01",
        "anim_feedback_scanneractivated_01",
        "anim_feedback_pickup_01",
    )

    /** All animations — used to populate ICozmoProtocol.availableAnimations */
    val ALL: List<String> = HAPPY + SILLY + ANGRY + SAD + SURPRISED + SPECIAL

    /** Category-to-list mapping for the UI's category filter */
    val BY_CATEGORY: Map<String, List<String>> = mapOf(
        "Happy"     to HAPPY,
        "Silly"     to SILLY,
        "Angry"     to ANGRY,
        "Sad"       to SAD,
        "Surprised" to SURPRISED,
        "Special"   to SPECIAL,
    )
}

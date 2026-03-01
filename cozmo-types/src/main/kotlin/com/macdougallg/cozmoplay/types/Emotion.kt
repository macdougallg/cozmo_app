package com.macdougallg.cozmoplay.types

/**
 * Cozmo's current displayed emotional state.
 * Derived from RobotState flags in incoming protocol messages.
 */
enum class Emotion {
    NEUTRAL,
    HAPPY,
    EXCITED,
    ANGRY,
    SAD,
    SURPRISED,
    BORED,
    SCARED
}

/**
 * Result of a suspend action (animation, pickup, roll, place).
 * Returned by ICozmoProtocol suspend functions.
 */
sealed class ActionResult {
    object Success : ActionResult()
    data class Failure(val reason: String) : ActionResult()
    /** Action did not complete within the allowed timeout window (15 seconds). */
    object Timeout : ActionResult()
    /** Action was cancelled before completion (e.g. user navigated away). */
    object Abandoned : ActionResult()
}

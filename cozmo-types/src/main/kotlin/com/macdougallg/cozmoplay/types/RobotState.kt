package com.macdougallg.cozmoplay.types

/**
 * Live telemetry snapshot of the Cozmo robot.
 * Updated at approximately 60Hz from incoming RobotState protocol messages.
 * All consumers should observe via StateFlow<RobotState>.
 */
data class RobotState(
    /** World X position in millimetres. */
    val poseX: Float = 0f,
    /** World Y position in millimetres. */
    val poseY: Float = 0f,
    /** Robot heading in radians. */
    val poseAngle: Float = 0f,
    /** Head tilt angle in radians. Valid range: -0.44 to +0.78. */
    val headAngle: Float = 0f,
    /** Lift height in millimetres. Valid range: 32 to 92. */
    val liftHeight: Float = 32f,
    /** Battery voltage in volts. Display low-battery warning below 3.5V. */
    val batteryVoltage: Float = 0f,
    /** True when a cube is resting on the robot's lift. */
    val isCarryingBlock: Boolean = false,
    /** True while a pick-up or place action is in progress. */
    val isPickingOrPlacing: Boolean = false,
    /** True when one or both wheels are moving. */
    val isMoving: Boolean = false,
    /** True when an animation is currently playing. */
    val isAnimating: Boolean = false,
    /** Left wheel speed in mm/s. Negative = reverse. */
    val leftWheelSpeed: Float = 0f,
    /** Right wheel speed in mm/s. Negative = reverse. */
    val rightWheelSpeed: Float = 0f,
    /** Current displayed emotion derived from robot state flags. */
    val emotion: Emotion = Emotion.NEUTRAL,
)

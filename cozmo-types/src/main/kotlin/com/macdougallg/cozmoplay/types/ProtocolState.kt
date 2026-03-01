package com.macdougallg.cozmoplay.types

/**
 * Represents all possible states of the Cozmo protocol connection.
 * Owned by cozmo-protocol; consumed by app (UI).
 */
sealed class ProtocolState {
    object Idle : ProtocolState()
    object Connecting : ProtocolState()
    object Connected : ProtocolState()
    object Disconnected : ProtocolState()
    data class Error(val reason: ProtocolError) : ProtocolState()
}

enum class ProtocolError {
    HANDSHAKE_TIMEOUT,
    PONG_TIMEOUT,
    RECEIVE_LOOP_FAILED,
    SOCKET_ERROR
}

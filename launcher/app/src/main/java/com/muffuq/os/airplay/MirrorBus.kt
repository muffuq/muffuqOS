package com.muffuq.os.airplay

import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Connects the AirPlay service (network side) with the mirroring screen (UI side). */
object MirrorBus {
    sealed interface State {
        data object Idle : State
        data class Connecting(val client: String) : State
        /** [showing] turns true once the first frame is actually on screen. */
        data class Mirroring(
            val client: String,
            val width: Int,
            val height: Int,
            val paused: Boolean,
            val showing: Boolean,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile var activePlayer: VideoPlayer? = null
        internal set

    /** Set by the service; called when the user presses Back on the mirroring screen. */
    @Volatile internal var stopRequest: (() -> Unit)? = null

    internal fun update(state: State) {
        _state.value = state
    }

    fun attachSurface(surface: Surface?) {
        activePlayer?.setSurface(surface)
    }

    fun stop() {
        stopRequest?.invoke()
    }
}

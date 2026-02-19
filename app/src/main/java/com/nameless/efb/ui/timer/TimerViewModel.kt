package com.nameless.efb.ui.timer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class TimerMode { COUNT_UP, COUNTDOWN }

data class TimerState(
    val mode: TimerMode = TimerMode.COUNT_UP,
    val elapsedSec: Int = 0,
    val durationSec: Int = 0,
    val isRunning: Boolean = false,
    val isExpired: Boolean = false,
)

/**
 * ViewModel managing up to 3 concurrent timers (UT-04).
 *
 * Timer state survives configuration changes (ViewModel scope).
 * On countdown expiry, [onTimerExpired] is invoked on the UI thread —
 * the caller should wire in haptic feedback and audio chime.
 *
 * Timers are zero-indexed (0, 1, 2).
 */
class TimerViewModel(
    private val onTimerExpired: (index: Int) -> Unit = {},
) : ViewModel() {

    private val _timers = List(3) { MutableStateFlow(TimerState()) }
    val timers: List<StateFlow<TimerState>> = _timers

    private val tickJobs = arrayOfNulls<Job>(3)

    fun start(index: Int, mode: TimerMode, durationSec: Int = 0) {
        require(index in 0..2)
        tickJobs[index]?.cancel()
        _timers[index].value = TimerState(
            mode        = mode,
            elapsedSec  = 0,
            durationSec = durationSec,
            isRunning   = true,
        )
        tickJobs[index] = viewModelScope.launch {
            while (true) {
                delay(1_000)
                val current = _timers[index].value
                if (!current.isRunning) break
                val newElapsed = current.elapsedSec + 1
                val expired = mode == TimerMode.COUNTDOWN && newElapsed >= durationSec
                _timers[index].value = current.copy(
                    elapsedSec = newElapsed,
                    isExpired  = expired,
                    isRunning  = !expired,
                )
                if (expired) {
                    onTimerExpired(index)
                    break
                }
            }
        }
    }

    fun stop(index: Int) {
        require(index in 0..2)
        tickJobs[index]?.cancel()
        _timers[index].value = _timers[index].value.copy(isRunning = false)
    }

    fun reset(index: Int) {
        require(index in 0..2)
        tickJobs[index]?.cancel()
        _timers[index].value = TimerState()
    }

    override fun onCleared() {
        tickJobs.forEach { it?.cancel() }
        super.onCleared()
    }
}

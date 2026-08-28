package com.rakshika.app.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DemoViewModel : ViewModel() {
    private val _state = MutableStateFlow(DemoState())
    val state: StateFlow<DemoState> = _state

    private var playJob: Job? = null

    init {
        applyBeat(DemoScene.SOS, 0)
    }

    fun selectScene(scene: DemoScene) {
        stopPlayback()
        applyBeat(scene, 0)
    }

    fun goToBeat(index: Int) {
        stopPlayback()
        applyBeat(_state.value.scene, index)
    }

    fun toggleMute() {
        _state.update { it.copy(isMuted = !it.isMuted) }
    }

    /** Plays through the remaining beats of the current scene, then the other scene, narrating each via [speak]. */
    fun togglePlay(speak: suspend (String) -> Unit) {
        if (_state.value.isPlaying) {
            stopPlayback()
            return
        }
        var scene = _state.value.scene
        var index = _state.value.beatIndex

        playJob = viewModelScope.launch {
            _state.update { it.copy(isPlaying = true) }
            while (isActive) {
                val beats = DemoScript.beatsFor(scene)
                if (index >= beats.size) {
                    if (scene == DemoScene.SOS) {
                        scene = DemoScene.ROUTE
                        index = 0
                        continue
                    } else {
                        break
                    }
                }
                applyBeat(scene, index)
                val text = beats[index].caption
                if (_state.value.isMuted) {
                    delay((text.length * 55L).coerceAtLeast(1400L))
                } else {
                    speak(text)
                }
                index += 1
            }
            applyBeat(DemoScene.SOS, 0)
            _state.update { it.copy(isPlaying = false) }
        }
    }

    private fun applyBeat(scene: DemoScene, index: Int) {
        val beat = DemoScript.beatsFor(scene)[index]
        _state.update { current ->
            beat.apply(current).copy(
                scene = scene,
                beatIndex = index,
                view = beat.view,
                caption = beat.caption,
                teleStatus = beat.teleStatus,
                teleDetail = beat.teleDetail,
                teleContacts = beat.teleContacts
            )
        }
    }

    private fun stopPlayback() {
        playJob?.cancel()
        playJob = null
        _state.update { it.copy(isPlaying = false) }
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }
}

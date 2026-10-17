package com.sample.videohub.utils

import android.view.View
import com.sample.videohub.databinding.ItemParticipantBinding
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

class StateFlowable<T> {

    private val _state = MutableStateFlow<T?>(null)
    val state: StateFlow<T?> = _state.asStateFlow()

    suspend fun emit(message: T) {
        _state.emit(message)
    }

}


fun View.visibleOrInvisible1(visible: Boolean) {
    visibility = if (visible) {
        View.VISIBLE
    } else {
        View.INVISIBLE
    }
}

private fun showFocus1(binding: ItemParticipantBinding) {
    binding.speakingIndicator.visibility = View.VISIBLE
}

private fun hideFocus1(binding: ItemParticipantBinding) {
    binding.speakingIndicator.visibility = View.INVISIBLE
}

private inline fun <T, R> Flow<T?>.flatMapLatestOrNull1(
    crossinline transform: suspend (value: T) -> Flow<R>,
): Flow<R?> {
    return flatMapLatest {
        if (it == null) {
            flowOf(null)
        } else {
            transform(it)
        }
    }
}

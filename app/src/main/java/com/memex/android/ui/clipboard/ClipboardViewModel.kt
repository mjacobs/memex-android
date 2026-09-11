package com.memex.android.ui.clipboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memex.android.data.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Clipboard state stays in memory; it never enters durable capture drafts. */
class ClipboardViewModel(private val repository: ClipboardRepository) : ViewModel() {
    data class State(val items: List<ClipboardItem> = emptyList(), val busy: Boolean = false,
                     val message: String? = null, val hasDraft: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var draft: ClipboardRequest? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            while (true) {
                delay(1000)
                mutable.update { it.copy(items = it.items.filter { item -> item.isLive() }) }
            }
        }
    }
    private fun run(block: suspend () -> String?) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            mutable.update { it.copy(busy = true, message = null) }
            try {
                val message = block()
                mutable.update { it.copy(items = repository.list(), message = message) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(message = "Request failed. Check your Memex connection and retry.") }
            } finally { mutable.update { it.copy(busy = false, hasDraft = draft != null) } }
        }
    }
    fun refresh() = run { null }
    fun stage(request: ClipboardRequest) { draft = request; mutable.update { it.copy(hasDraft = true, message = "Ready to send to Clipboard") } }
    fun send() = run {
        val request = draft ?: return@run null
        repository.send(request)
        draft = null
        "Sent to Clipboard. Expires in 24 hours."
    }
    fun discard() { draft = null; mutable.update { it.copy(hasDraft = false, message = null) } }
    fun delete(item: ClipboardItem) = run { repository.delete(item.id); "Deleted" }
    fun keep(item: ClipboardItem) = run {
        val result = repository.keep(item.id)
        if (result.status == "enriched") "Saved to Memex" else "Memex is processing this item; tap Keep again to check."
    }
}

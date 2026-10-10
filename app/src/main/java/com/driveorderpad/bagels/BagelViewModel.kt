package com.driveorderpad.bagels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class BagelUiState(val loading: Boolean = true, val book: BagelNotebook? = null, val catalog: BagelCatalog = BagelCatalog.fallback, val proposal: List<BagelCar> = emptyList(), val editId: String? = null, val message: String? = null, val canUndo: Boolean = false, val saving: Boolean = false, val saveFailed: Boolean = false, val listening: Boolean = false, val partial: String = "")
class BagelViewModel(private val saved: SavedStateHandle, private val repository: BagelRepository, private val loadCatalog: suspend () -> BagelCatalog) : ViewModel() {
    private val mutable = MutableStateFlow(BagelUiState(editId = saved["bagel.editId"]))
    val state: StateFlow<BagelUiState> = mutable
    private val events = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var undo: Pair<Long, BagelNotebook>? = null
    private var captureBase: Long = 0
    private var session: String? = null
    init {
        viewModelScope.launch { for (event in events) event() }
        retry()
    }
    private fun enqueue(block: suspend () -> Unit) { events.trySend(block) }
    fun retry() = enqueue {
        try {
            val catalog = loadCatalog()
            val book = repository.load()
            mutable.value = mutable.value.copy(loading = false, book = book, catalog = catalog, saveFailed = false, message = if (book.interrupted) "Capture was interrupted. Heard text is saved; review it before using the batch." else null)
            if (book.draft.isNotBlank()) organizeInternal(book)
        } catch (e: Exception) { mutable.value = mutable.value.copy(loading = false, saveFailed = true, message = e.message ?: "Could not load bagel notes.") }
    }
    private suspend fun write(orderChange: Boolean = true, transform: (BagelNotebook) -> BagelNotebook): Boolean {
        if (mutable.value.saveFailed) return false
        val before = mutable.value.book ?: return false
        mutable.value = mutable.value.copy(saving = true)
        return try {
            val after = repository.update(orderChange, transform)
            if (orderChange && after.orderRevision != before.orderRevision) undo = after.orderRevision to before
            mutable.value = mutable.value.copy(book = after, saving = false, message = null, canUndo = undo != null)
            true
        } catch (e: IllegalArgumentException) {
            mutable.value = mutable.value.copy(saving = false, message = e.message)
            false
        } catch (e: Exception) {
            mutable.value = mutable.value.copy(saving = false, saveFailed = true, listening = false, message = "Not saved: ${e.message ?: "storage error"}. Retry before continuing.")
            false
        }
    }
    fun edit(id: String?) { saved["bagel.editId"] = id; mutable.value = mutable.value.copy(editId = id) }
    fun startCapture(id: String) {
        session = id
        captureBase = mutable.value.book?.orderRevision ?: 0
        val base = captureBase
        mutable.value = mutable.value.copy(listening = true, partial = "", proposal = emptyList(), message = null)
        enqueue { write(false) { it.copy(draft = "", draftBaseRevision = base, interrupted = true) } }
    }
    fun partial(id: String, text: String) {
        if (session != id) return
        mutable.value = mutable.value.copy(partial = text)
        val base = captureBase
        enqueue { if (session == id) write(false) { it.copy(draft = text, draftBaseRevision = base, interrupted = true) } }
    }
    fun finishCapture(id: String, text: String, interrupted: Boolean, message: String?) {
        if (session != id) return
        session = null
        val base = captureBase
        mutable.value = mutable.value.copy(listening = false, partial = text, message = message)
        enqueue {
            if (write(false) { it.copy(draft = text, draftBaseRevision = base, interrupted = interrupted) }) {
                val book = checkNotNull(mutable.value.book)
                if (text.isNotBlank()) organizeInternal(book)
                mutable.value = mutable.value.copy(message = message ?: if (interrupted) "Capture interrupted. Check the saved words before applying." else null)
            }
        }
    }
    fun saveTyped(text: String) = enqueue {
        write(false) { it.copy(draft = text, draftBaseRevision = it.orderRevision, interrupted = false) }
        mutable.value = mutable.value.copy(proposal = emptyList())
    }
    fun organize(text: String) = enqueue {
        if (write(false) { it.copy(draft = text, draftBaseRevision = it.orderRevision, interrupted = false) }) organizeInternal(checkNotNull(mutable.value.book))
    }
    private fun organizeInternal(book: BagelNotebook) {
        try {
            val parsed = BagelParser(mutable.value.catalog).parse(book.draft, book.nextCarNumber, book.cars)
            mutable.value = mutable.value.copy(proposal = parsed)
        } catch (e: IllegalArgumentException) { mutable.value = mutable.value.copy(proposal = emptyList(), message = e.message) }
    }
    fun discard() = enqueue {
        if (write(false) { it.copy(draft = "", interrupted = false) }) mutable.value = mutable.value.copy(proposal = emptyList(), partial = "")
    }
    fun applyBatch() = enqueue {
        val ui = mutable.value
        if (ui.proposal.isEmpty()) return@enqueue
        val book = ui.book ?: return@enqueue
        if (book.orderRevision != book.draftBaseRevision) {
            mutable.value = ui.copy(message = "Orders changed. Tap Organize again to review this batch against the latest orders.")
            return@enqueue
        }
        try {
            if (write { BagelChanges.applyBatch(it, ui.proposal, book.draftBaseRevision) }) mutable.value = mutable.value.copy(proposal = emptyList(), partial = "")
        } catch (e: IllegalArgumentException) { mutable.value = mutable.value.copy(message = e.message) }
    }
    fun changeCar(id: String, change: (BagelCar) -> BagelCar) = enqueue {
        write { b -> b.copy(cars = b.cars.map { if (it.id == id) change(it) else it }) }
    }
    fun addCar() = enqueue { write { b ->
        require(b.nextCarNumber <= 1000) { "This session has reached 1000 cars." }
        b.copy(cars = b.cars + BagelCar(number = b.nextCarNumber), nextCarNumber = b.nextCarNumber + 1)
    } }
    fun confirmSkipped() = enqueue { write { b -> b.copy(cars = b.cars.map { if (!it.entered && it.status == CarStatus.UNCAPTURED) it.copy(status = CarStatus.NONE) else it }) } }
    fun undo() = enqueue {
        val (revision, before) = undo ?: return@enqueue
        try {
            val after = repository.undo(revision, before)
            undo = null
            mutable.value = mutable.value.copy(book = after, canUndo = false, saveFailed = false, message = "Previous order change restored.")
            if (after.draft.isNotBlank()) organizeInternal(after)
        } catch (e: Exception) { mutable.value = mutable.value.copy(message = e.message) }
    }
}

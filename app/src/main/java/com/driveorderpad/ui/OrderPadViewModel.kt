package com.driveorderpad.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.driveorderpad.AppDependencies
import com.driveorderpad.domain.MenuCatalog
import com.driveorderpad.domain.Notebook
import com.driveorderpad.domain.OrderAction
import com.driveorderpad.domain.Program
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class NavigationState(
    val screen: String = "entry",
    val orderId: String? = null,
    val mealId: String? = null,
    val slotId: String? = null,
    val categoryId: String? = null,
    val returnScreen: String = "entry",
)

data class OrderPadState(
    val menu: MenuCatalog? = null,
    val notebook: Notebook? = null,
    val navigation: NavigationState = NavigationState(),
    val expanded: Set<Program> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val canUndo: Boolean = false,
    val inputResetEpoch: Int = 0,
)

class OrderPadViewModel(
    private val savedState: SavedStateHandle,
    private val loadDependencies: suspend () -> AppDependencies,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val restoredNav = runCatching { json.decodeFromString<NavigationState>(savedState["navigation"] ?: "{}") }.getOrDefault(NavigationState())
    private val _state = MutableStateFlow(OrderPadState(
        navigation = restoredNav,
        expanded = (savedState.get<ArrayList<String>>("expanded") ?: arrayListOf()).mapNotNull { key -> Program.entries.find { it.key == key } }.toSet(),
    ))
    val state = _state.asStateFlow()
    private var dependencies: AppDependencies? = null
    private var undo: Notebook? = null
    private var failureEpoch = 0
    private sealed interface Event {
        data object Load : Event
        data object Undo : Event
        data class Apply(val action: OrderAction, val destination: NavigationState? = null, val failureEpoch: Int) : Event
    }
    private val events = Channel<Event>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (event in events) {
                try {
                    when (event) {
                        Event.Load -> load()
                        Event.Undo -> restore()
                        is Event.Apply -> commit(event)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    failureEpoch += 1
                    _state.update { it.copy(loading = false, saving = false, inputResetEpoch = it.inputResetEpoch + 1, error = if (it.notebook == null) "Could not open order notes. Stored notes have been kept. Retry to open them." else "Change was not saved. ${error.message ?: "Please try again."}") }
                }
            }
        }
        retry()
    }

    fun retry() { events.trySend(Event.Load) }
    private suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        val deps = dependencies ?: loadDependencies().also { dependencies = it }
        val notes = deps.repository.load()
        _state.update { it.copy(menu = deps.menu, notebook = notes, loading = false) }
        if (!validNavigation(_state.value.navigation)) navigate(NavigationState())
    }

    private fun validNavigation(nav: NavigationState): Boolean {
        val notes = _state.value.notebook ?: return false
        if (nav.screen in listOf("entry", "queue")) return true
        val order = notes.orders.find { it.id == nav.orderId } ?: return false
        if (nav.screen == "ticket") return true
        if (nav.screen != "picker") return false
        val meal = order.meals.find { it.id == nav.mealId } ?: return false
        return _state.value.menu!!.slots(meal).any { it.id == nav.slotId }
    }

    private suspend fun commit(event: Event.Apply) {
        // Cancel buffered taps after a failed write. A new deliberate tap may retry.
        if (event.failureEpoch != failureEpoch) return
        val deps = dependencies ?: return
        _state.update { it.copy(saving = true, error = null) }
        val change = deps.repository.apply(event.action)
        if (change.before != change.after) undo = change.before
        _state.update { it.copy(notebook = change.after, saving = false, message = change.message, canUndo = undo != null) }
        event.destination?.let(::navigate)
    }

    private suspend fun restore() {
        val deps = dependencies ?: return
        val previous = undo ?: return
        _state.update { it.copy(saving = true, error = null) }
        val restored = deps.repository.undo(_state.value.notebook!!.revision, previous)
        undo = null
        _state.update { it.copy(notebook = restored, saving = false, canUndo = false, message = "Undone.", inputResetEpoch = it.inputResetEpoch + 1) }
        if (_state.value.navigation.screen == "ticket") navigate(NavigationState("ticket", restored.activeOrderId))
        else if (!validNavigation(_state.value.navigation)) navigate(NavigationState())
    }

    private fun post(action: OrderAction, destination: NavigationState? = null) { events.trySend(Event.Apply(action, destination, failureEpoch)) }
    fun apply(action: OrderAction) { post(action) }
    fun undo() { events.trySend(Event.Undo) }
    fun nextCar() {
        val id = _state.value.notebook?.activeOrderId ?: return
        post(OrderAction.NextCar(id), NavigationState())
    }
    fun editOrder(id: String) { post(OrderAction.Activate(id), NavigationState("ticket", id)) }
    fun toggle(program: Program) {
        _state.update { it.copy(expanded = if (program in it.expanded) it.expanded - program else it.expanded + program) }
        savedState["expanded"] = ArrayList(_state.value.expanded.map { it.key })
    }
    fun queue() = navigate(NavigationState(screen = "queue"))
    fun entry() = navigate(NavigationState())
    fun openSlot(orderId: String, mealId: String, slotId: String, returnScreen: String) {
        val state = _state.value
        val meal = state.notebook!!.orders.first { it.id == orderId }.meals.first { it.id == mealId }
        val menu = state.menu!!
        val choices = menu.choices(meal.program, menu.slots(meal).first { it.id == slotId })
        val category = menu.categories(choices).singleOrNull()?.id
        navigate(NavigationState("picker", orderId, mealId, slotId, category, returnScreen))
    }
    fun category(id: String?) = navigate(_state.value.navigation.copy(categoryId = id))
    fun choose(offerId: String, portionId: String?) {
        val nav = _state.value.navigation
        if (nav.screen == "picker") post(OrderAction.Select(nav.orderId!!, nav.mealId!!, nav.slotId!!, offerId, portionId), NavigationState(screen = nav.returnScreen, orderId = nav.orderId))
    }
    fun clearPicker() {
        val nav = _state.value.navigation
        post(OrderAction.Clear(nav.orderId!!, nav.mealId!!, nav.slotId!!), NavigationState(screen = nav.returnScreen, orderId = nav.orderId))
    }
    fun back() {
        val nav = _state.value.navigation
        if (nav.screen == "picker" && nav.categoryId != null) {
            val meal = _state.value.notebook!!.orders.first { it.id == nav.orderId }.meals.first { it.id == nav.mealId }
            val menu = _state.value.menu!!
            if (menu.categories(menu.choices(meal.program, menu.slots(meal).first { it.id == nav.slotId })).size > 1) { category(null); return }
        }
        navigate(if (nav.screen == "picker") NavigationState(screen = nav.returnScreen, orderId = nav.orderId) else NavigationState())
    }
    private fun navigate(nav: NavigationState) {
        savedState["navigation"] = json.encodeToString(nav)
        _state.update { it.copy(navigation = nav) }
    }
}

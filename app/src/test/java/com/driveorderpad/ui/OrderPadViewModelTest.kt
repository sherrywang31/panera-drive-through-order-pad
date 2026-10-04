package com.driveorderpad.ui

import androidx.lifecycle.SavedStateHandle
import com.driveorderpad.AppDependencies
import com.driveorderpad.data.NotebookRepository
import com.driveorderpad.domain.*
import com.driveorderpad.testMenu
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OrderPadViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val menu = testMenu()
    private val engine = OrderEngine(menu)
    private class MemoryRepository(private val engine: OrderEngine) : NotebookRepository {
        var notes = engine.initialize(Notebook())
        var fail = false
        override suspend fun load() = notes
        override suspend fun apply(action: OrderAction): Change {
            if (fail) throw java.io.IOException("Disk is full.")
            return engine.apply(notes, action).let { change ->
                notes = change.after.copy(revision = notes.revision + 1)
                change.copy(after = notes)
            }
        }
        override suspend fun undo(expectedRevision: Long, restore: Notebook): Notebook {
            check(notes.revision == expectedRevision)
            notes = restore.copy(revision = notes.revision + 1)
            return notes
        }
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun `rapid next car taps do not skip or lose the order`() = runTest(dispatcher) {
        val repo = MemoryRepository(engine)
        val model = OrderPadViewModel(SavedStateHandle()) { AppDependencies(menu, repo) }
        advanceUntilIdle()
        val s = repo.notes
        val meal = s.active.meals.first { it.program == Program.DEFAULT }
        model.apply(OrderAction.Select(s.activeOrderId, meal.id, "food", "default:food:sandwiches-bacon-turkey-bravo", "half"))
        advanceUntilIdle()
        model.nextCar(); model.nextCar(); model.nextCar()
        advanceUntilIdle()
        assertEquals(1, model.state.value.notebook!!.pending.size)
        assertEquals(2L, model.state.value.notebook!!.active.sequence)
        assertEquals(2, model.state.value.notebook!!.orders.size)
    }

    @Test fun `failed save keeps committed notes and resets numeric edit`() = runTest(dispatcher) {
        val repo = MemoryRepository(engine)
        val model = OrderPadViewModel(SavedStateHandle()) { AppDependencies(menu, repo) }
        advanceUntilIdle()
        val s = repo.notes
        val meal = s.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        model.apply(OrderAction.Select(s.activeOrderId, meal.id, "bagel-1", "bagel-tuesday:food:bagels-plain", "each"))
        advanceUntilIdle()
        val before = model.state.value.notebook
        repo.fail = true
        model.apply(OrderAction.Quantity(s.activeOrderId, meal.id, "bagel-1", 14))
        model.nextCar()
        advanceUntilIdle()
        assertEquals(before, model.state.value.notebook)
        assertTrue(model.state.value.error!!.contains("not saved"))
        assertEquals(1, model.state.value.inputResetEpoch)
        assertFalse(model.state.value.saving)
        assertEquals(s.activeOrderId, model.state.value.notebook!!.activeOrderId)
        repo.fail = false
        model.apply(OrderAction.Quantity(s.activeOrderId, meal.id, "bagel-1", 14))
        advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals(14L, model.state.value.notebook!!.active.meals.first { it.id == meal.id }.bagelTotal)
    }

    @Test fun `picker and card expansion restore from SavedStateHandle`() = runTest(dispatcher) {
        val repo = MemoryRepository(engine)
        val saved = SavedStateHandle()
        val model = OrderPadViewModel(saved) { AppDependencies(menu, repo) }
        advanceUntilIdle()
        val meal = repo.notes.active.meals.first { it.program == Program.YOU_PICK_TWO }
        model.toggle(Program.BAGEL_TUESDAY)
        model.openSlot(repo.notes.activeOrderId, meal.id, "food-1", "entry")
        model.category("sandwiches")
        val restored = OrderPadViewModel(saved) { AppDependencies(menu, repo) }
        advanceUntilIdle()
        assertEquals(model.state.value.navigation, restored.state.value.navigation)
        assertEquals(setOf(Program.BAGEL_TUESDAY), restored.state.value.expanded)
        restored.choose("you-pick-two:food:sandwiches-bacon-turkey-bravo", "half")
        advanceUntilIdle()
        assertEquals("entry", restored.state.value.navigation.screen)
        assertTrue(restored.state.value.notebook!!.active.hasItems)
    }

    @Test fun `Undo removal restores bagels and marks input for refresh`() = runTest(dispatcher) {
        val repo = MemoryRepository(engine)
        val model = OrderPadViewModel(SavedStateHandle()) { AppDependencies(menu, repo) }
        advanceUntilIdle()
        val s = repo.notes
        val m = s.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        model.apply(OrderAction.Select(s.activeOrderId, m.id, "bagel-1", "bagel-tuesday:food:bagels-plain", "each"))
        model.apply(OrderAction.Quantity(s.activeOrderId, m.id, "bagel-1", 15))
        model.apply(OrderAction.RemoveBagel(s.activeOrderId, m.id, "bagel-1"))
        advanceUntilIdle()
        assertEquals(0L, repo.notes.active.meals.first { it.id == m.id }.bagelTotal)
        model.undo()
        advanceUntilIdle()
        assertEquals(15L, repo.notes.active.meals.first { it.id == m.id }.bagelTotal)
        assertFalse(model.state.value.canUndo)
        assertEquals(1, model.state.value.inputResetEpoch)
    }
}

package com.driveorderpad.data

import com.driveorderpad.domain.*
import com.driveorderpad.testMenu
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NotebookRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val engine = OrderEngine(testMenu())

    @Test fun `orders survive closing and reopening file storage`() = runBlocking {
        val file = File(temporary.root, "notes.json")
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repository = FileNotebookRepository.create(file, engine, scope)
        val s = repository.load()
        val meal = s.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        repository.apply(OrderAction.Select(s.activeOrderId, meal.id, "bagel-1", "bagel-tuesday:food:bagels-everything", "each"))
        val captured = repository.apply(OrderAction.Quantity(s.activeOrderId, meal.id, "bagel-1", 13)).after
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = FileNotebookRepository.create(file, engine, scope).load()
            assertEquals(captured, reopened)
            assertEquals(13L, reopened.active.meals.first { it.program == Program.BAGEL_TUESDAY }.bagelTotal)
        } finally { scope.cancel() }
    }

    @Test fun `older over-limit notes reopen unchanged and can be reduced`() = runBlocking {
        val initial = engine.initialize(Notebook())
        val bagel = initial.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        val legacy = bagel.copy(slots = mapOf("bagel-1" to Selection("bagel-tuesday:food:bagels-plain", "bagels-plain", "Plain", "each", "Each", 17)))
        val notes = initial.copy(orders = listOf(initial.active.copy(meals = initial.active.meals.map { if (it.id == bagel.id) legacy else it })))
        val file = File(temporary.root, "legacy.json")
        file.outputStream().use { NotebookSerializer.writeTo(notes, it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = FileNotebookRepository.create(file, engine, scope)
            assertEquals(notes, repository.load())
            val after = repository.apply(OrderAction.StepBagel(notes.activeOrderId, bagel.id, "bagels-plain", -1)).after
            assertEquals(16L, after.active.meals.first { it.id == bagel.id }.bagelTotal)
        } finally { scope.cancel() }
    }

    @Test fun `concurrent writes to different slots do not lose items`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = FileNotebookRepository.create(File(temporary.root, "notes.json"), engine, scope)
            val state = repository.load()
            val meal = state.active.meals.first { it.program == Program.MIX_MATCH }
            val offers = engine.menu.choices(meal.program, engine.menu.slots(meal).first())
            (1..10).map { i -> launch(Dispatchers.Default) {
                repository.apply(OrderAction.Select(state.activeOrderId, meal.id, "item-$i", offers[i - 1].id, offers[i - 1].allowedPortionIds.single()))
            } }.joinAll()
            val loaded = repository.load()
            assertEquals(10, loaded.active.meals.first { it.id == meal.id }.slots.values.count { it != null })
            assertEquals(10L, loaded.revision)
        } finally { scope.cancel() }
    }

    @Test fun `Undo is revision checked and restores row removal`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repository = FileNotebookRepository.create(File(temporary.root, "notes.json"), engine, scope)
            val initial = repository.load()
            val meal = initial.active.meals.first { it.program == Program.BAGEL_TUESDAY }
            repository.apply(OrderAction.Select(initial.activeOrderId, meal.id, "bagel-1", "bagel-tuesday:food:bagels-plain", "each"))
            val removed = repository.apply(OrderAction.RemoveBagel(initial.activeOrderId, meal.id, "bagel-1"))
            val restored = repository.undo(removed.after.revision, removed.before)
            assertEquals(1L, restored.active.meals.first { it.id == meal.id }.bagelTotal)
            try { repository.undo(removed.after.revision, removed.before); fail("Stale Undo was accepted") } catch (_: IllegalStateException) { }
        } finally { scope.cancel() }
    }

    @Test fun `bad data raises an error and original bytes are not erased`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = File(temporary.root, "notes.json").apply { writeText("{broken order notes") }
        try {
            val repository = FileNotebookRepository.create(file, engine, scope)
            try { repository.load(); fail("Corrupt notes were silently reset") } catch (_: androidx.datastore.core.CorruptionException) { }
            assertEquals("{broken order notes", file.readText())
        } finally { scope.cancel() }
    }

    @Test fun `serializer round trip and unsupported version fail safely`() = runBlocking {
        val notebook = engine.initialize(Notebook())
        val output = ByteArrayOutputStream()
        NotebookSerializer.writeTo(notebook, output)
        assertEquals(notebook, NotebookSerializer.readFrom(ByteArrayInputStream(output.toByteArray())))
        assertThrows(IllegalArgumentException::class.java) { engine.initialize(notebook.copy(schemaVersion = 999)) }
        Unit
    }

    @Test fun `missing required storage fields are treated as corruption`() = runBlocking {
        try { NotebookSerializer.readFrom(ByteArrayInputStream("{}".encodeToByteArray())); fail("Incomplete notes were accepted") }
        catch (_: androidx.datastore.core.CorruptionException) { }
    }

    @Test fun `schema one migration is persisted once and multiple dozens survive reopening and Undo`() = runBlocking {
        val fresh = engine.initialize(Notebook())
        val oldPrograms = setOf(Program.DEFAULT, Program.YOU_PICK_TWO, Program.BAGEL_TUESDAY, Program.MIX_MATCH)
        val old = fresh.copy(schemaVersion = 1, revision = 4, orders = listOf(fresh.active.copy(meals = fresh.active.meals.filter { it.program in oldPrograms })))
        val file = File(temporary.root, "upgrade.json")
        file.outputStream().use { NotebookSerializer.writeTo(old, it) }
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val repo = FileNotebookRepository.create(file, engine, scope)
        val upgraded = repo.load()
        assertEquals(2, upgraded.schemaVersion)
        assertEquals(5L, upgraded.revision)
        assertEquals(upgraded, repo.load())
        val first = upgraded.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        repeat(13) { repo.apply(OrderAction.StepCount(upgraded.activeOrderId, first.id, "bagel-tuesday:food:bagels-plain", "each", 1)) }
        val created = repo.apply(OrderAction.Another(upgraded.activeOrderId, Program.BAGEL_TUESDAY)).after
        val second = created.active.meals.last { it.program == Program.BAGEL_TUESDAY }
        repeat(4) { repo.apply(OrderAction.StepCount(upgraded.activeOrderId, second.id, "bagel-tuesday:food:bagels-everything", "each", 1)) }
        val removal = repo.apply(OrderAction.RemoveDozen(upgraded.activeOrderId, second.id))
        val restored = repo.undo(removal.after.revision, removal.before)
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = FileNotebookRepository.create(file, engine, scope).load()
            assertEquals(restored, reopened)
            assertEquals(listOf(13L, 4L), reopened.active.meals.filter { it.program == Program.BAGEL_TUESDAY }.map { it.bagelTotal })
        } finally { scope.cancel() }
    }
    @Test fun `Undo of dozen creation never reuses its ID for a later target`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val repo = FileNotebookRepository.create(File(temporary.root, "ids.json"), engine, scope)
            val s = repo.load()
            val added = repo.apply(OrderAction.Another(s.activeOrderId, Program.BAGEL_TUESDAY))
            val removedId = added.after.active.meals.last { it.program == Program.BAGEL_TUESDAY }.id
            repo.undo(added.after.revision, added.before)
            val next = repo.apply(OrderAction.Another(s.activeOrderId, Program.BAGEL_TUESDAY)).after
            assertNotEquals(removedId, next.active.meals.last { it.program == Program.BAGEL_TUESDAY }.id)
            try {
                repo.apply(OrderAction.StepCount(s.activeOrderId, removedId, "bagel-tuesday:food:bagels-plain", "each", 1))
                fail("A stale tap wrote into a new target")
            } catch (_: IllegalArgumentException) { }
        } finally { scope.cancel() }
    }

}

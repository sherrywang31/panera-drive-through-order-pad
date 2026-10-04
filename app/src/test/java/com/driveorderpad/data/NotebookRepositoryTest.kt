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
        val captured = repository.apply(OrderAction.Quantity(s.activeOrderId, meal.id, "bagel-1", 17)).after
        scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val reopened = FileNotebookRepository.create(file, engine, scope).load()
            assertEquals(captured, reopened)
            assertEquals(17L, reopened.active.meals.first { it.program == Program.BAGEL_TUESDAY }.bagelTotal)
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
}

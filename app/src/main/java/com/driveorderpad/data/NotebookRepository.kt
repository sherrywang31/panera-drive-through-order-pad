package com.driveorderpad.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.driveorderpad.domain.Change
import com.driveorderpad.domain.Notebook
import com.driveorderpad.domain.OrderAction
import com.driveorderpad.domain.OrderEngine
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

interface NotebookRepository {
    suspend fun load(): Notebook
    suspend fun apply(action: OrderAction): Change
    suspend fun undo(expectedRevision: Long, restore: Notebook): Notebook
}

object NotebookSerializer : Serializer<Notebook> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override val defaultValue = Notebook()
    override suspend fun readFrom(input: InputStream): Notebook = try {
        val content = input.readBytes().decodeToString()
        val fields = kotlinx.serialization.json.JsonObject.serializer().let { json.decodeFromString(it, content) }
        if (!fields.keys.containsAll(setOf("schemaVersion", "revision", "nextSequence", "activeOrderId", "orders"))) {
            throw CorruptionException("Order notes are incomplete. The original file has been kept.")
        }
        json.decodeFromString<Notebook>(content)
    } catch (error: SerializationException) {
        // Do not silently reset corrupted order notes; let the UI offer retry.
        throw CorruptionException("Order notes could not be read. The original file has been kept.", error)
    }
    override suspend fun writeTo(t: Notebook, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

/** One application-scoped DataStore. Every mutation is an atomic read/modify/write. */
class FileNotebookRepository(
    private val store: DataStore<Notebook>,
    private val engine: OrderEngine,
) : NotebookRepository {
    override suspend fun load(): Notebook {
        return store.updateData { current ->
            val initialized = engine.initialize(current)
            // Initial creation starts at revision zero; upgrades are atomic and happen once.
            if (current.orders.isNotEmpty() && initialized != current) initialized.copy(revision = current.revision + 1) else initialized
        }
    }

    override suspend fun apply(action: OrderAction): Change {
        var committed: Change? = null
        store.updateData { current ->
            val change = engine.apply(current, action)
            val after = if (change.after == current) current else change.after.copy(revision = current.revision + 1)
            committed = change.copy(after = after)
            after
        }
        return checkNotNull(committed)
    }

    override suspend fun undo(expectedRevision: Long, restore: Notebook): Notebook = store.updateData { current ->
        check(current.revision == expectedRevision) { "Notes changed since that action. Undo is no longer available." }
        engine.validate(restore)
        // Undo restores notes, while allocation counters stay monotonic to reject stale UI taps.
        restore.copy(revision = current.revision + 1, nextSequence = maxOf(current.nextSequence, restore.nextSequence),
            orders = restore.orders.map { order -> order.copy(nextMealNumber = maxOf(order.nextMealNumber, current.orders.firstOrNull { it.id == order.id }?.nextMealNumber ?: order.nextMealNumber)) })
    }

    companion object {
        fun create(file: File, engine: OrderEngine, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) =
            FileNotebookRepository(DataStoreFactory.create(serializer = NotebookSerializer, scope = scope, produceFile = { file }), engine)
    }
}

package com.driveorderpad.bagels

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

object BagelSerializer : Serializer<BagelNotebook> {
    private val json = Json { encodeDefaults = true }
    override val defaultValue = BagelNotebook()
    override suspend fun readFrom(input: InputStream): BagelNotebook = try {
        val text = input.readBytes().decodeToString()
        val fields = json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
        require(fields != null && fields.keys.containsAll(setOf("schemaVersion", "revision", "orderRevision", "nextCarNumber", "cars", "draft"))) { "Incomplete bagel notes." }
        json.decodeFromString<BagelNotebook>(text).also(BagelChanges::validate)
    } catch (error: Exception) {
        throw CorruptionException("Bagel notes could not be read. Original notes have been kept.", error)
    }
    override suspend fun writeTo(t: BagelNotebook, output: OutputStream) { output.write(json.encodeToString(t).encodeToByteArray()) }
}
class BagelRepository(private val store: DataStore<BagelNotebook>) {
    suspend fun load(): BagelNotebook = store.data.first()
    suspend fun update(orderChange: Boolean = true, transform: (BagelNotebook) -> BagelNotebook): BagelNotebook = store.updateData { old ->
        val next = transform(old)
        BagelChanges.validate(next)
        if (next == old) old else next.copy(revision = old.revision + 1, orderRevision = old.orderRevision + if (orderChange) 1 else 0)
    }
    suspend fun undo(expectedOrderRevision: Long, restore: BagelNotebook): BagelNotebook = update { old ->
        check(old.orderRevision == expectedOrderRevision) { "Orders changed; Undo is no longer available." }
        old.copy(cars = restore.cars, nextCarNumber = maxOf(old.nextCarNumber, restore.nextCarNumber), draft = restore.draft, draftBaseRevision = old.orderRevision + 1, interrupted = restore.interrupted)
    }
    companion object {
        fun create(file: File, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) = BagelRepository(DataStoreFactory.create(serializer = BagelSerializer, scope = scope, produceFile = { file }))
    }
}

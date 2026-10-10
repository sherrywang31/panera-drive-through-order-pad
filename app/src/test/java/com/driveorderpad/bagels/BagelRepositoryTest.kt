package com.driveorderpad.bagels

import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BagelRepositoryTest {
    @Test fun batchDraftAndOrdersReopenAndUndoDoesNotReuseNumbers() = runTest {
        val file=File.createTempFile("bagel-test", ".json").apply { delete() }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val repo=BagelRepository.create(file,scope)
        val initial=repo.load()
        val draft=repo.update(false) { it.copy(draft="Car 1 13 sesame", interrupted=true) }
        assertEquals(0L,draft.orderRevision)
        val batch=BagelParser(BagelCatalog.fallback).parse(draft.draft,1)
        val saved=repo.update { BagelChanges.applyBatch(it,batch,0) }
        assertEquals(1L,saved.orderRevision)
        val undone=repo.undo(saved.orderRevision,initial)
        assertTrue(undone.cars.isEmpty());assertEquals(2,undone.nextCarNumber)
        scope.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.join()
        val secondScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val second=BagelRepository.create(file,secondScope).load()
        assertEquals(undone,second)
        secondScope.cancel();secondScope.coroutineContext[kotlinx.coroutines.Job]?.join();file.delete()
    }
    @Test fun corruptNotesAreNeverReset() = runTest {
        val input=ByteArrayInputStream("{\"cars\":[]}".toByteArray())
        try { BagelSerializer.readFrom(input);fail() } catch (_: androidx.datastore.core.CorruptionException) { }
    }
    @Test fun splitPrepKeepsTotalAndSizeChoicesPersist() = runTest {
        val book=BagelNotebook(cars=listOf(BagelCar(number=1,status=CarStatus.ORDER,groups=listOf(BagelGroup(bagels=listOf(BagelLine(flavor="Sesame",quantity=13)),spreads=listOf(SpreadLine(flavor="Plain Cream Cheese",quantity=2,size=SpreadSize.TUB)))))))
        val out=java.io.ByteArrayOutputStream();BagelSerializer.writeTo(book,out)
        assertEquals(book,BagelSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}

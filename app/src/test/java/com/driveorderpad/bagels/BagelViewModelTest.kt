package com.driveorderpad.bagels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BagelViewModelTest {
    private suspend fun await(test: () -> Boolean) { withTimeout(10000) { while (!test()) delay(10) } }
    @Test fun lateBatchCannotOverwriteManualEditsAndCanBeReorganized() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val file=File.createTempFile("bagel-vm", ".json").apply { delete() }
        val repo=BagelRepository.create(file,scope)
        val store=ViewModelStore()
        try {
            repo.update { BagelChanges.applyBatch(it,BagelParser(BagelCatalog.fallback).parse("Car 1 2 sesame",1),0) }
            val vm=BagelViewModel(SavedStateHandle(),repo) { BagelCatalog.fallback }
            store.put("test",vm)
            await { !vm.state.value.loading }
            val original=vm.state.value.book!!.cars.single()
            vm.startCapture("speech-1")
            vm.changeCar(original.id) { c -> c.copy(groups=c.groups.map { g -> g.copy(bagels=g.bagels.map { it.copy(quantity=3) }) }) }
            vm.finishCapture("speech-1","Car 2 5 chocolate chip",false,null)
            await { vm.state.value.proposal.isNotEmpty() }
            vm.applyBatch()
            await { vm.state.value.message?.contains("Orders changed") == true }
            assertEquals(3,vm.state.value.book!!.cars.single().count)
            vm.organize(vm.state.value.book!!.draft)
            await { vm.state.value.book!!.draftBaseRevision==vm.state.value.book!!.orderRevision }
            vm.applyBatch()
            await { vm.state.value.book!!.cars.size==2 }
            assertEquals(3,vm.state.value.book!!.cars.first().count)
            assertEquals(5,vm.state.value.book!!.cars.last().count)
            vm.partial("speech-1","Car 99 no bagels")
            assertEquals("",vm.state.value.book!!.draft)
        } finally { store.clear();scope.cancel();scope.coroutineContext[Job]?.join();file.delete();Dispatchers.resetMain() }
    }
}

package com.driveorderpad.bagels

import org.junit.Assert.*
import org.junit.Test

class BagelParserTest {
    private val parser = BagelParser(BagelCatalog.fallback)
    private val example = "For car 1, 8 Cinnamon Swirl & Raisin slice and toast, 5 Chocolate Chip, 3 Asiago, Plain Cream Cheese and Honey Walnut. Car 3 13 Sesame tub of plain and honey walnut. Car 4 no bagel."
    @Test fun userExamplePreservesOrdersAndUncertainty() {
        val batch = parser.parse(example, 1)
        assertEquals(listOf(1, 3, 4), batch.map { it.number })
        val first = batch[0].groups.single()
        assertEquals(16, first.count)
        assertEquals(Slice.SLICED, first.bagels[0].slice)
        assertEquals(Toast.TOASTED, first.bagels[0].toast)
        assertEquals(Slice.UNKNOWN, first.bagels[1].slice)
        assertEquals(BagelKind.UNKNOWN, first.kind)
        assertNull(first.spreads[0].quantity)
        assertEquals(SpreadSize.UNKNOWN, first.spreads[0].size)
        assertEquals(13, batch[1].count)
        assertEquals(SpreadSize.TUB, batch[1].groups.single().spreads.first().size)
        assertEquals(CarStatus.NONE, batch[2].status)
        val book = BagelChanges.applyBatch(BagelNotebook(), batch, 0)
        assertEquals(CarStatus.UNCAPTURED, book.cars.first { it.number == 2 }.status)
        assertEquals(5, book.nextCarNumber)
    }
    @Test fun explicitDozenAndBothSpreadCounts() {
        val car = parser.parse("Car two baker’s dozen 13 sesame unsliced double toasted, 1 tub of plain cream cheese and 1 tub of honey walnut.", 1).single()
        val group = car.groups.single()
        assertEquals(2, car.number)
        assertEquals(BagelKind.DOZEN, group.kind)
        assertEquals(13, group.count)
        assertEquals(Slice.UNSLICED, group.bagels.single().slice)
        assertEquals(Toast.DOUBLE, group.bagels.single().toast)
        assertTrue(group.spreads.all { it.quantity == 1 && it.size == SpreadSize.TUB })
        assertTrue(car.reviews().toString(), car.reviews().isEmpty())
    }
    @Test fun noToastAndNoSliceNeverBecomePositivePrep() {
        val line = parser.parse("Car 1 2 plain not sliced not toasted",1).single().groups.single().bagels.single()
        assertEquals(Slice.UNSLICED, line.slice); assertEquals(Toast.UNTOASTED, line.toast)
    }
    @Test fun unknownFlavorAndModifiersRemainHeard() {
        val car = parser.parse("Car 1 2 blueberry extra crunchy plus 3 jalapeno",1).single()
        assertEquals(2, car.count)
        assertTrue(car.unresolved.any { "jalapeno" in it && "extra crunchy" in it })
    }
    @Test fun excessDozenIsNeverTruncated() {
        val car = parser.parse("Car 1 baker's dozen 16 sesame",1).single()
        assertEquals(16, car.count); assertTrue(car.reviews().any { "16 bagels" in it })
    }
    @Test fun repeatedFlavorWithDifferentPrepRemainsSeparate() {
        val lines = parser.parse("Car 1 2 sesame sliced toasted and 3 sesame unsliced untoasted",1).single().groups.single().bagels
        assertEquals(2, lines.size); assertEquals(5, lines.sumOf { it.quantity!! }); assertEquals(Slice.UNSLICED, lines[1].slice)
    }
    @Test fun explicitCorrectionSetsInsteadOfAdds() {
        val original = parser.parse("Car 1 5 chocolate chip",1).single()
        val changed = parser.parse("Car 1 make chocolate chip four",2,listOf(original)).single()
        assertEquals(original.id, changed.id); assertEquals(4, changed.count)
        val added = parser.parse("Car 1 add 3 chocolate chip",2,listOf(original)).single()
        assertEquals(8, added.count)
        assertEquals(0, parser.parse("Car 1 remove chocolate chip",2,listOf(original)).single().count)
    }
    @Test fun ambiguousCorrectionDoesNotOverwrite() {
        val original = parser.parse("Car 1 2 sesame toasted and 3 sesame untoasted",1).single()
        val changed = parser.parse("Car 1 make sesame 4",2,listOf(original)).single()
        assertEquals(original.groups, changed.groups); assertTrue(changed.unresolved.any { "Correction" in it })
    }
    @Test fun repeatedCarMentionsDoNotLoseEarlierItems() {
        val car = parser.parse("Car 1 2 sesame. Car 1 3 chocolate chip.",1).single()
        assertEquals(5, car.count); assertTrue(car.unresolved.isNotEmpty())
    }
    @Test fun oldEnteredCarCannotBeOverwritten() {
        val car = parser.parse("Car 1 2 sesame",1).single().copy(entered = true)
        try { BagelChanges.applyBatch(BagelNotebook(cars=listOf(car), nextCarNumber=2), parser.parse("Car 1 no bagels",2),0); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun staleBatchCannotOverwriteManualEdit() {
        try { BagelChanges.applyBatch(BagelNotebook(orderRevision=2),parser.parse("Car 1 no bagels",1),1); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun invalidCountsAreUnresolvedNotDropped() {
        val car=parser.parse("Car 1 1000 sesame",1).single()
        assertNull(car.groups.single().bagels.single().quantity); assertTrue(car.unresolved.any { "range" in it })
    }
    @Test fun plainBagelsAreDistinctFromPlainSpread() {
        val group = parser.parse("Car 1 3 plain sliced toasted with 2 singles of plain cream cheese",1).single().groups.single()
        assertEquals(3, group.count); assertEquals(2, group.spreads.single().quantity); assertEquals(SpreadSize.SINGLE,group.spreads.single().size)
    }
}

package com.driveorderpad.domain

import com.driveorderpad.testMenu
import org.junit.Assert.*
import org.junit.Test

class OrderEngineTest {
    private val menu = testMenu()
    private val engine = OrderEngine(menu)
    private fun blank() = engine.initialize(Notebook())
    private fun meal(state: Notebook, program: Program) = state.active.meals.last { it.program == program }
    private fun select(state: Notebook, program: Program, slot: String, item: String, portion: String? = null): Notebook {
        val m = meal(state, program)
        val definition = menu.slots(m).first { it.id == slot }
        val offer = menu.choices(program, definition).first { menu.items.getValue(it.itemId).label == item }
        return engine.apply(state, OrderAction.Select(state.activeOrderId, m.id, slot, offer.id, portion)).after
    }

    @Test fun `canonical catalog has all items and every enabled offer can be selected`() {
        assertEquals(249, menu.items.size)
        menu.data.offers.filter { it.demoEnabled }.forEach { offer ->
            val state = blank()
            val m = meal(state, Program.from(offer.programId))
            val slot = menu.slots(m).first { it.role == offer.role }
            offer.allowedPortionIds.forEach { portion ->
                val result = engine.apply(state, OrderAction.Select(state.activeOrderId, m.id, slot.id, offer.id, portion)).after
                assertEquals(portion, result.active.meals.first { it.id == m.id }.slots.getValue(slot.id)!!.portionId)
            }
        }
    }

    @Test fun `You Pick Two never accepts a full sandwich or an offer from Default`() {
        val s = blank(); val m = meal(s, Program.YOU_PICK_TWO)
        val ypt = "you-pick-two:food:sandwiches-bacon-turkey-bravo"
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Select(s.activeOrderId, m.id, "food-1", ypt, "whole")) }
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Select(s.activeOrderId, m.id, "food-1", "default:food:sandwiches-bacon-turkey-bravo", "half")) }
        val result = select(s, Program.YOU_PICK_TWO, "food-1", "Bacon Turkey Bravo")
        assertEquals("half", meal(result, Program.YOU_PICK_TWO).slots["food-1"]!!.portionId)
    }

    @Test fun `both side pickers offer only Apple Chips Baguette`() {
        listOf(Program.DEFAULT, Program.YOU_PICK_TWO).forEach { program ->
            val slot = menu.card(program).slots.first { it.role == "side" }
            assertEquals(setOf("Apple", "Chips", "Baguette"), menu.choices(program, slot).map { menu.items.getValue(it.itemId).label }.toSet())
        }
        assertEquals(10, menu.choices(Program.MIX_MATCH, menu.card(Program.MIX_MATCH).slots.first()).size)
        assertEquals(10, menu.card(Program.MIX_MATCH).slots.size)
    }

    @Test fun `unknown size and partial combos stay in queue`() {
        var s = select(blank(), Program.DEFAULT, "food", "Bacon Turkey Bravo")
        assertNull(meal(s, Program.DEFAULT).slots["food"]!!.portionId)
        assertTrue(menu.review(meal(s, Program.DEFAULT)).contains("Confirm the size."))
        s = select(s, Program.DEFAULT, "side", "Chips")
        val original = s.active.id
        s = engine.apply(s, OrderAction.NextCar(original)).after
        assertEquals(1, s.pending.size)
        assertFalse(s.active.hasItems)
        assertNull(s.pending.single().meals.first { it.program == Program.DEFAULT }.slots["drink"])
    }

    @Test fun `rapid bagel taps stop at thirteen and subtract one without becoming negative`() {
        var s = blank()
        val m = meal(s, Program.BAGEL_TUESDAY)
        repeat(20) { s = engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-everything", 1)).after }
        assertEquals(13L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(13L, meal(s, Program.BAGEL_TUESDAY).bagelQuantity("bagels-everything"))
        assertEquals(s, engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-plain", 1)).after)
        assertThrows(IllegalArgumentException::class.java) { select(s, Program.BAGEL_TUESDAY, menu.slots(meal(s, Program.BAGEL_TUESDAY)).last().id, "Plain") }
        s = engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-everything", -1)).after
        s = engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-plain", 1)).after
        assertEquals(13L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        repeat(20) { s = engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-everything", -1)).after }
        assertEquals(1L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(0L, meal(s, Program.BAGEL_TUESDAY).bagelQuantity("bagels-everything"))
        assertTrue(meal(s, Program.BAGEL_TUESDAY).slots.values.any { it == null })
    }

    @Test fun `legacy duplicate and over-limit bagel notes are preserved and removable without overflow`() {
        var s = select(blank(), Program.BAGEL_TUESDAY, "bagel-1", "Everything")
        val m = meal(s, Program.BAGEL_TUESDAY)
        val line = m.slots["bagel-1"]!!
        val legacy = m.copy(slots = mapOf("bagel-1" to line.copy(quantity = Int.MAX_VALUE), "bagel-2" to line.copy(quantity = Int.MAX_VALUE)))
        s = s.copy(orders = listOf(s.active.copy(meals = s.active.meals.map { if (it.id == m.id) legacy else it })))
        engine.validate(s)
        assertTrue(meal(s, Program.BAGEL_TUESDAY).bagelTotal > Int.MAX_VALUE)
        assertEquals(s, engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-everything", 1)).after)
        val reduced = engine.apply(s, OrderAction.StepBagel(s.activeOrderId, m.id, "bagels-everything", -1)).after
        assertEquals(legacy.bagelTotal - 1, meal(reduced, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(legacy.bagelTotal - 1, meal(reduced, Program.BAGEL_TUESDAY).bagelQuantity("bagels-everything"))
        assertEquals(1, menu.bagelSelections(meal(reduced, Program.BAGEL_TUESDAY)).size)
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Quantity(s.activeOrderId, m.id, "bagel-1", 0)) }
    }

    @Test fun `repeated meals preserve earlier items and next car is idempotent`() {
        var s = select(blank(), Program.YOU_PICK_TWO, "food-1", "Bacon Turkey Bravo")
        s = select(s, Program.YOU_PICK_TWO, "side", "Chips")
        val old = s.active.id
        s = engine.apply(s, OrderAction.Another(old, Program.YOU_PICK_TWO)).after
        assertEquals(2, s.active.meals.count { it.program == Program.YOU_PICK_TWO })
        assertTrue(s.active.meals.first { it.program == Program.YOU_PICK_TWO }.hasItems)
        val next = engine.apply(s, OrderAction.NextCar(old)).after
        assertEquals(next, engine.apply(next, OrderAction.NextCar(old)).after)
        assertEquals(1, next.pending.size)
        assertNotEquals(old, next.active.id)
    }

    @Test fun `mark entered removes the correct car and Undo snapshot is intact`() {
        var s = select(blank(), Program.DEFAULT, "food", "Grilled Cheese", "half")
        s = select(s, Program.DEFAULT, "side", "Chips")
        val a = s.active.id
        s = engine.apply(s, OrderAction.NextCar(a)).after
        s = select(s, Program.DEFAULT, "food", "Tuna Salad", "whole")
        val b = s.active.id
        val change = engine.apply(s, OrderAction.Entered(a))
        assertEquals(listOf(b), change.after.pending.map { it.id })
        assertEquals(listOf(a, b), change.before.pending.map { it.id })
        assertEquals(change.after, engine.apply(change.after, OrderAction.Entered(a)).after)
    }

    @Test fun `saved labels survive removal from menu`() {
        val selection = Selection("old-offer", "old-item", "Seasonal sandwich", "half", "Half")
        val m = engine.blankMeal(Program.DEFAULT, "old-meal").copy(slots = mapOf("food" to selection, "drink" to null, "side" to null))
        assertEquals("Half · Seasonal sandwich", menu.label(selection))
        assertTrue(menu.review(m).any { it.contains("Menu choice changed") })
    }

    @Test fun `required sides gate every applicable default category and all You Pick Two meals`() {
        val categories = setOf("sandwiches", "soups-mac", "salads", "market-bowls", "kids", "stuffers")
        categories.forEach { category ->
            val offer = menu.choices(Program.DEFAULT, menu.card(Program.DEFAULT).slots.first()).first { menu.items.getValue(it.itemId).categoryId == category }
            var s = select(blank(), Program.DEFAULT, "food", menu.items.getValue(offer.itemId).label, offer.allowedPortionIds.first())
            assertTrue(category, menu.requiresSide(meal(s, Program.DEFAULT)))
            assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.NextCar(s.activeOrderId)) }
            assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Entered(s.activeOrderId)) }
            s = select(s, Program.DEFAULT, "side", "Baguette")
            assertEquals(1, engine.apply(s, OrderAction.NextCar(s.activeOrderId)).after.pending.size)
        }
        var s = select(blank(), Program.YOU_PICK_TWO, "food-1", "Bacon Turkey Bravo")
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.NextCar(s.activeOrderId)) }
        s = select(s, Program.YOU_PICK_TWO, "side", "Apple")
        assertEquals(1, engine.apply(s, OrderAction.NextCar(s.activeOrderId)).after.pending.size)
        val bakery = menu.choices(Program.DEFAULT, menu.card(Program.DEFAULT).slots.first()).first { menu.items.getValue(it.itemId).categoryId == "bakery" }
        val pastry = select(blank(), Program.DEFAULT, "food", menu.items.getValue(bakery.itemId).label)
        assertFalse(menu.requiresSide(meal(pastry, Program.DEFAULT)))
        assertEquals(1, engine.apply(pastry, OrderAction.NextCar(pastry.activeOrderId)).after.pending.size)
    }

    @Test fun `all hot coffee and tea offers expose sixteen and twenty ounce sizes`() {
        val drinks = menu.items.values.filter { it.categoryId == "hot-coffee-tea" }
        drinks.forEach { item ->
            assertEquals(item.label, listOf("16-oz", "20-oz"), item.portionIds)
            menu.offers.values.filter { it.itemId == item.id }.forEach { assertEquals(item.label, item.portionIds, it.allowedPortionIds) }
        }
    }

    @Test fun `car positions start at zero and renumber without changing identity`() {
        var s = blank()
        assertEquals(0, s.carNumber(s.activeOrderId))
        repeat(3) {
            s = select(s, Program.BAGEL_TUESDAY, "bagel-1", "Plain")
            s = engine.apply(s, OrderAction.NextCar(s.activeOrderId)).after
        }
        val ids = s.pending.map { it.id }
        assertEquals(listOf(0, 1, 2), ids.map { s.carNumber(it) })
        assertEquals(3, s.carNumber(s.activeOrderId))
        val after = engine.apply(s, OrderAction.Entered(ids[1])).after
        assertEquals(listOf(ids[0], ids[2]), after.pending.map { it.id })
        assertEquals(listOf(0, 1), after.pending.map { after.carNumber(it.id) })
        assertEquals(2, after.carNumber(after.activeOrderId))
        assertEquals(s.orders.first { it.id == ids[2] }.sequence, after.orders.first { it.id == ids[2] }.sequence)
    }
}

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
        val original = s.active.id
        s = engine.apply(s, OrderAction.NextCar(original)).after
        assertEquals(1, s.pending.size)
        assertFalse(s.active.hasItems)
        assertNull(s.pending.single().meals.first { it.program == Program.DEFAULT }.slots["drink"])
    }

    @Test fun `bagel target is advisory rows remain removable and flavor replacement retains quantity`() {
        var s = select(blank(), Program.BAGEL_TUESDAY, "bagel-1", "Plain")
        val m = meal(s, Program.BAGEL_TUESDAY)
        s = engine.apply(s, OrderAction.Quantity(s.activeOrderId, m.id, "bagel-1", 14)).after
        assertEquals(14L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        assertTrue(menu.review(meal(s, Program.BAGEL_TUESDAY)).any { it.contains("over") })
        s = select(s, Program.BAGEL_TUESDAY, "bagel-1", "Everything")
        assertEquals(14, meal(s, Program.BAGEL_TUESDAY).slots["bagel-1"]!!.quantity)
        s = select(s, Program.BAGEL_TUESDAY, "bagel-2", "Sesame")
        assertEquals(15L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        s = engine.apply(s, OrderAction.RemoveBagel(s.activeOrderId, m.id, "bagel-1")).after
        assertFalse(meal(s, Program.BAGEL_TUESDAY).slots.containsKey("bagel-1"))
        assertEquals(1L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        s = engine.apply(s, OrderAction.RemoveBagel(s.activeOrderId, m.id, "bagel-2")).after
        assertFalse(meal(s, Program.BAGEL_TUESDAY).hasItems)
        assertEquals(1, meal(s, Program.BAGEL_TUESDAY).slots.size)
    }

    @Test fun `bagels support more than thirteen rows and totals do not overflow Int`() {
        var s = blank()
        repeat(15) { i -> s = select(s, Program.BAGEL_TUESDAY, "bagel-${i + 1}", "Everything") }
        val m = meal(s, Program.BAGEL_TUESDAY)
        repeat(2) { i -> s = engine.apply(s, OrderAction.Quantity(s.activeOrderId, m.id, "bagel-${i + 1}", Int.MAX_VALUE)).after }
        assertTrue(meal(s, Program.BAGEL_TUESDAY).bagelTotal > Int.MAX_VALUE)
        assertEquals(16, meal(s, Program.BAGEL_TUESDAY).slots.size)
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Quantity(s.activeOrderId, m.id, "bagel-1", 0)) }
    }

    @Test fun `repeated meals preserve earlier items and next car is idempotent`() {
        var s = select(blank(), Program.YOU_PICK_TWO, "food-1", "Bacon Turkey Bravo")
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
}

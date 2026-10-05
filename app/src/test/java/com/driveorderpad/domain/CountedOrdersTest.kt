package com.driveorderpad.domain

import com.driveorderpad.testMenu
import org.junit.Assert.*
import org.junit.Test

class CountedOrdersTest {
    private val menu = testMenu()
    private val engine = OrderEngine(menu)
    private fun meal(s: Notebook, p: Program) = s.active.meals.last { it.program == p }
    private fun step(s: Notebook, p: Program, item: String, portion: String = "each", delta: Int = 1, mealId: String = meal(s, p).id): Notebook {
        val offer = menu.data.offers.first { it.programId == p.key && it.itemId == item }
        return engine.apply(s, OrderAction.StepCount(s.activeOrderId, mealId, offer.id, portion, delta)).after
    }

    @Test fun `all hot drinks require eligible sizes and no size drinks have the requested roster`() {
        val bottled = listOf("Bottled Passionfruit Papaya Tea", "Bottled Water", "Bottled Orange Juice", "Kids Apple Juice", "Kids Chocolate Milk", "Kids White Milk")
        listOf(Program.DEFAULT, Program.YOU_PICK_TWO, Program.DRINKS).forEach { p ->
            val offers = menu.choices(p, menu.card(p).slots.first { it.role == "drink" })
            assertEquals(bottled, offers.filter { menu.items.getValue(it.itemId).categoryId == "bottled-canned" }.map { menu.items.getValue(it.itemId).label })
            val frozen = offers.filter { menu.items.getValue(it.itemId).categoryId == "frozen-smoothies" }
            assertEquals(10, frozen.size)
            (frozen + offers.filter { menu.noSize(it.itemId) }).forEach { assertEquals(listOf("each"), it.allowedPortionIds) }
            offers.filter { menu.items.getValue(it.itemId).categoryId == "hot-coffee-tea" }.forEach { assertEquals(listOf("16-oz", "20-oz"), it.allowedPortionIds) }
        }
        val s = engine.initialize(Notebook())
        val m = meal(s, Program.DEFAULT)
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Select(s.activeOrderId, m.id, "drink", "default:drink:hot-coffee-tea-dark-roast-coffee", null)) }
        assertThrows(IllegalArgumentException::class.java) { engine.apply(s, OrderAction.Select(s.activeOrderId, m.id, "drink", "default:drink:hot-coffee-tea-espresso", "2-oz")) }
        val selected = engine.apply(s, OrderAction.Select(s.activeOrderId, m.id, "drink", "default:drink:bottled-canned-premium-oj", null)).after
        assertEquals("each", meal(selected, Program.DEFAULT).slots["drink"]!!.portionId)
        assertEquals("Bottled Orange Juice", menu.label(meal(selected, Program.DEFAULT).slots["drink"]!!))
    }

    @Test fun `You Pick Two soup offers cup and bowl while mac and sandwiches retain their portions`() {
        val offers = menu.choices(Program.YOU_PICK_TWO, menu.card(Program.YOU_PICK_TWO).slots.first())
        offers.filter { menu.items.getValue(it.itemId).categoryId == "soups-mac" }.forEach {
            assertEquals(if (menu.items.getValue(it.itemId).label.contains("Mac & Cheese")) listOf("cup") else listOf("cup", "bowl"), it.allowedPortionIds)
        }
        offers.filter { menu.items.getValue(it.itemId).categoryId == "sandwiches" }.forEach { assertEquals(listOf("half"), it.allowedPortionIds) }
    }

    @Test fun `individual breakfast drinks and cream cheese counts are independent and never negative`() {
        var s = engine.initialize(Notebook())
        repeat(18) { s = step(s, Program.INDIVIDUAL_BAGELS, "bagels-plain") }
        repeat(4) { s = step(s, Program.BREAKFAST, "breakfast-bacon-egg-cheese-ciabatta") }
        repeat(3) { s = step(s, Program.DRINKS, "hot-coffee-tea-dark-roast-coffee", "20-oz") }
        s = step(s, Program.DRINKS, "hot-coffee-tea-dark-roast-coffee", "16-oz")
        s = step(s, Program.CREAM_CHEESE, "spreads-plain-cream-cheese", "1-5-oz")
        s = step(s, Program.CREAM_CHEESE, "spreads-plain-cream-cheese", "8-oz")
        assertEquals(18L, meal(s, Program.INDIVIDUAL_BAGELS).bagelTotal)
        assertEquals(0L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(4L, meal(s, Program.BREAKFAST).bagelTotal)
        assertEquals(3L, meal(s, Program.DRINKS).quantity("hot-coffee-tea-dark-roast-coffee", "20-oz"))
        assertTrue(menu.readback(meal(s, Program.CREAM_CHEESE)).contains("Single · Plain Cream Cheese"))
        assertTrue(menu.readback(meal(s, Program.CREAM_CHEESE)).contains("Tub · Plain Cream Cheese"))
        repeat(25) { s = step(s, Program.INDIVIDUAL_BAGELS, "bagels-plain", delta = -1) }
        assertEquals(0L, meal(s, Program.INDIVIDUAL_BAGELS).bagelTotal)
        assertEquals(2L, meal(s, Program.CREAM_CHEESE).bagelTotal)
        assertThrows(IllegalArgumentException::class.java) { step(s, Program.DRINKS, "hot-coffee-tea-dark-roast-coffee", "each") }
    }

    @Test fun `multiple dozens have independent caps and deletion never reuses a stable ID`() {
        var s = engine.initialize(Notebook())
        val first = meal(s, Program.BAGEL_TUESDAY).id
        repeat(20) { s = step(s, Program.BAGEL_TUESDAY, "bagels-plain", mealId = first) }
        s = engine.apply(s, OrderAction.Another(s.activeOrderId, Program.BAGEL_TUESDAY)).after
        val second = meal(s, Program.BAGEL_TUESDAY).id
        repeat(15) { s = step(s, Program.BAGEL_TUESDAY, "bagels-everything", mealId = second) }
        assertEquals(listOf(13L, 13L), s.active.meals.filter { it.program == Program.BAGEL_TUESDAY }.map { it.bagelTotal })
        s = step(s, Program.BAGEL_TUESDAY, "bagels-plain", delta = -1, mealId = first)
        assertEquals(13L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(12L, s.active.meals.first { it.id == first }.bagelTotal)
        val removed = engine.apply(s, OrderAction.RemoveDozen(s.activeOrderId, second))
        assertTrue(removed.before.active.meals.any { it.id == second })
        s = engine.apply(removed.after, OrderAction.Another(s.activeOrderId, Program.BAGEL_TUESDAY)).after
        assertNotEquals(second, meal(s, Program.BAGEL_TUESDAY).id)
        assertEquals(0L, meal(s, Program.BAGEL_TUESDAY).bagelTotal)
        // Blank dozens can be created deliberately, and remain distinct selectable targets.
        s = engine.apply(s, OrderAction.Another(s.activeOrderId, Program.BAGEL_TUESDAY)).after
        assertEquals(3, s.active.meals.count { it.program == Program.BAGEL_TUESDAY })
    }

    @Test fun `old notebook upgrades without losing notes legacy hot sizes or duplicate bagels`() {
        val fresh = engine.initialize(Notebook())
        val oldPrograms = setOf(Program.DEFAULT, Program.YOU_PICK_TWO, Program.BAGEL_TUESDAY, Program.MIX_MATCH)
        val old = fresh.copy(schemaVersion = 1, revision = 7, orders = listOf(fresh.active.copy(meals = fresh.active.meals.filter { it.program in oldPrograms }.map { m ->
            when (m.program) {
                Program.DEFAULT -> m.copy(slots = m.slots + ("drink" to Selection("default:drink:hot-coffee-tea-espresso", "hot-coffee-tea-espresso", "Espresso", "2-oz", "2 oz")))
                Program.YOU_PICK_TWO -> m.copy(slots = m.slots + ("drink" to Selection("you-pick-two:drink:bottled-canned-premium-oj", "bottled-canned-premium-oj", "Premium OJ", "11-5-oz-bottle", "11.5 oz bottle")))
                Program.BAGEL_TUESDAY -> m.copy(slots = mapOf("bagel-1" to Selection("bagel-tuesday:food:bagels-plain", "bagels-plain", "Plain", "each", "Each", 8), "bagel-2" to Selection("bagel-tuesday:food:bagels-plain", "bagels-plain", "Plain", "each", "Each", 9)))
                else -> m
            }
        })))
        val migrated = engine.initialize(old)
        assertEquals(2, migrated.schemaVersion)
        assertEquals(old.activeOrderId, migrated.activeOrderId)
        assertEquals(7L, migrated.revision)
        assertEquals("2-oz", meal(migrated, Program.DEFAULT).slots["drink"]!!.portionId)
        assertEquals("each", meal(migrated, Program.YOU_PICK_TWO).slots["drink"]!!.portionId)
        assertEquals("Premium OJ", meal(migrated, Program.YOU_PICK_TWO).slots["drink"]!!.itemLabel)
        assertEquals(17L, meal(migrated, Program.BAGEL_TUESDAY).bagelTotal)
        assertEquals(migrated, engine.initialize(migrated))
        val reduced = engine.apply(migrated, OrderAction.StepBagel(migrated.activeOrderId, meal(migrated, Program.BAGEL_TUESDAY).id, "bagels-plain", -1)).after
        assertEquals(16L, meal(reduced, Program.BAGEL_TUESDAY).bagelTotal)
    }
}

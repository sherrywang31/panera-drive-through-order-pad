package com.driveorderpad.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MenuData(
    val catalogVersion: String,
    val categories: List<MenuCategory>,
    val portions: List<MenuPortion>,
    val items: List<MenuItem>,
    val offers: List<MenuOffer>,
    val cards: List<CardDefinition>,
)

@Serializable data class MenuCategory(val id: String, val label: String, val sortOrder: Int)
@Serializable data class MenuPortion(val id: String, val label: String)
@Serializable data class MenuItem(val id: String, val label: String, val categoryId: String, val portionIds: List<String>, val requiresSide: Boolean = false)
@Serializable data class MenuOffer(
    val id: String,
    val programId: String,
    val itemId: String,
    val role: String,
    val allowedPortionIds: List<String>,
    val demoEnabled: Boolean,
)
@Serializable data class SlotDefinition(val id: String, val role: String, val label: String, val optional: Boolean)
@Serializable data class CardDefinition(
    val id: String,
    val label: String,
    val slots: List<SlotDefinition>,
    val collapsible: Boolean,
    val initiallyExpanded: Boolean,
    val repeatMeal: Boolean,
)

enum class Program(val key: String) {
    DRINKS("drinks"), BREAKFAST("breakfast"), INDIVIDUAL_BAGELS("individual-bagels"),
    BAGEL_TUESDAY("bagel-tuesday"), CREAM_CHEESE("cream-cheese"),
    DEFAULT("default"), YOU_PICK_TWO("you-pick-two"), MIX_MATCH("mix-match");
    val counted: Boolean get() = this in setOf(DRINKS, BREAKFAST, INDIVIDUAL_BAGELS, BAGEL_TUESDAY, CREAM_CHEESE)
    val section: Program get() = if (this in setOf(INDIVIDUAL_BAGELS, CREAM_CHEESE)) BAGEL_TUESDAY else this
    companion object { fun from(key: String) = entries.first { it.key == key } }
}

/** Canonical items and explicit program offers are different concepts. */
class MenuCatalog(val data: MenuData) {
    val items = data.items.associateBy { it.id }
    val offers = data.offers.associateBy { it.id }
    val portions = data.portions.associateBy { it.id }
    val cards = data.cards.associateBy { it.id }

    init {
        require(items.size == data.items.size && offers.size == data.offers.size)
        require(cards.keys == Program.entries.map { it.key }.toSet())
        require(data.items.all { item -> data.categories.any { it.id == item.categoryId } })
        require(data.offers.all { offer ->
            offer.programId in cards && offer.itemId in items && offer.allowedPortionIds.isNotEmpty() &&
                offer.allowedPortionIds.all { it in portions && it in items.getValue(offer.itemId).portionIds }
        })
    }

    fun card(program: Program) = cards.getValue(program.key)
    fun choices(program: Program, slot: SlotDefinition): List<MenuOffer> = data.offers.filter {
        it.demoEnabled && it.programId == program.key && it.role == slot.role
    }
    fun categories(offers: List<MenuOffer>) = data.categories.sortedBy { it.sortOrder }.filter { category ->
        offers.any { items.getValue(it.itemId).categoryId == category.id }
    }
    fun portionLabel(id: String?) = portions[id]?.label ?: "Size?"
    fun slots(meal: Meal): List<SlotDefinition> = if (meal.program.counted) {
        meal.slots.keys.sortedBy { it.substringAfterLast('-').toLong() }.map {
            SlotDefinition(it, card(meal.program).slots.first().role, "Item ${it.substringAfterLast('-')}", true)
        }
    } else card(meal.program).slots

    fun noSize(itemId: String) = items[itemId]?.categoryId in setOf("bottled-canned", "frozen-smoothies")
    fun capturePortionLabel(program: Program, id: String) = if (program == Program.CREAM_CHEESE) {
        if (id == "1-5-oz") "Single" else if (id == "8-oz") "Tub" else portionLabel(id)
    } else portionLabel(id)

    fun mealTitle(order: CarOrder, meal: Meal): String {
        val label = card(meal.program).label
        return if (card(meal.program).repeatMeal) "$label ${order.meals.filter { it.program == meal.program }.indexOfFirst { it.id == meal.id } + 1}" else label
    }

    fun readback(meal: Meal): String = if (meal.program.counted) {
        meal.slots.values.filterNotNull().groupBy { it.itemId to it.portionId }.values.joinToString(" · ") { lines ->
            "${lines.sumOf { it.quantity.toLong() }} × ${label(lines.first().copy(quantity = 1))}"
        }
    } else slots(meal).mapNotNull { slot -> meal.slots[slot.id]?.let {
        "${if (slot.role == "food") "" else slot.label + ": "}${label(it)}"
    } }.joinToString(" · ")

    fun label(line: Selection, bagels: Boolean = false): String = buildString {
        if (bagels || line.quantity > 1) append("${line.quantity} × ")
        if (line.portionId != "each") append("${line.portionLabel ?: "Size?"} · ")
        append(line.itemLabel)
    }

    fun requiresSide(meal: Meal): Boolean = when (meal.program) {
        Program.YOU_PICK_TWO -> meal.hasItems
        Program.DEFAULT -> slots(meal).filter { it.role == "food" }.mapNotNull { meal.slots[it.id] }
            .any { items[it.itemId]?.requiresSide ?: true }
        else -> false
    }

    fun missingSides(order: CarOrder) = order.meals.filter { requiresSide(it) && it.slots["side"] == null }

    /** Representative labels for each flavor; bagelQuantity supplies the aggregated Long count. */
    fun bagelSelections(meal: Meal): List<Selection> = meal.slots.values.filterNotNull().distinctBy { it.itemId }

    fun review(meal: Meal): List<String> {
        if (!meal.hasItems) return emptyList()
        val foods = slots(meal).filter { it.role == "food" }.mapNotNull { meal.slots[it.id] }
        return buildList {
            if (meal.program == Program.DEFAULT && foods.isEmpty()) add("Food still needed.")
            if (meal.program == Program.YOU_PICK_TWO && foods.size < 2) add("Two foods needed for You Pick Two.")
            if (meal.program == Program.MIX_MATCH) {
                if (foods.size < 2) add("At least two foods needed.")
                if (foods.map { it.itemId }.distinct().size < foods.size) add("Repeated Mix & Match item: confirm at register.")
                add("Confirm Mix & Match side at register.")
            }
            if (meal.slots.values.filterNotNull().any { it.portionId == null }) add("Confirm the size.")
            if (requiresSide(meal) && meal.slots["side"] == null) add("Required side still needed.")
            if (meal.slots.values.filterNotNull().any { offers[it.offerId]?.demoEnabled != true }) add("Menu choice changed: confirm at register.")
            if (meal.slots.values.filterNotNull().any { line -> line.portionId != null && offers[line.offerId]?.let { line.portionId !in it.allowedPortionIds } == true }) add("Saved size is no longer offered: confirm at register.")
            if (meal.program == Program.BAGEL_TUESDAY) {
                when {
                    meal.bagelTotal > 13 -> add("${meal.bagelTotal - 13} over the 13-bagel limit. Remove bagels before adding more.")
                    meal.bagelTotal < 13 -> add("${13 - meal.bagelTotal} more to reach 13 bagels.")
                }
            }
        }
    }

    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String) = MenuCatalog(json.decodeFromString<MenuData>(text))
    }
}

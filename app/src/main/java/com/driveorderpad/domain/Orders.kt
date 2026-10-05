package com.driveorderpad.domain

import kotlinx.serialization.Serializable

@Serializable enum class OrderStatus { DRAFT, QUEUED }

/** Display snapshots preserve a heard request when a later menu revision removes it. */
@Serializable data class Selection(
    val offerId: String,
    val itemId: String,
    val itemLabel: String,
    val portionId: String?,
    val portionLabel: String?,
    val quantity: Int = 1,
)
@Serializable data class Meal(val id: String, val program: Program, val slots: Map<String, Selection?>) {
    val hasItems: Boolean get() = slots.values.any { it != null }
    val bagelTotal: Long get() = slots.values.filterNotNull().sumOf { it.quantity.toLong() }
    fun bagelQuantity(itemId: String): Long = slots.values.filterNotNull().filter { it.itemId == itemId }.sumOf { it.quantity.toLong() }
    fun quantity(itemId: String, portionId: String): Long = slots.values.filterNotNull().filter { it.itemId == itemId && it.portionId == portionId }.sumOf { it.quantity.toLong() }
}
@Serializable data class CarOrder(
    val id: String,
    val sequence: Long,
    val status: OrderStatus = OrderStatus.DRAFT,
    val menuVersion: String,
    val meals: List<Meal>,
    val nextMealNumber: Long = 2,
) { val hasItems: Boolean get() = meals.any { it.hasItems } }

@Serializable data class Notebook(
    val schemaVersion: Int = 2,
    val revision: Long = 0,
    val nextSequence: Long = 1,
    val activeOrderId: String = "",
    val orders: List<CarOrder> = emptyList(),
) {
    val active: CarOrder get() = orders.first { it.id == activeOrderId }
    val pending: List<CarOrder> get() = orders.filter { it.hasItems }.sortedBy { it.sequence }
    /** Queue positions are presentation only; IDs and allocation sequences never change. */
    fun carNumber(orderId: String): Int = orders.filter { it.hasItems || it.id == activeOrderId }.sortedBy { it.sequence }.indexOfFirst { it.id == orderId }
}

sealed interface OrderAction {
    data class Select(val orderId: String, val mealId: String, val slotId: String, val offerId: String, val portionId: String?) : OrderAction
    data class SetPortion(val orderId: String, val mealId: String, val slotId: String, val portionId: String) : OrderAction
    data class Quantity(val orderId: String, val mealId: String, val slotId: String, val quantity: Int) : OrderAction
    data class Clear(val orderId: String, val mealId: String, val slotId: String) : OrderAction
    data class RemoveBagel(val orderId: String, val mealId: String, val slotId: String) : OrderAction
    data class StepBagel(val orderId: String, val mealId: String, val itemId: String, val delta: Int) : OrderAction
    data class StepCount(val orderId: String, val mealId: String, val offerId: String, val portionId: String, val delta: Int) : OrderAction
    data class RemoveDozen(val orderId: String, val mealId: String) : OrderAction
    data class Another(val orderId: String, val program: Program) : OrderAction
    data class NextCar(val orderId: String) : OrderAction
    data class Entered(val orderId: String) : OrderAction
    data class Activate(val orderId: String) : OrderAction
}

data class Change(val before: Notebook, val after: Notebook, val message: String)

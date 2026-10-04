package com.driveorderpad.domain

/** Pure business rules: no Android, storage or UI dependencies. */
class OrderEngine(val menu: MenuCatalog) {
    fun blankMeal(program: Program, id: String) = Meal(id, program, menu.card(program).slots.associate { it.id to null })
    private fun blankOrder(sequence: Long) = CarOrder(
        "car-$sequence", sequence, menuVersion = menu.data.catalogVersion,
        meals = Program.entries.map { blankMeal(it, "meal-$sequence-${it.key}-1") },
    )

    fun initialize(notebook: Notebook): Notebook {
        require(notebook.schemaVersion == 1) { "Unsupported order storage version. Notes have been kept." }
        if (notebook.orders.isNotEmpty()) {
            validate(notebook)
            return notebook
        }
        val order = blankOrder(notebook.nextSequence)
        return notebook.copy(orders = listOf(order), activeOrderId = order.id, nextSequence = order.sequence + 1)
    }

    fun validate(notebook: Notebook) {
        require(notebook.schemaVersion == 1)
        require(notebook.orders.isNotEmpty() && notebook.orders.any { it.id == notebook.activeOrderId })
        require(notebook.orders.map { it.id }.distinct().size == notebook.orders.size)
        require(notebook.orders.map { it.sequence }.distinct().size == notebook.orders.size)
        require(notebook.nextSequence > notebook.orders.maxOf { it.sequence })
        notebook.orders.forEach { order ->
            require(order.sequence > 0)
            require(order.meals.map { it.id }.distinct().size == order.meals.size)
            require(Program.entries.all { program -> order.meals.any { it.program == program } })
            require(order.meals.count { it.program == Program.BAGEL_TUESDAY } == 1)
            order.meals.forEach { meal ->
                if (meal.program == Program.BAGEL_TUESDAY) {
                    require(meal.slots.isNotEmpty() && meal.slots.keys.all { it.matches(Regex("bagel-[1-9][0-9]*")) && it.substringAfter("bagel-").toLongOrNull() != null })
                } else require(meal.slots.keys == menu.card(meal.program).slots.map { it.id }.toSet())
                require(meal.slots.values.filterNotNull().all { it.quantity > 0 && (meal.program == Program.BAGEL_TUESDAY || it.quantity == 1) })
            }
        }
    }

    private fun ensureBlank(meal: Meal): Meal {
        if (meal.program != Program.BAGEL_TUESDAY || meal.slots.values.any { it == null }) return meal
        val next = meal.slots.keys.maxOf { it.substringAfter("bagel-").toLong() } + 1
        return meal.copy(slots = meal.slots + ("bagel-$next" to null))
    }

    private fun edit(state: Notebook, orderId: String, mealId: String, update: (Meal) -> Meal): Notebook {
        val order = state.orders.firstOrNull { it.id == orderId } ?: error("This car is no longer in the queue.")
        require(order.meals.any { it.id == mealId }) { "This meal is no longer available." }
        return state.copy(orders = state.orders.map { car ->
            if (car.id != orderId) car else car.copy(meals = car.meals.map { if (it.id == mealId) ensureBlank(update(it)) else it })
        })
    }

    private fun requireSlot(meal: Meal, slotId: String) = menu.slots(meal).firstOrNull { it.id == slotId }
        ?: error("This row is no longer available.")

    fun apply(state: Notebook, action: OrderAction): Change {
        var message = "Saved."
        val result = when (action) {
            is OrderAction.Select -> edit(state, action.orderId, action.mealId) { meal ->
                val slot = requireSlot(meal, action.slotId)
                val offer = menu.offers[action.offerId] ?: error("Item unavailable.")
                require(offer in menu.choices(meal.program, slot)) { "Item is not eligible for this meal." }
                require(action.portionId == null || action.portionId in offer.allowedPortionIds) { "Size is not eligible for this meal." }
                val portion = action.portionId ?: offer.allowedPortionIds.singleOrNull()
                val item = menu.items.getValue(offer.itemId)
                val quantity = if (meal.program == Program.BAGEL_TUESDAY) meal.slots[action.slotId]?.quantity ?: 1 else 1
                meal.copy(slots = meal.slots + (action.slotId to Selection(offer.id, item.id, item.label, portion, portion?.let { menu.portionLabel(it) }, quantity)))
            }
            is OrderAction.SetPortion -> edit(state, action.orderId, action.mealId) { meal ->
                requireSlot(meal, action.slotId)
                val line = meal.slots[action.slotId] ?: error("Choose an item first.")
                val offer = menu.offers[line.offerId] ?: error("Item unavailable.")
                require(action.portionId in offer.allowedPortionIds) { "Size is not eligible for this meal." }
                meal.copy(slots = meal.slots + (action.slotId to line.copy(portionId = action.portionId, portionLabel = menu.portionLabel(action.portionId))))
            }
            is OrderAction.Quantity -> edit(state, action.orderId, action.mealId) { meal ->
                require(meal.program == Program.BAGEL_TUESDAY && action.quantity > 0) { "Use a positive whole number." }
                requireSlot(meal, action.slotId)
                val line = meal.slots[action.slotId] ?: error("Choose a bagel first.")
                meal.copy(slots = meal.slots + (action.slotId to line.copy(quantity = action.quantity)))
            }
            is OrderAction.Clear -> edit(state, action.orderId, action.mealId) { meal ->
                requireSlot(meal, action.slotId)
                message = "Selection cleared."
                meal.copy(slots = meal.slots + (action.slotId to null))
            }
            is OrderAction.RemoveBagel -> edit(state, action.orderId, action.mealId) { meal ->
                require(meal.program == Program.BAGEL_TUESDAY)
                requireSlot(meal, action.slotId)
                message = "Bagel row removed."
                val remaining = meal.slots - action.slotId
                meal.copy(slots = remaining.ifEmpty { mapOf("bagel-1" to null) })
            }
            is OrderAction.Another -> {
                require(menu.card(action.program).repeatMeal) { "This card allows one bundle per car." }
                val order = state.orders.first { it.id == action.orderId }
                val meals = order.meals.filter { it.program == action.program }
                if (!meals.last().hasItems) state else {
                    message = "Another ${menu.card(action.program).label} meal started."
                    val meal = blankMeal(action.program, "meal-${order.sequence}-${action.program.key}-${meals.size + 1}")
                    state.copy(orders = state.orders.map { if (it.id == order.id) it.copy(meals = it.meals + meal) else it })
                }
            }
            is OrderAction.NextCar -> {
                // Stable source ID makes a double tap harmless after the first transition.
                if (state.activeOrderId != action.orderId || !state.active.hasItems) state else {
                    message = "Car ${state.active.sequence} saved."
                    val queued = state.copy(orders = state.orders.map { if (it.id == action.orderId) it.copy(status = OrderStatus.QUEUED) else it })
                    activateEmpty(queued)
                }
            }
            is OrderAction.Entered -> {
                val car = state.orders.firstOrNull { it.id == action.orderId }
                if (car == null || !car.hasItems) state else {
                    message = "Car ${car.sequence} entered at register."
                    val remaining = state.copy(orders = state.orders.filterNot { it.id == action.orderId })
                    if (remaining.orders.none { it.id == remaining.activeOrderId }) activateEmpty(remaining) else remaining
                }
            }
            is OrderAction.Activate -> {
                require(state.orders.any { it.id == action.orderId })
                state.copy(activeOrderId = action.orderId)
            }
        }
        validate(result)
        return Change(state, result, message)
    }

    private fun activateEmpty(state: Notebook): Notebook {
        val empty = state.orders.firstOrNull { !it.hasItems && it.status == OrderStatus.DRAFT }
        if (empty != null) return state.copy(activeOrderId = empty.id)
        val order = blankOrder(state.nextSequence)
        return state.copy(orders = state.orders + order, activeOrderId = order.id, nextSequence = state.nextSequence + 1)
    }
}

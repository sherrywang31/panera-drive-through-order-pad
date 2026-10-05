package com.driveorderpad.domain

/** Pure business rules: no Android, storage or UI dependencies. */
class OrderEngine(val menu: MenuCatalog) {
    fun blankMeal(program: Program, id: String) = Meal(id, program, menu.card(program).slots.associate { it.id to null })
    private fun blankOrder(sequence: Long) = CarOrder(
        "car-$sequence", sequence, menuVersion = menu.data.catalogVersion,
        meals = Program.entries.map { blankMeal(it, "meal-$sequence-${it.key}-1") },
    )

    fun initialize(notebook: Notebook): Notebook {
        require(notebook.schemaVersion in 1..2) { "Unsupported order storage version. Notes have been kept." }
        if (notebook.orders.isNotEmpty()) {
            // Add new capture groups without replacing old meals, stable IDs, quantities or label snapshots.
            validate(notebook, legacy = notebook.schemaVersion == 1)
            val upgraded = notebook.copy(schemaVersion = 2, orders = notebook.orders.map { order ->
                val meals = order.meals.map { meal -> meal.copy(slots = meal.slots.mapValues { (_, line) ->
                    if (line != null && menu.noSize(line.itemId)) line.copy(portionId = "each", portionLabel = menu.portionLabel("each")) else line
                }) } + Program.entries.filter { program -> order.meals.none { it.program == program } }
                    .map { blankMeal(it, "meal-${order.sequence}-${it.key}-1") }
                order.copy(meals = meals, nextMealNumber = maxOf(order.nextMealNumber, meals.maxOf { it.id.substringAfterLast('-').toLongOrNull() ?: 1 } + 1))
            })
            validate(upgraded)
            return upgraded
        }
        val order = blankOrder(notebook.nextSequence)
        return notebook.copy(schemaVersion = 2, orders = listOf(order), activeOrderId = order.id, nextSequence = order.sequence + 1)
    }

    fun validate(notebook: Notebook, legacy: Boolean = false) {
        require(notebook.schemaVersion == 2 || legacy && notebook.schemaVersion == 1)
        require(notebook.orders.isNotEmpty() && notebook.orders.any { it.id == notebook.activeOrderId })
        require(notebook.orders.map { it.id }.distinct().size == notebook.orders.size)
        require(notebook.orders.map { it.sequence }.distinct().size == notebook.orders.size)
        require(notebook.nextSequence > notebook.orders.maxOf { it.sequence })
        notebook.orders.forEach { order ->
            require(order.sequence > 0)
            require(order.meals.map { it.id }.distinct().size == order.meals.size)
            val required = if (legacy) listOf(Program.DEFAULT, Program.YOU_PICK_TWO, Program.BAGEL_TUESDAY, Program.MIX_MATCH) else Program.entries
            require(required.all { program -> order.meals.any { it.program == program } })
            require(order.nextMealNumber > 0)
            if (!legacy) require(order.meals.filterNot { menu.card(it.program).repeatMeal }.groupBy { it.program }.all { it.value.size == 1 })
            order.meals.forEach { meal ->
                if (meal.program.counted) {
                    val prefix = if (meal.program == Program.BAGEL_TUESDAY) "bagel" else "count"
                    require(meal.slots.isNotEmpty() && meal.slots.keys.all { it.matches(Regex("$prefix-[1-9][0-9]*")) && it.substringAfterLast('-').toLongOrNull() != null })
                } else require(meal.slots.keys == menu.card(meal.program).slots.map { it.id }.toSet())
                require(meal.slots.values.filterNotNull().all { it.quantity > 0 && (meal.program.counted || it.quantity == 1) })
            }
        }
    }

    private fun ensureBlank(meal: Meal): Meal {
        if (!meal.program.counted || meal.slots.values.any { it == null }) return meal
        val next = (meal.slots.keys.maxOfOrNull { it.substringAfterLast('-').toLong() } ?: 0) + 1
        val prefix = if (meal.program == Program.BAGEL_TUESDAY) "bagel" else "count"
        return meal.copy(slots = meal.slots + ("$prefix-$next" to null))
    }

    private fun edit(state: Notebook, orderId: String, mealId: String, update: (Meal) -> Meal): Notebook {
        val order = state.orders.firstOrNull { it.id == orderId } ?: error("This car is no longer in the queue.")
        require(order.meals.any { it.id == mealId }) { "This meal is no longer available." }
        return state.copy(orders = state.orders.map { car ->
            if (car.id != orderId) car else car.copy(meals = car.meals.map {
                if (it.id != mealId) it else update(it).let { updated -> if (updated == it) it else ensureBlank(updated) }
            })
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
                require(slot.role != "drink" || portion != null) { "Choose a drink size before adding it." }
                val item = menu.items.getValue(offer.itemId)
                val quantity = if (meal.program.counted) meal.slots[action.slotId]?.quantity ?: 1 else 1
                require(meal.program != Program.BAGEL_TUESDAY || meal.slots[action.slotId] != null || meal.bagelTotal < 13) { "13 bagels selected. Remove one before adding more." }
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
                val total = meal.bagelTotal - line.quantity + action.quantity
                require(total <= 13 || total < meal.bagelTotal) { "13 bagels selected. Remove one before adding more." }
                meal.copy(slots = meal.slots + (action.slotId to line.copy(quantity = action.quantity)))
            }
            is OrderAction.StepBagel -> edit(state, action.orderId, action.mealId) { meal ->
                require(meal.program == Program.BAGEL_TUESDAY && action.delta in listOf(-1, 1))
                val matches = meal.slots.entries.filter { it.value?.itemId == action.itemId }
                when {
                    action.delta > 0 && meal.bagelTotal >= 13 -> { message = "13 bagels selected. Remove one before adding more."; meal }
                    action.delta < 0 && matches.isEmpty() -> meal
                    action.delta < 0 -> {
                        val (slot, line) = matches.last()
                        message = "One ${line!!.itemLabel} removed."
                        meal.copy(slots = if (line.quantity > 1) meal.slots + (slot to line.copy(quantity = line.quantity - 1)) else meal.slots - slot)
                    }
                    else -> {
                        val offer = menu.choices(meal.program, menu.slots(meal).first()).firstOrNull { it.itemId == action.itemId }
                            ?: error("This bagel is no longer available.")
                        val item = menu.items.getValue(offer.itemId)
                        message = "One ${item.label} added."
                        if (matches.isNotEmpty()) {
                            val (slot, line) = matches.first()
                            meal.copy(slots = meal.slots + (slot to line!!.copy(quantity = line.quantity + 1)))
                        } else {
                            val available = ensureBlank(meal)
                            val slot = available.slots.entries.first { it.value == null }.key
                            available.copy(slots = available.slots + (slot to Selection(offer.id, item.id, item.label, "each", menu.portionLabel("each"))))
                        }
                    }
                }
            }
            is OrderAction.StepCount -> edit(state, action.orderId, action.mealId) { meal ->
                require(meal.program.counted && action.delta in listOf(-1, 1))
                val matches = meal.slots.entries.filter { it.value?.offerId == action.offerId && it.value?.portionId == action.portionId }
                when {
                    action.delta < 0 && matches.isEmpty() -> meal
                    action.delta < 0 -> {
                        val (slot, line) = matches.last()
                        message = "One ${line!!.itemLabel} removed."
                        meal.copy(slots = if (line.quantity > 1) meal.slots + (slot to line.copy(quantity = line.quantity - 1)) else (meal.slots - slot).ifEmpty { blankMeal(meal.program, meal.id).slots })
                    }
                    meal.program == Program.BAGEL_TUESDAY && meal.bagelTotal >= 13 -> { message = "13 bagels selected. Remove one before adding more."; meal }
                    else -> {
                        val offer = menu.offers[action.offerId] ?: error("Item unavailable.")
                        require(offer in menu.choices(meal.program, menu.slots(meal).first())) { "Item unavailable for this box." }
                        require(action.portionId in offer.allowedPortionIds) { "Choose an eligible size before adding." }
                        val item = menu.items.getValue(offer.itemId)
                        message = "One ${item.label} added."
                        if (matches.isNotEmpty()) {
                            val (slot, line) = matches.first()
                            require(line!!.quantity < Int.MAX_VALUE) { "Quantity limit reached." }
                            meal.copy(slots = meal.slots + (slot to line.copy(quantity = line.quantity + 1)))
                        } else {
                            val available = ensureBlank(meal)
                            val slot = available.slots.entries.first { it.value == null }.key
                            available.copy(slots = available.slots + (slot to Selection(offer.id, item.id, item.label, action.portionId, menu.capturePortionLabel(meal.program, action.portionId))))
                        }
                    }
                }
            }
            is OrderAction.RemoveDozen -> {
                val order = state.orders.first { it.id == action.orderId }
                val meal = order.meals.first { it.id == action.mealId }
                require(meal.program == Program.BAGEL_TUESDAY)
                val only = order.meals.count { it.program == Program.BAGEL_TUESDAY } == 1
                message = if (only) "Dozen cleared." else "Dozen removed."
                state.copy(orders = state.orders.map { if (it.id != order.id) it else it.copy(meals = if (only) it.meals.map { m -> if (m.id == meal.id) blankMeal(m.program, m.id) else m } else it.meals.filterNot { m -> m.id == meal.id }) })
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
                if (!meals.last().hasItems && action.program != Program.BAGEL_TUESDAY) state else {
                    message = "Another ${menu.card(action.program).label} meal started."
                    val meal = blankMeal(action.program, "meal-${order.sequence}-${action.program.key}-${order.nextMealNumber}")
                    state.copy(orders = state.orders.map { if (it.id == order.id) it.copy(meals = it.meals + meal, nextMealNumber = it.nextMealNumber + 1) else it })
                }
            }
            is OrderAction.NextCar -> {
                // Stable source ID makes a double tap harmless after the first transition.
                if (state.activeOrderId != action.orderId || !state.active.hasItems) state else {
                    require(menu.missingSides(state.active).isEmpty()) { "Choose a side for each required meal before Next car." }
                    message = "Car ${state.carNumber(state.active.id)} saved."
                    val queued = state.copy(orders = state.orders.map { if (it.id == action.orderId) it.copy(status = OrderStatus.QUEUED) else it })
                    activateEmpty(queued)
                }
            }
            is OrderAction.Entered -> {
                val car = state.orders.firstOrNull { it.id == action.orderId }
                if (car == null || !car.hasItems) state else {
                    require(menu.missingSides(car).isEmpty()) { "Choose the required sides before marking this car entered." }
                    message = "Car ${state.carNumber(car.id)} entered at register."
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

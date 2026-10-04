package com.driveorderpad.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driveorderpad.R
import com.driveorderpad.domain.CarOrder
import com.driveorderpad.domain.Meal
import com.driveorderpad.domain.MenuCatalog
import com.driveorderpad.domain.MenuOffer
import com.driveorderpad.domain.OrderAction
import com.driveorderpad.domain.Program
import com.driveorderpad.domain.Selection
import com.driveorderpad.domain.SlotDefinition

/** Stateless screens receive state and callbacks; the lifecycle owner lives at the app boundary. */
data class OrderPadCallbacks(
    val apply: (OrderAction) -> Unit,
    val nextCar: () -> Unit,
    val undo: () -> Unit,
    val retry: () -> Unit,
    val entry: () -> Unit,
    val queue: () -> Unit,
    val back: () -> Unit,
    val edit: (String) -> Unit,
    val toggle: (Program) -> Unit,
    val openSlot: (String, String, String, String) -> Unit,
    val category: (String?) -> Unit,
    val choose: (String, String?) -> Unit,
    val clear: () -> Unit,
)

@Composable
fun OrderPadApp(viewModel: OrderPadViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val callbacks = remember(viewModel) { OrderPadCallbacks(
        viewModel::apply, viewModel::nextCar, viewModel::undo, viewModel::retry,
        viewModel::entry, viewModel::queue, viewModel::back, viewModel::editOrder,
        viewModel::toggle, viewModel::openSlot, viewModel::category, viewModel::choose, viewModel::clearPicker,
    ) }
    BackHandler(enabled = state.navigation.screen != "entry") { viewModel.back() }
    OrderPadContent(state, callbacks)
}

@Composable
fun OrderPadContent(state: OrderPadState, actions: OrderPadCallbacks) {
    val focus = LocalFocusManager.current
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().testTag("app-surface"),
        topBar = { Header(state, actions) },
        bottomBar = {
            if (state.notebook != null && state.navigation.screen in listOf("entry", "ticket")) {
                Surface(modifier = Modifier.navigationBarsPadding()) {
                    Button(
                        onClick = { focus.clearFocus(); actions.nextCar() },
                        enabled = state.notebook.active.hasItems && !state.saving,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 56.dp).testTag("next-car"),
                    ) { Text(stringResource(R.string.next_car)) }
                }
            }
        },
    ) { insets ->
        Box(Modifier.padding(insets).fillMaxSize()) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else if (state.notebook == null || state.menu == null) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(state.error ?: "Could not open order notes.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = actions.retry) { Text(stringResource(R.string.retry)) }
                }
            } else {
                when (state.navigation.screen) {
                    "picker" -> PickerScreen(state, actions)
                    "queue" -> QueueScreen(state, actions)
                    "ticket" -> TicketScreen(state, actions)
                    else -> EntryScreen(state, actions)
                }
            }
        }
    }
}

@Composable
private fun Header(state: OrderPadState, actions: OrderPadCallbacks) {
    Surface(modifier = Modifier.statusBarsPadding(), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.navigation.screen != "entry") TextButton(onClick = actions.back) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(when (state.navigation.screen) {
                    "queue" -> "At the register"
                    "ticket" -> "Car ${state.notebook?.active?.sequence ?: ""}"
                    "picker" -> "Choose item"
                    else -> "Order pad"
                }, style = MaterialTheme.typography.titleLarge)
                if (state.navigation.screen == "entry") Text("Glenview · drive-through", style = MaterialTheme.typography.bodySmall)
                if (state.navigation.screen == "queue") Text("${state.notebook?.pending?.size ?: 0} cars · oldest first", style = MaterialTheme.typography.bodySmall)
            }
            if (state.navigation.screen in listOf("entry", "ticket")) OutlinedButton(onClick = actions.queue, modifier = Modifier.testTag("queue")) {
                Text("Queue · ${state.notebook?.pending?.size ?: 0}")
            }
        }
    }
}

@Composable
private fun Status(state: OrderPadState, actions: OrderPadCallbacks) {
    if (state.error != null) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
            Text(state.error, Modifier.padding(12.dp).semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
    if (state.message != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(state.message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            if (state.canUndo) TextButton(onClick = actions.undo, enabled = !state.saving, modifier = Modifier.testTag("undo")) { Text(stringResource(R.string.undo)) }
        }
    }
}

@Composable
private fun EntryScreen(state: OrderPadState, actions: OrderPadCallbacks) {
    val menu = state.menu!!
    val order = state.notebook!!.active
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.testTag("entry-list"),
    ) {
        item("car") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Car ${order.sequence}", style = MaterialTheme.typography.titleMedium)
                Text(if (state.saving) "Saving…" else if (order.hasItems) "Saved on device" else "New order", style = MaterialTheme.typography.bodySmall)
            }
            Status(state, actions)
        }
        items(Program.entries, key = { "card-${it.key}" }) { program ->
            val meals = order.meals.filter { it.program == program }
            val meal = meals.last()
            MealCard(menu, order, meal, program in state.expanded, meals.count { it.hasItems && it.id != meal.id }, actions, state.inputResetEpoch)
        }
    }
}

@Composable
private fun MealCard(menu: MenuCatalog, order: CarOrder, meal: Meal, expanded: Boolean, earlier: Int, actions: OrderPadCallbacks, inputResetEpoch: Int) {
    val card = menu.card(meal.program)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, if (meal.program == Program.BAGEL_TUESDAY && meal.bagelTotal > 13) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("card-${meal.program.key}"),
    ) {
        Row(
            Modifier.fillMaxWidth().then(if (card.collapsible) Modifier.clickable { actions.toggle(meal.program) } else Modifier)
                .padding(horizontal = 12.dp).heightIn(min = 56.dp)
                .testTag("toggle-${meal.program.key}")
                .semantics { if (card.collapsible) stateDescription = if (expanded) "Expanded" else "Collapsed" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(card.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                if (earlier > 0) Text("$earlier earlier meal · edit in Queue", style = MaterialTheme.typography.bodySmall)
                when (meal.program) {
                    Program.BAGEL_TUESDAY -> Text("${meal.bagelTotal} / 13 bagels${if (meal.bagelTotal > 13) " · ${meal.bagelTotal - 13} over" else ""}", color = if (meal.bagelTotal > 13) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("bagel-summary"))
                    Program.MIX_MATCH -> Text("${meal.slots.values.count { it != null }} / 10 selected", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
            if (card.repeatMeal && (!card.collapsible || expanded)) TextButton(onClick = { actions.apply(OrderAction.Another(order.id, meal.program)) }, enabled = meal.hasItems) { Text(stringResource(R.string.another)) }
            if (card.collapsible) Text(if (expanded) "⌃" else "⌄", Modifier.padding(start = 8.dp))
        }
        if (!card.collapsible || expanded) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (meal.program) {
                    Program.BAGEL_TUESDAY -> BagelRows(menu, order, meal, actions, "entry", inputResetEpoch)
                    Program.MIX_MATCH -> menu.slots(meal).chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { slot -> SlotButton(menu, meal, slot, { actions.openSlot(order.id, meal.id, slot.id, "entry") }, Modifier.weight(1f)) }
                        }
                    }
                    else -> menu.slots(meal).forEach { slot ->
                        FoodRow(menu, order, meal, slot, actions, "entry", showSize = meal.program == Program.DEFAULT && slot.id == "food")
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotButton(menu: MenuCatalog, meal: Meal, slot: SlotDefinition, onClick: () -> Unit, modifier: Modifier = Modifier, foodNameOnly: Boolean = false) {
    val selection = meal.slots[slot.id]
    OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = 54.dp).testTag("slot-${meal.id}-${slot.id}"), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
        Column(Modifier.fillMaxWidth()) {
            if (selection != null) Text(slot.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (selection == null) when {
                meal.program == Program.MIX_MATCH -> "${slot.label} · add"
                meal.program == Program.YOU_PICK_TWO && slot.role == "food" -> "Add ${slot.label.lowercase()}"
                else -> "Add ${slot.role}${if (slot.optional) " · optional" else ""}"
            } else if (foodNameOnly) selection.itemLabel else menu.label(selection), color = MaterialTheme.colorScheme.onSurface)
            if (selection != null && selection.portionId == null) Text("Confirm size", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FoodRow(menu: MenuCatalog, order: CarOrder, meal: Meal, slot: SlotDefinition, actions: OrderPadCallbacks, returnScreen: String, showSize: Boolean) {
    val selection = meal.slots[slot.id]
    val allowed = selection?.let { menu.offers[it.offerId]?.allowedPortionIds }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SlotButton(menu, meal, slot, { actions.openSlot(order.id, meal.id, slot.id, returnScreen) }, Modifier.fillMaxWidth(), foodNameOnly = showSize && allowed.size > 1)
        if (showSize && selection != null && allowed.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allowed.forEach { portion ->
                    Surface(
                        onClick = { actions.apply(OrderAction.SetPortion(order.id, meal.id, slot.id, portion)) },
                        color = if (selection.portionId == portion) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (selection.portionId == portion) "Selected" else "Not selected" },
                    ) { Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { Text(menu.portionLabel(portion)) } }
                }
            }
        }
    }
}

@Composable
private fun BagelRows(menu: MenuCatalog, order: CarOrder, meal: Meal, actions: OrderPadCallbacks, returnScreen: String, inputResetEpoch: Int) {
    val total = meal.bagelTotal
    Text("$total / 13 bagels", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("bagel-total").semantics { liveRegion = LiveRegionMode.Polite })
    if (total > 13) Warning("${total - 13} over the 13-bagel target. You can still continue.", Modifier.testTag("bagel-over"))
    val slots = menu.slots(meal)
    val shown = slots.filter { meal.slots[it.id] != null } + listOfNotNull(slots.firstOrNull { meal.slots[it.id] == null })
    shown.forEach { slot ->
        androidx.compose.runtime.key(meal.id, slot.id) {
            val line = meal.slots[slot.id]
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                OutlinedButton(
                    onClick = { actions.openSlot(order.id, meal.id, slot.id, returnScreen) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("bagel-pick-${slot.id}"),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp), shape = RoundedCornerShape(10.dp),
                ) { Text(line?.itemLabel ?: stringResource(R.string.select_bagel)) }
                if (line != null) {
                    QuantityField(line, slot.id, meal.id, inputResetEpoch) { actions.apply(OrderAction.Quantity(order.id, meal.id, slot.id, it)) }
                    TextButton(
                        onClick = { actions.apply(OrderAction.RemoveBagel(order.id, meal.id, slot.id)) },
                        modifier = Modifier.width(72.dp).heightIn(min = 56.dp).testTag("bagel-remove-${slot.id}").semantics { contentDescription = "Remove ${line.itemLabel} bagel row" },
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(stringResource(R.string.remove)) }
                } else {
                    OutlinedTextField(value = "", onValueChange = {}, enabled = false, modifier = Modifier.width(68.dp), placeholder = { Text(stringResource(R.string.quantity)) })
                    Spacer(Modifier.width(72.dp))
                }
            }
        }
    }
}

@Composable
private fun QuantityField(line: Selection, slotId: String, mealId: String, inputResetEpoch: Int, onQuantity: (Int) -> Unit) {
    var text by rememberSaveable(mealId, slotId) { mutableStateOf(line.quantity.toString()) }
    var focused by remember { mutableStateOf(false) }
    val valid = text.toIntOrNull()?.let { it > 0 } == true
    val focusManager = LocalFocusManager.current
    LaunchedEffect(line.quantity) { if (!focused) text = line.quantity.toString() }
    LaunchedEffect(inputResetEpoch) { text = line.quantity.toString() }
    OutlinedTextField(
        value = text,
        onValueChange = { value ->
            text = value
            value.toIntOrNull()?.takeIf { it > 0 }?.let(onQuantity)
        },
        singleLine = true,
        isError = text.isNotEmpty() && !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.width(68.dp).testTag("bagel-qty-$slotId").onFocusChanged {
            if (focused && !it.isFocused && !valid) text = line.quantity.toString()
            focused = it.isFocused
        }.semantics { contentDescription = "Quantity of ${line.itemLabel}; positive whole number" },
    )
}

@Composable
private fun Warning(text: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive }, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun PickerScreen(state: OrderPadState, actions: OrderPadCallbacks) {
    val nav = state.navigation
    val menu = state.menu!!
    val order = state.notebook!!.orders.first { it.id == nav.orderId }
    val meal = order.meals.first { it.id == nav.mealId }
    val slot = menu.slots(meal).first { it.id == nav.slotId }
    val offers = menu.choices(meal.program, slot)
    val categories = menu.categories(offers)
    val selectedCategory = categories.find { it.id == nav.categoryId }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("picker-list")) {
        item("context") {
            Text("${menu.card(meal.program).label} · ${slot.label}", style = MaterialTheme.typography.titleMedium)
            Status(state, actions)
        }
        if (selectedCategory == null) {
            items(categories.chunked(2), key = { "categories-${it.first().id}" }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { category ->
                        OutlinedButton(onClick = { actions.category(category.id) }, modifier = Modifier.weight(1f).heightIn(min = 72.dp).testTag("category-${category.id}"), shape = RoundedCornerShape(12.dp)) {
                            Text(category.label)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            item("category-heading") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedCategory.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (categories.size > 1) TextButton(onClick = { actions.category(null) }) { Text(stringResource(R.string.categories)) }
                }
            }
            items(offers.filter { menu.items.getValue(it.itemId).categoryId == selectedCategory.id }.chunked(2), key = { it.first().id }) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { offer -> OfferChoice(menu, offer, meal, actions.choose, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        item("clear") { OutlinedButton(onClick = actions.clear, enabled = meal.slots[slot.id] != null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.clear_selection)) } }
    }
}

@Composable
private fun OfferChoice(menu: MenuCatalog, offer: MenuOffer, meal: Meal, choose: (String, String?) -> Unit, modifier: Modifier = Modifier) {
    val item = menu.items.getValue(offer.itemId)
    val portions = offer.allowedPortionIds
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        TextButton(onClick = { choose(offer.id, portions.singleOrNull()) }, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("offer-${offer.id}")) {
            Column(Modifier.fillMaxWidth()) {
                Text(item.label, color = MaterialTheme.colorScheme.onSurface)
                if (portions.size == 1 && portions.single() != "each") Text(menu.portionLabel(portions.single()), style = MaterialTheme.typography.bodySmall)
                if (portions.size > 1) Text("Choose size below", style = MaterialTheme.typography.bodySmall)
                if (meal.program == Program.MIX_MATCH && meal.slots.values.any { it?.itemId == item.id }) Text("Already selected", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (portions.size > 1) FlowRow(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            portions.forEach { portion -> OutlinedButton(onClick = { choose(offer.id, portion) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.testTag("offer-${offer.id}-$portion")) { Text(menu.portionLabel(portion)) } }
        }
    }
}

@Composable
private fun QueueScreen(state: OrderPadState, actions: OrderPadCallbacks) {
    val notes = state.notebook!!
    val menu = state.menu!!
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.testTag("queue-list")) {
        item("status") { Status(state, actions) }
        if (notes.pending.isEmpty()) item("empty") { Text("All caught up.") }
        items(notes.pending, key = { it.id }) { car ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), modifier = Modifier.fillMaxWidth().testTag("queued-${car.id}")) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Car ${car.sequence}", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        TextButton(onClick = { actions.edit(car.id) }) { Text(stringResource(R.string.edit)) }
                    }
                    car.meals.filter { it.hasItems }.forEach { meal ->
                        Text(menu.card(meal.program).label, style = MaterialTheme.typography.titleMedium)
                        if (meal.program == Program.BAGEL_TUESDAY) Text("${meal.bagelTotal} / 13 bagels", style = MaterialTheme.typography.titleMedium)
                        menu.slots(meal).forEach { slot -> meal.slots[slot.id]?.let { line ->
                            Text("${if (slot.role != "food") slot.label + ": " else ""}${menu.label(line, meal.program == Program.BAGEL_TUESDAY)}")
                        } }
                        menu.review(meal).forEach { Warning(it) }
                        HorizontalDivider()
                    }
                    OutlinedButton(onClick = { actions.apply(OrderAction.Entered(car.id)) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.entered)) }
                }
            }
        }
    }
}

@Composable
private fun TicketScreen(state: OrderPadState, actions: OrderPadCallbacks) {
    val order = state.notebook!!.orders.find { it.id == state.navigation.orderId } ?: state.notebook.active
    val menu = state.menu!!
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("status") { Status(state, actions) }
        items(order.meals.filter { it.hasItems }, key = { it.id }) { meal ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(menu.card(meal.program).label, style = MaterialTheme.typography.titleMedium)
                    if (meal.program == Program.BAGEL_TUESDAY) BagelRows(menu, order, meal, actions, "ticket", state.inputResetEpoch)
                    else menu.slots(meal).forEach { FoodRow(menu, order, meal, it, actions, "ticket", showSize = meal.program == Program.DEFAULT && it.role == "food") }
                    menu.review(meal).filterNot { meal.program == Program.BAGEL_TUESDAY && meal.bagelTotal > 13 }.forEach { Warning(it) }
                }
            }
        }
    }
}

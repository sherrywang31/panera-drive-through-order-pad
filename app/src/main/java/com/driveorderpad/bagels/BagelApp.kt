package com.driveorderpad.bagels

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BagelApp(vm: BagelViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val speech = remember(vm, context) { OfflineBagelSpeech(context, vm::partial, vm::finishCapture) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionMessage = if (allowed) "Microphone ready. Hold the button again to capture the batch." else "Microphone permission denied. Type the batch or edit orders manually."
    }
    DisposableEffect(speech, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) speech.interrupt() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); speech.destroy() }
    }
    BackHandler(enabled = state.editId != null) { vm.edit(null) }
    BagelContent(state, speech.available, permissionMessage,
        onStart = {
            if (state.listening) speech.release()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permissions.launch(Manifest.permission.RECORD_AUDIO)
            else if (!state.saveFailed && state.book != null) {
                val id = bagelId()
                vm.startCapture(id)
                if (!speech.start(id)) vm.finishCapture(id, "", true, "On-device recognition is unavailable. Use the typed batch or touch editor.")
            }
        },
        onRelease = speech::release, onCancelCapture = speech::interrupt, onEdit = vm::edit,
        onChange = vm::changeCar, onTyped = vm::saveTyped, onOrganize = vm::organize,
        onApply = vm::applyBatch, onDiscard = vm::discard, onUndo = vm::undo,
        onRetry = vm::retry, onAddCar = vm::addCar, onConfirmSkipped = vm::confirmSkipped)
}

@Composable
fun BagelContent(
    state: BagelUiState, speechAvailable: Boolean, permissionMessage: String? = null,
    onStart: () -> Unit = {}, onRelease: () -> Unit = {}, onCancelCapture: () -> Unit = {},
    onEdit: (String?) -> Unit = {}, onChange: (String, (BagelCar) -> BagelCar) -> Unit = { _, _ -> },
    onTyped: (String) -> Unit = {}, onOrganize: (String) -> Unit = {}, onApply: () -> Unit = {}, onDiscard: () -> Unit = {},
    onUndo: () -> Unit = {}, onRetry: () -> Unit = {}, onAddCar: () -> Unit = {}, onConfirmSkipped: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val editorState = rememberLazyListState()
    val car = state.book?.cars?.firstOrNull { it.id == state.editId }
    LaunchedEffect(state.editId) { if (state.editId != null) editorState.scrollToItem(0) }
    var typedOpen by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(state.book?.draft.orEmpty()) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var entering by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.book?.draft) { if (!typing) typed = state.book?.draft.orEmpty(); if (typed.isNotBlank()) typedOpen = true }
    Scaffold(bottomBar = {
        if (car == null && !state.loading) Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (state.listening) "Listening to the batch · release when finished" else if (speechAvailable) "Say several numbered cars in one capture" else "Offline speech unavailable · type or edit below", style = MaterialTheme.typography.labelMedium)
                HoldCapture(state.listening, speechAvailable && !state.saveFailed && !state.saving && (state.book?.draft.isNullOrBlank() || state.listening), { typing = false; onStart() }, onRelease, onCancelCapture)
            }
        }
    }) { padding ->
        if (state.loading) Column(Modifier.padding(padding).padding(24.dp)) { CircularProgressIndicator(); Text("Opening bagel notes…") }
        else if (car != null) {
            LazyColumn(state = editorState, modifier = Modifier.padding(padding).testTag("bagel-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Column(Modifier.padding(16.dp)) {
                    Button(onClick = { onEdit(null) }, modifier = Modifier.testTag("back-to-batch")) { Text("Back to batch") }
                    Text("Edit Car ${car.number}", style = MaterialTheme.typography.headlineSmall)
                    Text("Changes auto-save · Undo available", style = MaterialTheme.typography.bodySmall)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.saveFailed) Button(onClick = onRetry) { Text("Retry saving") }
                    OutlinedButton(onClick = onUndo, enabled = state.canUndo && !state.saveFailed) { Text("Undo") }
                    Wrap {
                        OutlinedButton(onClick = { onChange(car.id) { it.copy(status = CarStatus.ORDER, groups = it.groups.ifEmpty { listOf(BagelGroup()) }) } }, enabled = !state.saveFailed) { Text("Has bagels") }
                        OutlinedButton(onClick = { onChange(car.id) { it.copy(status = CarStatus.NONE, groups = emptyList(), unresolved = emptyList()) } }, enabled = !state.saveFailed) { Text("No bagels") }
                    }
                } }
                if (car.status == CarStatus.ORDER) {
                    items(car.groups, key = { it.id }) { group ->
                        GroupEditor(car.id, group, state.catalog, !state.saveFailed, onChange)
                    }
                    item { TextButton(onClick = { onChange(car.id) { it.copy(groups = it.groups + BagelGroup()) } }, enabled = !state.saveFailed) { Text("+ Order group") } }
                }
                item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    car.reviews().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (car.heard.isNotBlank()) { Text("Heard text", fontWeight = FontWeight.Bold); Text(car.heard) }
                    if (car.unresolved.isNotEmpty()) TextButton(onClick = { onChange(car.id) { it.copy(unresolved = emptyList()) } }, enabled = !state.saveFailed) { Text("Heard text checked ✓") }
                    Button(onClick = { onEdit(null) }) { Text("Back to batch") }
                } }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.padding(padding).testTag("bagel-batch"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Bagel batch", style = MaterialTheme.typography.headlineMedium)
                    Text("Hold once: “Car 1 … Car 2 no bagels … Car 3 …”", style = MaterialTheme.typography.bodyMedium)
                    Text("Menu and dozen selling rules need café verification.", style = MaterialTheme.typography.bodySmall)
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    permissionMessage?.let { Text(it) }
                    if (state.saveFailed) Button(onClick = onRetry) { Text("Retry") }
                    if (state.listening && state.partial.isNotBlank()) Text(state.partial, modifier = Modifier.testTag("partial-transcript"))
                    Wrap {
                        TextButton(onClick = { typedOpen = !typedOpen }) { Text(if (typedOpen) "Hide heard text" else "Type / review heard text") }
                        OutlinedButton(onClick = onUndo, enabled = state.canUndo && !state.saveFailed && !state.listening) { Text("Undo") }
                    }
                    if (typedOpen) {
                        OutlinedTextField(value = typed, onValueChange = { typing = true; typed = it; onTyped(it) }, label = { Text("Batch transcript") }, modifier = Modifier.fillMaxWidth().testTag("batch-transcript"), enabled = !state.listening && !state.saveFailed, minLines = 3)
                        Wrap {
                            Button(onClick = { typing = false; onOrganize(typed) }, enabled = typed.isNotBlank() && !state.saveFailed && !state.listening, modifier = Modifier.testTag("organize-batch")) { Text("Organize batch") }
                            TextButton(onClick = { typing = false; onDiscard() }, enabled = !state.saveFailed && !state.listening) { Text("Discard heard text") }
                        }
                    }
                    if (state.proposal.isNotEmpty()) {
                        Text("Captured ${state.proposal.size} cars · review below", fontWeight = FontWeight.Bold)
                        Text(state.proposal.joinToString(" · ") { "Car ${it.number}: ${if (it.status == CarStatus.NONE) "no bagels" else "${it.count} bagels"}" })
                        val replaced = state.proposal.filter { p -> state.book?.cars?.any { it.number == p.number && it.status != CarStatus.UNCAPTURED } == true }
                        if (replaced.isNotEmpty()) Text("Replaces ${replaced.joinToString { "Car ${it.number}" }}. Other cars stay in place.", color = MaterialTheme.colorScheme.error)
                        if (state.book?.interrupted == true) Text("Capture was interrupted. Check for missing final words.", color = MaterialTheme.colorScheme.error)
                        Wrap {
                            Button(onClick = { typing = false; onApply() }, enabled = !state.saveFailed && !state.listening, modifier = Modifier.testTag("use-batch")) { Text("Use whole batch") }
                            TextButton(onClick = { typing = false; onDiscard() }, enabled = !state.saveFailed && !state.listening) { Text("Discard") }
                        }
                    }
                } }
                if (state.proposal.isNotEmpty()) items(state.proposal, key = { "proposal-${it.id}" }) { p ->
                    Summary(p, null, null, preview = true)
                }
                item { Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider()
                    Text("Cars in order", style = MaterialTheme.typography.titleLarge)
                    Wrap {
                        OutlinedButton(onClick = onAddCar, enabled = !state.saveFailed && !state.listening) { Text("+ Car") }
                        if (state.book?.cars?.any { !it.entered && it.status == CarStatus.UNCAPTURED } == true) TextButton(onClick = onConfirmSkipped, enabled = !state.saveFailed && !state.listening) { Text("Skipped cars: no bagels") }
                    }
                    if (state.book?.cars?.none { !it.entered } != false) Text("No queued cars. Capture a batch or add a car.")
                } }
                items(state.book?.cars.orEmpty().filterNot { it.entered }, key = { it.id }) { order ->
                    Summary(order, { onEdit(order.id) }, {
                        if (order.reviews().isEmpty()) onChange(order.id) { it.copy(entered = true) } else entering = order.id
                    })
                }
            }
        }
    }
    val pendingEntry = state.book?.cars?.firstOrNull { it.id == entering }
    if (pendingEntry != null) androidx.compose.material3.AlertDialog(onDismissRequest = { entering = null }, title = { Text("Car ${pendingEntry.number} still needs review") }, text = { Text("Have you resolved the missing details at the register?\n\n" + pendingEntry.reviews().joinToString("\n")) }, confirmButton = { TextButton(onClick = { onChange(pendingEntry.id) { it.copy(entered = true) }; entering = null }) { Text("Resolved · entered") } }, dismissButton = { TextButton(onClick = { entering = null; onEdit(pendingEntry.id) }) { Text("Edit car") } })
}

@Composable
private fun HoldCapture(listening: Boolean, enabled: Boolean, start: () -> Unit, release: () -> Unit, cancel: () -> Unit) {
    val canStart by rememberUpdatedState(enabled)
    val begin by rememberUpdatedState(start)
    val end by rememberUpdatedState(release)
    val abort by rememberUpdatedState(cancel)
    Surface(color = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp).testTag("hold-batch").semantics {
        role = Role.Button
        contentDescription = if (listening) "Listening to batch. Activate to stop." else "Hold to capture the whole batch. Activate to start hands-free."
        onClick { if (enabled || listening) { if (listening) release() else start(); true } else false }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (canStart) {
                down.consume(); begin()
                val up = waitForUpOrCancellation()
                if (up == null) abort() else { up.consume(); end() }
            }
        }
    }) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (listening) "Listening to the batch…" else if (enabled) "Hold to capture the whole batch" else "Use typed batch / touch editor", style = MaterialTheme.typography.titleLarge)
            Text(if (listening) "Release to organize every car" else "Car 1 · Car 2 · Car 3 …", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
@Composable private fun Summary(car: BagelCar, edit: (() -> Unit)?, entered: (() -> Unit)?, preview: Boolean = false) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(if (preview) "preview-car-${car.number}" else "car-${car.number}"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Car ${car.number}", style = MaterialTheme.typography.titleLarge)
                if (edit != null) OutlinedButton(onClick = edit, modifier = Modifier.testTag("edit-car-${car.number}")) { Text("Edit") }
            }
            when (car.status) {
                CarStatus.NONE -> Text("No bagels")
                CarStatus.UNCAPTURED -> Text("Not captured · confirm no bagels or add an order")
                CarStatus.ORDER -> car.groups.forEachIndexed { i, g ->
                    Text("${g.kind.label}${if (g.kind == BagelKind.DOZEN) " ${i + 1} · ${g.count}/13" else " · ${g.count} bagels"}", fontWeight = FontWeight.SemiBold)
                    g.bagels.forEach { line ->
                        Text("${line.quantity ?: "?"} × ${line.flavor}", fontWeight = FontWeight.Medium)
                        Text("${line.slice.label} · ${line.toast.label}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (g.spreads.isNotEmpty()) { HorizontalDivider(); g.spreads.forEach { s -> Text("${s.quantity ?: "?"} × ${s.flavor} · ${s.size.label}", style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            if (car.reviews().isNotEmpty()) Text("${car.reviews().size} details to review${if (preview) "" else " in Edit"}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (entered != null) TextButton(onClick = entered) { Text("Entered at register ✓") }
        }
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun Wrap(content: @Composable () -> Unit) { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() } }

@Composable private fun GroupEditor(carId: String, group: BagelGroup, catalog: BagelCatalog, enabled: Boolean, change: (String, (BagelCar) -> BagelCar) -> Unit) {
    fun modify(transform: (BagelGroup) -> BagelGroup) = change(carId) { c -> c.copy(groups = c.groups.map { if (it.id == group.id) transform(it) else it }) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${group.count} bagels${if (group.kind == BagelKind.DOZEN) " / 13 · ${group.spreads.sumOf { it.quantity ?: 0 }} / 2 spreads" else ""}", fontWeight = FontWeight.Bold)
            Wrap { BagelKind.entries.forEach { kind -> OutlinedButton(onClick = { modify { it.copy(kind = kind) } }, enabled = enabled && group.kind != kind) { Text(kind.label) } } }
            group.bagels.forEach { line ->
                FlavorSelector(line.flavor, catalog.flavors, enabled) { flavor -> modify { it.copy(bagels = it.bagels.map { b -> if (b.id == line.id) b.copy(flavor = flavor) else b }) } }
                Quantity("${line.flavor} quantity", line.quantity, enabled,
                    adjust = { delta -> modify { g -> g.copy(bagels = g.bagels.map { b -> if (b.id == line.id) b.copy(quantity = ((b.quantity ?: 0) + delta).coerceIn(1,999)) else b }) } },
                    change = { q -> modify { it.copy(bagels = it.bagels.map { b -> if (b.id == line.id) b.copy(quantity = q) else b }) } })
                Wrap { Slice.entries.forEach { value -> OutlinedButton(onClick = { modify { it.copy(bagels = it.bagels.map { b -> if (b.id == line.id) b.copy(slice = value) else b }) } }, enabled = enabled && line.slice != value) { Text(value.label) } } }
                Wrap { Toast.entries.forEach { value -> OutlinedButton(onClick = { modify { it.copy(bagels = it.bagels.map { b -> if (b.id == line.id) b.copy(toast = value) else b }) } }, enabled = enabled && line.toast != value) { Text(value.label) } } }
                Wrap {
                    TextButton(onClick = { modify { g ->
                        val current = g.bagels.first { it.id == line.id }
                        if ((current.quantity ?: 0) < 2) g else g.copy(bagels = g.bagels.map { if (it.id == line.id) it.copy(quantity = checkNotNull(it.quantity) - 1) else it } + current.copy(id = bagelId(), quantity = 1, slice = Slice.UNKNOWN, toast = Toast.UNKNOWN))
                    } }, enabled = enabled && (line.quantity ?: 0) >= 2) { Text("Split prep") }
                    TextButton(onClick = { modify { it.copy(bagels = it.bagels.filterNot { b -> b.id == line.id }) } }, enabled = enabled) { Text("Remove bagel") }
                }
                HorizontalDivider()
            }
            Text("Add bagel flavor", fontWeight = FontWeight.Bold)
            Wrap { catalog.flavors.forEach { flavor -> OutlinedButton(onClick = { modify { it.copy(bagels = it.bagels + BagelLine(flavor = flavor, quantity = 1)) } }, enabled = enabled) { Text("+ $flavor") } } }
            Text("Cream cheese", style = MaterialTheme.typography.titleMedium)
            group.spreads.forEach { spread ->
                FlavorSelector(spread.flavor, BagelCatalog.spreads, enabled) { flavor -> modify { it.copy(spreads = it.spreads.map { s -> if (s.id == spread.id) s.copy(flavor = flavor) else s }) } }
                Quantity("${spread.flavor} quantity", spread.quantity, enabled,
                    adjust = { delta -> modify { g -> g.copy(spreads = g.spreads.map { b -> if (b.id == spread.id) b.copy(quantity = ((b.quantity ?: 0) + delta).coerceIn(1,999)) else b }) } },
                    change = { q -> modify { it.copy(spreads = it.spreads.map { s -> if (s.id == spread.id) s.copy(quantity = q) else s }) } })
                Wrap { SpreadSize.entries.forEach { size -> OutlinedButton(onClick = { modify { it.copy(spreads = it.spreads.map { s -> if (s.id == spread.id) s.copy(size = size) else s }) } }, enabled = enabled && spread.size != size) { Text(size.label) } } }
                TextButton(onClick = { modify { it.copy(spreads = it.spreads.filterNot { s -> s.id == spread.id }) } }, enabled = enabled) { Text("Remove spread") }
            }
            Wrap { BagelCatalog.spreads.forEach { flavor -> OutlinedButton(onClick = { modify { it.copy(spreads = it.spreads + SpreadLine(flavor = flavor, quantity = 1)) } }, enabled = enabled) { Text("+ $flavor") } } }
            TextButton(onClick = { change(carId) { it.copy(groups = it.groups.filterNot { g -> g.id == group.id }) } }, enabled = enabled) { Text("Remove group · Undo available") }
        }
    }
}
@Composable private fun FlavorSelector(flavor: String, choices: List<String>, enabled: Boolean, change: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("$flavor ▾", fontWeight = FontWeight.Bold) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { change(choice); open = false }) } }
    }
}
@Composable private fun Quantity(label: String, quantity: Int?, enabled: Boolean, adjust: (Int) -> Unit, change: (Int?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { adjust(-1) }, enabled = enabled && (quantity ?: 0) > 1, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("−") }
        OutlinedTextField(value = quantity?.toString().orEmpty(), onValueChange = { value -> if (value.isEmpty()) change(null) else value.toIntOrNull()?.takeIf { it in 1..999 }?.let(change) }, label = { Text("Qty") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled, modifier = Modifier.width(90.dp).semantics { contentDescription = label })
        OutlinedButton(onClick = { adjust(1) }, enabled = enabled && (quantity ?: 0) < 999, modifier = Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) { Text("+") }
    }
}

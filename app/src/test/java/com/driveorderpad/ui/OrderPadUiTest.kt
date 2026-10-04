package com.driveorderpad.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.driveorderpad.domain.*
import com.driveorderpad.testMenu
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrderPadUiTest {
    @get:Rule val compose = createComposeRule()
    private val menu = testMenu()
    private val engine = OrderEngine(menu)
    private val state = mutableStateOf(OrderPadState(menu = menu, notebook = engine.initialize(Notebook()), loading = false))
    private var undo: Notebook? = null

    private fun mutate(action: OrderAction) {
        val change = engine.apply(state.value.notebook!!, action)
        undo = change.before
        state.value = state.value.copy(notebook = change.after, message = change.message, canUndo = true)
    }
    private val callbacks = OrderPadCallbacks(
        apply = ::mutate,
        nextCar = { mutate(OrderAction.NextCar(state.value.notebook!!.activeOrderId)) },
        undo = { state.value = state.value.copy(notebook = undo!!, inputResetEpoch = state.value.inputResetEpoch + 1, message = "Undone.", canUndo = false) },
        retry = {}, entry = { state.value = state.value.copy(navigation = NavigationState()) },
        queue = { state.value = state.value.copy(navigation = NavigationState("queue")) },
        back = { state.value = state.value.copy(navigation = NavigationState()) },
        edit = { id -> mutate(OrderAction.Activate(id)); state.value = state.value.copy(navigation = NavigationState("ticket", id)) },
        toggle = { program -> state.value = state.value.copy(expanded = if (program in state.value.expanded) state.value.expanded - program else state.value.expanded + program) },
        openSlot = { order, mealId, slotId, from ->
            val meal = state.value.notebook!!.orders.first { it.id == order }.meals.first { it.id == mealId }
            val category = menu.categories(menu.choices(meal.program, menu.slots(meal).first { it.id == slotId })).singleOrNull()?.id
            state.value = state.value.copy(navigation = NavigationState("picker", order, mealId, slotId, category, from))
        },
        category = { category -> state.value = state.value.copy(navigation = state.value.navigation.copy(categoryId = category)) },
        choose = { offer, portion ->
            val nav = state.value.navigation
            mutate(OrderAction.Select(nav.orderId!!, nav.mealId!!, nav.slotId!!, offer, portion))
            state.value = state.value.copy(navigation = NavigationState(nav.returnScreen, nav.orderId))
        },
        clear = {},
    )
    private var contentView: android.view.View? = null
    private fun show() = compose.setContent {
        contentView = LocalView.current
        OrderPadTheme { OrderPadContent(state.value, callbacks) }
    }
    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = checkNotNull(contentView)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val destination = java.io.File("build/reports/screenshots/$name.png").apply { parentFile?.mkdirs() }
            destination.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun `entry has no View ticket button and collapsed bagels can select remove and undo`() {
        show()
        screenshot("entry")
        compose.onNodeWithText("View ticket").assertDoesNotExist()
        compose.onNodeWithTag("next-car").assertExists()
        compose.onNodeWithTag("bagel-pick-bagel-1").assertDoesNotExist()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("card-bagel-tuesday"))
        compose.onNodeWithTag("toggle-bagel-tuesday").performClick()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-pick-bagel-1"))
        compose.onNodeWithTag("bagel-pick-bagel-1").performClick()
        compose.onNodeWithTag("picker-list").performScrollToNode(hasTestTag("offer-bagel-tuesday:food:bagels-everything"))
        compose.onNodeWithTag("offer-bagel-tuesday:food:bagels-everything").performClick()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-qty-bagel-1"))
        compose.onNodeWithTag("bagel-qty-bagel-1").performTextReplacement("14")
        compose.onNodeWithTag("bagel-total").assertTextContains("14 / 13 bagels")
        compose.onNodeWithTag("bagel-over").assertExists()
        screenshot("bagel-over-13")
        compose.onNodeWithTag("bagel-remove-bagel-1").performClick()
        compose.onNodeWithTag("bagel-remove-bagel-1").assertDoesNotExist()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("undo"))
        compose.onNodeWithTag("undo").performClick()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-qty-bagel-1"))
        compose.onNodeWithTag("bagel-qty-bagel-1").assertTextContains("14")
    }

    @Test fun `side picker skips categories and Next car preserves readback`() {
        val initial = state.value.notebook!!
        val meal = initial.active.meals.first { it.program == Program.DEFAULT }
        mutate(OrderAction.Select(initial.activeOrderId, meal.id, "food", "default:food:sandwiches-bacon-turkey-bravo", "whole"))
        show()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("slot-${meal.id}-side"))
        compose.onNodeWithTag("slot-${meal.id}-side").performClick()
        compose.onNodeWithTag("offer-default:side:sides-chips").performClick()
        compose.onNodeWithTag("next-car").performClick()
        compose.onNodeWithTag("queue").performClick()
        compose.onNodeWithTag("queued-car-1").assertExists()
        compose.onNodeWithText("Full · Bacon Turkey Bravo").assertExists()
        compose.onNodeWithText("Side: Chips").assertExists()
        screenshot("queue")
    }
}

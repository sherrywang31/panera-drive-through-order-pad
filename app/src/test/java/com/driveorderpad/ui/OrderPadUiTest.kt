package com.driveorderpad.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.geometry.Offset
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
    private val restoration = StateRestorationTester(compose)
    private val menu = testMenu()
    private val engine = OrderEngine(menu)
    private val state = mutableStateOf(OrderPadState(menu = menu, notebook = engine.initialize(Notebook()), loading = false))
    private var undo: Notebook? = null
    private var scrollRequest = 0L

    private fun mutate(action: OrderAction) {
        val change = engine.apply(state.value.notebook!!, action)
        undo = change.before
        state.value = state.value.copy(notebook = change.after, message = change.message, canUndo = true)
        if (action is OrderAction.Another) {
            val meal = change.after.active.meals.last { it.program == action.program }
            state.value = state.value.copy(focusedMeals = state.value.focusedMeals + ("${change.after.activeOrderId}:${meal.program.key}" to meal.id), expanded = state.value.expanded + meal.program)
        }
    }
    private fun returnToMeal(orderId: String, mealId: String, slotId: String? = null, screen: String = "entry", fromSummary: Boolean = false) {
        val meal = state.value.notebook!!.orders.first { it.id == orderId }.meals.first { it.id == mealId }
        state.value = state.value.copy(navigation = NavigationState(screen = screen, orderId = orderId, anchorMealId = mealId, anchorSlotId = slotId, anchorRequest = ++scrollRequest, fromSummary = fromSummary),
            focusedMeals = state.value.focusedMeals + ("$orderId:${meal.program.key}" to mealId), expanded = state.value.expanded + meal.program)
    }
    private val callbacks = OrderPadCallbacks(
        apply = ::mutate,
        nextCar = { mutate(OrderAction.NextCar(state.value.notebook!!.activeOrderId)) },
        undo = { state.value = state.value.copy(notebook = undo!!, inputResetEpoch = state.value.inputResetEpoch + 1, message = "Undone.", canUndo = false) },
        retry = {}, entry = { state.value = state.value.copy(navigation = NavigationState()) },
        queue = { state.value = state.value.copy(navigation = NavigationState("queue")) },
        back = { val nav = state.value.navigation; if (nav.screen == "picker") returnToMeal(nav.orderId!!,nav.mealId!!,nav.slotId,nav.returnScreen) else state.value = state.value.copy(navigation = NavigationState()) },
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
            returnToMeal(nav.orderId,nav.mealId,nav.slotId,nav.returnScreen)
        },
        clear = { val nav = state.value.navigation; mutate(OrderAction.Clear(nav.orderId!!,nav.mealId!!,nav.slotId!!)); returnToMeal(nav.orderId,nav.mealId,nav.slotId,nav.returnScreen) },
        editMeal = { mealId -> returnToMeal(state.value.notebook!!.activeOrderId,mealId,fromSummary = true) },
    )
    private var contentView: android.view.View? = null
    private fun show() = restoration.setContent {
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

    @Test fun `whole bagel boxes add to thirteen minus removes one and summary edit reaches folded bagels`() {
        show()
        screenshot("entry")
        compose.onNodeWithText("View ticket").assertDoesNotExist()
        compose.onNodeWithTag("next-car").assertExists()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-everything"))
        compose.onNodeWithTag("bagel-add-bagels-everything").performTouchInput { click(Offset(12f, 12f)) }
        repeat(12) { compose.onNodeWithTag("bagel-add-bagels-everything").performClick() }
        compose.onNodeWithTag("bagel-total").assertTextContains("13 / 13 bagels")
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsNotEnabled()
        compose.onNodeWithTag("bagel-minus-bagels-everything").assertIsEnabled()
        screenshot("bagel-thirteen")
        compose.onNodeWithTag("toggle-bagel-tuesday").performScrollTo().performClick()
        val bagel = state.value.notebook!!.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("summary-${bagel.id}"))
        compose.onNodeWithTag("summary-${bagel.id}").performClick()
        compose.onNodeWithTag("toggle-bagel-tuesday").assertIsDisplayed()
        screenshot("bagel-edit-return")
        compose.onNodeWithTag("bagel-minus-bagels-everything").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("bagel-total").assertTextContains("12 / 13 bagels")
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsEnabled()
        compose.onNodeWithTag("bagel-minus-bagels-everything").performClick()
        compose.onNodeWithTag("bagel-total").assertTextContains("11 / 13 bagels")
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("undo"))
        compose.onNodeWithTag("undo").performClick()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-total"))
        compose.onNodeWithTag("bagel-total").assertTextContains("12 / 13 bagels")
    }

    @Test fun `side picker skips categories and Next car preserves readback`() {
        val initial = state.value.notebook!!
        val meal = initial.active.meals.first { it.program == Program.DEFAULT }
        mutate(OrderAction.Select(initial.activeOrderId, meal.id, "food", "default:food:sandwiches-bacon-turkey-bravo", "whole"))
        show()
        compose.onNodeWithTag("next-car").assertIsNotEnabled()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("slot-${meal.id}-side"))
        compose.onNodeWithTag("slot-${meal.id}-side").performClick()
        compose.onNodeWithTag("offer-default:side:sides-chips").performClick()
        compose.onNodeWithTag("slot-${meal.id}-side").assertIsDisplayed()
        compose.onNodeWithTag("next-car").assertIsEnabled()
        compose.onNodeWithTag("next-car").performClick()
        compose.onNodeWithTag("queue").performClick()
        compose.onNodeWithTag("queued-car-1").assertExists()
        compose.onNodeWithText("Car 0").assertExists()
        compose.onNodeWithText("Full · Bacon Turkey Bravo").assertExists()
        compose.onNodeWithText("Side: Chips").assertExists()
        screenshot("queue")
    }

    @Test fun `two Default and two You Pick Two meals remain editable from the summary and selection returns to its field`() {
        val car = state.value.notebook!!.active
        val firstDefault = car.meals.first { it.program == Program.DEFAULT }
        val firstTwo = car.meals.first { it.program == Program.YOU_PICK_TWO }
        mutate(OrderAction.Select(car.id, firstDefault.id, "food", "default:food:sandwiches-bacon-turkey-bravo", "whole"))
        mutate(OrderAction.Another(car.id, Program.DEFAULT))
        val secondDefault = state.value.notebook!!.active.meals.last { it.program == Program.DEFAULT }
        mutate(OrderAction.Select(car.id, secondDefault.id, "food", "default:food:sandwiches-tuna-salad", "whole"))
        mutate(OrderAction.Select(car.id, firstTwo.id, "food-1", "you-pick-two:food:sandwiches-grilled-cheese", "half"))
        mutate(OrderAction.Another(car.id, Program.YOU_PICK_TWO))
        val secondTwo = state.value.notebook!!.active.meals.last { it.program == Program.YOU_PICK_TWO }
        mutate(OrderAction.Select(car.id, secondTwo.id, "food-1", "you-pick-two:food:sandwiches-tuna-salad", "half"))
        show()
        compose.onNodeWithText("2 Default · 2 You Pick Two").assertExists()
        screenshot("ordered-list")
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("summary-${firstTwo.id}"))
        compose.onNodeWithTag("summary-${firstTwo.id}").performClick()
        compose.onNodeWithTag("slot-${firstTwo.id}-food-1").assertIsDisplayed()
        val originalTop = compose.onNodeWithTag("slot-${firstTwo.id}-food-2").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("slot-${firstTwo.id}-food-2").performClick()
        compose.onNodeWithTag("category-soups-mac").performClick()
        compose.onNodeWithTag("picker-list").performScrollToNode(hasTestTag("offer-you-pick-two:food:soups-mac-mac-cheese"))
        compose.onNodeWithTag("offer-you-pick-two:food:soups-mac-mac-cheese").performClick()
        compose.onNodeWithTag("slot-${firstTwo.id}-food-2").assertIsDisplayed().assertTextContains("Cup · Mac & Cheese", substring = true)
        val returnedTop = compose.onNodeWithTag("slot-${firstTwo.id}-food-2").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertEquals("the field retains its scroll position", originalTop, returnedTop, 1f)
        compose.runOnIdle { org.junit.Assert.assertEquals("Tuna Salad", state.value.notebook!!.active.meals.first { it.id == secondTwo.id }.slots["food-1"]!!.itemLabel) }
        screenshot("selection-return")
        // A handled return target must not pull the cashier back after screen recreation.
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-everything"))
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsDisplayed()
    }
}

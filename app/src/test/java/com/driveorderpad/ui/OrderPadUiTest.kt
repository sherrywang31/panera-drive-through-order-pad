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
            state.value = state.value.copy(focusedMeals = state.value.focusedMeals + ("${change.after.activeOrderId}:${meal.program.section.key}" to meal.id), expanded = state.value.expanded + meal.program.section)
        }
    }
    private fun returnToMeal(orderId: String, mealId: String, slotId: String? = null, screen: String = "entry", fromSummary: Boolean = false) {
        val meal = state.value.notebook!!.orders.first { it.id == orderId }.meals.first { it.id == mealId }
        state.value = state.value.copy(navigation = NavigationState(screen = screen, orderId = orderId, anchorMealId = mealId, anchorSlotId = slotId, anchorRequest = ++scrollRequest, fromSummary = fromSummary),
            focusedMeals = state.value.focusedMeals + ("$orderId:${meal.program.section.key}" to mealId), expanded = state.value.expanded + meal.program.section)
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
            val slot = menu.slots(meal).first { it.id == slotId }
            val category = if (slot.role == "drink") "hot-coffee-tea" else menu.categories(menu.choices(meal.program, slot)).singleOrNull()?.id
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
        focusCount = { mealId ->
            val meal = state.value.notebook!!.active.meals.first { it.id == mealId }
            state.value = state.value.copy(focusedMeals = state.value.focusedMeals + ("${state.value.notebook!!.activeOrderId}:${meal.program.section.key}" to mealId))
        },
        drinkCategory = { id -> state.value = state.value.copy(drinkCategory = id) },
        drinkSize = { category, portion -> state.value = state.value.copy(drinkSizes = state.value.drinkSizes + (category to portion)) },
    )
    private fun open(program: Program) {
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("toggle-${program.key}"))
        compose.onNodeWithTag("toggle-${program.key}").performClick()
    }
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
        open(Program.BAGEL_TUESDAY)
        val target = state.value.notebook!!.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        compose.onNodeWithTag("bagel-target-${target.id}").performClick()
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
        open(Program.DEFAULT)
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
        open(Program.BAGEL_TUESDAY)
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-everything"))
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("bagel-add-bagels-everything").assertIsDisplayed()
    }
    @Test fun `Drinks opens hot and Bottled and Frozen add without size controls`() {
        show()
        compose.onNodeWithTag("drink-category-heading").assertDoesNotExist()
        open(Program.DRINKS)
        compose.onNodeWithTag("drink-category-heading").assertTextContains("Hot Coffee & Tea")
        compose.onNodeWithTag("drink-size-2-oz").assertDoesNotExist()
        compose.onNodeWithTag("drink-size-20-oz").performClick()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("drink-add-hot-coffee-tea-dark-roast-coffee-20-oz"))
        compose.onNodeWithTag("drink-add-hot-coffee-tea-dark-roast-coffee-20-oz").performTouchInput { click(Offset(12f, 12f)) }
        compose.runOnIdle { org.junit.Assert.assertEquals(1L, state.value.notebook!!.active.meals.first { it.program == Program.DRINKS }.quantity("hot-coffee-tea-dark-roast-coffee", "20-oz")) }
        compose.onNodeWithTag("drink-category-bottled-canned").performScrollTo().performClick()
        compose.onNodeWithTag("drink-size-16-oz").assertDoesNotExist()
        compose.onNodeWithTag("drink-size-each").assertDoesNotExist()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("drink-add-bottled-canned-premium-oj-each"))
        compose.onNodeWithTag("drink-add-bottled-canned-premium-oj-each").performClick()
        screenshot("bottled")
        compose.onNodeWithTag("drink-category-frozen-smoothies").performScrollTo().performClick()
        compose.onNodeWithTag("drink-size-20-oz").assertDoesNotExist()
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("drink-add-frozen-smoothies-tropical-green-smoothie-each"))
        compose.onNodeWithTag("drink-add-frozen-smoothies-tropical-green-smoothie-each").performClick()
        compose.runOnIdle {
            val lines = state.value.notebook!!.active.meals.first { it.program == Program.DRINKS }.slots.values.filterNotNull()
            org.junit.Assert.assertEquals(3, lines.size)
            org.junit.Assert.assertEquals("each", lines.last().portionId)
        }
        screenshot("frozen")
    }

    @Test fun `one bagel grid switches individual and multiple dozen counts and supports cream cheese and Undo`() {
        show()
        open(Program.BAGEL_TUESDAY)
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-plain"))
        repeat(15) { compose.onNodeWithTag("bagel-add-bagels-plain").performClick() }
        val individual = state.value.notebook!!.active.meals.first { it.program == Program.INDIVIDUAL_BAGELS }
        val first = state.value.notebook!!.active.meals.first { it.program == Program.BAGEL_TUESDAY }
        compose.onNodeWithTag("bagel-target-${first.id}").performScrollTo().performClick()
        compose.onNodeWithTag("bagel-total").assertTextContains("0 / 13 bagels")
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-plain"))
        repeat(13) { compose.onNodeWithTag("bagel-add-bagels-plain").performClick() }
        compose.onNodeWithTag("bagel-add-bagels-plain").assertIsNotEnabled()
        compose.onNodeWithTag("add-dozen").performScrollTo().performClick()
        val second = state.value.notebook!!.active.meals.last { it.program == Program.BAGEL_TUESDAY }
        compose.onNodeWithTag("bagel-total").assertTextContains("0 / 13 bagels")
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("bagel-add-bagels-everything"))
        repeat(3) { compose.onNodeWithTag("bagel-add-bagels-everything").performClick() }
        compose.onNodeWithTag("bagel-target-${individual.id}").performScrollTo().performClick()
        compose.onNodeWithTag("bagel-total").assertTextContains("15 bagels")
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("cream-add-spreads-plain-cream-cheese-1-5-oz"))
        compose.onNodeWithTag("cream-add-spreads-plain-cream-cheese-1-5-oz").performClick()
        compose.onNodeWithTag("cream-add-spreads-plain-cream-cheese-8-oz").performClick()
        screenshot("cream-cheese")
        compose.onNodeWithTag("bagel-target-${second.id}").performScrollTo().performClick()
        screenshot("multiple-dozens")
        compose.onNodeWithTag("remove-dozen").performClick()
        compose.runOnIdle { org.junit.Assert.assertFalse(state.value.notebook!!.active.meals.any { it.id == second.id }) }
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("undo"))
        compose.onNodeWithTag("undo").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals(3L, state.value.notebook!!.active.meals.first { it.id == second.id }.bagelTotal)
            org.junit.Assert.assertEquals(15L, state.value.notebook!!.active.meals.first { it.id == individual.id }.bagelTotal)
        }
    }

    @Test fun `meal drink names wait for sizes and soup bowl returns to its field`() {
        show()
        open(Program.DEFAULT)
        val car = state.value.notebook!!.active
        val m = car.meals.first { it.program == Program.DEFAULT }
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("slot-${m.id}-drink"))
        compose.onNodeWithTag("slot-${m.id}-drink").performClick()
        val offer = "default:drink:hot-coffee-tea-dark-roast-coffee"
        compose.onNodeWithTag("offer-$offer").performTouchInput { click(Offset(12f, 12f)) }
        compose.runOnIdle {
            org.junit.Assert.assertEquals("picker", state.value.navigation.screen)
            org.junit.Assert.assertNull(state.value.notebook!!.active.meals.first { it.id == m.id }.slots["drink"])
        }
        compose.onNodeWithTag("offer-$offer-20-oz").performClick()
        compose.onNodeWithTag("slot-${m.id}-drink").assertIsDisplayed().assertTextContains("20 oz · Dark Roast Coffee")
        val two = car.meals.first { it.program == Program.YOU_PICK_TWO }
        open(Program.YOU_PICK_TWO)
        compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("slot-${two.id}-food-1"))
        compose.onNodeWithTag("slot-${two.id}-food-1").performClick()
        compose.onNodeWithTag("category-soups-mac").performClick()
        compose.onNodeWithTag("offer-you-pick-two:food:soups-mac-broccoli-cheddar-bowl").performClick()
        compose.onNodeWithTag("slot-${two.id}-food-1").assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals("bowl", state.value.notebook!!.active.meals.first { it.id == two.id }.slots["food-1"]!!.portionId) }
        screenshot("soup-bowl")
    }

}

package com.driveorderpad.bagels

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToNode
import com.driveorderpad.ui.OrderPadTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w320dp-h640dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BagelUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun cleanBatchAndDedicatedEditorAtSmallPhoneWidth() {
        val cars=BagelParser(BagelCatalog.fallback).parse("Car 1 8 cinnamon swirl and raisin sliced toasted,5 chocolate chip,3 asiago. Car 2 no bagels",1)
        val state=mutableStateOf(BagelUiState(loading=false,book=BagelNotebook(cars=cars,nextCarNumber=3)))
        var view:android.view.View?=null
        compose.setContent { view=LocalView.current;OrderPadTheme { BagelContent(state.value,true,onEdit={state.value=state.value.copy(editId=it)}) } }
        compose.onNodeWithTag("hold-batch").assertIsDisplayed()
        compose.onNodeWithText("Drinks").assertDoesNotExist()
        compose.onNodeWithTag("bagel-batch").performScrollToNode(hasTestTag("edit-car-1"))
        compose.onNodeWithTag("edit-car-1").performClick()
        compose.onNodeWithTag("back-to-batch").assertIsDisplayed()
        compose.onNodeWithText("Edit Car 1").assertIsDisplayed()
        compose.runOnIdle {
            val v=checkNotNull(view)
            val bitmap=android.graphics.Bitmap.createBitmap(v.width,v.height,android.graphics.Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/screenshots/bagel-editor-320.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        }
        compose.onNodeWithTag("back-to-batch").performClick()
        compose.onNodeWithTag("hold-batch").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null,state.value.editId) }
    }
    @Test fun wholeBatchConfirmationIsOneAction() {
        val proposal=BagelParser(BagelCatalog.fallback).parse("Car 1 2 sesame. Car 2 no bagels. Car 3 13 plain.",1)
        var applied=0
        compose.setContent { OrderPadTheme { BagelContent(BagelUiState(loading=false,book=BagelNotebook(),proposal=proposal),true,onApply={applied++}) } }
        compose.onNodeWithTag("bagel-batch").performScrollToNode(hasTestTag("use-batch"))
        compose.onNodeWithTag("use-batch").performClick()
        compose.runOnIdle { assertEquals(1,applied) }
    }
    @Test fun holdingSurvivesSavingRecompositionAndReleasesOnce() {
        val state=mutableStateOf(BagelUiState(loading=false,book=BagelNotebook()))
        var starts=0;var releases=0
        compose.setContent { OrderPadTheme { BagelContent(state.value,true,onStart={starts++;state.value=state.value.copy(listening=true,saving=true)},onRelease={releases++}) } }
        compose.onNodeWithTag("hold-batch").performTouchInput { down(center) }
        compose.waitForIdle()
        compose.onNodeWithTag("hold-batch").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1,starts);assertEquals(1,releases) }
    }

}

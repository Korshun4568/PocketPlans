package ru.slava.pocketplans

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.slava.pocketplans.data.*
import ru.slava.pocketplans.ui.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val phone = Product(7, "Phone", "Portable device for work and travel", "tech", 500, 1789747200000)
    @Test fun itemClickDeliversCorrectId() {
        var selected = -1
        compose.setContent { PocketTheme { Surface {
            CatalogScreen(CatalogState(Phase.Success, listOf(phone)), "", "", false, emptyList(), {}, {}, {}, {}, { selected = it }, {}, {})
        } } }
        compose.onNodeWithTag("product-7").performClick()
        compose.runOnIdle { assertEquals(7, selected) }
    }
    @Test fun errorRetryUpdatesRenderedScreen() {
        var attempts = 0
        compose.setContent {
            var state by remember { mutableStateOf(CatalogState(Phase.Error, error = "Нет сети")) }
            PocketTheme { Surface { CatalogScreen(state, "", "", false, emptyList(), {}, {}, {}, {
                attempts++; state = CatalogState(Phase.Success, listOf(phone))
            }, {}, {}, {}) } }
        }
        compose.onNodeWithTag("retry").performClick(); compose.onNodeWithTag("product-7").assertExists()
        compose.runOnIdle { assertEquals(1, attempts) }
    }
    @Test fun renderDocumentedStates() {
        var current by mutableStateOf(CatalogState())
        compose.setContent { PocketTheme { Surface {
            CatalogScreen(current, "", "", false, listOf("tech"), {}, {}, {}, {}, {}, {}, {})
        } } }
        compose.onNodeWithTag("loading").assertExists(); capture("loading")
        compose.runOnIdle { current = CatalogState(Phase.Error, error = "Нет сети") }; capture("error")
        compose.runOnIdle { current = CatalogState(Phase.Empty) }; capture("empty")
        compose.runOnIdle { current = CatalogState(Phase.Success, listOf(phone), favoriteIds = setOf(7)) }; capture("catalog")
    }
    @Test fun detailSavesEditedDecision() {
        var saved: Personal? = null
        compose.setContent { PocketTheme { Surface {
            DetailScreen(phone, Personal(7), emptyList(), emptyList(),
                { note, target, favorite -> saved = Personal(7, note, target, favorite) }, {})
        } } }
        compose.onNodeWithText("Моя заметка / решение").performTextInput("Для работы")
        compose.onNodeWithText("Целевая цена, USD (необязательно)").performTextInput("4.50")
        compose.onNodeWithText("Сохранить решение").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Personal(7, "Для работы", 450, false), saved) }
        capture("detail")
    }
    @Test fun planQuantityUpdatesBudgetOnScreen() {
        var entry by mutableStateOf(PlanEntry(1, 7))
        compose.setContent { PocketTheme { Surface {
            PlanScreen(Plan(1, "Техника", 700, 0), listOf(entry), listOf(phone), {}, { entry = it }, {}, {}, {}, null)
        } } }
        compose.onNodeWithText("+").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(2, entry.quantity) }
        compose.onNodeWithText("Превышение: ${money(300)}").performScrollTo().assertIsDisplayed()
        capture("plan")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Render the actual view hierarchy without an asynchronous PixelCopy callback.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val file = File("build/screenshots/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle(); assertTrue(file.length() > 0)
        }
    }
}

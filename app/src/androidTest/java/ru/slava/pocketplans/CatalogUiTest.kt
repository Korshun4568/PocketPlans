package ru.slava.pocketplans

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import ru.slava.pocketplans.data.Product
import ru.slava.pocketplans.ui.*

class CatalogUiTest {
    @get:Rule val compose = createComposeRule()
    private val phone = Product(7, "Phone", "Device", "tech", 500, 0)
    @Test fun itemClickDeliversCorrectId() {
        var selected = -1
        compose.setContent { MaterialTheme {
            CatalogScreen(CatalogState(Phase.Success, listOf(phone)), "", "", false, emptyList(), {}, {}, {}, {}, { selected = it }, {}, {})
        } }
        compose.onNodeWithTag("product-7").performClick(); compose.runOnIdle { assertEquals(7, selected) }
    }
    @Test fun retryDisplaysContent() {
        compose.setContent {
            var state by remember { mutableStateOf(CatalogState(Phase.Error, error = "Нет сети")) }
            MaterialTheme { CatalogScreen(state, "", "", false, emptyList(), {}, {}, {}, {
                state = CatalogState(Phase.Success, listOf(phone))
            }, {}, {}, {}) }
        }
        compose.onNodeWithTag("retry").performClick(); compose.onNodeWithTag("product-7").assertExists()
    }
    @Test fun emptyResultHasEmptyMessage() {
        compose.setContent { MaterialTheme {
            CatalogScreen(CatalogState(Phase.Empty), "", "", false, emptyList(), {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("Ничего не найдено").assertExists()
    }
}

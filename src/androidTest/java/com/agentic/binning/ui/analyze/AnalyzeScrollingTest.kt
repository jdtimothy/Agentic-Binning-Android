package com.agentic.binning.ui.analyze

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.agentic.binning.db.ItemKind
import com.agentic.binning.db.ItemStatus
import com.agentic.binning.ui.theme.AgenticTheme
import com.agentic.binning.ui.theme.ThemeMode
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic UI only: never opens a project or starts model inference. */
class AnalyzeScrollingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun carousel_exposesHorizontalTouchScrolling() {
        compose.setContent {
            AgenticTheme(ThemeMode.Dark) {
                CardCarousel(
                    CarouselState(listOf(card(1), card(2), card(3)), 1, "scratch"),
                    remember { mutableStateOf(NoProgress) },
                    onAddMedia = {},
                    modifier = Modifier.width(390.dp).height(290.dp),
                )
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .assertExists()
    }

    @Test fun carousel_swipesSelectMatchingOutput_andStopAtBothEnds() {
        val state = mutableStateOf(CarouselState(listOf(card(1), card(2), card(3)), 1, "scratch"))
        compose.setContent {
            AgenticTheme(ThemeMode.Dark) {
                Column {
                    CardCarousel(
                        state.value, remember { mutableStateOf(NoProgress) }, onAddMedia = {},
                        modifier = Modifier.width(390.dp).height(290.dp),
                        onSelectCard = { id -> state.value = state.value.copy(current = state.value.cards.indexOfFirst { it.id == id }) },
                    )
                    val card = state.value.focus!!
                    val saved = StoredAnalysis(card.id, "Saved description for ${card.name}", emptyList(), listOf("clip-${card.id}"))
                    OutputCard(outputOf(true, false, card, null, saved), Modifier.width(360.dp).height(200.dp))
                }
            }
        }
        val carousel = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        carousel.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Saved description for Clip 3").assertIsDisplayed()
        carousel.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Saved description for Clip 3").assertIsDisplayed()
        repeat(2) { carousel.performTouchInput { swipeRight() }; compose.waitForIdle() }
        compose.onNodeWithText("Saved description for Clip 1").assertIsDisplayed()
        carousel.performTouchInput { swipeRight() }
        compose.onNodeWithText("Saved description for Clip 1").assertIsDisplayed()
    }

    @Test fun description_canBeSwipedDownToActionsAndBackUp() {
        compose.setContent {
            AgenticTheme(ThemeMode.Dark) {
                OutputCard(
                    EmptyOutput.copy(
                        itemId = 1,
                        description = (1..60).joinToString("\n") { "Description line $it: a clip with plenty of detail." },
                        actions = listOf(ActionRow("0:01", "Last action")),
                        tags = listOf("last-tag"),
                    ),
                    Modifier.width(360.dp).height(260.dp),
                )
            }
        }
        val body = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        fun offset() = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertTrue(offset() == 0f)
        body.performTouchInput { swipeUp() }
        compose.waitForIdle()
        val down = offset()
        assertTrue("Swipe up must reveal later description content", down > 0f)
        body.performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertTrue("Swipe down must return to earlier description content", offset() < down)
        repeat(35) { body.performTouchInput { swipeUp() }; compose.waitForIdle() }
        compose.onNodeWithText("#last-tag").assertIsDisplayed()
        compose.onNodeWithText("Last action").performScrollTo().assertIsDisplayed()
        repeat(35) { body.performTouchInput { swipeDown() }; compose.waitForIdle() }
        assertTrue("The entire description remains reachable in both directions", offset() == 0f)
    }

    private fun card(id: Long) = CardItem(
        id = id, name = "Clip $id", kind = ItemKind.Video, status = ItemStatus.Analyzed,
        meta = "Test clip", duration = "0:10", durationMs = 10_000,
        image = File("/nonexistent/$id.jpg"), imageIsVideo = false,
    )
}

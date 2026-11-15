package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserSupportScreen
import com.example.ui.user.computeIstMidnightCountdownMillis
import com.example.ui.user.formatCountdownHms
import com.example.util.UnityAdsHelper
import com.unity3d.services.core.properties.ClientProperties
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditFixesRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `IST midnight countdown ignores device timezone and formats exact remaining time`() {
    val previousTz = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
      val istCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"), Locale.US).apply {
        set(2026, Calendar.NOVEMBER, 15, 23, 59, 30)
        set(Calendar.MILLISECOND, 0)
      }
      val diffMillis = computeIstMidnightCountdownMillis(istCal.timeInMillis)
      assertEquals(30_000L, diffMillis)
      assertEquals("00:00:30", formatCountdownHms(diffMillis))
    } finally {
      TimeZone.setDefault(previousTz)
    }
  }

  @Test
  fun `UserSupportScreen waits for submit callback result before confirming submission`() {
    var shouldSucceed = false
    composeTestRule.setContent {
      TaskFlowTheme {
        UserSupportScreen(
          onBack = {},
          onNavigateScreen = {},
          onSubmitTicket = { _, _, _, onResult ->
            onResult(shouldSucceed)
          }
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("tab_contact_support").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithText("Subject").performTextInput("Missing Coins")
    composeTestRule.onNodeWithText("Detailed Description").performTextInput("Check-in completed cleanly")
    composeTestRule.waitForIdle()

    // Simulate backend failure: confirmation card must NOT appear
    composeTestRule.onNodeWithTag("submit_support_ticket_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule
      .onNodeWithText("Could not submit your inquiry. Please try again.")
      .performScrollTo()
      .assertIsDisplayed()

    // Retry with backend success: confirmation card appears
    shouldSucceed = true
    composeTestRule.onNodeWithTag("submit_support_ticket_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Inquiry Submitted!").assertIsDisplayed()
  }

  @Test
  fun `UnityAdsHelper loadRewardedAd invokes onError cleanly when no context is available`() {
    ClientProperties.setApplicationContext(null)
    ClientProperties.setActivity(null)
    var capturedError = ""
    UnityAdsHelper.loadRewardedAd(
      onLoaded = {},
      onError = { capturedError = it }
    )
    assertTrue(capturedError.isNotBlank())
  }
}

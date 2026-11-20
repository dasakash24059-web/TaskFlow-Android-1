package com.example

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.DailyCheckInState
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.ScratchCardSession
import com.example.data.model.ScratchCardUiState
import com.example.data.model.ScratchDailyState
import com.example.data.model.SpinEarnUiState
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TelegramTaskItem
import com.example.data.model.TelegramUserTaskState
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.coinsToCents
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.GoldAccentDark
import com.example.ui.theme.GoldAccentLight
import com.example.ui.theme.TaskFlowFontFamily
import com.example.ui.theme.TaskFlowSemanticColors
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.theme.TaskFlowTypography
import com.example.ui.theme.taskFlowColors
import com.example.ui.user.DedicatedDailyCheckInScreen
import com.example.ui.user.OfferWallScreen
import com.example.ui.user.ScratchCardScreen
import com.example.ui.user.SpinAndEarnScreen
import com.example.ui.user.TelegramJoinTaskCard
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserWalletScreen
import com.example.ui.user.WithdrawalHistoryScreen
import com.example.ui.viewmodel.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2 verification suite covering:
 * 1. Consolidated Material 3 Roboto Sans-serif typography with Normal, Medium, SemiBold weights only.
 * 2. Light and Dark semantic color resolution (restrained gold/charcoal CTAs, champagne-gold badges,
 *    accessible foreground/background luminance contrast).
 * 3. Rendering key modified screens cleanly in BOTH Light Mode and Dark Mode.
 * 4. Preservation of the 125 TF = ₹1 conversion standard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class Phase2TypographyThemeTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun consolidatedTypography_usesRobotoSansSerifAndRestrainedWeightsOnly() {
    assertEquals(FontFamily.SansSerif, TaskFlowFontFamily)

    val allowedWeights = setOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold)
    val allStyles =
      listOf(
        "displayLarge" to TaskFlowTypography.displayLarge,
        "displayMedium" to TaskFlowTypography.displayMedium,
        "displaySmall" to TaskFlowTypography.displaySmall,
        "headlineLarge" to TaskFlowTypography.headlineLarge,
        "headlineMedium" to TaskFlowTypography.headlineMedium,
        "headlineSmall" to TaskFlowTypography.headlineSmall,
        "titleLarge" to TaskFlowTypography.titleLarge,
        "titleMedium" to TaskFlowTypography.titleMedium,
        "titleSmall" to TaskFlowTypography.titleSmall,
        "bodyLarge" to TaskFlowTypography.bodyLarge,
        "bodyMedium" to TaskFlowTypography.bodyMedium,
        "bodySmall" to TaskFlowTypography.bodySmall,
        "labelLarge" to TaskFlowTypography.labelLarge,
        "labelMedium" to TaskFlowTypography.labelMedium,
        "labelSmall" to TaskFlowTypography.labelSmall
      )

    allStyles.forEach { (name, style) ->
      assertEquals("$name must use FontFamily.SansSerif", FontFamily.SansSerif, style.fontFamily)
      assertTrue(
        "$name weight ${style.fontWeight} must be Normal, Medium, or SemiBold",
        style.fontWeight in allowedWeights
      )
    }
  }

  @Test
  fun lightAndDarkThemes_resolveRestrainedGoldTokensAndReadableContrast() {
    val themeModeState = mutableStateOf(AppThemeMode.LIGHT)
    var capturedColors: TaskFlowSemanticColors? = null
    var capturedBg: Color = Color.Unspecified
    var capturedOnBg: Color = Color.Unspecified
    var capturedSurface: Color = Color.Unspecified
    var capturedOnSurface: Color = Color.Unspecified

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = themeModeState.value) {
        capturedColors = taskFlowColors()
        capturedBg = MaterialTheme.colorScheme.background
        capturedOnBg = MaterialTheme.colorScheme.onBackground
        capturedSurface = MaterialTheme.colorScheme.surface
        capturedOnSurface = MaterialTheme.colorScheme.onSurface
      }
    }

    composeTestRule.waitForIdle()
    val light = capturedColors
    assertNotNull(light)
    assertFalse("Light theme should report isDark=false", light!!.isDark)
    assertEquals(GoldAccentLight, light.claimContainer)
    assertTrue("Light background must be bright", capturedBg.luminance() > 0.7f)
    assertTrue("Light onBackground must be dark for contrast", capturedOnBg.luminance() < 0.2f)
    assertTrue("Light onSurface must contrast surface", capturedSurface.luminance() - capturedOnSurface.luminance() > 0.6f)

    // Switch to Dark Mode
    composeTestRule.runOnIdle { themeModeState.value = AppThemeMode.DARK }
    composeTestRule.waitForIdle()

    val dark = capturedColors
    assertNotNull(dark)
    assertTrue("Dark theme should report isDark=true", dark!!.isDark)
    assertEquals(GoldAccentDark, dark.claimContainer)
    assertTrue("Dark background must be dark", capturedBg.luminance() < 0.15f)
    assertTrue("Dark onBackground must be bright for contrast", capturedOnBg.luminance() > 0.7f)
    assertTrue("Dark onSurface must contrast surface", capturedOnSurface.luminance() - capturedSurface.luminance() > 0.6f)
    assertTrue(
      "Dark goldText must remain bright on dark surfaces",
      dark.goldText.luminance() > 0.45f
    )
  }

  @Test
  fun keyUserScreens_renderCleanlyInBothLightAndDarkModes() {
    val sampleProfile =
      UserProfile(
        id = "u_phase2",
        userId = "u_phase2",
        displayName = "Aarav Mehta",
        email = "aarav@example.com",
        approvalStatus = "approved",
        balanceCoins = 1250L,
        eligibleCoins = 1250L,
        promotionalCoins = 0L
      )
    val sampleTask =
      TaskItem(
        id = "t_phase2",
        title = "Explore Finance App",
        appName = "FinTrack",
        category = "App Install",
        rewardCoins = 250L,
        rewardCents = coinsToCents(250L),
        isActive = true,
        status = TaskStatus.PENDING
      )
    val sampleTx =
      TransactionRecord(
        id = "tx_phase2",
        userId = "u_phase2",
        title = "Daily Check-In Bonus",
        amountCoins = 125L,
        type = TransactionType.DAILY_CHECKIN
      )
    val sampleWithdrawal =
      DemoWithdrawalRequest(
        id = "wd_phase2",
        requestId = "wd_phase2",
        userId = "u_phase2",
        amountCoins = 1250L,
        amountRupees = 10L,
        amountCents = 1000L,
        method = WithdrawalMethodType.UPI,
        upiId = "aarav@upi",
        status = WithdrawalStatus.COMPLETED,
        paymentReference = "UTR99887766"
      )
    val sampleTelegramTask =
      TelegramTaskItem(
        id = "tg_phase2",
        taskId = "tg_phase2",
        title = "Join TaskFlow Updates",
        rewardCoins = 150L,
        isActive = true
      )
    val verifiedTelegramState =
      TelegramUserTaskState(
        taskId = "tg_phase2",
        userId = "u_phase2",
        hasClickedJoin = true,
        isMembershipVerified = true,
        isClaimed = false
      )

    // Verify 125 TF = ₹1 math remains untouched
    assertEquals(125L, COINS_PER_RUPEE)
    assertEquals(100L, coinsToCents(125L))

    val activeScreen = mutableStateOf(0)
    val activeMode = mutableStateOf(AppThemeMode.LIGHT)

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = activeMode.value) {
        when (activeScreen.value) {
          0 ->
            UserHomeScreen(
              profileState = UiState.Success(sampleProfile),
              tasksState = UiState.Success(listOf(sampleTask)),
              isAdminAuthorized = false,
              onSelectTask = {},
              onNavigateToTracking = {},
              onSwitchToAdmin = {},
              dailyCheckInState = DailyCheckInState(currentStreak = 3, canCheckInToday = true, nextClaimDay = 4),
              transactionsState = UiState.Success(listOf(sampleTx)),
              leaderboardState = UiState.Success(listOf(sampleProfile))
            )
          1 ->
            DedicatedDailyCheckInScreen(
              dailyCheckInState = DailyCheckInState(currentStreak = 3, canCheckInToday = true, nextClaimDay = 4),
              onClaimDay = {},
              onBack = {}
            )
          2 ->
            SpinAndEarnScreen(
              onBack = {},
              spinState = SpinEarnUiState(freeSpinAvailable = true)
            )
          3 ->
            ScratchCardScreen(
              uiState =
                ScratchCardUiState(
                  dailyState = ScratchDailyState(scratchesCompletedToday = 0L),
                  activeSession = ScratchCardSession(cardId = "s1", rewardCoins = 50L, status = "ACTIVE")
                ),
              onBack = {},
              onWatchAd = {},
              onScratchRevealed = {},
              onClaimReward = {},
              onDismissCongratulations = {}
            )
          4 ->
            UserWalletScreen(
              profileState = UiState.Success(sampleProfile),
              tasksState = UiState.Success(listOf(sampleTask)),
              withdrawalRequestsState = UiState.Success(listOf(sampleWithdrawal)),
              currentUserId = "u_phase2",
              transactionsState = UiState.Success(listOf(sampleTx))
            )
          5 ->
            WithdrawalHistoryScreen(
              requests = listOf(sampleWithdrawal),
              onBack = {}
            )
          6 ->
            OfferWallScreen(
              tasks = listOf(sampleTask),
              onBack = {}
            )
          else ->
            TelegramJoinTaskCard(
              task = sampleTelegramTask,
              taskState = verifiedTelegramState,
              onJoin = {},
              onVerify = {},
              onClaim = {}
            )
        }
      }
    }

    // Exercise every screen in BOTH Light and Dark themes
    val modes = listOf(AppThemeMode.LIGHT, AppThemeMode.DARK)
    modes.forEach { mode ->
      composeTestRule.runOnIdle {
        activeMode.value = mode
        activeScreen.value = 0
      }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("user_home_screen").assertIsDisplayed()
      composeTestRule.onNodeWithTag("home_daily_checkin_card").assertIsDisplayed()
      composeTestRule.onNodeWithTag("home_offer_wall_card").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 1 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("dedicated_daily_checkin_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 2 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("spin_and_earn_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 3 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("scratch_card_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 4 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("user_wallet_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 5 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("withdrawal_history_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 6 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("offer_wall_screen").assertIsDisplayed()

      composeTestRule.runOnIdle { activeScreen.value = 7 }
      composeTestRule.waitForIdle()
      composeTestRule.onNodeWithTag("user_telegram_claim_button").assertIsDisplayed()
      composeTestRule.onNodeWithText("CLAIM").assertIsDisplayed()
    }
  }
}

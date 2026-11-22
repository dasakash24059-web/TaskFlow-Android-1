package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdminConversionReviewAction
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.LeaderboardPeriod
import com.example.data.model.PublicLeaderboardSnapshot
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatRewardInr
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.OfferWallScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * P0 Master Remediation Android verification suite covering:
 * - C-03: Phase 3E server-only callable fail-closed behavior + removal of 500 TF local promo fallback
 * - C-04: Canonical 125 TF = ₹1.00 = 100 paise manual task reward conversion, legacy compatibility, and UI parity
 * - C-05: Server-authoritative public leaderboard client integration + zero cross-user transaction reads
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class P0RemediationTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      ensureFirebaseInitialized(context)
    }
    return FirebaseFirestore.getInstance()
  }

  private fun createFunctionsException(
    code: FirebaseFunctionsException.Code,
    message: String
  ): FirebaseFunctionsException {
    val ctor =
      FirebaseFunctionsException::class.java.getDeclaredConstructor(
        String::class.java,
        FirebaseFunctionsException.Code::class.java,
        Any::class.java
      )
    ctor.isAccessible = true
    return ctor.newInstance(message, code, null)
  }

  // =========================================================================
  // C-03: PHASE 3E CALLABLES FAIL CLOSED + NO LOCAL FALLBACKS
  // =========================================================================

  @Test
  fun c03_phase3ECallables_succeedOnValidServerResponse_andFailClosedOnEveryErrorMode() = runBlocking {
    val firestore = createTestFirestore()

    // 1. Valid server responses succeed
    val successClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, _ ->
          when (fnName) {
            "listUserEligibleTrackedCampaigns" ->
              mapOf(
                "items" to listOf(
                  mapOf(
                    "campaignId" to "camp_ok",
                    "title" to "Verified Offer",
                    "rewardTf" to 250L,
                    "rewardCents" to 200L,
                    "verificationMode" to "AFFILIATE_POSTBACK",
                    "userStatus" to "AVAILABLE",
                    "canClick" to true,
                    "remainingSubmissions" to 10L
                  )
                ),
                "totalCount" to 1,
                "hasMore" to false,
                "accountApproved" to true
              )
            "listUserTrackedRewardActivity" ->
              mapOf(
                "items" to listOf(
                  mapOf(
                    "activityId" to "act_0123456789abcdef01234567",
                    "campaignId" to "camp_ok",
                    "campaignTitle" to "Verified Offer",
                    "status" to "APPROVED",
                    "statusLabel" to "Approved",
                    "rewardTf" to 250L,
                    "rewardCents" to 200L,
                    "createdAtMillis" to 1_760_000_000_000L
                  )
                ),
                "counts" to mapOf("pending" to 0, "approved" to 1, "rejected" to 0, "reversed" to 0),
                "totalCount" to 1,
                "hasMore" to false
              )
            "adminListCampaignConversions" ->
              mapOf(
                "items" to emptyList<Map<String, Any>>(),
                "totalCount" to 0,
                "hasMore" to false
              )
            "reviewCampaignConversion" ->
              mapOf(
                "success" to true,
                "moderationId" to "mod_11223344556677889900aabb",
                "state" to "APPROVED",
                "rewardTf" to 250L,
                "rewardCents" to 200L,
                "duplicate" to false
              )
            else -> error("Unexpected callable $fnName")
          }
        }
      )
    val successRepo =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = successClient,
        authenticatedUidProvider = { "uid_1" }
      )
    assertTrue(successRepo.listUserEligibleTrackedCampaigns().isSuccess)
    assertTrue(successRepo.listUserTrackedRewardActivity().isSuccess)
    assertTrue(successRepo.adminListCampaignConversions().isSuccess)
    assertTrue(
      successRepo
        .adminReviewCampaignConversion(
          moderationId = "mod_11223344556677889900aabb",
          action = AdminConversionReviewAction.APPROVE,
          reason = "Verified conversion proof"
        )
        .isSuccess
    )

    // 2. Every failure mode MUST fail closed (never fake success, never empty-success list)
    val errorCases: List<Pair<String, suspend () -> Nothing>> = listOf(
      "NOT_FOUND" to { throw createFunctionsException(FirebaseFunctionsException.Code.NOT_FOUND, "NOT_FOUND") },
      "UNAVAILABLE" to { throw createFunctionsException(FirebaseFunctionsException.Code.UNAVAILABLE, "UNAVAILABLE") },
      "DEADLINE_EXCEEDED" to {
        throw createFunctionsException(FirebaseFunctionsException.Code.DEADLINE_EXCEEDED, "DEADLINE_EXCEEDED")
      },
      "TIMEOUT" to {
        withTimeout(1L) { delay(500L) }
        error("unreachable")
      },
      "PERMISSION_DENIED" to {
        throw createFunctionsException(FirebaseFunctionsException.Code.PERMISSION_DENIED, "Admin required")
      },
      "INTERNAL" to { throw createFunctionsException(FirebaseFunctionsException.Code.INTERNAL, "Internal failure") },
      "FAILED_PRECONDITION" to {
        throw createFunctionsException(
          FirebaseFunctionsException.Code.FAILED_PRECONDITION,
          "Stale conversion state"
        )
      }
    )

    for ((label, thrower) in errorCases) {
      val failingClient =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { _, _ -> thrower() }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = failingClient,
          authenticatedUidProvider = { "uid_1" }
        )

      val r1 = repo.listUserEligibleTrackedCampaigns()
      assertTrue("listUserEligibleTrackedCampaigns must fail closed on $label", r1.isFailure)
      assertTrue(r1.exceptionOrNull()?.message.orEmpty().isNotBlank())

      val r2 = repo.listUserTrackedRewardActivity()
      assertTrue("listUserTrackedRewardActivity must fail closed on $label", r2.isFailure)
      assertTrue(r2.exceptionOrNull()?.message.orEmpty().isNotBlank())

      val r3 = repo.adminListCampaignConversions()
      assertTrue("adminListCampaignConversions must fail closed on $label", r3.isFailure)
      assertTrue(r3.exceptionOrNull()?.message.orEmpty().isNotBlank())

      val r4 =
        repo.adminReviewCampaignConversion(
          moderationId = "mod_11223344556677889900aabb",
          action = AdminConversionReviewAction.APPROVE,
          reason = "Verified conversion proof"
        )
      assertTrue("reviewCampaignConversion must fail closed on $label (never fake success)", r4.isFailure)
      assertTrue(r4.exceptionOrNull()?.message.orEmpty().isNotBlank())
    }
  }

  @Test
  fun c03_promoCodeRedemption_neverCredits500TfLocally_underServerFailures() = runBlocking {
    val firestore = createTestFirestore()
    val errorCodes = listOf(
      FirebaseFunctionsException.Code.NOT_FOUND,
      FirebaseFunctionsException.Code.UNAVAILABLE,
      FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
      FirebaseFunctionsException.Code.INTERNAL,
      FirebaseFunctionsException.Code.FAILED_PRECONDITION
    )
    val testCodes = listOf("ARBITRARY999", "INVALID_CODE", "EXPIRED2025")

    for (code in errorCodes) {
      val failingClient =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          promoCallableInvoker = { _, _ ->
            throw createFunctionsException(code, "Simulated $code")
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = failingClient,
          authenticatedUidProvider = { "user_promo_test" }
        )
      for (promoCode in testCodes) {
        val res = repo.redeemPromoCode(promoCode)
        assertTrue(
          "Promo '$promoCode' must fail closed under $code (no 500 TF local fallback)",
          res.isFailure
        )
      }
    }
  }

  @Test
  fun c03_rewardAndAdminCallables_alwaysFailClosedWithoutLocalFallback() = runBlocking {
    val firestore = createTestFirestore()
    val failingClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, _ ->
          throw createFunctionsException(
            FirebaseFunctionsException.Code.NOT_FOUND,
            "$fnName not deployed"
          )
        }
      )

    fun assertFails(label: String, block: suspend () -> Unit) = runBlocking {
      val result = runCatching { block() }
      assertTrue("$label must fail closed on NOT_FOUND", result.isFailure)
    }

    assertFails("claimDailyCheckIn") { failingClient.claimDailyCheckIn(1) }
    assertFails("createSpinSession") { failingClient.createSpinSession() }
    assertFails("claimSpinReward") { failingClient.claimSpinReward("sess_1") }
    assertFails("createScratchCardSession") { failingClient.createScratchCardSession() }
    assertFails("claimScratchCardReward") { failingClient.claimScratchCardReward("card_1") }
    assertFails("creditManualBonus") {
      failingClient.creditManualBonus("user_1", 125L, "Bonus", "req_1")
    }
    assertFails("getPublicLeaderboard") { failingClient.getPublicLeaderboard() }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun c03_viewModel_surfacesGenuineErrorsForPhase3EDiscoveryAndActivity() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val firestore = createTestFirestore()
      val failingClient =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { _, _ ->
            throw createFunctionsException(
              FirebaseFunctionsException.Code.UNAVAILABLE,
              "Backend temporarily unavailable"
            )
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = failingClient,
          authenticatedUidProvider = { "uid_err_test" }
        )
      val vm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "uid_err_test",
          currentUserEmail = "err_test@example.com",
          currentUserDisplayName = "Error Tester",
          initialPortal = PortalLoginTarget.USER_APP
        )

      vm.loadUserEligibleTrackedCampaigns()
      vm.loadUserTrackedRewardActivity()
      advanceUntilIdle()

      assertNotNull(
        "Discovery failure must surface a genuine error message",
        vm.userTrackedCampaignsState.value.error
      )
      assertFalse(vm.userTrackedCampaignsState.value.isLoading)

      assertNotNull(
        "Reward activity failure must surface a genuine error message",
        vm.userTrackedActivityState.value.error
      )
      assertFalse(vm.userTrackedActivityState.value.isLoading)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // =========================================================================
  // C-04: CANONICAL 125 TF = ₹1.00 = 100 PAISE TASK REWARD ACCOUNTING
  // =========================================================================

  @Test
  fun c04_canonicalTaskRewardConversionMatrix_exactParityAcrossAllTiers() {
    assertEquals(125L, COINS_PER_RUPEE)

    val tiers = listOf(
      Triple(125L, 100L, "₹1.00"),
      Triple(250L, 200L, "₹2.00"),
      Triple(500L, 400L, "₹4.00"),
      Triple(1000L, 800L, "₹8.00")
    )

    for ((expectedTf, expectedPaise, expectedInr) in tiers) {
      assertEquals("coinsToCents($expectedTf)", expectedPaise, coinsToCents(expectedTf))
      assertEquals("centsToCoins($expectedPaise)", expectedTf, centsToCoins(expectedPaise))
      assertEquals("formatRewardInr($expectedPaise)", expectedInr, formatRewardInr(expectedPaise))

      // Newly stored canonical TaskItem
      val canonicalTask =
        TaskItem(
          id = "task_$expectedTf",
          title = "Task $expectedTf TF",
          rewardCoins = expectedTf,
          rewardCents = expectedPaise,
          status = TaskStatus.PENDING
        )
      assertEquals(expectedTf, canonicalTask.getEffectiveRewardCoins())
      assertEquals(expectedPaise, coinsToCents(canonicalTask.getEffectiveRewardCoins()))
      assertEquals(expectedInr, formatRewardInr(coinsToCents(canonicalTask.getEffectiveRewardCoins())))

      // Legacy pre-fix buggy TaskItem (where TF was erroneously stored verbatim in rewardCents)
      val legacyBuggyTask =
        TaskItem(
          id = "legacy_buggy_$expectedTf",
          title = "Legacy Buggy $expectedTf TF",
          rewardCoins = expectedTf,
          rewardCents = expectedTf, // pre-fix bug: 125 TF stored as 125 paise
          status = TaskStatus.PENDING
        )
      assertEquals(expectedTf, legacyBuggyTask.getEffectiveRewardCoins())
      assertEquals(expectedPaise, coinsToCents(legacyBuggyTask.getEffectiveRewardCoins()))
      assertEquals(expectedInr, formatRewardInr(coinsToCents(legacyBuggyTask.getEffectiveRewardCoins())))

      // Legacy paise-only TaskItem (rewardCoins == 0, rewardCents == paise)
      val legacyPaiseOnlyTask =
        TaskItem(
          id = "legacy_paise_$expectedTf",
          title = "Legacy Paise $expectedTf",
          rewardCoins = 0L,
          rewardCents = expectedPaise,
          status = TaskStatus.PENDING
        )
      assertEquals(expectedTf, legacyPaiseOnlyTask.getEffectiveRewardCoins())
      assertEquals(expectedPaise, coinsToCents(legacyPaiseOnlyTask.getEffectiveRewardCoins()))
      assertEquals(expectedInr, formatRewardInr(coinsToCents(legacyPaiseOnlyTask.getEffectiveRewardCoins())))
    }
  }

  @Test
  fun c04_offerWallScreen_displaysExactTfWithout25PercentInflation() {
    val task125 =
      TaskItem(
        id = "offer_125",
        title = "Survey Offer 125",
        description = "Complete quick survey",
        category = "Survey",
        rewardCoins = 125L,
        rewardCents = 100L,
        isActive = true,
        status = TaskStatus.PENDING
      )
    val legacyBuggy250 =
      TaskItem(
        id = "offer_250_legacy",
        title = "Legacy Offer 250",
        description = "Legacy stored task",
        category = "App Install",
        rewardCoins = 250L,
        rewardCents = 250L, // pre-fix buggy rewardCents: old code rendered (250*125)/100 = 312 TF!
        isActive = true,
        status = TaskStatus.PENDING
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        OfferWallScreen(
          tasks = listOf(task125, legacyBuggy250),
          onBack = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("offer_wall_screen")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("offer_card_offer_125"))
    composeTestRule.onNodeWithText("125", substring = true).assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithText("156", substring = true).fetchSemanticsNodes().isEmpty())

    composeTestRule
      .onNodeWithTag("offer_wall_screen")
      .performScrollToNode(androidx.compose.ui.test.hasTestTag("offer_card_offer_250_legacy"))
    composeTestRule.onNodeWithText("250", substring = true).assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithText("312", substring = true).fetchSemanticsNodes().isEmpty())
  }

  // =========================================================================
  // C-05: SERVER-AUTHORITATIVE LEADERBOARD & ZERO CROSS-USER TX QUERIES
  // =========================================================================

  @Test
  fun c05_publicLeaderboardCallable_parsesAllPeriods_andCrossUserTxObserverIsNeutralized() = runBlocking {
    val firestore = createTestFirestore()
    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, _ ->
          assertEquals("getPublicLeaderboard", fnName)
          mapOf(
            "success" to true,
            "generatedAtMillis" to 1_760_000_000_000L,
            "allTime" to listOf(
              mapOf(
                "rank" to 1,
                "userId" to "u_alice",
                "displayName" to "Alice",
                "earnedCoins" to 1200L,
                "completedTasksCount" to 3
              )
            ),
            "thisMonth" to listOf(
              mapOf(
                "rank" to 1,
                "userId" to "u_alice",
                "displayName" to "Alice",
                "earnedCoins" to 900L,
                "completedTasksCount" to 3
              )
            ),
            "thisWeek" to listOf(
              mapOf(
                "rank" to 1,
                "userId" to "u_alice",
                "displayName" to "Alice",
                "earnedCoins" to 500L,
                "completedTasksCount" to 3
              )
            )
          )
        }
      )
    val repo =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "u_bob" }
      )

    val snapshot: PublicLeaderboardSnapshot = repo.getPublicLeaderboard().getOrThrow()
    assertEquals(1_760_000_000_000L, snapshot.generatedAtMillis)
    assertEquals(1200L, snapshot.entriesFor(LeaderboardPeriod.ALL_TIME).single().earnedCoins)
    assertEquals(900L, snapshot.entriesFor(LeaderboardPeriod.THIS_MONTH).single().earnedCoins)
    assertEquals(500L, snapshot.entriesFor(LeaderboardPeriod.THIS_WEEK).single().earnedCoins)

    // Cross-user transaction listener MUST emit empty list immediately (no /transactions collection scan)
    val crossUserTxs = repo.observeCreditedTransactionsForLeaderboard().first()
    assertTrue("Cross-user leaderboard transaction observer must be neutralized", crossUserTxs.isEmpty())
  }
}

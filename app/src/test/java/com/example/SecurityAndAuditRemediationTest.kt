package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppVersionInfo
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.DailyCheckInState
import com.example.data.model.ScratchCardSession
import com.example.data.model.ScratchCardUiState
import com.example.data.model.ScratchDailyState
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.FirebaseStorageProofUploader
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.ADMIN_10_MAIN_SECTIONS
import com.example.ui.admin.AdminControlCenterHomeScreen
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.components.AppUpdateDialog
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.DedicatedDailyCheckInScreen
import com.example.ui.user.OfferWallScreen
import com.example.ui.user.ScratchCardScreen
import com.example.ui.user.UserWalletScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.example.ui.viewmodel.UiState
import com.example.util.AppUpdateManager
import com.example.util.UnityAdsHelper
import com.example.util.activeAppCheckProviderNameForTesting
import com.example.util.configureAppCheck
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Targeted regression suite covering the H-01..H-05, M-04, M-07A..C, L-02, and Phase F.3 audit fixes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SecurityAndAuditRemediationTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      ensureFirebaseInitialized(context)
    }
    return FirebaseFirestore.getInstance()
  }

  private fun createTestRepo(uid: String): TaskFlowRepository {
    val firestore = createTestFirestore()
    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { _, _ -> emptyMap<String, Any?>() }
      )
    return TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { uid }
    )
  }

  // =========================================================================
  // H-01: EMAIL_VERIFIED REQUIREMENT FOR BOOTSTRAPPED ADMIN
  // =========================================================================

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun h01_unverifiedBootstrappedAdminEmail_doesNotEnterAdminPortalOrAuthorizeAdmin() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val repo = createTestRepo("uid_unverified_admin")
      val unverifiedVm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "uid_unverified_admin",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Unverified Bootstrap",
          initialPortal = PortalLoginTarget.USER_APP,
          currentUserEmailVerified = false
        )
      assertEquals(PortalLoginTarget.USER_APP, unverifiedVm.activePortal.value)
      assertFalse(unverifiedVm.isAdminAuthorized.value)

      val verifiedVm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "uid_verified_admin",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Verified Bootstrap",
          initialPortal = PortalLoginTarget.USER_APP,
          currentUserEmailVerified = true
        )
      assertEquals(PortalLoginTarget.ADMIN_DASHBOARD, verifiedVm.activePortal.value)
      assertTrue(verifiedVm.isAdminAuthorized.value)
    } finally {
      Dispatchers.resetMain()
    }
  }

  // =========================================================================
  // H-02: DEBUG VS RELEASE APP CHECK PROVIDER INSTALLER
  // =========================================================================

  @Test
  fun h02_debugBuildUsesDebugAppCheckProvider_andConfigureIsRobolectricSafe() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("DEBUG", activeAppCheckProviderNameForTesting())
    assertFalse(configureAppCheck(context, null))
  }

  // =========================================================================
  // H-03: REWARD SESSION S2S CLIENT VALIDATION FAILS CLOSED
  // =========================================================================

  @Test
  fun h03_awaitSessionVerified_rejectsBlankAndClientPrefixedSessions() = runBlocking {
    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = createTestFirestore(),
        rewardCallableInvoker = { _, _ -> emptyMap<String, Any?>() }
      )

    val blankUser = runCatching { client.awaitSessionVerified("", "sess_valid") }
    assertTrue(blankUser.exceptionOrNull() is IllegalArgumentException)

    val blankSession = runCatching { client.awaitSessionVerified("uid_1", "   ") }
    assertTrue(blankSession.exceptionOrNull() is IllegalArgumentException)

    val fakeClientSession = runCatching { client.awaitSessionVerified("uid_1", "ad_client_fake") }
    assertTrue(fakeClientSession.exceptionOrNull() is IllegalArgumentException)
  }

  // =========================================================================
  // H-04: UNITY ADS TEST ENDPOINTS & FALLBACK ID GATED TO DEBUG BUILDS
  // =========================================================================

  @Test
  fun h04_unityAds_gatesTestModeAndFallbackGameIdToDebugOnly_whilePreservingProductionIds() {
    assertEquals("800430673", UnityAdsHelper.GAME_ID)
    assertEquals("BP_Rewarded_Android", UnityAdsHelper.REWARDED_PLACEMENT_ID)
    assertEquals("BP_Banner_Android", UnityAdsHelper.BANNER_PLACEMENT_ID)
    assertEquals("BP_Interstitial_Android", UnityAdsHelper.INTERSTITIAL_PLACEMENT_ID)

    try {
      UnityAdsHelper.isDebugBuildOverride = false
      assertFalse(UnityAdsHelper.TEST_MODE)
      assertFalse(UnityAdsHelper.shouldUseFallbackGameIdForCurrentBuild())
      assertFalse(UnityAdsHelper.shouldUseTestEndpointForCurrentBuild(true))

      UnityAdsHelper.isDebugBuildOverride = true
      assertTrue(UnityAdsHelper.TEST_MODE)
      assertTrue(UnityAdsHelper.shouldUseFallbackGameIdForCurrentBuild())
      assertTrue(UnityAdsHelper.shouldUseTestEndpointForCurrentBuild(true))
      assertFalse(UnityAdsHelper.shouldUseTestEndpointForCurrentBuild(false))
    } finally {
      UnityAdsHelper.isDebugBuildOverride = null
    }
  }

  // =========================================================================
  // H-05: APK SIGNATURE VERIFICATION FAIL-CLOSED & OPTIONAL UPDATE DISMISSAL
  // =========================================================================

  @Test
  fun h05_verifyApkSignatures_failsClosedOnMissingEmptyAndCorruptedFiles() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertFalse(
      AppUpdateManager.verifyApkSignatures(
        context,
        File(context.cacheDir, "does_not_exist.apk").absolutePath
      )
    )

    val emptyApk = File(context.cacheDir, "empty_update.apk").apply { writeBytes(ByteArray(0)) }
    val corruptApk =
      File(context.cacheDir, "corrupt_update.apk").apply { writeText("not-a-valid-apk-archive") }
    try {
      assertFalse(AppUpdateManager.verifyApkSignatures(context, emptyApk.absolutePath))
      assertFalse(AppUpdateManager.verifyApkSignatures(context, corruptApk.absolutePath))
    } finally {
      emptyApk.delete()
      corruptApk.delete()
    }
  }

  @Test
  fun h05_appUpdateDialog_allowsDismissForOptionalUpdates_andBlocksForForcedUpdates() {
    var dismissClicks = 0
    val optionalUpdate =
      AppVersionInfo(
        versionCode = 9999,
        versionName = "9.9.9",
        apkUrl = "https://github.com/dasakash24059-web/TaskFlow-Android-1/releases/latest/download/TaskFlow.apk",
        releaseNotes = "Optional bug fixes",
        forceUpdate = false
      )

    composeTestRule.setContent {
      var showOptional by remember { mutableStateOf(true) }
      TaskFlowTheme {
        if (showOptional) {
          AppUpdateDialog(
            versionInfo = optionalUpdate,
            onUpdateNow = {},
            onDismiss = {
              dismissClicks++
              showOptional = false
            }
          )
        }
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithText("This is a required update", substring = true)
        .fetchSemanticsNodes()
        .isEmpty()
    )
    composeTestRule.onNodeWithTag("update_later_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, dismissClicks)
    assertTrue(
      composeTestRule.onAllNodesWithTag("update_dialog").fetchSemanticsNodes().isEmpty()
    )
  }

  @Test
  fun h05_appUpdateDialog_forcedUpdateHidesLaterButtonAndShowsRequiredNotice() {
    val mandatoryUpdate =
      AppVersionInfo(
        versionCode = 9999,
        versionName = "9.9.9",
        apkUrl = "https://github.com/dasakash24059-web/TaskFlow-Android-1/releases/latest/download/TaskFlow.apk",
        releaseNotes = "Critical security update",
        forceUpdate = true
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        AppUpdateDialog(
          versionInfo = mandatoryUpdate,
          onUpdateNow = {},
          onDismiss = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithTag("update_later_button").fetchSemanticsNodes().isEmpty()
    )
    composeTestRule.onNodeWithText("This is a required update", substring = true).assertIsDisplayed()
  }

  // =========================================================================
  // M-04 & M-07A: WALLET TOTAL EARNINGS FALLBACK & SPENDABLE POOL LABELS
  // =========================================================================

  @Test
  fun m04_m07a_userWallet_usesUpdatedLabels_andNeverDoubleCountsApprovedTasksWithBalance() {
    val profile =
      UserProfile(
        id = "u_wallet_1",
        userId = "u_wallet_1",
        balanceCoins = 500L,
        balanceCents = 240L,
        eligibleCoins = 300L,
        promotionalCoins = 200L
      )
    val approvedTask =
      TaskItem(
        id = "t_done_1",
        title = "Completed Survey",
        rewardCoins = 300L,
        rewardCents = 240L,
        status = TaskStatus.APPROVED,
        assignedUserId = "u_wallet_1"
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserWalletScreen(
          profileState = UiState.Success(profile),
          tasksState = UiState.Success(listOf(approvedTask)),
          withdrawalRequestsState = UiState.Success(emptyList()),
          currentUserId = "u_wallet_1",
          transactionsState = UiState.Success(emptyList())
        )
      }
    }
    composeTestRule.waitForIdle()

    // Fallback when transactions are empty must be maxOf(300, 500) = 500 TF, never 300 + 500 = 800 TF.
    composeTestRule.onNodeWithTag("wallet_total_earnings").assertTextEquals("500 TF")
    composeTestRule.onNodeWithText("Task Earnings").assertIsDisplayed()
    composeTestRule.onNodeWithText("Bonus & Rewards").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithText("Eligible Withdrawable").fetchSemanticsNodes().isEmpty()
    )
    assertTrue(
      composeTestRule.onAllNodesWithText("Promotional Coins").fetchSemanticsNodes().isEmpty()
    )
  }

  // =========================================================================
  // M-07B: DYNAMIC ADMIN MODULE COUNT
  // =========================================================================

  @Test
  fun m07b_adminControlCenter_displaysActualDynamicModuleCount() {
    composeTestRule.setContent {
      TaskFlowTheme {
        AdminControlCenterHomeScreen(
          users = emptyList(),
          tasks = emptyList(),
          campaigns = emptyList(),
          reviewTasks = emptyList(),
          withdrawalRequests = emptyList(),
          transactions = emptyList(),
          activityLogs = emptyList(),
          onSelectSection = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithText("${ADMIN_10_MAIN_SECTIONS.size} Modules")
      .assertIsDisplayed()
    assertTrue(composeTestRule.onAllNodesWithText("10 Modules").fetchSemanticsNodes().isEmpty())
  }

  // =========================================================================
  // M-07C: OFFER WALL EXCLUDES EXHAUSTED QUOTA TASKS
  // =========================================================================

  @Test
  fun m07c_offerWall_hidesQuotaExhaustedTasks() {
    val availableTask =
      TaskItem(
        id = "active_1",
        title = "Available Offer",
        rewardCoins = 125L,
        rewardCents = 100L,
        isActive = true,
        status = TaskStatus.PENDING,
        targetSubmissions = 10L,
        completedSubmissions = 4L
      )
    val exhaustedTask =
      TaskItem(
        id = "exhausted_2",
        title = "Full Quota Offer",
        rewardCoins = 250L,
        rewardCents = 200L,
        isActive = true,
        status = TaskStatus.PENDING,
        targetSubmissions = 10L,
        completedSubmissions = 10L
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        OfferWallScreen(
          tasks = listOf(availableTask, exhaustedTask),
          onBack = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("offer_card_active_1").assertIsDisplayed()
    assertTrue(
      composeTestRule.onAllNodesWithTag("offer_card_exhausted_2").fetchSemanticsNodes().isEmpty()
    )
  }

  // =========================================================================
  // L-02: CONSISTENT SINGLE TF REWARD UNITS IN SCRATCH CARD & CHECK-IN
  // =========================================================================

  @Test
  fun l02_scratchCardAndDailyCheckIn_renderCanonicalTfUnitWithoutDuplicateCoinsSuffix() {
    val state =
      ScratchCardUiState(
        dailyState = ScratchDailyState(dateKey = "2026-11-23"),
        activeSession = ScratchCardSession(cardId = "c1", rewardCoins = 40L),
        revealedCoins = 40L,
        isScratched = true,
        showCongratulations = true
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        ScratchCardScreen(
          uiState = state,
          onBack = {},
          onWatchAd = {},
          onScratchRevealed = {},
          onClaimReward = {},
          onDismissCongratulations = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("scratch_reward_amount_text").assertTextEquals("+40 TF")
    assertTrue(
      composeTestRule.onAllNodesWithText("TF Coins", substring = true).fetchSemanticsNodes().isEmpty()
    )
  }

  @Test
  fun l02_dedicatedDailyCheckInScreen_formatsDayRewardsWithTfUnit() {
    val checkInState =
      DailyCheckInState(
        currentStreak = 0,
        nextClaimDay = 1,
        canCheckInToday = true,
        nextRewardCoins = 25L
      )
    composeTestRule.setContent {
      TaskFlowTheme {
        DedicatedDailyCheckInScreen(
          dailyCheckInState = checkInState,
          onClaimDay = {},
          onBack = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("dedicated_daily_checkin_screen")
      .performScrollToNode(hasTestTag("daily_checkin_30day_grid"))
    composeTestRule.onNodeWithTag("day_1_card").assertTextContains("25 TF", substring = true)
    assertTrue(
      composeTestRule.onAllNodesWithText("Coins", substring = false).fetchSemanticsNodes().isEmpty()
    )
  }

  // =========================================================================
  // PHASE F.3: SERVER-TICKETED PROOF UPLOADER & FINALIZATION PIPELINE
  // =========================================================================

  @Test
  fun f3_firebaseStorageProofUploader_requestsServerTicketWithSha256_andUploadsViaSignedPut(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val tempImgFile = File(context.cacheDir, "phase_f3_valid_proof.png")
    val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
      eraseColor(Color.BLUE)
    }
    FileOutputStream(tempImgFile).use { out ->
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }

    var capturedFnName = ""
    var capturedPayload: Map<*, *> = emptyMap<String, Any?>()
    var capturedPutUrl = ""
    var capturedPutBytes = ByteArray(0)
    var capturedPutHeaders: Map<String, String> = emptyMap()

    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = createTestFirestore(),
        rewardCallableInvoker = { fnName, payload ->
          capturedFnName = fnName
          capturedPayload = (payload as? Map<*, *>) ?: emptyMap<String, Any?>()
          mapOf(
            "ticketId" to "tkt_1763890000123_abcdef123456",
            "storagePath" to "submissions/uid_f3_1/task_f3_1/proof_1763890000123_tkt_1763890000123_abcdef123456.jpg",
            "uploadUrl" to "https://storage.googleapis.com/taskflow-8852d.firebasestorage.app/signed_put_test",
            "uploadMethod" to "PUT",
            "uploadMode" to "SIGNED_PUT",
            "expiresAtMillis" to 1763890300123L,
            "requiredHeaders" to mapOf(
              "Content-Type" to "image/jpeg",
              "x-goog-if-generation-match" to "0",
              "x-goog-meta-uid" to "uid_f3_1",
              "x-goog-meta-task-id" to "task_f3_1",
              "x-goog-meta-ticket-id" to "tkt_1763890000123_abcdef123456"
            )
          )
        }
      )

    val uploader =
      FirebaseStorageProofUploader(
        rewardFunctionsClientProvider = { functionsClient },
        contextProvider = { context },
        signedUrlHttpPutExecutor = { url, bytes, headers, onProgress ->
          capturedPutUrl = url
          capturedPutBytes = bytes
          capturedPutHeaders = headers
          assertEquals("image/jpeg", headers["Content-Type"])
          onProgress(70)
        }
      )

    try {
      val progressSteps = mutableListOf<Int>()
      val uploaded =
        uploader.uploadProofPhoto(
          userId = "uid_f3_1",
          taskId = "task_f3_1",
          imageUri = Uri.fromFile(tempImgFile),
          onProgress = { progressSteps.add(it) }
        )

      assertEquals("issueTaskProofUploadTicket", capturedFnName)
      assertEquals("task_f3_1", capturedPayload["taskId"])
      assertEquals("image/jpeg", capturedPayload["contentType"])
      val sizeBytes = (capturedPayload["sizeBytes"] as Number).toLong()
      assertTrue(sizeBytes > 0L && sizeBytes <= 5L * 1024L * 1024L)
      val sha256Hex = capturedPayload["sha256Hex"] as String
      assertEquals(64, sha256Hex.length)

      assertEquals(
        "https://storage.googleapis.com/taskflow-8852d.firebasestorage.app/signed_put_test",
        capturedPutUrl
      )
      assertEquals(sizeBytes, capturedPutBytes.size.toLong())
      assertEquals("0", capturedPutHeaders["x-goog-if-generation-match"])
      assertEquals("tkt_1763890000123_abcdef123456", uploaded.ticketId)
      assertEquals("SIGNED_PUT", uploaded.uploadMode)
      assertTrue(uploaded.storagePath.startsWith("submissions/uid_f3_1/task_f3_1/"))
      assertTrue(progressSteps.contains(90))
    } finally {
      tempImgFile.delete()
    }
  }

  @Test
  fun f3_firebaseStorageProofUploader_failsClosedWhenTicketDeniedOrCorruptedImage(): Unit = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val corruptFile = File(context.cacheDir, "phase_f3_corrupt.bin").apply {
      writeText("not-an-image")
    }

    var ticketRequested = false
    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = createTestFirestore(),
        rewardCallableInvoker = { _, _ ->
          ticketRequested = true
          throw IllegalStateException("PERMISSION_DENIED: Account is pending approval")
        }
      )

    val uploader =
      FirebaseStorageProofUploader(
        rewardFunctionsClientProvider = { functionsClient },
        contextProvider = { context },
        signedUrlHttpPutExecutor = { _, _, _, _ ->
          throw AssertionError("Must never execute PUT when ticket or image validation fails")
        }
      )

    try {
      // 1. Corrupt/non-image file fails closed before requesting a ticket
      val corruptResult =
        runCatching {
          uploader.uploadProofPhoto(
            userId = "uid_pending_1",
            taskId = "task_1",
            imageUri = Uri.fromFile(corruptFile),
            onProgress = {}
          )
        }
      assertTrue(corruptResult.isFailure)
      assertFalse(ticketRequested)

      // 2. Valid image when server denies ticket (e.g. pending user) fails closed without direct upload fallback
      val validImgFile = File(context.cacheDir, "phase_f3_pending_user.png")
      val bmp = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
      }
      FileOutputStream(validImgFile).use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
      }
      val deniedResult =
        runCatching {
          uploader.uploadProofPhoto(
            userId = "uid_pending_1",
            taskId = "task_1",
            imageUri = Uri.fromFile(validImgFile),
            onProgress = {}
          )
        }
      assertTrue(deniedResult.isFailure)
      assertTrue(ticketRequested)
      validImgFile.delete()
    } finally {
      corruptFile.delete()
    }
  }
}

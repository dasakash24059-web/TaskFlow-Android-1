package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdminCampaignTrackingUiState
import com.example.data.model.CampaignItem
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.UserTrackedCampaignItem
import com.example.data.model.WatchEarnFlowStatus
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.FirebaseStorageProofUploader
import com.example.data.repository.SPARK_AD_PLAYBACK_COMPLETED_NO_CREDIT_MESSAGE
import com.example.data.repository.SPARK_PLAYBACK_SESSION_PREFIX
import com.example.data.repository.SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE
import com.example.data.repository.SparkFallbackDataSource
import com.example.data.repository.TaskFlowRepository
import com.example.data.repository.isCallableUndeployedError
import com.example.data.repository.isSparkPlaybackCorrelationId
import com.example.ui.admin.AdminCampaignTrackingSection
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctionsException
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SparkSafeRecoveryTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  private lateinit var context: Context
  private lateinit var firestore: FirebaseFirestore

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    if (FirebaseApp.getApps(context).isEmpty()) {
      FirebaseApp.initializeApp(
        context,
        FirebaseOptions.Builder()
          .setProjectId("demo-no-project")
          .setApplicationId("1:1234567890:android:abcdef123456")
          .setApiKey("AIzaSyDummyKeyForLocalUnitTesting123456")
          .build()
      )
    }
    firestore = FirebaseFirestore.getInstance()
  }

  private fun createFunctionsException(
    code: FirebaseFunctionsException.Code,
    message: String = "Function error: ${code.name}"
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

  private fun createSampleJpegUri(): Uri {
    val file = File(context.cacheDir, "spark_proof_test_${System.nanoTime()}.jpg")
    val bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(0xFF1E88E5.toInt())
    FileOutputStream(file).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
    }
    return Uri.fromFile(file)
  }

  private class FakeSparkFallbackDataSource(
    private val approvalStatusByUser: Map<String, String> = mapOf("user_spark_1" to "approved"),
    private val campaigns: List<CampaignItem> = emptyList(),
    private val tasks: List<TaskItem> = emptyList(),
    private val submissionsByUser: Map<String, List<SubmissionItem>> = emptyMap(),
    private val transactionsByUser: Map<String, List<TransactionRecord>> = emptyMap(),
    private val leaderboardUsers: List<UserProfile> = emptyList()
  ) : SparkFallbackDataSource {
    var leaderboardReadsCount = 0
    var submissionsRequestedForUsers = mutableListOf<String>()
    var transactionsRequestedForUsers = mutableListOf<String>()

    override suspend fun getUserApprovalStatus(userId: String): String? =
      approvalStatusByUser[userId]

    override suspend fun getActiveCampaigns(): List<CampaignItem> = campaigns

    override suspend fun getActiveTasks(): List<TaskItem> = tasks

    override suspend fun getUserSubmissions(userId: String): List<SubmissionItem> {
      submissionsRequestedForUsers.add(userId)
      return submissionsByUser[userId].orEmpty()
    }

    override suspend fun getUserTransactions(userId: String): List<TransactionRecord> {
      transactionsRequestedForUsers.add(userId)
      return transactionsByUser[userId].orEmpty()
    }

    override suspend fun getApprovedLeaderboardUsers(): List<UserProfile> {
      leaderboardReadsCount++
      return leaderboardUsers
    }
  }

  // =========================================================================
  // 1 & 2. MANUAL TASK PROOF UPLOAD FALLBACK ON NOT_FOUND / FAIL-CLOSED ON OTHER ERRORS
  // =========================================================================

  @Test
  fun manualProofUpload_fallsBackToInlineCompressedDataUri_onNotFoundAndUnimplemented() = runBlocking {
    val imageUri = createSampleJpegUri()
    for (code in listOf(
      FirebaseFunctionsException.Code.NOT_FOUND,
      FirebaseFunctionsException.Code.UNIMPLEMENTED
    )) {
      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, _ ->
          throw createFunctionsException(code, "$fnName is not deployed on Spark")
        }
      )
      val uploader = FirebaseStorageProofUploader(
        rewardFunctionsClientProvider = { client },
        contextProvider = { context }
      )
      val progressSteps = mutableListOf<Int>()
      val uploaded = uploader.uploadProofPhoto(
        userId = "user_spark_1",
        taskId = "task_install_1",
        imageUri = imageUri,
        onProgress = { progressSteps.add(it) }
      )

      assertEquals("INLINE_DATA_URI", uploaded.uploadMode)
      assertEquals("", uploaded.ticketId)
      assertTrue(uploaded.downloadUrl.startsWith("data:image/jpeg;base64,"))
      assertTrue(
        "Inline proof data URI must satisfy Firestore rules <= 350,000 chars",
        uploaded.downloadUrl.length <= 350_000
      )
      assertTrue(uploaded.storagePath.startsWith("inline_proof/user_spark_1/task_install_1/proof_"))
      assertTrue(progressSteps.contains(90))
    }
  }

  @Test
  fun manualProofUpload_failsClosed_onPermissionDeniedUnavailableAndDeadlineExceeded() = runBlocking {
    val imageUri = createSampleJpegUri()
    val nonFallbackCodes = listOf(
      FirebaseFunctionsException.Code.PERMISSION_DENIED,
      FirebaseFunctionsException.Code.UNAVAILABLE,
      FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
      FirebaseFunctionsException.Code.INTERNAL
    )

    for (code in nonFallbackCodes) {
      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { _, _ ->
          throw createFunctionsException(code, "Backend rejected with ${code.name}")
        }
      )
      val uploader = FirebaseStorageProofUploader(
        rewardFunctionsClientProvider = { client },
        contextProvider = { context }
      )
      val result = runCatching {
        uploader.uploadProofPhoto(
          userId = "user_spark_1",
          taskId = "task_install_1",
          imageUri = imageUri,
          onProgress = {}
        )
      }
      assertTrue("Must fail closed for ${code.name}", result.isFailure)
      assertFalse(isCallableUndeployedError(result.exceptionOrNull()))
    }
  }

  // =========================================================================
  // 3 & 4. USER CAMPAIGN DISCOVERY FALLBACK ON NOT_FOUND / FAIL-CLOSED ON OTHER ERRORS
  // =========================================================================

  @Test
  fun userCampaignDiscovery_fallsBackToActiveManualProofCampaignsAndTasks_onNotFound() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource(
      approvalStatusByUser = mapOf("user_spark_1" to "approved"),
      campaigns = listOf(
        CampaignItem(
          id = "camp_active_1",
          title = "Install NeoBank & Register",
          clientName = "NeoBank Partner",
          description = "Open an account and upload screenshot proof.",
          category = "App Install",
          status = "ACTIVE",
          budgetCents = 50000L,
          spentCents = 5000L,
          targetSubmissions = 100L,
          completedSubmissions = 12L,
          rewardPerTaskCents = 400L,
          linkedTaskId = "task_linked_1"
        )
      ),
      tasks = listOf(
        TaskItem(
          id = "task_linked_1",
          title = "Install NeoBank & Register",
          description = "Install and sign up",
          category = "App Install",
          rewardCents = 400L,
          rewardCoins = 500L,
          isActive = true,
          instructions = "Download app and take screenshot of dashboard"
        ),
        TaskItem(
          id = "task_standalone_2",
          title = "Rate TaskFlow on Store",
          description = "Leave an honest review and submit screenshot",
          category = "Review",
          rewardCents = 200L,
          rewardCoins = 250L,
          isActive = true
        )
      )
    )

    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { fnName, _ ->
        throw createFunctionsException(
          FirebaseFunctionsException.Code.NOT_FOUND,
          "$fnName not deployed"
        )
      }
    )
    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "user_spark_1" },
      sparkFallbackDataSource = fakeDataSource
    )

    val res = repo.listUserEligibleTrackedCampaigns()
    assertTrue("Expected Spark fallback to succeed on NOT_FOUND", res.isSuccess)
    val (approved, slice) = res.getOrThrow()
    assertTrue(approved)
    assertEquals(2, slice.items.size)
    assertTrue(slice.items.all { it.verificationMode == "MANUAL_PROOF" })
    assertEquals("camp_active_1", slice.items[0].campaignId)
    assertEquals("task_linked_1", slice.items[0].linkedTaskId)
    assertEquals(500L, slice.items[0].rewardTf)
    assertEquals("task_task_standalone_2", slice.items[1].campaignId)
    assertEquals("task_standalone_2", slice.items[1].linkedTaskId)
  }

  @Test
  fun userCampaignDiscovery_failsClosed_onPermissionDeniedUnavailableAndDeadlineExceeded() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource()
    for (code in listOf(
      FirebaseFunctionsException.Code.PERMISSION_DENIED,
      FirebaseFunctionsException.Code.UNAVAILABLE,
      FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
    )) {
      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { _, _ ->
          throw createFunctionsException(code, "Error ${code.name}")
        }
      )
      val repo = TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "user_spark_1" },
        sparkFallbackDataSource = fakeDataSource
      )
      val res = repo.listUserEligibleTrackedCampaigns()
      assertTrue("listUserEligibleTrackedCampaigns must fail closed on ${code.name}", res.isFailure)
    }
  }

  // =========================================================================
  // 5. USER REWARD ACTIVITY FALLBACK TO OWN SUBMISSIONS & TRANSACTIONS ONLY
  // =========================================================================

  @Test
  fun userTrackedRewardActivity_fallsBackOnlyToOwnSubmissionsAndTransactions_withOpaqueIds() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource(
      submissionsByUser = mapOf(
        "user_spark_1" to listOf(
          SubmissionItem(
            id = "sub_approved_101",
            taskId = "task_linked_1",
            taskTitle = "Install NeoBank & Register",
            userId = "user_spark_1",
            status = "APPROVED",
            rewardCents = 400L,
            rewardCoins = 500L,
            createdAt = Timestamp(1_700_000_100L, 0),
            updatedAt = Timestamp(1_700_000_500L, 0)
          ),
          SubmissionItem(
            id = "sub_pending_102",
            taskId = "task_standalone_2",
            taskTitle = "Rate TaskFlow on Store",
            userId = "user_spark_1",
            status = "PENDING",
            rewardCents = 200L,
            rewardCoins = 250L,
            createdAt = Timestamp(1_700_000_200L, 0)
          )
        ),
        "other_user_999" to listOf(
          SubmissionItem(
            id = "sub_other_user",
            taskId = "task_linked_1",
            userId = "other_user_999",
            status = "APPROVED",
            rewardCoins = 9999L
          )
        )
      ),
      transactionsByUser = mapOf(
        "user_spark_1" to listOf(
          TransactionRecord(
            id = "tx_101",
            userId = "user_spark_1",
            type = TransactionType.TASK_REWARD,
            amountCoins = 500L,
            status = "CREDITED",
            referenceId = "sub_approved_101"
          )
        )
      )
    )

    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { fnName, _ ->
        throw createFunctionsException(
          FirebaseFunctionsException.Code.UNIMPLEMENTED,
          "$fnName unimplemented"
        )
      }
    )
    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "user_spark_1" },
      sparkFallbackDataSource = fakeDataSource
    )

    val res = repo.listUserTrackedRewardActivity(status = "ALL")
    assertTrue(res.isSuccess)
    val (counts, slice) = res.getOrThrow()
    assertEquals(listOf("user_spark_1"), fakeDataSource.submissionsRequestedForUsers)
    assertEquals(listOf("user_spark_1"), fakeDataSource.transactionsRequestedForUsers)
    assertEquals(2, counts.total)
    assertEquals(1, counts.approved)
    assertEquals(1, counts.pending)
    assertEquals(2, slice.items.size)
    assertTrue(slice.items.all { it.activityId.startsWith("act_") && it.activityId.length == 28 })
    assertFalse(slice.items.any { it.activityId.contains("sub_") })
  }

  // =========================================================================
  // 6. PUBLIC LEADERBOARD FALLBACK ONLY TO /leaderboard
  // =========================================================================

  @Test
  fun publicLeaderboard_fallsBackOnlyToApprovedLeaderboardDocs_onNotFound() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource(
      leaderboardUsers = listOf(
        UserProfile(
          id = "u1",
          userId = "u1",
          displayName = "Aarav Sharma",
          approvalStatus = "approved",
          balanceCoins = 12500L,
          completedTasksCount = 15L
        ),
        UserProfile(
          id = "u2",
          userId = "u2",
          displayName = "Priya Patel",
          approvalStatus = "approved",
          balanceCoins = 8750L,
          completedTasksCount = 9L
        ),
        UserProfile(
          id = "u3_pending",
          userId = "u3_pending",
          displayName = "Pending User",
          approvalStatus = "pending",
          balanceCoins = 99999L,
          completedTasksCount = 50L
        )
      )
    )

    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { _, _ ->
        throw createFunctionsException(
          FirebaseFunctionsException.Code.NOT_FOUND,
          "getPublicLeaderboard not deployed"
        )
      }
    )
    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "user_spark_1" },
      sparkFallbackDataSource = fakeDataSource
    )

    val res = repo.getPublicLeaderboard()
    assertTrue(res.isSuccess)
    val snapshot = res.getOrThrow()
    assertEquals(1, fakeDataSource.leaderboardReadsCount)
    assertTrue(fakeDataSource.transactionsRequestedForUsers.isEmpty())
    assertEquals(2, snapshot.allTime.size)
    assertEquals(1, snapshot.allTime[0].rank)
    assertEquals("Aarav Sharma", snapshot.allTime[0].displayName)
    assertEquals(12500L, snapshot.allTime[0].earnedCoins)
    assertEquals(2, snapshot.allTime[1].rank)
    assertEquals("Priya Patel", snapshot.allTime[1].displayName)
  }

  @Test
  fun publicLeaderboard_failsClosed_onPermissionDeniedAndUnavailable() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource()
    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { _, _ ->
        throw createFunctionsException(
          FirebaseFunctionsException.Code.PERMISSION_DENIED,
          "Permission denied"
        )
      }
    )
    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "user_spark_1" },
      sparkFallbackDataSource = fakeDataSource
    )
    val res = repo.getPublicLeaderboard()
    assertTrue(res.isFailure)
    assertEquals(0, fakeDataSource.leaderboardReadsCount)
  }

  // =========================================================================
  // 7. UNITY ADS PLAYBACK CORRELATION ID WITHOUT LOCAL WALLET CREDIT
  // =========================================================================

  @Test
  fun unityAdsPlayback_issuesSparkCorrelationIdOnNotFound_andRejectsLocalVerificationAndClaim() = runBlocking {
    val fakeDataSource = FakeSparkFallbackDataSource(
      approvalStatusByUser = mapOf("user_spark_1" to "approved")
    )
    var claimCallableInvoked = false
    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { fnName, _ ->
        if (fnName == "claimRewardSession") {
          claimCallableInvoked = true
        }
        throw createFunctionsException(
          FirebaseFunctionsException.Code.NOT_FOUND,
          "$fnName not deployed on Spark"
        )
      }
    )
    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "user_spark_1" },
      sparkFallbackDataSource = fakeDataSource
    )

    val watchSession = repo.createWatchEarnSession("user_spark_1").getOrThrow()
    val claimSession = repo.createWatchEarnClaimSession("user_spark_1").getOrThrow()
    val scratchSession = repo.createScratchCardAdSession("user_spark_1").getOrThrow()
    val extraSpinSession = repo.createExtraSpinUnlockSession("user_spark_1").getOrThrow()

    assertTrue(watchSession.startsWith(SPARK_PLAYBACK_SESSION_PREFIX))
    assertTrue(claimSession.startsWith(SPARK_PLAYBACK_SESSION_PREFIX))
    assertTrue(scratchSession.startsWith(SPARK_PLAYBACK_SESSION_PREFIX))
    assertTrue(extraSpinSession.startsWith(SPARK_PLAYBACK_SESSION_PREFIX))
    assertTrue(isSparkPlaybackCorrelationId(watchSession))
    assertEquals(0L, repo.getOrAssignWatchEarnSessionReward(watchSession))

    // Post-ad verification and claiming MUST fail closed with zero wallet credit
    val verifyWatchRes = repo.verifyWatchEarnSessionCompleted("user_spark_1", watchSession)
    assertTrue(verifyWatchRes.isFailure)
    assertEquals(
      SPARK_AD_PLAYBACK_COMPLETED_NO_CREDIT_MESSAGE,
      verifyWatchRes.exceptionOrNull()?.message
    )

    val claimWatchRes = repo.claimVerifiedWatchEarnSession(
      userId = "user_spark_1",
      userName = "Spark User",
      sessionId = watchSession,
      secondSessionId = claimSession
    )
    assertTrue(claimWatchRes.isFailure)
    assertEquals(
      SPARK_AD_PLAYBACK_COMPLETED_NO_CREDIT_MESSAGE,
      claimWatchRes.exceptionOrNull()?.message
    )
    assertFalse("claimRewardSession callable must not be called for spark_playback_ IDs", claimCallableInvoked)

    val scratchVerifyRes = repo.verifyAndRecordScratchAdWatched("user_spark_1", scratchSession)
    assertTrue(scratchVerifyRes.isFailure)
    assertEquals(
      SPARK_AD_PLAYBACK_COMPLETED_NO_CREDIT_MESSAGE,
      scratchVerifyRes.exceptionOrNull()?.message
    )

    val extraSpinRecordRes = repo.recordExtraSpinAdWatch(extraSpinSession)
    assertTrue(extraSpinRecordRes.isFailure)
    assertEquals(
      SPARK_AD_PLAYBACK_COMPLETED_NO_CREDIT_MESSAGE,
      extraSpinRecordRes.exceptionOrNull()?.message
    )
  }

  // =========================================================================
  // 8. ADMIN CAMPAIGN TRACKING SPARK AVAILABILITY STATE & MANUAL PROOF NAVIGATION
  // =========================================================================

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun adminCampaignTracking_setsSparkUnavailableStateOnNotFound_andDisplaysSparkNoticeCard() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(testDispatcher)
      try {
        val client = FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, _ ->
            throw createFunctionsException(
              FirebaseFunctionsException.Code.NOT_FOUND,
              "$fnName not deployed on Spark"
            )
          }
        )
        val repo = TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "admin_uid_1" }
        )
        val viewModel = TaskFlowViewModel(
          repository = repo,
          currentUserId = "admin_uid_1",
          currentUserEmail = com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Admin",
          initialPortal = com.example.ui.auth.PortalLoginTarget.ADMIN_DASHBOARD
        )

        viewModel.refreshCampaignTrackingAdminData()
        advanceUntilIdle()

        val adminState = viewModel.campaignTrackingAdminState.value
        assertTrue(adminState.isSparkFunctionsUnavailable)
        assertEquals(SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE, adminState.dashboardError)
        assertEquals(SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE, adminState.networksError)
        assertEquals(SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE, adminState.configsError)
        assertEquals(SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE, adminState.conversionsError)

        // Verify manual proof offer launch in ViewModel routes to task detail without calling createCampaignClick
        var createClickCalled = false
        viewModel.launchTrackedCampaign(
          campaign = UserTrackedCampaignItem(
            campaignId = "camp_manual_1",
            linkedTaskId = "task_manual_1",
            title = "Manual Proof Offer",
            rewardTf = 500L,
            verificationMode = "MANUAL_PROOF",
            canClick = true
          ),
          onTrackingUrlReady = { createClickCalled = true }
        )
        assertFalse("MANUAL_PROOF offer must not invoke createCampaignClick URL callback", createClickCalled)
        assertEquals("task_manual_1", viewModel.selectedTaskId.value)
      } finally {
        Dispatchers.resetMain()
      }

      // Verify Compose UI renders the SparkPlanCampaignTrackingNoticeCard
      composeTestRule.setContent {
        TaskFlowTheme {
          AdminCampaignTrackingSection(
            state = AdminCampaignTrackingUiState(
              isSparkFunctionsUnavailable = true,
              dashboardError = SPARK_UNDEPLOYED_ADMIN_CAMPAIGN_TRACKING_MESSAGE
            ),
            onRefreshAll = {},
            onSelectDateRange = {},
            onLoadMoreCampaignAnalytics = {},
            onLoadMoreNetworkAnalytics = {},
            onLoadMoreNetworks = {},
            onLoadMoreConfigs = {},
            onSaveNetwork = { _, _, _ -> },
            onToggleNetworkStatus = { _, _ -> },
            onSaveCampaignConfig = { _, _, _ -> },
            onToggleCampaignConfigStatus = { _, _ -> },
            onClearFeedback = {},
            onNavigateToManualCampaigns = {},
            onNavigateToManualTasks = {},
            onNavigateToProofSubmissions = {}
          )
        }
      }

      composeTestRule.onNodeWithTag("spark_plan_campaign_tracking_notice_card").assertIsDisplayed()
      composeTestRule.onNodeWithText("Firebase Spark Free Plan Mode").assertIsDisplayed()
      composeTestRule.onNodeWithTag("spark_go_manual_campaigns_button").assertIsDisplayed()
      composeTestRule.onNodeWithTag("spark_go_manual_tasks_button").assertIsDisplayed()
      composeTestRule.onNodeWithTag("spark_go_proof_submissions_button").assertIsDisplayed()
    }
}

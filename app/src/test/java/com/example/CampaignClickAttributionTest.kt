package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserTaskDetailScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CampaignClickAttributionTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      ensureFirebaseInitialized(context)
    }
    return FirebaseFirestore.getInstance()
  }

  @Test
  fun createCampaignClick_callsServerCallable_andRejectsUnsafeOrSpoofedBehavior() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Map<*, *>>>()
    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, rawPayload ->
          val map = rawPayload as Map<*, *>
          recordedCalls += fnName to map
          mapOf(
            "success" to true,
            "clickId" to "clk_a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "campaignId" to "camp_finance_01",
            "networkId" to "net_alpha",
            "verificationMode" to CampaignVerificationMode.AFFILIATE_POSTBACK.name,
            "trackingUrl" to "https://partner.example.com/offer?aff_sub=clk_a1b2c3d4e5f60718293a4b5c6d7e8f90",
            "expiresAtMillis" to 1_900_000_000_000L,
            "deduplicated" to false
          )
        }
      )

    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = functionsClient,
        authenticatedUidProvider = { "uid_alice_01" }
      )

    val result =
      repository.createCampaignClick(
        campaignId = "camp_finance_01",
        taskId = "task_fin_01",
        devicePlatform = "android",
        idempotencyKey = "idem_click_001"
      )

    assertTrue("Expected createCampaignClick to succeed", result.isSuccess)
    val issued = result.getOrThrow()
    assertEquals("clk_a1b2c3d4e5f60718293a4b5c6d7e8f90", issued.clickId)
    assertEquals("camp_finance_01", issued.campaignId)
    assertEquals("net_alpha", issued.networkId)
    assertEquals(CampaignVerificationMode.AFFILIATE_POSTBACK, issued.resolvedVerificationMode())
    assertTrue(issued.trackingUrl.startsWith("https://partner.example.com/offer"))

    assertEquals(1, recordedCalls.size)
    val (fnName, payload) = recordedCalls.single()
    assertEquals("createCampaignClick", fnName)
    assertEquals("camp_finance_01", payload["campaignId"])
    assertEquals("task_fin_01", payload["taskId"])
    assertEquals("ANDROID", payload["devicePlatform"])
    assertEquals("idem_click_001", payload["idempotencyKey"])
    assertFalse(payload.containsKey("userId"))
    assertFalse(payload.containsKey("rewardTf"))
  }

  @Test
  fun createCampaignClick_rejectsNonHttpsTrackingUrl_andFailsClosedOnMissingFunction() = runBlocking {
    val firestore = createTestFirestore()
    val unsafeUrlClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { _, _ ->
          mapOf(
            "success" to true,
            "clickId" to "clk_1234567890abcdef1234567890abcdef",
            "campaignId" to "camp_finance_01",
            "trackingUrl" to "http://insecure.example.com/phish"
          )
        }
      )
    val unsafeRepo =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = unsafeUrlClient,
        authenticatedUidProvider = { "uid_alice_01" }
      )
    val unsafeResult = unsafeRepo.createCampaignClick(campaignId = "camp_finance_01")
    assertTrue("Insecure URL from server must fail closed", unsafeResult.isFailure)

    val errorClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { _, _ ->
          throw FirebaseNetworkException("createCampaignClick endpoint not found (404)")
        }
      )
    val failingRepo =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = errorClient,
        authenticatedUidProvider = { "uid_alice_01" }
      )
    val failingResult = failingRepo.createCampaignClick(campaignId = "camp_finance_01")
    assertTrue("Unavailable Cloud Function must never fall back locally", failingResult.isFailure)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_openCampaignTaskLink_requestsServerClickForTrackedCampaign_andPreservesManualTaskFlow() =
    runTest {
      val dispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(dispatcher)
      try {
        val firestore = createTestFirestore()
        var callableCount = 0
        val gate = CompletableDeferred<Unit>()

        val functionsClient =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            rewardCallableInvoker = { fnName, payload ->
              callableCount++
              assertEquals("createCampaignClick", fnName)
              val map = payload as Map<*, *>
              assertEquals("camp_tracked_99", map["campaignId"])
              gate.await()
              mapOf(
                "success" to true,
                "clickId" to "clk_99887766554433221100aabbccddeeff",
                "campaignId" to "camp_tracked_99",
                "networkId" to "net_s2s",
                "verificationMode" to "ADVERTISER_S2S_API",
                "trackingUrl" to "https://advertiser.example.com/start?subid=clk_99887766554433221100aabbccddeeff"
              )
            }
          )

        val repository =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = functionsClient,
            authenticatedUidProvider = { "uid_alice_01" }
          )

        val vm =
          TaskFlowViewModel(
            repository = repository,
            currentUserId = "uid_alice_01",
            currentUserEmail = "alice@example.com",
            currentUserDisplayName = "Alice",
            initialPortal = PortalLoginTarget.USER_APP
          )

        // 1. Tracked campaign task -> must call createCampaignClick first & guard double-tap
        val trackedTask =
          TaskItem(
            id = "task_tracked_1",
            title = "Install Tracked App",
            taskLink = "https://advertiser.example.com/start",
            campaignId = "camp_tracked_99",
            status = TaskStatus.PENDING,
            isActive = true
          )
        val launchedUrls = mutableListOf<String>()

        vm.openCampaignTaskLink(trackedTask) { launchedUrls += it }
        vm.openCampaignTaskLink(trackedTask) { launchedUrls += it } // rapid double tap ignored
        assertTrue(vm.isOpeningCampaignLink.value)
        assertTrue(launchedUrls.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(vm.isOpeningCampaignLink.value)
        assertEquals(1, callableCount)
        assertEquals(
          listOf("https://advertiser.example.com/start?subid=clk_99887766554433221100aabbccddeeff"),
          launchedUrls
        )

        // 2. Manual proof task without campaignId -> direct HTTPS launch preserved without calling click callable
        val manualTask =
          TaskItem(
            id = "task_manual_1",
            title = "Manual Screenshot Task",
            taskLink = "https://partner.example.org/manual-page",
            campaignId = "",
            status = TaskStatus.PENDING,
            isActive = true
          )
        val manualUrls = mutableListOf<String>()
        vm.openCampaignTaskLink(manualTask) { manualUrls += it }
        advanceUntilIdle()

        assertEquals(1, callableCount)
        assertEquals(listOf("https://partner.example.org/manual-page"), manualUrls)
      } finally {
        Dispatchers.resetMain()
      }
    }

  @Test
  fun userTaskDetailScreen_routesTrackedCampaignTapThroughServerClickCallback() {
    val trackedTask =
      TaskItem(
        id = "task_ui_01",
        title = "Tracked Partner Task",
        description = "Install and complete onboarding",
        appName = "PartnerApp",
        taskLink = "https://partner.example.com/raw-offer",
        campaignId = "camp_ui_01",
        rewardCents = 500,
        status = TaskStatus.PENDING,
        isActive = true
      )

    var interceptedRawUrl: String? = null
    composeTestRule.setContent {
      TaskFlowTheme {
        UserTaskDetailScreen(
          task = trackedTask,
          isBusy = false,
          onBack = {},
          onSubmitProof = { _, _ -> },
          onOpenTaskUrl = { rawUrl, onReady ->
            interceptedRawUrl = rawUrl
            onReady("https://partner.example.com/tracked?click_id=clk_ui_test_123456")
          }
        )
      }
    }

    composeTestRule
      .onNodeWithTag("start_task_open_link_button")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()

    assertNotNull(interceptedRawUrl)
    assertEquals("https://partner.example.com/raw-offer", interceptedRawUrl)
  }

  @Test
  fun userTaskDetailScreen_trackedCampaignWithoutServerCallback_failsClosedAndDoesNotConfirmInstall() {
    val trackedTask =
      TaskItem(
        id = "task_ui_fail_closed",
        title = "Tracked Partner Task",
        description = "Install and verify",
        appName = "PartnerApp",
        taskLink = "https://partner.example.com/direct-untracked",
        campaignId = "camp_ui_fail_closed",
        rewardCents = 500,
        status = TaskStatus.PENDING,
        isActive = true
      )

    var confirmInstallCalled = false
    composeTestRule.setContent {
      TaskFlowTheme {
        UserTaskDetailScreen(
          task = trackedTask,
          isBusy = false,
          onBack = {},
          onSubmitProof = { _, _ -> },
          onConfirmInstallation = { confirmInstallCalled = true }
        )
      }
    }

    composeTestRule
      .onNodeWithTag("start_task_open_link_button")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()

    assertFalse(
      "Tracked campaign must fail closed without server click callback",
      confirmInstallCalled
    )
  }
}

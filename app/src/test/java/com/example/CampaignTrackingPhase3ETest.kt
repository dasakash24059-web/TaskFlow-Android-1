package com.example

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdminCampaignConversionItem
import com.example.data.model.AdminCampaignTrackingUiState
import com.example.data.model.AdminConversionReviewAction
import com.example.data.model.AdminConversionStatusFilter
import com.example.data.model.AdminPaginatedSlice
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserTrackedCampaignItem
import com.example.data.model.UserTrackedCampaignsUiState
import com.example.data.model.UserTrackedRewardActivityCounts
import com.example.data.model.UserTrackedRewardActivityItem
import com.example.data.model.UserTrackedRewardActivityUiState
import com.example.data.model.coinsToCents
import com.example.data.model.formatUserCampaignClickError
import com.example.data.repository.CampaignAdminParsers
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.AdminCampaignTrackingSection
import com.example.ui.admin.CampaignConversionModerationDialog
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.OfferWallScreen
import com.example.ui.user.UserTrackedRewardActivityScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.example.util.CampaignUrlLauncher
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CampaignTrackingPhase3ETest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      ensureFirebaseInitialized(context)
    }
    return FirebaseFirestore.getInstance()
  }

  private fun sampleTrackedCampaign(
    id: String = "camp_3e_01",
    rewardTf: Long = 250L,
    userStatus: String = "AVAILABLE",
    canClick: Boolean = true
  ): UserTrackedCampaignItem = UserTrackedCampaignItem(
    campaignId = id,
    title = "Alpha FinTech Signup",
    description = "Install and complete KYC to earn verified TF rewards.",
    category = "App Install",
    rewardTf = rewardTf,
    rewardCents = coinsToCents(rewardTf),
    verificationMode = "AFFILIATE_POSTBACK",
    requiresAdminApproval = false,
    allowMultipleConversions = false,
    remainingSubmissions = 40L,
    userStatus = userStatus,
    canClick = canClick
  )

  private fun sampleActivityItem(
    activityId: String = "act_abc123456789012345678901",
    status: String = "PENDING",
    statusLabel: String = "Pending Verification",
    rewardTf: Long = 250L
  ): UserTrackedRewardActivityItem = UserTrackedRewardActivityItem(
    activityId = activityId,
    campaignId = "camp_3e_01",
    campaignTitle = "Alpha FinTech Signup",
    status = status,
    statusLabel = statusLabel,
    rewardTf = rewardTf,
    rewardCents = coinsToCents(rewardTf),
    createdAtMillis = 1_760_000_000_000L,
    verifiedAtMillis = if (status == "APPROVED") 1_760_000_600_000L else 0L,
    rejectedAtMillis = if (status == "REJECTED") 1_760_000_600_000L else 0L,
    reversedAtMillis = if (status == "REVERSED") 1_760_000_600_000L else 0L
  )

  private fun sampleAdminConversion(
    moderationId: String = "mod_11223344556677889900aabb",
    state: String = "PENDING",
    rewardTf: Long = 500L,
    expectedRecoverableTf: Long = 375L,
    estimatedShortfallTf: Long = 125L
  ): AdminCampaignConversionItem = AdminCampaignConversionItem(
    moderationId = moderationId,
    displayRef = "CONV-11223344",
    campaignId = "camp_3e_01",
    campaignTitle = "Alpha FinTech Signup",
    networkId = "net_alpha",
    networkName = "Alpha Affiliate Network",
    userId = "uid_user_01",
    userDisplayName = "Aarav Sharma",
    state = state,
    verificationMode = "AFFILIATE_POSTBACK",
    rewardTf = rewardTf,
    rewardCents = coinsToCents(rewardTf),
    rewardCredited = state == "APPROVED" || state == "REVERSED",
    recoveredTf = if (state == "REVERSED") 375L else 0L,
    recoveredCents = if (state == "REVERSED") coinsToCents(375L) else 0L,
    shortfallTf = if (state == "REVERSED") 125L else 0L,
    shortfallCents = if (state == "REVERSED") coinsToCents(125L) else 0L,
    expectedRecoverableTf = expectedRecoverableTf,
    expectedRecoverableCents = coinsToCents(expectedRecoverableTf),
    estimatedShortfallTf = estimatedShortfallTf,
    estimatedShortfallCents = coinsToCents(estimatedShortfallTf),
    createdAtMillis = 1_760_000_000_000L,
    verifiedAtMillis = if (state == "APPROVED") 1_760_000_500_000L else 0L
  )

  @Test
  fun urlLauncher_acceptsValidHttpsAndFailsClosedOnUnsafeUrls() {
    val valid = CampaignUrlLauncher.buildSafeCustomTabIntent(
      rawUrl = "https://track.partner.example/click?sub_id=clk_server_01",
      toolbarColor = 0xFF1E88E5.toInt()
    )
    assertNotNull(valid)
    assertEquals(Intent.ACTION_VIEW, valid!!.action)
    assertEquals("https", valid.data?.scheme)
    assertEquals("track.partner.example", valid.data?.host)
    assertTrue(valid.hasExtra("android.support.customtabs.extra.SESSION"))
    assertEquals(1, valid.getIntExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 0))

    // Fail closed on non-HTTPS, JS injection, private hosts, credentials, and blank strings
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent(""))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("   "))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("http://track.partner.example/click"))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("javascript:alert(1)"))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("https://localhost/click"))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("https://127.0.0.1/click"))
    assertNull(CampaignUrlLauncher.buildSafeCustomTabIntent("https://user:pass@track.partner.example/click"))
  }

  @Test
  fun userCampaignClickErrorFormatter_handlesAllExpectedServerFailureModes() {
    assertTrue(formatUserCampaignClickError("Click cooldown active").contains("wait a moment", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("Campaign reward budget exhausted").contains("budget", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("Campaign has expired").contains("active schedule", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("Country geo restriction failed").contains("region", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("Account not approved yet").contains("approved", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("UNAUTHENTICATED").contains("sign in", ignoreCase = true))
    assertTrue(formatUserCampaignClickError("App Check verification failed").contains("security verification", ignoreCase = true))
  }

  @Test
  fun parsers_parsePhase3EUserAndAdminPayloadsWith125TfEconomics() {
    assertEquals(125L, COINS_PER_RUPEE)
    assertEquals(100L, coinsToCents(125L))

    val eligible = CampaignAdminParsers.parseUserTrackedCampaignsPage(
      mapOf(
        "items" to listOf(
          mapOf(
            "campaignId" to "camp_01",
            "title" to "Fintech Offer",
            "rewardTf" to 250,
            "rewardCents" to 200,
            "verificationMode" to "AFFILIATE_POSTBACK",
            "userStatus" to "AVAILABLE",
            "canClick" to true,
            "remainingSubmissions" to 12
          )
        ),
        "totalCount" to 1,
        "hasMore" to false,
        "accountApproved" to true
      )
    )
    assertEquals(1, eligible.slice.items.size)
    assertEquals(250L, eligible.slice.items.single().rewardTf)
    assertEquals(200L, eligible.slice.items.single().rewardCents)
    assertTrue(eligible.accountApproved)

    val activity = CampaignAdminParsers.parseUserTrackedActivityPage(
      mapOf(
        "items" to listOf(
          mapOf(
            "activityId" to "act_01",
            "campaignId" to "camp_01",
            "campaignTitle" to "Fintech Offer",
            "status" to "PENDING",
            "statusLabel" to "Pending Verification",
            "rewardTf" to 250,
            "rewardCents" to 200,
            "createdAtMillis" to 1760000000000L
          )
        ),
        "counts" to mapOf("pending" to 1, "approved" to 2, "rejected" to 0, "reversed" to 1),
        "totalCount" to 1,
        "hasMore" to false
      )
    )
    assertEquals(1, activity.slice.items.size)
    assertEquals("Pending Verification", activity.slice.items.single().statusLabel)
    assertEquals(1, activity.counts.pending)
    assertEquals(2, activity.counts.approved)
    assertEquals(1, activity.counts.reversed)

    val adminQueue = CampaignAdminParsers.parseAdminConversionsPage(
      mapOf(
        "items" to listOf(
          mapOf(
            "moderationId" to "mod_abcdef1234567890",
            "displayRef" to "CONV-ABCDEF12",
            "campaignId" to "camp_01",
            "campaignTitle" to "Fintech Offer",
            "networkId" to "net_alpha",
            "networkName" to "Alpha Network",
            "userId" to "uid_01",
            "userDisplayName" to "User One",
            "state" to "APPROVED",
            "rewardTf" to 500,
            "rewardCents" to 400,
            "rewardCredited" to true,
            "expectedRecoverableTf" to 375,
            "expectedRecoverableCents" to 300,
            "estimatedShortfallTf" to 125,
            "estimatedShortfallCents" to 100
          )
        ),
        "totalCount" to 1,
        "hasMore" to false,
        "truncated" to true,
        "scanLimit" to 2000
      )
    )
    assertTrue(adminQueue.slice.truncated)
    assertEquals(2000, adminQueue.scanLimit)
    val row = adminQueue.slice.items.single()
    assertEquals("mod_abcdef1234567890", row.moderationId)
    assertEquals(500L, row.rewardTf)
    assertEquals(375L, row.expectedRecoverableTf)
    assertEquals(125L, row.estimatedShortfallTf)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_phase3EUserClickAndAdminModerationEnforceServerAuthorityAndNonAdminBlock() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val firestore = createTestFirestore()
      val invokedFunctions = mutableListOf<String>()
      var lastReviewPayload: Map<*, *>? = null

      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          invokedFunctions += fnName
          val map = payload as? Map<*, *> ?: emptyMap<Any, Any>()
          when (fnName) {
            "listUserEligibleTrackedCampaigns" -> mapOf(
              "items" to listOf(
                mapOf(
                  "campaignId" to "camp_3e_01",
                  "title" to "Alpha FinTech Signup",
                  "rewardTf" to 250,
                  "rewardCents" to 200,
                  "userStatus" to "AVAILABLE",
                  "canClick" to true
                )
              ),
              "totalCount" to 1,
              "hasMore" to false,
              "accountApproved" to true
            )
            "createCampaignClick" -> mapOf(
              "clickId" to "clk_server_authoritative_01",
              "campaignId" to (map["campaignId"] ?: "camp_3e_01"),
              "trackingUrl" to "https://track.partner.example/go?sub_id=clk_server_authoritative_01",
              "expiresAtMillis" to 1_900_000_000_000L
            )
            "listUserTrackedRewardActivity" -> mapOf(
              "items" to listOf(
                mapOf(
                  "activityId" to "act_01",
                  "campaignId" to "camp_3e_01",
                  "campaignTitle" to "Alpha FinTech Signup",
                  "status" to "PENDING",
                  "statusLabel" to "Pending Verification",
                  "rewardTf" to 250,
                  "rewardCents" to 200
                )
              ),
              "counts" to mapOf("pending" to 1, "approved" to 0, "rejected" to 0, "reversed" to 0, "total" to 1),
              "totalCount" to 1,
              "hasMore" to false
            )
            "adminListCampaignConversions" -> mapOf(
              "items" to listOf(
                mapOf(
                  "moderationId" to "mod_11223344556677889900aabb",
                  "displayRef" to "CONV-11223344",
                  "campaignId" to "camp_3e_01",
                  "campaignTitle" to "Alpha FinTech Signup",
                  "networkId" to "net_alpha",
                  "networkName" to "Alpha Network",
                  "userId" to "uid_regular",
                  "userDisplayName" to "Regular User",
                  "state" to "PENDING",
                  "rewardTf" to 250,
                  "rewardCents" to 200
                )
              ),
              "totalCount" to 1,
              "hasMore" to false,
              "truncated" to false,
              "scanLimit" to 2000
            )
            "reviewCampaignConversion" -> {
              lastReviewPayload = map
              mapOf(
                "success" to true,
                "moderationId" to (map["moderationId"] ?: ""),
                "state" to "APPROVED",
                "rewardTf" to 250,
                "rewardCredited" to true,
                "walletCreditApplied" to true
              )
            }
            "adminGetCampaignTrackingDashboard" -> mapOf(
              "dateRange" to "30D",
              "summary" to mapOf("totalCampaigns" to 1, "approvedConversions" to 1, "grossCreditedTf" to 250, "netCreditedTf" to 250),
              "campaignPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0),
              "networkPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0)
            )
            else -> mapOf("items" to emptyList<Any>(), "totalCount" to 0)
          }
        }
      )

      val repo = TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "uid_regular" }
      )

      // 1) User discovers tracked campaigns & launches server tracking URL without mutating wallet
      val userVm = TaskFlowViewModel(
        repository = repo,
        currentUserId = "uid_regular",
        currentUserEmail = "regular@example.com",
        currentUserDisplayName = "Regular",
        initialPortal = PortalLoginTarget.USER_APP
      )
      userVm.loadUserEligibleTrackedCampaigns()
      advanceUntilIdle()
      assertEquals(1, userVm.userTrackedCampaignsState.value.items.size)

      var launchedUrl: String? = null
      userVm.launchTrackedCampaign(userVm.userTrackedCampaignsState.value.items.single()) { url ->
        launchedUrl = url
        true
      }
      advanceUntilIdle()
      assertEquals("https://track.partner.example/go?sub_id=clk_server_authoritative_01", launchedUrl)
      assertFalse(userVm.userTrackedCampaignsState.value.feedbackIsError)

      userVm.loadUserTrackedRewardActivity()
      advanceUntilIdle()
      assertEquals(1, userVm.userTrackedActivityState.value.items.size)
      assertEquals("Pending Verification", userVm.userTrackedActivityState.value.items.single().statusLabel)

      // 2) Non-admin user is blocked from admin conversion queue & review actions
      val countBeforeAdminBlock = invokedFunctions.size
      userVm.loadAdminCampaignConversions()
      userVm.reviewAdminCampaignConversion(
        item = sampleAdminConversion(),
        action = AdminConversionReviewAction.APPROVE,
        reason = "Spoof attempt"
      )
      advanceUntilIdle()
      assertEquals(countBeforeAdminBlock, invokedFunctions.size)
      assertNotNull(userVm.campaignTrackingAdminState.value.conversionsError)

      // 3) Authorized admin loads queue and approves with mandatory reason -> auto-refreshes queue & analytics
      val adminVm = TaskFlowViewModel(
        repository = repo,
        currentUserId = "uid_admin",
        currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        currentUserDisplayName = "Admin",
        initialPortal = PortalLoginTarget.ADMIN_DASHBOARD
      )
      adminVm.loadAdminCampaignConversions()
      advanceUntilIdle()
      assertEquals(1, adminVm.campaignTrackingAdminState.value.conversions.items.size)

      var reviewDone = false
      adminVm.reviewAdminCampaignConversion(
        item = adminVm.campaignTrackingAdminState.value.conversions.items.single(),
        action = AdminConversionReviewAction.APPROVE,
        reason = "Verified against advertiser S2S report",
        onSuccess = { reviewDone = true }
      )
      advanceUntilIdle()
      assertTrue(reviewDone)
      assertEquals("mod_11223344556677889900aabb", lastReviewPayload?.get("moderationId"))
      assertEquals("APPROVE", lastReviewPayload?.get("action"))
      assertEquals("PENDING", lastReviewPayload?.get("expectedState"))
      assertEquals("Verified against advertiser S2S report", lastReviewPayload?.get("reason"))
      assertTrue(invokedFunctions.contains("adminGetCampaignTrackingDashboard"))
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun offerWallScreen_rendersTrackedOffersAndActivityButton_whilePreservingLegacyOffers() {
    var launchedCampaignId: String? = null
    var openedActivity = false

    val legacyOffer = TaskItem(
      id = "legacy_task_01",
      title = "Classic Survey Offer",
      category = "Surveys",
      rewardCoins = 125L,
      isActive = true,
      status = TaskStatus.PENDING
    )
    val trackedState = UserTrackedCampaignsUiState(
      items = listOf(sampleTrackedCampaign()),
      accountApproved = true,
      feedbackMessage = "Offer opened. Reward credits after server verification.",
      feedbackIsError = false
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        OfferWallScreen(
          tasks = listOf(legacyOffer),
          onBack = {},
          trackedCampaignsState = trackedState,
          onLaunchTrackedCampaign = { launchedCampaignId = it.campaignId },
          onOpenTrackedActivity = { openedActivity = true }
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("offer_wall_tracked_activity_button").performClick()
    assertTrue(openedActivity)

    composeTestRule.onNodeWithTag("offer_wall_screen")
      .performScrollToNode(hasTestTag("tracked_campaign_card_camp_3e_01"))
    composeTestRule.onNodeWithTag("tracked_campaign_card_camp_3e_01").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tracked_campaign_start_camp_3e_01").assertIsEnabled().performClick()
    assertEquals("camp_3e_01", launchedCampaignId)

    // Existing legacy offer remains intact alongside tracked offers
    composeTestRule.onNodeWithTag("offer_wall_screen")
      .performScrollToNode(androidx.compose.ui.test.hasText("Classic Survey Offer"))
    composeTestRule.onNodeWithText("Classic Survey Offer").assertIsDisplayed()
  }

  @Test
  fun offerWallScreen_disablesTrackedStartWhenAccountNotApproved() {
    val unapprovedState = UserTrackedCampaignsUiState(
      items = listOf(sampleTrackedCampaign()),
      accountApproved = false
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.DARK) {
        OfferWallScreen(
          tasks = emptyList(),
          onBack = {},
          trackedCampaignsState = unapprovedState
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("offer_wall_screen")
      .performScrollToNode(hasTestTag("offer_wall_account_not_approved_banner"))
    composeTestRule.onNodeWithTag("offer_wall_account_not_approved_banner").assertIsDisplayed()

    composeTestRule.onNodeWithTag("offer_wall_screen")
      .performScrollToNode(hasTestTag("tracked_campaign_start_camp_3e_01"))
    composeTestRule.onNodeWithTag("tracked_campaign_start_camp_3e_01").assertIsNotEnabled()
  }

  @Test
  fun userTrackedRewardActivityScreen_displaysAllStatusesTfInrAndIsStrictlyReadOnly() {
    var selectedFilter: String? = null
    val activityState = UserTrackedRewardActivityUiState(
      items = listOf(
        sampleActivityItem(activityId = "act_pending", status = "PENDING", statusLabel = "Pending Verification", rewardTf = 250L),
        sampleActivityItem(activityId = "act_approved", status = "APPROVED", statusLabel = "Approved", rewardTf = 500L),
        sampleActivityItem(activityId = "act_rejected", status = "REJECTED", statusLabel = "Rejected", rewardTf = 125L),
        sampleActivityItem(activityId = "act_reversed", status = "REVERSED", statusLabel = "Reversed", rewardTf = 375L)
      ),
      counts = UserTrackedRewardActivityCounts(pending = 1, approved = 1, rejected = 1, reversed = 1),
      selectedStatusFilter = "ALL"
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.DARK) {
        UserTrackedRewardActivityScreen(
          state = activityState,
          onSelectStatusFilter = { selectedFilter = it },
          onRefresh = {},
          onLoadMore = {},
          onBack = {}
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("tracked_activity_filter_pending").performClick()
    assertEquals("PENDING", selectedFilter)

    composeTestRule.onNodeWithTag("tracked_activity_list")
      .performScrollToNode(hasTestTag("tracked_activity_item_act_pending"))
    composeTestRule.onNodeWithTag("tracked_activity_item_act_pending").assertIsDisplayed()
    composeTestRule.onNodeWithText("250 TF (₹2.00)", substring = true).assertExists()

    composeTestRule.onNodeWithTag("tracked_activity_list")
      .performScrollToNode(hasTestTag("tracked_activity_item_act_approved"))
    composeTestRule.onNodeWithTag("tracked_activity_item_act_approved").assertIsDisplayed()
    composeTestRule.onNodeWithText("500 TF (₹4.00)", substring = true).assertExists()

    composeTestRule.onNodeWithTag("tracked_activity_list")
      .performScrollToNode(hasTestTag("tracked_activity_item_act_reversed"))
    composeTestRule.onNodeWithTag("tracked_activity_item_act_reversed").assertIsDisplayed()
    composeTestRule.onNodeWithText("375 TF (₹3.00)", substring = true).assertExists()
  }

  @Test
  fun adminConversionsTab_displaysQueueTruncationFilterAndMandatoryReasonDialog() {
    var appliedCampaignFilter: String? = null
    var appliedNetworkFilter: String? = null
    var submittedReason: String? = null
    var submittedAction: AdminConversionReviewAction? = null

    val pendingItem = sampleAdminConversion(
      moderationId = "mod_pending_01",
      state = "PENDING",
      rewardTf = 250L
    )
    val state = AdminCampaignTrackingUiState(
      hasLoadedDashboard = true,
      conversions = AdminPaginatedSlice(
        items = listOf(pendingItem),
        totalCount = 1,
        hasMore = false,
        truncated = true
      ),
      conversionsScanLimit = 2000,
      conversionsFilterStatus = AdminConversionStatusFilter.PENDING
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = state,
          onRefreshAll = {},
          onSelectDateRange = {},
          onLoadMoreCampaignAnalytics = {},
          onLoadMoreNetworkAnalytics = {},
          onLoadMoreNetworks = {},
          onLoadMoreConfigs = {},
          onSaveNetwork = { _, _, onDone -> onDone() },
          onToggleNetworkStatus = { _, _ -> },
          onSaveCampaignConfig = { _, _, onDone -> onDone() },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {},
          onApplyConversionsFilters = { camp, net ->
            appliedCampaignFilter = camp
            appliedNetworkFilter = net
          },
          onReviewConversion = { _, action, reason, onDone ->
            submittedAction = action
            submittedReason = reason
            onDone()
          }
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_conversions").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_conversions_truncation_banner")
      .performScrollTo()
      .assertIsDisplayed()

    // Apply campaign & network filters
    composeTestRule.onNodeWithTag("admin_conversions_campaign_filter_input")
      .performScrollTo()
      .performTextInput("camp_3e_01")
    composeTestRule.onNodeWithTag("admin_conversions_network_filter_input")
      .performScrollTo()
      .performTextInput("net_alpha")
    composeTestRule.onNodeWithTag("admin_conversions_apply_filters_button")
      .performScrollTo()
      .performClick()
    assertEquals("camp_3e_01", appliedCampaignFilter)
    assertEquals("net_alpha", appliedNetworkFilter)

    // Open Approve moderation dialog and verify blank reason is blocked
    composeTestRule.onNodeWithTag("admin_conversion_approve_mod_pending_01")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_conversion_financial_preview").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_conversion_confirm_button").performClick()
    assertNull(submittedReason)
    composeTestRule.onNodeWithText("Please enter a clear reason", substring = true).assertExists()

    // Enter valid reason and confirm
    composeTestRule.onNodeWithTag("admin_conversion_reason_input")
      .performScrollTo()
      .performTextInput("Verified against partner report")
    composeTestRule.onNodeWithTag("admin_conversion_confirm_button").performClick()
    assertEquals(AdminConversionReviewAction.APPROVE, submittedAction)
    assertEquals("Verified against partner report", submittedReason)
  }

  @Test
  fun adminReverseModerationDialog_showsOriginalCreditedExpectedRecoverableShortfallAndWarning() {
    var submittedReason: String? = null
    val approvedItem = sampleAdminConversion(
      moderationId = "mod_approved_01",
      state = "APPROVED",
      rewardTf = 500L,
      expectedRecoverableTf = 375L,
      estimatedShortfallTf = 125L
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.DARK) {
        CampaignConversionModerationDialog(
          item = approvedItem,
          action = AdminConversionReviewAction.REVERSE,
          isSubmitting = false,
          onDismiss = {},
          onConfirm = { submittedReason = it }
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_conversion_financial_preview").assertIsDisplayed()
    composeTestRule.onNodeWithText("500 TF (₹4.00)", substring = true).assertExists()
    composeTestRule.onNodeWithText("375 TF (₹3.00)", substring = true).assertExists()
    composeTestRule.onNodeWithText("125 TF (₹1.00)", substring = true).assertExists()
    composeTestRule.onNodeWithTag("admin_conversion_reversal_warning").assertExists()
    composeTestRule.onNodeWithText("Full recovery is not guaranteed", substring = true).assertExists()

    composeTestRule.onNodeWithTag("admin_conversion_reason_input")
      .performTextInput("Partner fraud chargeback notice")
    composeTestRule.onNodeWithTag("admin_conversion_confirm_button").performClick()
    assertEquals("Partner fraud chargeback notice", submittedReason)
  }
}

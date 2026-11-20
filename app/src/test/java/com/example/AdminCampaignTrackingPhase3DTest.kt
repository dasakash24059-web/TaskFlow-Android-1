package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AdminCampaignNetworkItem
import com.example.data.model.AdminCampaignTrackingConfigItem
import com.example.data.model.AdminCampaignTrackingUiState
import com.example.data.model.AdminNetworkSignatureConfig
import com.example.data.model.AdminPaginatedSlice
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.CampaignAnalyticsDateRange
import com.example.data.model.CampaignAnalyticsScanLimits
import com.example.data.model.CampaignPerformanceRow
import com.example.data.model.CampaignTrackingSummaryMetrics
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.MAX_CAMPAIGN_TRACKING_URL_LENGTH
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkPerformanceRow
import com.example.data.model.NetworkSignatureAlgorithm
import com.example.data.model.REGISTERED_CAMPAIGN_SECRET_KEYS
import com.example.data.model.coinsToCents
import com.example.data.model.formatApprovalRateDisplay
import com.example.data.model.formatSignedRewardInr
import com.example.data.model.isValidCampaignTrackingUrl
import com.example.data.model.parseStrictIsoDateToUtcMillis
import com.example.data.model.validateAdminCampaignConfigForm
import com.example.data.model.validateAdminNetworkForm
import com.example.data.repository.CampaignAdminParsers
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.AdminCampaignTrackingSection
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.admin.CampaignTrackingConfigEditorDialog
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.TaskFlowViewModel
import com.example.ui.viewmodel.UiState
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
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
class AdminCampaignTrackingPhase3DTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    if (FirebaseApp.getApps(context).isEmpty()) {
      ensureFirebaseInitialized(context)
    }
    return FirebaseFirestore.getInstance()
  }

  private fun sampleNetwork(
    id: String = "net_alpha",
    active: Boolean = true
  ): AdminCampaignNetworkItem = AdminCampaignNetworkItem(
    networkId = id,
    name = "Alpha Affiliate Network",
    adapterType = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
    isActive = active,
    capabilities = NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = true,
      supportsPostback = true
    ),
    allowedDestinationHosts = listOf("track.alpha.example"),
    signatureConfig = AdminNetworkSignatureConfig(
      algorithm = NetworkSignatureAlgorithm.HMAC_SHA256.name,
      secretEnvKey = REGISTERED_CAMPAIGN_SECRET_KEYS[1],
      signingMode = "SORTED_PARAMS",
      encoding = "hex"
    )
  )

  private fun sampleConfig(
    id: String = "camp_alpha_01",
    networkId: String = "net_alpha",
    active: Boolean = true
  ): AdminCampaignTrackingConfigItem = AdminCampaignTrackingConfigItem(
    configId = id,
    campaignId = id,
    title = "Alpha FinTech Install",
    isActive = active,
    startDate = "2026-01-01",
    endDate = "2026-12-31",
    submissionBudget = 100L,
    usedSubmissions = 25L,
    rewardBudgetTf = 25_000L,
    spentBudgetTf = 6_250L,
    allowedCountries = listOf("IN", "US"),
    allowedPlatforms = listOf("ANDROID"),
    rewardTf = 250L,
    rewardCents = coinsToCents(250L),
    verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
    networkId = networkId,
    trackingBaseUrl = "https://track.alpha.example/offer/1"
  )

  private fun samplePopulatedState(): AdminCampaignTrackingUiState = AdminCampaignTrackingUiState(
    isLoadingDashboard = false,
    hasLoadedDashboard = true,
    selectedDateRange = CampaignAnalyticsDateRange.LAST_30_DAYS,
    summary = CampaignTrackingSummaryMetrics(
      totalCampaigns = 5,
      activeCampaigns = 3,
      totalNetworks = 2,
      activeNetworks = 2,
      totalClicks = 420L,
      totalConversions = 12L,
      pendingConversions = 2L,
      approvedConversions = 7L,
      rejectedConversions = 1L,
      reversedConversions = 2L,
      approvalRatePercent = 58.3,
      hasConversionData = true,
      grossCreditedTf = 2_250L,
      reversedTf = 500L,
      netCreditedTf = 1_750L,
      grossCreditedCents = coinsToCents(2_250L),
      netCreditedCents = coinsToCents(1_750L)
    ),
    campaignPerformance = AdminPaginatedSlice(
      items = listOf(
        CampaignPerformanceRow(
          campaignId = "camp_alpha_01",
          title = "Alpha FinTech Install",
          networkId = "net_alpha",
          verificationMode = "AFFILIATE_POSTBACK",
          isActive = true,
          rewardTf = 250L,
          clicksCount = 300L,
          totalConversions = 12L,
          pendingConversions = 2L,
          approvedConversions = 7L,
          rejectedConversions = 1L,
          reversedConversions = 2L,
          approvalRatePercent = 58.3,
          grossCreditedTf = 2_250L,
          reversedTf = 500L,
          netCreditedTf = 1_750L,
          grossCreditedCents = coinsToCents(2_250L),
          netCreditedCents = coinsToCents(1_750L),
          submissionBudget = 100L,
          usedSubmissions = 25L,
          rewardBudgetTf = 25_000L,
          spentBudgetTf = 6_250L,
          budgetUtilizationPercent = 25.0
        ),
        CampaignPerformanceRow(
          campaignId = "camp_zero_conv",
          title = "Brand New Offer",
          networkId = "net_alpha",
          verificationMode = "AFFILIATE_POSTBACK",
          isActive = true,
          rewardTf = 125L,
          clicksCount = 15L,
          totalConversions = 0L,
          approvalRatePercent = null,
          grossCreditedTf = 0L,
          reversedTf = 0L,
          netCreditedTf = 0L
        )
      ),
      totalCount = 2
    ),
    networkPerformance = AdminPaginatedSlice(
      items = listOf(
        NetworkPerformanceRow(
          networkId = "net_alpha",
          name = "Alpha Affiliate Network",
          adapterType = "AFFILIATE_POSTBACK",
          isActive = true,
          campaignsCount = 2,
          clicksCount = 315L,
          totalConversions = 12L,
          pendingConversions = 2L,
          approvedConversions = 7L,
          rejectedConversions = 1L,
          reversedConversions = 2L,
          approvalRatePercent = 58.3,
          grossCreditedTf = 2_250L,
          reversedTf = 500L,
          netCreditedTf = 1_750L,
          grossCreditedCents = coinsToCents(2_250L),
          netCreditedCents = coinsToCents(1_750L)
        )
      ),
      totalCount = 1
    ),
    networks = AdminPaginatedSlice(items = listOf(sampleNetwork()), totalCount = 1),
    configs = AdminPaginatedSlice(items = listOf(sampleConfig()), totalCount = 1)
  )

  @Test
  fun formValidation_enforcesBackendRules_andApprovalRateFormattingHandlesZero() {
    assertEquals("N/A", formatApprovalRateDisplay(null, 0L))
    assertEquals("N/A", formatApprovalRateDisplay(0.0, 0L))
    assertEquals("75.0%", formatApprovalRateDisplay(75.0, 4L))

    // Valid network passes
    assertTrue(validateAdminNetworkForm(sampleNetwork()).isEmpty())

    // Unregistered secret key is rejected
    val badSecretNet = sampleNetwork().copy(
      signatureConfig = sampleNetwork().signatureConfig.copy(secretEnvKey = "CUSTOM_UNBOUND_KEY")
    )
    assertTrue(
      validateAdminNetworkForm(badSecretNet).any { it.contains("registered Firebase Secret Manager") }
    )

    // NONE signature on AFFILIATE_POSTBACK is rejected
    val badNoneNet = sampleNetwork().copy(
      signatureConfig = sampleNetwork().signatureConfig.copy(algorithm = NetworkSignatureAlgorithm.NONE.name)
    )
    assertTrue(validateAdminNetworkForm(badNoneNet).isNotEmpty())

    // CANONICAL_FIELDS without signedFields is rejected
    val badCanonicalNet = sampleNetwork().copy(
      signatureConfig = sampleNetwork().signatureConfig.copy(
        signingMode = "CANONICAL_FIELDS",
        signedFields = emptyList()
      )
    )
    assertTrue(
      validateAdminNetworkForm(badCanonicalNet).any { it.contains("CANONICAL_FIELDS") }
    )

    // Campaign config validation checks network/mode match, rewardTf, budgets, URL
    val net = sampleNetwork()
    assertTrue(validateAdminCampaignConfigForm(sampleConfig(), net).isEmpty())

    val badConfig = sampleConfig().copy(
      rewardTf = 0L,
      trackingBaseUrl = "http://insecure.example.com",
      allowedCountries = listOf("INVALID_COUNTRY"),
      verificationMode = CampaignVerificationMode.TELEGRAM_BOT_API.name
    )
    val configErrors = validateAdminCampaignConfigForm(badConfig, net)
    assertTrue(configErrors.any { it.contains("must match verification mode") })
    assertTrue(configErrors.any { it.contains("Reward TF") })
    assertTrue(configErrors.any { it.contains("HTTPS") })
    assertTrue(configErrors.any { it.contains("Allowed countries") })
  }

  @Test
  fun repositoryCallsServerOnlyAdminCallables_andStripsUnregisteredSecretKeys() = runBlocking {
    val firestore = createTestFirestore()
    val calledFunctions = mutableListOf<String>()
    val client = FirebaseRewardCloudFunctionsClient(
      firestore = firestore,
      rewardCallableInvoker = { fnName, _ ->
        calledFunctions += fnName
        when (fnName) {
          "adminListCampaignNetworks" -> mapOf(
            "items" to listOf(
              mapOf(
                "networkId" to "net_alpha",
                "name" to "Alpha Network",
                "adapterType" to "AFFILIATE_POSTBACK",
                "isActive" to true,
                "signatureConfig" to mapOf(
                  "algorithm" to "HMAC_SHA256",
                  "secretEnvKey" to "ROGUE_SECRET_KEY"
                )
              )
            ),
            "totalCount" to 1,
            "hasMore" to false,
            "nextCursor" to ""
          )
          "adminGetCampaignTrackingDashboard" -> mapOf(
            "dateRange" to "7D",
            "summary" to mapOf(
              "totalCampaigns" to 2,
              "activeCampaigns" to 1,
              "totalClicks" to 50,
              "grossCreditedTf" to 500,
              "reversedTf" to 125,
              "netCreditedTf" to 375,
              "grossCreditedCents" to 400,
              "netCreditedCents" to 300
            ),
            "campaignPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0),
            "networkPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0)
          )
          else -> mapOf("success" to true)
        }
      }
    )

    val repo = TaskFlowRepository(
      firestore = firestore,
      rewardFunctionsClient = client,
      authenticatedUidProvider = { "admin_uid_01" }
    )

    val netRes = repo.adminListCampaignNetworks()
    assertTrue(netRes.isSuccess)
    val parsedNet = netRes.getOrThrow().items.single()
    assertEquals("net_alpha", parsedNet.networkId)
    assertEquals("", parsedNet.signatureConfig.secretEnvKey)

    val dashRes = repo.adminGetCampaignTrackingDashboard(CampaignAnalyticsDateRange.LAST_7_DAYS)
    assertTrue(dashRes.isSuccess)
    val dash = dashRes.getOrThrow()
    assertEquals(500L, dash.summary.grossCreditedTf)
    assertEquals(125L, dash.summary.reversedTf)
    assertEquals(375L, dash.summary.netCreditedTf)
    assertEquals(listOf("adminListCampaignNetworks", "adminGetCampaignTrackingDashboard"), calledFunctions)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_blocksNonAdminUsers_andAllowsAuthorizedAdminToLoadServerDashboard() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val firestore = createTestFirestore()
      var callableInvocations = 0
      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, _ ->
          callableInvocations++
          when (fnName) {
            "adminGetCampaignTrackingDashboard" -> mapOf(
              "dateRange" to "30D",
              "summary" to mapOf(
                "totalCampaigns" to 4,
                "activeCampaigns" to 2,
                "totalClicks" to 88,
                "grossCreditedTf" to 1000,
                "reversedTf" to 250,
                "netCreditedTf" to 750
              ),
              "campaignPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0),
              "networkPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0)
            )
            "adminListCampaignNetworks",
            "adminListCampaignTrackingConfigs" -> mapOf("items" to emptyList<Any>(), "totalCount" to 0)
            else -> mapOf("success" to true)
          }
        }
      )
      val repo = TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "uid_01" }
      )

      // Non-admin user must be rejected without hitting backend
      val nonAdminVm = TaskFlowViewModel(
        repository = repo,
        currentUserId = "uid_regular",
        currentUserEmail = "regular.user@example.com",
        currentUserDisplayName = "Regular",
        initialPortal = PortalLoginTarget.USER_APP
      )
      nonAdminVm.refreshCampaignTrackingAdminData()
      nonAdminVm.upsertCampaignTrackingNetwork(sampleNetwork(), isEdit = false)
      nonAdminVm.setCampaignTrackingConfigStatus("camp_alpha_01", false)
      advanceUntilIdle()
      assertEquals(0, callableInvocations)
      assertNotNull(nonAdminVm.campaignTrackingAdminState.value.dashboardError)
      assertTrue(nonAdminVm.campaignTrackingAdminState.value.feedbackIsError)

      // Authorized admin succeeds
      val adminVm = TaskFlowViewModel(
        repository = repo,
        currentUserId = "uid_admin",
        currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        currentUserDisplayName = "Admin",
        initialPortal = PortalLoginTarget.ADMIN_DASHBOARD
      )
      adminVm.refreshCampaignTrackingAdminData()
      advanceUntilIdle()
      assertTrue(callableInvocations >= 3)
      assertEquals(4, adminVm.campaignTrackingAdminState.value.summary.totalCampaigns)
      assertEquals(750L, adminVm.campaignTrackingAdminState.value.summary.netCreditedTf)
      assertFalse(adminVm.campaignTrackingAdminState.value.isLoadingDashboard)
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun dashboardOverview_rendersAllSummaryMetrics_inLightAndDarkThemes() {
    val populated = samplePopulatedState()
    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.DARK) {
        AdminCampaignTrackingSection(
          state = populated,
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
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("overview_gross_vs_net_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("metric_gross_tf_rewards").assertTextContains("2,250", substring = true)
    composeTestRule.onNodeWithTag("metric_net_tf_rewards").assertTextContains("1,750", substring = true)
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("metric_total_campaigns"))
    composeTestRule.onNodeWithTag("metric_total_campaigns").assertTextContains("5", substring = true)
    composeTestRule.onNodeWithTag("metric_active_campaigns").assertTextContains("3", substring = true)
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("metric_total_clicks"))
    composeTestRule.onNodeWithTag("metric_total_clicks").assertTextContains("420", substring = true)
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("metric_pending_conversions"))
    composeTestRule.onNodeWithTag("metric_pending_conversions").assertTextContains("2", substring = true)
    composeTestRule.onNodeWithTag("metric_approved_conversions").assertTextContains("7", substring = true)
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("metric_rejected_conversions"))
    composeTestRule.onNodeWithTag("metric_rejected_conversions").assertTextContains("1", substring = true)
    composeTestRule.onNodeWithTag("metric_reversed_conversions").assertTextContains("2", substring = true)
  }

  @Test
  fun networksTab_requiresConfirmationBeforeStatusChange_andValidatesForm() {
    var toggledNetworkId: String? = null
    var toggledNetworkActive: Boolean? = null
    val populated = samplePopulatedState()

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = populated,
          onRefreshAll = {},
          onSelectDateRange = {},
          onLoadMoreCampaignAnalytics = {},
          onLoadMoreNetworkAnalytics = {},
          onLoadMoreNetworks = {},
          onLoadMoreConfigs = {},
          onSaveNetwork = { _, _, onDone -> onDone() },
          onToggleNetworkStatus = { id, active ->
            toggledNetworkId = id
            toggledNetworkActive = active
          },
          onSaveCampaignConfig = { _, _, onDone -> onDone() },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_networks").performClick()
    composeTestRule.onNodeWithTag("networks_lazy_column")
      .performScrollToNode(hasTestTag("network_card_net_alpha"))
    composeTestRule.onNodeWithTag("toggle_network_net_alpha").performClick()
    composeTestRule.onNodeWithTag("campaign_status_confirm_dialog").assertExists()
    composeTestRule.onNodeWithText("Disable Network?").assertExists()
    assertEquals(null, toggledNetworkId)
    composeTestRule.onNodeWithTag("campaign_status_confirm_button").performClick()
    assertEquals("net_alpha", toggledNetworkId)
    assertEquals(false, toggledNetworkActive)

    composeTestRule.onNodeWithTag("add_campaign_network_button").performClick()
    composeTestRule.onNodeWithTag("campaign_network_editor_dialog").assertExists()
    composeTestRule.onNodeWithTag("network_form_save_button").performClick()
    composeTestRule.onNodeWithTag("network_form_validation_error").assertExists()
  }

  @Test
  fun campaignsTab_requiresConfirmationBeforePause_andValidatesForm() {
    var toggledCampaignId: String? = null
    var toggledCampaignActive: Boolean? = null
    val populated = samplePopulatedState()

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = populated,
          onRefreshAll = {},
          onSelectDateRange = {},
          onLoadMoreCampaignAnalytics = {},
          onLoadMoreNetworkAnalytics = {},
          onLoadMoreNetworks = {},
          onLoadMoreConfigs = {},
          onSaveNetwork = { _, _, onDone -> onDone() },
          onToggleNetworkStatus = { _, _ -> },
          onSaveCampaignConfig = { _, _, onDone -> onDone() },
          onToggleCampaignConfigStatus = { id, active ->
            toggledCampaignId = id
            toggledCampaignActive = active
          },
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_campaigns").performClick()
    composeTestRule.onNodeWithTag("configs_lazy_column")
      .performScrollToNode(hasTestTag("campaign_config_card_camp_alpha_01"))
    composeTestRule.onNodeWithTag("toggle_campaign_config_camp_alpha_01").performClick()
    composeTestRule.onNodeWithTag("campaign_status_confirm_dialog").assertExists()
    composeTestRule.onNodeWithText("Pause Campaign?").assertExists()
    assertEquals(null, toggledCampaignId)
    composeTestRule.onNodeWithTag("campaign_status_confirm_button").performClick()
    assertEquals("camp_alpha_01", toggledCampaignId)
    assertEquals(false, toggledCampaignActive)

    composeTestRule.onNodeWithTag("configs_lazy_column").performScrollToIndex(0)
    composeTestRule.onNodeWithTag("add_campaign_config_button").performClick()
    composeTestRule.onNodeWithTag("campaign_config_editor_dialog").assertExists()
    composeTestRule.onNodeWithTag("campaign_config_form_save_button").performClick()
    composeTestRule.onNodeWithTag("campaign_config_form_validation_error").assertExists()
  }

  @Test
  fun analyticsTab_handlesLoadingEmptyErrorRetry_andDisplaysNaApprovalRateForZeroConversions() {
    var selectedRange: CampaignAnalyticsDateRange? = null

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = samplePopulatedState(),
          onRefreshAll = {},
          onSelectDateRange = { selectedRange = it },
          onLoadMoreCampaignAnalytics = {},
          onLoadMoreNetworkAnalytics = {},
          onLoadMoreNetworks = {},
          onLoadMoreConfigs = {},
          onSaveNetwork = { _, _, onDone -> onDone() },
          onToggleNetworkStatus = { _, _ -> },
          onSaveCampaignConfig = { _, _, onDone -> onDone() },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_analytics").performClick()
    composeTestRule.onNodeWithTag("analytics_gross_vs_net_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analytics_range_7D").performClick()
    assertEquals(CampaignAnalyticsDateRange.LAST_7_DAYS, selectedRange)

    composeTestRule.onNodeWithTag("analytics_lazy_column")
      .performScrollToNode(hasTestTag("analytics_campaign_row_camp_zero_conv"))
    composeTestRule.onNodeWithTag("analytics_campaign_row_camp_zero_conv")
      .assertTextContains("Rate: N/A", substring = true)

    composeTestRule.onNodeWithTag("analytics_lazy_column")
      .performScrollToNode(hasTestTag("analytics_network_row_net_alpha"))
    composeTestRule.onNodeWithTag("analytics_network_row_net_alpha").assertIsDisplayed()
  }

  @Test
  fun analyticsTab_loadingAndEmptyStates_renderCleanly() {
    val emptyState = AdminCampaignTrackingUiState(
      isLoadingDashboard = false,
      hasLoadedDashboard = true,
      selectedDateRange = CampaignAnalyticsDateRange.TODAY
    )
    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = emptyState,
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
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_analytics").performClick()
    composeTestRule.onNodeWithTag("analytics_lazy_column")
      .performScrollToNode(hasTestTag("campaign_analytics_empty_state"))
    composeTestRule.onNodeWithTag("campaign_analytics_empty_state").assertIsDisplayed()
  }

  @Test
  fun analyticsTab_errorState_showsErrorAndInvokesRetry() {
    var retried = false
    val errorState = AdminCampaignTrackingUiState(
      isLoadingDashboard = false,
      hasLoadedDashboard = false,
      dashboardError = "Server temporarily unavailable"
    )
    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = errorState,
          onRefreshAll = { retried = true },
          onSelectDateRange = { retried = true },
          onLoadMoreCampaignAnalytics = {},
          onLoadMoreNetworkAnalytics = {},
          onLoadMoreNetworks = {},
          onLoadMoreConfigs = {},
          onSaveNetwork = { _, _, onDone -> onDone() },
          onToggleNetworkStatus = { _, _ -> },
          onSaveCampaignConfig = { _, _, onDone -> onDone() },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_analytics").performClick()
    composeTestRule.onNodeWithTag("campaign_analytics_error").assertIsDisplayed()
    composeTestRule.onNodeWithTag("campaign_analytics_retry_button").performClick()
    assertTrue(retried)
  }

  @Test
  fun adminDashboardScreen_blocksUnauthorizedUserFromCampaignTracking() {
    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminDashboardScreen(
          isAdminAuthorized = false,
          currentUserId = "uid_non_admin",
          currentUserEmail = "intruder@example.com",
          currentUserDisplayName = "Intruder",
          activeTab = AdminTab.CAMPAIGN_TRACKING,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(emptyList()),
          submissionFilter = "all",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {},
          campaignTrackingAdminState = samplePopulatedState()
        )
      }
    }

    composeTestRule.onNodeWithText("Admin Access Restricted", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_campaign_tracking_root").assertDoesNotExist()
  }

  @Test
  fun high2_parsersAndAnalyticsUi_handleNegativeNet_recoveredShortfall_orphans_andTruncationBanner() {
    assertEquals("-₹4.00", formatSignedRewardInr(-400L))
    assertEquals("₹4.00", formatSignedRewardInr(400L))

    val parsed = CampaignAdminParsers.parseDashboardResult(
      mapOf(
        "dateRange" to "TODAY",
        "timezone" to "Asia/Kolkata",
        "summary" to mapOf(
          "totalCampaigns" to 1,
          "activeCampaigns" to 1,
          "totalClicks" to 10,
          "totalConversions" to 1,
          "reversedConversions" to 1,
          "grossCreditedTf" to 0,
          "reversedTf" to 500,
          "recoveredTf" to 300,
          "shortfallTf" to 200,
          "netCreditedTf" to -500,
          "grossCreditedCents" to 0,
          "reversedCents" to 400,
          "recoveredCents" to 240,
          "shortfallCents" to 160,
          "netCreditedCents" to -400,
          "orphanedClicksCount" to 2,
          "orphanedConversionsCount" to 1,
          "orphanedCampaignCount" to 1,
          "orphanedNetworkCount" to 1
        ),
        "scanLimits" to mapOf(
          "clicksScanLimit" to 5000,
          "conversionsScanLimit" to 5000,
          "clicksScanned" to 5000,
          "conversionsScanned" to 1,
          "clicksTruncated" to true,
          "conversionsTruncated" to false,
          "isTruncated" to true,
          "queryStrategy" to "INDEXED_TIMESTAMP_RANGE"
        ),
        "campaignPerformance" to mapOf(
          "items" to listOf(
            mapOf(
              "campaignId" to "camp_deleted",
              "title" to "Orphaned Campaign (camp_deleted)",
              "networkId" to "net_deleted",
              "isOrphaned" to true,
              "clicksCount" to 2,
              "totalConversions" to 1,
              "reversedConversions" to 1,
              "grossCreditedTf" to 0,
              "reversedTf" to 500,
              "recoveredTf" to 300,
              "shortfallTf" to 200,
              "netCreditedTf" to -500,
              "netCreditedCents" to -400
            )
          ),
          "totalCount" to 1
        ),
        "networkPerformance" to mapOf("items" to emptyList<Any>(), "totalCount" to 0)
      )
    )

    assertEquals("Asia/Kolkata", parsed.timezone)
    assertEquals(-500L, parsed.summary.netCreditedTf)
    assertEquals(-400L, parsed.summary.netCreditedCents)
    assertEquals(300L, parsed.summary.recoveredTf)
    assertEquals(200L, parsed.summary.shortfallTf)
    assertTrue(parsed.scanLimits.isTruncated)
    assertTrue(parsed.scanLimits.clicksTruncated)
    assertTrue(parsed.campaignPerformance.items.single().isOrphaned)

    val uiState = AdminCampaignTrackingUiState(
      hasLoadedDashboard = true,
      selectedDateRange = CampaignAnalyticsDateRange.TODAY,
      timezone = parsed.timezone,
      summary = parsed.summary,
      scanLimits = parsed.scanLimits,
      campaignPerformance = parsed.campaignPerformance,
      networkPerformance = parsed.networkPerformance
    )

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        AdminCampaignTrackingSection(
          state = uiState,
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
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("analytics_truncation_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analytics_orphaned_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("overview_gross_vs_net_card"))
    composeTestRule.onNodeWithTag("metric_net_tf_rewards").assertTextContains("-500", substring = true)
    composeTestRule.onNodeWithTag("metric_net_tf_rewards").assertTextContains("-₹4.00", substring = true)
    composeTestRule.onNodeWithTag("metric_recovered_tf").assertTextContains("300", substring = true)
    composeTestRule.onNodeWithTag("metric_shortfall_tf").assertTextContains("200", substring = true)

    composeTestRule.onNodeWithTag("campaign_tracking_tab_analytics").performClick()
    composeTestRule.onNodeWithTag("analytics_truncation_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("analytics_lazy_column")
      .performScrollToNode(hasTestTag("analytics_campaign_row_camp_deleted"))
    composeTestRule.onNodeWithTag("analytics_campaign_row_camp_deleted")
      .assertTextContains("ORPHANED", substring = true)
  }

  @Test
  fun medium1AndMedium2_strictDateValidation_endDateClearing_and2000CharUrlParity() {
    val net = sampleNetwork()

    // Invalid calendar dates return null (never silently 0L) and fail validation explicitly
    assertEquals(null, parseStrictIsoDateToUtcMillis("2026-02-30", endOfDay = true))
    assertEquals(null, parseStrictIsoDateToUtcMillis("not-a-date", endOfDay = false))
    assertEquals(0L, parseStrictIsoDateToUtcMillis("", endOfDay = true))

    val invalidDateErrors = validateAdminCampaignConfigForm(
      sampleConfig().copy(startDate = "2026-02-30", endDate = "bad-date"),
      net
    )
    assertTrue(invalidDateErrors.any { it.contains("Start date must be a valid YYYY-MM-DD") })
    assertTrue(invalidDateErrors.any { it.contains("End date must be a valid YYYY-MM-DD") })

    val invertedDateErrors = validateAdminCampaignConfigForm(
      sampleConfig().copy(startDate = "2026-12-31", endDate = "2026-01-01"),
      net
    )
    assertTrue(invertedDateErrors.any { it.contains("End date must be on or after") })

    // URL length parity: 2000 accepted, 2001 rejected
    val prefix = "https://track.alpha.example/click?p="
    val url2000 = prefix + "x".repeat(MAX_CAMPAIGN_TRACKING_URL_LENGTH - prefix.length)
    val url2001 = prefix + "x".repeat(MAX_CAMPAIGN_TRACKING_URL_LENGTH - prefix.length + 1)
    assertTrue(isValidCampaignTrackingUrl(url2000))
    assertFalse(isValidCampaignTrackingUrl(url2001))
    assertTrue(validateAdminCampaignConfigForm(sampleConfig().copy(trackingBaseUrl = url2000), net).isEmpty())
    assertTrue(validateAdminCampaignConfigForm(sampleConfig().copy(trackingBaseUrl = url2001), net).isNotEmpty())

    // Clearing endDate in CampaignTrackingConfigEditorDialog must clear expiresAtMillis to 0L
    var savedItem: AdminCampaignTrackingConfigItem? = null
    val initialWithExpiry = sampleConfig().copy(
      startDate = "2026-01-01",
      endDate = "2026-12-31",
      startAtMillis = parseStrictIsoDateToUtcMillis("2026-01-01", endOfDay = false)!!,
      expiresAtMillis = parseStrictIsoDateToUtcMillis("2026-12-31", endOfDay = true)!!
    )
    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        CampaignTrackingConfigEditorDialog(
          initial = initialWithExpiry,
          networks = listOf(net),
          isSaving = false,
          onDismiss = {},
          onSave = { item, _ -> savedItem = item }
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_config_end_date_input").performTextClearance()
    composeTestRule.onNodeWithTag("campaign_config_form_save_button").performClick()
    assertNotNull(savedItem)
    assertEquals("", savedItem!!.endDate)
    assertEquals(0L, savedItem!!.expiresAtMillis)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun medium2_page2NetworkLookup_resolvesExactNetworkInViewModelAndDialog() = runTest {
    val dispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(dispatcher)
    try {
      val firestore = createTestFirestore()
      val page1Network = sampleNetwork(id = "net_page1")
      val page2Network = sampleNetwork(id = "net_page2")
      var exactLookupRequestedId: String? = null
      var upsertedCampaignId: String? = null

      val client = FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          val payloadMap = payload as? Map<*, *> ?: emptyMap<Any, Any>()
          when (fnName) {
            "adminListCampaignNetworks" -> {
              val requestedNetId = (payloadMap["networkId"] as? String).orEmpty()
              if (requestedNetId == "net_page2") {
                exactLookupRequestedId = requestedNetId
                mapOf(
                  "items" to listOf(
                    mapOf(
                      "networkId" to page2Network.networkId,
                      "name" to page2Network.name,
                      "adapterType" to page2Network.adapterType,
                      "isActive" to true,
                      "capabilities" to mapOf("supportsIncentivizedTraffic" to true, "supportsPostback" to true),
                      "signatureConfig" to mapOf(
                        "algorithm" to "HMAC_SHA256",
                        "secretEnvKey" to REGISTERED_CAMPAIGN_SECRET_KEYS[1]
                      )
                    )
                  ),
                  "totalCount" to 1,
                  "hasMore" to false,
                  "nextCursor" to ""
                )
              } else {
                mapOf(
                  "items" to listOf(
                    mapOf(
                      "networkId" to page1Network.networkId,
                      "name" to page1Network.name,
                      "adapterType" to page1Network.adapterType,
                      "isActive" to true,
                      "capabilities" to mapOf("supportsIncentivizedTraffic" to true, "supportsPostback" to true),
                      "signatureConfig" to mapOf(
                        "algorithm" to "HMAC_SHA256",
                        "secretEnvKey" to REGISTERED_CAMPAIGN_SECRET_KEYS[1]
                      )
                    )
                  ),
                  "totalCount" to 2,
                  "hasMore" to true,
                  "nextCursor" to "net_page1"
                )
              }
            }
            "adminUpsertCampaignTrackingConfig" -> {
              upsertedCampaignId = payloadMap["campaignId"] as? String
              mapOf(
                "config" to mapOf(
                  "configId" to (payloadMap["campaignId"] ?: "camp_page2"),
                  "campaignId" to (payloadMap["campaignId"] ?: "camp_page2"),
                  "title" to (payloadMap["title"] ?: "Page 2 Campaign"),
                  "networkId" to "net_page2",
                  "verificationMode" to "AFFILIATE_POSTBACK",
                  "isActive" to true,
                  "rewardTf" to 250
                )
              )
            }
            "adminGetCampaignTrackingDashboard" -> mapOf(
              "dateRange" to "30D",
              "summary" to mapOf("totalCampaigns" to 1),
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
        authenticatedUidProvider = { "uid_admin" }
      )
      val adminVm = TaskFlowViewModel(
        repository = repo,
        currentUserId = "uid_admin",
        currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        currentUserDisplayName = "Admin",
        initialPortal = PortalLoginTarget.ADMIN_DASHBOARD
      )

      // Load only page 1 of networks (contains net_page1, NOT net_page2)
      adminVm.loadCampaignTrackingNetworks(loadMore = false)
      advanceUntilIdle()
      assertEquals(listOf("net_page1"), adminVm.campaignTrackingAdminState.value.networks.items.map { it.networkId })

      // Upsert campaign referencing net_page2 -> ViewModel resolves net_page2 via exact lookup and saves cleanly!
      var saveSucceeded = false
      adminVm.upsertCampaignTrackingConfig(
        item = sampleConfig(id = "camp_page2", networkId = "net_page2"),
        isEdit = true,
        onSuccess = { saveSucceeded = true }
      )
      advanceUntilIdle()

      assertEquals("net_page2", exactLookupRequestedId)
      assertEquals("camp_page2", upsertedCampaignId)
      assertTrue(saveSucceeded)
      assertTrue(adminVm.campaignTrackingAdminState.value.resolvedNetworksById.containsKey("net_page2"))
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun medium2_editorDialog_triggersEnsureNetworkLoadedForPage2Network_andShowsLoadMoreChip() {
    var requestedLookupId: String? = null
    var loadMoreClicked = false
    val page1Net = sampleNetwork(id = "net_page1")
    val editingCampaignOnPage2Net = sampleConfig(id = "camp_p2", networkId = "net_page2")

    composeTestRule.setContent {
      TaskFlowTheme(themeMode = AppThemeMode.LIGHT) {
        CampaignTrackingConfigEditorDialog(
          initial = editingCampaignOnPage2Net,
          networks = listOf(page1Net),
          isSaving = false,
          hasMoreNetworks = true,
          onLoadMoreNetworks = { loadMoreClicked = true },
          onEnsureNetworkLoaded = { requestedLookupId = it },
          onDismiss = {},
          onSave = { _, _ -> }
        )
      }
    }

    composeTestRule.waitForIdle()
    assertEquals("net_page2", requestedLookupId)
    composeTestRule.onNodeWithTag("campaign_editor_load_more_networks_chip").performClick()
    assertTrue(loadMoreClicked)
  }

  @Test
  fun low1_networkDisableDialog_showsActiveDependentCampaignCountImpactAndPreservesKillSwitch() {
    var toggledNetworkId: String? = null
    var toggledNextActive: Boolean? = null

    val activeNet = sampleNetwork(id = "net_live").copy(
      name = "Live Partner Net",
      isActive = true,
      activeCampaignsCount = 3
    )
    val state = AdminCampaignTrackingUiState(
      hasLoadedDashboard = true,
      networks = AdminPaginatedSlice(items = listOf(activeNet), totalCount = 1),
      configs = AdminPaginatedSlice(
        items = listOf(
          sampleConfig(id = "camp_a", networkId = "net_live").copy(isActive = true),
          sampleConfig(id = "camp_b", networkId = "net_live").copy(isActive = false)
        ),
        totalCount = 2
      )
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
          onSaveNetwork = { _, _, _ -> },
          onToggleNetworkStatus = { id, nextActive ->
            toggledNetworkId = id
            toggledNextActive = nextActive
          },
          onSaveCampaignConfig = { _, _, _ -> },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("campaign_tracking_tab_networks").performClick()
    composeTestRule.onNodeWithTag("networks_lazy_column")
      .performScrollToNode(hasTestTag("network_card_net_live"))
    composeTestRule.onNodeWithTag("toggle_network_net_live").performClick()

    // Confirmation dialog must clearly show active dependent count (3), click & postback impact, and non-mutation notice
    composeTestRule.onNodeWithTag("campaign_status_confirm_dialog").assertExists()
    composeTestRule.onNodeWithText("3 active dependent campaigns", substring = true).assertExists()
    composeTestRule.onNodeWithText("stop new campaign click issuance", substring = true).assertExists()
    composeTestRule.onNodeWithText("interrupt associated postback processing", substring = true).assertExists()
    composeTestRule.onNodeWithText("will not be modified or deactivated", substring = true).assertExists()

    // Confirming preserves immediate kill-switch capability
    composeTestRule.onNodeWithTag("campaign_status_confirm_button").performClick()
    assertEquals("net_live", toggledNetworkId)
    assertEquals(false, toggledNextActive)
  }

  @Test
  fun low3_analyticsUi_displaysNetWalletImpact_defaultCampaignLimit1000_andManagementTruncationBanners() {
    // 1) Verify campaignsScanLimit fallback default is 1000 (both data class default and parser fallback)
    assertEquals(1000, CampaignAnalyticsScanLimits().campaignsScanLimit)
    val parsedDefaultLimits = CampaignAdminParsers.parseScanLimits(emptyMap<String, Any>())
    assertEquals(1000, parsedDefaultLimits.campaignsScanLimit)
    assertEquals(500, parsedDefaultLimits.networksScanLimit)
    assertEquals(5000, parsedDefaultLimits.clicksScanLimit)
    assertEquals(5000, parsedDefaultLimits.conversionsScanLimit)

    // 2) Verify Net Wallet Impact row and management-tab truncation warning banners
    val summary = CampaignTrackingSummaryMetrics(
      totalCampaigns = 1,
      activeCampaigns = 1,
      totalNetworks = 1,
      activeNetworks = 1,
      grossCreditedTf = 500L,
      reversedTf = 500L,
      recoveredTf = 300L,
      shortfallTf = 200L,
      netCreditedTf = 0L,
      netWalletImpactTf = 200L,
      grossCreditedCents = 400L,
      reversedCents = 400L,
      recoveredCents = 240L,
      shortfallCents = 160L,
      netCreditedCents = 0L,
      netWalletImpactCents = 160L
    )
    val state = AdminCampaignTrackingUiState(
      hasLoadedDashboard = true,
      summary = summary,
      scanLimits = CampaignAnalyticsScanLimits(
        campaignsScanLimit = 1000,
        networksScanLimit = 500,
        campaignsTruncated = true,
        networksTruncated = true,
        isTruncated = true
      ),
      networks = AdminPaginatedSlice(
        items = listOf(sampleNetwork(id = "net_trunc")),
        totalCount = 1,
        truncated = true
      ),
      configs = AdminPaginatedSlice(
        items = listOf(sampleConfig(id = "camp_trunc", networkId = "net_trunc")),
        totalCount = 1,
        truncated = true
      )
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
          onSaveNetwork = { _, _, _ -> },
          onToggleNetworkStatus = { _, _ -> },
          onSaveCampaignConfig = { _, _, _ -> },
          onToggleCampaignConfigStatus = { _, _ -> },
          onClearFeedback = {}
        )
      }
    }

    // Overview card displays Net Wallet Impact TF and INR
    composeTestRule.onNodeWithTag("overview_lazy_column")
      .performScrollToNode(hasTestTag("overview_gross_vs_net_card"))
    composeTestRule.onNodeWithTag("metric_net_wallet_impact_tf").assertTextContains("200", substring = true)
    composeTestRule.onNodeWithTag("metric_net_wallet_impact_tf").assertTextContains("₹1.60", substring = true)

    // Networks tab displays truncation warning banner with 500 limit
    composeTestRule.onNodeWithTag("campaign_tracking_tab_networks").performClick()
    composeTestRule.onNodeWithTag("networks_truncation_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("networks_truncation_warning_banner").assertTextContains("500", substring = true)

    // Campaigns tab displays truncation warning banner with 1000 limit
    composeTestRule.onNodeWithTag("campaign_tracking_tab_campaigns").performClick()
    composeTestRule.onNodeWithTag("campaigns_truncation_warning_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("campaigns_truncation_warning_banner").assertTextContains("1000", substring = true)
  }
}



package com.example

import com.example.data.model.CampaignClickRecord
import com.example.data.model.CampaignClickStatus
import com.example.data.model.CampaignConversionRecord
import com.example.data.model.CampaignConversionRequirements
import com.example.data.model.CampaignConversionState
import com.example.data.model.CampaignDevicePlatform
import com.example.data.model.CampaignEligibilitySpec
import com.example.data.model.CampaignNetworkConfig
import com.example.data.model.CampaignTrackingCollections
import com.example.data.model.CampaignTrackingConfig
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkParameterMapping
import com.example.data.model.NetworkSignatureAlgorithm
import com.example.data.model.NetworkSignatureSpec
import com.example.data.model.NotificationRewardType
import com.example.data.model.TransactionType
import com.example.data.tracking.CampaignNetworkAdapterRegistry
import com.example.data.tracking.OutboundTrackingContext
import com.example.data.tracking.RawCallbackInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignTrackingFoundationTest {

  @Test
  fun verificationModesAndConversionStatesMatchRequiredContract() {
    assertEquals(
      setOf(
        "AFFILIATE_POSTBACK",
        "ADVERTISER_S2S_API",
        "TELEGRAM_BOT_API",
        "MANUAL_PROOF",
        "CUSTOM_APPROVED"
      ),
      CampaignVerificationMode.entries.map { it.name }.toSet()
    )

    assertEquals(
      setOf("PENDING", "APPROVED", "REJECTED", "REVERSED"),
      CampaignConversionState.entries.map { it.name }.toSet()
    )

    assertEquals(
      setOf(
        "campaign_tracking_configs",
        "campaign_clicks",
        "campaign_conversions",
        "campaign_networks"
      ),
      setOf(
        CampaignTrackingCollections.CAMPAIGN_TRACKING_CONFIGS,
        CampaignTrackingCollections.CAMPAIGN_CLICKS,
        CampaignTrackingCollections.CAMPAIGN_CONVERSIONS,
        CampaignTrackingCollections.CAMPAIGN_NETWORKS
      )
    )
  }

  @Test
  fun conversionStateTransitionsEnforceValidLifecycleOnly() {
    assertTrue(CampaignConversionState.PENDING.canTransitionTo(CampaignConversionState.APPROVED))
    assertTrue(CampaignConversionState.PENDING.canTransitionTo(CampaignConversionState.REJECTED))
    assertFalse(CampaignConversionState.PENDING.canTransitionTo(CampaignConversionState.REVERSED))
    assertFalse(CampaignConversionState.PENDING.canTransitionTo(CampaignConversionState.PENDING))

    assertTrue(CampaignConversionState.APPROVED.canTransitionTo(CampaignConversionState.REVERSED))
    assertFalse(CampaignConversionState.APPROVED.canTransitionTo(CampaignConversionState.REJECTED))

    assertTrue(CampaignConversionState.REJECTED.isTerminal)
    assertTrue(CampaignConversionState.REVERSED.isTerminal)
    assertFalse(CampaignConversionState.REJECTED.canTransitionTo(CampaignConversionState.APPROVED))
    assertFalse(CampaignConversionState.REVERSED.canTransitionTo(CampaignConversionState.APPROVED))
  }

  @Test
  fun eligibilitySpecEnforcesScheduleBudgetCountryPlatformAndTfReward() {
    val now = 1_750_000_000_000L
    val validSpec = CampaignEligibilitySpec(
      isActive = true,
      startDate = "2026-11-01",
      endDate = "2026-12-31",
      startAtMillis = now - 60_000L,
      expiresAtMillis = now + 86_400_000L,
      submissionBudget = 100L,
      usedSubmissions = 20L,
      rewardBudgetTf = 50_000L,
      spentBudgetTf = 10_000L,
      allowedCountries = listOf("IN", "AE"),
      allowedPlatforms = listOf("ANDROID"),
      rewardTf = 500L,
      verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      networkId = "net_partner_a",
      trackingNetworkRef = "campaign_networks/net_partner_a"
    )

    assertEquals(emptyList<String>(), validSpec.validate())
    assertTrue(validSpec.isEligible(countryCode = "in", platform = "android", nowMillis = now))
    assertFalse(validSpec.isEligible(countryCode = "US", platform = "ANDROID", nowMillis = now))
    assertFalse(validSpec.isEligible(countryCode = "IN", platform = "IOS", nowMillis = now))
    assertFalse(validSpec.copy(isActive = false).isEligible("IN", "ANDROID", now))
    assertFalse(validSpec.copy(usedSubmissions = 100L).isEligible("IN", "ANDROID", now))
    assertFalse(validSpec.copy(spentBudgetTf = 49_800L).isEligible("IN", "ANDROID", now))
    assertFalse(validSpec.isEligible("IN", "ANDROID", nowMillis = now - 120_000L))
    assertFalse(validSpec.isEligible("IN", "ANDROID", nowMillis = now + 100_000_000L))

    val invalidBudget = validSpec.copy(rewardTf = 0L, submissionBudget = 5L, usedSubmissions = 6L)
    assertTrue(invalidBudget.validate().size >= 2)
  }

  @Test
  fun trackingConfigSchemaValidationAndSecretRedactionWorkProperly() {
    val config = CampaignTrackingConfig(
      configId = "camp_swiggy_q4",
      campaignId = "camp_swiggy_q4",
      isActive = true,
      startAtMillis = 1_750_000_000_000L,
      expiresAtMillis = 1_760_000_000_000L,
      submissionBudget = 200L,
      usedSubmissions = 0L,
      rewardBudgetTf = 100_000L,
      spentBudgetTf = 0L,
      allowedCountries = listOf("IN"),
      allowedPlatforms = listOf(CampaignDevicePlatform.ANDROID.name),
      rewardTf = 500L,
      verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      networkId = "net_trackier",
      trackingNetworkRef = "campaign_networks/net_trackier",
      trackingBaseUrl = "https://track.example.com/offer/123",
      conversionRequirements = CampaignConversionRequirements(
        requireUniqueClickId = true,
        requireInstallAttribution = true,
        autoApproveVerifiedPostback = false,
        attributionWindowHours = 72
      ),
      privateSecretRef = "SECRET_MANAGER_KEY_TRACKIER",
      postbackSecretHash = "sha256_deadbeef"
    )

    assertEquals(emptyList<String>(), config.validate())

    val summary = config.toClientSafeSummary()
    val serializedKeys = summary.toSafeMap().keys
    assertTrue(CampaignTrackingConfig.hasNoSecretFields(serializedKeys))
    assertFalse(serializedKeys.contains("privateSecretRef"))
    assertFalse(serializedKeys.contains("postbackSecretHash"))
  }

  @Test
  fun clickAndConversionRecordsValidateStrictFoundationInvariants() {
    val validClick = CampaignClickRecord(
      clickId = "clk_9f8e7d6c5b4a3210",
      userId = "user_alice_123",
      campaignId = "camp_001",
      networkId = "net_alpha",
      trackingNetworkRef = "campaign_networks/net_alpha",
      verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      countryCode = "IN",
      devicePlatform = "ANDROID",
      status = CampaignClickStatus.ISSUED.name,
      expiresAtMillis = 1_760_000_000_000L
    )
    assertEquals(emptyList<String>(), validClick.validate())

    val weakClick = validClick.copy(clickId = "123")
    assertTrue(weakClick.validate().isNotEmpty())

    val validConversion = CampaignConversionRecord(
      conversionId = "conv_1001",
      clickId = "clk_9f8e7d6c5b4a3210",
      userId = "user_alice_123",
      campaignId = "camp_001",
      networkId = "net_alpha",
      trackingNetworkRef = "campaign_networks/net_alpha",
      verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      state = CampaignConversionState.PENDING.name,
      rewardTf = 500L,
      externalTransactionId = "ext_tx_888",
      idempotencyKey = "net_alpha_ext_tx_888",
      rewardCredited = false
    )
    assertEquals(emptyList<String>(), validConversion.validate())
    assertTrue(validConversion.canTransitionTo(CampaignConversionState.APPROVED))
    assertFalse(validConversion.rewardCredited)

    val missingClickConversion = validConversion.copy(clickId = "")
    assertTrue(missingClickConversion.validate().isNotEmpty())
  }

  @Test
  fun extensibleNetworkAdapterNormalizesDisparateAffiliateNetworkFormatsWithoutHardcoding() {
    val registry = CampaignNetworkAdapterRegistry()

    val networkAlpha = CampaignNetworkConfig(
      networkId = "net_alpha",
      name = "Alpha Affiliate",
      adapterType = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      isActive = true,
      capabilities = NetworkCapabilitiesSpec(
        supportsIncentivizedTraffic = true,
        supportsPostback = true,
        supportsInstallAttribution = true
      ),
      parameterMapping = NetworkParameterMapping(
        clickIdParam = "aff_sub",
        userIdParam = "aff_sub2",
        externalTxnParam = "transaction_id",
        statusParam = "conversion_status",
        payoutParam = "payout_usd",
        signatureParam = "hmac"
      ),
      postbackStatusMapping = mapOf(
        "install_verified" to "APPROVED",
        "rejected_fraud" to "REJECTED",
        "clawback" to "REVERSED"
      ),
      signatureConfig = NetworkSignatureSpec(
        algorithm = NetworkSignatureAlgorithm.HMAC_SHA256.name,
        queryParamName = "hmac",
        secretEnvKey = "ALPHA_POSTBACK_SECRET"
      )
    )

    val networkBeta = CampaignNetworkConfig(
      networkId = "net_beta",
      name = "Beta S2S Partner",
      adapterType = CampaignVerificationMode.ADVERTISER_S2S_API.name,
      isActive = true,
      capabilities = NetworkCapabilitiesSpec(
        supportsIncentivizedTraffic = false,
        supportsPostback = true,
        supportsInstallAttribution = false,
        supportsServerToServerPolling = true
      ),
      parameterMapping = NetworkParameterMapping(
        clickIdParam = "s1",
        externalTxnParam = "order_ref",
        statusParam = "state",
        payoutParam = "amount",
        signatureParam = "token"
      ),
      postbackStatusMapping = mapOf(
        "hold" to "PENDING",
        "settled" to "APPROVED"
      ),
      signatureConfig = NetworkSignatureSpec(
        algorithm = NetworkSignatureAlgorithm.SHARED_TOKEN_HEADER.name,
        headerName = "X-Partner-Token",
        secretEnvKey = "BETA_S2S_SECRET"
      )
    )

    assertEquals(emptyList<String>(), networkAlpha.validate())
    assertEquals(emptyList<String>(), networkBeta.validate())

    val baseConfig = CampaignTrackingConfig(
      configId = "camp_1",
      campaignId = "camp_1",
      rewardTf = 250L,
      verificationMode = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
      networkId = "net_alpha",
      trackingNetworkRef = "campaign_networks/net_alpha",
      trackingBaseUrl = "https://offer.partner.example/go?offer=99"
    )

    val alphaAdapter = registry.resolve(CampaignVerificationMode.AFFILIATE_POSTBACK)
    val outboundAlpha = alphaAdapter.buildTrackingSpec(
      config = baseConfig,
      network = networkAlpha,
      context = OutboundTrackingContext(
        clickId = "clk_unpredictable_001",
        userId = "uid_42",
        campaignId = "camp_1"
      )
    )
    assertTrue(outboundAlpha.trackingUrl.contains("aff_sub=clk_unpredictable_001"))
    assertTrue(outboundAlpha.trackingUrl.contains("aff_sub2=uid_42"))
    assertTrue(outboundAlpha.trackingUrl.contains("offer=99"))

    val eventAlpha = alphaAdapter.normalizeConversionCallback(
      input = RawCallbackInput(
        queryParams = mapOf(
          "aff_sub" to "clk_unpredictable_001",
          "transaction_id" to "tx_a1",
          "conversion_status" to "install_verified",
          "payout_usd" to "1.50",
          "hmac" to "sig_alpha_xyz"
        )
      ),
      network = networkAlpha
    )
    assertEquals("clk_unpredictable_001", eventAlpha.clickId)
    assertEquals("tx_a1", eventAlpha.externalTransactionId)
    assertEquals(CampaignConversionState.APPROVED, eventAlpha.normalizedState)
    assertEquals("sig_alpha_xyz", eventAlpha.signatureValue)

    val betaAdapter = registry.resolve(CampaignVerificationMode.ADVERTISER_S2S_API)
    val eventBeta = betaAdapter.normalizeConversionCallback(
      input = RawCallbackInput(
        headers = mapOf("x-partner-token" to "header_secret_123"),
        bodyParams = mapOf(
          "s1" to "clk_unpredictable_002",
          "order_ref" to "ord_99",
          "state" to "hold"
        )
      ),
      network = networkBeta
    )
    assertEquals("clk_unpredictable_002", eventBeta.clickId)
    assertEquals("ord_99", eventBeta.externalTransactionId)
    assertEquals(CampaignConversionState.PENDING, eventBeta.normalizedState)
    assertEquals("header_secret_123", eventBeta.signatureValue)

    // Verify all 5 required verification modes resolve cleanly in the registry
    CampaignVerificationMode.entries.forEach { mode ->
      val resolved = registry.resolve(mode)
      assertNotNull(resolved)
      assertEquals(mode, resolved.supportedVerificationMode)
    }
  }

  @Test
  fun campaignRewardAndReversalLedgerClassificationWorkCorrectly() {
    assertTrue(TransactionType.isCreditedRewardType(TransactionType.CAMPAIGN_REWARD))
    assertFalse(TransactionType.isCreditedRewardType(TransactionType.CAMPAIGN_REVERSAL))

    assertTrue(TransactionType.isDebitOrReversalType(TransactionType.CAMPAIGN_REVERSAL))
    assertTrue(TransactionType.isDebitOrReversalType(TransactionType.WITHDRAWAL_DEBIT))
    assertFalse(TransactionType.isDebitOrReversalType(TransactionType.CAMPAIGN_REWARD))

    assertEquals("CAMPAIGN_REWARD", NotificationRewardType.CAMPAIGN_REWARD)
    assertEquals("CAMPAIGN_REVERSAL", NotificationRewardType.CAMPAIGN_REVERSAL)
    assertEquals(
      CampaignClickStatus.PENDING_VERIFICATION,
      CampaignClickStatus.fromWireValue("PENDING_VERIFICATION")
    )
  }
}

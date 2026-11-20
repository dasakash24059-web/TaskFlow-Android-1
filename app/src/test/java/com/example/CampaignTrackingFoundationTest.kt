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
import com.example.data.model.LeaderboardPeriod
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkParameterMapping
import com.example.data.model.NetworkSignatureAlgorithm
import com.example.data.model.NetworkSignatureSpec
import com.example.data.model.NotificationRewardType
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.computeLeaderboardEntriesForPeriod
import com.example.data.model.computeNetCreditedEarningsCoins
import com.example.data.tracking.CampaignNetworkAdapterRegistry
import com.example.data.tracking.OutboundTrackingContext
import com.example.data.tracking.RawCallbackInput
import com.google.firebase.Timestamp
import java.util.Date
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

  @Test
  fun leaderboardAllTimeThisMonthThisWeekSubtractCampaignReversalsWithoutDoubleSubtraction() {
    // Anchor at Thursday 2026-06-18 12:00 IST so "This Week" starts Mon 2026-06-15 00:00 IST
    // and "This Month" starts 2026-06-01 00:00 IST.
    val dayMs = 24L * 60L * 60L * 1000L
    val nowMillis = 1_781_764_200_000L // 2026-06-18T06:30:00Z = 2026-06-18T12:00:00 IST
    val twoDaysAgo = Timestamp(Date(nowMillis - 2 * dayMs)) // This Week & This Month
    val twelveDaysAgo = Timestamp(Date(nowMillis - 12 * dayMs)) // Earlier This Month (not This Week)
    val fortyDaysAgo = Timestamp(Date(nowMillis - 40 * dayMs)) // Prior Month (All Time only)

    val alice = UserProfile(
      id = "u_alice",
      userId = "u_alice",
      displayName = "Alice",
      email = "alice@example.com",
      role = "user",
      approvalStatus = "approved",
      balanceCoins = 300L, // synced net wallet balance after reversal
      completedTasksCount = 2L
    )
    val bob = UserProfile(
      id = "u_bob",
      userId = "u_bob",
      displayName = "Bob",
      email = "bob@example.com",
      role = "user",
      approvalStatus = "approved",
      balanceCoins = 400L,
      completedTasksCount = 1L
    )

    val transactions = listOf(
      // Alice: 500 campaign reward + 200 spin reward this week, reversed by 500 (duplicate reversal records included)
      TransactionRecord(
        id = "conv_credit_c1",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.CAMPAIGN_REWARD,
        amountCoins = 500L,
        status = "CREDITED",
        referenceId = "conv_c1",
        createdAt = twoDaysAgo
      ),
      TransactionRecord(
        id = "conv_rev_c1",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 500L,
        status = "REVERSED",
        referenceId = "conv_c1",
        originalTransactionId = "conv_credit_c1",
        createdAt = Timestamp(Date(nowMillis - dayMs))
      ),
      // Duplicate reversal pointing to the same originalTransactionId (must NOT double-subtract)
      TransactionRecord(
        id = "conv_rev_c1_dup",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 500L,
        status = "REVERSED",
        referenceId = "conv_c1",
        originalTransactionId = "conv_credit_c1",
        createdAt = Timestamp(Date(nowMillis - dayMs))
      ),
      TransactionRecord(
        id = "spin_1",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.SPIN_EARN,
        amountCoins = 200L,
        status = "CREDITED",
        createdAt = twoDaysAgo
      ),
      // Alice earlier this month (not this week): 300 daily check-in - 200 campaign reversed down to 100
      TransactionRecord(
        id = "conv_credit_c2",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.CAMPAIGN_REWARD,
        amountCoins = 200L,
        status = "CREDITED",
        referenceId = "conv_c2",
        createdAt = twelveDaysAgo
      ),
      TransactionRecord(
        id = "conv_rev_c2",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 200L,
        status = "REVERSED",
        referenceId = "conv_c2",
        originalTransactionId = "conv_credit_c2",
        // Reversed in the current week! Must attach to c2 so c2 becomes 0 without penalizing spin_1 in This Week.
        createdAt = twoDaysAgo
      ),
      TransactionRecord(
        id = "checkin_1",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.DAILY_CHECKIN,
        amountCoins = 100L,
        status = "CREDITED",
        createdAt = twelveDaysAgo
      ),
      // Withdrawal debit must remain ignored in leaderboard earnings
      TransactionRecord(
        id = "wd_1",
        userId = "u_alice",
        userName = "Alice",
        type = TransactionType.WITHDRAWAL_DEBIT,
        amountCoins = 125L,
        status = "PAID",
        createdAt = twoDaysAgo
      ),
      // Bob prior month only: 400 promo reward
      TransactionRecord(
        id = "promo_bob_1",
        userId = "u_bob",
        userName = "Bob",
        type = TransactionType.PROMO_CODE,
        amountCoins = 400L,
        status = "CREDITED",
        createdAt = fortyDaysAgo
      )
    )

    val thisWeek = computeLeaderboardEntriesForPeriod(
      users = listOf(alice, bob),
      transactions = transactions,
      period = LeaderboardPeriod.THIS_WEEK,
      nowMillis = nowMillis
    )
    assertEquals(1, thisWeek.size)
    assertEquals("u_alice", thisWeek.first().userId)
    assertEquals(200L, thisWeek.first().earnedCoins)

    val thisMonth = computeLeaderboardEntriesForPeriod(
      users = listOf(alice, bob),
      transactions = transactions,
      period = LeaderboardPeriod.THIS_MONTH,
      nowMillis = nowMillis
    )
    assertEquals(1, thisMonth.size)
    assertEquals("u_alice", thisMonth.first().userId)
    assertEquals(300L, thisMonth.first().earnedCoins)

    val allTime = computeLeaderboardEntriesForPeriod(
      users = listOf(alice, bob),
      transactions = transactions,
      period = LeaderboardPeriod.ALL_TIME,
      nowMillis = nowMillis
    )
    assertEquals(2, allTime.size)
    assertEquals("u_bob", allTime[0].userId)
    assertEquals(400L, allTime[0].earnedCoins)
    assertEquals("u_alice", allTime[1].userId)
    assertEquals(300L, allTime[1].earnedCoins)
  }

  @Test
  fun netCreditedEarningsCoinsReflectsFullPartialAndDuplicateReversalsForUserAndAdminTotals() {
    val txs = listOf(
      TransactionRecord(
        id = "conv_credit_1",
        userId = "u1",
        type = TransactionType.CAMPAIGN_REWARD,
        amountCoins = 500L,
        referenceId = "conv_1",
        status = "CREDITED"
      ),
      TransactionRecord(
        id = "conv_rev_1",
        userId = "u1",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 500L,
        referenceId = "conv_1",
        originalTransactionId = "conv_credit_1",
        status = "REVERSED"
      ),
      // Duplicate reversal record must not reduce remaining earnings below their net value
      TransactionRecord(
        id = "conv_rev_1_dup",
        userId = "u1",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 500L,
        referenceId = "conv_1",
        originalTransactionId = "conv_credit_1",
        status = "REVERSED"
      ),
      TransactionRecord(
        id = "watch_1",
        userId = "u1",
        type = TransactionType.WATCH_EARN,
        amountCoins = 125L,
        status = "CREDITED"
      ),
      TransactionRecord(
        id = "wd_debit",
        userId = "u1",
        type = TransactionType.WITHDRAWAL_DEBIT,
        amountCoins = 125L,
        status = "PAID"
      ),
      TransactionRecord(
        id = "wd_refund",
        userId = "u1",
        type = TransactionType.WITHDRAWAL_REFUND,
        amountCoins = 125L,
        status = "REFUNDED"
      )
    )

    // 500 - 500 + 125 = 125 (duplicate reversal capped, withdrawals ignored)
    assertEquals(125L, computeNetCreditedEarningsCoins(txs))

    // Full single-campaign reversal nets to 0 (no negative balance, no inflated earnings)
    val fullReversalOnly = listOf(
      TransactionRecord(
        id = "conv_credit_only",
        userId = "u1",
        type = TransactionType.CAMPAIGN_REWARD,
        amountCoins = 500L,
        referenceId = "conv_only",
        status = "CREDITED"
      ),
      TransactionRecord(
        id = "conv_rev_only",
        userId = "u1",
        type = TransactionType.CAMPAIGN_REVERSAL,
        amountCoins = 500L,
        referenceId = "conv_only",
        originalTransactionId = "conv_credit_only",
        status = "REVERSED"
      )
    )
    assertEquals(0L, computeNetCreditedEarningsCoins(fullReversalOnly))
  }
}

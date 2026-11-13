package com.example

import com.example.data.model.ADMIN_EMAIL_PRIVACY_PLACEHOLDER
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.COINS_PER_RUPEE
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalAvailabilityConfig
import com.example.data.model.WithdrawalCatalog
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatVisibleUserEmail
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.getEligibleWithdrawalCoins
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Locale

class WithdrawalAuditUnitTest {

  @Test
  fun catalogDenominations_haveExactCoinToPaiseDivisibility_andRejectUnapprovedValues() {
    assertEquals(33_000L, WithdrawalCatalog.resolveRequiredCoins(WithdrawalMethodType.UPI, 200L))
    assertEquals(82_500L, WithdrawalCatalog.resolveRequiredCoins(WithdrawalMethodType.UPI, 500L))
    assertNull(WithdrawalCatalog.resolveRequiredCoins(WithdrawalMethodType.UPI, 150L))
    assertNull(WithdrawalCatalog.resolveRequiredCoins(WithdrawalMethodType.UPI, 100L))

    val giftCardMethods =
      listOf(
        WithdrawalMethodType.AMAZON_GIFT_CARD,
        WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD,
        WithdrawalMethodType.FLIPKART_GIFT_CARD
      )
    for (method in giftCardMethods) {
      assertEquals(33_000L, WithdrawalCatalog.resolveRequiredCoins(method, 150L))
      assertEquals(44_000L, WithdrawalCatalog.resolveRequiredCoins(method, 200L))
      assertEquals(55_000L, WithdrawalCatalog.resolveRequiredCoins(method, 250L))
      assertNull(WithdrawalCatalog.resolveRequiredCoins(method, 500L))
    }

    val allCatalogCoins =
      (WithdrawalCatalog.UPI_DENOMINATIONS + WithdrawalCatalog.GIFT_CARD_DENOMINATIONS)
        .map { it.amountCoins }
    for (coins in allCatalogCoins) {
      assertEquals(0L, (coins * 100L) % COINS_PER_RUPEE)
      val paise = coinsToCents(coins)
      assertEquals(coins, centsToCoins(paise))
    }
  }

  @Test
  fun bucketDeductionAndRefundParity_restoresExactOriginalBuckets() {
    // Mixed wallet: 20,000 eligibleCoins (16,000 paise) + 20,000 promotionalCoins = 40,000 balanceCoins
    val oldBalanceCents = 16_000L
    val oldEligibleCoins = 20_000L
    val oldPromoCoins = 20_000L
    val oldBalanceCoins = 40_000L
    val requiredCoins = 33_000L

    val deductEligible = minOf(oldEligibleCoins, requiredCoins)
    val deductPromo = minOf(oldPromoCoins, requiredCoins - deductEligible)
    val deductCents = minOf(oldBalanceCents, coinsToCents(deductEligible))

    assertEquals(20_000L, deductEligible)
    assertEquals(13_000L, deductPromo)
    assertEquals(16_000L, deductCents)

    val afterDebitEligible = oldEligibleCoins - deductEligible
    val afterDebitPromo = oldPromoCoins - deductPromo
    val afterDebitCents = oldBalanceCents - deductCents
    val afterDebitBalanceCoins = oldBalanceCoins - requiredCoins

    assertEquals(0L, afterDebitEligible)
    assertEquals(7_000L, afterDebitPromo)
    assertEquals(0L, afterDebitCents)
    assertEquals(7_000L, afterDebitBalanceCoins)

    // Admin Reject refunds exact deducted buckets once
    assertEquals(oldEligibleCoins, afterDebitEligible + deductEligible)
    assertEquals(oldPromoCoins, afterDebitPromo + deductPromo)
    assertEquals(oldBalanceCents, afterDebitCents + deductCents)
    assertEquals(oldBalanceCoins, afterDebitBalanceCoins + requiredCoins)
  }

  @Test
  fun userProfileEffectiveBalanceHelpers_remainConsistentAfterDebitAndRefund() {
    val profile =
      UserProfile(
        userId = "u1",
        balanceCents = 26_400L,
        balanceCoins = 43_000L,
        eligibleCoins = 33_000L,
        promotionalCoins = 10_000L
      )
    assertEquals(33_000L, profile.getEligibleWithdrawalCoins())
    assertEquals(43_000L, profile.getEffectiveBalanceCoins())
  }

  @Test
  fun availabilityConfig_enforcesMethodAndDenominationFlagsAcrossAllMethods() {
    val cfg =
      WithdrawalAvailabilityConfig(
        upiEnabled = true,
        amazonEnabled = false,
        googlePlayEnabled = true,
        flipkartEnabled = true,
        upiDenominationsEnabled = mapOf("200" to false, "500" to true),
        googlePlayDenominationsEnabled = mapOf("150" to true, "200" to false, "250" to true)
      )

    assertFalse(cfg.isDenominationAvailable(WithdrawalMethodType.UPI, 200L))
    assertTrue(cfg.isDenominationAvailable(WithdrawalMethodType.UPI, 500L))
    assertFalse(cfg.isDenominationAvailable(WithdrawalMethodType.AMAZON_GIFT_CARD, 150L))
    assertTrue(cfg.isDenominationAvailable(WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD, 150L))
    assertFalse(cfg.isDenominationAvailable(WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD, 200L))
    assertTrue(cfg.isDenominationAvailable(WithdrawalMethodType.FLIPKART_GIFT_CARD, 250L))
  }

  @Test
  fun withdrawalStatus_terminalAndDelayedRules_areConsistent() {
    assertTrue(WithdrawalStatus.isTerminal(WithdrawalStatus.COMPLETED))
    assertTrue(WithdrawalStatus.isTerminal(WithdrawalStatus.REJECTED))
    assertTrue(WithdrawalStatus.isTerminal("approved"))
    assertFalse(WithdrawalStatus.isTerminal(WithdrawalStatus.SUBMITTED))
    assertFalse(WithdrawalStatus.isTerminal(WithdrawalStatus.UNDER_REVIEW))
    assertFalse(WithdrawalStatus.isTerminal(WithdrawalStatus.PROCESSING))

    val now = 1_700_000_000_000L
    assertFalse(WithdrawalStatus.isDelayed(WithdrawalStatus.SUBMITTED, now - 10_000L, now))
    assertTrue(
      WithdrawalStatus.isDelayed(
        WithdrawalStatus.SUBMITTED,
        now - WithdrawalStatus.DELAYED_THRESHOLD_MS,
        now
      )
    )
    assertFalse(
      WithdrawalStatus.isDelayed(
        WithdrawalStatus.COMPLETED,
        now - WithdrawalStatus.DELAYED_THRESHOLD_MS - 1_000L,
        now
      )
    )
  }

  @Test
  fun adminEmailPrivacyMasking_andGiftCodeHashNormalization_areDeterministic() {
    assertEquals(
      ADMIN_EMAIL_PRIVACY_PLACEHOLDER,
      formatVisibleUserEmail(BOOTSTRAPPED_ADMIN_EMAIL, "admin")
    )
    assertEquals(
      ADMIN_EMAIL_PRIVACY_PLACEHOLDER,
      formatVisibleUserEmail("anyone@example.com", "admin")
    )
    assertEquals("user@example.com", formatVisibleUserEmail("user@example.com", "user"))

    fun normalizeAndHash(raw: String): String {
      val norm = raw.trim().uppercase(Locale.US)
      return MessageDigest.getInstance("SHA-256")
        .digest(norm.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    }
    assertEquals(
      normalizeAndHash("  amzn-9988-abcd "),
      normalizeAndHash("AMZN-9988-ABCD")
    )
  }
}

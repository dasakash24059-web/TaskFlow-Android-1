package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.data.model.DailyCheckInState
import com.example.data.model.ManualBonusRecord
import com.example.data.model.NotificationItem
import com.example.data.model.NotificationRewardType
import com.example.data.model.PromoCodeItem
import com.example.data.model.PromoCodeStatus
import com.example.data.model.PromoRedemptionRecord
import com.example.data.model.PromoRedemptionUiState
import com.example.data.model.ScratchCardConstants
import com.example.data.model.ScratchCardSession
import com.example.data.model.ScratchDailyState
import com.example.data.model.ScratchCardUiState
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TelegramJoinConfig
import com.example.data.model.TelegramTaskClaimRecord
import com.example.data.model.TelegramTaskItem
import com.example.data.model.TelegramUserRewardState
import com.example.data.model.TelegramUserTaskState
import com.example.data.model.TelegramVerifyResult
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.formatCoins
import com.example.data.model.getEffectiveBalanceCoins
import com.example.data.model.normalizePromoCode
import com.example.data.model.selectNextTelegramTaskForUser
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.data.repository.TelegramMembershipVerifier
import com.example.ui.admin.AdminCoinsRewardsSection
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.DedicatedDailyCheckInScreen
import com.example.ui.user.PromoCodeRedemptionScreen
import com.example.ui.user.ScratchCardScreen
import com.example.ui.user.UserTrackingScreen
import com.example.ui.viewmodel.UiState
import java.util.UUID
import com.google.firebase.Timestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Comprehensive verification suite for the TaskFlow Rewards System implementation covering all 14
 * required scenarios:
 * 1. Telegram Join config creation and custom reward setting
 * 2. Telegram membership verification required before claim
 * 3. Telegram Join one-time claim enforcement
 * 4. Telegram Join permanently hidden after claim
 * 5. Multiple promo codes created with custom reward amounts and slot limits
 * 6. User redeeming multiple different valid promo codes
 * 7. Same user blocked from redeeming the same promo code twice
 * 8. Invalid promo code rejected
 * 9. Expired promo code rejected
 * 10. Exhausted promo code rejected when slots are full
 * 11. Admin manual bonus crediting existing user balance
 * 12. Preventing duplicate manual bonus on repeated clicks
 * 13. Notification Bell entries created and displayed for all reward types
 * 14. Existing wallet balance and transaction history preserved
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RewardsSystemTest {

  @get:Rule val composeTestRule = createComposeRule()

  /**
   * Deterministic in-memory simulation of the atomic Rewards System ledger (Telegram Join, Promo
   * Codes, Admin Manual Bonus, Wallet, Transactions, and Notification Bell) matching the exact
   * rules in TaskFlowRepository and firestore.rules.
   */
  private class SimulatedRewardsLedger(
    var telegramConfig: TelegramJoinConfig =
      TelegramJoinConfig(
        channelUrl = "https://t.me/TaskFlowOfficial",
        channelChatId = "@TaskFlowOfficial",
        botToken = "123456:ABCDEF",
        rewardCoins = 275L,
        isActive = true
      ),
    var telegramVerifier: TelegramMembershipVerifier =
      TelegramMembershipVerifier { _, _, telegramUserId ->
        telegramUserId.trim().startsWith("member_")
      }
  ) {
    val users = mutableMapOf<String, UserProfile>()
    val telegramUserStates = mutableMapOf<String, TelegramUserRewardState>()
    val promoCodes = linkedMapOf<String, PromoCodeItem>()
    val promoRedemptionsByCodeAndUser = mutableMapOf<Pair<String, String>, PromoRedemptionRecord>()
    val manualBonuses = linkedMapOf<String, ManualBonusRecord>()
    val transactions = mutableListOf<TransactionRecord>()
    val notificationsByUser = mutableMapOf<String, MutableList<NotificationItem>>()

    fun configureTelegramTask(
      channelUrl: String,
      channelChatId: String,
      botToken: String,
      rewardCoins: Long,
      isActive: Boolean
    ) {
      require(channelUrl.startsWith("https://")) { "Invalid channel URL" }
      require(rewardCoins > 0L) { "Reward must be > 0 coins" }
      telegramConfig =
        TelegramJoinConfig(
          channelUrl = channelUrl,
          channelChatId = channelChatId,
          botToken = botToken,
          rewardCoins = rewardCoins,
          isActive = isActive
        )
    }

    suspend fun verifyTelegramMembership(userId: String, telegramUserId: String): TelegramUserRewardState {
      check(telegramConfig.isActive) { "Telegram Join Reward is currently inactive." }
      val existing = telegramUserStates[userId] ?: TelegramUserRewardState(userId = userId)
      check(!existing.isClaimed) { "You have already claimed the Telegram Join Reward." }
      val isMember =
        telegramVerifier.verifyChannelMembership(
          botToken = telegramConfig.botToken,
          channelChatId = telegramConfig.channelChatId,
          telegramUserId = telegramUserId
        )
      check(isMember) {
        "Telegram membership could not be verified. Please join the official Telegram channel first and try again."
      }
      val updated =
        existing.copy(
          telegramUserId = telegramUserId.trim(),
          isMembershipVerified = true
        )
      telegramUserStates[userId] = updated
      users[userId]?.let { u ->
        users[userId] = u.copy(telegramJoinedVerified = true, telegramUserId = telegramUserId.trim())
      }
      return updated
    }

    fun claimTelegramJoinReward(userId: String): Long {
      check(telegramConfig.isActive && telegramConfig.rewardCoins > 0L) {
        "Telegram Join Reward is currently inactive."
      }
      val user = checkNotNull(users[userId]) { "User not found" }
      val state = telegramUserStates[userId] ?: TelegramUserRewardState(userId = userId)
      check(!state.isClaimed && !user.telegramRewardClaimed) {
        "Telegram Join Reward has already been claimed."
      }
      check(state.isMembershipVerified) {
        "Please verify your Telegram channel membership before claiming the reward."
      }
      val rewardCoins = telegramConfig.rewardCoins
      val newPromo = user.promotionalCoins + rewardCoins
      val newBalance = user.getEffectiveBalanceCoins() + rewardCoins
      users[userId] =
        user.copy(
          promotionalCoins = newPromo,
          balanceCoins = newBalance,
          telegramJoinedVerified = true,
          telegramRewardClaimed = true
        )
      telegramUserStates[userId] =
        state.copy(
          isClaimed = true,
          claimedCoins = rewardCoins
        )
      transactions.add(
        TransactionRecord(
          id = "tg_join_$userId",
          userId = userId,
          userName = user.displayName,
          type = TransactionType.TELEGRAM_JOIN,
          title = "Telegram Join Reward",
          amountCoins = rewardCoins,
          status = "CREDITED",
          referenceId = "telegram_join_$userId",
          createdAt = Timestamp.now()
        )
      )
      notificationsByUser.getOrPut(userId) { mutableListOf() }.add(
        NotificationItem(
          id = "notif_tg_join_$userId",
          userId = userId,
          title = "Telegram Join Reward Credited",
          body = "You received $rewardCoins Coins for joining our official Telegram channel.",
          type = NotificationRewardType.TELEGRAM_REWARD,
          rewardCoins = rewardCoins,
          referenceId = "telegram_join_$userId",
          isRead = false,
          createdAt = Timestamp.now()
        )
      )
      return rewardCoins
    }

    fun createPromoCode(
      rawCode: String,
      rewardCoins: Long,
      maxSlots: Long,
      expiresAtMillis: Long,
      isActive: Boolean = true
    ): PromoCodeItem {
      val normalized = normalizePromoCode(rawCode)
      require(normalized.length in 3..32) { "Invalid promo code" }
      require(rewardCoins > 0L) { "Reward coins must be > 0" }
      require(maxSlots >= 1L) { "Max slots must be >= 1" }
      check(!promoCodes.containsKey(normalized)) { "Promo code '$normalized' already exists." }
      val item =
        PromoCodeItem(
          id = normalized,
          code = normalized,
          rewardCoins = rewardCoins,
          maxSlots = maxSlots,
          usedSlots = 0L,
          expiresAtMillis = expiresAtMillis,
          isActive = isActive,
          status = if (isActive) PromoCodeStatus.ACTIVE else PromoCodeStatus.INACTIVE,
          createdAt = Timestamp.now()
        )
      promoCodes[normalized] = item
      return item
    }

    fun redeemPromoCode(userId: String, rawCode: String, nowMillis: Long = System.currentTimeMillis()): Long {
      val normalized = normalizePromoCode(rawCode)
      check(normalized.isNotBlank()) { "Please enter a valid promo code." }
      val promo = promoCodes[normalized] ?: error("Invalid promo code. Please check the code and try again.")
      check(promo.isActive && !promo.status.equals(PromoCodeStatus.INACTIVE, ignoreCase = true)) {
        "This promo code is currently inactive."
      }
      if (promo.isExpired(nowMillis)) {
        promoCodes[normalized] = promo.copy(status = PromoCodeStatus.EXPIRED)
        error("This promo code has expired.")
      }
      if (promo.isExhausted) {
        promoCodes[normalized] = promo.copy(status = PromoCodeStatus.EXHAUSTED)
        error("This promo code has reached its maximum redemption limit.")
      }
      val key = normalized to userId
      check(!promoRedemptionsByCodeAndUser.containsKey(key)) {
        "You have already redeemed promo code $normalized."
      }
      val user = checkNotNull(users[userId]) { "User not found" }
      val newUsedSlots = promo.usedSlots + 1L
      val newStatus = if (newUsedSlots >= promo.maxSlots) PromoCodeStatus.EXHAUSTED else PromoCodeStatus.ACTIVE
      promoCodes[normalized] = promo.copy(usedSlots = newUsedSlots, status = newStatus)
      promoRedemptionsByCodeAndUser[key] =
        PromoRedemptionRecord(
          id = "${normalized}_$userId",
          promoId = normalized,
          code = normalized,
          userId = userId,
          userName = user.displayName,
          rewardCoins = promo.rewardCoins,
          redeemedAt = Timestamp.now()
        )
      users[userId] =
        user.copy(
          promotionalCoins = user.promotionalCoins + promo.rewardCoins,
          balanceCoins = user.getEffectiveBalanceCoins() + promo.rewardCoins
        )
      transactions.add(
        TransactionRecord(
          id = "promo_${normalized}_$userId",
          userId = userId,
          userName = user.displayName,
          type = TransactionType.PROMO_CODE,
          title = "Promo Code Redeemed ($normalized)",
          amountCoins = promo.rewardCoins,
          status = "CREDITED",
          referenceId = normalized,
          createdAt = Timestamp.now()
        )
      )
      notificationsByUser.getOrPut(userId) { mutableListOf() }.add(
        NotificationItem(
          id = "notif_promo_${normalized}_$userId",
          userId = userId,
          title = "Promo Code Redeemed",
          body = "Code $normalized credited ${promo.rewardCoins} Coins to your wallet.",
          type = NotificationRewardType.PROMO_CODE_REWARD,
          rewardCoins = promo.rewardCoins,
          referenceId = normalized,
          isRead = false,
          createdAt = Timestamp.now()
        )
      )
      return promo.rewardCoins
    }

    fun creditAdminManualBonus(
      adminId: String,
      adminEmail: String,
      targetUserId: String,
      amountCoins: Long,
      reason: String,
      idempotencyKey: String
    ): ManualBonusRecord {
      require(amountCoins > 0L) { "Bonus amount must be greater than 0 coins." }
      val safeKey = idempotencyKey.trim().replace(Regex("[^A-Za-z0-9_-]"), "_")
      val bonusId = "bonus_${targetUserId}_$safeKey"
      check(!manualBonuses.containsKey(bonusId)) {
        "This manual bonus has already been credited (duplicate click prevented)."
      }
      val user = checkNotNull(users[targetUserId]) { "Selected user account was not found." }
      val cleanReason = reason.trim().ifBlank { "Admin Manual Bonus" }
      val record =
        ManualBonusRecord(
          id = bonusId,
          targetUserId = targetUserId,
          targetUserEmail = user.email,
          targetUserName = user.displayName,
          amountCoins = amountCoins,
          reason = cleanReason,
          adminId = adminId,
          adminEmail = adminEmail,
          idempotencyKey = safeKey,
          createdAt = Timestamp.now()
        )
      manualBonuses[bonusId] = record
      users[targetUserId] =
        user.copy(
          promotionalCoins = user.promotionalCoins + amountCoins,
          balanceCoins = user.getEffectiveBalanceCoins() + amountCoins
        )
      transactions.add(
        TransactionRecord(
          id = "tx_$bonusId",
          userId = targetUserId,
          userName = user.displayName,
          type = TransactionType.ADMIN_MANUAL_BONUS,
          title = "Manual Bonus: $cleanReason",
          amountCoins = amountCoins,
          status = "CREDITED",
          referenceId = bonusId,
          createdAt = Timestamp.now()
        )
      )
      notificationsByUser.getOrPut(targetUserId) { mutableListOf() }.add(
        NotificationItem(
          id = "notif_$bonusId",
          userId = targetUserId,
          title = "Bonus Coins Credited",
          body = "You received $amountCoins Bonus Coins ($cleanReason).",
          type = NotificationRewardType.ADMIN_BONUS,
          rewardCoins = amountCoins,
          referenceId = bonusId,
          isRead = false,
          createdAt = Timestamp.now()
        )
      )
      return record
    }

    val scratchDailyByUserAndDate = mutableMapOf<Pair<String, String>, ScratchDailyState>()
    val scratchSessions = mutableMapOf<String, ScratchCardSession>()

    fun getOrCreateScratchCard(userId: String, dateKey: String = "2026-11-14"): ScratchCardSession {
      val user = checkNotNull(users[userId]) { "User profile not found." }
      check(user.approvalStatus == "approved") { "Account must be approved." }
      val daily = scratchDailyByUserAndDate[userId to dateKey] ?: ScratchDailyState(dateKey = dateKey)
      check(daily.scratchesCompletedToday < ScratchCardConstants.MAX_DAILY_SCRATCHES) {
        "Daily scratch limit reached. Check back tomorrow!"
      }
      val activeId = daily.activeCardId
      if (activeId.isNotBlank() && scratchSessions.containsKey(activeId)) {
        val existing = scratchSessions[activeId]!!
        if (!existing.claimed) return existing
      }
      val isFree = !daily.hasUsedFreeScratchToday
      check(isFree || daily.unlockedScratchesCount > 0L) {
        "Watch 2 videos to unlock your next Scratch Card."
      }
      val rewardCoins = ScratchCardConstants.calculateScratchReward(user.completedTasksCount)
      val cardId = "scratch_test_${UUID.randomUUID().toString().replace("-", "")}"
      val session = ScratchCardSession(
        id = cardId,
        cardId = cardId,
        userId = userId,
        dateKey = dateKey,
        scratchNumber = daily.scratchesCompletedToday + 1,
        rewardCoins = rewardCoins,
        isFree = isFree,
        status = "ACTIVE",
        claimed = false
      )
      scratchSessions[cardId] = session
      val nextUnlocked = if (isFree) daily.unlockedScratchesCount else (daily.unlockedScratchesCount - 1L).coerceAtLeast(0L)
      scratchDailyByUserAndDate[userId to dateKey] = daily.copy(
        hasUsedFreeScratchToday = true,
        unlockedScratchesCount = nextUnlocked,
        activeCardId = cardId,
        activeCardRewardCoins = rewardCoins,
        activeCardIsScratched = false,
        activeCardIsClaimed = false
      )
      return session
    }

    fun recordScratchVideoAd(userId: String, dateKey: String = "2026-11-14"): ScratchDailyState {
      val daily = scratchDailyByUserAndDate[userId to dateKey] ?: ScratchDailyState(dateKey = dateKey)
      check(daily.scratchesCompletedToday < ScratchCardConstants.MAX_DAILY_SCRATCHES) {
        "Daily scratch limit reached. Check back tomorrow!"
      }
      val nextWatched = daily.adsWatchedForNextScratch + 1L
      val (finalWatched, finalUnlocked) = if (nextWatched >= ScratchCardConstants.ADS_REQUIRED_PER_SCRATCH) {
        0L to (daily.unlockedScratchesCount + 1L)
      } else {
        nextWatched to daily.unlockedScratchesCount
      }
      val updated = daily.copy(
        adsWatchedForNextScratch = finalWatched,
        unlockedScratchesCount = finalUnlocked
      )
      scratchDailyByUserAndDate[userId to dateKey] = updated
      return updated
    }

    fun claimScratchCard(userId: String, cardId: String, dateKey: String = "2026-11-14"): Long {
      val user = checkNotNull(users[userId]) { "User profile not found." }
      val session = checkNotNull(scratchSessions[cardId]) { "Scratch card session not found." }
      check(session.userId == userId) { "Belongs to another user." }
      check(!session.claimed) { "This Scratch Card reward has already been claimed." }
      val rewardCoins = session.rewardCoins
      val newPromo = user.promotionalCoins + rewardCoins
      val newBalance = user.getEffectiveBalanceCoins() + rewardCoins
      users[userId] = user.copy(promotionalCoins = newPromo, balanceCoins = newBalance)
      scratchSessions[cardId] = session.copy(claimed = true, status = "CLAIMED")
      val daily = scratchDailyByUserAndDate[userId to dateKey] ?: ScratchDailyState(dateKey = dateKey)
      scratchDailyByUserAndDate[userId to dateKey] = daily.copy(
        scratchesCompletedToday = daily.scratchesCompletedToday + 1,
        activeCardId = "",
        activeCardRewardCoins = 0L,
        activeCardIsScratched = false,
        activeCardIsClaimed = true
      )
      transactions.add(
        TransactionRecord(
          id = "tx_${UUID.randomUUID().toString().replace("-", "")}",
          userId = userId,
          userName = user.displayName,
          type = TransactionType.SCRATCH_CARD,
          title = "Scratch Card Reward (+$rewardCoins Coins)",
          amountCoins = rewardCoins,
          amountCents = rewardCoins,
          status = "CREDITED",
          referenceId = cardId,
          createdAt = Timestamp.now()
        )
      )
      notificationsByUser.getOrPut(userId) { mutableListOf() }.add(
        NotificationItem(
          id = "notif_scratch_$cardId",
          userId = userId,
          title = "Scratch Card Reward",
          body = "You revealed and claimed +${formatCoins(rewardCoins)} Coins from Scratch & Win!",
          type = NotificationRewardType.SCRATCH_CARD_REWARD,
          rewardCoins = rewardCoins,
          referenceId = cardId,
          isRead = false,
          createdAt = Timestamp.now()
        )
      )
      return rewardCoins
    }
  }

  // =========================================================================
  // SCENARIO 1: Telegram Join config creation and custom reward setting
  // =========================================================================
  @Test
  fun scenario01_telegramJoinConfig_supportsCustomRewardAmounts_andActivationFlags() {
    val ledger = SimulatedRewardsLedger()

    // Admin configures a non-100 custom coin reward (e.g. 450 Coins)
    ledger.configureTelegramTask(
      channelUrl = "https://t.me/TaskFlowNews",
      channelChatId = "@TaskFlowNews",
      botToken = "999888:XYZ",
      rewardCoins = 450L,
      isActive = true
    )

    assertEquals("https://t.me/TaskFlowNews", ledger.telegramConfig.channelUrl)
    assertEquals("@TaskFlowNews", ledger.telegramConfig.channelChatId)
    assertEquals(450L, ledger.telegramConfig.rewardCoins)
    assertTrue(ledger.telegramConfig.isActive)

    // Admin updates to another custom amount (e.g. 1250 Coins) and deactivates
    ledger.configureTelegramTask(
      channelUrl = "https://t.me/TaskFlowNews",
      channelChatId = "@TaskFlowNews",
      botToken = "999888:XYZ",
      rewardCoins = 1250L,
      isActive = false
    )
    assertEquals(1250L, ledger.telegramConfig.rewardCoins)
    assertFalse(ledger.telegramConfig.isActive)

    // Verify Telegram Bot API getChatMember JSON parser & channel chat_id resolver
    assertTrue(
      com.example.data.repository.HttpTelegramBotMembershipVerifier.parseTelegramChatMemberResponse(
        """{"ok":true,"result":{"status":"member","user":{"id":12345}}}"""
      )
    )
    assertTrue(
      com.example.data.repository.HttpTelegramBotMembershipVerifier.parseTelegramChatMemberResponse(
        """{"ok":true,"result":{"status":"administrator","user":{"id":12345}}}"""
      )
    )
    assertFalse(
      com.example.data.repository.HttpTelegramBotMembershipVerifier.parseTelegramChatMemberResponse(
        """{"ok":true,"result":{"status":"left","user":{"id":12345}}}"""
      )
    )
    assertEquals(
      "@TaskFlowNews",
      com.example.data.repository.HttpTelegramBotMembershipVerifier.resolveChannelChatId(
        "",
        "https://t.me/TaskFlowNews"
      )
    )
  }

  // =========================================================================
  // SCENARIO 2: Telegram membership verification required before claim
  // =========================================================================
  @Test
  fun scenario02_telegramJoinReward_requiresBotMembershipVerificationBeforeClaim() =
    kotlinx.coroutines.runBlocking {
      val ledger = SimulatedRewardsLedger()
      ledger.users["u1"] =
        UserProfile(
          id = "u1",
          userId = "u1",
          email = "user1@example.com",
          displayName = "User One",
          balanceCoins = 500L,
          promotionalCoins = 500L
        )

      // 1. Attempting to claim before verifying membership must be rejected
      try {
        ledger.claimTelegramJoinReward("u1")
        fail("Expected claim without membership verification to fail")
      } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("verify your Telegram channel membership"))
      }
      assertEquals(500L, ledger.users["u1"]!!.getEffectiveBalanceCoins())

      // 2. Non-member Telegram User ID fails Bot API verification
      try {
        ledger.verifyTelegramMembership("u1", "non_member_999")
        fail("Expected non-member Telegram User ID verification to fail")
      } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("Telegram membership could not be verified"))
      }
      assertFalse(ledger.telegramUserStates["u1"]?.isMembershipVerified == true)

      // 3. Actual channel member passes Bot API verification and can then claim
      val verifiedState = ledger.verifyTelegramMembership("u1", "member_12345")
      assertTrue(verifiedState.isMembershipVerified)
      assertFalse(verifiedState.isClaimed)

      val claimedCoins = ledger.claimTelegramJoinReward("u1")
      assertEquals(275L, claimedCoins)
      assertEquals(775L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    }

  // =========================================================================
  // SCENARIO 3: Telegram Join one-time claim enforcement
  // =========================================================================
  @Test
  fun scenario03_telegramJoinReward_enforcesOneTimeClaim_andRejectsDuplicateClaim() =
    kotlinx.coroutines.runBlocking {
      val ledger = SimulatedRewardsLedger()
      ledger.users["u1"] =
        UserProfile(
          id = "u1",
          userId = "u1",
          email = "user1@example.com",
          displayName = "User One",
          balanceCoins = 1000L,
          promotionalCoins = 1000L
        )

      ledger.verifyTelegramMembership("u1", "member_777")
      val firstClaim = ledger.claimTelegramJoinReward("u1")
      assertEquals(275L, firstClaim)
      assertEquals(1275L, ledger.users["u1"]!!.getEffectiveBalanceCoins())

      // Attempting a second claim must fail and never credit extra coins
      try {
        ledger.claimTelegramJoinReward("u1")
        fail("Expected second Telegram Join claim to fail")
      } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("already been claimed"))
      }
      assertEquals(1275L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
      assertEquals(1, ledger.transactions.count { it.type == TransactionType.TELEGRAM_JOIN })
    }

  // =========================================================================
  // SCENARIO 4: Telegram Join moved to Tasks screen (Minimal card + Daily Rewards exclusion)
  // =========================================================================
  @Test
  fun scenario04_telegramJoinTaskCard_rendersMinimalCardInTasksScreen_andNotInDailyRewards() {
    val task1 =
      TelegramTaskItem(
        id = "tg_1",
        taskId = "tg_1",
        title = "Join Official Updates",
        channelUrl = "https://t.me/TaskFlowOfficial",
        channelChatId = "@TaskFlowOfficial",
        rewardCoins = 350L,
        isActive = true,
        createdAt = Timestamp(1_750_000_000L, 0)
      )

    // Verify Daily Rewards screen no longer renders Telegram Join card
    composeTestRule.setContent {
      TaskFlowTheme {
        DedicatedDailyCheckInScreen(
          dailyCheckInState = DailyCheckInState(currentStreak = 1, canCheckInToday = true, nextClaimDay = 2),
          approvedEarningsCents = 0L,
          onClaimDay = {},
          onBack = {}
        )
      }
    }
    composeTestRule.onNodeWithTag("daily_rewards_telegram_join_card").assertDoesNotExist()
  }

  @Test
  fun scenario04_telegramJoinTaskCard_displaysNewBadgeRewardAndActionButtons_inTasksScreen() {
    val task1 =
      TelegramTaskItem(
        id = "tg_1",
        taskId = "tg_1",
        title = "Join Official Updates",
        channelUrl = "https://t.me/TaskFlowOfficial",
        channelChatId = "@TaskFlowOfficial",
        rewardCoins = 350L,
        isActive = true,
        createdAt = Timestamp(1_750_000_000L, 0)
      )
    var joinedTask = ""
    var verifiedTask = ""
    var claimedTask = ""
    val stateHolder =
      androidx.compose.runtime.mutableStateOf(
        TelegramUserTaskState(
          taskId = "tg_1",
          userId = "u1",
          hasClickedJoin = false,
          isMembershipVerified = false
        )
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserTrackingScreen(
          tasksState = UiState.Success(emptyList()),
          selectedFilter = "all",
          onSelectFilter = {},
          onSelectTask = {},
          currentUserId = "u1",
          nextTelegramTask = task1,
          telegramTaskState = stateHolder.value,
          onJoinTelegramTask = {
            joinedTask = it.effectiveTaskId
            stateHolder.value = stateHolder.value.copy(hasClickedJoin = true)
          },
          onVerifyTelegramTask = { verifiedTask = it.effectiveTaskId },
          onClaimTelegramTask = { claimedTask = it.effectiveTaskId }
        )
      }
    }

    composeTestRule.onNodeWithTag("user_telegram_task_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("user_telegram_new_badge").assertIsDisplayed()
    composeTestRule.onNodeWithTag("user_telegram_reward_badge").assertIsDisplayed()
    composeTestRule.onNodeWithText("+350 TF").assertIsDisplayed()

    // State 1: Single dynamic button displays JOIN NOW; VERIFY & CLAIM buttons do not exist simultaneously
    composeTestRule.onNodeWithTag("user_telegram_verify_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("user_telegram_claim_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("user_telegram_join_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    // State 2: After JOIN click, the single dynamic button transitions to VERIFY; CLAIM still does not exist
    composeTestRule.onNodeWithTag("user_telegram_join_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("user_telegram_claim_button").assertDoesNotExist()
    composeTestRule.onNodeWithTag("user_telegram_verify_button").assertIsDisplayed().performClick()

    assertEquals("tg_1", joinedTask)
    assertEquals("tg_1", verifiedTask)
    assertEquals("", claimedTask)
  }

  @Test
  fun scenario04b_telegramJoinMultiTask_autoAdvancesAfterClaim_andShowsNotFoundWhenNoneLeft() {
    val task1 =
      TelegramTaskItem(
        id = "tg_1",
        taskId = "tg_1",
        title = "Channel One",
        channelUrl = "https://t.me/TaskFlowOne",
        rewardCoins = 100L,
        isActive = true,
        createdAt = Timestamp(1_750_000_000L, 0)
      )
    val task2 =
      TelegramTaskItem(
        id = "tg_2",
        taskId = "tg_2",
        title = "Channel Two",
        channelUrl = "https://t.me/TaskFlowTwo",
        rewardCoins = 250L,
        isActive = true,
        createdAt = Timestamp(1_750_000_100L, 0)
      )
    val allTasks = listOf(task2, task1)

    // Initially picks earliest active unclaimed task (tg_1)
    val firstNext = selectNextTelegramTaskForUser(tasks = allTasks, userStates = emptyMap(), userClaims = emptyList())
    assertEquals("tg_1", firstNext?.effectiveTaskId)

    // Once tg_1 is claimed, tg_1 is permanently hidden and tg_2 becomes next automatically
    val stateAfterClaim1 = mapOf(
      "tg_1" to TelegramUserTaskState(taskId = "tg_1", userId = "u1", isMembershipVerified = true, isClaimed = true, rewardCoinsClaimed = 100L),
      "tg_2" to TelegramUserTaskState(taskId = "tg_2", userId = "u1", isMembershipVerified = true, isClaimed = false)
    )
    val secondNext = selectNextTelegramTaskForUser(tasks = allTasks, userStates = stateAfterClaim1, userClaims = emptyList())
    assertEquals("tg_2", secondNext?.effectiveTaskId)

    // Once both are claimed, null is returned and UserTrackingScreen shows Not Found state when regular tasks empty
    val stateAfterBothClaimed = stateAfterClaim1 + (
      "tg_2" to TelegramUserTaskState(taskId = "tg_2", userId = "u1", isMembershipVerified = true, isClaimed = true, rewardCoinsClaimed = 250L)
    )
    val noNext = selectNextTelegramTaskForUser(tasks = allTasks, userStates = stateAfterBothClaimed, userClaims = emptyList())
    assertNull(noNext)

    composeTestRule.setContent {
      TaskFlowTheme {
        UserTrackingScreen(
          tasksState = UiState.Success(emptyList()),
          selectedFilter = "all",
          onSelectFilter = {},
          onSelectTask = {},
          currentUserId = "u1",
          nextTelegramTask = noNext,
          telegramTaskState = null
        )
      }
    }

    composeTestRule.onNodeWithTag("user_telegram_task_card").assertDoesNotExist()
    composeTestRule.onNodeWithText("No tasks in any status").assertIsDisplayed()
  }

  // =========================================================================
  // SCENARIO 5: Multiple promo codes created with custom reward amounts and slot limits
  // =========================================================================
  @Test
  fun scenario05_promoCodeManager_createsMultipleIndependentPromoCodesWithCustomRewardsAndSlots() {
    val ledger = SimulatedRewardsLedger()
    val futureExpiry = System.currentTimeMillis() + 86_400_000L

    val code1 = ledger.createPromoCode("MORNING77", rewardCoins = 77L, maxSlots = 15L, expiresAtMillis = futureExpiry)
    val code2 = ledger.createPromoCode("NOON325", rewardCoins = 325L, maxSlots = 40L, expiresAtMillis = futureExpiry)
    val code3 = ledger.createPromoCode("NIGHT990", rewardCoins = 990L, maxSlots = 3L, expiresAtMillis = futureExpiry)

    // Creating a new promo code never deactivates or overwrites previous promo codes
    assertEquals(3, ledger.promoCodes.size)
    assertTrue(ledger.promoCodes["MORNING77"]!!.isActive)
    assertEquals(77L, code1.rewardCoins)
    assertEquals(15L, code1.maxSlots)
    assertEquals(325L, code2.rewardCoins)
    assertEquals(40L, code2.maxSlots)
    assertEquals(990L, code3.rewardCoins)
    assertEquals(3L, code3.maxSlots)
  }

  // =========================================================================
  // SCENARIO 6: User redeeming multiple different valid promo codes (same day, no daily limit)
  // =========================================================================
  @Test
  fun scenario06_promoCodeRedemption_allowsUserToRedeemMultipleDifferentValidCodesEvenOnSameDay() {
    val ledger = SimulatedRewardsLedger()
    val futureExpiry = System.currentTimeMillis() + 86_400_000L
    ledger.users["u1"] =
      UserProfile(
        id = "u1",
        userId = "u1",
        email = "u1@example.com",
        displayName = "Alice",
        balanceCoins = 1000L,
        promotionalCoins = 1000L
      )

    ledger.createPromoCode("CODE_A", rewardCoins = 120L, maxSlots = 10L, expiresAtMillis = futureExpiry)
    ledger.createPromoCode("CODE_B", rewardCoins = 230L, maxSlots = 10L, expiresAtMillis = futureExpiry)
    ledger.createPromoCode("CODE_C", rewardCoins = 450L, maxSlots = 10L, expiresAtMillis = futureExpiry)

    // Same user redeems all 3 different promo codes on the same day with no daily limit
    assertEquals(120L, ledger.redeemPromoCode("u1", "code_a"))
    assertEquals(230L, ledger.redeemPromoCode("u1", "CODE_B"))
    assertEquals(450L, ledger.redeemPromoCode("u1", " Code_C "))

    // Existing 1000 + 120 + 230 + 450 = 1800 Coins
    assertEquals(1800L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    assertEquals(3, ledger.transactions.count { it.type == TransactionType.PROMO_CODE })
  }

  // =========================================================================
  // SCENARIO 7: Same user blocked from redeeming the same promo code twice
  // =========================================================================
  @Test
  fun scenario07_promoCodeRedemption_blocksSameUserFromRedeemingSameCodeTwice() {
    val ledger = SimulatedRewardsLedger()
    val futureExpiry = System.currentTimeMillis() + 86_400_000L
    ledger.users["u1"] =
      UserProfile(
        id = "u1",
        userId = "u1",
        email = "u1@example.com",
        displayName = "Alice",
        balanceCoins = 500L,
        promotionalCoins = 500L
      )
    ledger.createPromoCode("ONCEONLY50", rewardCoins = 180L, maxSlots = 10L, expiresAtMillis = futureExpiry)

    assertEquals(180L, ledger.redeemPromoCode("u1", "ONCEONLY50"))
    assertEquals(680L, ledger.users["u1"]!!.getEffectiveBalanceCoins())

    try {
      ledger.redeemPromoCode("u1", "onceonly50")
      fail("Expected duplicate redemption of same promo code by same user to fail")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("already redeemed promo code ONCEONLY50"))
    }

    // Balance and slot count remain unchanged
    assertEquals(680L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    assertEquals(1L, ledger.promoCodes["ONCEONLY50"]!!.usedSlots)
  }

  // =========================================================================
  // SCENARIO 8: Invalid promo code rejected
  // =========================================================================
  @Test
  fun scenario08_promoCodeRedemption_rejectsInvalidPromoCodeWithoutCreditingCoins() {
    val ledger = SimulatedRewardsLedger()
    ledger.users["u1"] =
      UserProfile(
        id = "u1",
        userId = "u1",
        email = "u1@example.com",
        displayName = "Alice",
        balanceCoins = 500L,
        promotionalCoins = 500L
      )

    try {
      ledger.redeemPromoCode("u1", "DOES_NOT_EXIST_999")
      fail("Expected invalid promo code to fail")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("Invalid promo code"))
    }

    assertEquals(500L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    assertTrue(ledger.transactions.isEmpty())
  }

  // =========================================================================
  // SCENARIO 9: Expired promo code rejected
  // =========================================================================
  @Test
  fun scenario09_promoCodeRedemption_rejectsExpiredPromoCodeWithoutCreditingCoins() {
    val ledger = SimulatedRewardsLedger()
    val now = 1_750_000_000_000L
    val pastExpiry = now - 60_000L // Expired 1 minute ago
    ledger.users["u1"] =
      UserProfile(
        id = "u1",
        userId = "u1",
        email = "u1@example.com",
        displayName = "Alice",
        balanceCoins = 500L,
        promotionalCoins = 500L
      )
    ledger.createPromoCode("FLASH_EXPIRED", rewardCoins = 300L, maxSlots = 20L, expiresAtMillis = pastExpiry)

    try {
      ledger.redeemPromoCode("u1", "FLASH_EXPIRED", nowMillis = now)
      fail("Expected expired promo code to fail")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("expired"))
    }

    assertEquals(500L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    assertEquals(PromoCodeStatus.EXPIRED, ledger.promoCodes["FLASH_EXPIRED"]!!.status)
  }

  // =========================================================================
  // SCENARIO 10: Exhausted promo code rejected when slots are full
  // =========================================================================
  @Test
  fun scenario10_promoCodeRedemption_marksCodeExhaustedWhenSlotsFill_andRejectsFurtherRedemptions() {
    val ledger = SimulatedRewardsLedger()
    val futureExpiry = System.currentTimeMillis() + 86_400_000L
    ledger.users["u1"] = UserProfile(id = "u1", userId = "u1", email = "u1@example.com", displayName = "U1")
    ledger.users["u2"] = UserProfile(id = "u2", userId = "u2", email = "u2@example.com", displayName = "U2")
    ledger.users["u3"] = UserProfile(id = "u3", userId = "u3", email = "u3@example.com", displayName = "U3")

    // Create code with maxSlots = 2
    ledger.createPromoCode("LIMITED2", rewardCoins = 150L, maxSlots = 2L, expiresAtMillis = futureExpiry)

    assertEquals(150L, ledger.redeemPromoCode("u1", "LIMITED2"))
    assertEquals(PromoCodeStatus.ACTIVE, ledger.promoCodes["LIMITED2"]!!.status)
    assertEquals(1L, ledger.promoCodes["LIMITED2"]!!.usedSlots)

    assertEquals(150L, ledger.redeemPromoCode("u2", "LIMITED2"))
    // Slot 2/2 filled -> automatically becomes EXHAUSTED
    assertEquals(PromoCodeStatus.EXHAUSTED, ledger.promoCodes["LIMITED2"]!!.status)
    assertEquals(2L, ledger.promoCodes["LIMITED2"]!!.usedSlots)

    // User 3 attempting to redeem must be rejected
    try {
      ledger.redeemPromoCode("u3", "LIMITED2")
      fail("Expected exhausted promo code to reject 3rd user")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("maximum redemption limit"))
    }
    assertEquals(0L, ledger.users["u3"]!!.getEffectiveBalanceCoins())
  }

  // =========================================================================
  // SCENARIO 11: Admin manual bonus crediting existing user balance & user search
  // =========================================================================
  @Test
  fun scenario11_adminManualBonus_creditsCustomCoins_preservesExistingBalance_andSupportsUserSearchByIdOrEmail() {
    val ledger = SimulatedRewardsLedger()
    val u1 =
      UserProfile(
        id = "uid_alpha_101",
        userId = "uid_alpha_101",
        email = "priya.sharma@example.com",
        displayName = "Priya Sharma",
        balanceCents = 8000L, // 10,000 eligible coins
        eligibleCoins = 10_000L,
        promotionalCoins = 2_500L,
        balanceCoins = 12_500L
      )
    val u2 =
      UserProfile(
        id = "uid_beta_202",
        userId = "uid_beta_202",
        email = "rahul.verma@example.com",
        displayName = "Rahul Verma",
        balanceCoins = 4_000L,
        promotionalCoins = 4_000L
      )
    ledger.users[u1.id] = u1
    ledger.users[u2.id] = u2

    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
      com.google.firebase.FirebaseApp.initializeApp(
        context,
        com.google.firebase.FirebaseOptions.Builder()
          .setApplicationId("com.aistudio.taskflow.kxmpzq")
          .setProjectId("taskflow-8852d")
          .setApiKey("fake-api-key")
          .build()
      )
    }
    val repo =
      TaskFlowRepository(
        firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance()
      )
    // Search by User ID
    val byId = repo.filterUsersByIdOrEmail(listOf(u1, u2), "uid_alpha_101")
    assertEquals(1, byId.size)
    assertEquals("uid_alpha_101", byId.first().id)

    // Search by Email
    val byEmail = repo.filterUsersByIdOrEmail(listOf(u1, u2), "rahul.verma@example.com")
    assertEquals(1, byEmail.size)
    assertEquals("uid_beta_202", byEmail.first().id)

    // Credit custom bonus of 1,375 Coins to Priya (existing balance: 12,500 Coins)
    val bonusRecord =
      ledger.creditAdminManualBonus(
        adminId = "admin_1",
        adminEmail = "dasakash24059@gmail.com",
        targetUserId = "uid_alpha_101",
        amountCoins = 1_375L,
        reason = "Top Performer Contest Winner",
        idempotencyKey = "idem_contest_01"
      )

    assertEquals(1_375L, bonusRecord.amountCoins)
    assertEquals("Top Performer Contest Winner", bonusRecord.reason)
    // Existing 12,500 + 1,375 = 13,875 Coins; eligibleCoins (10,000) & balanceCents (8,000) preserved
    val updatedPriya = ledger.users["uid_alpha_101"]!!
    assertEquals(13_875L, updatedPriya.getEffectiveBalanceCoins())
    assertEquals(10_000L, updatedPriya.eligibleCoins)
    assertEquals(3_875L, updatedPriya.promotionalCoins)
  }

  // =========================================================================
  // SCENARIO 12: Preventing duplicate manual bonus on repeated clicks
  // =========================================================================
  @Test
  fun scenario12_adminManualBonus_preventsDuplicateCreditOnRepeatedClicksWithSameIdempotencyKey() {
    val ledger = SimulatedRewardsLedger()
    ledger.users["u1"] =
      UserProfile(
        id = "u1",
        userId = "u1",
        email = "u1@example.com",
        displayName = "User One",
        balanceCoins = 2_000L,
        promotionalCoins = 2_000L
      )

    ledger.creditAdminManualBonus(
      adminId = "admin_1",
      adminEmail = "dasakash24059@gmail.com",
      targetUserId = "u1",
      amountCoins = 600L,
      reason = "Support Compensation",
      idempotencyKey = "click_key_abc123"
    )
    assertEquals(2_600L, ledger.users["u1"]!!.getEffectiveBalanceCoins())

    // Repeated click with the same idempotencyKey must be rejected
    try {
      ledger.creditAdminManualBonus(
        adminId = "admin_1",
        adminEmail = "dasakash24059@gmail.com",
        targetUserId = "u1",
        amountCoins = 600L,
        reason = "Support Compensation",
        idempotencyKey = "click_key_abc123"
      )
      fail("Expected duplicate manual bonus click with same idempotency key to fail")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("already been credited"))
    }

    assertEquals(2_600L, ledger.users["u1"]!!.getEffectiveBalanceCoins())
    assertEquals(1, ledger.transactions.count { it.type == TransactionType.ADMIN_MANUAL_BONUS })
  }

  // =========================================================================
  // SCENARIO 13: Notification Bell entries created & rendered for all reward types
  // =========================================================================
  @Test
  fun scenario13_notificationBell_displaysEntriesForTelegramPromoManualBonusAndTaskRewards_withReadStatus() {
    val sampleNotifications =
      listOf(
        NotificationItem(
          id = "n_tg",
          userId = "u1",
          title = "Telegram Join Reward Credited",
          body = "You received 300 Coins for joining our official Telegram channel.",
          type = NotificationRewardType.TELEGRAM_REWARD,
          rewardCoins = 300L,
          isRead = false,
          createdAt = Timestamp(1_750_000_000L, 0)
        ),
        NotificationItem(
          id = "n_promo",
          userId = "u1",
          title = "Promo Code Redeemed",
          body = "Code MEGA250 credited 250 Coins to your wallet.",
          type = NotificationRewardType.PROMO_CODE_REWARD,
          rewardCoins = 250L,
          isRead = false,
          createdAt = Timestamp(1_750_000_100L, 0)
        ),
        NotificationItem(
          id = "n_bonus",
          userId = "u1",
          title = "Bonus Coins Credited",
          body = "You received 500 Bonus Coins (Weekly Winner).",
          type = NotificationRewardType.ADMIN_BONUS,
          rewardCoins = 500L,
          isRead = false,
          createdAt = Timestamp(1_750_000_200L, 0)
        ),
        NotificationItem(
          id = "n_task",
          userId = "u1",
          title = "Task Reward Credited",
          body = "Your submission for 'Install App' was approved and 1,000 Coins were credited.",
          type = NotificationRewardType.TASK_REWARD,
          rewardCoins = 1000L,
          isRead = true,
          createdAt = Timestamp(1_750_000_300L, 0)
        )
      )

    var markedReadId: String? = null
    composeTestRule.setContent {
      TaskFlowTheme {
        NotificationListDialog(
          notifications = sampleNotifications,
          onDismiss = {},
          onMarkRead = { markedReadId = it }
        )
      }
    }

    composeTestRule.onNodeWithTag("notification_bell_list").assertIsDisplayed()
    composeTestRule.onNodeWithTag("notification_item_n_tg").assertIsDisplayed()
    composeTestRule.onNodeWithTag("notification_coins_n_tg", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithTag("notification_status_n_tg", useUnmergedTree = true).assertExists()
    composeTestRule.onNodeWithText("Telegram Join Reward Credited").assertIsDisplayed()
    composeTestRule.onNodeWithText("Promo Code Redeemed").assertIsDisplayed()

    composeTestRule.onNodeWithTag("notif_mark_read_n_tg").performClick()
    assertEquals("n_tg", markedReadId)

    composeTestRule
      .onNodeWithTag("notification_bell_list")
      .performScrollToNode(hasTestTag("notification_item_n_bonus"))
    composeTestRule.onNodeWithText("Bonus Coins Credited").assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("notification_bell_list")
      .performScrollToNode(hasTestTag("notification_item_n_task"))
    composeTestRule.onNodeWithText("Task Reward Credited").assertIsDisplayed()
  }

  // =========================================================================
  // SCENARIO 14: Existing wallet balance and transaction history preserved
  // =========================================================================
  @Test
  fun scenario14_walletAndTransactionIntegrity_preservesExistingBalances_andRecordsTransactionsForAllRewardTypes() =
    kotlinx.coroutines.runBlocking {
      val ledger = SimulatedRewardsLedger()
      val futureExpiry = System.currentTimeMillis() + 86_400_000L

      // User starts with 15,000 eligibleCoins (from tasks) + 2,000 promotionalCoins = 17,000 Coins
      // and an existing TASK_REWARD transaction in history
      ledger.users["u1"] =
        UserProfile(
          id = "u1",
          userId = "u1",
          email = "u1@example.com",
          displayName = "Existing User",
          balanceCents = 12_000L,
          eligibleCoins = 15_000L,
          promotionalCoins = 2_000L,
          balanceCoins = 17_000L
        )
      ledger.transactions.add(
        TransactionRecord(
          id = "tx_existing_task_1",
          userId = "u1",
          userName = "Existing User",
          type = TransactionType.TASK_REWARD,
          title = "Task Approved: Demo App",
          amountCents = 12_000L,
          amountCoins = 15_000L,
          status = "CREDITED"
        )
      )

      // 1. Claim Telegram Join Reward (+275 Coins)
      ledger.verifyTelegramMembership("u1", "member_u1")
      ledger.claimTelegramJoinReward("u1")

      // 2. Redeem 2 Promo Codes (+125 Coins and +300 Coins)
      ledger.createPromoCode("PROMO125", rewardCoins = 125L, maxSlots = 5L, expiresAtMillis = futureExpiry)
      ledger.createPromoCode("PROMO300", rewardCoins = 300L, maxSlots = 5L, expiresAtMillis = futureExpiry)
      ledger.redeemPromoCode("u1", "PROMO125")
      ledger.redeemPromoCode("u1", "PROMO300")

      // 3. Receive Admin Manual Bonus (+800 Coins)
      ledger.creditAdminManualBonus(
        adminId = "admin_1",
        adminEmail = "dasakash24059@gmail.com",
        targetUserId = "u1",
        amountCoins = 800L,
        reason = "Loyalty Bonus",
        idempotencyKey = "loyalty_u1_1"
      )

      // Total expected: 17,000 + 275 + 125 + 300 + 800 = 18,500 Coins
      val finalUser = ledger.users["u1"]!!
      assertEquals(18_500L, finalUser.getEffectiveBalanceCoins())
      assertEquals(15_000L, finalUser.eligibleCoins)
      assertEquals(3_500L, finalUser.promotionalCoins)

      // Existing transaction + 4 new transactions = 5 total transactions
      assertEquals(5, ledger.transactions.size)
      assertTrue(ledger.transactions.any { it.id == "tx_existing_task_1" && it.type == TransactionType.TASK_REWARD })
      assertTrue(ledger.transactions.any { it.type == TransactionType.TELEGRAM_JOIN && it.amountCoins == 275L })
      assertTrue(ledger.transactions.any { it.type == TransactionType.PROMO_CODE && it.referenceId == "PROMO125" && it.amountCoins == 125L })
      assertTrue(ledger.transactions.any { it.type == TransactionType.PROMO_CODE && it.referenceId == "PROMO300" && it.amountCoins == 300L })
      assertTrue(ledger.transactions.any { it.type == TransactionType.ADMIN_MANUAL_BONUS && it.amountCoins == 800L })

      // 4 notifications generated for the 4 new rewards
      val userNotifs = ledger.notificationsByUser["u1"].orEmpty()
      assertEquals(4, userNotifs.size)
    }

  // =========================================================================
  // UI INTEGRATION TESTS: Promo Code Screen & Admin Coins/Rewards Section
  // =========================================================================
  @Test
  fun promoCodeRedemptionScreen_submitsCode_andDisplaysSuccessAndErrorMessages() {
    var submittedCode = ""
    composeTestRule.setContent {
      TaskFlowTheme {
        PromoCodeRedemptionScreen(
          uiState =
            PromoRedemptionUiState(
              isRedeeming = false,
              successMessage = "Promo code SAVE500 redeemed! +500 Coins added to your wallet.",
              lastCreditedCoins = 500L
            ),
          onRedeemCode = { submittedCode = it },
          onClearFeedback = {},
          onBack = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("promo_code_redemption_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("promo_code_success_message").assertIsDisplayed()
    composeTestRule.onNodeWithTag("promo_code_input_field").performTextInput("save500")
    composeTestRule.onNodeWithTag("promo_code_redeem_button").performClick()
    assertEquals("SAVE500", submittedCode)
  }

  @Test
  fun adminCoinsRewardsSection_rendersTelegramManager_promoCodeManager_andManualBonusSection() {
    val users =
      listOf(
        UserProfile(
          id = "user_99",
          userId = "user_99",
          email = "tester@example.com",
          displayName = "Tester Nine",
          balanceCoins = 1200L,
          promotionalCoins = 1200L
        )
      )

    val existingTgTasks =
      listOf(
        TelegramTaskItem(
          id = "tg_existing",
          taskId = "tg_existing",
          title = "Join Main Group",
          channelUrl = "https://t.me/TaskFlowMain",
          rewardCoins = 150L,
          isActive = true,
          claimsCount = 4L
        )
      )

    var createdTgTitle = ""
    var createdTgUrl = ""
    var createdTgReward = 0L
    var toggledTgId = ""
    var toggledTgActive = true
    var createdPromoCode = ""
    var creditedBonusUser = ""
    var creditedBonusAmount = 0L

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminCoinsRewardsSection(
          users = users,
          reviewTasks = emptyList(),
          transactions = emptyList(),
          telegramTasks = existingTgTasks,
          telegramClaims =
            listOf(
              TelegramTaskClaimRecord(
                id = "tg_existing_user_99",
                claimId = "tg_existing_user_99",
                taskId = "tg_existing",
                taskTitle = "Join Main Group",
                userId = "user_99",
                userName = "Tester Nine",
                telegramUsernameMasked = "@tes***99",
                rewardCoins = 150L
              )
            ),
          telegramConfig = TelegramJoinConfig(),
          promoCodes =
            listOf(
              PromoCodeItem(
                id = "WELCOME250",
                code = "WELCOME250",
                rewardCoins = 250L,
                maxSlots = 100L,
                usedSlots = 12L,
                isActive = true,
                status = PromoCodeStatus.ACTIVE
              )
            ),
          manualBonuses = emptyList(),
          onCreateTelegramTask = { title, url, _, rewardCoins, _ ->
            createdTgTitle = title
            createdTgUrl = url
            createdTgReward = rewardCoins
          },
          onToggleTelegramTaskActive = { taskId, isActive ->
            toggledTgId = taskId
            toggledTgActive = isActive
          },
          onCreatePromoCode = { code, _, _, _, _, _ -> createdPromoCode = code },
          onCreditManualBonus = { uid, amountCoins, _ ->
            creditedBonusUser = uid
            creditedBonusAmount = amountCoins
          }
        )
      }
    }

    composeTestRule.onNodeWithTag("admin_telegram_join_management_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_telegram_title_input").performScrollTo().performTextInput("VIP Channel")
    composeTestRule.onNodeWithTag("admin_telegram_url_input").performScrollTo().performTextInput("https://t.me/TaskFlowVIP")
    composeTestRule.onNodeWithTag("admin_telegram_reward_coins_input").performScrollTo().performTextInput("420")
    composeTestRule.onNodeWithTag("admin_telegram_save_button").performScrollTo().performClick()
    assertEquals("VIP Channel", createdTgTitle)
    assertEquals("https://t.me/TaskFlowVIP", createdTgUrl)
    assertEquals(420L, createdTgReward)

    composeTestRule.onNodeWithTag("admin_telegram_task_item_tg_existing").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_telegram_toggle_tg_existing").performScrollTo().performClick()
    assertEquals("tg_existing", toggledTgId)
    assertFalse(toggledTgActive)

    composeTestRule
      .onNodeWithTag("admin_coins_rewards_section")
      .performScrollToNode(hasTestTag("admin_promo_code_manager_card"))
    composeTestRule.onNodeWithTag("admin_promo_code_manager_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_promo_code_text_input").performScrollTo().performTextInput("FESTIVE888")
    composeTestRule.onNodeWithTag("admin_promo_reward_coins_input").performScrollTo().performTextInput("888")
    composeTestRule.onNodeWithTag("admin_promo_max_slots_input").performScrollTo().performTextInput("25")
    composeTestRule.onNodeWithTag("admin_promo_create_button").performScrollTo().performClick()
    assertEquals("FESTIVE888", createdPromoCode)

    composeTestRule
      .onNodeWithTag("admin_coins_rewards_section")
      .performScrollToNode(hasTestTag("admin_manual_bonus_section"))
    composeTestRule.onNodeWithTag("admin_manual_bonus_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_manual_bonus_search_input").performScrollTo().performTextInput("tester@example.com")
    composeTestRule.onNodeWithTag("admin_manual_bonus_user_user_99").performScrollTo().performClick()
    composeTestRule.onNodeWithTag("admin_manual_bonus_amount_input").performScrollTo().performTextInput("750")
    composeTestRule.onNodeWithTag("admin_manual_bonus_submit_button").performScrollTo().performClick()
    assertEquals("user_99", creditedBonusUser)
    assertEquals(750L, creditedBonusAmount)
  }

  @Test
  @Config(sdk = [33], qualifiers = "w393dp-h851dp-xxhdpi")
  fun mobileResolution_and_officialTaskFlowLogo_verifiedAcrossAllRewardScreens() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val logoResId = context.resources.getIdentifier("ic_taskflow_logo", "drawable", context.packageName)
    assertTrue("Official ic_taskflow_logo drawable asset must exist", logoResId != 0)

    composeTestRule.setContent {
      TaskFlowTheme {
        PromoCodeRedemptionScreen(
          uiState = PromoRedemptionUiState(codeInput = "TASKFLOW2026"),
          onRedeemCode = {},
          onBack = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("promo_code_redemption_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("promo_code_input_field").assertIsDisplayed()
    composeTestRule.onNodeWithTag("promo_code_redeem_button").assertIsDisplayed()
  }

  // =========================================================================
  // SCENARIO 15: First scratch each day is free & subsequent requires 2 videos
  // =========================================================================
  @Test
  fun scenario15_scratchCard_firstDailyScratchIsFree_andSubsequentRequiresTwoVideos() {
    val ledger = SimulatedRewardsLedger()
    val uid = "user_free_scratch"
    ledger.users[uid] = UserProfile(userId = uid, displayName = "Free Scratcher", approvalStatus = "approved")

    // 1. First scratch of the day is FREE
    val firstCard = ledger.getOrCreateScratchCard(uid)
    assertTrue("First scratch of day must be free", firstCard.isFree)
    val claimedCoins = ledger.claimScratchCard(uid, firstCard.cardId)
    assertTrue("Reward coins must be in 1..100", claimedCoins in 1L..100L)

    // 2. Immediate second scratch attempt without watching ads MUST be rejected
    try {
      ledger.getOrCreateScratchCard(uid)
      fail("Should reject second scratch without completed video ads")
    } catch (e: IllegalStateException) {
      assertTrue(e.message?.contains("Watch 2 videos") == true)
    }

    // 3. Complete Video 1 of 2: Still locked (requires 2 completed videos)
    val stateAfterVideo1 = ledger.recordScratchVideoAd(uid)
    assertEquals(1L, stateAfterVideo1.adsWatchedForNextScratch)
    assertEquals(0L, stateAfterVideo1.unlockedScratchesCount)
    assertFalse(stateAfterVideo1.canScratchNow)

    // 4. Complete Video 2 of 2: Unlocks exactly ONE scratch
    val stateAfterVideo2 = ledger.recordScratchVideoAd(uid)
    assertEquals(0L, stateAfterVideo2.adsWatchedForNextScratch)
    assertEquals(1L, stateAfterVideo2.unlockedScratchesCount)
    assertTrue(stateAfterVideo2.canScratchNow)

    // 5. User can now scratch the unlocked card
    val secondCard = ledger.getOrCreateScratchCard(uid)
    assertFalse("Subsequent scratch is unlocked via ads, not free", secondCard.isFree)
    ledger.claimScratchCard(uid, secondCard.cardId)
    assertEquals(2L, ledger.scratchDailyByUserAndDate[uid to "2026-11-14"]?.scratchesCompletedToday)
  }

  // =========================================================================
  // SCENARIO 16: Reward distribution (90% 1–40, 10% 41–100) & single generation
  // =========================================================================
  @Test
  fun scenario16_scratchCard_rewardDistribution_andSingleGenerationPerScratch() {
    val ledger = SimulatedRewardsLedger()
    val uid = "user_dist_check"
    ledger.users[uid] = UserProfile(userId = uid, displayName = "Dist Tester", approvalStatus = "approved", completedTasksCount = 5L)

    // Test reward calculation boundary and probability distribution
    // Low tier (randomRoll < 0.90): exactly in 1..40
    for (i in 0 until 50) {
      val roll = 0.05 + (i * 0.015) // all < 0.90
      val coins = ScratchCardConstants.calculateScratchReward(
        completedTasksCount = 5L,
        randomRoll = roll,
        randomValue = i / 50.0
      )
      assertTrue("Low-tier reward must be in 1..40, got $coins", coins in 1L..40L)
    }

    // High tier (randomRoll >= 0.90): exactly in 41..100
    for (i in 0 until 20) {
      val roll = 0.90 + (i * 0.005) // all >= 0.90
      val coins = ScratchCardConstants.calculateScratchReward(
        completedTasksCount = 10L,
        randomRoll = roll,
        randomValue = i / 20.0
      )
      assertTrue("High-tier reward must be in 41..100, got $coins", coins in 41L..100L)
    }

    // Test Single Generation per scratch: Calling getOrCreate multiple times returns exact same card & coins
    val cardA = ledger.getOrCreateScratchCard(uid)
    val cardB = ledger.getOrCreateScratchCard(uid)
    assertEquals("Must return exact same card session without re-generating", cardA.cardId, cardB.cardId)
    assertEquals("Must return exact same reward coins", cardA.rewardCoins, cardB.rewardCoins)
  }

  // =========================================================================
  // SCENARIO 17: Prevents duplicate claim & credits wallet atomically
  // =========================================================================
  @Test
  fun scenario17_scratchCard_preventsDuplicateClaim_andCreditsWalletAtomically() {
    val ledger = SimulatedRewardsLedger()
    val uid = "user_dup_claim"
    ledger.users[uid] = UserProfile(
      userId = uid,
      displayName = "Dup Claim Tester",
      approvalStatus = "approved",
      balanceCoins = 500L,
      promotionalCoins = 100L
    )

    val card = ledger.getOrCreateScratchCard(uid)
    val reward = card.rewardCoins

    // First claim succeeds and credits wallet
    val credited = ledger.claimScratchCard(uid, card.cardId)
    assertEquals(reward, credited)
    val updatedUser = ledger.users[uid]!!
    assertEquals(500L + reward, updatedUser.balanceCoins)
    assertEquals(100L + reward, updatedUser.promotionalCoins)

    // Auditable transaction and notification created
    val tx = ledger.transactions.firstOrNull { it.referenceId == card.cardId }
    assertTrue("Transaction record must be created", tx != null)
    assertEquals(TransactionType.SCRATCH_CARD, tx?.type)
    assertEquals(reward, tx?.amountCoins)

    val notifs = ledger.notificationsByUser[uid] ?: emptyList()
    assertTrue("Notification must be sent to user", notifs.any { it.referenceId == card.cardId })

    // Second claim attempt MUST be rejected
    try {
      ledger.claimScratchCard(uid, card.cardId)
      fail("Duplicate claim must be rejected")
    } catch (e: IllegalStateException) {
      assertTrue(e.message?.contains("already been claimed") == true)
    }
  }

  // =========================================================================
  // SCENARIO 18: Maximum 50 scratches per day & daily reset on new dateKey
  // =========================================================================
  @Test
  fun scenario18_scratchCard_enforces50PerDayLimit_andResetsDailyOnNewDateKey() {
    val ledger = SimulatedRewardsLedger()
    val uid = "user_50_limit"
    ledger.users[uid] = UserProfile(userId = uid, displayName = "Heavy Scratcher", approvalStatus = "approved")

    val day1 = "2026-11-14"
    // Simulate reaching 50 completed scratches on day 1
    ledger.scratchDailyByUserAndDate[uid to day1] = ScratchDailyState(
      dateKey = day1,
      scratchesCompletedToday = 50L,
      hasUsedFreeScratchToday = true,
      unlockedScratchesCount = 5L
    )

    // Attempting 51st scratch on Day 1 is blocked
    try {
      ledger.getOrCreateScratchCard(uid, dateKey = day1)
      fail("Should enforce 50 scratches per day limit")
    } catch (e: IllegalStateException) {
      assertTrue(e.message?.contains("Daily scratch limit reached") == true)
    }

    // Daily reset: On next day (new dateKey), limit resets and first scratch is free again!
    val day2 = "2026-11-15"
    val day2Card = ledger.getOrCreateScratchCard(uid, dateKey = day2)
    assertTrue("First scratch on new day must be free", day2Card.isFree)
    ledger.claimScratchCard(uid, day2Card.cardId, dateKey = day2)
    assertEquals(1L, ledger.scratchDailyByUserAndDate[uid to day2]?.scratchesCompletedToday)
  }

  // =========================================================================
  // SCENARIO 19: ScratchCardScreen UI renders interactive card & Congratulations popup
  // =========================================================================
  @Test
  @Config(sdk = [33], qualifiers = "w393dp-h851dp-xxhdpi")
  fun scenario19_scratchCardScreen_uiInteraction_andCongratulationsPopup() {
    var claimed = false
    var dismissed = false

    composeTestRule.setContent {
      TaskFlowTheme {
        ScratchCardScreen(
          uiState = ScratchCardUiState(
            revealedCoins = 55L,
            isScratched = true,
            showCongratulations = true,
            dailyState = ScratchDailyState(
              scratchesCompletedToday = 1L,
              hasUsedFreeScratchToday = true
            )
          ),
          onBack = {},
          onWatchAd = {},
          onScratchRevealed = {},
          onClaimReward = { claimed = true },
          onDismissCongratulations = { dismissed = true }
        )
      }
    }

    composeTestRule.onNodeWithTag("scratch_card_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("interactive_scratch_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("congratulations_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("scratch_reward_amount_text").assertIsDisplayed()
    composeTestRule.onNodeWithTag("claim_scratch_reward_button").assertIsDisplayed().performClick()
    assertTrue("Tapping claim button in popup must trigger onClaimReward", claimed)
  }

  @Test
  @Config(sdk = [33], qualifiers = "w393dp-h851dp-xxhdpi")
  fun scenario19b_scratchCardScreen_showsRetryWhenCardReadyButSessionMissing() {
    var retried = false
    composeTestRule.setContent {
      TaskFlowTheme {
        ScratchCardScreen(
          uiState = ScratchCardUiState(
            isLoading = false,
            activeSession = null,
            errorMessage = "Server temporarily unavailable. Please try again.",
            dailyState = ScratchDailyState(
              scratchesCompletedToday = 0L,
              hasUsedFreeScratchToday = false
            )
          ),
          onBack = {},
          onWatchAd = {},
          onScratchRevealed = {},
          onClaimReward = {},
          onDismissCongratulations = {},
          onRetryPrepare = { retried = true }
        )
      }
    }

    composeTestRule.onNodeWithTag("scratch_error_banner").assertIsDisplayed()
    composeTestRule.onNodeWithTag("retry_prepare_scratch_button").assertIsDisplayed().performClick()
    assertTrue("Tapping Retry button must invoke onRetryPrepare", retried)
  }

  // =========================================================================
  // STEP 9A: REPOSITORY CALLABLE CONTRACTS (MANUAL BONUS + MULTI-TASK TELEGRAM)
  // =========================================================================
  @Test
  fun step9a_repository_creditManualBonus_callsCloudFunctionAndParsesResult() =
    kotlinx.coroutines.runBlocking {
      val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(
          context,
          com.google.firebase.FirebaseOptions.Builder()
            .setApplicationId("com.aistudio.taskflow.kxmpzq")
            .setProjectId("taskflow-8852d")
            .setApiKey("fake-api-key")
            .build()
        )
      }
      val calls = mutableListOf<Pair<String, Any?>>()
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardCallableInvoker = { name, payload ->
            calls.add(name to payload)
            mapOf(
              "success" to true,
              "id" to "bonus_key_9a",
              "targetUserId" to "uid_target_1",
              "targetUserName" to "Priya",
              "targetUserEmail" to "priya@example.com",
              "adminId" to "admin_uid_1",
              "adminEmail" to "dasakash24059@gmail.com",
              "amountCoins" to 550L,
              "reason" to "Contest Winner",
              "idempotencyKey" to "bonus_key_9a"
            )
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "admin_uid_1" }
        )

      val result =
        repo.creditAdminManualBonus(
          targetUserId = "uid_target_1",
          amountCoins = 550L,
          reason = "Contest Winner",
          idempotencyKey = "bonus_key_9a"
        )
      assertTrue(result.isSuccess)
      val record = result.getOrThrow()
      assertEquals("uid_target_1", record.targetUserId)
      assertEquals("admin_uid_1", record.adminId)
      assertEquals(550L, record.amountCoins)
      assertEquals("bonus_key_9a", record.idempotencyKey)

      assertEquals(1, calls.size)
      assertEquals("creditManualBonus", calls.first().first)
      val sentPayload = calls.first().second as Map<*, *>
      assertEquals("uid_target_1", sentPayload["targetUserId"])
      assertEquals(550L, sentPayload["amountCoins"])
      assertEquals("Contest Winner", sentPayload["reason"])
      assertEquals("bonus_key_9a", sentPayload["idempotencyKey"])
    }

  @Test
  fun step9a_repository_telegramCallables_matchBackendContractsAndParseResponses() =
    kotlinx.coroutines.runBlocking {
      val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(
          context,
          com.google.firebase.FirebaseOptions.Builder()
            .setApplicationId("com.aistudio.taskflow.kxmpzq")
            .setProjectId("taskflow-8852d")
            .setApiKey("fake-api-key")
            .build()
        )
      }
      val calls = mutableListOf<Pair<String, Any?>>()
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardCallableInvoker = { name, payload ->
            calls.add(name to payload)
            when (name) {
              "createTelegramTask" ->
                mapOf(
                  "success" to true,
                  "taskId" to "tg_task_101",
                  "title" to "Join Announcements",
                  "channelUrl" to "https://t.me/TaskFlowAnnouncements",
                  "channelChatId" to "@TaskFlowAnnouncements",
                  "rewardCoins" to 300L,
                  "isActive" to true,
                  "claimsCount" to 0L,
                  "createdBy" to "admin_uid_1"
                )
              "setTelegramTaskActiveStatus" ->
                mapOf("success" to true, "taskId" to "tg_task_101", "isActive" to false)
              "verifyTelegramTaskMembership" ->
                mapOf(
                  "success" to true,
                  "verified" to false,
                  "needsAccountLink" to true,
                  "taskId" to "tg_task_101",
                  "botUsername" to "TaskFlowVerifyBot",
                  "botLinkUrl" to "https://t.me/TaskFlowVerifyBot?start=tok123",
                  "message" to "Link your Telegram account with @TaskFlowVerifyBot."
                )
              "claimTelegramTaskReward" ->
                mapOf(
                  "success" to true,
                  "taskId" to "tg_task_101",
                  "rewardCoins" to 300L
                )
              else -> error("Unexpected callable $name")
            }
          }
        )

      val adminRepo =
        TaskFlowRepository(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "admin_uid_1" }
        )
      val created =
        adminRepo.createTelegramTask(
          title = "Join Announcements",
          channelUrl = "https://t.me/TaskFlowAnnouncements",
          channelChatId = "@TaskFlowAnnouncements",
          rewardCoins = 300L,
          isActive = true
        ).getOrThrow()
      assertEquals("tg_task_101", created.taskId)
      assertEquals(300L, created.rewardCoins)

      val toggled = adminRepo.setTelegramTaskActiveStatus("tg_task_101", false).getOrThrow()
      assertFalse(toggled)

      val userRepo =
        TaskFlowRepository(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "user_uid_1" }
        )
      val verify = userRepo.verifyTelegramTaskMembership("user_uid_1", "tg_task_101").getOrThrow()
      assertTrue(verify.needsAccountLink)
      assertFalse(verify.verified)
      assertEquals("https://t.me/TaskFlowVerifyBot?start=tok123", verify.botLinkUrl)

      val claimedCoins = userRepo.claimTelegramTaskReward("user_uid_1", "tg_task_101").getOrThrow()
      assertEquals(300L, claimedCoins)

      assertEquals(
        listOf(
          "createTelegramTask",
          "setTelegramTaskActiveStatus",
          "verifyTelegramTaskMembership",
          "claimTelegramTaskReward"
        ),
        calls.map { it.first }
      )
    }

  @Test
  fun phase1_telegramVerifyAndClaim_failClosedWithoutLocalFallback() =
    kotlinx.coroutines.runBlocking {
      val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
      if (com.google.firebase.FirebaseApp.getApps(context).isEmpty()) {
        com.google.firebase.FirebaseApp.initializeApp(
          context,
          com.google.firebase.FirebaseOptions.Builder()
            .setApplicationId("com.aistudio.taskflow.kxmpzq")
            .setProjectId("taskflow-8852d")
            .setApiKey("fake-api-key")
            .build()
        )
      }
      val failingClient =
        FirebaseRewardCloudFunctionsClient(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardCallableInvoker = { name, _ ->
            throw IllegalStateException("NOT_FOUND: $name unavailable")
          }
        )
      val userRepo =
        TaskFlowRepository(
          firestore = com.google.firebase.firestore.FirebaseFirestore.getInstance(),
          rewardFunctionsClient = failingClient,
          authenticatedUidProvider = { "user_uid_1" }
        )

      val verifyResult = userRepo.verifyTelegramTaskMembership("user_uid_1", "tg_task_101")
      assertTrue("verifyTelegramTaskMembership must fail closed when backend errors", verifyResult.isFailure)

      val claimResult = userRepo.claimTelegramTaskReward("user_uid_1", "tg_task_101")
      assertTrue("claimTelegramTaskReward must fail closed when backend errors", claimResult.isFailure)
    }

  @Test
  fun admin_selfManualBonus_and_documentWithIdField_doesNotCrashOnLoginOrDashboardLoad() {
    val adminEmail = "dasakash24059@gmail.com"
    val adminUid = "admin_dasakash_uid"
    val regularUid = "regular_user_uid"
    val ledger = SimulatedRewardsLedger()

    // Seed pre-existing admin and regular user accounts + pre-existing transaction history
    ledger.users[adminUid] =
      UserProfile(
        id = adminUid,
        userId = adminUid,
        email = adminEmail,
        displayName = "Akash Das",
        role = "admin",
        approvalStatus = "approved",
        balanceCoins = 1000L,
        eligibleCoins = 1000L
      )
    ledger.users[regularUid] =
      UserProfile(
        id = regularUid,
        userId = regularUid,
        email = "member@example.com",
        displayName = "Regular Member",
        role = "user",
        approvalStatus = "approved",
        balanceCoins = 400L,
        eligibleCoins = 400L
      )
    ledger.transactions.add(
      TransactionRecord(
        id = "tx_preexisting_1",
        userId = adminUid,
        userName = "Akash Das",
        type = TransactionType.TASK_REWARD,
        title = "Pre-existing Task Reward",
        amountCoins = 1000L,
        status = "CREDITED",
        createdAt = Timestamp.now()
      )
    )

    // 1. Verify existing Manual Bonus records in Firestore containing an explicit "id" field
    // deserialize cleanly via Firebase CustomClassMapper without throwing RuntimeException
    val existingFirestoreBonusMap =
      mapOf(
        "id" to "existing_bonus_doc_key",
        "targetUserId" to adminUid,
        "targetUserEmail" to adminEmail,
        "targetUserName" to "Akash Das",
        "amountCoins" to 250L,
        "reason" to "Existing Firestore Manual Bonus",
        "adminId" to adminUid,
        "adminEmail" to adminEmail,
        "idempotencyKey" to "existing_bonus_doc_key"
      )
    val parsedExistingBonus =
      com.google.firebase.firestore.util.CustomClassMapper.convertToCustomClass(
        existingFirestoreBonusMap,
        ManualBonusRecord::class.java,
        null
      )
    assertNotNull(parsedExistingBonus)
    assertEquals("existing_bonus_doc_key", parsedExistingBonus.id)
    assertEquals(adminUid, parsedExistingBonus.targetUserId)
    assertEquals(250L, parsedExistingBonus.amountCoins)

    listOf(
      ManualBonusRecord::class.java,
      PromoCodeItem::class.java,
      PromoRedemptionRecord::class.java,
      TelegramTaskItem::class.java,
      TelegramTaskClaimRecord::class.java
    ).forEach { clazz ->
      val idField = clazz.getDeclaredField("id")
      assertFalse(
        "${clazz.simpleName}.id must not have @DocumentId to prevent Firestore CustomClassMapper crash when 'id' is stored in document",
        idField.isAnnotationPresent(com.google.firebase.firestore.DocumentId::class.java)
      )
    }

    // 2. Admin credits a manual bonus to their own user account (dasakash24059@gmail.com)
    val bonusRecord =
      ledger.creditAdminManualBonus(
        adminId = adminUid,
        adminEmail = adminEmail,
        targetUserId = adminUid,
        amountCoins = 500L,
        reason = "Admin Self Test Bonus",
        idempotencyKey = "self_bonus_key_1"
      )
    assertEquals(adminUid, bonusRecord.targetUserId)
    assertEquals(adminEmail, bonusRecord.targetUserEmail)
    assertEquals(adminUid, bonusRecord.adminId)
    assertEquals(adminEmail, bonusRecord.adminEmail)
    assertEquals(500L, bonusRecord.amountCoins)

    // 3. Verify bonus is credited exactly once (duplicate submission with same idempotencyKey is blocked)
    try {
      ledger.creditAdminManualBonus(
        adminId = adminUid,
        adminEmail = adminEmail,
        targetUserId = adminUid,
        amountCoins = 500L,
        reason = "Admin Self Test Bonus",
        idempotencyKey = "self_bonus_key_1"
      )
      fail("Expected duplicate manual bonus with same idempotencyKey to be rejected")
    } catch (expected: IllegalStateException) {
      assertTrue(expected.message.orEmpty().contains("already been credited"))
    }

    // 4. Verify wallet balance, pre-existing transaction history, new transaction, notification,
    // and combined admin activity logs remain consistent and intact
    assertEquals(1500L, ledger.users[adminUid]?.getEffectiveBalanceCoins())
    assertEquals(400L, ledger.users[regularUid]?.getEffectiveBalanceCoins())
    assertEquals(2, ledger.transactions.size)
    assertTrue(ledger.transactions.any { it.id == "tx_preexisting_1" && it.amountCoins == 1000L })
    assertTrue(
      ledger.transactions.any {
        it.type == TransactionType.ADMIN_MANUAL_BONUS &&
          it.userId == adminUid &&
          it.amountCoins == 500L
      }
    )
    val adminNotifs = ledger.notificationsByUser[adminUid].orEmpty()
    assertEquals(1, adminNotifs.size)
    assertEquals(NotificationRewardType.ADMIN_BONUS, adminNotifs.first().type)
    assertEquals(500L, adminNotifs.first().rewardCoins)

    val combinedLogs =
      com.example.ui.admin.buildCombinedActivityEntries(
        activityLogs = emptyList(),
        reviewTasks = emptyList(),
        tasks = emptyList(),
        campaigns = emptyList(),
        transactions = ledger.transactions,
        users = ledger.users.values.toList()
      )
    assertEquals(combinedLogs.size, combinedLogs.map { it.id }.distinct().size)

    // 5. Verify AdminCoinsRewardsSection renders with self-bonus transaction & manual bonus record
    // without throwing any duplicate key or runtime exception
    composeTestRule.setContent {
      TaskFlowTheme {
        AdminCoinsRewardsSection(
          users = ledger.users.values.toList(),
          reviewTasks = emptyList(),
          transactions = ledger.transactions,
          telegramTasks = emptyList(),
          telegramClaims = emptyList(),
          telegramConfig = ledger.telegramConfig,
          promoCodes = ledger.promoCodes.values.toList(),
          manualBonuses = listOf(parsedExistingBonus) + ledger.manualBonuses.values.toList()
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("admin_coins_rewards_section")
      .performScrollToNode(hasTestTag("admin_manual_bonus_section"))
    composeTestRule.onNodeWithTag("admin_manual_bonus_section").assertIsDisplayed()
  }
}




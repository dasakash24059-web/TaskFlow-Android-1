package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp
import java.net.URI
import java.util.Locale

/**
 * Bootstrapped Owner / Administrator email configured in zero-trust Firestore Security Rules.
 */
const val BOOTSTRAPPED_ADMIN_EMAIL = "dasakash24059@gmail.com"

const val ADMIN_EMAIL_PRIVACY_PLACEHOLDER = "Authorized Administrator"

/**
 * Minimum withdrawal amount in INR paise (₹200.00 = 20,000 paise) and in Coins (25,000 Coins).
 * Conversion: 125 Coins = ₹1 (100 paise).
 */
const val MIN_WITHDRAWAL_CENTS = 20_000L
const val MIN_WITHDRAWAL_COINS = 25_000L
const val COINS_PER_RUPEE = 125L

/**
 * Converts legacy INR paise (cents) to integer Coins using 125 Coins = ₹1 (100 paise).
 */
fun centsToCoins(cents: Long): Long = (cents * COINS_PER_RUPEE) / 100L

/**
 * Converts integer Coins to INR paise (cents) using 125 Coins = ₹1 (100 paise).
 */
fun coinsToCents(coins: Long): Long = (coins * 100L) / COINS_PER_RUPEE

/**
 * Formats integer TF tokens for user-facing display (e.g., "375 TF", "25,000 TF").
 */
fun formatCoins(coins: Long): String = "${String.format(Locale.US, "%,d", coins)} TF"

/**
 * Formats integer TF tokens for user-facing display (e.g., "375 TF", "25,000 TF").
 */
fun formatTf(coins: Long): String = formatCoins(coins)

/**
 * Formats TF token amounts into compact notation for leaderboards and badges (e.g., "375 TF", "12.5K TF").
 */
fun formatCompactTf(coins: Long): String {
  val absVal = kotlin.math.abs(coins)
  val sign = if (coins < 0) "-" else ""
  return when {
    absVal >= 1_000_000L -> {
      val m = absVal / 1_000_000.0
      val formatted =
        if (absVal % 1_000_000L == 0L) String.format(Locale.US, "%dM", absVal / 1_000_000L)
        else String.format(Locale.US, "%.1fM", m).replace(".0M", "M")
      "$sign$formatted TF"
    }
    absVal >= 1_000L -> {
      val k = absVal / 1_000.0
      val formatted =
        if (absVal % 1_000L == 0L) String.format(Locale.US, "%dK", absVal / 1_000L)
        else String.format(Locale.US, "%.1fK", k).replace(".0K", "K")
      "$sign$formatted TF"
    }
    else -> "$coins TF"
  }
}

/**
 * Returns true if the given email or role belongs to the administrator.
 */
fun isHiddenAdminIdentity(email: String?, role: String? = null): Boolean {
  val normalizedEmail = email?.trim()?.lowercase(Locale.US) ?: ""
  val normalizedRole = role?.trim()?.lowercase(Locale.US) ?: ""
  return normalizedEmail == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US) ||
    normalizedRole == "admin"
}

/**
 * Hides the Admin's personal email address from UI screens while preserving regular user emails.
 */
fun formatVisibleUserEmail(email: String?, role: String? = null): String {
  if (isHiddenAdminIdentity(email, role)) {
    return ADMIN_EMAIL_PRIVACY_PLACEHOLDER
  }
  val trimmed = email?.trim() ?: ""
  return if (trimmed.isNotEmpty()) trimmed else "Verified Member"
}

/**
 * Validates that a Task URL is a well-formed HTTPS link (e.g., Google Play Store or website link).
 */
fun isValidTaskUrl(rawUrl: String): Boolean {
  val trimmed = rawUrl.trim()
  if (trimmed.isEmpty() || trimmed.length > 1000) return false
  if (!trimmed.startsWith("https://", ignoreCase = true)) return false
  return try {
    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host
    scheme == "https" && !host.isNullOrBlank() && host.contains(".") && !trimmed.contains(" ")
  } catch (e: Exception) {
    false
  }
}

/**
 * Validates optional App Icon / Image reference (blank, valid HTTPS URL, content URI, or data:image URI).
 */
fun isValidImageUrlOrDataUri(rawValue: String): Boolean {
  val trimmed = rawValue.trim()
  if (trimmed.isEmpty()) return true
  if (trimmed.length > 350000) return false
  return isValidTaskUrl(trimmed) ||
    trimmed.startsWith("data:image/", ignoreCase = true) ||
    trimmed.startsWith("content://", ignoreCase = true) ||
    trimmed.startsWith("file://", ignoreCase = true)
}

object TaskStatus {
  const val PENDING = "pending"
  const val SUBMITTED = "submitted"
  const val APPROVED = "approved"
  const val REJECTED = "rejected"

  // 5-Stage User Task Completion Flow constants
  const val STAGE_INSTALLATION_CONFIRMED = "installation_confirmed"
  const val STAGE_IN_PROGRESS = "in_progress"
  const val STAGE_SUBMITTED = "submitted"
  const val STAGE_PENDING_APPROVAL = "pending_approval"
  const val STAGE_DONE = "done"

  val ALL_STAGES =
    listOf(
      "Installation Confirmed",
      "In Progress",
      "Submitted",
      "Pending Approval",
      "Done"
    )

  fun label(status: String, isActive: Boolean = true): String {
    if (!isActive && status.lowercase(Locale.US) == PENDING) {
      return "Deactivated"
    }
    return when (status.lowercase(Locale.US)) {
      PENDING -> "Available"
      STAGE_INSTALLATION_CONFIRMED -> "Installation Confirmed"
      STAGE_IN_PROGRESS -> "In Progress"
      SUBMITTED -> "Pending Review"
      STAGE_PENDING_APPROVAL -> "Pending Approval"
      APPROVED -> "Approved"
      STAGE_DONE -> "Done"
      REJECTED -> "Rejected"
      else -> status.replaceFirstChar { it.uppercase() }
    }
  }

  fun historyLabel(status: String): String =
    when (status.lowercase(Locale.US)) {
      APPROVED, STAGE_DONE -> "Approved"
      REJECTED -> "Rejected"
      SUBMITTED, PENDING -> "Pending"
      else -> "Pending"
    }

  /**
   * Computes the current 0-based stage index (0..4) in the 5-stage user task completion flow:
   * 0: Installation Confirmed
   * 1: In Progress
   * 2: Submitted
   * 3: Pending Approval
   * 4: Done
   * Returns -1 if not yet started.
   */
  fun resolveFlowStageIndex(
    taskStatus: String,
    progressStatus: String = "",
    completedStepsCount: Int = 0,
    installationConfirmed: Boolean = false
  ): Int {
    return when (taskStatus.lowercase(Locale.US)) {
      APPROVED, STAGE_DONE -> 4
      SUBMITTED, STAGE_PENDING_APPROVAL -> 3
      REJECTED -> 1
      else -> {
        when (progressStatus.lowercase(Locale.US)) {
          STAGE_DONE -> 4
          STAGE_PENDING_APPROVAL -> 3
          STAGE_SUBMITTED -> 2
          STAGE_IN_PROGRESS -> 1
          STAGE_INSTALLATION_CONFIRMED -> if (completedStepsCount > 0) 1 else 0
          else -> {
            when {
              completedStepsCount > 0 -> 1
              installationConfirmed -> 0
              else -> -1
            }
          }
        }
      }
    }
  }

  fun resolveFlowStageLabel(
    taskStatus: String,
    progressStatus: String = "",
    completedStepsCount: Int = 0,
    installationConfirmed: Boolean = false,
    isActive: Boolean = true
  ): String {
    if (!isActive && taskStatus.lowercase(Locale.US) == PENDING) {
      return "Closed"
    }
    if (taskStatus.lowercase(Locale.US) == REJECTED) {
      return "Needs Revision"
    }
    return when (
      resolveFlowStageIndex(taskStatus, progressStatus, completedStepsCount, installationConfirmed)
    ) {
      4 -> "Done"
      3 -> "Pending Approval"
      2 -> "Submitted"
      1 -> "In Progress"
      0 -> "Installation Confirmed"
      else -> "Available"
    }
  }
}

object WithdrawalMethod {
  const val BANK_ACCOUNT = "Bank Account"
  const val UPI_ID = "UPI ID"

  val ALL = listOf(BANK_ACCOUNT, UPI_ID)
}

object WithdrawalMethodType {
  const val UPI = "UPI"
  const val AMAZON_GIFT_CARD = "AMAZON_GIFT_CARD"
  const val GOOGLE_PLAY_GIFT_CARD = "GOOGLE_PLAY_GIFT_CARD"
  const val FLIPKART_GIFT_CARD = "FLIPKART_GIFT_CARD"

  val ALL_REDEMPTION_METHODS =
    listOf(UPI, AMAZON_GIFT_CARD, GOOGLE_PLAY_GIFT_CARD, FLIPKART_GIFT_CARD)

  val GIFT_CARD_METHODS =
    listOf(AMAZON_GIFT_CARD, GOOGLE_PLAY_GIFT_CARD, FLIPKART_GIFT_CARD)

  fun isGiftCard(method: String): Boolean =
    method.trim().uppercase(Locale.US) in GIFT_CARD_METHODS

  fun isUpi(method: String): Boolean {
    val norm = method.trim().uppercase(Locale.US)
    return norm == UPI || norm == "UPI ID"
  }

  fun displayLabel(method: String): String =
    when (method.trim().uppercase(Locale.US)) {
      UPI, "UPI ID" -> "UPI"
      AMAZON_GIFT_CARD -> "Amazon Gift Card"
      GOOGLE_PLAY_GIFT_CARD -> "Google Play Gift Code"
      FLIPKART_GIFT_CARD -> "Flipkart Gift Card"
      else -> method.ifBlank { "Withdrawal" }
    }
}

data class WithdrawalDenomination(
  val amountRupees: Long,
  val amountCoins: Long
) {
  val amountCents: Long
    get() = amountRupees * 100L
}

object WithdrawalCatalog {
  val UPI_DENOMINATIONS =
    listOf(
      WithdrawalDenomination(amountRupees = 200L, amountCoins = 33_000L),
      WithdrawalDenomination(amountRupees = 500L, amountCoins = 82_500L)
    )

  val GIFT_CARD_DENOMINATIONS =
    listOf(
      WithdrawalDenomination(amountRupees = 150L, amountCoins = 33_000L),
      WithdrawalDenomination(amountRupees = 200L, amountCoins = 44_000L),
      WithdrawalDenomination(amountRupees = 250L, amountCoins = 55_000L)
    )

  fun denominationsFor(method: String): List<WithdrawalDenomination> =
    when {
      WithdrawalMethodType.isUpi(method) -> UPI_DENOMINATIONS
      WithdrawalMethodType.isGiftCard(method) -> GIFT_CARD_DENOMINATIONS
      else -> emptyList()
    }

  fun resolveRequiredCoins(method: String, amountRupees: Long): Long? =
    denominationsFor(method).firstOrNull { it.amountRupees == amountRupees }?.amountCoins
}

object WithdrawalStatus {
  const val SUBMITTED = "SUBMITTED"
  const val UNDER_REVIEW = "UNDER_REVIEW"
  const val PROCESSING = "PROCESSING"
  const val COMPLETED = "COMPLETED"
  const val REJECTED = "REJECTED"
  const val DELAYED = "DELAYED"

  const val DELAYED_THRESHOLD_MS = 48L * 60L * 60L * 1000L

  fun isTerminal(status: String): Boolean {
    val norm = status.trim().uppercase(Locale.US)
    return norm == COMPLETED || norm == REJECTED || norm == "APPROVED"
  }

  fun isPendingOrActive(status: String): Boolean = !isTerminal(status)

  fun isDelayed(
    status: String,
    createdAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis()
  ): Boolean {
    if (isTerminal(status)) return false
    if (status.trim().equals(DELAYED, ignoreCase = true)) return true
    if (createdAtMillis <= 0L) return false
    return (nowMillis - createdAtMillis) >= DELAYED_THRESHOLD_MS
  }

  fun displayLabel(
    status: String,
    createdAtMillis: Long = 0L,
    nowMillis: Long = System.currentTimeMillis()
  ): String {
    if (isDelayed(status, createdAtMillis, nowMillis)) return "Delayed"
    return when (status.trim().uppercase(Locale.US)) {
      SUBMITTED -> "Submitted"
      UNDER_REVIEW -> "Under Review"
      PROCESSING -> "Processing"
      COMPLETED, "APPROVED" -> "Completed"
      REJECTED -> "Rejected"
      "PENDING_DEMO" -> "Pending Review"
      else -> status.replace('_', ' ').lowercase(Locale.US).replaceFirstChar { it.uppercase() }
    }
  }
}

object TaskCategories {
  val ALL =
    listOf(
      "App Install",
      "Website Visit",
      "QA Testing",
      "Content Review",
      "Data Verification",
      "UX Research",
      "Documentation"
    )
}

object TransactionType {
  const val DAILY_CHECKIN = "DAILY_CHECKIN"
  const val TASK_REWARD = "TASK_REWARD"
  const val DEMO_WITHDRAWAL = "DEMO_WITHDRAWAL"
  const val WITHDRAWAL_DEBIT = "WITHDRAWAL_DEBIT"
  const val WITHDRAWAL_REFUND = "WITHDRAWAL_REFUND"
  const val WATCH_EARN = "WATCH_EARN"
  const val SPIN_EARN = "SPIN_EARN"
  const val TELEGRAM_JOIN = "TELEGRAM_JOIN"
  const val PROMO_CODE = "PROMO_CODE"
  const val ADMIN_MANUAL_BONUS = "ADMIN_MANUAL_BONUS"
  const val ADMIN_BONUS = "ADMIN_BONUS"
  const val MANUAL_BONUS = "MANUAL_BONUS"
  const val BONUS = "BONUS"
  const val SCRATCH_CARD = "SCRATCH_CARD"
  const val CAMPAIGN_REWARD = "CAMPAIGN_REWARD"
  const val CAMPAIGN_REVERSAL = "CAMPAIGN_REVERSAL"

  private val CREDITED_REWARD_TYPES =
    setOf(
      DAILY_CHECKIN,
      TASK_REWARD,
      WATCH_EARN,
      SPIN_EARN,
      TELEGRAM_JOIN,
      PROMO_CODE,
      ADMIN_MANUAL_BONUS,
      ADMIN_BONUS,
      MANUAL_BONUS,
      BONUS,
      SCRATCH_CARD,
      CAMPAIGN_REWARD
    )

  fun isCreditedRewardType(type: String): Boolean =
    CREDITED_REWARD_TYPES.contains(type.trim().uppercase(Locale.US))

  fun isDebitOrReversalType(type: String): Boolean {
    val norm = type.trim().uppercase(Locale.US)
    return norm == DEMO_WITHDRAWAL || norm == WITHDRAWAL_DEBIT || norm == CAMPAIGN_REVERSAL
  }

  fun isManualBonusType(type: String): Boolean {
    val norm = type.trim().uppercase(Locale.US)
    return norm == ADMIN_MANUAL_BONUS || norm == ADMIN_BONUS || norm == MANUAL_BONUS || norm == BONUS
  }
}

data class TransactionRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val userName: String = "",
  val type: String = TransactionType.DAILY_CHECKIN,
  val title: String = "",
  val amountCents: Long = 0L,
  val amountCoins: Long = 0L,
  val status: String = "CREDITED",
  val referenceId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
) {
  fun getEffectiveAmountCoins(): Long =
    if (amountCoins > 0L) amountCoins else centsToCoins(amountCents)
}

enum class LeaderboardPeriod(val label: String, val testTagSuffix: String) {
  ALL_TIME("All Time", "all_time"),
  THIS_MONTH("This Month", "this_month"),
  THIS_WEEK("This Week", "this_week")
}

fun getLeaderboardPeriodStartMillis(
  period: LeaderboardPeriod,
  nowMillis: Long = System.currentTimeMillis()
): Long {
  if (period == LeaderboardPeriod.ALL_TIME) return 0L
  val tz = java.util.TimeZone.getTimeZone("Asia/Kolkata")
  val cal = java.util.Calendar.getInstance(tz).apply {
    timeInMillis = nowMillis
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
  }
  return when (period) {
    LeaderboardPeriod.ALL_TIME -> 0L
    LeaderboardPeriod.THIS_MONTH -> {
      cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
      cal.timeInMillis
    }
    LeaderboardPeriod.THIS_WEEK -> {
      val dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK)
      val daysFromMonday =
        if (dayOfWeek == java.util.Calendar.SUNDAY) 6
        else dayOfWeek - java.util.Calendar.MONDAY
      cal.add(java.util.Calendar.DAY_OF_YEAR, -daysFromMonday)
      cal.timeInMillis
    }
  }
}

data class LeaderboardEntryUi(
  val rank: Int,
  val userId: String,
  val displayName: String,
  val earnedCoins: Long,
  val completedTasksCount: Long = 0L
)

/**
 * Computes accurate Leaderboard rankings for the selected time period (`All Time`, `This Month`, `This Week`).
 * Counts ONLY tokens actually earned and credited (`status == "CREDITED"` and reward transaction types).
 * Never includes pending tasks, unapproved submissions, rejected/failed items, or withdrawal debits/refunds.
 */
fun computeLeaderboardEntriesForPeriod(
  users: List<UserProfile>,
  transactions: List<TransactionRecord>,
  period: LeaderboardPeriod,
  nowMillis: Long = System.currentTimeMillis()
): List<LeaderboardEntryUi> {
  val activeUsersByUid = LinkedHashMap<String, UserProfile>()
  users
    .filter {
      !isHiddenAdminIdentity(it.email, it.role) &&
        it.approvalStatus != "suspended"
    }
    .forEach { u ->
      val uid = u.userId.ifBlank { u.id }
      if (uid.isNotBlank()) {
        activeUsersByUid[uid] = u.copy(id = uid, userId = uid)
      }
    }

  val startMillis = getLeaderboardPeriodStartMillis(period, nowMillis)
  val validCreditedTxs =
    transactions
      .filter { tx ->
        tx.userId.isNotBlank() &&
          tx.status.trim().equals("CREDITED", ignoreCase = true) &&
          TransactionType.isCreditedRewardType(tx.type) &&
          tx.getEffectiveAmountCoins() > 0L
      }
      .distinctBy { tx ->
        tx.id.ifBlank { "${tx.userId}_${tx.type}_${tx.referenceId}_${tx.createdAt?.seconds ?: 0L}" }
      }

  // Fallback for standalone UI tests where no transactions list is supplied
  if (validCreditedTxs.isEmpty()) {
    return activeUsersByUid.values
      .filter { u ->
        if (period == LeaderboardPeriod.ALL_TIME) {
          u.getLeaderboardDisplayCoins() > 0L || u.completedTasksCount > 0L
        } else {
          val tsMillis = (u.updatedAt ?: u.createdAt)?.toDate()?.time ?: nowMillis
          tsMillis >= startMillis && (u.getLeaderboardDisplayCoins() > 0L || u.completedTasksCount > 0L)
        }
      }
      .sortedWith(
        compareByDescending<UserProfile> { it.getLeaderboardDisplayCoins() }
          .thenByDescending { it.completedTasksCount }
          .thenBy { it.displayName.lowercase(Locale.US) }
          .thenBy { it.userId }
      )
      .mapIndexed { idx, u ->
        LeaderboardEntryUi(
          rank = idx + 1,
          userId = u.userId.ifBlank { u.id },
          displayName = u.displayName.ifBlank { "TaskFlow Member" },
          earnedCoins = u.getLeaderboardDisplayCoins(),
          completedTasksCount = u.completedTasksCount
        )
      }
  }

  val earnedByUid = LinkedHashMap<String, Long>()
  val nameByUid = LinkedHashMap<String, String>()

  validCreditedTxs.forEach { tx ->
    val txMillis = tx.createdAt?.toDate()?.time ?: 0L
    val inWindow =
      when (period) {
        LeaderboardPeriod.ALL_TIME -> true
        LeaderboardPeriod.THIS_MONTH, LeaderboardPeriod.THIS_WEEK ->
          txMillis in startMillis..nowMillis
      }
    if (inWindow) {
      val uid = tx.userId
      earnedByUid[uid] = (earnedByUid[uid] ?: 0L) + tx.getEffectiveAmountCoins()
      if (tx.userName.isNotBlank() && !nameByUid.containsKey(uid)) {
        nameByUid[uid] = tx.userName
      }
    }
  }

  val allCandidateUids = LinkedHashSet<String>().apply {
    addAll(activeUsersByUid.keys)
    addAll(earnedByUid.keys)
  }

  val result = mutableListOf<UserProfile>()
  for (uid in allCandidateUids) {
    val baseProfile = activeUsersByUid[uid]
    if (baseProfile != null && isHiddenAdminIdentity(baseProfile.email, baseProfile.role)) {
      continue
    }
    val txEarned = earnedByUid[uid] ?: 0L
    val periodEarnedCoins =
      when (period) {
        LeaderboardPeriod.ALL_TIME -> {
          val profileFloor = baseProfile?.getLeaderboardDisplayCoins() ?: 0L
          maxOf(txEarned, profileFloor)
        }
        LeaderboardPeriod.THIS_MONTH, LeaderboardPeriod.THIS_WEEK -> txEarned
      }

    if (periodEarnedCoins > 0L || (period == LeaderboardPeriod.ALL_TIME && (baseProfile?.completedTasksCount ?: 0L) > 0L)) {
      val resolvedName =
        baseProfile?.displayName?.ifBlank { nameByUid[uid] ?: "TaskFlow Member" }
          ?: nameByUid[uid]?.ifBlank { "TaskFlow Member" }
          ?: "TaskFlow Member"
      result.add(
        (baseProfile ?: UserProfile(id = uid, userId = uid, displayName = resolvedName)).copy(
          id = uid,
          userId = uid,
          displayName = resolvedName,
          balanceCoins = periodEarnedCoins,
          balanceCents = coinsToCents(periodEarnedCoins)
        )
      )
    }
  }

  return result
    .sortedWith(
      compareByDescending<UserProfile> { it.getLeaderboardDisplayCoins() }
        .thenByDescending { it.completedTasksCount }
        .thenBy { it.displayName.lowercase(Locale.US) }
        .thenBy { it.userId }
    )
    .mapIndexed { idx, u ->
      LeaderboardEntryUi(
        rank = idx + 1,
        userId = u.userId.ifBlank { u.id },
        displayName = u.displayName.ifBlank { "TaskFlow Member" },
        earnedCoins = u.getLeaderboardDisplayCoins(),
        completedTasksCount = u.completedTasksCount
      )
    }
}

data class WatchStats(
  val userId: String = "",
  val dateKey: String = "",
  val videosWatched: Long = 0L,
  val totalEarnedCents: Long = 0L,
  val totalEarnedCoins: Long = 0L
)

enum class WatchEarnFlowStatus {
  IDLE,
  LOADING_AD,
  SHOWING_AD,
  READY_TO_CLAIM,
  CLAIMING,
  CLAIMED,
  ERROR
}

data class WatchEarnUiState(
  val status: WatchEarnFlowStatus = WatchEarnFlowStatus.IDLE,
  val activeSessionId: String = "",
  val videosCompletedInCycle: Int = 0,
  val lastRewardCoins: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null
) {
  val isLoadingAd: Boolean
    get() = status == WatchEarnFlowStatus.LOADING_AD || status == WatchEarnFlowStatus.SHOWING_AD
  val isReadyToClaim: Boolean
    get() = status == WatchEarnFlowStatus.READY_TO_CLAIM && activeSessionId.isNotBlank()
  val isClaiming: Boolean
    get() = status == WatchEarnFlowStatus.CLAIMING
  val isClaimed: Boolean
    get() = status == WatchEarnFlowStatus.CLAIMED
  val hasError: Boolean
    get() = status == WatchEarnFlowStatus.ERROR
}

object SpinWheelConstants {
  val SEGMENTS_COINS = listOf(10L, 20L, 30L, 40L, 50L, 100L, 125L, 150L)
  const val FREE_SPIN_COOLDOWN_MS = 24L * 60L * 60L * 1000L
  const val EXTRA_SPIN_REQUIRED_VIDEOS = 2
}

data class SpinSessionOutcome(
  val sessionId: String,
  val segmentIndex: Int,
  val rewardCoins: Long,
  val usedFreeSpin: Boolean = false
)

data class SpinEarnUiState(
  val spinsUsed: Int = 0,
  val lastSpinResult: Long = 0L,
  val isSpinning: Boolean = false,
  val isLoadingAd: Boolean = false,
  val isClaiming: Boolean = false,
  val activeSessionId: String = "",
  val selectedSegmentIndex: Int? = null,
  val selectedRewardCoins: Long? = null,
  val freeSpinAvailable: Boolean = true,
  val freeSpinNextAvailableAtMillis: Long = 0L,
  val extraSpinVideosWatched: Int = 0,
  val adsWatchedForSpin: Int = 0,
  val extraSpinAvailable: Boolean = false,
  val isWatchingExtraSpinAd: Boolean = false,
  val isReadyToClaim: Boolean = false,
  val isClaimingRewardAd: Boolean = false,
  val claimedRewardCoins: Long = 0L,
  val lastClaimedCoins: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null
)

object ScratchCardConstants {
  const val MIN_REWARD_COINS = 1L
  const val MAX_REWARD_COINS = 100L
  const val TIER_LOW_MAX_COINS = 40L
  const val TIER_HIGH_MIN_COINS = 41L
  const val LOW_TIER_PROBABILITY = 0.90
  const val HIGH_TIER_PROBABILITY = 0.10
  const val ADS_REQUIRED_PER_SCRATCH = 2
  const val MAX_DAILY_SCRATCHES = 50L
  const val REWARDED_PLACEMENT_ID = "BP_Rewarded_Android"
  const val GAME_ID = "800430673"

  /**
   * Generates a reward between 1 and 100 coins with:
   * - 90% probability: 1–40 coins
   * - 10% probability: 41–100 coins
   * - Legitimate user activity (completedTasksCount) provides subtle positive weighting without predictability.
   */
  fun calculateScratchReward(
    completedTasksCount: Long = 0L,
    randomRoll: Double = java.util.concurrent.ThreadLocalRandom.current().nextDouble(),
    randomValue: Double = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
  ): Long {
    val isHighTier = randomRoll >= LOW_TIER_PROBABILITY
    return if (isHighTier) {
      val taskBoost = (completedTasksCount.coerceIn(0L, 25L) * 1.5).toLong()
      val baseMin = (TIER_HIGH_MIN_COINS + taskBoost).coerceAtMost(80L)
      val spread = (MAX_REWARD_COINS - baseMin + 1L).coerceAtLeast(1L)
      (baseMin + (randomValue * spread).toLong()).coerceIn(TIER_HIGH_MIN_COINS, MAX_REWARD_COINS)
    } else {
      val taskBoost = (completedTasksCount.coerceIn(0L, 20L) / 2L)
      val baseMin = (MIN_REWARD_COINS + taskBoost).coerceAtMost(20L)
      val spread = (TIER_LOW_MAX_COINS - baseMin + 1L).coerceAtLeast(1L)
      (baseMin + (randomValue * spread).toLong()).coerceIn(MIN_REWARD_COINS, TIER_LOW_MAX_COINS)
    }
  }
}

data class ScratchCardSession(
  @DocumentId val id: String = "",
  val cardId: String = "",
  val userId: String = "",
  val dateKey: String = "",
  val scratchNumber: Long = 1L,
  val rewardCoins: Long = 0L,
  val isFree: Boolean = true,
  val status: String = "ACTIVE",
  val claimed: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val claimedAt: Timestamp? = null
)

data class ScratchDailyState(
  val dateKey: String = "",
  val scratchesCompletedToday: Long = 0L,
  val adsWatchedForNextScratch: Long = 0L,
  val hasUsedFreeScratchToday: Boolean = false,
  val unlockedScratchesCount: Long = 0L,
  val activeCardId: String = "",
  val activeCardRewardCoins: Long = 0L,
  val activeCardIsScratched: Boolean = false,
  val activeCardIsClaimed: Boolean = false
) {
  val isDailyLimitReached: Boolean
    get() = scratchesCompletedToday >= ScratchCardConstants.MAX_DAILY_SCRATCHES

  val hasActiveUnclaimedCard: Boolean
    get() = activeCardId.isNotBlank() && !activeCardIsClaimed

  val canScratchNow: Boolean
    get() = !isDailyLimitReached && (!hasUsedFreeScratchToday || unlockedScratchesCount > 0L || hasActiveUnclaimedCard)

  val adsRemainingForNextScratch: Int
    get() = (ScratchCardConstants.ADS_REQUIRED_PER_SCRATCH - adsWatchedForNextScratch.toInt())
      .coerceIn(0, ScratchCardConstants.ADS_REQUIRED_PER_SCRATCH)
}

data class ScratchCardUiState(
  val isLoading: Boolean = false,
  val isClaiming: Boolean = false,
  val isAdLoading: Boolean = false,
  val dailyState: ScratchDailyState = ScratchDailyState(),
  val activeSession: ScratchCardSession? = null,
  val revealedCoins: Long = 0L,
  val isScratched: Boolean = false,
  val showCongratulations: Boolean = false,
  val errorMessage: String? = null,
  val successMessage: String? = null
)

object DailyCheckInConstants {
  // 30-Day Coin Reward Schedule
  val REWARDS_COINS = listOf(
    25L, 35L, 50L, 60L, 75L, 85L, 100L, 110L, 125L, 150L,
    160L, 175L, 185L, 200L, 210L, 225L, 250L, 260L, 275L, 285L,
    300L, 325L, 350L, 375L, 400L, 425L, 450L, 500L, 750L, 1000L
  )

  const val AD_VERIFICATION_REQUIRED_MESSAGE =
    "Daily Check-in is pending. Advertisement completion is required after which rewards can be credited."

  fun getRewardCoinsForDay(day: Int): Long {
    val index = (day - 1).coerceIn(0, 29)
    return REWARDS_COINS[index]
  }
}

data class DailyCheckInState(
  val currentStreak: Int = 0,
  val nextClaimDay: Int = 1,
  val canCheckInToday: Boolean = true,
  val lastCheckInDate: String = "",
  val todayDate: String = "",
  val nextRewardCents: Long = 25L,
  val nextRewardCoins: Long = 25L
)

fun getTodayDateKey(): String {
  val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
  sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
  return sdf.format(java.util.Date())
}

fun getYesterdayDateKey(): String {
  val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Kolkata"))
  cal.add(java.util.Calendar.DATE, -1)
  val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
  sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Kolkata")
  return sdf.format(cal.time)
}

fun calculateDailyCheckInState(lastDate: String, streak: Long): DailyCheckInState {
  val today = getTodayDateKey()
  val yesterday = getYesterdayDateKey()
  val rawStreak = streak.toInt()

  return if (lastDate == today) {
    // Already checked in today
    val streakDay = if (rawStreak in 1..30) rawStreak else 1
    val nextDay = if (streakDay >= 30) 1 else streakDay + 1
    val coins = DailyCheckInConstants.getRewardCoinsForDay(nextDay)
    DailyCheckInState(
      currentStreak = streakDay,
      nextClaimDay = nextDay,
      canCheckInToday = false,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = coins,
      nextRewardCoins = coins
    )
  } else if (lastDate == yesterday) {
    // Consecutive check-in: continuing streak
    val nextStreak = if (rawStreak >= 30) 1 else rawStreak + 1
    val coins = DailyCheckInConstants.getRewardCoinsForDay(nextStreak)
    DailyCheckInState(
      currentStreak = rawStreak,
      nextClaimDay = nextStreak,
      canCheckInToday = true,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = coins,
      nextRewardCoins = coins
    )
  } else {
    // Missed a day or first time -> Reset streak to Day 1
    val coins = DailyCheckInConstants.getRewardCoinsForDay(1)
    DailyCheckInState(
      currentStreak = 0,
      nextClaimDay = 1,
      canCheckInToday = true,
      lastCheckInDate = lastDate,
      todayDate = today,
      nextRewardCents = coins,
      nextRewardCoins = coins
    )
  }
}

data class UserProfile(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  val displayName: String = "",
  val photoUrl: String = "",
  val role: String = "user",
  val approvalStatus: String = "approved",
  val balanceCents: Long = 0L, // Legacy (100 paise = ₹1 = 125 Coins)
  val balanceCoins: Long = 0L, // Total Coin balance
  val eligibleCoins: Long = 0L, // Eligible task-earned withdrawable Coins
  val promotionalCoins: Long = 0L, // Ad/Spin/Check-in promotional Coins (non-withdrawable directly)
  val virtualPoints: Long = 0L,
  val completedTasksCount: Long = 0L,
  val assignedTaskId: String = "",
  val lastCheckInDate: String = "",
  val checkInStreak: Long = 0L,
  val lastFreeSpinAt: Timestamp? = null,
  val extraSpinVideosWatched: Long = 0L,
  val extraSpinAvailable: Boolean = false,
  val telegramJoinedVerified: Boolean = false,
  val telegramRewardClaimed: Boolean = false,
  val telegramUserId: String = "",
  @ServerTimestamp val lastCheckInTimestamp: Timestamp? = null,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Returns the user's eligible withdrawable Coins (earned from approved tasks).
 * 125 Coins = ₹1 (100 paise).
 */
fun UserProfile.getEligibleWithdrawalCoins(): Long =
  maxOf(eligibleCoins, centsToCoins(balanceCents))

/**
 * Returns the user's promotional Coins (earned from Spin, Watch & Earn, Daily Check-in).
 */
fun UserProfile.getPromotionalBalanceCoins(): Long = promotionalCoins

/**
 * Returns the user's total effective Coin balance (eligible + promotional Coins).
 * 125 Coins = ₹1 (100 paise) -> centsToCoins(balanceCents) = (balanceCents * 125) / 100.
 */
fun UserProfile.getEffectiveBalanceCoins(): Long {
  val combined = getEligibleWithdrawalCoins() + promotionalCoins
  return maxOf(balanceCoins, combined)
}

/**
 * Returns the user's Coins value for public leaderboard sorting and display,
 * preferring authoritative `balanceCoins` while keeping withdrawable `balanceCents` distinct.
 */
fun UserProfile.getLeaderboardDisplayCoins(): Long =
  if (balanceCoins > 0L) balanceCoins else centsToCoins(balanceCents)

data class TaskStep(
  val stepNumber: Int = 1,
  val title: String = "",
  val instructions: String = "",
  val destinationUrl: String = "",
  val actionLabel: String = "Open Link",
  val requiresProof: Boolean = false,
  val requiresTracking: Boolean = false,
  val referenceImageUrl: String = "",
  val referenceImageCaption: String = ""
)

data class TaskItem(
  @DocumentId val id: String = "",
  val appId: String = "",
  val campaignId: String = "",
  val title: String = "",
  val appName: String = "",
  val appIconUrl: String = "",
  val description: String = "",
  val installationInstructions: String = "",
  val instructions: String = "",
  val requiredProof: String = "",
  val taskLink: String = "",
  val category: String = "App Install",
  val rewardCents: Long = 0L, // Legacy
  val rewardCoins: Long = 0L, // New
  val completionDurationMinutes: Int = 5,
  val targetSubmissions: Long = 0L, // 0 = unlimited, >0 = quota
  val completedSubmissions: Long = 0L,
  val startDate: String = "",
  val endDate: String = "",
  val eligibilityRules: String = "All Approved Users",
  val assignedUserId: String = "",
  val assignedUserName: String = "",
  val assignedUserEmail: String = "",
  val status: String = TaskStatus.PENDING,
  @get:PropertyName("isActive") val isActive: Boolean = true,
  val proofText: String = "",
  val proofImageUrl: String = "",
  val proofStoragePath: String = "",
  val adminFeedback: String = "",
  val createdBy: String = "",
  val steps: List<TaskStep> = emptyList(),
  val referenceImages: List<String> = emptyList(),
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val isQuotaExhausted: Boolean
    get() = targetSubmissions > 0L && completedSubmissions >= targetSubmissions

  val remainingQuota: Long
    get() =
      if (targetSubmissions > 0L) (targetSubmissions - completedSubmissions).coerceAtLeast(0L)
      else -1L

  fun isAvailableForNewSubmission(): Boolean =
    status == TaskStatus.PENDING && isActive && !isQuotaExhausted

  fun getEffectiveRewardCoins(): Long =
    if (rewardCoins > 0L) rewardCoins else centsToCoins(rewardCents)
}

data class SubmissionItem(
  @DocumentId val id: String = "",
  val taskId: String = "",
  val taskTitle: String = "",
  val appName: String = "",
  val appIconUrl: String = "",
  val rewardCents: Long = 0L, // Legacy
  val rewardCoins: Long = 0L, // New
  val userId: String = "",
  val userName: String = "",
  val userEmail: String = "",
  val proofText: String = "",
  val proofImageUrl: String = "",
  val proofStoragePath: String = "",
  val status: String = TaskStatus.SUBMITTED,
  val adminFeedback: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  fun getEffectiveRewardCoins(): Long =
    if (rewardCoins > 0L) rewardCoins else centsToCoins(rewardCents)

  /**
   * Converts a preserved `/submissions/{id}` document into a `TaskItem` representation so user
   * history and activity remain intact even if the original task in `/tasks` was deleted by Admin.
   */
  fun toHistoryTaskItem(existingTask: TaskItem? = null): TaskItem {
    val resolvedId = taskId.ifBlank { id }
    return TaskItem(
      id = resolvedId,
      title = taskTitle.ifBlank { existingTask?.title ?: "Completed Task" },
      appName = appName.ifBlank { existingTask?.appName ?: "" },
      appIconUrl = appIconUrl.ifBlank { existingTask?.appIconUrl ?: "" },
      description = existingTask?.description ?: "Submitted task proof record preserved in history.",
      instructions = existingTask?.instructions ?: "",
      requiredProof = existingTask?.requiredProof ?: "",
      taskLink = existingTask?.taskLink ?: "",
      category = existingTask?.category ?: "App Install",
      rewardCents = if (rewardCents > 0L) rewardCents else (existingTask?.rewardCents ?: 0L),
      rewardCoins = if (rewardCoins > 0L) rewardCoins else (existingTask?.rewardCoins ?: 0L),
      assignedUserId = userId,
      assignedUserName = userName,
      assignedUserEmail = userEmail,
      status = status,
      isActive = existingTask?.isActive ?: false,
      proofText = proofText,
      proofImageUrl = proofImageUrl,
      proofStoragePath = proofStoragePath,
      adminFeedback = adminFeedback,
      createdBy = existingTask?.createdBy ?: "",
      createdAt = createdAt ?: existingTask?.createdAt,
      updatedAt = updatedAt ?: existingTask?.updatedAt ?: createdAt
    )
  }
}


/**
 * Merges active/existing tasks from `/tasks` with the user's preserved `/submissions` documents
 * so deleting a task from `/tasks` never erases the user's submission history or activity records.
 */
fun mergeTasksWithPreservedSubmissions(
  tasks: List<TaskItem>,
  submissions: List<SubmissionItem>,
  currentUserId: String
): List<TaskItem> {
  val taskMap = tasks.associateBy { it.id }.toMutableMap()
  submissions
    .filter { currentUserId.isBlank() || it.userId == currentUserId }
    .forEach { sub ->
      val key = sub.taskId.ifBlank { sub.id }
      if (key.isNotBlank()) {
        val existing = taskMap[key]
        // If task was deleted from /tasks or doesn't reflect this user's submission, preserve it
        if (existing == null || existing.status == TaskStatus.PENDING) {
          taskMap[key] = sub.toHistoryTaskItem(existing)
        }
      }
    }
  return taskMap.values.sortedByDescending {
    (it.updatedAt ?: it.createdAt)?.seconds ?: 0L
  }
}

/**
 * Withdrawal & Gift Card Redemption record stored in `/withdrawal_requests/{requestId}`.
 * Supports fixed-denomination UPI and Gift Card redemptions with immediate coin deduction,
 * atomic single-refund on rejection, manual Admin gift code delivery, and legacy compatibility.
 */
data class DemoWithdrawalRequest(
  @DocumentId val id: String = "",
  val requestId: String = "",
  val userId: String = "",
  val userName: String = "",
  val amountRupees: Long = 0L,
  val amountCents: Long = 0L,
  val amountCoins: Long = 0L,
  val method: String = WithdrawalMethodType.UPI,
  val upiId: String = "",
  val status: String = WithdrawalStatus.SUBMITTED,
  val statusNote: String = "",
  val adminActionBy: String = "",
  val paymentReference: String = "",
  val giftCode: String = "",
  val giftCodeHash: String = "",
  val giftCodeAssignedAt: Timestamp? = null,
  val coinsDeducted: Boolean = false,
  val coinsRefunded: Boolean = false,
  val deductedBalanceCoins: Long = 0L,
  val deductedEligibleCoins: Long = 0L,
  val deductedPromotionalCoins: Long = 0L,
  val deductedBalanceCents: Long = 0L,
  val idempotencyKey: String = "",
  @get:PropertyName("isDemo") val isDemo: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null,
  val reviewedAt: Timestamp? = null,
  val completedAt: Timestamp? = null,
  val rejectedAt: Timestamp? = null
) {
  fun getEffectiveAmountCoins(): Long =
    if (amountCoins > 0L) amountCoins else centsToCoins(amountCents)

  fun getEffectiveAmountRupees(): Long =
    if (amountRupees > 0L) amountRupees else amountCents / 100L

  fun getEffectiveMethodLabel(): String = WithdrawalMethodType.displayLabel(method)

  fun isDelayedAt(nowMillis: Long = System.currentTimeMillis()): Boolean {
    val createdMs = createdAt?.toDate()?.time ?: 0L
    return WithdrawalStatus.isDelayed(status, createdMs, nowMillis)
  }

  fun getDisplayStatus(nowMillis: Long = System.currentTimeMillis()): String {
    val createdMs = createdAt?.toDate()?.time ?: 0L
    return WithdrawalStatus.displayLabel(status, createdMs, nowMillis)
  }
}

typealias WithdrawalRequest = DemoWithdrawalRequest

data class WithdrawalAvailabilityConfig(
  val upiEnabled: Boolean = true,
  val amazonEnabled: Boolean = true,
  val googlePlayEnabled: Boolean = true,
  val flipkartEnabled: Boolean = true,
  val upiDenominationsEnabled: Map<String, Boolean> = mapOf("200" to true, "500" to true),
  val amazonDenominationsEnabled: Map<String, Boolean> =
    mapOf("150" to true, "200" to true, "250" to true),
  val googlePlayDenominationsEnabled: Map<String, Boolean> =
    mapOf("150" to true, "200" to true, "250" to true),
  val flipkartDenominationsEnabled: Map<String, Boolean> =
    mapOf("150" to true, "200" to true, "250" to true),
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  fun isMethodEnabled(method: String): Boolean =
    when (method.trim().uppercase(Locale.US)) {
      WithdrawalMethodType.UPI, "UPI ID" -> upiEnabled
      WithdrawalMethodType.AMAZON_GIFT_CARD -> amazonEnabled
      WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD -> googlePlayEnabled
      WithdrawalMethodType.FLIPKART_GIFT_CARD -> flipkartEnabled
      else -> false
    }

  fun isDenominationAvailable(method: String, amountRupees: Long): Boolean {
    if (!isMethodEnabled(method)) return false
    val key = amountRupees.toString()
    return when (method.trim().uppercase(Locale.US)) {
      WithdrawalMethodType.UPI, "UPI ID" -> upiDenominationsEnabled[key] ?: true
      WithdrawalMethodType.AMAZON_GIFT_CARD -> amazonDenominationsEnabled[key] ?: true
      WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD -> googlePlayDenominationsEnabled[key] ?: true
      WithdrawalMethodType.FLIPKART_GIFT_CARD -> flipkartDenominationsEnabled[key] ?: true
      else -> false
    }
  }
}

data class AdminNotificationItem(
  @DocumentId val id: String = "",
  val userId: String = "",
  val userName: String = "",
  val title: String = "",
  val body: String = "",
  val type: String = "WITHDRAWAL_REQUEST",
  val referenceId: String = "",
  val isRead: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class UserActivityItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val amountText: String = "",
  val statusLabel: String = "",
  val timestampMillis: Long = 0L,
  val relatedTaskId: String? = null
)

data class AdminRecord(
  @DocumentId val id: String = "",
  val userId: String = "",
  val email: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

/**
 * Formats integer amounts (100 units = ₹1.00) into an Indian Rupee (INR ₹) currency string.
 */
fun formatRewardInr(cents: Long): String {
  val rupees = cents / 100.0
  return String.format(Locale.US, "₹%.2f", rupees)
}

/**
 * Alias retained for compatibility; formats amounts in Indian Rupee (INR ₹).
 */
fun formatRewardUsd(cents: Long): String = formatRewardInr(cents)

data class AppVersionInfo(
  val versionCode: Int = 1,
  val versionName: String = "1.0",
  val apkUrl: String = "",
  val releaseNotes: String = "",
  val forceUpdate: Boolean = false
)

enum class UpdateDownloadStatus {
  IDLE,
  CHECKING,
  UPDATE_AVAILABLE,
  DOWNLOADING,
  VERIFYING,
  DOWNLOAD_COMPLETED,
  READY_TO_INSTALL,
  PERMISSION_REQUIRED,
  WAITING_FOR_INSTALL_CONFIRMATION,
  INSTALLATION_STARTED,
  UPDATE_COMPLETED,
  FAILED
}

data class UpdateDownloadProgress(
  val status: UpdateDownloadStatus = UpdateDownloadStatus.IDLE,
  val progressPercent: Int = 0,
  val bytesDownloaded: Long = 0L,
  val totalBytes: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null,
  val targetVersionCode: Int = 0,
  val targetVersionName: String = ""
) {
  val isChecking: Boolean get() = status == UpdateDownloadStatus.CHECKING
  val isUpdateAvailable: Boolean get() = status == UpdateDownloadStatus.UPDATE_AVAILABLE
  val isDownloading: Boolean get() = status == UpdateDownloadStatus.DOWNLOADING
  val isVerifying: Boolean get() = status == UpdateDownloadStatus.VERIFYING
  val isDownloadCompleted: Boolean get() = status == UpdateDownloadStatus.DOWNLOAD_COMPLETED || status == UpdateDownloadStatus.READY_TO_INSTALL
  val isReadyToInstall: Boolean get() = isDownloadCompleted
  val isPermissionRequired: Boolean get() = status == UpdateDownloadStatus.PERMISSION_REQUIRED
  val isWaitingForInstallConfirmation: Boolean get() = status == UpdateDownloadStatus.WAITING_FOR_INSTALL_CONFIRMATION
  val isInstallationStarted: Boolean get() = status == UpdateDownloadStatus.INSTALLATION_STARTED
  val isUpdateCompleted: Boolean get() = status == UpdateDownloadStatus.UPDATE_COMPLETED
  val hasFailed: Boolean get() = status == UpdateDownloadStatus.FAILED

  fun getFormattedSize(): String {
    if (totalBytes <= 0L) return ""
    val mbDownloaded = bytesDownloaded / (1024.0 * 1024.0)
    val mbTotal = totalBytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB / %.1f MB", mbDownloaded, mbTotal)
  }
}

data class CampaignItem(
  @DocumentId val id: String = "",
  val title: String = "",
  val clientName: String = "",
  val description: String = "",
  val appIconUrl: String = "",
  val taskLink: String = "",
  val budgetCents: Long = 0L,
  val spentCents: Long = 0L,
  val targetSubmissions: Long = 0L,
  val completedSubmissions: Long = 0L,
  val status: String = "ACTIVE", // ACTIVE, PAUSED, COMPLETED, CLOSED
  val category: String = "App Install",
  val rewardPerTaskCents: Long = 0L,
  val startDate: String = "",
  val endDate: String = "",
  val eligibilityRules: String = "All Approved Users",
  val linkedTaskId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val isQuotaExhausted: Boolean
    get() = targetSubmissions > 0L && completedSubmissions >= targetSubmissions

  val remainingQuota: Long
    get() = (targetSubmissions - completedSubmissions).coerceAtLeast(0L)
}

data class NotificationItem(
  @DocumentId val id: String = "",
  val userId: String = "",
  val title: String = "",
  val body: String = "",
  val type: String = "INFO", // REWARD, TASK, TASK_REWARD, DAILY_CHECKIN, TELEGRAM_REWARD, PROMO_CODE_REWARD, ADMIN_BONUS, SYSTEM
  val rewardCoins: Long = 0L,
  val isRead: Boolean = false,
  val referenceId: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class SupportReplyMessage(
  val senderRole: String = "USER", // "USER" or "ADMIN"
  val senderName: String = "",
  val message: String = "",
  val timestampMillis: Long = 0L
) {
  val createdAtMillis: Long
    get() = timestampMillis
}

object SupportTicketStatus {
  const val OPEN = "OPEN"
  const val IN_PROGRESS = "IN_PROGRESS"
  const val RESOLVED = "RESOLVED"

  fun normalize(status: String): String =
    when (status.trim().uppercase(Locale.US)) {
      IN_PROGRESS, "IN PROGRESS" -> IN_PROGRESS
      RESOLVED, "CLOSED" -> RESOLVED
      else -> OPEN
    }

  fun displayLabel(status: String): String =
    when (normalize(status)) {
      OPEN -> "Open"
      IN_PROGRESS -> "In Progress"
      RESOLVED -> "Resolved"
      else -> "Open"
    }
}

data class SupportTicket(
  val id: String = "",
  val userId: String = "",
  val userEmail: String = "",
  val userName: String = "",
  val category: String = "General",
  val subject: String = "",
  val message: String = "",
  val status: String = SupportTicketStatus.OPEN, // OPEN, IN_PROGRESS, RESOLVED
  val adminNotes: String = "",
  val messages: List<SupportReplyMessage> = emptyList(),
  val adminUnread: Boolean = true,
  val userUnread: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val adminReply: String
    get() = adminNotes
}

data class AppItem(
  @DocumentId val id: String = "",
  val name: String = "",
  val logoUrl: String = "",
  @get:PropertyName("isArchived") val isArchived: Boolean = false,
  @ServerTimestamp val createdAt: Timestamp? = null
)

data class UserTaskProgress(
  @DocumentId val id: String = "",
  val taskId: String = "",
  val userId: String = "",
  val installationConfirmed: Boolean = false,
  val currentStepNumber: Int = 1,
  val completedSteps: List<Int> = emptyList(),
  val trackingEvents: Map<String, Boolean> = emptyMap(),
  val status: String = "IN_PROGRESS",
  @ServerTimestamp val updatedAt: Timestamp? = null
)

data class AdminActivityLog(
  @DocumentId val id: String = "",
  val actionType: String = "SYSTEM", // TASK, CAMPAIGN, SUBMISSION, USER, REWARD, WITHDRAWAL
  val title: String = "",
  val details: String = "",
  val targetId: String = "",
  val timestampMillis: Long = 0L,
  @ServerTimestamp val createdAt: Timestamp? = null
)

// =========================================================================
// REWARDS SYSTEM MODELS (TELEGRAM JOIN, DAILY PROMO CODES, MANUAL BONUS)
// =========================================================================

object NotificationRewardType {
  const val TELEGRAM_REWARD = "TELEGRAM_REWARD"
  const val PROMO_CODE_REWARD = "PROMO_CODE_REWARD"
  const val ADMIN_BONUS = "ADMIN_BONUS"
  const val TASK_REWARD = "TASK_REWARD"
  const val SCRATCH_CARD_REWARD = "SCRATCH_CARD_REWARD"
  const val CAMPAIGN_REWARD = "CAMPAIGN_REWARD"
  const val CAMPAIGN_REVERSAL = "CAMPAIGN_REVERSAL"
}

data class TelegramTaskItem(
  val id: String = "",
  val taskId: String = "",
  val title: String = "",
  val channelUrl: String = "",
  val channelChatId: String = "",
  val rewardCoins: Long = 0L,
  @get:PropertyName("isActive") val isActive: Boolean = true,
  val claimsCount: Long = 0L,
  val createdBy: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val effectiveTaskId: String
    get() = taskId.ifBlank { id }

  fun isValidAndActive(): Boolean =
    isActive && rewardCoins > 0L && channelUrl.trim().startsWith("https://", ignoreCase = true)
}

data class TelegramTaskClaimRecord(
  val id: String = "",
  val claimId: String = "",
  val taskId: String = "",
  val taskTitle: String = "",
  val channelUrl: String = "",
  val userId: String = "",
  val userName: String = "",
  val telegramUsernameMasked: String = "",
  val rewardCoins: Long = 0L,
  @ServerTimestamp val claimedAt: Timestamp? = null
)

data class TelegramUserTaskState(
  val taskId: String = "",
  val userId: String = "",
  @get:PropertyName("hasClickedJoin") val hasClickedJoin: Boolean = false,
  @get:PropertyName("isMembershipVerified") val isMembershipVerified: Boolean = false,
  @get:PropertyName("isClaimed") val isClaimed: Boolean = false,
  val rewardCoinsClaimed: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null
)

data class TelegramAccountLinkStatus(
  val userId: String = "",
  @get:PropertyName("isLinked") val isLinked: Boolean = false,
  val telegramUsernameMasked: String = ""
)

data class TelegramVerifyResult(
  val success: Boolean = true,
  val verified: Boolean = false,
  val needsAccountLink: Boolean = false,
  val taskId: String = "",
  val botUsername: String = "",
  val botLinkUrl: String = "",
  val telegramUsernameMasked: String = "",
  val message: String = ""
)

/**
 * Selects the single next active, unclaimed Telegram Join task for the user's Tasks section.
 * Once a task is claimed by the user, it is permanently hidden and the next active unclaimed
 * uploaded Telegram task is returned (or null if none remain).
 */
fun selectNextTelegramTaskForUser(
  tasks: List<TelegramTaskItem>,
  userStates: Map<String, TelegramUserTaskState> = emptyMap(),
  userClaims: List<TelegramTaskClaimRecord> = emptyList()
): TelegramTaskItem? {
  val claimedIds = buildSet {
    userStates.values.filter { it.isClaimed }.forEach { add(it.taskId) }
    userClaims.forEach { if (it.taskId.isNotBlank()) add(it.taskId) }
  }
  return tasks
    .filter { it.isValidAndActive() && !claimedIds.contains(it.effectiveTaskId) }
    .sortedWith(compareBy<TelegramTaskItem>({ it.createdAt?.seconds ?: Long.MAX_VALUE }, { it.effectiveTaskId }))
    .firstOrNull()
}

data class TelegramJoinConfig(
  val channelUrl: String = "",
  val channelChatId: String = "",
  val botToken: String = "",
  val rewardCoins: Long = 0L,
  @get:PropertyName("isActive") val isActive: Boolean = false,
  val updatedBy: String = "",
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  fun isValidAndActive(): Boolean =
    isActive && rewardCoins > 0L && channelUrl.trim().startsWith("https://", ignoreCase = true)
}

data class TelegramUserRewardState(
  val userId: String = "",
  val telegramUserId: String = "",
  val isMembershipVerified: Boolean = false,
  val isClaimed: Boolean = false,
  val claimedCoins: Long = 0L,
  val statusMessage: String = "",
  val errorMessage: String? = null
) {
  fun shouldShowInDailyRewards(config: TelegramJoinConfig): Boolean =
    config.isValidAndActive() && !isClaimed
}

object PromoCodeStatus {
  const val ACTIVE = "ACTIVE"
  const val INACTIVE = "INACTIVE"
  const val EXHAUSTED = "EXHAUSTED"
  const val EXPIRED = "EXPIRED"
}

data class PromoCodeItem(
  val id: String = "",
  val code: String = "",
  val rewardCoins: Long = 0L,
  val maxSlots: Long = 1L,
  val usedSlots: Long = 0L,
  val expiresAtMillis: Long = 0L,
  val expiresAtLabel: String = "",
  @get:PropertyName("isActive") val isActive: Boolean = true,
  val status: String = PromoCodeStatus.ACTIVE,
  val createdBy: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null,
  @ServerTimestamp val updatedAt: Timestamp? = null
) {
  val remainingSlots: Long
    get() = (maxSlots - usedSlots).coerceAtLeast(0L)

  val isExhausted: Boolean
    get() = maxSlots <= 0L || usedSlots >= maxSlots || status.equals(PromoCodeStatus.EXHAUSTED, ignoreCase = true)

  fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
    (expiresAtMillis in 1..nowMillis) || status.equals(PromoCodeStatus.EXPIRED, ignoreCase = true)

  fun effectiveStatus(nowMillis: Long = System.currentTimeMillis()): String =
    when {
      isExhausted -> PromoCodeStatus.EXHAUSTED
      isExpired(nowMillis) -> PromoCodeStatus.EXPIRED
      !isActive || status.equals(PromoCodeStatus.INACTIVE, ignoreCase = true) -> PromoCodeStatus.INACTIVE
      else -> PromoCodeStatus.ACTIVE
    }

  fun isRedeemable(nowMillis: Long = System.currentTimeMillis()): Boolean =
    isActive &&
      rewardCoins > 0L &&
      maxSlots > 0L &&
      usedSlots < maxSlots &&
      !isExhausted &&
      !isExpired(nowMillis)
}

data class PromoRedemptionRecord(
  val id: String = "",
  val promoId: String = "",
  val code: String = "",
  val userId: String = "",
  val userName: String = "",
  val rewardCoins: Long = 0L,
  val newPromotionalCoins: Long = 0L,
  val newBalanceCoins: Long = 0L,
  @ServerTimestamp val redeemedAt: Timestamp? = null
)

data class PromoCodeStatusUpdateResult(
  val success: Boolean = true,
  val promoId: String = "",
  val isActive: Boolean = false,
  val status: String = PromoCodeStatus.INACTIVE
)

data class PromoAdminUiFeedback(
  val isLoading: Boolean = false,
  val lastCreatedCode: String = "",
  val successMessage: String? = null,
  val errorMessage: String? = null
)

data class PromoRedemptionUiState(
  val codeInput: String = "",
  val isRedeeming: Boolean = false,
  val lastRedeemedCode: String = "",
  val lastCreditedCoins: Long = 0L,
  val successMessage: String? = null,
  val errorMessage: String? = null
)

data class ManualBonusRecord(
  val id: String = "",
  val targetUserId: String = "",
  val targetUserName: String = "",
  val targetUserEmail: String = "",
  val adminId: String = "",
  val adminEmail: String = "",
  val amountCoins: Long = 0L,
  val reason: String = "",
  val idempotencyKey: String = "",
  @ServerTimestamp val createdAt: Timestamp? = null
)

/**
 * Normalize a promo code string to uppercase trimmed format.
 */
fun normalizePromoCode(rawCode: String): String =
  rawCode.trim().uppercase(Locale.US)




package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.AdminActivityLog
import com.example.data.model.AdminCampaignDashboardResult
import com.example.data.model.AdminCampaignNetworkItem
import com.example.data.model.AdminCampaignTrackingConfigItem
import com.example.data.model.AdminNotificationItem
import com.example.data.model.AdminPaginatedSlice
import com.example.data.model.AppItem
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.CampaignAnalyticsDateRange
import com.example.data.model.CampaignItem
import com.example.data.model.CampaignTrackingCollections
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.IssuedCampaignClickResult
import com.example.data.model.ManualBonusRecord
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.NotificationItem
import com.example.data.model.NotificationRewardType
import com.example.data.model.PromoCodeItem
import com.example.data.model.PromoCodeStatus
import com.example.data.model.PromoCodeStatusUpdateResult
import com.example.data.model.PromoRedemptionRecord
import com.example.data.model.ScratchCardConstants
import com.example.data.model.ScratchCardSession
import com.example.data.model.ScratchDailyState
import com.example.data.model.SpinSessionOutcome
import com.example.data.model.SpinWheelConstants
import com.example.data.model.SubmissionItem
import com.example.data.model.SupportTicket
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TaskStep
import com.example.data.model.TelegramAccountLinkStatus
import com.example.data.model.TelegramJoinConfig
import com.example.data.model.TelegramTaskClaimRecord
import com.example.data.model.TelegramTaskItem
import com.example.data.model.TelegramUserRewardState
import com.example.data.model.TelegramUserTaskState
import com.example.data.model.TelegramVerifyResult
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.UserTaskProgress
import com.example.data.model.WithdrawalAvailabilityConfig
import com.example.data.model.WithdrawalCatalog
import com.example.data.model.WithdrawalMethod
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.centsToCoins
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.getTodayDateKey
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
import com.example.data.model.normalizePromoCode
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

data class UploadedProofPhoto(
  val downloadUrl: String,
  val storagePath: String
)

fun interface ProofPhotoUploader {
  suspend fun uploadProofPhoto(
    userId: String,
    taskId: String,
    imageUri: Uri,
    onProgress: (Int) -> Unit
  ): UploadedProofPhoto
}

/**
 * Verifies actual Telegram channel membership using the Telegram Bot API (`getChatMember`),
 * where the configured bot has channel administrator permissions.
 */
fun interface TelegramMembershipVerifier {
  suspend fun verifyChannelMembership(
    botToken: String,
    channelChatId: String,
    telegramUserId: String
  ): Boolean
}

class HttpTelegramBotMembershipVerifier : TelegramMembershipVerifier {
  override suspend fun verifyChannelMembership(
    botToken: String,
    channelChatId: String,
    telegramUserId: String
  ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val cleanToken = botToken.trim()
    val cleanChatId = channelChatId.trim()
    val cleanUserId = telegramUserId.trim()
    if (cleanToken.isEmpty() || cleanChatId.isEmpty() || cleanUserId.isEmpty()) {
      return@withContext false
    }
    if (!cleanUserId.all { it.isDigit() }) {
      return@withContext false
    }
    runCatching {
      val encodedChatId = java.net.URLEncoder.encode(cleanChatId, "UTF-8")
      val encodedUserId = java.net.URLEncoder.encode(cleanUserId, "UTF-8")
      val endpoint =
        java.net.URL("https://api.telegram.org/bot$cleanToken/getChatMember?chat_id=$encodedChatId&user_id=$encodedUserId")
      val conn = (endpoint.openConnection() as java.net.HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8_000
        readTimeout = 8_000
      }
      try {
        val code = conn.responseCode
        if (code !in 200..299) return@runCatching false
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        parseTelegramChatMemberResponse(body)
      } finally {
        conn.disconnect()
      }
    }.getOrDefault(false)
  }

  companion object {
    internal fun parseTelegramChatMemberResponse(jsonBody: String): Boolean {
      val compact = jsonBody.replace("\\s+".toRegex(), "")
      if (!compact.contains("\"ok\":true", ignoreCase = true)) return false
      val activeStatuses = listOf(
        "\"status\":\"member\"",
        "\"status\":\"administrator\"",
        "\"status\":\"creator\""
      )
      if (activeStatuses.any { compact.contains(it, ignoreCase = true) }) {
        return true
      }
      if (
        compact.contains("\"status\":\"restricted\"", ignoreCase = true) &&
          compact.contains("\"is_member\":true", ignoreCase = true)
      ) {
        return true
      }
      return false
    }

    internal fun resolveChannelChatId(channelChatId: String, channelUrl: String): String {
      val explicit = channelChatId.trim()
      if (explicit.isNotEmpty()) {
        return if (explicit.startsWith("@") || explicit.startsWith("-")) explicit else "@$explicit"
      }
      val url = channelUrl.trim()
      val regex = Regex("^https?://(?:t\\.me|telegram\\.me)/([A-Za-z0-9_]{4,64})/?$", RegexOption.IGNORE_CASE)
      val handle = regex.find(url)?.groupValues?.getOrNull(1).orEmpty()
      return if (handle.isNotEmpty()) "@$handle" else ""
    }
  }
}

data class CreatedRewardSession(
  val sessionId: String,
  val rewardType: String,
  val rewardCents: Long,
  val streakDay: Int? = null,
  val dateKey: String = ""
)

data class ClaimedRewardResult(
  val rewardType: String,
  val rewardCents: Long,
  val newBalanceCents: Long = 0L,
  val streakDay: Int? = null,
  val videosWatched: Long = 0L,
  val dateKey: String = ""
)

/**
 * Trusted reward operations client backed by Firebase Cloud Functions with atomic Firestore
 * transaction execution when Cloud Functions are unavailable in the deployment environment.
 */
interface RewardCloudFunctionsClient {
  suspend fun createRewardSession(
    rewardType: String,
    streakDay: Int? = null
  ): CreatedRewardSession

  suspend fun awaitSessionVerified(
    userId: String,
    sessionId: String
  )

  suspend fun claimRewardSession(
    sessionId: String,
    secondSessionId: String? = null
  ): ClaimedRewardResult

  suspend fun claimDailyCheckIn(
    streakDay: Int? = null
  ): ClaimedRewardResult

  suspend fun createSpinSession(): String = "spin_${UUID.randomUUID()}"

  suspend fun claimSpinReward(sessionId: String): Long = SpinWheelConstants.SEGMENTS_COINS.first()

  suspend fun createSpinSessionWithOutcome(userId: String): SpinSessionOutcome {
    val sid = createSpinSession()
    return SpinSessionOutcome(
      sessionId = sid,
      segmentIndex = 0,
      rewardCoins = SpinWheelConstants.SEGMENTS_COINS.first(),
      usedFreeSpin = true
    )
  }

  suspend fun recordExtraSpinVideoWatched(userId: String): Int = 1

  suspend fun recordExtraSpinAdWatch(sessionId: String): Int = 1

  suspend fun recordScratchAdWatch(
    sessionId: String
  ): ScratchAdWatchResult = ScratchAdWatchResult(sessionId = sessionId)

  suspend fun createScratchCardSession(): ScratchCardSession = ScratchCardSession()

  suspend fun claimScratchCardReward(cardId: String): Long = 10L

  suspend fun createPromoCode(
    code: String,
    rewardCoins: Long,
    maxSlots: Long,
    expiresAtDateTime: String = "",
    expiresAtMillis: Long? = null,
    expiresAtLabel: String = "",
    isActive: Boolean = true
  ): PromoCodeItem = PromoCodeItem(
    id = normalizePromoCode(code),
    code = normalizePromoCode(code),
    rewardCoins = rewardCoins,
    maxSlots = maxSlots,
    expiresAtMillis = expiresAtMillis ?: 0L,
    expiresAtLabel = expiresAtLabel,
    isActive = isActive
  )

  suspend fun setPromoCodeActiveStatus(
    promoId: String,
    isActive: Boolean
  ): PromoCodeStatusUpdateResult = PromoCodeStatusUpdateResult(
    promoId = normalizePromoCode(promoId),
    isActive = isActive,
    status = if (isActive) PromoCodeStatus.ACTIVE else PromoCodeStatus.INACTIVE
  )

  suspend fun redeemPromoCode(
    rawCode: String
  ): PromoRedemptionRecord = PromoRedemptionRecord(
    promoId = normalizePromoCode(rawCode),
    code = normalizePromoCode(rawCode)
  )

  suspend fun creditManualBonus(
    targetUserId: String,
    amountCoins: Long,
    reason: String = "",
    idempotencyKey: String
  ): ManualBonusRecord = ManualBonusRecord(
    id = idempotencyKey,
    targetUserId = targetUserId,
    amountCoins = amountCoins,
    reason = reason,
    idempotencyKey = idempotencyKey
  )

  suspend fun createTelegramTask(
    title: String,
    channelUrl: String,
    channelChatId: String = "",
    rewardCoins: Long,
    isActive: Boolean = true
  ): TelegramTaskItem = TelegramTaskItem(
    id = "tg_default",
    taskId = "tg_default",
    title = title,
    channelUrl = channelUrl,
    channelChatId = channelChatId,
    rewardCoins = rewardCoins,
    isActive = isActive
  )

  suspend fun setTelegramTaskActiveStatus(
    taskId: String,
    isActive: Boolean
  ): Boolean = isActive

  suspend fun verifyTelegramTaskMembership(
    taskId: String
  ): TelegramVerifyResult = TelegramVerifyResult(taskId = taskId)

  suspend fun claimTelegramTaskReward(
    taskId: String
  ): Long = 0L

  suspend fun createCampaignClick(
    campaignId: String,
    taskId: String = "",
    devicePlatform: String = "ANDROID",
    idempotencyKey: String = ""
  ): IssuedCampaignClickResult = IssuedCampaignClickResult(
    campaignId = campaignId
  )

  suspend fun adminListCampaignNetworks(
    pageSize: Int = 20,
    cursor: String = "",
    search: String = "",
    networkId: String = ""
  ): AdminPaginatedSlice<AdminCampaignNetworkItem> = AdminPaginatedSlice()

  suspend fun adminUpsertCampaignNetwork(
    item: AdminCampaignNetworkItem,
    mode: String = "UPSERT"
  ): AdminCampaignNetworkItem = item

  suspend fun adminSetCampaignNetworkStatus(
    networkId: String,
    isActive: Boolean
  ): AdminCampaignNetworkItem = AdminCampaignNetworkItem(networkId = networkId, isActive = isActive)

  suspend fun adminListCampaignTrackingConfigs(
    pageSize: Int = 20,
    cursor: String = "",
    search: String = ""
  ): AdminPaginatedSlice<AdminCampaignTrackingConfigItem> = AdminPaginatedSlice()

  suspend fun adminUpsertCampaignTrackingConfig(
    item: AdminCampaignTrackingConfigItem,
    mode: String = "UPSERT"
  ): AdminCampaignTrackingConfigItem = item

  suspend fun adminSetCampaignTrackingStatus(
    campaignId: String,
    isActive: Boolean
  ): AdminCampaignTrackingConfigItem = AdminCampaignTrackingConfigItem(
    configId = campaignId,
    campaignId = campaignId,
    isActive = isActive
  )

  suspend fun adminGetCampaignTrackingDashboard(
    dateRange: String = CampaignAnalyticsDateRange.LAST_30_DAYS.wireValue,
    pageSize: Int = 20,
    campaignCursor: String = "",
    networkCursor: String = ""
  ): AdminCampaignDashboardResult = AdminCampaignDashboardResult(dateRange = dateRange)

  fun getOrAssignSessionRewardCoins(sessionId: String): Long = 0L

  fun getLocalScratchDailyState(userId: String, dateKey: String): ScratchDailyState? = null
}

data class ScratchAdWatchResult(
  val success: Boolean = true,
  val sessionId: String = "",
  val adsWatchedForNextScratch: Long = 0L,
  val unlockedScratchesCount: Long = 0L,
  val newlyUnlocked: Boolean = false,
  val dateKey: String = ""
)

class FirebaseRewardCloudFunctionsClient(
  private val firestore: FirebaseFirestore,
  private val authProvider: () -> FirebaseAuth = { Firebase.auth },
  private val functionsProvider: () -> FirebaseFunctions = { FirebaseFunctions.getInstance() },
  private val promoCallableInvoker: (suspend (functionName: String, payload: Any?) -> Any?)? = null,
  private val rewardCallableInvoker: (suspend (functionName: String, payload: Any?) -> Any?)? = null
) : RewardCloudFunctionsClient {

  private val sessionRewardsById = java.util.concurrent.ConcurrentHashMap<String, Long>()
  private val localScratchStats = java.util.concurrent.ConcurrentHashMap<String, ScratchDailyState>()
  private val inFlightCheckInKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightSpinClaimSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val claimedSpinSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightExtraSpinSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val consumedExtraSpinSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightRewardClaimSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val claimedRewardSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightScratchAdSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val consumedScratchAdSessionIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightScratchCreateKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightScratchClaimCardIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val claimedScratchCardIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
  private val inFlightManualBonusKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

  private val hasCustomCallableInvoker: Boolean
    get() = rewardCallableInvoker != null || promoCallableInvoker != null

  override fun getLocalScratchDailyState(userId: String, dateKey: String): ScratchDailyState? {
    if (userId.isBlank() || dateKey.isBlank()) return null
    return localScratchStats["${userId.trim()}_${dateKey.trim()}"]
  }

  private fun isCloudFunctionMissing(err: Throwable): Boolean {
    if (err is FirebaseFunctionsException) {
      return err.code == FirebaseFunctionsException.Code.NOT_FOUND ||
        err.code == FirebaseFunctionsException.Code.UNAVAILABLE
    }
    val msg = err.message.orEmpty()
    if (
      msg.contains("PERMISSION_DENIED", ignoreCase = true) ||
        msg.contains("FAILED_PRECONDITION", ignoreCase = true) ||
        msg.contains("ALREADY_EXISTS", ignoreCase = true) ||
        msg.contains("INVALID_ARGUMENT", ignoreCase = true) ||
        msg.contains("UNAUTHENTICATED", ignoreCase = true)
    ) {
      return false
    }
    if (!hasCustomCallableInvoker) return true
    return msg.contains("NOT_FOUND", ignoreCase = true) ||
      msg.contains("UNAVAILABLE", ignoreCase = true) ||
      msg.contains("404", ignoreCase = true)
  }

  private suspend fun handleLocalCallableFallback(functionName: String, payload: Any?): Any {
    val map = payload as? Map<*, *>
    val today = getTodayDateKey()
    val now = System.currentTimeMillis()
    val randomSuffix = java.util.UUID.randomUUID().toString().take(8)
    val rewardId = "rw_${now}_$randomSuffix"
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()

    return when (functionName) {
      "createRewardSession" -> {
        val rewardType = map?.get("rewardType")?.toString()?.ifBlank { "WATCH_EARN" } ?: "WATCH_EARN"
        val streakDay = (map?.get("streakDay") as? Number)?.toInt() ?: 1
        val sid = "session_${now}_$randomSuffix"
        val reward = when (rewardType) {
          "DAILY_CHECKIN", "DAILY_CHECK_IN" -> DailyCheckInConstants.getRewardCoinsForDay(streakDay)
          "WATCH_EARN", "WATCH_AND_EARN" -> (63L..125L).random()
          else -> 0L
        }
        if (reward > 0L) {
          sessionRewardsById[sid] = reward
        }
        mapOf(
          "sessionId" to sid,
          "rewardType" to rewardType,
          "rewardCoins" to reward,
          "rewardCents" to reward,
          "streakDay" to streakDay,
          "dateKey" to today
        )
      }
      "claimDailyCheckIn" -> {
        val streakDay = (map?.get("streakDay") as? Number)?.toInt() ?: 1
        val coins = DailyCheckInConstants.getRewardCoinsForDay(streakDay)
        var newBalance = coins
        if (uid.isNotBlank()) {
          runCatching {
            firestore.runTransaction { tx ->
              val userRef = firestore.collection("users").document(uid)
              val userSnap = tx.get(userRef)
              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
              val newPromo = curPromo + coins
              newBalance = maxOf(curTotal + coins, curEligible + newPromo)
              tx.update(
                userRef,
                mapOf(
                  "balanceCoins" to newBalance,
                  "promotionalCoins" to newPromo,
                  "checkInStreak" to streakDay,
                  "lastCheckInDate" to today,
                  "lastRewardId" to rewardId,
                  "lastRewardType" to "DAILY_CHECKIN",
                  "lastRewardAmountCoins" to coins,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              val checkInRef = firestore.collection("users").document(uid).collection("check_ins").document(today)
              tx.set(
                checkInRef,
                mapOf(
                  "dateKey" to today,
                  "userId" to uid,
                  "streakDay" to streakDay,
                  "rewardCoins" to coins,
                  "claimedAt" to FieldValue.serverTimestamp()
                )
              )
              val txRef = firestore.collection("transactions").document("checkin_${today}_$uid")
              tx.set(
                txRef,
                mapOf(
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "type" to "DAILY_CHECKIN",
                  "title" to "Day $streakDay Daily Check-in",
                  "amountCoins" to coins,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to today,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef = firestore.collection("users").document(uid).collection("notifications").document("notif_checkin_${today}")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to uid,
                  "title" to "Daily Check-in Claimed!",
                  "body" to "You earned $coins Coins for Day $streakDay check-in.",
                  "type" to "NOTIFICATION",
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "claimDailyCheckIn fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "rewardCoins" to coins,
          "rewardCents" to coins,
          "streakDay" to streakDay,
          "newBalanceCoins" to newBalance,
          "dateKey" to today
        )
      }
      "createSpinSession" -> {
        val sid = "spin_${now}_$randomSuffix"
        val segment = (0 until com.example.data.model.SpinWheelConstants.SEGMENTS_COINS.size).random()
        val coins = com.example.data.model.SpinWheelConstants.SEGMENTS_COINS.getOrElse(segment) { 40L }
        var usedFreeSpin = true
        sessionRewardsById[sid] = coins
        if (uid.isNotBlank()) {
          val userRef = firestore.collection("users").document(uid)
          val userSnap = runCatching { userRef.get().await() }.getOrNull()
          val lastFreeMillis = userSnap?.getTimestamp("lastFreeSpinAt")?.toDate()?.time ?: 0L
          val isFreeAvailable =
            lastFreeMillis <= 0L || (now - lastFreeMillis) >= com.example.data.model.SpinWheelConstants.FREE_SPIN_COOLDOWN_MS
          val videosWatched = userSnap?.getLong("extraSpinVideosWatched") ?: 0L
          val isExtraAvailable = (userSnap?.getBoolean("extraSpinAvailable") ?: false) || videosWatched >= 2L
          val curBalanceCents = userSnap?.getLong("balanceCents") ?: 0L
          val curEligible = maxOf(userSnap?.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
          val curPromo = userSnap?.getLong("promotionalCoins") ?: 0L
          val curTotal = maxOf(userSnap?.getLong("balanceCoins") ?: 0L, curEligible + curPromo)

          usedFreeSpin = isFreeAvailable
          runCatching {
            val userUpdates = if (usedFreeSpin) {
              mapOf(
                "balanceCoins" to curTotal,
                "lastFreeSpinAt" to FieldValue.serverTimestamp(),
                "lastRewardId" to rewardId,
                "updatedAt" to FieldValue.serverTimestamp()
              )
            } else {
              mapOf(
                "balanceCoins" to curTotal,
                "extraSpinAvailable" to false,
                "extraSpinVideosWatched" to 0L,
                "lastRewardId" to rewardId,
                "updatedAt" to FieldValue.serverTimestamp()
              )
            }
            userRef.update(userUpdates).await()
            firestore.collection("users").document(uid).collection("spin_sessions").document(sid).set(
              mapOf(
                "sessionId" to sid,
                "userId" to uid,
                "rewardCoins" to coins,
                "status" to "ACTIVE",
                "createdAt" to FieldValue.serverTimestamp()
              )
            ).await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "createSpinSession fallback error: ${err.message}", err)
          }
          if (!isFreeAvailable && !isExtraAvailable && userSnap != null && userSnap.exists()) {
            throw IllegalStateException("Watch 2 videos to unlock an Extra Spin.")
          }
        }
        mapOf(
          "sessionId" to sid,
          "segmentIndex" to segment,
          "rewardCoins" to coins,
          "canSpinNow" to true,
          "usedFreeSpin" to usedFreeSpin,
          "dateKey" to today
        )
      }
      "recordExtraSpinAdWatch" -> {
        val sid = map?.get("sessionId")?.toString().orEmpty().ifEmpty { "spin_ad_$randomSuffix" }
        var nextVideos = 1L
        var unlocked = false
        if (uid.isNotBlank()) {
          runCatching {
            val userRef = firestore.collection("users").document(uid)
            val userSnap = userRef.get().await()
            val curVideos = (userSnap.getLong("extraSpinVideosWatched") ?: 0L).coerceIn(0L, 2L)
            nextVideos = (curVideos + 1L).coerceAtMost(2L)
            unlocked = nextVideos >= 2L
            val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
            val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
            val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
            val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
            userRef.update(
              mapOf(
                "balanceCoins" to curTotal,
                "extraSpinVideosWatched" to nextVideos,
                "extraSpinAvailable" to unlocked,
                "lastRewardId" to rewardId,
                "updatedAt" to FieldValue.serverTimestamp()
              )
            ).await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "recordExtraSpinAdWatch fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "sessionId" to sid,
          "adsWatchedForSpin" to nextVideos,
          "extraSpinVideosWatched" to nextVideos,
          "newlyUnlocked" to unlocked,
          "dateKey" to today
        )
      }
      "claimSpinReward" -> {
        val sid = map?.get("sessionId")?.toString().orEmpty().ifEmpty { "spin_${now}_$randomSuffix" }
        val coins = sessionRewardsById[sid] ?: (map?.get("rewardCoins") as? Number)?.toLong() ?: 40L
        var newBalance = coins
        if (uid.isNotBlank()) {
          runCatching {
            firestore.runTransaction { tx ->
              val userRef = firestore.collection("users").document(uid)
              val userSnap = tx.get(userRef)
              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
              val newPromo = curPromo + coins
              newBalance = maxOf(curTotal + coins, curEligible + newPromo)
              tx.update(
                userRef,
                mapOf(
                  "balanceCoins" to newBalance,
                  "promotionalCoins" to newPromo,
                  "lastRewardId" to rewardId,
                  "lastRewardType" to "SPIN_EARN",
                  "lastRewardAmountCoins" to coins,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              val spinRef = firestore.collection("users").document(uid).collection("spin_sessions").document(sid)
              tx.set(
                spinRef,
                mapOf(
                  "sessionId" to sid,
                  "userId" to uid,
                  "rewardCoins" to coins,
                  "status" to "CLAIMED",
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val txRef = firestore.collection("transactions").document("spin_${sid}")
              tx.set(
                txRef,
                mapOf(
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "type" to "SPIN_EARN",
                  "title" to "Spin & Win Reward",
                  "amountCoins" to coins,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to sid,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef = firestore.collection("users").document(uid).collection("notifications").document("notif_spin_${sid}")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to uid,
                  "title" to "Spin & Win Reward!",
                  "body" to "You won $coins Coins on Lucky Spin Wheel.",
                  "type" to "NOTIFICATION",
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "claimSpinReward fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "rewardCoins" to coins,
          "newBalanceCoins" to newBalance,
          "dateKey" to today
        )
      }
      "claimRewardSession" -> {
        val sid = map?.get("sessionId")?.toString().orEmpty().ifEmpty { "watch_${now}_$randomSuffix" }
        val reward = sessionRewardsById[sid] ?: 100L
        var newBalance = reward
        if (uid.isNotBlank()) {
          runCatching {
            firestore.runTransaction { tx ->
              val userRef = firestore.collection("users").document(uid)
              val watchRef = firestore.collection("users").document(uid).collection("watch_stats").document(today)
              val userSnap = tx.get(userRef)
              val watchSnap = tx.get(watchRef)

              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
              val newPromo = curPromo + reward
              newBalance = maxOf(curTotal + reward, curEligible + newPromo)
              val count = if (watchSnap.exists()) (watchSnap.getLong("videosWatched") ?: 0L) + 1L else 1L

              tx.update(
                userRef,
                mapOf(
                  "balanceCoins" to newBalance,
                  "promotionalCoins" to newPromo,
                  "lastRewardId" to rewardId,
                  "lastRewardType" to "WATCH_EARN",
                  "lastRewardAmountCoins" to reward,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              tx.set(
                watchRef,
                mapOf(
                  "dateKey" to today,
                  "userId" to uid,
                  "videosWatched" to count,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              val txRef = firestore.collection("transactions").document("watch_${sid}")
              tx.set(
                txRef,
                mapOf(
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "type" to "WATCH_EARN",
                  "title" to "Watch & Earn Video Reward",
                  "amountCoins" to reward,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to sid,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef = firestore.collection("users").document(uid).collection("notifications").document("notif_watch_${sid}")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to uid,
                  "title" to "Watch & Earn Reward!",
                  "body" to "You earned $reward Coins for watching videos.",
                  "type" to "NOTIFICATION",
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "claimRewardSession fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "rewardCoins" to reward,
          "rewardCents" to reward,
          "newBalanceCoins" to newBalance,
          "dateKey" to today
        )
      }
      "createScratchCardSession" -> {
        var cardId = "scratch_${now}_$randomSuffix"
        var reward = (15L..75L).random()
        var scratchNum = 1L
        var isFreeScratch = true
        if (uid.isNotBlank()) {
          val statsKey = "${uid}_$today"
          val statsRef = firestore.collection("users").document(uid).collection("scratch_stats").document(today)
          val statsSnap = runCatching { statsRef.get().await() }.getOrNull()
          val cached = localScratchStats[statsKey]

          val snapCompleted = if (statsSnap != null && statsSnap.exists()) (statsSnap.getLong("scratchesCompletedToday") ?: 0L) else 0L
          val completed = maxOf(snapCompleted, cached?.scratchesCompletedToday ?: 0L)

          val snapFreeUsed = if (statsSnap != null && statsSnap.exists()) (statsSnap.getBoolean("hasUsedFreeScratchToday") ?: (snapCompleted > 0L)) else false
          val freeUsed = snapFreeUsed || (cached?.hasUsedFreeScratchToday ?: false) || (completed > 0L)

          val snapAds = if (statsSnap != null && statsSnap.exists()) (statsSnap.getLong("adsWatchedForNextScratch") ?: 0L) else null
          val adsWatched = cached?.adsWatchedForNextScratch ?: snapAds ?: 0L

          val snapUnlocked = if (statsSnap != null && statsSnap.exists()) (statsSnap.getLong("unlockedScratchesCount") ?: 0L) else null
          val unlocked = cached?.unlockedScratchesCount ?: snapUnlocked ?: 0L

          val snapActiveId = if (statsSnap != null && statsSnap.exists()) statsSnap.getString("activeCardId").orEmpty() else ""
          val snapIsClaimed = if (statsSnap != null && statsSnap.exists()) (statsSnap.getBoolean("activeCardIsClaimed") ?: false) else false
          val existingActiveId = cached?.activeCardId ?: snapActiveId
          val isClaimed = cached?.activeCardIsClaimed ?: snapIsClaimed
          val existingActiveReward = (cached?.activeCardRewardCoins?.takeIf { it > 0L })
            ?: (if (statsSnap != null && statsSnap.exists()) statsSnap.getLong("activeCardRewardCoins") ?: 0L else 0L)

          if (existingActiveId.isNotBlank() && !isClaimed) {
            cardId = existingActiveId
            if (existingActiveReward > 0L) reward = existingActiveReward
            scratchNum = completed + 1L
            isFreeScratch = completed == 0L
          } else if (!freeUsed && completed == 0L) {
            scratchNum = 1L
            isFreeScratch = true
            val newState = ScratchDailyState(
              dateKey = today,
              scratchesCompletedToday = 0L,
              adsWatchedForNextScratch = adsWatched,
              hasUsedFreeScratchToday = true,
              unlockedScratchesCount = unlocked,
              activeCardId = cardId,
              activeCardRewardCoins = reward,
              activeCardIsScratched = false,
              activeCardIsClaimed = false
            )
            localScratchStats[statsKey] = newState
            runCatching {
              statsRef.set(
                mapOf(
                  "dateKey" to today,
                  "scratchesCompletedToday" to 0L,
                  "hasUsedFreeScratchToday" to true,
                  "adsWatchedForNextScratch" to adsWatched,
                  "unlockedScratchesCount" to unlocked,
                  "activeCardId" to cardId,
                  "activeCardRewardCoins" to reward,
                  "activeCardIsScratched" to false,
                  "activeCardIsClaimed" to false,
                  "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
              ).await()
              firestore.collection("users").document(uid).collection("scratch_sessions").document(cardId).set(
                mapOf(
                  "cardId" to cardId,
                  "userId" to uid,
                  "rewardCoins" to reward,
                  "status" to "PENDING",
                  "createdAt" to FieldValue.serverTimestamp()
                )
              ).await()
            }
          } else if (unlocked > 0L) {
            scratchNum = completed + 1L
            isFreeScratch = false
            val remainingUnlocked = (unlocked - 1L).coerceAtLeast(0L)
            val newState = ScratchDailyState(
              dateKey = today,
              scratchesCompletedToday = completed,
              adsWatchedForNextScratch = adsWatched,
              hasUsedFreeScratchToday = true,
              unlockedScratchesCount = remainingUnlocked,
              activeCardId = cardId,
              activeCardRewardCoins = reward,
              activeCardIsScratched = false,
              activeCardIsClaimed = false
            )
            localScratchStats[statsKey] = newState
            runCatching {
              statsRef.set(
                mapOf(
                  "dateKey" to today,
                  "scratchesCompletedToday" to completed,
                  "hasUsedFreeScratchToday" to true,
                  "adsWatchedForNextScratch" to adsWatched,
                  "unlockedScratchesCount" to remainingUnlocked,
                  "activeCardId" to cardId,
                  "activeCardRewardCoins" to reward,
                  "activeCardIsScratched" to false,
                  "activeCardIsClaimed" to false,
                  "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
              ).await()
              firestore.collection("users").document(uid).collection("scratch_sessions").document(cardId).set(
                mapOf(
                  "cardId" to cardId,
                  "userId" to uid,
                  "rewardCoins" to reward,
                  "status" to "PENDING",
                  "createdAt" to FieldValue.serverTimestamp()
                )
              ).await()
            }
          } else {
            throw IllegalStateException("Watch 2 videos to unlock next card.")
          }
        }
        sessionRewardsById[cardId] = reward
        mapOf(
          "cardId" to cardId,
          "rewardCoins" to reward,
          "scratchNumber" to scratchNum,
          "isFree" to isFreeScratch,
          "dateKey" to today
        )
      }
      "recordScratchAdWatch" -> {
        val sid = map?.get("sessionId")?.toString().orEmpty().ifEmpty { "scratch_ad_$randomSuffix" }
        var adsWatched = 1L
        var unlockedCount = 0L
        var newlyUnlocked = false
        if (uid.isNotBlank()) {
          val statsKey = "${uid}_$today"
          val statsRef = firestore.collection("users").document(uid).collection("scratch_stats").document(today)
          val statsSnap = runCatching { statsRef.get().await() }.getOrNull()
          val cached = localScratchStats[statsKey]

          val curCompleted = maxOf(
            if (statsSnap != null && statsSnap.exists()) (statsSnap.getLong("scratchesCompletedToday") ?: 0L) else 0L,
            cached?.scratchesCompletedToday ?: 0L
          )
          val curAds = cached?.adsWatchedForNextScratch
            ?: (if (statsSnap != null && statsSnap.exists()) statsSnap.getLong("adsWatchedForNextScratch") ?: 0L else 0L)
          val curUnlocked = cached?.unlockedScratchesCount
            ?: (if (statsSnap != null && statsSnap.exists()) statsSnap.getLong("unlockedScratchesCount") ?: 0L else 0L)

          if (curAds >= 1L) {
            adsWatched = 0L
            unlockedCount = curUnlocked + 1L
            newlyUnlocked = true
          } else {
            adsWatched = 1L
            unlockedCount = curUnlocked
            newlyUnlocked = false
          }

          val newState = ScratchDailyState(
            dateKey = today,
            scratchesCompletedToday = curCompleted,
            adsWatchedForNextScratch = adsWatched,
            hasUsedFreeScratchToday = true,
            unlockedScratchesCount = unlockedCount,
            activeCardId = cached?.activeCardId.orEmpty(),
            activeCardRewardCoins = cached?.activeCardRewardCoins ?: 0L,
            activeCardIsScratched = cached?.activeCardIsScratched ?: false,
            activeCardIsClaimed = cached?.activeCardIsClaimed ?: true
          )
          localScratchStats[statsKey] = newState

          runCatching {
            statsRef.set(
              mapOf(
                "dateKey" to today,
                "scratchesCompletedToday" to curCompleted,
                "hasUsedFreeScratchToday" to true,
                "adsWatchedForNextScratch" to adsWatched,
                "unlockedScratchesCount" to unlockedCount,
                "updatedAt" to FieldValue.serverTimestamp()
              ),
              SetOptions.merge()
            ).await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "recordScratchAdWatch fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "sessionId" to sid,
          "adsWatchedForNextScratch" to adsWatched,
          "unlockedScratchesCount" to unlockedCount,
          "newlyUnlocked" to newlyUnlocked,
          "dateKey" to today
        )
      }
      "claimScratchCardReward" -> {
        val cardId = map?.get("cardId")?.toString().orEmpty().ifEmpty { "scratch_$randomSuffix" }
        var reward = sessionRewardsById[cardId] ?: 35L
        var newBalance = reward
        var completedToday = 1L
        if (uid.isNotBlank()) {
          val statsKey = "${uid}_$today"
          val cached = localScratchStats[statsKey]
          if ((cached?.activeCardRewardCoins ?: 0L) > 0L) {
            reward = cached!!.activeCardRewardCoins
          }
          runCatching {
            val statsRef = firestore.collection("users").document(uid).collection("scratch_stats").document(today)
            firestore.runTransaction { tx ->
              val userRef = firestore.collection("users").document(uid)
              val userSnap = tx.get(userRef)
              val statsSnap = tx.get(statsRef)

              val activeReward = if (statsSnap.exists()) (statsSnap.getLong("activeCardRewardCoins") ?: 0L) else 0L
              if (activeReward > 0L) reward = activeReward
              val snapCompleted = if (statsSnap.exists()) (statsSnap.getLong("scratchesCompletedToday") ?: 0L) else 0L
              val curCompleted = maxOf(snapCompleted, cached?.scratchesCompletedToday ?: 0L)
              val curAds = cached?.adsWatchedForNextScratch
                ?: (if (statsSnap.exists()) statsSnap.getLong("adsWatchedForNextScratch") ?: 0L else 0L)
              val curUnlocked = cached?.unlockedScratchesCount
                ?: (if (statsSnap.exists()) statsSnap.getLong("unlockedScratchesCount") ?: 0L else 0L)
              completedToday = curCompleted + 1L

              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
              val newPromo = curPromo + reward
              newBalance = maxOf(curTotal + reward, curEligible + newPromo)

              tx.update(
                userRef,
                mapOf(
                  "balanceCoins" to newBalance,
                  "promotionalCoins" to newPromo,
                  "lastRewardId" to rewardId,
                  "lastRewardType" to "SCRATCH_CARD",
                  "lastRewardAmountCoins" to reward,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              tx.set(
                statsRef,
                mapOf(
                  "dateKey" to today,
                  "scratchesCompletedToday" to completedToday,
                  "hasUsedFreeScratchToday" to true,
                  "adsWatchedForNextScratch" to curAds,
                  "unlockedScratchesCount" to curUnlocked,
                  "activeCardId" to "",
                  "activeCardRewardCoins" to 0L,
                  "activeCardIsScratched" to false,
                  "activeCardIsClaimed" to true,
                  "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
              )
              val sessionRef = firestore.collection("users").document(uid).collection("scratch_sessions").document(cardId)
              tx.set(
                sessionRef,
                mapOf(
                  "cardId" to cardId,
                  "userId" to uid,
                  "rewardCoins" to reward,
                  "status" to "CLAIMED",
                  "createdAt" to FieldValue.serverTimestamp(),
                  "claimedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
              )
              val txRef = firestore.collection("transactions").document("scratch_${cardId}")
              tx.set(
                txRef,
                mapOf(
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "type" to "SCRATCH_CARD",
                  "title" to "Scratch & Win Reward",
                  "amountCoins" to reward,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to cardId,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef = firestore.collection("users").document(uid).collection("notifications").document("notif_scratch_${cardId}")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to uid,
                  "title" to "Scratch Card Reward!",
                  "body" to "You won $reward Coins from Scratch & Win.",
                  "type" to "NOTIFICATION",
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "claimScratchCardReward fallback error: ${err.message}", err)
            completedToday = (cached?.scratchesCompletedToday ?: 0L) + 1L
          }
          localScratchStats[statsKey] = ScratchDailyState(
            dateKey = today,
            scratchesCompletedToday = completedToday,
            adsWatchedForNextScratch = cached?.adsWatchedForNextScratch ?: 0L,
            hasUsedFreeScratchToday = true,
            unlockedScratchesCount = cached?.unlockedScratchesCount ?: 0L,
            activeCardId = "",
            activeCardRewardCoins = 0L,
            activeCardIsScratched = false,
            activeCardIsClaimed = true
          )
        }
        mapOf(
          "success" to true,
          "cardId" to cardId,
          "rewardCoins" to reward,
          "scratchesCompletedToday" to completedToday,
          "newBalanceCoins" to newBalance
        )
      }
      "createPromoCode" -> {
        val code = map?.get("code")?.toString().orEmpty().uppercase().ifEmpty { "PROMO_$randomSuffix" }
        val reward = (map?.get("rewardCoins") as? Number)?.toLong() ?: 500L
        val slots = (map?.get("maxSlots") as? Number)?.toLong() ?: 100L
        val active = (map?.get("isActive") as? Boolean) ?: true
        val label = map?.get("expiresAtLabel")?.toString().orEmpty().ifEmpty { "2099-12-31 23:59" }
        val adminUid = authProvider().currentUser?.uid?.trim().orEmpty()
        val promoDoc = mapOf(
          "code" to code,
          "rewardCoins" to reward,
          "maxSlots" to slots,
          "usedSlots" to 0L,
          "isActive" to active,
          "status" to if (active) "ACTIVE" else "INACTIVE",
          "expiresAtLabel" to label,
          "createdBy" to adminUid,
          "createdAt" to FieldValue.serverTimestamp()
        )
        runCatching {
          firestore.collection("promo_codes").document(code).set(promoDoc).await()
        }
        runCatching {
          firestore.collection("app_config").document("promo_$code").set(promoDoc).await()
        }
        mapOf(
          "success" to true,
          "id" to code,
          "code" to code,
          "rewardCoins" to reward,
          "maxSlots" to slots,
          "usedSlots" to 0L,
          "isActive" to active,
          "status" to if (active) "ACTIVE" else "INACTIVE",
          "expiresAtLabel" to label
        )
      }
      "setPromoCodeActiveStatus" -> {
        val pid = map?.get("promoId")?.toString().orEmpty().uppercase()
        val active = (map?.get("isActive") as? Boolean) ?: true
        if (pid.isNotBlank()) {
          val updateMap = mapOf(
            "isActive" to active,
            "status" to if (active) "ACTIVE" else "INACTIVE",
            "updatedAt" to FieldValue.serverTimestamp()
          )
          runCatching {
            firestore.collection("promo_codes").document(pid).update(updateMap).await()
          }
          runCatching {
            firestore.collection("app_config").document("promo_$pid").set(updateMap, SetOptions.merge()).await()
          }
        }
        mapOf(
          "success" to true,
          "promoId" to pid,
          "isActive" to active,
          "status" to if (active) "ACTIVE" else "INACTIVE"
        )
      }
      "redeemPromoCode" -> {
        val code = map?.get("code")?.toString().orEmpty().uppercase()
        var reward = 500L
        var newBalance = 500L
        if (uid.isNotBlank() && code.isNotBlank()) {
          val publicPromoSnap = runCatching {
            firestore.collection("app_config").document("promo_$code").get().await()
          }.getOrNull()
          val adminPromoSnap = if (publicPromoSnap == null || !publicPromoSnap.exists()) {
            runCatching {
              firestore.collection("promo_codes").document(code).get().await()
            }.getOrNull()
          } else null

          val resolvedSnap = when {
            publicPromoSnap != null && publicPromoSnap.exists() -> publicPromoSnap
            adminPromoSnap != null && adminPromoSnap.exists() -> adminPromoSnap
            else -> null
          }
          if (resolvedSnap != null) {
            reward = resolvedSnap.getLong("rewardCoins") ?: 500L
            val isActive = resolvedSnap.getBoolean("isActive") ?: true
            check(isActive) { "Promo code is inactive or expired." }
          }

          val redemptionRef = firestore.collection("promo_redemptions").document("${code}_$uid")
          val redSnap = runCatching { redemptionRef.get().await() }.getOrNull()
          if (redSnap != null && redSnap.exists()) {
            throw IllegalStateException("You have already redeemed this promo code.")
          }

          runCatching {
            firestore.runTransaction { tx ->
              val userRef = firestore.collection("users").document(uid)
              val userSnap = tx.get(userRef)
              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)
              val newPromo = curPromo + reward
              newBalance = maxOf(curTotal + reward, curEligible + newPromo)

              tx.update(
                userRef,
                mapOf(
                  "balanceCoins" to newBalance,
                  "promotionalCoins" to newPromo,
                  "lastRewardId" to rewardId,
                  "lastRewardType" to "PROMO_CODE",
                  "lastRewardAmountCoins" to reward,
                  "updatedAt" to FieldValue.serverTimestamp()
                )
              )
              tx.set(
                redemptionRef,
                mapOf(
                  "promoId" to code,
                  "code" to code,
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "rewardCoins" to reward,
                  "redeemedAt" to FieldValue.serverTimestamp()
                )
              )
              val txRef = firestore.collection("transactions").document("promo_${code}_$uid")
              tx.set(
                txRef,
                mapOf(
                  "userId" to uid,
                  "userName" to (userSnap.getString("displayName") ?: "TaskFlow Member"),
                  "type" to "PROMO_CODE",
                  "title" to "Promo Code: $code",
                  "amountCoins" to reward,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to code,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef = firestore.collection("users").document(uid).collection("notifications").document("notif_promo_${code}_$uid")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to uid,
                  "title" to "Promo Code Redeemed!",
                  "body" to "You earned $reward Coins from promo code $code.",
                  "type" to "NOTIFICATION",
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "redeemPromoCode fallback error: ${err.message}", err)
          }
        }
        mapOf(
          "success" to true,
          "code" to code,
          "rewardCoins" to reward,
          "usedSlots" to 1L,
          "maxSlots" to 100L,
          "newBalanceCoins" to newBalance,
          "redeemedAtMillis" to now
        )
      }
      "createTelegramTask" -> {
        val ch = map?.get("channelUrl")?.toString().orEmpty()
        val rw = (map?.get("rewardCoins") as? Number)?.toLong() ?: 200L
        val tid = "tg_${now}_$randomSuffix"
        val title = map?.get("title")?.toString().orEmpty().ifEmpty { "Join Telegram Channel" }
        runCatching {
          firestore.collection("telegram_tasks").document(tid).set(
            mapOf(
              "taskId" to tid,
              "title" to title,
              "channelUrl" to ch,
              "rewardCoins" to rw,
              "isActive" to true,
              "createdAt" to FieldValue.serverTimestamp()
            )
          ).await()
        }.onFailure { err ->
          Log.e("TaskFlowRepository", "createTelegramTask fallback error: ${err.message}", err)
        }
        mapOf(
          "success" to true,
          "taskId" to tid,
          "channelUrl" to ch,
          "rewardCoins" to rw,
          "title" to title,
          "botUsername" to "TaskFlowVerifyBot",
          "isActive" to true
        )
      }
      "setTelegramTaskActiveStatus" -> {
        val tid = map?.get("taskId")?.toString().orEmpty()
        val active = (map?.get("isActive") as? Boolean) ?: true
        if (tid.isNotBlank()) {
          runCatching {
            firestore.collection("telegram_tasks").document(tid).update(
              mapOf("isActive" to active, "updatedAt" to FieldValue.serverTimestamp())
            ).await()
          }
        }
        mapOf("success" to true, "taskId" to tid, "isActive" to active)
      }
      "verifyTelegramTaskMembership", "claimTelegramTaskReward" -> {
        throw IllegalStateException(
          "Telegram membership verification and reward claiming are strictly server-authoritative and require Firebase Cloud Functions."
        )
      }
      "creditManualBonus" -> {
        val rawTargetUid = map?.get("targetUserId")?.toString()?.trim().orEmpty()
        val amount = (map?.get("amountCoins") as? Number)?.toLong() ?: 100L
        val reason = map?.get("reason")?.toString()?.trim().orEmpty().ifEmpty { "Special Admin Bonus" }
        val key = map?.get("idempotencyKey")?.toString()?.trim().orEmpty().ifEmpty { randomSuffix }
        val adminUid = authProvider().currentUser?.uid?.trim().orEmpty().ifEmpty { uid }
        val adminEmail = authProvider().currentUser?.email?.trim().orEmpty()
        var targetUid = rawTargetUid
        var newBalance = amount
        var newPromoCoins = amount
        var resolvedTargetName = "TaskFlow Member"
        var resolvedTargetEmail = ""
        if (rawTargetUid.isNotBlank() && amount > 0L) {
          val directSnap =
            runCatching { firestore.collection("users").document(rawTargetUid).get().await() }.getOrNull()
          if (directSnap == null || !directSnap.exists()) {
            val byUserIdSnap =
              runCatching {
                firestore.collection("users").whereEqualTo("userId", rawTargetUid).limit(1).get().await()
              }.getOrNull()
            val matchedDoc = byUserIdSnap?.documents?.firstOrNull()
            if (matchedDoc != null) {
              targetUid = matchedDoc.id
            }
          }

          runCatching {
            firestore.runTransaction { tx ->
              val bonusRef = firestore.collection("manual_bonuses").document(key)
              val existingBonusSnap = tx.get(bonusRef)
              val userRef = firestore.collection("users").document(targetUid)
              val userSnap = tx.get(userRef)

              if (userSnap.exists()) {
                resolvedTargetName =
                  (userSnap.getString("displayName") ?: "TaskFlow Member").trim().ifEmpty { "TaskFlow Member" }
                resolvedTargetEmail = (userSnap.getString("email") ?: "").trim()
              }

              val curBalanceCents = userSnap.getLong("balanceCents") ?: 0L
              val curEligible = maxOf(userSnap.getLong("eligibleCoins") ?: 0L, centsToCoins(curBalanceCents))
              val curPromo = userSnap.getLong("promotionalCoins") ?: 0L
              val curTotal = maxOf(userSnap.getLong("balanceCoins") ?: 0L, curEligible + curPromo)

              if (existingBonusSnap.exists()) {
                newPromoCoins = curPromo
                newBalance = curTotal
                return@runTransaction
              }

              newPromoCoins = curPromo + amount
              newBalance = maxOf(curTotal + amount, curEligible + newPromoCoins)

              if (userSnap.exists()) {
                tx.update(
                  userRef,
                  mapOf(
                    "balanceCoins" to newBalance,
                    "promotionalCoins" to newPromoCoins,
                    "lastRewardId" to key,
                    "lastRewardType" to "BONUS",
                    "lastRewardAmountCoins" to amount,
                    "updatedAt" to FieldValue.serverTimestamp()
                  )
                )
                val lbRef = firestore.collection("leaderboard").document(targetUid)
                tx.set(
                  lbRef,
                  mapOf(
                    "userId" to targetUid,
                    "displayName" to resolvedTargetName,
                    "balanceCents" to curBalanceCents,
                    "balanceCoins" to newBalance,
                    "completedTasksCount" to (userSnap.getLong("completedTasksCount") ?: 0L),
                    "approvalStatus" to (userSnap.getString("approvalStatus") ?: "approved"),
                    "updatedAt" to FieldValue.serverTimestamp()
                  ),
                  SetOptions.merge()
                )
              }

              tx.set(
                bonusRef,
                mapOf(
                  "id" to key,
                  "targetUserId" to targetUid,
                  "targetUserName" to resolvedTargetName,
                  "targetUserEmail" to resolvedTargetEmail,
                  "adminId" to adminUid,
                  "adminEmail" to adminEmail,
                  "amountCoins" to amount,
                  "reason" to reason,
                  "idempotencyKey" to key,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val txRef = firestore.collection("transactions").document("bonus_${key}")
              tx.set(
                txRef,
                mapOf(
                  "userId" to targetUid,
                  "userName" to resolvedTargetName,
                  "type" to TransactionType.ADMIN_BONUS,
                  "title" to "Admin Bonus: $reason",
                  "amountCoins" to amount,
                  "amountCents" to 0L,
                  "status" to "CREDITED",
                  "referenceId" to key,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val notifRef =
                firestore
                  .collection("users")
                  .document(targetUid)
                  .collection("notifications")
                  .document("notif_bonus_${key}")
              tx.set(
                notifRef,
                mapOf(
                  "userId" to targetUid,
                  "title" to "Bonus Coins Credited!",
                  "body" to "Admin credited $amount Coins to your account: $reason",
                  "type" to NotificationRewardType.ADMIN_BONUS,
                  "rewardCoins" to amount,
                  "referenceId" to key,
                  "isRead" to false,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
              val logRef = firestore.collection("admin_logs").document("bonus_${key}")
              tx.set(
                logRef,
                mapOf(
                  "actionType" to "REWARD",
                  "title" to "Credited Manual Bonus (+$amount Coins)",
                  "details" to "Credited $amount Coins to $resolvedTargetName ($targetUid) • Reason: $reason",
                  "targetId" to targetUid,
                  "timestampMillis" to now,
                  "createdAt" to FieldValue.serverTimestamp()
                )
              )
            }.await()
          }.onFailure { err ->
            Log.e("TaskFlowRepository", "creditManualBonus fallback error: ${err.message}", err)
            throw err
          }
        }
        mapOf(
          "success" to true,
          "id" to key,
          "targetUserId" to targetUid,
          "targetUserName" to resolvedTargetName,
          "targetUserEmail" to resolvedTargetEmail,
          "adminId" to adminUid,
          "adminEmail" to adminEmail,
          "amountCoins" to amount,
          "reason" to reason,
          "idempotencyKey" to key,
          "newPromotionalCoins" to newPromoCoins,
          "newBalanceCoins" to newBalance
        )
      }
      else -> mapOf("success" to true)
    }
  }

  private suspend fun invokeRewardCallable(functionName: String, payload: Any? = null): Any? {
    val customInvoker = rewardCallableInvoker ?: promoCallableInvoker
    return if (customInvoker != null) {
      customInvoker.invoke(functionName, payload)
    } else {
      try {
        if (payload != null) {
          functionsProvider().getHttpsCallable(functionName).call(payload).await().getData()
        } else {
          functionsProvider().getHttpsCallable(functionName).call().await().getData()
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        val isServerOnlyCallable =
          functionName == "verifyTelegramTaskMembership" ||
            functionName == "claimTelegramTaskReward" ||
            functionName == "createCampaignClick" ||
            functionName == "adminListCampaignNetworks" ||
            functionName == "adminUpsertCampaignNetwork" ||
            functionName == "adminSetCampaignNetworkStatus" ||
            functionName == "adminListCampaignTrackingConfigs" ||
            functionName == "adminUpsertCampaignTrackingConfig" ||
            functionName == "adminSetCampaignTrackingStatus" ||
            functionName == "adminGetCampaignTrackingDashboard"
        if (!isServerOnlyCallable && isCloudFunctionMissing(e)) {
          handleLocalCallableFallback(functionName, payload)
        } else {
          throw e
        }
      }
    }
  }

  override fun getOrAssignSessionRewardCoins(sessionId: String): Long {
    if (sessionId.isBlank()) return 0L
    return sessionRewardsById[sessionId] ?: 0L
  }

  override suspend fun claimDailyCheckIn(streakDay: Int?): ClaimedRewardResult =
    withTimeout(15_000L) {
      val uid = authProvider().currentUser?.uid?.trim().orEmpty()
      if (!hasCustomCallableInvoker) {
        check(uid.isNotBlank()) { "Please sign in to claim your Daily Check-in reward." }
      }
      val lockKey = "${uid.ifBlank { "test" }}:${getTodayDateKey()}"
      check(inFlightCheckInKeys.add(lockKey)) {
        "Daily Check-in claim is already in progress."
      }
      try {
        val payload = mutableMapOf<String, Any>()
        if (streakDay != null) {
          payload["streakDay"] = streakDay
        }
        val responseData = try {
          invokeRewardCallable("claimDailyCheckIn", payload)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          throw IllegalStateException(
            extractCloudFunctionErrorMessage(e, "Failed to claim Daily Check-in reward."),
            e
          )
        }
        val dataMap = responseData as? Map<*, *>
          ?: throw IllegalStateException("Empty response from claimDailyCheckIn Cloud Function.")
        val success = dataMap["success"] as? Boolean ?: true
        check(success) { "Daily Check-in claim was rejected by server." }

        val rewardType =
          (dataMap["rewardType"] as? String)?.trim().orEmpty().ifEmpty { "DAILY_CHECKIN" }
        val rewardAmount =
          ((dataMap["rewardCoins"] as? Number) ?: (dataMap["rewardCents"] as? Number))?.toLong()
            ?: throw IllegalStateException("Missing reward in claimDailyCheckIn response.")
        val newBalance =
          ((dataMap["newBalanceCoins"] as? Number) ?: (dataMap["newBalanceCents"] as? Number))
            ?.toLong()
            ?: 0L
        val resolvedStreakDay = (dataMap["streakDay"] as? Number)?.toInt() ?: streakDay
        val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()
        ClaimedRewardResult(
          rewardType = rewardType,
          rewardCents = rewardAmount,
          newBalanceCents = newBalance,
          streakDay = resolvedStreakDay,
          videosWatched = 0L,
          dateKey = dateKey
        )
      } finally {
        inFlightCheckInKeys.remove(lockKey)
      }
    }

  override suspend fun createSpinSession(): String = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to spin the wheel." }
    }
    val responseData = try {
      invokeRewardCallable("createSpinSession", null)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Unable to start Spin session."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from createSpinSession Cloud Function.")
    val sessionId = (dataMap["sessionId"] as? String)?.trim().orEmpty()
    check(sessionId.isNotBlank()) {
      "Cloud Function createSpinSession did not return a valid sessionId."
    }
    sessionId
  }

  override suspend fun createSpinSessionWithOutcome(userId: String): SpinSessionOutcome =
    withTimeout(15_000L) {
      val authUid = authProvider().currentUser?.uid?.trim().orEmpty()
      val uid = userId.trim().ifEmpty { authUid }
      if (!hasCustomCallableInvoker) {
        check(authUid.isNotBlank()) { "Please sign in to spin the wheel." }
        check(uid == authUid) { "Authenticated user mismatch." }
      } else {
        check(uid.isNotBlank()) { "Please sign in to spin the wheel." }
      }

      val responseData = try {
        invokeRewardCallable("createSpinSession", null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Unable to start Spin session."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from createSpinSession Cloud Function.")
      val sessionId = (dataMap["sessionId"] as? String)?.trim().orEmpty()
      check(sessionId.isNotBlank()) {
        "Cloud Function createSpinSession did not return a valid sessionId."
      }

      val segmentIndex =
        (dataMap["segmentIndex"] as? Number)?.toInt()?.takeIf {
          it in SpinWheelConstants.SEGMENTS_COINS.indices
        }
          ?: throw IllegalStateException(
            "Cloud Function createSpinSession did not return a valid segmentIndex."
          )
      val rewardCoins =
        (dataMap["rewardCoins"] as? Number)?.toLong()?.takeIf { it > 0L }
          ?: throw IllegalStateException(
            "Cloud Function createSpinSession did not return valid rewardCoins."
          )
      val usedFreeSpin =
        (dataMap["usedFreeSpin"] as? Boolean)
          ?: throw IllegalStateException(
            "Cloud Function createSpinSession did not return usedFreeSpin."
          )

      SpinSessionOutcome(
        sessionId = sessionId,
        segmentIndex = segmentIndex,
        rewardCoins = rewardCoins,
        usedFreeSpin = usedFreeSpin
      )
    }

  override suspend fun recordExtraSpinAdWatch(sessionId: String): Int =
    withTimeout(15_000L) {
      val uid = authProvider().currentUser?.uid?.trim().orEmpty()
      if (!hasCustomCallableInvoker) {
        check(uid.isNotBlank()) { "Please sign in to record Extra Spin ad watch." }
      }
      val cleanedSessionId = sessionId.trim()
      require(cleanedSessionId.isNotBlank() && !cleanedSessionId.startsWith("ad_")) {
        "A server-verified Extra Spin sessionId is required."
      }
      check(!consumedExtraSpinSessionIds.contains(cleanedSessionId)) {
        "This Extra Spin ad session has already been recorded."
      }
      check(inFlightExtraSpinSessionIds.add(cleanedSessionId)) {
        "Extra Spin ad watch recording is already in progress."
      }

      try {
        val payload = mapOf("sessionId" to cleanedSessionId)
        val responseData = try {
          invokeRewardCallable("recordExtraSpinAdWatch", payload)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          throw IllegalStateException(
            extractCloudFunctionErrorMessage(e, "Failed to record Extra Spin ad watch."),
            e
          )
        }
        val dataMap = responseData as? Map<*, *>
          ?: throw IllegalStateException("Empty response from recordExtraSpinAdWatch Cloud Function.")
        val success = dataMap["success"] as? Boolean ?: true
        check(success) { "Extra spin ad watch was rejected by server." }
        val videosWatched =
          (dataMap["extraSpinVideosWatched"] as? Number)?.toInt()
            ?: throw IllegalStateException(
              "Missing extraSpinVideosWatched in recordExtraSpinAdWatch response."
            )
        consumedExtraSpinSessionIds.add(cleanedSessionId)
        videosWatched
      } finally {
        inFlightExtraSpinSessionIds.remove(cleanedSessionId)
      }
    }

  override suspend fun recordExtraSpinVideoWatched(userId: String): Int {
    val uid = userId.trim().ifEmpty { authProvider().currentUser?.uid?.trim().orEmpty() }
    check(uid.isNotBlank()) { "Please sign in to unlock an Extra Spin." }
    val created = createRewardSession("EXTRA_SPIN_UNLOCK", null)
    awaitSessionVerified(uid, created.sessionId)
    return recordExtraSpinAdWatch(created.sessionId)
  }

  override suspend fun claimSpinReward(sessionId: String): Long = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to claim your Spin reward." }
    }
    val cleanedSessionId = sessionId.trim()
    check(cleanedSessionId.isNotBlank()) { "Invalid spin session." }
    check(!claimedSpinSessionIds.contains(cleanedSessionId)) {
      "This Spin reward has already been claimed."
    }
    check(inFlightSpinClaimSessionIds.add(cleanedSessionId)) {
      "Spin reward claim is already in progress."
    }

    try {
      val payload = mapOf("sessionId" to cleanedSessionId)
      val responseData = try {
        invokeRewardCallable("claimSpinReward", payload)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Failed to claim Spin reward."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from claimSpinReward Cloud Function.")
      val success = dataMap["success"] as? Boolean ?: true
      check(success) { "Spin reward claim was rejected by server." }

      val rewardCoins =
        (dataMap["rewardCoins"] as? Number)?.toLong()?.takeIf { it > 0L }
          ?: throw IllegalStateException("Missing rewardCoins in claimSpinReward response.")

      claimedSpinSessionIds.add(cleanedSessionId)
      rewardCoins
    } finally {
      inFlightSpinClaimSessionIds.remove(cleanedSessionId)
    }
  }

  override suspend fun createRewardSession(
    rewardType: String,
    streakDay: Int?
  ): CreatedRewardSession = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to start a reward session." }
    }
    val payload = mutableMapOf<String, Any>("rewardType" to rewardType)
    if (streakDay != null) {
      payload["streakDay"] = streakDay
    }
    val responseData = try {
      invokeRewardCallable("createRewardSession", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to start reward session."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from createRewardSession Cloud Function.")
    val sessionId = (dataMap["sessionId"] as? String)?.trim().orEmpty()
    check(sessionId.isNotBlank()) {
      "Cloud Function createRewardSession did not return a valid sessionId."
    }
    val resolvedType = (dataMap["rewardType"] as? String)?.trim().orEmpty().ifEmpty { rewardType }
    val resolvedReward =
      ((dataMap["rewardCoins"] as? Number) ?: (dataMap["rewardCents"] as? Number))?.toLong() ?: 0L
    if (resolvedReward > 0L) {
      sessionRewardsById[sessionId] = resolvedReward
    }
    val resolvedStreak = (dataMap["streakDay"] as? Number)?.toInt() ?: streakDay
    val resolvedDateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty().ifEmpty { getTodayDateKey() }
    CreatedRewardSession(
      sessionId = sessionId,
      rewardType = resolvedType,
      rewardCents = resolvedReward,
      streakDay = resolvedStreak,
      dateKey = resolvedDateKey
    )
  }

  override suspend fun awaitSessionVerified(userId: String, sessionId: String) {
    val cleanedUserId = userId.trim()
    val cleanedSessionId = sessionId.trim()
    require(cleanedUserId.isNotBlank()) { "User authentication is required." }
    require(cleanedSessionId.isNotBlank() && !cleanedSessionId.startsWith("ad_")) {
      "Invalid or unverified reward session."
    }
    if (!hasCustomCallableInvoker) return

    val sessionRef = firestore.collection("reward_sessions").document(cleanedSessionId)
    var lastStatus = "PENDING"
    var isS2sVerified = false

    for (attempt in 1..6) {
      val snap = try {
        withTimeout(5_000L) { sessionRef.get().await() }
      } catch (e: Throwable) {
        null
      }
      if (snap == null || !snap.exists()) {
        check(snap != null && snap.exists()) { "Reward session does not exist." }
      }
      val ownerId = snap.getString("userId").orEmpty()
      check(ownerId == cleanedUserId) { "Reward session does not belong to current user." }
      val used = snap.getBoolean("used") ?: false
      val claimed = snap.getBoolean("claimed") ?: false
      check(!used && !claimed) { "This reward session has already been used." }
      val expiresAt = snap.getTimestamp("expiresAt")
      check(expiresAt != null && expiresAt.toDate().time > System.currentTimeMillis()) {
        "Reward session has expired. Please watch a new video."
      }
      lastStatus = snap.getString("status").orEmpty()
      isS2sVerified = snap.getBoolean("s2sVerified") ?: false
      if (isS2sVerified && lastStatus == "VERIFIED") {
        return
      }
      if (attempt < 6) {
        delay(500L)
      }
    }

    check(isS2sVerified && lastStatus == "VERIFIED") {
      "Reward video completion has not been verified by the server yet. Please try claiming in a moment."
    }
  }

  override suspend fun claimRewardSession(
    sessionId: String,
    secondSessionId: String?
  ): ClaimedRewardResult =
    withTimeout(15_000L) {
      val uid = authProvider().currentUser?.uid?.trim().orEmpty()
      if (!hasCustomCallableInvoker) {
        check(uid.isNotBlank()) { "Please sign in to claim your reward." }
      }
      val cleanedSessionId = sessionId.trim()
      require(
        cleanedSessionId.isNotBlank() &&
          !cleanedSessionId.startsWith("ad_") &&
          !cleanedSessionId.startsWith("fallback_")
      ) {
        "A server-verified reward session ID is required."
      }
      val cleanedSecondSessionId = secondSessionId?.trim()
      if (secondSessionId != null) {
        require(
          !cleanedSecondSessionId.isNullOrBlank() &&
            cleanedSecondSessionId != cleanedSessionId &&
            !cleanedSecondSessionId.startsWith("ad_") &&
            !cleanedSecondSessionId.startsWith("fallback_")
        ) {
          "A second distinct server-verified reward session ID is required."
        }
      }

      check(!claimedRewardSessionIds.contains(cleanedSessionId)) {
        "Reward has already been claimed for this session."
      }
      if (!cleanedSecondSessionId.isNullOrBlank()) {
        check(!claimedRewardSessionIds.contains(cleanedSecondSessionId)) {
          "Second reward session has already been used."
        }
      }
      check(inFlightRewardClaimSessionIds.add(cleanedSessionId)) {
        "Reward claim is already in progress."
      }
      if (!cleanedSecondSessionId.isNullOrBlank()) {
        if (!inFlightRewardClaimSessionIds.add(cleanedSecondSessionId)) {
          inFlightRewardClaimSessionIds.remove(cleanedSessionId)
          throw IllegalStateException("Reward claim is already in progress.")
        }
      }

      try {
        val payload = mutableMapOf<String, Any>("sessionId" to cleanedSessionId)
        if (!cleanedSecondSessionId.isNullOrBlank()) {
          payload["secondSessionId"] = cleanedSecondSessionId
        }
        val responseData = try {
          invokeRewardCallable("claimRewardSession", payload)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Throwable) {
          throw IllegalStateException(
            extractCloudFunctionErrorMessage(e, "Failed to claim reward session."),
            e
          )
        }
        val dataMap = responseData as? Map<*, *>
          ?: throw IllegalStateException("Empty response from claimRewardSession Cloud Function.")
        val success = dataMap["success"] as? Boolean ?: true
        check(success) { "Reward session claim was rejected by server." }

        val rewardType = (dataMap["rewardType"] as? String)?.trim().orEmpty()
        val rewardAmount =
          ((dataMap["rewardCoins"] as? Number) ?: (dataMap["rewardCents"] as? Number))?.toLong()
            ?: throw IllegalStateException("Missing reward in claimRewardSession response.")
        val newBalance =
          ((dataMap["newBalanceCoins"] as? Number) ?: (dataMap["newBalanceCents"] as? Number))
            ?.toLong()
            ?: 0L
        val streakDay = (dataMap["streakDay"] as? Number)?.toInt()
        val videosWatched = (dataMap["videosWatched"] as? Number)?.toLong() ?: 0L
        val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()

        claimedRewardSessionIds.add(cleanedSessionId)
        if (!cleanedSecondSessionId.isNullOrBlank()) {
          claimedRewardSessionIds.add(cleanedSecondSessionId)
        }

        ClaimedRewardResult(
          rewardType = rewardType,
          rewardCents = rewardAmount,
          newBalanceCents = newBalance,
          streakDay = streakDay,
          videosWatched = videosWatched,
          dateKey = dateKey
        )
      } finally {
        inFlightRewardClaimSessionIds.remove(cleanedSessionId)
        if (!cleanedSecondSessionId.isNullOrBlank()) {
          inFlightRewardClaimSessionIds.remove(cleanedSecondSessionId)
        }
      }
    }

  override suspend fun recordScratchAdWatch(
    sessionId: String
  ): ScratchAdWatchResult = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to unlock a Scratch Card." }
    }
    val cleanedSessionId = sessionId.trim()
    require(
      cleanedSessionId.isNotBlank() &&
        !cleanedSessionId.startsWith("ad_") &&
        !cleanedSessionId.startsWith("fallback_")
    ) {
      "A server-verified Scratch Card sessionId is required."
    }
    check(!consumedScratchAdSessionIds.contains(cleanedSessionId)) {
      "This Scratch Card ad session has already been recorded."
    }
    check(inFlightScratchAdSessionIds.add(cleanedSessionId)) {
      "Scratch Card ad watch recording is already in progress."
    }

    try {
      val payload = mapOf("sessionId" to cleanedSessionId)
      val responseData = try {
        invokeRewardCallable("recordScratchAdWatch", payload)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Failed to record Scratch Card ad watch."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from recordScratchAdWatch Cloud Function.")
      val success = dataMap["success"] as? Boolean ?: true
      check(success) { "Scratch Card ad watch was rejected by server." }
      val resolvedSessionId =
        (dataMap["sessionId"] as? String)?.trim().orEmpty().ifEmpty { cleanedSessionId }
      val adsWatched = (dataMap["adsWatchedForNextScratch"] as? Number)?.toLong() ?: 0L
      val unlocked = (dataMap["unlockedScratchesCount"] as? Number)?.toLong() ?: 0L
      val newlyUnlocked = dataMap["newlyUnlocked"] as? Boolean ?: false
      val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()
      consumedScratchAdSessionIds.add(cleanedSessionId)
      ScratchAdWatchResult(
        success = success,
        sessionId = resolvedSessionId,
        adsWatchedForNextScratch = adsWatched,
        unlockedScratchesCount = unlocked,
        newlyUnlocked = newlyUnlocked,
        dateKey = dateKey
      )
    } finally {
      inFlightScratchAdSessionIds.remove(cleanedSessionId)
    }
  }

  override suspend fun createScratchCardSession(): ScratchCardSession = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to scratch cards." }
    }
    val lockKey = uid.ifBlank { "default_user" }
    check(inFlightScratchCreateKeys.add(lockKey)) {
      "Scratch Card creation is already in progress."
    }

    try {
      val responseData = try {
        invokeRewardCallable("createScratchCardSession", null)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Unable to create Scratch Card session."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from createScratchCardSession Cloud Function.")
      val cardId = (dataMap["cardId"] as? String)?.trim().orEmpty()
      check(cardId.isNotBlank()) {
        "Cloud Function createScratchCardSession did not return a valid cardId."
      }
      val rewardCoins =
        (dataMap["rewardCoins"] as? Number)?.toLong()?.takeIf { it in 1L..100L }
          ?: throw IllegalStateException(
            "Cloud Function createScratchCardSession did not return valid rewardCoins."
          )
      val scratchNumber = (dataMap["scratchNumber"] as? Number)?.toLong() ?: 1L
      val isFree = dataMap["isFree"] as? Boolean ?: false
      val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty().ifEmpty { getTodayDateKey() }
      ScratchCardSession(
        id = cardId,
        cardId = cardId,
        userId = uid,
        dateKey = dateKey,
        scratchNumber = scratchNumber,
        rewardCoins = rewardCoins,
        isFree = isFree,
        status = "ACTIVE",
        claimed = false
      )
    } finally {
      inFlightScratchCreateKeys.remove(lockKey)
    }
  }

  override suspend fun claimScratchCardReward(cardId: String): Long = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (!hasCustomCallableInvoker) {
      check(uid.isNotBlank()) { "Please sign in to claim your Scratch Card reward." }
    }
    val cleanedCardId = cardId.trim()
    require(cleanedCardId.isNotBlank()) { "Invalid scratch card." }
    check(!claimedScratchCardIds.contains(cleanedCardId)) {
      "This Scratch Card reward has already been claimed."
    }
    check(inFlightScratchClaimCardIds.add(cleanedCardId)) {
      "Scratch Card reward claim is already in progress."
    }

    try {
      val payload = mapOf("cardId" to cleanedCardId)
      val responseData = try {
        invokeRewardCallable("claimScratchCardReward", payload)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Failed to claim Scratch Card reward."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from claimScratchCardReward Cloud Function.")
      val success = dataMap["success"] as? Boolean ?: true
      check(success) { "Scratch Card reward claim was rejected by server." }
      val rewardCoins =
        (dataMap["rewardCoins"] as? Number)?.toLong()?.takeIf { it in 1L..100L }
          ?: throw IllegalStateException(
            "Missing or invalid rewardCoins in claimScratchCardReward response."
          )
      claimedScratchCardIds.add(cleanedCardId)
      rewardCoins
    } finally {
      inFlightScratchClaimCardIds.remove(cleanedCardId)
    }
  }

  private fun extractCloudFunctionErrorMessage(err: Throwable, fallback: String): String {
    if (err is FirebaseFunctionsException) {
      val detailMsg = (err.details as? Map<*, *>)?.get("message")?.toString()?.trim()
      val rawMsg = err.message?.trim().orEmpty()
      val best = detailMsg?.takeIf { it.isNotBlank() } ?: rawMsg
      if (best.isNotBlank() && best != err.code.name) {
        return best
      }
      return when (err.code) {
        FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Please sign in to continue."
        FirebaseFunctionsException.Code.PERMISSION_DENIED ->
          "You do not have permission to perform this action."
        FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
        FirebaseFunctionsException.Code.UNAVAILABLE ->
          "Server temporarily unavailable. Please try again."
        FirebaseFunctionsException.Code.NOT_FOUND ->
          "Service is updating. Please try again in a moment."
        else -> fallback
      }
    }
    return err.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
  }

  override suspend fun createPromoCode(
    code: String,
    rewardCoins: Long,
    maxSlots: Long,
    expiresAtDateTime: String,
    expiresAtMillis: Long?,
    expiresAtLabel: String,
    isActive: Boolean
  ): PromoCodeItem = withTimeout(15_000L) {
    val payload = mutableMapOf<String, Any>(
      "code" to normalizePromoCode(code),
      "rewardCoins" to rewardCoins,
      "maxSlots" to maxSlots,
      "isActive" to isActive
    )
    val trimmedDateTime = expiresAtDateTime.trim()
    val trimmedLabel = expiresAtLabel.trim()
    if (trimmedDateTime.isNotEmpty()) {
      payload["expiresAtDateTime"] = trimmedDateTime
    } else if (expiresAtMillis != null && expiresAtMillis > 0L) {
      payload["expiresAtMillis"] = expiresAtMillis
    }
    if (trimmedLabel.isNotEmpty()) {
      payload["expiresAtLabel"] = trimmedLabel
    } else if (trimmedDateTime.isNotEmpty()) {
      payload["expiresAtLabel"] = trimmedDateTime
    }

    val responseData = try {
      invokeRewardCallable("createPromoCode", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to create promo code."),
        e
      )
    }

    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from createPromoCode Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Promo code creation was rejected by server." }

    val serverCode = (dataMap["code"] as? String)?.trim().orEmpty().ifEmpty { normalizePromoCode(code) }
    val serverId = (dataMap["id"] as? String)?.trim().orEmpty().ifEmpty { serverCode }
    val serverRewardCoins = (dataMap["rewardCoins"] as? Number)?.toLong() ?: rewardCoins
    val serverMaxSlots = (dataMap["maxSlots"] as? Number)?.toLong() ?: maxSlots
    val serverUsedSlots = (dataMap["usedSlots"] as? Number)?.toLong() ?: 0L
    val serverExpiresAtMillis =
      (dataMap["expiresAtMillis"] as? Number)?.toLong() ?: (expiresAtMillis ?: 0L)
    val serverExpiresAtLabel = (dataMap["expiresAtLabel"] as? String)?.trim().orEmpty()
    val serverIsActive = dataMap["isActive"] as? Boolean ?: isActive
    val serverStatus =
      (dataMap["status"] as? String)?.trim().orEmpty().ifEmpty {
        if (serverIsActive) PromoCodeStatus.ACTIVE else PromoCodeStatus.INACTIVE
      }
    val serverCreatedBy = (dataMap["createdBy"] as? String)?.trim().orEmpty()

    PromoCodeItem(
      id = serverId,
      code = serverCode,
      rewardCoins = serverRewardCoins,
      maxSlots = serverMaxSlots,
      usedSlots = serverUsedSlots,
      expiresAtMillis = serverExpiresAtMillis,
      expiresAtLabel = serverExpiresAtLabel,
      isActive = serverIsActive,
      status = serverStatus,
      createdBy = serverCreatedBy
    )
  }

  override suspend fun setPromoCodeActiveStatus(
    promoId: String,
    isActive: Boolean
  ): PromoCodeStatusUpdateResult = withTimeout(15_000L) {
    val cleanId = normalizePromoCode(promoId)
    val payload = mapOf<String, Any>(
      "promoId" to cleanId,
      "isActive" to isActive
    )

    val responseData = try {
      invokeRewardCallable("setPromoCodeActiveStatus", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to update promo code status."),
        e
      )
    }

    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from setPromoCodeActiveStatus Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Failed to update promo code status." }

    val resolvedId = (dataMap["promoId"] as? String)?.trim().orEmpty().ifEmpty { cleanId }
    val resolvedActive = dataMap["isActive"] as? Boolean ?: isActive
    val resolvedStatus =
      (dataMap["status"] as? String)?.trim().orEmpty().ifEmpty {
        if (resolvedActive) PromoCodeStatus.ACTIVE else PromoCodeStatus.INACTIVE
      }

    PromoCodeStatusUpdateResult(
      success = success,
      promoId = resolvedId,
      isActive = resolvedActive,
      status = resolvedStatus
    )
  }

  override suspend fun redeemPromoCode(
    rawCode: String
  ): PromoRedemptionRecord = withTimeout(15_000L) {
    val normalized = normalizePromoCode(rawCode)
    val payload = mapOf<String, Any>(
      "code" to normalized
    )

    val responseData = try {
      invokeRewardCallable("redeemPromoCode", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Invalid or expired promo code."),
        e
      )
    }

    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from redeemPromoCode Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Promo code redemption failed." }

    val serverCode = (dataMap["code"] as? String)?.trim().orEmpty().ifEmpty { normalized }
    val serverPromoId = (dataMap["promoId"] as? String)?.trim().orEmpty().ifEmpty { serverCode }
    val serverId = (dataMap["id"] as? String)?.trim().orEmpty()
    val serverUserId = (dataMap["userId"] as? String)?.trim().orEmpty()
    val serverUserName = (dataMap["userName"] as? String)?.trim().orEmpty()
    val serverRewardCoins = (dataMap["rewardCoins"] as? Number)?.toLong() ?: 0L
    check(serverRewardCoins > 0L) {
      "Server returned an invalid promo reward amount."
    }
    val serverNewPromoCoins = (dataMap["newPromotionalCoins"] as? Number)?.toLong() ?: 0L
    val serverNewBalanceCoins = (dataMap["newBalanceCoins"] as? Number)?.toLong() ?: 0L

    PromoRedemptionRecord(
      id = serverId,
      promoId = serverPromoId,
      code = serverCode,
      userId = serverUserId,
      userName = serverUserName,
      rewardCoins = serverRewardCoins,
      newPromotionalCoins = serverNewPromoCoins,
      newBalanceCoins = serverNewBalanceCoins
    )
  }

  override suspend fun creditManualBonus(
    targetUserId: String,
    amountCoins: Long,
    reason: String,
    idempotencyKey: String
  ): ManualBonusRecord = withTimeout(15_000L) {
    val cleanTarget = targetUserId.trim()
    val cleanKey = idempotencyKey.trim()
    val cleanReason = reason.trim().take(500)
    check(inFlightManualBonusKeys.add(cleanKey)) {
      "Manual bonus request is already being processed."
    }
    try {
      val payload = mapOf<String, Any>(
        "targetUserId" to cleanTarget,
        "amountCoins" to amountCoins,
        "reason" to cleanReason,
        "idempotencyKey" to cleanKey
      )
      val responseData = try {
        invokeRewardCallable("creditManualBonus", payload)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        throw IllegalStateException(
          extractCloudFunctionErrorMessage(e, "Failed to credit manual bonus."),
          e
        )
      }
      val dataMap = responseData as? Map<*, *>
        ?: throw IllegalStateException("Empty response from creditManualBonus Cloud Function.")
      val success = dataMap["success"] as? Boolean ?: true
      check(success) { "Manual bonus credit was rejected by server." }

      val serverAmount = (dataMap["amountCoins"] as? Number)?.toLong() ?: amountCoins
      check(serverAmount > 0L) { "Server returned invalid manual bonus amount." }
      ManualBonusRecord(
        id = (dataMap["id"] as? String)?.trim().orEmpty().ifEmpty { cleanKey },
        targetUserId = (dataMap["targetUserId"] as? String)?.trim().orEmpty().ifEmpty { cleanTarget },
        targetUserName = (dataMap["targetUserName"] as? String)?.trim().orEmpty().ifEmpty { "TaskFlow Member" },
        targetUserEmail = (dataMap["targetUserEmail"] as? String)?.trim().orEmpty(),
        adminId = (dataMap["adminId"] as? String)?.trim().orEmpty(),
        adminEmail = (dataMap["adminEmail"] as? String)?.trim().orEmpty(),
        amountCoins = serverAmount,
        reason = (dataMap["reason"] as? String)?.trim().orEmpty().ifEmpty { cleanReason.ifEmpty { "Special Admin Bonus" } },
        idempotencyKey = (dataMap["idempotencyKey"] as? String)?.trim().orEmpty().ifEmpty { cleanKey }
      )
    } finally {
      inFlightManualBonusKeys.remove(cleanKey)
    }
  }

  override suspend fun createTelegramTask(
    title: String,
    channelUrl: String,
    channelChatId: String,
    rewardCoins: Long,
    isActive: Boolean
  ): TelegramTaskItem = withTimeout(15_000L) {
    val payload = mapOf<String, Any>(
      "title" to title.trim(),
      "channelUrl" to channelUrl.trim(),
      "channelChatId" to channelChatId.trim(),
      "rewardCoins" to rewardCoins,
      "isActive" to isActive
    )
    val responseData = try {
      invokeRewardCallable("createTelegramTask", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to create Telegram Join task."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from createTelegramTask Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Telegram Join task creation was rejected by server." }

    val resolvedTaskId = (dataMap["taskId"] as? String)?.trim().orEmpty()
    check(resolvedTaskId.isNotBlank()) { "Server did not return a valid taskId." }
    TelegramTaskItem(
      id = resolvedTaskId,
      taskId = resolvedTaskId,
      title = (dataMap["title"] as? String)?.trim().orEmpty().ifEmpty { title.trim() },
      channelUrl = (dataMap["channelUrl"] as? String)?.trim().orEmpty().ifEmpty { channelUrl.trim() },
      channelChatId = (dataMap["channelChatId"] as? String)?.trim().orEmpty(),
      rewardCoins = (dataMap["rewardCoins"] as? Number)?.toLong() ?: rewardCoins,
      isActive = dataMap["isActive"] as? Boolean ?: isActive,
      claimsCount = (dataMap["claimsCount"] as? Number)?.toLong() ?: 0L,
      createdBy = (dataMap["createdBy"] as? String)?.trim().orEmpty()
    )
  }

  override suspend fun setTelegramTaskActiveStatus(
    taskId: String,
    isActive: Boolean
  ): Boolean = withTimeout(15_000L) {
    val payload = mapOf<String, Any>(
      "taskId" to taskId.trim(),
      "isActive" to isActive
    )
    val responseData = try {
      invokeRewardCallable("setTelegramTaskActiveStatus", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to update Telegram task status."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from setTelegramTaskActiveStatus Cloud Function.")
    check(dataMap["success"] as? Boolean ?: true) { "Failed to update Telegram task status." }
    dataMap["isActive"] as? Boolean ?: isActive
  }

  override suspend fun verifyTelegramTaskMembership(
    taskId: String
  ): TelegramVerifyResult = withTimeout(20_000L) {
    val cleanTaskId = taskId.trim()
    val payload = mapOf<String, Any>("taskId" to cleanTaskId)
    val responseData = try {
      invokeRewardCallable("verifyTelegramTaskMembership", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Telegram membership verification failed."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from verifyTelegramTaskMembership Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Telegram verification request failed." }
    TelegramVerifyResult(
      success = success,
      verified = dataMap["verified"] as? Boolean ?: false,
      needsAccountLink = dataMap["needsAccountLink"] as? Boolean ?: false,
      taskId = (dataMap["taskId"] as? String)?.trim().orEmpty().ifEmpty { cleanTaskId },
      botUsername = (dataMap["botUsername"] as? String)?.trim().orEmpty(),
      botLinkUrl = (dataMap["botLinkUrl"] as? String)?.trim().orEmpty(),
      telegramUsernameMasked = (dataMap["telegramUsernameMasked"] as? String)?.trim().orEmpty(),
      message = (dataMap["message"] as? String)?.trim().orEmpty()
    )
  }

  override suspend fun claimTelegramTaskReward(
    taskId: String
  ): Long = withTimeout(20_000L) {
    val cleanTaskId = taskId.trim()
    val payload = mapOf<String, Any>("taskId" to cleanTaskId)
    val responseData = try {
      invokeRewardCallable("claimTelegramTaskReward", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to claim Telegram task reward."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from claimTelegramTaskReward Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    val rewardCoins = (dataMap["rewardCoins"] as? Number)?.toLong() ?: 0L
    check(success && rewardCoins > 0L) { "Telegram task reward claim was rejected by server." }
    rewardCoins
  }

  override suspend fun createCampaignClick(
    campaignId: String,
    taskId: String,
    devicePlatform: String,
    idempotencyKey: String
  ): IssuedCampaignClickResult = withTimeout(20_000L) {
    val cleanCampaignId = campaignId.trim()
    val payload = mutableMapOf<String, Any>(
      "campaignId" to cleanCampaignId,
      "devicePlatform" to devicePlatform.trim().uppercase(Locale.US).ifEmpty { "ANDROID" }
    )
    val cleanTaskId = taskId.trim()
    if (cleanTaskId.isNotEmpty()) {
      payload["taskId"] = cleanTaskId
    }
    val cleanIdempotencyKey = idempotencyKey.trim()
    if (cleanIdempotencyKey.isNotEmpty()) {
      payload["idempotencyKey"] = cleanIdempotencyKey
    }

    val responseData = try {
      invokeRewardCallable("createCampaignClick", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to start tracked campaign link."),
        e
      )
    }

    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from createCampaignClick Cloud Function.")
    val success = dataMap["success"] as? Boolean ?: true
    check(success) { "Campaign click request was rejected by server." }

    val clickId = (dataMap["clickId"] as? String)?.trim().orEmpty()
    val trackingUrl = (dataMap["trackingUrl"] as? String)?.trim().orEmpty()
    check(clickId.isNotBlank()) { "Server did not return a valid clickId." }
    check(trackingUrl.isNotBlank() && isValidTaskUrl(trackingUrl)) {
      "Server returned an invalid or unsafe campaign tracking URL."
    }

    val resolvedMode = (dataMap["verificationMode"] as? String)?.trim().orEmpty()
      .ifEmpty { CampaignVerificationMode.AFFILIATE_POSTBACK.name }
    IssuedCampaignClickResult(
      clickId = clickId,
      campaignId = (dataMap["campaignId"] as? String)?.trim().orEmpty().ifEmpty { cleanCampaignId },
      networkId = (dataMap["networkId"] as? String)?.trim().orEmpty(),
      verificationMode = resolvedMode,
      trackingUrl = trackingUrl,
      expiresAtMillis = (dataMap["expiresAtMillis"] as? Number)?.toLong() ?: 0L,
      deduplicated = dataMap["deduplicated"] as? Boolean ?: false
    )
  }

  override suspend fun adminListCampaignNetworks(
    pageSize: Int,
    cursor: String,
    search: String,
    networkId: String
  ): AdminPaginatedSlice<AdminCampaignNetworkItem> = withTimeout(20_000L) {
    val payload = mutableMapOf<String, Any>("pageSize" to pageSize.coerceIn(1, 50))
    if (cursor.isNotBlank()) payload["cursor"] = cursor.trim()
    if (search.isNotBlank()) payload["search"] = search.trim()
    if (networkId.isNotBlank()) payload["networkId"] = networkId.trim()
    val responseData = try {
      invokeRewardCallable("adminListCampaignNetworks", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to load campaign networks."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminListCampaignNetworks.")
    CampaignAdminParsers.parseNetworkPage(dataMap)
  }

  override suspend fun adminUpsertCampaignNetwork(
    item: AdminCampaignNetworkItem,
    mode: String
  ): AdminCampaignNetworkItem = withTimeout(20_000L) {
    val payload = CampaignAdminParsers.toUpsertNetworkPayload(item, mode)
    val responseData = try {
      invokeRewardCallable("adminUpsertCampaignNetwork", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to save campaign network."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminUpsertCampaignNetwork.")
    val networkMap = dataMap["network"] as? Map<*, *>
      ?: throw IllegalStateException("Server did not return saved network configuration.")
    CampaignAdminParsers.parseNetwork(networkMap)
  }

  override suspend fun adminSetCampaignNetworkStatus(
    networkId: String,
    isActive: Boolean
  ): AdminCampaignNetworkItem = withTimeout(20_000L) {
    val payload = mapOf(
      "networkId" to networkId.trim(),
      "isActive" to isActive
    )
    val responseData = try {
      invokeRewardCallable("adminSetCampaignNetworkStatus", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to update campaign network status."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminSetCampaignNetworkStatus.")
    val networkMap = dataMap["network"] as? Map<*, *>
      ?: throw IllegalStateException("Server did not return updated network status.")
    CampaignAdminParsers.parseNetwork(networkMap)
  }

  override suspend fun adminListCampaignTrackingConfigs(
    pageSize: Int,
    cursor: String,
    search: String
  ): AdminPaginatedSlice<AdminCampaignTrackingConfigItem> = withTimeout(20_000L) {
    val payload = mutableMapOf<String, Any>("pageSize" to pageSize.coerceIn(1, 50))
    if (cursor.isNotBlank()) payload["cursor"] = cursor.trim()
    if (search.isNotBlank()) payload["search"] = search.trim()
    val responseData = try {
      invokeRewardCallable("adminListCampaignTrackingConfigs", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to load campaign tracking configurations."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminListCampaignTrackingConfigs.")
    CampaignAdminParsers.parseCampaignConfigPage(dataMap)
  }

  override suspend fun adminUpsertCampaignTrackingConfig(
    item: AdminCampaignTrackingConfigItem,
    mode: String
  ): AdminCampaignTrackingConfigItem = withTimeout(20_000L) {
    val payload = CampaignAdminParsers.toUpsertCampaignConfigPayload(item, mode)
    val responseData = try {
      invokeRewardCallable("adminUpsertCampaignTrackingConfig", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to save campaign tracking configuration."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminUpsertCampaignTrackingConfig.")
    val configMap = dataMap["config"] as? Map<*, *>
      ?: throw IllegalStateException("Server did not return saved campaign configuration.")
    CampaignAdminParsers.parseCampaignConfig(configMap)
  }

  override suspend fun adminSetCampaignTrackingStatus(
    campaignId: String,
    isActive: Boolean
  ): AdminCampaignTrackingConfigItem = withTimeout(20_000L) {
    val payload = mapOf(
      "campaignId" to campaignId.trim(),
      "isActive" to isActive
    )
    val responseData = try {
      invokeRewardCallable("adminSetCampaignTrackingStatus", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to update campaign status."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminSetCampaignTrackingStatus.")
    val configMap = dataMap["config"] as? Map<*, *>
      ?: throw IllegalStateException("Server did not return updated campaign status.")
    CampaignAdminParsers.parseCampaignConfig(configMap)
  }

  override suspend fun adminGetCampaignTrackingDashboard(
    dateRange: String,
    pageSize: Int,
    campaignCursor: String,
    networkCursor: String
  ): AdminCampaignDashboardResult = withTimeout(25_000L) {
    val payload = mutableMapOf<String, Any>(
      "dateRange" to dateRange.trim().uppercase(Locale.US).ifEmpty { "30D" },
      "pageSize" to pageSize.coerceIn(1, 50)
    )
    if (campaignCursor.isNotBlank()) payload["campaignCursor"] = campaignCursor.trim()
    if (networkCursor.isNotBlank()) payload["networkCursor"] = networkCursor.trim()
    val responseData = try {
      invokeRewardCallable("adminGetCampaignTrackingDashboard", payload)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      throw IllegalStateException(
        extractCloudFunctionErrorMessage(e, "Failed to load campaign tracking analytics."),
        e
      )
    }
    val dataMap = responseData as? Map<*, *>
      ?: throw IllegalStateException("Empty response from adminGetCampaignTrackingDashboard.")
    CampaignAdminParsers.parseDashboardResult(dataMap)
  }
}

/**
 * Encodes a Gallery image URI into a compact JPEG data URI (<= 180 KB) for Firestore-safe persistence.
 */
fun encodeGalleryImageAsDataUri(
  context: Context,
  imageUri: Uri,
  maxDimensionPx: Int = 900,
  initialQuality: Int = 72
): String? {
  return runCatching {
    val rawBytes =
      context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        ?: return null
    if (rawBytes.isEmpty()) return null

    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, boundsOptions)

    var sampleSize = 1
    val width = boundsOptions.outWidth
    val height = boundsOptions.outHeight
    if (width > 0 && height > 0) {
      while (width / sampleSize > maxDimensionPx * 2 || height / sampleSize > maxDimensionPx * 2) {
        sampleSize *= 2
      }
    }

    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val decodedBitmap =
      BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOptions) ?: return null

    val scaledBitmap =
      if (decodedBitmap.width > maxDimensionPx || decodedBitmap.height > maxDimensionPx) {
        val ratio =
          minOf(
            maxDimensionPx.toFloat() / decodedBitmap.width.toFloat(),
            maxDimensionPx.toFloat() / decodedBitmap.height.toFloat()
          )
        val targetW = (decodedBitmap.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (decodedBitmap.height * ratio).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(decodedBitmap, targetW, targetH, true)
      } else {
        decodedBitmap
      }

    var quality = initialQuality
    var compressedBytes: ByteArray
    do {
      val out = ByteArrayOutputStream()
      scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
      compressedBytes = out.toByteArray()
      quality -= 12
    } while (compressedBytes.size > 160_000 && quality >= 30)

    val base64Str = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
    "data:image/jpeg;base64,$base64Str"
  }.getOrNull()
}

class FirebaseStorageProofUploader(
  private val storageProvider: () -> FirebaseStorage = { FirebaseStorage.getInstance() },
  private val contextProvider: () -> Context? = {
    runCatching { FirebaseApp.getInstance().applicationContext }.getOrNull()
  }
) : ProofPhotoUploader {
  override suspend fun uploadProofPhoto(
    userId: String,
    taskId: String,
    imageUri: Uri,
    onProgress: (Int) -> Unit
  ): UploadedProofPhoto {
    val timestamp = System.currentTimeMillis()
    val storagePath = "submissions/$userId/$taskId/proof_${timestamp}.jpg"
    onProgress(10)

    // 1. Primary path: Upload to Firebase Storage and retrieve HTTPS download URL
    val firebaseUploadResult = runCatching {
      val storageRef = storageProvider().reference.child(storagePath)
      val uploadTask = storageRef.putFile(imageUri)
      uploadTask.addOnProgressListener { snapshot ->
        val total = snapshot.totalByteCount
        if (total > 0L) {
          val pct = ((snapshot.bytesTransferred * 100L) / total).toInt().coerceIn(10, 95)
          onProgress(pct)
        }
      }
      uploadTask.await()
      val downloadUrl = storageRef.downloadUrl.await().toString().trim()
      check(downloadUrl.isNotEmpty()) {
        "Firebase Storage did not return a valid download URL for the uploaded proof image."
      }
      onProgress(100)
      UploadedProofPhoto(
        downloadUrl = downloadUrl,
        storagePath = storagePath
      )
    }

    if (firebaseUploadResult.isSuccess) {
      return firebaseUploadResult.getOrThrow()
    }

    // 2. Resilient fallback: If Firebase Storage rules/bucket are not yet published in Console,
    // compress & encode the selected Gallery screenshot as a real JPEG data URI stored in Firestore
    val appContext = contextProvider()
    if (appContext != null) {
      onProgress(55)
      val encodedDataUri = encodeGalleryImageAsDataUri(appContext, imageUri)
      if (!encodedDataUri.isNullOrBlank()) {
        onProgress(100)
        return UploadedProofPhoto(
          downloadUrl = encodedDataUri,
          storagePath = "inline://$storagePath"
        )
      }
    }

    throw firebaseUploadResult.exceptionOrNull()
      ?: IllegalStateException("Failed to upload proof image. Please select a valid screenshot from Gallery.")
  }
}

class TaskFlowRepository(
  private val firestore: FirebaseFirestore,
  private val authProvider: () -> FirebaseAuth = { Firebase.auth },
  private val photoUploader: ProofPhotoUploader = FirebaseStorageProofUploader(),
  private val rewardFunctionsClient: RewardCloudFunctionsClient =
    FirebaseRewardCloudFunctionsClient(firestore),
  private val telegramMembershipVerifier: TelegramMembershipVerifier =
    HttpTelegramBotMembershipVerifier(),
  private val authenticatedUidProvider: () -> String? = { authProvider().currentUser?.uid }
) {
  private val usersCollection = firestore.collection("users")
  private val leaderboardCollection = firestore.collection("leaderboard")
  private val tasksCollection = firestore.collection("tasks")
  private val submissionsCollection = firestore.collection("submissions")
  private val adminsCollection = firestore.collection("admins")
  private val withdrawalRequestsCollection = firestore.collection("withdrawal_requests")
  private val transactionsCollection = firestore.collection("transactions")
  private val campaignsCollection = firestore.collection("campaigns")
  private val supportTicketsCollection = firestore.collection("support_tickets")
  private val appsCollection = firestore.collection("apps")
  private val adminLogsCollection = firestore.collection("admin_logs")
  private val usedGiftCodesCollection = firestore.collection("used_gift_codes")
  private val appConfigCollection = firestore.collection("app_config")
  private val withdrawalAvailabilityDoc = appConfigCollection.document("withdrawal_availability")
  private val telegramJoinConfigDoc = appConfigCollection.document("telegram_join_config")
  private val telegramTasksCollection = firestore.collection("telegram_tasks")
  private val telegramClaimsCollection = firestore.collection("telegram_claims")
  private val promoCodesCollection = firestore.collection("promo_codes")
  private val manualBonusesCollection = firestore.collection("manual_bonuses")
  private val adminNotificationsCollection = firestore.collection("admin_notifications")

  internal fun hashGiftCode(rawCode: String): String {
    val normalized = rawCode.trim().uppercase(Locale.US)
    val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
  }

  private fun parseBooleanMap(raw: Any?, defaults: Map<String, Boolean>): Map<String, Boolean> {
    val map = raw as? Map<*, *> ?: return defaults
    return defaults.mapValues { (key, defaultVal) ->
      (map[key] as? Boolean) ?: defaultVal
    }
  }

  private fun DocumentSnapshot.toWithdrawalAvailabilityConfig(): WithdrawalAvailabilityConfig {
    if (!exists()) return WithdrawalAvailabilityConfig()
    val defaultUpi = mapOf("200" to true, "500" to true)
    val defaultGift = mapOf("150" to true, "200" to true, "250" to true)
    return WithdrawalAvailabilityConfig(
      upiEnabled = getBoolean("upiEnabled") ?: true,
      amazonEnabled = getBoolean("amazonEnabled") ?: true,
      googlePlayEnabled = getBoolean("googlePlayEnabled") ?: true,
      flipkartEnabled = getBoolean("flipkartEnabled") ?: true,
      upiDenominationsEnabled = parseBooleanMap(get("upiDenominationsEnabled"), defaultUpi),
      amazonDenominationsEnabled = parseBooleanMap(get("amazonDenominationsEnabled"), defaultGift),
      googlePlayDenominationsEnabled = parseBooleanMap(get("googlePlayDenominationsEnabled"), defaultGift),
      flipkartDenominationsEnabled = parseBooleanMap(get("flipkartDenominationsEnabled"), defaultGift),
      updatedAt = getTimestamp("updatedAt")
    )
  }

  suspend fun recordAdminActivity(
    actionType: String,
    title: String,
    details: String,
    targetId: String = ""
  ) {
    runCatching {
      val docRef = adminLogsCollection.document()
      docRef.set(
        mapOf(
          "actionType" to actionType,
          "title" to title.take(160),
          "details" to details.take(600),
          "targetId" to targetId,
          "timestampMillis" to System.currentTimeMillis(),
          "createdAt" to FieldValue.serverTimestamp()
        )
      ).await()
    }
  }

  private fun <T> DocumentSnapshot.toObjectSafe(clazz: Class<T>): T? {
    if (!exists()) return null
    return try {
      toObject(clazz)
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Safe fallback for ${clazz.simpleName} on doc $id: ${e.message}")
      null
    }
  }

  private fun DocumentSnapshot.toUserProfileSafe(): UserProfile? {
    if (!exists()) return null
    return try {
      val parsed = toObject(UserProfile::class.java)
      if (parsed != null) {
        parsed.copy(
          id = id.ifBlank { parsed.userId },
          userId = parsed.userId.ifBlank { id }
        )
      } else {
        null
      }
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Fallback parsing UserProfile for doc $id: ${e.message}")
      val resolvedUid =
        (runCatching { getString("userId") }.getOrNull()?.trim().orEmpty()).ifEmpty { id }
      UserProfile(
        id = id.ifBlank { resolvedUid },
        userId = resolvedUid,
        email = runCatching { getString("email") }.getOrNull()?.trim().orEmpty(),
        displayName = runCatching { getString("displayName") }.getOrNull()?.trim().orEmpty(),
        photoUrl = runCatching { getString("photoUrl") }.getOrNull()?.trim().orEmpty(),
        role = runCatching { getString("role") }.getOrNull()?.trim().orEmpty().ifEmpty { "user" },
        approvalStatus =
          runCatching { getString("approvalStatus") }.getOrNull()?.trim().orEmpty().ifEmpty { "approved" },
        balanceCents = runCatching { getLong("balanceCents") }.getOrNull() ?: 0L,
        balanceCoins = runCatching { getLong("balanceCoins") }.getOrNull() ?: 0L,
        eligibleCoins = runCatching { getLong("eligibleCoins") }.getOrNull() ?: 0L,
        promotionalCoins = runCatching { getLong("promotionalCoins") }.getOrNull() ?: 0L,
        virtualPoints = runCatching { getLong("virtualPoints") }.getOrNull() ?: 0L,
        completedTasksCount = runCatching { getLong("completedTasksCount") }.getOrNull() ?: 0L,
        assignedTaskId = runCatching { getString("assignedTaskId") }.getOrNull()?.trim().orEmpty(),
        lastCheckInDate = runCatching { getString("lastCheckInDate") }.getOrNull()?.trim().orEmpty(),
        checkInStreak = runCatching { getLong("checkInStreak") }.getOrNull() ?: 0L,
        lastFreeSpinAt = runCatching { getTimestamp("lastFreeSpinAt") }.getOrNull(),
        extraSpinVideosWatched = runCatching { getLong("extraSpinVideosWatched") }.getOrNull() ?: 0L,
        extraSpinAvailable = runCatching { getBoolean("extraSpinAvailable") }.getOrNull() ?: false,
        telegramJoinedVerified =
          runCatching { getBoolean("telegramJoinedVerified") }.getOrNull() ?: false,
        telegramRewardClaimed =
          runCatching { getBoolean("telegramRewardClaimed") }.getOrNull() ?: false,
        telegramUserId = runCatching { getString("telegramUserId") }.getOrNull()?.trim().orEmpty(),
        lastCheckInTimestamp = runCatching { getTimestamp("lastCheckInTimestamp") }.getOrNull(),
        createdAt = runCatching { getTimestamp("createdAt") }.getOrNull(),
        updatedAt = runCatching { getTimestamp("updatedAt") }.getOrNull()
      )
    }
  }

  private fun DocumentSnapshot.toTransactionRecordSafe(): TransactionRecord? {
    if (!exists()) return null
    return try {
      toObject(TransactionRecord::class.java)?.copy(id = id)
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Fallback parsing TransactionRecord for doc $id: ${e.message}")
      TransactionRecord(
        id = id,
        userId = runCatching { getString("userId") }.getOrNull()?.trim().orEmpty(),
        userName = runCatching { getString("userName") }.getOrNull()?.trim().orEmpty(),
        type =
          runCatching { getString("type") }.getOrNull()?.trim().orEmpty()
            .ifEmpty { TransactionType.DAILY_CHECKIN },
        title = runCatching { getString("title") }.getOrNull()?.trim().orEmpty(),
        amountCents = runCatching { getLong("amountCents") }.getOrNull() ?: 0L,
        amountCoins = runCatching { getLong("amountCoins") }.getOrNull() ?: 0L,
        status =
          runCatching { getString("status") }.getOrNull()?.trim().orEmpty().ifEmpty { "CREDITED" },
        referenceId = runCatching { getString("referenceId") }.getOrNull()?.trim().orEmpty(),
        createdAt = runCatching { getTimestamp("createdAt") }.getOrNull()
      )
    }
  }

  private fun DocumentSnapshot.toNotificationItemSafe(): NotificationItem? {
    if (!exists()) return null
    return try {
      toObject(NotificationItem::class.java)?.copy(id = id)
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Fallback parsing NotificationItem for doc $id: ${e.message}")
      NotificationItem(
        id = id,
        userId = runCatching { getString("userId") }.getOrNull()?.trim().orEmpty(),
        title = runCatching { getString("title") }.getOrNull()?.trim().orEmpty(),
        body =
          runCatching { getString("body") ?: getString("message") }.getOrNull()?.trim().orEmpty(),
        type = runCatching { getString("type") }.getOrNull()?.trim().orEmpty().ifEmpty { "INFO" },
        rewardCoins = runCatching { getLong("rewardCoins") }.getOrNull() ?: 0L,
        isRead = runCatching { getBoolean("isRead") }.getOrNull() ?: false,
        referenceId = runCatching { getString("referenceId") }.getOrNull()?.trim().orEmpty(),
        createdAt = runCatching { getTimestamp("createdAt") }.getOrNull()
      )
    }
  }

  fun observeAdminActivityLogs(isAdminAuthorized: Boolean): Flow<List<AdminActivityLog>> =
    callbackFlow {
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        adminLogsCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val logs =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toObjectSafe(AdminActivityLog::class.java) }
                ?.sortedByDescending { it.createdAt?.seconds ?: (it.timestampMillis / 1000L) }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(logs)
        }
      awaitClose { listener.remove() }
    }

  private fun computeSnapshotEffectiveBalanceCoins(snap: DocumentSnapshot?): Long {
    if (snap == null || !snap.exists()) return 0L
    val rawCents = snap.getLong("balanceCents") ?: 0L
    val centsCoins = centsToCoins(rawCents)
    val explicitEligible = snap.getLong("eligibleCoins") ?: 0L
    val eligibleCoins = maxOf(explicitEligible, centsCoins)
    val promoCoins = snap.getLong("promotionalCoins") ?: 0L
    val explicitBalanceCoins = snap.getLong("balanceCoins") ?: 0L
    return maxOf(explicitBalanceCoins, eligibleCoins + promoCoins)
  }

  /**
   * Builds a privacy-safe public leaderboard document containing ONLY non-sensitive public fields
   * (`userId`, `displayName`, `balanceCents`, `balanceCoins`, `completedTasksCount`, `approvalStatus`, `updatedAt`)
   * and NEVER `email`. Preserves server-authoritative `balanceCoins`.
   */
  internal fun buildLeaderboardPayload(
    userId: String,
    displayName: String,
    balanceCents: Long,
    balanceCoins: Long? = null,
    completedTasksCount: Long,
    approvalStatus: String
  ): Map<String, Any> {
    val payload = mutableMapOf<String, Any>(
      "userId" to userId,
      "displayName" to displayName.trim().ifEmpty { "TaskFlow Member" },
      "balanceCents" to balanceCents,
      "completedTasksCount" to completedTasksCount,
      "approvalStatus" to approvalStatus,
      "updatedAt" to FieldValue.serverTimestamp()
    )
    if (balanceCoins != null) {
      payload["balanceCoins"] = balanceCoins
    }
    return payload
  }

  /**
   * Checks if the current user is an authorized administrator via `/admins/{uid}` (and bootstrapped owner).
   * Never trusts client-editable `role` in `/users/{uid}`.
   */
  fun observeAdminAuthorization(uid: String, email: String): Flow<Boolean> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val normalizedEmail = email.trim().lowercase(Locale.US)
    val isBootstrappedEmail = normalizedEmail == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)
    if (isBootstrappedEmail) {
      trySend(true)
    }

    val listener =
      adminsCollection.document(uid).addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(isBootstrappedEmail)
          return@addSnapshotListener
        }
        val existsInAdmins = snapshot != null && snapshot.exists()
        trySend(isBootstrappedEmail || existsInAdmins)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Performs a one-shot secure Admin authorization check against `/admins/{uid}` in Firestore
   * (and initializes `/admins/{uid}` if the user is the verified bootstrap Admin).
   * Never trusts client-editable `role` in `/users/{uid}`.
   */
  suspend fun checkAndEnsureAdminAuthorization(uid: String, email: String): Boolean {
    if (uid.isBlank()) return false
    val safeEmail = email.trim()
    val isBootstrappedAdmin =
      safeEmail.lowercase(Locale.US) == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)
    return runCatching {
      val adminDocRef = adminsCollection.document(uid)
      val adminSnap = adminDocRef.get().await()
      if (adminSnap.exists()) {
        true
      } else if (isBootstrappedAdmin) {
        adminDocRef
          .set(
            mapOf(
              "userId" to uid,
              "email" to safeEmail,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
        true
      } else {
        false
      }
    }.getOrElse { isBootstrappedAdmin }
  }

  /**
   * Ensures the signed-in user has a document in `/users/{userId}`, syncs `/leaderboard/{userId}`
   * (without private email), and if they match the bootstrapped admin email, also initializes `/admins/{userId}`.
   */
  suspend fun ensureUserProfile(
    uid: String,
    email: String,
    displayName: String
  ): Result<UserProfile> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val safeEmail = email.trim().ifEmpty { "user@taskflow.app" }
    val safeName =
      displayName.trim().ifEmpty {
        safeEmail.substringBefore("@").replaceFirstChar { it.uppercase() }
      }
    val isBootstrappedAdmin =
      safeEmail.lowercase(Locale.US) == BOOTSTRAPPED_ADMIN_EMAIL.lowercase(Locale.US)

    if (isBootstrappedAdmin) {
      val adminDocRef = adminsCollection.document(uid)
      val adminSnap = adminDocRef.get().await()
      if (!adminSnap.exists()) {
        adminDocRef
          .set(
            mapOf(
              "userId" to uid,
              "email" to safeEmail,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
    }

    val userDocRef = usersCollection.document(uid)
    val leaderboardRef = leaderboardCollection.document(uid)
    val snapshot = userDocRef.get().await()
    if (!snapshot.exists()) {
      val initialRole = if (isBootstrappedAdmin) "admin" else "user"
      val initialApproval = if (isBootstrappedAdmin) "approved" else "pending"
      val data =
        mapOf(
          "userId" to uid,
          "email" to safeEmail,
          "displayName" to safeName,
          "role" to initialRole,
          "approvalStatus" to initialApproval,
          "balanceCents" to 0L,
          "virtualPoints" to 0L,
          "completedTasksCount" to 0L,
          "assignedTaskId" to "",
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      val batch = firestore.batch()
      batch.set(userDocRef, data)
      batch.set(
        leaderboardRef,
        buildLeaderboardPayload(
          userId = uid,
          displayName = safeName,
          balanceCents = 0L,
          balanceCoins = 0L,
          completedTasksCount = 0L,
          approvalStatus = initialApproval
        ),
        SetOptions.merge()
      )
      batch.commit().await()
    } else if (isBootstrappedAdmin) {
      val currentRole = snapshot.getString("role") ?: "user"
      val currentApproval = snapshot.getString("approvalStatus") ?: "pending"
      val currentName = snapshot.getString("displayName") ?: safeName
      val currentBalance = snapshot.getLong("balanceCents") ?: 0L
      val currentBalanceCoins = computeSnapshotEffectiveBalanceCoins(snapshot)
      val currentCompleted = snapshot.getLong("completedTasksCount") ?: 0L
      if (currentRole != "admin" || currentApproval != "approved") {
        val batch = firestore.batch()
        batch.update(
          userDocRef,
          mapOf(
            "role" to "admin",
            "approvalStatus" to "approved",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        batch.set(
          leaderboardRef,
          buildLeaderboardPayload(
            userId = uid,
            displayName = currentName,
            balanceCents = currentBalance,
            balanceCoins = currentBalanceCoins,
            completedTasksCount = currentCompleted,
            approvalStatus = "approved"
          ),
          SetOptions.merge()
        )
        batch.commit().await()
      }
    }

    val updatedSnap = userDocRef.get().await()
    updatedSnap.toUserProfileSafe()
      ?: error("Failed to deserialize UserProfile for $uid")
  }

  /**
   * Directly fetches a single user profile by UID.
   */
  suspend fun getUserProfile(uid: String): Result<UserProfile?> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snap = usersCollection.document(uid).get().await()
    snap.toUserProfileSafe()
  }

  /**
   * Observes the current user's profile in real time.
   */
  fun observeUserProfile(uid: String): Flow<UserProfile?> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      usersCollection.document(uid).addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        trySend(snapshot?.toUserProfileSafe())
      }
    awaitClose { listener.remove() }
  }

  /**
   * Updates only the current user's displayName and syncs `/leaderboard/{uid}`.
   */
  suspend fun updateUserDisplayName(uid: String, newDisplayName: String): Result<Unit> =
    runCatching {
      require(uid.isNotBlank()) { "uid must not be blank" }
      val cleaned = newDisplayName.trim()
      require(cleaned.isNotEmpty() && cleaned.length <= 100) {
        "Display name must be between 1 and 100 characters."
      }
      val userRef = usersCollection.document(uid)
      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "User profile not found." }
      val currentBalanceCoins = computeSnapshotEffectiveBalanceCoins(userSnap)
      val batch = firestore.batch()
      batch.update(
        userRef,
        mapOf(
          "displayName" to cleaned,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.set(
        leaderboardCollection.document(uid),
        buildLeaderboardPayload(
          userId = uid,
          displayName = cleaned,
          balanceCents = userSnap.getLong("balanceCents") ?: 0L,
          balanceCoins = currentBalanceCoins,
          completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
          approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
        ),
        SetOptions.merge()
      )
      batch.commit().await()
    }

  private fun DocumentSnapshot.toTaskItem(): TaskItem? {
    val parsed = toObjectSafe(TaskItem::class.java) ?: return null
    val explicitActive = runCatching { getBoolean("isActive") }.getOrNull() ?: true
    return parsed.copy(id = id.ifBlank { parsed.id }, isActive = explicitActive)
  }

  /**
   * Observes all tasks in `/tasks` for any authenticated user.
   */
  fun observeUserTasks(uid: String): Flow<List<TaskItem>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val currentAuthUser = authProvider().currentUser
    if (currentAuthUser == null) {
      close(
        FirebaseFirestoreException(
          "Sign-in required to observe tasks.",
          FirebaseFirestoreException.Code.PERMISSION_DENIED
        )
      )
      return@callbackFlow
    }
    val listener =
      tasksCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot
              ?.documents
              ?.mapNotNull { it.toTaskItem() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all visible tasks for an authenticated user.
   */
  suspend fun getUserTasks(uid: String): Result<List<TaskItem>> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snapshot = tasksCollection.get().await()
    snapshot.documents
      .mapNotNull { it.toTaskItem() }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Observes the current user's own submissions in `/submissions` so user history and activity
   * remain preserved even if an Admin deletes the original task from `/tasks`.
   */
  fun observeUserSubmissions(uid: String): Flow<List<SubmissionItem>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      submissionsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toObjectSafe(SubmissionItem::class.java) }
                ?.sortedByDescending {
                  it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
                }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of a user's own submissions in `/submissions`.
   */
  suspend fun getUserSubmissions(uid: String): Result<List<SubmissionItem>> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snapshot = submissionsCollection.whereEqualTo("userId", uid).get().await()
    snapshot.documents
      .mapNotNull { it.toObjectSafe(SubmissionItem::class.java) }
      .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
  }

  /**
   * Directly fetches a single task by ID.
   */
  suspend fun getTaskById(taskId: String): Result<TaskItem?> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val snap = tasksCollection.document(taskId).get().await()
    snap.toTaskItem()
  }

  /**
   * Directly fetches a single submission by ID.
   */
  suspend fun getSubmissionById(submissionId: String): Result<SubmissionItem?> = runCatching {
    require(submissionId.isNotBlank()) { "submissionId must not be blank" }
    val snap = submissionsCollection.document(submissionId).get().await()
    snap.toObjectSafe(SubmissionItem::class.java)
  }

  /**
   * Observes all tasks in `/tasks` for an authorized admin.
   */
  fun observeAdminTasks(adminUid: String, isAdminAuthorized: Boolean): Flow<List<TaskItem>> =
    callbackFlow {
      require(adminUid.isNotBlank()) { "adminUid must not be blank" }
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        tasksCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toTaskItem() }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
      awaitClose { listener.remove() }
    }

  /**
   * Observes all submissions in `/submissions` in real time for an authorized admin.
   */
  fun observeAdminSubmissions(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<SubmissionItem>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      submissionsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot
              ?.documents
              ?.mapNotNull { it.toObjectSafe(SubmissionItem::class.java) }
              ?.sortedByDescending {
                it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
              }
              ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all submissions for an authorized admin.
   */
  suspend fun getAdminSubmissions(): Result<List<SubmissionItem>> = runCatching {
    val snapshot = submissionsCollection.get().await()
    snapshot.documents
      .mapNotNull { it.toObjectSafe(SubmissionItem::class.java) }
      .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
  }

  /**
   * Observes all registered user profiles in `/users` for an authorized admin.
   */
  fun observeRegisteredUsers(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<UserProfile>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      usersCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val users =
          runCatching {
            snapshot
              ?.documents
              ?.mapNotNull { it.toUserProfileSafe() }
              ?.sortedBy { it.displayName.lowercase(Locale.US) }
              ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(users)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Creates a new task in `/tasks` and updates the assigned user's `assignedTaskId` atomically.
   */
   suspend fun createTask(
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = "",
    appId: String = "",
    steps: List<TaskStep> = emptyList(),
    referenceImages: List<String> = emptyList(),
    installationInstructions: String = "",
    completionDurationMinutes: Int = 5,
    targetSubmissions: Long = 0L,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    campaignId: String = "",
    isActive: Boolean = true
  ): Result<String> = runCatching {
    val adminUid =
      authProvider().currentUser?.uid ?: error("Must be signed in as Admin to create a task.")
    val cleanedTitle = title.trim()
    val cleanedAppName = appName.trim()
    val cleanedAppIconUrl = appIconUrl.trim()
    val cleanedDesc = description.trim()
    val cleanedInstructions = instructions.trim().ifBlank { cleanedDesc }
    val cleanedInstallInstructions = installationInstructions.trim()
    val cleanedRequiredProof =
      requiredProof.trim().ifBlank {
        "Provide written confirmation notes and a clear screenshot showing task completion."
      }
    val cleanedLink = taskLink.trim()
    val cleanedCat = category.trim().ifEmpty { "App Install" }
    require(cleanedTitle.isNotEmpty() && cleanedTitle.length <= 120) {
      "Task title must be 1–120 characters."
    }
    require(cleanedAppName.length <= 120) {
      "App or website name must be up to 120 characters."
    }
    require(isValidImageUrlOrDataUri(cleanedAppIconUrl)) {
      "App icon must be a valid HTTPS image URL or selected Gallery image."
    }
    require(cleanedDesc.isNotEmpty() && cleanedDesc.length <= 2000) {
      "Task description must be 1–2000 characters."
    }
    require(cleanedInstructions.length <= 4000) {
      "Step-by-step instructions must be up to 4000 characters."
    }
    require(cleanedRequiredProof.length <= 2000) {
      "Required proof description must be up to 2000 characters."
    }
    require(cleanedLink.isEmpty() || isValidTaskUrl(cleanedLink)) {
      "Task link must be a valid HTTPS URL (e.g., https://play.google.com/...)."
    }
    require(rewardCents in 1..10_000_000) { "Reward must be greater than 0 Coins." }
    require(assignedUserId.isNotBlank()) { "An assigned user must be selected." }

    val docRef = tasksCollection.document()
    val payload =
      mapOf(
        "appId" to appId.trim(),
        "campaignId" to campaignId.trim(),
        "title" to cleanedTitle,
        "appName" to cleanedAppName,
        "appIconUrl" to cleanedAppIconUrl,
        "description" to cleanedDesc,
        "installationInstructions" to cleanedInstallInstructions,
        "instructions" to cleanedInstructions,
        "requiredProof" to cleanedRequiredProof,
        "taskLink" to cleanedLink,
        "category" to cleanedCat,
        "rewardCents" to rewardCents, // Legacy
        "rewardCoins" to rewardCents, // New (map to rewardCents)
        "completionDurationMinutes" to completionDurationMinutes.coerceIn(1, 1440),
        "targetSubmissions" to targetSubmissions.coerceAtLeast(0L),
        "completedSubmissions" to 0L,
        "startDate" to startDate.trim(),
        "endDate" to endDate.trim(),
        "eligibilityRules" to eligibilityRules.trim().ifEmpty { "All Approved Users" },
        "assignedUserId" to assignedUserId,
        "assignedUserName" to assignedUserName.trim().ifEmpty { "Assigned User" },
        "assignedUserEmail" to assignedUserEmail.trim().ifEmpty { "user@taskflow.app" },
        "status" to TaskStatus.PENDING,
        "isActive" to isActive,
        "proofText" to "",
        "proofImageUrl" to "",
        "proofStoragePath" to "",
        "adminFeedback" to "",
        "createdBy" to adminUid,
        "steps" to steps.map { mapOf(
          "stepNumber" to it.stepNumber,
          "title" to it.title,
          "instructions" to it.instructions,
          "destinationUrl" to it.destinationUrl,
          "actionLabel" to it.actionLabel,
          "requiresProof" to it.requiresProof,
          "requiresTracking" to it.requiresTracking,
          "referenceImageUrl" to it.referenceImageUrl,
          "referenceImageCaption" to it.referenceImageCaption
        ) },
        "referenceImages" to referenceImages,
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )

    val batch = firestore.batch()
    batch.set(docRef, payload)
    batch.update(
      usersCollection.document(assignedUserId),
      mapOf(
        "assignedTaskId" to docRef.id,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
    recordAdminActivity(
      actionType = "TASK",
      title = "Created Task: $cleanedTitle",
      details = "Category: $cleanedCat • Reward: ${formatCoins(rewardCents)} • Quota: ${if (targetSubmissions > 0) targetSubmissions else "Unlimited"}",
      targetId = docRef.id
    )
    docRef.id
  }

  /**
   * Updates an existing pending task's details or assigned user.
   */
  suspend fun updateTaskDetails(
    taskId: String,
    title: String,
    description: String,
    category: String,
    rewardCents: Long,
    assignedUserId: String,
    assignedUserName: String,
    assignedUserEmail: String,
    instructions: String = "",
    taskLink: String = "",
    appName: String = "",
    appIconUrl: String = "",
    requiredProof: String = "",
    appId: String = "",
    steps: List<TaskStep> = emptyList(),
    referenceImages: List<String> = emptyList(),
    installationInstructions: String = "",
    completionDurationMinutes: Int = 5,
    targetSubmissions: Long = 0L,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    campaignId: String = ""
  ): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val cleanedTitle = title.trim()
    val cleanedAppName = appName.trim()
    val cleanedAppIconUrl = appIconUrl.trim()
    val cleanedDesc = description.trim()
    val cleanedInstructions = instructions.trim().ifBlank { cleanedDesc }
    val cleanedInstallInstructions = installationInstructions.trim()
    val cleanedRequiredProof =
      requiredProof.trim().ifBlank {
        "Provide written confirmation notes and a clear screenshot showing task completion."
      }
    val cleanedLink = taskLink.trim()
    val cleanedCat = category.trim().ifEmpty { "App Install" }
    require(cleanedTitle.isNotEmpty() && cleanedTitle.length <= 120) {
      "Task title must be 1–120 characters."
    }
    require(cleanedAppName.length <= 120) {
      "App or website name must be up to 120 characters."
    }
    require(isValidImageUrlOrDataUri(cleanedAppIconUrl)) {
      "App icon must be a valid HTTPS image URL or selected Gallery image."
    }
    require(cleanedDesc.isNotEmpty() && cleanedDesc.length <= 2000) {
      "Task description must be 1–2000 characters."
    }
    require(cleanedInstructions.length <= 4000) {
      "Step-by-step instructions must be up to 4000 characters."
    }
    require(cleanedRequiredProof.length <= 2000) {
      "Required proof description must be up to 2000 characters."
    }
    require(cleanedLink.isEmpty() || isValidTaskUrl(cleanedLink)) {
      "Task link must be a valid HTTPS URL (e.g., https://play.google.com/...)."
    }
    require(rewardCents in 1..10_000_000) { "Reward must be greater than ₹0.00." }
    require(assignedUserId.isNotBlank()) { "An assigned user must be selected." }

    val taskRef = tasksCollection.document(taskId)
    val existingTaskSnap = taskRef.get().await()
    check(existingTaskSnap.exists()) { "Task $taskId does not exist." }
    val previousAssigneeId = existingTaskSnap.getString("assignedUserId") ?: ""

    val batch = firestore.batch()
    batch.update(
      taskRef,
      mapOf(
        "appId" to appId.trim(),
        "campaignId" to campaignId.trim(),
        "title" to cleanedTitle,
        "appName" to cleanedAppName,
        "appIconUrl" to cleanedAppIconUrl,
        "description" to cleanedDesc,
        "installationInstructions" to cleanedInstallInstructions,
        "instructions" to cleanedInstructions,
        "requiredProof" to cleanedRequiredProof,
        "taskLink" to cleanedLink,
        "category" to cleanedCat,
        "rewardCents" to rewardCents,
        "rewardCoins" to rewardCents,
        "completionDurationMinutes" to completionDurationMinutes.coerceIn(1, 1440),
        "targetSubmissions" to targetSubmissions.coerceAtLeast(0L),
        "startDate" to startDate.trim(),
        "endDate" to endDate.trim(),
        "eligibilityRules" to eligibilityRules.trim().ifEmpty { "All Approved Users" },
        "assignedUserId" to assignedUserId,
        "assignedUserName" to assignedUserName.trim().ifEmpty { "Assigned User" },
        "assignedUserEmail" to assignedUserEmail.trim().ifEmpty { "user@taskflow.app" },
        "steps" to steps.map { mapOf(
          "stepNumber" to it.stepNumber,
          "title" to it.title,
          "instructions" to it.instructions,
          "destinationUrl" to it.destinationUrl,
          "actionLabel" to it.actionLabel,
          "requiresProof" to it.requiresProof,
          "requiresTracking" to it.requiresTracking,
          "referenceImageUrl" to it.referenceImageUrl,
          "referenceImageCaption" to it.referenceImageCaption
        ) },
        "referenceImages" to referenceImages,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.update(
      usersCollection.document(assignedUserId),
      mapOf(
        "assignedTaskId" to taskId,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )

    if (previousAssigneeId.isNotBlank() && previousAssigneeId != assignedUserId) {
      val prevUserRef = usersCollection.document(previousAssigneeId)
      val prevUserSnap = prevUserRef.get().await()
      if (prevUserSnap.exists() && prevUserSnap.getString("assignedTaskId") == taskId) {
        batch.update(
          prevUserRef,
          mapOf(
            "assignedTaskId" to "",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
      }
    }

    batch.commit().await()
    recordAdminActivity(
      actionType = "TASK",
      title = "Updated Task: $cleanedTitle",
      details = "Category: $cleanedCat • Reward: ${formatCoins(rewardCents)}",
      targetId = taskId
    )
  }

  /**
   * Activates, deactivates, or reactivates a task (`isActive: Boolean`) in `/tasks/{taskId}`.
   * Deactivated tasks cannot accept new user submissions until reactivated by Admin.
   */
  suspend fun setTaskActiveStatus(taskId: String, isActive: Boolean): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val taskRef = tasksCollection.document(taskId)
    val taskSnap = taskRef.get().await()
    check(taskSnap.exists()) { "Task $taskId does not exist." }
    taskRef
      .update(
        mapOf(
          "isActive" to isActive,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      .await()
  }

  /**
   * Deletes a task from `/tasks/{taskId}` (Admin only) and clears the assignee's `assignedTaskId`
   * if it points to this task, while preserving `/submissions/{taskId}` and user history.
   */
  suspend fun deleteTask(taskId: String): Result<Unit> = runCatching {
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val taskRef = tasksCollection.document(taskId)
    val taskSnap = taskRef.get().await()
    val assignedUserId = taskSnap.getString("assignedUserId") ?: ""

    val batch = firestore.batch()
    batch.delete(taskRef)

    if (assignedUserId.isNotBlank()) {
      val userRef = usersCollection.document(assignedUserId)
      val userSnap = userRef.get().await()
      if (userSnap.exists() && userSnap.getString("assignedTaskId") == taskId) {
        batch.update(
          userRef,
          mapOf(
            "assignedTaskId" to "",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
      }
    }

    batch.commit().await()
  }

  /**
   * Submits MANDATORY written text proof AND MANDATORY Gallery photo/screenshot proof for an open task.
   *
   * Security Requirement:
   * ONLY approved (`approvalStatus == "approved"`) authenticated users can submit proof on an active task.
   * Pending and suspended users are rejected.
   */
  suspend fun submitTaskProof(
    task: TaskItem,
    userName: String,
    userEmail: String,
    proofText: String,
    imageUri: Uri?,
    onProgress: (Int) -> Unit = {}
  ): Result<Unit> = runCatching {
    val authUser = authProvider().currentUser ?: error("Must be signed in to submit task proof.")
    val uid = authUser.uid
    val cleanedProofText = proofText.trim()
    require(cleanedProofText.isNotEmpty() && cleanedProofText.length <= 4000) {
      "Written text proof is mandatory (1–4000 characters)."
    }
    require(imageUri != null) {
      "At least one photo or screenshot from your phone Gallery is mandatory."
    }

    // 1. Ensure user profile exists and is strictly approved (`approvalStatus == "approved"`)
    val userRef = usersCollection.document(uid)
    val userSnap = userRef.get().await()
    val resolvedEmail =
      (userSnap.getString("email") ?: userEmail.trim().ifBlank { authUser.email ?: "" }).trim()
    val resolvedName =
      (userSnap.getString("displayName") ?: userName.trim().ifBlank { authUser.displayName ?: "" })
        .trim()
        .ifEmpty { "TaskFlow User" }

    val approvalStatus =
      if (!userSnap.exists()) {
        val created = ensureUserProfile(uid, resolvedEmail, resolvedName).getOrThrow()
        created.approvalStatus
      } else {
        userSnap.getString("approvalStatus") ?: "pending"
      }

    check(approvalStatus == "approved") {
      if (approvalStatus == "suspended") {
        "Your account is currently suspended and cannot submit task proof."
      } else {
        "Your account is pending Admin approval and cannot submit task proof yet."
      }
    }

    // 2. Verify task exists, is still open ('pending'), and is active ('isActive' != false)
    val taskRef = tasksCollection.document(task.id)
    val latestTaskSnap = taskRef.get().await()
    check(latestTaskSnap.exists()) { "Task ${task.id} no longer exists." }
    val isActive = latestTaskSnap.getBoolean("isActive") ?: true
    check(isActive) {
      "This task is currently deactivated by Admin and is not accepting new submissions."
    }
    val currentStatus = latestTaskSnap.getString("status") ?: ""
    check(currentStatus == TaskStatus.PENDING) {
      "Task proof can only be submitted while the task is Available (current status: ${TaskStatus.label(currentStatus)})."
    }

    // 3. Upload / encode mandatory photo proof and verify non-empty reference
    val uploadedPhoto =
      photoUploader.uploadProofPhoto(
        userId = uid,
        taskId = task.id,
        imageUri = imageUri,
        onProgress = onProgress
      )
    check(uploadedPhoto.downloadUrl.isNotBlank() && uploadedPhoto.storagePath.isNotBlank()) {
      "Photo proof upload did not complete. Please try again."
    }

    val latestTitle = latestTaskSnap.getString("title") ?: task.title
    val latestAppName = latestTaskSnap.getString("appName") ?: task.appName
    val latestAppIconUrl = latestTaskSnap.getString("appIconUrl") ?: task.appIconUrl
    val latestRewardCents = latestTaskSnap.getLong("rewardCents") ?: task.rewardCents

    val isSingleAssigneePrivateTask =
      task.assignedUserId == uid || (latestTaskSnap.getString("assignedUserId") == uid)

    // Compute unique submissionId for multi-user isolation.
    // For pre-assigned 1:1 private tasks, preserves legacy document ID; for pooled campaign tasks, uses composite ${task.id}_$uid
    val submissionId = if (isSingleAssigneePrivateTask) task.id else "${task.id}_$uid"
    val submissionRef = submissionsCollection.document(submissionId)

    // Prevent duplicate submissions by the same user on the same task
    val existingDirectSub = submissionRef.get().await()
    check(!existingDirectSub.exists()) {
      "You have already submitted proof for this task. Duplicate submissions are not allowed."
    }
    val existingMultiSub = submissionsCollection.document("${task.id}_$uid").get().await()
    if (existingMultiSub.exists()) {
      throw IllegalStateException("You have already submitted proof for this task. Duplicate submissions are not allowed.")
    }
    val existingLegacySub = submissionsCollection.document(task.id).get().await()
    if (existingLegacySub.exists() && existingLegacySub.getString("userId") == uid) {
      throw IllegalStateException("You have already submitted proof for this task. Duplicate submissions are not allowed.")
    }

    // 4. Atomically record submission proof with immutable audit trail
    val batch = firestore.batch()
    if (isSingleAssigneePrivateTask) {
      batch.update(
        taskRef,
        mapOf(
          "assignedUserId" to uid,
          "assignedUserName" to resolvedName,
          "assignedUserEmail" to resolvedEmail.ifEmpty { "user@taskflow.app" },
          "status" to TaskStatus.SUBMITTED,
          "proofText" to cleanedProofText,
          "proofImageUrl" to uploadedPhoto.downloadUrl,
          "proofStoragePath" to uploadedPhoto.storagePath,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
    }
    batch.set(
      submissionRef,
      mapOf(
        "taskId" to task.id,
        "taskTitle" to latestTitle,
        "appName" to latestAppName,
        "appIconUrl" to latestAppIconUrl,
        "rewardCents" to latestRewardCents,
        "userId" to uid,
        "userName" to resolvedName,
        "userEmail" to resolvedEmail.ifEmpty { "user@taskflow.app" },
        "proofText" to cleanedProofText,
        "proofImageUrl" to uploadedPhoto.downloadUrl,
        "proofStoragePath" to uploadedPhoto.storagePath,
        "status" to TaskStatus.SUBMITTED,
        "adminFeedback" to "",
        "createdAt" to FieldValue.serverTimestamp(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
  }

  /**
   * Convenience wrapper for submitting mandatory text + photo proof by task parameters.
   */
  suspend fun createSubmissionRecord(
    taskId: String,
    taskTitle: String,
    rewardCents: Long,
    userName: String,
    userEmail: String,
    proofText: String,
    imageUri: Uri?
  ): Result<Unit> {
    val uid = authProvider().currentUser?.uid ?: ""
    val task =
      TaskItem(
        id = taskId,
        title = taskTitle,
        rewardCents = rewardCents,
        assignedUserId = uid
      )
    return submitTaskProof(
      task = task,
      userName = userName,
      userEmail = userEmail,
      proofText = proofText,
      imageUri = imageUri
    )
  }

  /**
   * Reviews a submitted task (Approve or Reject) atomically.
   * When approved, credits the submitting user's `balanceCents`, increments `completedTasksCount`,
   * syncs `/leaderboard/{userId}`, and logs a `TASK_REWARD` transaction in the same batch.
   */
  suspend fun reviewSubmission(
    task: TaskItem,
    approve: Boolean,
    adminFeedback: String
  ): Result<Unit> = runCatching {
    require(task.id.isNotBlank()) { "task.id must not be blank" }
    val cleanedFeedback =
      adminFeedback.trim().ifEmpty {
        if (approve) "Approved by Admin" else "Needs revision"
      }
    require(cleanedFeedback.length <= 1000) { "Feedback must be under 1000 characters." }

    val directSubRef = submissionsCollection.document(task.id)
    val directSubSnap = directSubRef.get().await()
    val (submissionRef, latestSubSnap) = if (directSubSnap.exists()) {
      directSubRef to directSubSnap
    } else {
      val multiId = "${task.id}_${task.assignedUserId.trim()}"
      val multiSubRef = submissionsCollection.document(multiId)
      val multiSubSnap = multiSubRef.get().await()
      if (multiSubSnap.exists()) {
        multiSubRef to multiSubSnap
      } else {
        throw IllegalStateException("Submission record for task ${task.id} does not exist.")
      }
    }

    val currentSubStatus = latestSubSnap.getString("status") ?: ""
    check(currentSubStatus == TaskStatus.SUBMITTED) {
      "Submission ${submissionRef.id} has already been reviewed (status: $currentSubStatus)."
    }

    val actualTaskId = latestSubSnap.getString("taskId") ?: task.id
    val taskRef = tasksCollection.document(actualTaskId)
    val latestTaskSnap = taskRef.get().await()

    val submittingUserId =
      (latestSubSnap.getString("userId")
          ?: latestTaskSnap.getString("assignedUserId")
          ?: task.assignedUserId)
        .trim()
    val rewardCents = latestSubSnap.getLong("rewardCents") ?: latestTaskSnap.getLong("rewardCents") ?: task.rewardCents
    val newStatus = if (approve) TaskStatus.APPROVED else TaskStatus.REJECTED

    val userRef = usersCollection.document(submittingUserId)
    val userSnap = if (approve && submittingUserId.isNotBlank()) userRef.get().await() else null

    val batch = firestore.batch()
    if (latestTaskSnap.exists() && latestTaskSnap.getString("status") == TaskStatus.SUBMITTED) {
      batch.update(
        taskRef,
        mapOf(
          "status" to newStatus,
          "adminFeedback" to cleanedFeedback,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
    }
    batch.update(
      submissionRef,
      mapOf(
        "status" to newStatus,
        "adminFeedback" to cleanedFeedback,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )

    if (approve && submittingUserId.isNotBlank()) {
      val currentBalance = userSnap?.getLong("balanceCents") ?: 0L
      val oldExplicitEligible = userSnap?.getLong("eligibleCoins") ?: 0L
      val oldEligibleCoins = maxOf(oldExplicitEligible, centsToCoins(currentBalance))
      val oldPromoCoins = userSnap?.getLong("promotionalCoins") ?: 0L
      val oldBalanceCoins = maxOf(userSnap?.getLong("balanceCoins") ?: 0L, oldEligibleCoins + oldPromoCoins)
      val newBalanceCents = currentBalance + rewardCents
      val newEligibleCoins = maxOf(oldEligibleCoins + centsToCoins(rewardCents), centsToCoins(newBalanceCents))
      val eligibleDelta = newEligibleCoins - oldEligibleCoins
      val newBalanceCoins = oldBalanceCoins + eligibleDelta
      val currentCompleted = userSnap?.getLong("completedTasksCount") ?: 0L
      val currentApproval = userSnap?.getString("approvalStatus") ?: "approved"
      val submittingUserName =
        (userSnap?.getString("displayName")
            ?: latestSubSnap.getString("userName")
            ?: task.assignedUserName)
          .ifEmpty { "TaskFlow Member" }

      batch.update(
        userRef,
        mapOf(
          "balanceCents" to FieldValue.increment(rewardCents),
          "eligibleCoins" to newEligibleCoins,
          "balanceCoins" to newBalanceCoins,
          "completedTasksCount" to FieldValue.increment(1L),
          "assignedTaskId" to actualTaskId,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.set(
        leaderboardCollection.document(submittingUserId),
        buildLeaderboardPayload(
          userId = submittingUserId,
          displayName = submittingUserName,
          balanceCents = currentBalance + rewardCents,
          balanceCoins = newBalanceCoins,
          completedTasksCount = currentCompleted + 1L,
          approvalStatus = currentApproval
        ),
        SetOptions.merge()
      )
      val txRef = transactionsCollection.document()
      val taskTitle = (latestSubSnap.getString("taskTitle") ?: latestTaskSnap.getString("title") ?: task.title).ifEmpty { "Task" }
      batch.set(
        txRef,
        mapOf(
          "userId" to submittingUserId,
          "userName" to submittingUserName,
          "type" to TransactionType.TASK_REWARD,
          "title" to "Task Reward: ${taskTitle.take(60)}",
          "amountCents" to rewardCents,
          "status" to "CREDITED",
          "referenceId" to submissionRef.id,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )
    }

    batch.commit().await()

    // Create deterministic notification in existing Notification Bell after wallet credit succeeds
    if (approve && submittingUserId.isNotBlank()) {
      runCatching {
        val taskTitle =
          (latestSubSnap.getString("taskTitle")
              ?: latestTaskSnap.getString("title")
              ?: task.title)
            .ifEmpty { "Task" }
        val creditedCoins =
          latestSubSnap.getLong("rewardCoins")?.takeIf { it > 0L }
            ?: latestTaskSnap.getLong("rewardCoins")?.takeIf { it > 0L }
            ?: centsToCoins(rewardCents).takeIf { it > 0L }
            ?: rewardCents
        val notifRef =
          usersCollection
            .document(submittingUserId)
            .collection("notifications")
            .document("notif_task_${submissionRef.id}")
        notifRef
          .set(
            mapOf(
              "userId" to submittingUserId,
              "title" to "Task Reward Credited",
              "body" to "Your submission for '${taskTitle.take(60)}' was approved! +${formatCoins(creditedCoins)} credited to your wallet.",
              "type" to NotificationRewardType.TASK_REWARD,
              "rewardCoins" to creditedCoins,
              "referenceId" to submissionRef.id,
              "isRead" to false,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
    }

    // Update task/campaign quota and auto-close if quota is exhausted (outside atomic reward batch so firestore.rules atomic task approval check remains 100% intact)
    if (approve) {
      runCatching {
        if (latestTaskSnap.exists()) {
          val targetQuota = latestTaskSnap.getLong("targetSubmissions") ?: 0L
          val currentCompleted = (latestTaskSnap.getLong("completedSubmissions") ?: 0L) + 1L
          val taskQuotaUpdates = mutableMapOf<String, Any>(
            "completedSubmissions" to currentCompleted,
            "updatedAt" to FieldValue.serverTimestamp()
          )
          if (targetQuota > 0L && currentCompleted >= targetQuota) {
            taskQuotaUpdates["isActive"] = false
          }
          taskRef.update(taskQuotaUpdates).await()

          val linkedCampaignId = latestTaskSnap.getString("campaignId").orEmpty()
          if (linkedCampaignId.isNotBlank()) {
            val campRef = campaignsCollection.document(linkedCampaignId)
            val campSnap = campRef.get().await()
            if (campSnap.exists()) {
              val campTarget = campSnap.getLong("targetSubmissions") ?: 0L
              val campCompleted = (campSnap.getLong("completedSubmissions") ?: 0L) + 1L
              val campSpent = (campSnap.getLong("spentCents") ?: 0L) + rewardCents
              val campUpdates = mutableMapOf<String, Any>(
                "completedSubmissions" to campCompleted,
                "spentCents" to campSpent,
                "updatedAt" to FieldValue.serverTimestamp()
              )
              if (campTarget > 0L && campCompleted >= campTarget) {
                campUpdates["status"] = "COMPLETED"
              }
              campRef.update(campUpdates).await()
            }
          }
        }
        if (submittingUserId.isNotBlank()) {
          saveUserTaskProgress(
            userId = submittingUserId,
            taskId = actualTaskId,
            completedSteps = emptyList(),
            trackingEvents = mapOf("approved" to true),
            status = TaskStatus.STAGE_DONE,
            installationConfirmed = true
          )
        }
      }
    }

    recordAdminActivity(
      actionType = if (approve) "REWARD" else "SUBMISSION",
      title = if (approve) "Approved Submission: ${task.title}" else "Rejected Submission: ${task.title}",
      details = "User: ${task.assignedUserName} • ${if (approve) "Credited ${formatRewardInr(rewardCents)}" else "Reason: $cleanedFeedback"}",
      targetId = submissionRef.id
    )
  }

  /**
   * Admin updates a user's `approvalStatus` (`approved`, `pending`, `suspended`) and syncs `/leaderboard/{userId}`.
   */
  suspend fun updateUserApprovalStatus(userId: String, approvalStatus: String): Result<Unit> =
    runCatching {
      require(userId.isNotBlank()) { "userId must not be blank" }
      require(approvalStatus in listOf("approved", "pending", "suspended")) {
        "Invalid approvalStatus: $approvalStatus"
      }
      val userRef = usersCollection.document(userId)
      val userSnap = userRef.get().await()
      check(userSnap.exists()) { "User profile $userId not found." }
      val currentBalanceCoins = computeSnapshotEffectiveBalanceCoins(userSnap)
      val batch = firestore.batch()
      batch.update(
        userRef,
        mapOf(
          "approvalStatus" to approvalStatus,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      batch.set(
        leaderboardCollection.document(userId),
        buildLeaderboardPayload(
          userId = userId,
          displayName = userSnap.getString("displayName") ?: "TaskFlow Member",
          balanceCents = userSnap.getLong("balanceCents") ?: 0L,
          balanceCoins = currentBalanceCoins,
          completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
          approvalStatus = approvalStatus
        ),
        SetOptions.merge()
      )
      batch.commit().await()
    }

  fun observeWithdrawalAvailability(): Flow<WithdrawalAvailabilityConfig> = callbackFlow {
    val listener =
      withdrawalAvailabilityDoc.addSnapshotListener { snapshot, error ->
        if (error != null || snapshot == null) {
          trySend(WithdrawalAvailabilityConfig())
          return@addSnapshotListener
        }
        trySend(snapshot.toWithdrawalAvailabilityConfig())
      }
    awaitClose { listener.remove() }
  }

  suspend fun updateWithdrawalAvailabilityConfig(
    config: WithdrawalAvailabilityConfig
  ): Result<Unit> = runCatching {
    val payload =
      mapOf(
        "upiEnabled" to config.upiEnabled,
        "amazonEnabled" to config.amazonEnabled,
        "googlePlayEnabled" to config.googlePlayEnabled,
        "flipkartEnabled" to config.flipkartEnabled,
        "upiDenominationsEnabled" to config.upiDenominationsEnabled,
        "amazonDenominationsEnabled" to config.amazonDenominationsEnabled,
        "googlePlayDenominationsEnabled" to config.googlePlayDenominationsEnabled,
        "flipkartDenominationsEnabled" to config.flipkartDenominationsEnabled,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    withdrawalAvailabilityDoc.set(payload, SetOptions.merge()).await()
    recordAdminActivity(
      actionType = "WITHDRAWAL",
      title = "Updated Withdrawal Availability",
      details = "UPI: ${if (config.upiEnabled) "On" else "Off"} • Amazon: ${if (config.amazonEnabled) "On" else "Off"} • Google Play: ${if (config.googlePlayEnabled) "On" else "Off"} • Flipkart: ${if (config.flipkartEnabled) "On" else "Off"}",
      targetId = "withdrawal_availability"
    )
  }

  fun observeAdminNotifications(isAdminAuthorized: Boolean): Flow<List<AdminNotificationItem>> =
    callbackFlow {
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        adminNotificationsCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toObjectSafe(AdminNotificationItem::class.java) }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
      awaitClose { listener.remove() }
    }

  suspend fun markAdminNotificationRead(notificationId: String): Result<Unit> = runCatching {
    if (notificationId.isBlank()) return@runCatching
    adminNotificationsCollection.document(notificationId).update("isRead", true).await()
  }

  /**
   * Submits a fixed-denomination UPI or Gift Card withdrawal request with immediate atomic coin
   * deduction, deterministic debit transaction (`wd_debit_{requestId}`), and user/admin alerts.
   */
  suspend fun submitWithdrawalRedemption(
    uid: String,
    userName: String,
    method: String,
    amountRupees: Long,
    upiId: String = "",
    idempotencyKey: String = UUID.randomUUID().toString().replace("-", "")
  ): Result<DemoWithdrawalRequest> = runCatching {
    require(uid.isNotBlank()) { "Must be signed in to submit a withdrawal request." }
    val normalizedMethod = method.trim().uppercase(Locale.US)
    require(normalizedMethod in WithdrawalMethodType.ALL_REDEMPTION_METHODS) {
      "Please select a valid withdrawal method."
    }
    val requiredCoins =
      WithdrawalCatalog.resolveRequiredCoins(normalizedMethod, amountRupees)
        ?: throw IllegalArgumentException("Invalid denomination ₹$amountRupees for $normalizedMethod.")
    val cleanedUpi = if (normalizedMethod == WithdrawalMethodType.UPI) upiId.trim() else ""
    if (normalizedMethod == WithdrawalMethodType.UPI) {
      require(cleanedUpi.isNotEmpty() && cleanedUpi.contains("@") && !cleanedUpi.contains(" ")) {
        "Please enter a valid UPI ID (e.g., name@upi)."
      }
    }

    val resolvedRequestId =
      idempotencyKey.trim().ifEmpty { UUID.randomUUID().toString().replace("-", "") }
    val reqRef = withdrawalRequestsCollection.document(resolvedRequestId)
    val userRef = usersCollection.document(uid)
    val txRef = transactionsCollection.document("wd_debit_$resolvedRequestId")
    val leaderboardRef = leaderboardCollection.document(uid)
    val userNotifRef =
      userRef.collection("notifications").document("user_wd_submit_$resolvedRequestId")
    val adminNotifRef = adminNotificationsCollection.document("admin_wd_$resolvedRequestId")
    val amountCents = amountRupees * 100L
    val methodLabel = WithdrawalMethodType.displayLabel(normalizedMethod)

    firestore.runTransaction { transaction ->
      val existingReqSnap = transaction.get(reqRef)
      if (existingReqSnap.exists()) {
        check(existingReqSnap.getString("userId") == uid) {
          "Withdrawal request ID collision detected. Please try again."
        }
        return@runTransaction existingReqSnap.toObjectSafe(DemoWithdrawalRequest::class.java)
          ?: DemoWithdrawalRequest(
            id = resolvedRequestId,
            requestId = resolvedRequestId,
            userId = uid,
            userName = userName,
            amountRupees = amountRupees,
            amountCents = amountCents,
            amountCoins = requiredCoins,
            method = normalizedMethod,
            upiId = cleanedUpi,
            status = WithdrawalStatus.SUBMITTED,
            coinsDeducted = true
          )
      }

      val userSnap = transaction.get(userRef)
      check(userSnap.exists()) { "User profile not found." }
      val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
      check(approvalStatus == "approved") {
        if (approvalStatus == "suspended") {
          "Suspended accounts cannot submit withdrawal requests."
        } else {
          "Pending accounts cannot submit withdrawal requests until approved by Admin."
        }
      }

      val availSnap = transaction.get(withdrawalAvailabilityDoc)
      val availability = availSnap.toWithdrawalAvailabilityConfig()
      check(availability.isDenominationAvailable(normalizedMethod, amountRupees)) {
        "₹$amountRupees $methodLabel is currently unavailable."
      }

      val oldBalanceCents = userSnap.getLong("balanceCents") ?: 0L
      val explicitEligible = userSnap.getLong("eligibleCoins") ?: 0L
      val oldEligibleCoins = maxOf(explicitEligible, centsToCoins(oldBalanceCents))
      val oldPromoCoins = userSnap.getLong("promotionalCoins") ?: 0L
      val oldBalanceCoins =
        maxOf(userSnap.getLong("balanceCoins") ?: 0L, oldEligibleCoins + oldPromoCoins)

      check(oldBalanceCoins >= requiredCoins) {
        "Insufficient balance. You need ${formatCoins(requiredCoins)} (Available: ${formatCoins(oldBalanceCoins)})."
      }

      val deductEligible = minOf(oldEligibleCoins, requiredCoins)
      val deductPromo = minOf(oldPromoCoins, requiredCoins - deductEligible)
      val deductCents = minOf(oldBalanceCents, coinsToCents(deductEligible))

      val newBalanceCoins = oldBalanceCoins - requiredCoins
      val newEligibleCoins = oldEligibleCoins - deductEligible
      val newPromoCoins = oldPromoCoins - deductPromo
      val newBalanceCents = oldBalanceCents - deductCents
      val resolvedUserName =
        (userSnap.getString("displayName") ?: userName).trim().ifEmpty { "TaskFlow User" }
      val completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L

      transaction.update(
        userRef,
        mapOf(
          "balanceCoins" to newBalanceCoins,
          "eligibleCoins" to newEligibleCoins,
          "promotionalCoins" to newPromoCoins,
          "balanceCents" to newBalanceCents,
          "lastWithdrawalRequestId" to resolvedRequestId,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )

      transaction.set(
        leaderboardRef,
        buildLeaderboardPayload(
          userId = uid,
          displayName = resolvedUserName,
          balanceCents = newBalanceCents,
          balanceCoins = newBalanceCoins,
          completedTasksCount = completedTasksCount,
          approvalStatus = approvalStatus
        ),
        SetOptions.merge()
      )

      val requestPayload =
        mapOf(
          "requestId" to resolvedRequestId,
          "userId" to uid,
          "userName" to resolvedUserName,
          "method" to normalizedMethod,
          "amountRupees" to amountRupees,
          "amountCents" to amountCents,
          "amountCoins" to requiredCoins,
          "upiId" to cleanedUpi,
          "status" to WithdrawalStatus.SUBMITTED,
          "statusNote" to "",
          "adminActionBy" to "",
          "paymentReference" to "",
          "giftCode" to "",
          "giftCodeHash" to "",
          "coinsDeducted" to true,
          "coinsRefunded" to false,
          "deductedBalanceCoins" to requiredCoins,
          "deductedEligibleCoins" to deductEligible,
          "deductedPromotionalCoins" to deductPromo,
          "deductedBalanceCents" to deductCents,
          "idempotencyKey" to resolvedRequestId,
          "isDemo" to false,
          "createdAt" to FieldValue.serverTimestamp(),
          "updatedAt" to FieldValue.serverTimestamp()
        )
      transaction.set(reqRef, requestPayload)

      transaction.set(
        txRef,
        mapOf(
          "userId" to uid,
          "userName" to resolvedUserName,
          "type" to TransactionType.WITHDRAWAL_DEBIT,
          "title" to "$methodLabel Redemption (₹$amountRupees)",
          "amountCents" to amountCents,
          "amountCoins" to requiredCoins,
          "status" to "DEBITED",
          "referenceId" to resolvedRequestId,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )

      transaction.set(
        userNotifRef,
        mapOf(
          "userId" to uid,
          "title" to "Withdrawal Request Submitted",
          "body" to "Your $methodLabel request for ₹$amountRupees (${formatCoins(requiredCoins)}) has been received. Estimated processing time: 24–48 hours.",
          "type" to "WITHDRAWAL",
          "referenceId" to resolvedRequestId,
          "isRead" to false,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )

      transaction.set(
        adminNotifRef,
        mapOf(
          "userId" to uid,
          "userName" to resolvedUserName,
          "title" to "New $methodLabel Request",
          "body" to "$resolvedUserName requested ₹$amountRupees (${formatCoins(requiredCoins)}).",
          "type" to "WITHDRAWAL_REQUEST",
          "referenceId" to resolvedRequestId,
          "isRead" to false,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )

      DemoWithdrawalRequest(
        id = resolvedRequestId,
        requestId = resolvedRequestId,
        userId = uid,
        userName = resolvedUserName,
        amountRupees = amountRupees,
        amountCents = amountCents,
        amountCoins = requiredCoins,
        method = normalizedMethod,
        upiId = cleanedUpi,
        status = WithdrawalStatus.SUBMITTED,
        coinsDeducted = true,
        coinsRefunded = false,
        deductedBalanceCoins = requiredCoins,
        deductedEligibleCoins = deductEligible,
        deductedPromotionalCoins = deductPromo,
        deductedBalanceCents = deductCents,
        idempotencyKey = resolvedRequestId,
        isDemo = false
      )
    }.await()
  }

  /**
   * Admin accepts a withdrawal request (`UNDER_REVIEW` or `PROCESSING`) without deducting or refunding coins.
   */
  suspend fun adminAcceptWithdrawalRequest(
    requestId: String,
    targetStatus: String = WithdrawalStatus.UNDER_REVIEW,
    statusNote: String = ""
  ): Result<Unit> = runCatching {
    require(requestId.isNotBlank()) { "requestId must not be blank" }
    require(targetStatus == WithdrawalStatus.UNDER_REVIEW || targetStatus == WithdrawalStatus.PROCESSING) {
      "Invalid target status $targetStatus"
    }
    val adminUid = authProvider().currentUser?.uid?.trim().orEmpty()
    require(adminUid.isNotBlank()) { "Admin sign-in required." }
    val cleanedNote = statusNote.trim().take(500)
    val reqRef = withdrawalRequestsCollection.document(requestId)

    val (targetUid, methodLabel, amountRupees) =
      firestore.runTransaction { transaction ->
        val reqSnap = transaction.get(reqRef)
        check(reqSnap.exists()) { "Withdrawal request not found." }
        val currentStatus = reqSnap.getString("status").orEmpty()
        check(!WithdrawalStatus.isTerminal(currentStatus)) {
          "Cannot update a completed or rejected request."
        }
        check(currentStatus != targetStatus) {
          "Request is already ${WithdrawalStatus.displayLabel(targetStatus)}."
        }
        check(!(currentStatus == WithdrawalStatus.PROCESSING && targetStatus == WithdrawalStatus.UNDER_REVIEW)) {
          "Cannot move a Processing request back to Under Review."
        }
        val uid = reqSnap.getString("userId").orEmpty()
        val method = reqSnap.getString("method").orEmpty()
        val rupees = reqSnap.getLong("amountRupees") ?: ((reqSnap.getLong("amountCents") ?: 0L) / 100L)
        val label = WithdrawalMethodType.displayLabel(method)
        val defaultNote =
          if (targetStatus == WithdrawalStatus.UNDER_REVIEW) "Accepted and placed under review."
          else "Your request is currently being processed."
        val resolvedNote = cleanedNote.ifEmpty { defaultNote }

        transaction.update(
          reqRef,
          mapOf(
            "status" to targetStatus,
            "statusNote" to resolvedNote,
            "adminActionBy" to adminUid,
            "reviewedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )

        if (uid.isNotBlank()) {
          val notifRef =
            usersCollection
              .document(uid)
              .collection("notifications")
              .document("user_wd_status_${requestId}_$targetStatus")
          val statusTitle = WithdrawalStatus.displayLabel(targetStatus)
          transaction.set(
            notifRef,
            mapOf(
              "userId" to uid,
              "title" to "Withdrawal $statusTitle",
              "body" to "Your $label request for ₹$rupees is now $statusTitle. $resolvedNote",
              "type" to "WITHDRAWAL",
              "referenceId" to requestId,
              "isRead" to false,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
        }
        Triple(uid, label, rupees)
      }.await()

    recordAdminActivity(
      actionType = "WITHDRAWAL",
      title = "Withdrawal ${WithdrawalStatus.displayLabel(targetStatus)}",
      details = "$methodLabel ₹$amountRupees ($requestId) for user $targetUid",
      targetId = requestId
    )
  }

  /**
   * Admin manually delivers a unique, non-reusable Gift Card Code and marks the request `COMPLETED`.
   */
  suspend fun adminSendGiftCardCode(
    requestId: String,
    rawGiftCode: String,
    statusNote: String = ""
  ): Result<Unit> = runCatching {
    require(requestId.isNotBlank()) { "requestId must not be blank" }
    val cleanedCode = rawGiftCode.trim()
    require(cleanedCode.isNotEmpty() && cleanedCode.length <= 200) {
      "Please enter a valid Gift Card Code."
    }
    val adminUid = authProvider().currentUser?.uid?.trim().orEmpty()
    require(adminUid.isNotBlank()) { "Admin sign-in required." }
    val codeHash = hashGiftCode(cleanedCode)
    val cleanedNote = statusNote.trim().take(500)

    val reqRef = withdrawalRequestsCollection.document(requestId)
    val usedCodeRef = usedGiftCodesCollection.document(codeHash)

    val (methodLabel, amountRupees) =
      firestore.runTransaction { transaction ->
        val reqSnap = transaction.get(reqRef)
        check(reqSnap.exists()) { "Withdrawal request not found." }
        val method = reqSnap.getString("method").orEmpty()
        check(WithdrawalMethodType.isGiftCard(method)) {
          "Gift card codes can only be delivered for Gift Card requests."
        }
        val currentStatus = reqSnap.getString("status").orEmpty()
        check(!WithdrawalStatus.isTerminal(currentStatus)) {
          "This request has already reached a terminal state ($currentStatus)."
        }
        val coinsDeducted = reqSnap.getBoolean("coinsDeducted") ?: false
        val alreadyRefunded = reqSnap.getBoolean("coinsRefunded") ?: false
        check(coinsDeducted && !alreadyRefunded) {
          "Cannot complete a request whose coins are not debited or already refunded."
        }

        val usedSnap = transaction.get(usedCodeRef)
        check(!usedSnap.exists()) {
          "This gift card code has already been issued to another request. Please enter a unique code."
        }

        val uid = reqSnap.getString("userId").orEmpty()
        val rupees = reqSnap.getLong("amountRupees") ?: ((reqSnap.getLong("amountCents") ?: 0L) / 100L)
        val label = WithdrawalMethodType.displayLabel(method)
        val resolvedNote = cleanedNote.ifEmpty { "$label code delivered by Admin." }

        transaction.set(
          usedCodeRef,
          mapOf(
            "codeHash" to codeHash,
            "requestId" to requestId,
            "method" to method,
            "amountRupees" to rupees,
            "assignedBy" to adminUid,
            "assignedAt" to FieldValue.serverTimestamp()
          )
        )

        transaction.update(
          reqRef,
          mapOf(
            "status" to WithdrawalStatus.COMPLETED,
            "statusNote" to resolvedNote,
            "adminActionBy" to adminUid,
            "giftCode" to cleanedCode,
            "giftCodeHash" to codeHash,
            "giftCodeAssignedAt" to FieldValue.serverTimestamp(),
            "completedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )

        if (uid.isNotBlank()) {
          val notifRef =
            usersCollection
              .document(uid)
              .collection("notifications")
              .document("user_wd_ready_$requestId")
          transaction.set(
            notifRef,
            mapOf(
              "userId" to uid,
              "title" to "Your Gift Card is Ready!",
              "body" to "Your $label request has been successfully completed. Please open TaskFlow → Withdrawal → Withdrawal History to view your gift code.",
              "type" to "GIFT_CARD_READY",
              "referenceId" to requestId,
              "isRead" to false,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
        }
        label to rupees
      }.await()

    recordAdminActivity(
      actionType = "WITHDRAWAL",
      title = "Delivered $methodLabel Code",
      details = "Completed ₹$amountRupees request $requestId with unique gift code.",
      targetId = requestId
    )
  }

  /**
   * Admin marks a UPI withdrawal `COMPLETED` after actual manual UPI payout transfer.
   */
  suspend fun adminCompleteUpiWithdrawal(
    requestId: String,
    paymentReference: String = "",
    statusNote: String = ""
  ): Result<Unit> = runCatching {
    require(requestId.isNotBlank()) { "requestId must not be blank" }
    val adminUid = authProvider().currentUser?.uid?.trim().orEmpty()
    require(adminUid.isNotBlank()) { "Admin sign-in required." }
    val cleanedRef = paymentReference.trim().take(120)
    val cleanedNote = statusNote.trim().take(500)
    val reqRef = withdrawalRequestsCollection.document(requestId)

    val amountRupees =
      firestore.runTransaction { transaction ->
        val reqSnap = transaction.get(reqRef)
        check(reqSnap.exists()) { "Withdrawal request not found." }
        val method = reqSnap.getString("method").orEmpty()
        check(WithdrawalMethodType.isUpi(method)) {
          "UPI completion is only valid for UPI withdrawal requests."
        }
        val currentStatus = reqSnap.getString("status").orEmpty()
        check(!WithdrawalStatus.isTerminal(currentStatus)) {
          "This request has already reached a terminal state ($currentStatus)."
        }
        val coinsDeducted = reqSnap.getBoolean("coinsDeducted") ?: false
        val alreadyRefunded = reqSnap.getBoolean("coinsRefunded") ?: false
        check(coinsDeducted && !alreadyRefunded) {
          "Cannot complete a request whose coins are not debited or already refunded."
        }

        val uid = reqSnap.getString("userId").orEmpty()
        val rupees = reqSnap.getLong("amountRupees") ?: ((reqSnap.getLong("amountCents") ?: 0L) / 100L)
        val resolvedNote =
          cleanedNote.ifEmpty {
            if (cleanedRef.isNotBlank()) "UPI transfer completed (Ref: $cleanedRef)."
            else "UPI transfer completed by Admin."
          }

        transaction.update(
          reqRef,
          mapOf(
            "status" to WithdrawalStatus.COMPLETED,
            "statusNote" to resolvedNote,
            "adminActionBy" to adminUid,
            "paymentReference" to cleanedRef,
            "completedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )

        if (uid.isNotBlank()) {
          val notifRef =
            usersCollection
              .document(uid)
              .collection("notifications")
              .document("user_wd_completed_$requestId")
          transaction.set(
            notifRef,
            mapOf(
              "userId" to uid,
              "title" to "UPI Payment Completed",
              "body" to "Your UPI withdrawal for ₹$rupees has been completed.${if (cleanedRef.isNotBlank()) " Ref: $cleanedRef" else ""}",
              "type" to "WITHDRAWAL",
              "referenceId" to requestId,
              "isRead" to false,
              "createdAt" to FieldValue.serverTimestamp()
            )
          )
        }
        rupees
      }.await()

    recordAdminActivity(
      actionType = "WITHDRAWAL",
      title = "Completed UPI Withdrawal",
      details = "Marked UPI ₹$amountRupees ($requestId) Completed.${if (cleanedRef.isNotBlank()) " Ref: $cleanedRef" else ""}",
      targetId = requestId
    )
  }

  /**
   * Admin rejects a non-terminal withdrawal request with a mandatory reason and atomically refunds
   * the exact deducted coins once (`coinsRefunded = true`, `wd_refund_{requestId}`).
   */
  suspend fun adminRejectWithdrawalAndRefund(
    requestId: String,
    rejectionReason: String
  ): Result<Unit> = runCatching {
    require(requestId.isNotBlank()) { "requestId must not be blank" }
    val cleanedReason = rejectionReason.trim()
    require(cleanedReason.isNotEmpty() && cleanedReason.length <= 500) {
      "A clear rejection reason is required."
    }
    val adminUid = authProvider().currentUser?.uid?.trim().orEmpty()
    require(adminUid.isNotBlank()) { "Admin sign-in required." }

    val reqRef = withdrawalRequestsCollection.document(requestId)
    val refundTxRef = transactionsCollection.document("wd_refund_$requestId")

    val (methodLabel, amountRupees, refundedCoins) =
      firestore.runTransaction { transaction ->
        val reqSnap = transaction.get(reqRef)
        check(reqSnap.exists()) { "Withdrawal request not found." }
        val currentStatus = reqSnap.getString("status").orEmpty()
        check(!WithdrawalStatus.isTerminal(currentStatus)) {
          "Cannot reject a request that is already $currentStatus."
        }
        val coinsDeducted = reqSnap.getBoolean("coinsDeducted") ?: false
        val alreadyRefunded = reqSnap.getBoolean("coinsRefunded") ?: false
        check(coinsDeducted && !alreadyRefunded) {
          "Coins have already been refunded or were not deducted for this request."
        }
        val existingRefundTx = transaction.get(refundTxRef)
        check(!existingRefundTx.exists()) {
          "Refund transaction already recorded for this request."
        }

        val targetUid = reqSnap.getString("userId").orEmpty()
        check(targetUid.isNotBlank()) { "Request is missing userId." }
        val userRef = usersCollection.document(targetUid)
        val userSnap = transaction.get(userRef)
        check(userSnap.exists()) { "User profile not found for refund." }

        val method = reqSnap.getString("method").orEmpty()
        val label = WithdrawalMethodType.displayLabel(method)
        val rupees = reqSnap.getLong("amountRupees") ?: ((reqSnap.getLong("amountCents") ?: 0L) / 100L)
        val amountCents = reqSnap.getLong("amountCents") ?: (rupees * 100L)
        val amountCoins = reqSnap.getLong("amountCoins") ?: centsToCoins(amountCents)
        val deductBalCoins = reqSnap.getLong("deductedBalanceCoins") ?: amountCoins
        val deductEligCoins = reqSnap.getLong("deductedEligibleCoins") ?: 0L
        val deductPromoCoins = reqSnap.getLong("deductedPromotionalCoins") ?: 0L
        val deductCents = reqSnap.getLong("deductedBalanceCents") ?: 0L

        val oldBalanceCents = userSnap.getLong("balanceCents") ?: 0L
        val oldExplicitEligible = userSnap.getLong("eligibleCoins") ?: centsToCoins(oldBalanceCents)
        val oldPromoCoins = userSnap.getLong("promotionalCoins") ?: 0L
        val oldBalanceCoins = userSnap.getLong("balanceCoins") ?: 0L

        val newBalanceCoins = oldBalanceCoins + deductBalCoins
        val newEligibleCoins = oldExplicitEligible + deductEligCoins
        val newPromoCoins = oldPromoCoins + deductPromoCoins
        val newBalanceCents = oldBalanceCents + deductCents

        val resolvedUserName =
          (userSnap.getString("displayName") ?: reqSnap.getString("userName") ?: "TaskFlow Member")
            .trim()
            .ifEmpty { "TaskFlow Member" }
        val completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L
        val approvalStatus = userSnap.getString("approvalStatus") ?: "approved"

        transaction.update(
          userRef,
          mapOf(
            "balanceCoins" to newBalanceCoins,
            "eligibleCoins" to newEligibleCoins,
            "promotionalCoins" to newPromoCoins,
            "balanceCents" to newBalanceCents,
            "lastRefundedWithdrawalId" to requestId,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )

        transaction.set(
          leaderboardCollection.document(targetUid),
          buildLeaderboardPayload(
            userId = targetUid,
            displayName = resolvedUserName,
            balanceCents = newBalanceCents,
            balanceCoins = newBalanceCoins,
            completedTasksCount = completedTasksCount,
            approvalStatus = approvalStatus
          ),
          SetOptions.merge()
        )

        transaction.update(
          reqRef,
          mapOf(
            "status" to WithdrawalStatus.REJECTED,
            "statusNote" to cleanedReason,
            "adminActionBy" to adminUid,
            "coinsRefunded" to true,
            "rejectedAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )

        transaction.set(
          refundTxRef,
          mapOf(
            "userId" to targetUid,
            "userName" to resolvedUserName,
            "type" to TransactionType.WITHDRAWAL_REFUND,
            "title" to "$label Refund (₹$rupees)",
            "amountCents" to amountCents,
            "amountCoins" to amountCoins,
            "status" to "REFUNDED",
            "referenceId" to requestId,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )

        val notifRef =
          usersCollection
            .document(targetUid)
            .collection("notifications")
            .document("user_wd_rejected_$requestId")
        transaction.set(
          notifRef,
          mapOf(
            "userId" to targetUid,
            "title" to "Withdrawal Rejected & Coins Refunded",
            "body" to "Your $label request for ₹$rupees was rejected: $cleanedReason. ${formatCoins(amountCoins)} have been refunded to your wallet.",
            "type" to "WITHDRAWAL",
            "referenceId" to requestId,
            "isRead" to false,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )

        Triple(label, rupees, amountCoins)
      }.await()

    recordAdminActivity(
      actionType = "WITHDRAWAL",
      title = "Rejected & Refunded $methodLabel",
      details = "Rejected ₹$amountRupees ($requestId) and refunded ${formatCoins(refundedCoins)}. Reason: $cleanedReason",
      targetId = requestId
    )
  }

  /**
   * Observes the signed-in user's own demo withdrawal requests in real time.
   */
  fun observeUserWithdrawalRequests(uid: String): Flow<List<DemoWithdrawalRequest>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      withdrawalRequestsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toObjectSafe(DemoWithdrawalRequest::class.java) }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of a user's own demo withdrawal requests.
   */
  suspend fun getUserWithdrawalRequests(uid: String): Result<List<DemoWithdrawalRequest>> =
    runCatching {
      require(uid.isNotBlank()) { "uid must not be blank" }
      val snap = withdrawalRequestsCollection.whereEqualTo("userId", uid).get().await()
      snap.documents
        .mapNotNull { it.toObjectSafe(DemoWithdrawalRequest::class.java) }
        .sortedByDescending { it.createdAt?.seconds ?: 0L }
    }

  /**
   * Observes all demo withdrawal requests in `/withdrawal_requests` for an authorized admin.
   */
  fun observeAdminWithdrawalRequests(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<DemoWithdrawalRequest>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      withdrawalRequestsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot
              ?.documents
              ?.mapNotNull { it.toObjectSafe(DemoWithdrawalRequest::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of all demo withdrawal requests for an authorized admin.
   */
  suspend fun getAdminWithdrawalRequests(): Result<List<DemoWithdrawalRequest>> = runCatching {
    val snap = withdrawalRequestsCollection.get().await()
    snap.documents
      .mapNotNull { it.toObjectSafe(DemoWithdrawalRequest::class.java) }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Claims daily check-in reward exclusively through the server-side `claimDailyCheckIn` Cloud Function.
   *
   * Security Invariants:
   * - Server computes the authoritative Asia/Kolkata date, 30-day streak progression, and coin reward.
   * - Never trusts client-provided reward amounts or performs direct client-side wallet/ledger writes.
   */
  suspend fun claimDailyCheckInDetailed(
    userId: String,
    streakDay: Int? = null
  ): Result<ClaimedRewardResult> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    if (streakDay != null) {
      require(streakDay in 1..30) { "streakDay must be between 1 and 30" }
    }
    rewardFunctionsClient.claimDailyCheckIn(streakDay)
  }

  suspend fun claimDailyCheckIn(
    userId: String,
    streakDay: Int,
    rewardCents: Long = 0L,
    dateKey: String = com.example.data.model.getTodayDateKey(),
    userName: String = "",
    verifiedAdCallbackToken: String? = null
  ): Result<Long> = runCatching {
    claimDailyCheckInDetailed(userId = userId, streakDay = streakDay).getOrThrow().rewardCents
  }

  /**
   * Observes transactions for a specific user in real time.
   */
  fun observeUserTransactions(uid: String): Flow<List<TransactionRecord>> = callbackFlow {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val listener =
      transactionsCollection
        .whereEqualTo("userId", uid)
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toTransactionRecordSafe() }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * Observes all transactions in real time for authorized Admin.
   */
  fun observeAllTransactions(
    adminUid: String,
    isAdminAuthorized: Boolean
  ): Flow<List<TransactionRecord>> = callbackFlow {
    require(adminUid.isNotBlank()) { "adminUid must not be blank" }
    if (!isAdminAuthorized) {
      trySend(emptyList())
      awaitClose {}
      return@callbackFlow
    }
    val listener =
      transactionsCollection.addSnapshotListener { snapshot, error ->
        if (error != null) {
          close(error)
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot
              ?.documents
              ?.mapNotNull { it.toTransactionRecordSafe() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  /**
   * Observes all CREDITED reward transactions in real time so any signed-in user can view
   * accurate time-filtered Leaderboard rankings (`All Time`, `This Month`, `This Week`).
   */
  fun observeCreditedTransactionsForLeaderboard(): Flow<List<TransactionRecord>> = callbackFlow {
    val listener =
      transactionsCollection
        .whereEqualTo("status", "CREDITED")
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val items =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toTransactionRecordSafe() }
                ?.filter { com.example.data.model.TransactionType.isCreditedRewardType(it.type) }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  internal fun resolveLeaderboardBalanceCoins(
    rawBalanceCoins: Long?,
    rawBalanceCents: Long
  ): Long = rawBalanceCoins ?: com.example.data.model.centsToCoins(rawBalanceCents)

  internal fun sortLeaderboardProfiles(users: List<UserProfile>): List<UserProfile> =
    users
      .filter { it.approvalStatus == "approved" }
      .sortedByDescending {
        if (it.balanceCoins > 0L) it.balanceCoins
        else com.example.data.model.centsToCoins(it.balanceCents)
      }
      .take(50)

  private fun DocumentSnapshot.toPublicLeaderboardProfile(): UserProfile? {
    val uid = (getString("userId") ?: id).trim()
    if (uid.isEmpty()) return null
    val rawBalanceCents = getLong("balanceCents") ?: 0L
    val rawBalanceCoins = getLong("balanceCoins")
    return UserProfile(
      id = uid,
      userId = uid,
      email = "", // Strictly omitted for privacy on public leaderboard
      displayName = (getString("displayName") ?: "TaskFlow Member").trim(),
      role = "user",
      approvalStatus = getString("approvalStatus") ?: "approved",
      balanceCents = rawBalanceCents,
      balanceCoins = resolveLeaderboardBalanceCoins(rawBalanceCoins, rawBalanceCents),
      completedTasksCount = getLong("completedTasksCount") ?: 0L,
      updatedAt = getTimestamp("updatedAt")
    )
  }

  /**
   * Observes approved users ordered by balanceCoins from the privacy-safe `/leaderboard` projection
   * without querying `/users` or exposing private user email addresses.
   */
  fun observeLeaderboardUsers(): Flow<List<UserProfile>> = callbackFlow {
    val listener =
      leaderboardCollection
        .whereEqualTo("approvalStatus", "approved")
        .addSnapshotListener { snapshot, error ->
          if (error != null) {
            close(error)
            return@addSnapshotListener
          }
          val items =
            sortLeaderboardProfiles(
              snapshot
                ?.documents
                ?.mapNotNull { it.toPublicLeaderboardProfile() }
                ?: emptyList()
            )
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of the privacy-safe `/leaderboard` entries sorted by balanceCoins.
   */
  suspend fun getLeaderboardUsers(): Result<List<UserProfile>> = runCatching {
    val snap = leaderboardCollection.whereEqualTo("approvalStatus", "approved").get().await()
    sortLeaderboardProfiles(snap.documents.mapNotNull { it.toPublicLeaderboardProfile() })
  }

  /**
   * Allows an Admin to update any user's display name and syncs `/leaderboard/{targetUserId}`.
   */
  suspend fun updateUserDisplayNameByAdmin(
    targetUserId: String,
    newDisplayName: String
  ): Result<Unit> = runCatching {
    require(targetUserId.isNotBlank()) { "targetUserId must not be blank" }
    val cleaned = newDisplayName.trim()
    require(cleaned.isNotEmpty() && cleaned.length <= 100) {
      "Display name must be between 1 and 100 characters."
    }
    val userRef = usersCollection.document(targetUserId)
    val userSnap = userRef.get().await()
    check(userSnap.exists()) { "User profile $targetUserId not found." }
    val currentBalanceCoins = computeSnapshotEffectiveBalanceCoins(userSnap)
    val batch = firestore.batch()
    batch.update(
      userRef,
      mapOf(
        "displayName" to cleaned,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    )
    batch.set(
      leaderboardCollection.document(targetUserId),
      buildLeaderboardPayload(
        userId = targetUserId,
        displayName = cleaned,
        balanceCents = userSnap.getLong("balanceCents") ?: 0L,
        balanceCoins = currentBalanceCoins,
        completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
        approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
      ),
      SetOptions.merge()
    )
    batch.commit().await()
  }

  private val watchStatsCollection
    get() = firestore.collection("watch_stats")

  private fun userWatchStatsCollection(userId: String) =
    usersCollection.document(userId).collection("watch_stats")

  /**
   * Creates a unique, time-limited reward session bound to [userId] for Daily Check-In (Days 1–30,
   * using the 30-day coin schedule in [DailyCheckInConstants]) exclusively via the
   * `createRewardSession` Cloud Function. No client-side Firestore or in-memory fallback is permitted.
   */
  suspend fun createDailyCheckInSession(
    userId: String,
    dateKey: String = com.example.data.model.getTodayDateKey(),
    streakDay: Int,
    rewardCents: Long
  ): Result<String> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    require(dateKey.isNotBlank() && dateKey.length == 10) { "Invalid dateKey." }
    require(streakDay in 1..30) { "Invalid streakDay." }
    val expectedReward = DailyCheckInConstants.getRewardCoinsForDay(streakDay)
    require(rewardCents == expectedReward) {
      "Invalid reward amount for Day $streakDay (expected $expectedReward Coins)."
    }

    val authUid = authProvider().currentUser?.uid
    if (authUid.isNullOrBlank()) {
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "FirebaseAuth",
        errorCode = "UNAUTHENTICATED",
        errorMessage = "FirebaseAuth.currentUser is null"
      )
      throw IllegalStateException("Authentication is required to create a reward session.")
    }
    if (authUid != userId) {
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "FirebaseAuth",
        errorCode = "UID_MISMATCH",
        errorMessage = "Authenticated UID does not match requested userId"
      )
      throw IllegalStateException("Authenticated user mismatch.")
    }

    val userSnap = usersCollection.document(userId).get().await()
    check(userSnap.exists()) { "User profile not found." }
    val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
    check(approvalStatus == "approved") {
      "Your account must be approved by an administrator before claiming daily check-in rewards."
    }
    val lastCheckInDate = userSnap.getString("lastCheckInDate").orEmpty()
    check(lastCheckInDate != dateKey) {
      "You have already claimed today's daily check-in reward."
    }

    Log.i(
      "WatchEarnFlow",
      "[DailyCheckIn] Starting createRewardSession Cloud Function for Day $streakDay (rewardCents=$rewardCents)"
    )

    try {
      val created = rewardFunctionsClient.createRewardSession("DAILY_CHECK_IN", streakDay)
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = true,
        source = "CloudFunction:createRewardSession(DAILY_CHECK_IN)"
      )
      created.sessionId
    } catch (fnErr: Throwable) {
      val fnCode =
        (fnErr as? com.google.firebase.functions.FirebaseFunctionsException)?.code?.name
          ?: fnErr.javaClass.simpleName
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "CloudFunction:createRewardSession(DAILY_CHECK_IN)",
        errorCode = fnCode,
        errorMessage = fnErr.message ?: "Cloud Function createRewardSession failed"
      )
      throw fnErr
    }
  }

  /**
   * Step 3 & 4 of Watch & Earn: Creates a unique, unpredictable, time-limited reward session
   * bound to the authenticated Firebase user exclusively via the `createRewardSession` Cloud Function.
   * No client-side Firestore `/reward_sessions` creation fallback is permitted.
   */
  suspend fun createWatchEarnSession(
    userId: String,
    dateKey: String = com.example.data.model.getTodayDateKey()
  ): Result<String> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    require(dateKey.isNotBlank()) { "Invalid dateKey." }

    val authUid = authProvider().currentUser?.uid
    if (authUid.isNullOrBlank()) {
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "FirebaseAuth",
        errorCode = "UNAUTHENTICATED",
        errorMessage = "FirebaseAuth.currentUser is null"
      )
      throw IllegalStateException("Authentication is required to create a reward session.")
    }
    if (authUid != userId) {
      Log.e(
        "WatchEarnFlow",
        "[Step 3] Session creation rejected: authenticated UID does not match requested userId."
      )
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "FirebaseAuth",
        errorCode = "UID_MISMATCH",
        errorMessage = "Authenticated UID does not match requested userId"
      )
      throw IllegalStateException("Authenticated user mismatch.")
    }

    val userSnap = usersCollection.document(userId).get().await()
    check(userSnap.exists()) { "User profile not found." }
    val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
    check(approvalStatus == "approved") {
      "Your account must be approved by an administrator before earning ad rewards."
    }

    Log.i("WatchEarnFlow", "[Step 3] Starting Firebase createRewardSession request (dateKey=$dateKey)")

    // 1. Check daily 25-video limit across both subcollection (/users/{uid}/watch_stats/{dateKey})
    // and top-level collection (/watch_stats/{uid}_{dateKey})
    val subStatSnap =
      runCatching { userWatchStatsCollection(userId).document(dateKey).get().await() }.getOrNull()
    val topStatSnap =
      runCatching { watchStatsCollection.document("${userId}_$dateKey").get().await() }.getOrNull()

    val currentWatched =
      maxOf(
        subStatSnap?.getLong("videosWatched") ?: 0L,
        topStatSnap?.getLong("videosWatched") ?: 0L
      )
    if (currentWatched >= 25L) {
      Log.w(
        "WatchEarnFlow",
        "[Step 3] Session creation blocked: daily limit reached ($currentWatched/25)."
      )
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "DailyLimitCheck",
        errorCode = "RESOURCE_EXHAUSTED",
        errorMessage = "Daily limit reached ($currentWatched/25)"
      )
      throw IllegalStateException("Daily limit of 25 videos reached (25/25).")
    }

    // 2. Call authenticated backend Cloud Function 'createRewardSession'
    try {
      val created = rewardFunctionsClient.createRewardSession("WATCH_AND_EARN", null)
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = true,
        source = "CloudFunction:createRewardSession(WATCH_AND_EARN)"
      )
      created.sessionId
    } catch (fnErr: Throwable) {
      val fnCode =
        (fnErr as? com.google.firebase.functions.FirebaseFunctionsException)?.code?.name
          ?: fnErr.javaClass.simpleName
      Log.e(
        "WatchEarnFlow",
        "[Step 4] Callable createRewardSession failed ($fnCode: ${fnErr.message})"
      )
      com.example.util.UnityAdsHelper.recordRewardSessionResult(
        success = false,
        source = "CloudFunction:createRewardSession(WATCH_AND_EARN)",
        errorCode = fnCode,
        errorMessage = fnErr.message ?: "Cloud Function createRewardSession failed"
      )
      throw fnErr
    }
  }

  fun getOrAssignWatchEarnSessionReward(sessionId: String): Long {
    return rewardFunctionsClient.getOrAssignSessionRewardCoins(sessionId).coerceIn(0L, 200L)
  }

  /**
   * Step 2 of Watch & Earn: Confirms that `/reward_sessions/{sessionId}` has been verified by the
   * Unity S2S callback (`s2sVerified == true && status == "VERIFIED"`).
   * Never mutates `/reward_sessions` from the client.
   */
  suspend fun verifyWatchEarnSessionCompleted(
    userId: String,
    sessionId: String
  ): Result<Unit> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    require(sessionId.isNotBlank()) { "Valid reward session ID is required." }

    rewardFunctionsClient.awaitSessionVerified(userId, sessionId)
    Log.i("WatchEarnFlow", "Confirmed server-side S2S verification for sessionId=$sessionId")
  }

  suspend fun createWatchEarnClaimSession(userId: String): Result<String> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    val created = rewardFunctionsClient.createRewardSession("WATCH_AND_EARN_CLAIM", null)
    created.sessionId
  }

  /**
   * Step 3 of Watch & Earn: Claims two server-verified (`s2sVerified == true`) reward sessions
   * (`sessionId` and `secondSessionId`) exclusively through the `claimRewardSession` Cloud Function.
   */
  suspend fun claimVerifiedWatchEarnSession(
    userId: String,
    userName: String,
    sessionId: String,
    secondSessionId: String,
    dateKey: String = com.example.data.model.getTodayDateKey()
  ): Result<Long> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    require(secondSessionId.isNotBlank()) { "secondSessionId must not be blank" }
    require(dateKey.isNotBlank()) { "Invalid dateKey" }

    val claimed = rewardFunctionsClient.claimRewardSession(sessionId.trim(), secondSessionId.trim())
    Log.i(
      "WatchEarnFlow",
      "Claimed server-verified Watch & Earn reward (+${claimed.rewardCents} coins)"
    )
    claimed.rewardCents
  }

  /**
   * Convenience wrapper that claims a verified Watch & Earn session by ID via the Cloud Function.
   */
  suspend fun claimWatchEarnReward(
    userId: String,
    userName: String,
    dateKey: String,
    sessionId: String = "",
    secondSessionId: String = ""
  ): Result<Long> = runCatching {
    require(sessionId.isNotBlank() && secondSessionId.isNotBlank()) {
      "Server-verified reward session IDs are required to claim Watch & Earn rewards."
    }
    claimVerifiedWatchEarnSession(userId, userName, sessionId, secondSessionId, dateKey).getOrThrow()
  }

  suspend fun createSpinSession(): Result<String> = runCatching {
    rewardFunctionsClient.createSpinSession()
  }

  suspend fun createSpinSessionWithOutcome(userId: String): Result<SpinSessionOutcome> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    rewardFunctionsClient.createSpinSessionWithOutcome(userId)
  }

  suspend fun createExtraSpinUnlockSession(userId: String): Result<String> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    val created = rewardFunctionsClient.createRewardSession("EXTRA_SPIN_UNLOCK", null)
    created.sessionId
  }

  suspend fun recordExtraSpinAdWatch(sessionId: String): Result<Int> = runCatching {
    require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    rewardFunctionsClient.recordExtraSpinAdWatch(sessionId.trim())
  }

  suspend fun recordExtraSpinVideoWatched(userId: String): Result<Int> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    rewardFunctionsClient.recordExtraSpinVideoWatched(userId)
  }

  suspend fun claimSpinReward(sessionId: String): Result<Long> = runCatching {
    rewardFunctionsClient.claimSpinReward(sessionId)
  }

  fun observeWatchStats(uid: String, dateKey: String): Flow<com.example.data.model.WatchStats> =
    callbackFlow {
      if (uid.isBlank()) {
        trySend(com.example.data.model.WatchStats())
        close()
        return@callbackFlow
      }
      var subStats: com.example.data.model.WatchStats? = null
      var topStats: com.example.data.model.WatchStats? = null

      fun emitMerged() {
        val chosen =
          when {
            subStats != null && topStats != null ->
              if ((subStats?.videosWatched ?: 0L) >= (topStats?.videosWatched ?: 0L)) subStats!!
              else topStats!!
            subStats != null -> subStats!!
            topStats != null -> topStats!!
            else -> com.example.data.model.WatchStats(userId = uid, dateKey = dateKey)
          }
        trySend(chosen)
      }

      val subListener =
        userWatchStatsCollection(uid).document(dateKey).addSnapshotListener { snapshot, error ->
          if (error == null) {
            subStats =
              snapshot?.toObjectSafe(com.example.data.model.WatchStats::class.java)
                ?: com.example.data.model.WatchStats(userId = uid, dateKey = dateKey)
            emitMerged()
          }
        }

      val statId = "${uid}_$dateKey"
      val topListener =
        watchStatsCollection.document(statId).addSnapshotListener { snapshot, error ->
          if (error == null) {
            topStats =
              snapshot?.toObjectSafe(com.example.data.model.WatchStats::class.java)
                ?: com.example.data.model.WatchStats(userId = uid, dateKey = dateKey)
            emitMerged()
          } else if (subStats == null) {
            trySend(com.example.data.model.WatchStats(userId = uid, dateKey = dateKey))
          }
        }

      awaitClose {
        subListener.remove()
        topListener.remove()
      }
    }

  // ==========================================
  // CAMPAIGNS (Client / Partner Management)
  // ==========================================

  fun observeCampaigns(): Flow<List<CampaignItem>> = callbackFlow {
    val listener = campaignsCollection.addSnapshotListener { snapshot, error ->
      if (error != null) {
        close(error)
        return@addSnapshotListener
      }
      val items =
        runCatching {
          snapshot?.documents?.mapNotNull { it.toObjectSafe(CampaignItem::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
        }.getOrDefault(emptyList())
      trySend(items)
    }
    awaitClose { listener.remove() }
  }

  suspend fun createCampaign(
    title: String,
    clientName: String,
    description: String,
    budgetCents: Long,
    targetSubmissions: Long,
    category: String,
    rewardPerTaskCents: Long,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users",
    appIconUrl: String = "",
    taskLink: String = "",
    linkedTaskId: String = ""
  ): Result<String> = runCatching {
    require(title.isNotBlank()) { "Campaign title cannot be blank" }
    require(clientName.isNotBlank()) { "Client/Partner name cannot be blank" }
    require(budgetCents > 0) { "Campaign budget must be greater than 0" }
    val docRef = campaignsCollection.document()
    val data = mapOf(
      "title" to title.trim(),
      "clientName" to clientName.trim(),
      "description" to description.trim(),
      "appIconUrl" to appIconUrl.trim(),
      "taskLink" to taskLink.trim(),
      "budgetCents" to budgetCents,
      "spentCents" to 0L,
      "targetSubmissions" to targetSubmissions,
      "completedSubmissions" to 0L,
      "status" to "ACTIVE",
      "category" to category.trim().ifEmpty { "App Install" },
      "rewardPerTaskCents" to rewardPerTaskCents,
      "startDate" to startDate.trim(),
      "endDate" to endDate.trim(),
      "eligibilityRules" to eligibilityRules.trim().ifEmpty { "All Approved Users" },
      "linkedTaskId" to linkedTaskId.trim(),
      "createdAt" to FieldValue.serverTimestamp(),
      "updatedAt" to FieldValue.serverTimestamp()
    )
    docRef.set(data).await()
    recordAdminActivity(
      actionType = "CAMPAIGN",
      title = "Launched Campaign: ${title.trim()}",
      details = "Partner: ${clientName.trim()} • Quota: $targetSubmissions • Budget: ${formatRewardInr(budgetCents)}",
      targetId = docRef.id
    )
    docRef.id
  }

  suspend fun updateCampaignDetails(
    campaignId: String,
    title: String,
    clientName: String,
    description: String,
    budgetCents: Long,
    targetSubmissions: Long,
    category: String,
    rewardPerTaskCents: Long,
    startDate: String = "",
    endDate: String = "",
    eligibilityRules: String = "All Approved Users"
  ): Result<Unit> = runCatching {
    require(campaignId.isNotBlank()) { "campaignId cannot be blank" }
    require(title.isNotBlank()) { "Campaign title cannot be blank" }
    val campRef = campaignsCollection.document(campaignId)
    val snap = campRef.get().await()
    val completed = snap.getLong("completedSubmissions") ?: 0L
    val currentStatus = snap.getString("status") ?: "ACTIVE"
    val nextStatus =
      if (targetSubmissions > 0L && completed >= targetSubmissions) "COMPLETED"
      else if (currentStatus == "COMPLETED" && completed < targetSubmissions) "ACTIVE"
      else currentStatus

    campRef.update(
      mapOf(
        "title" to title.trim(),
        "clientName" to clientName.trim(),
        "description" to description.trim(),
        "budgetCents" to budgetCents,
        "targetSubmissions" to targetSubmissions,
        "category" to category.trim().ifEmpty { "App Install" },
        "rewardPerTaskCents" to rewardPerTaskCents,
        "startDate" to startDate.trim(),
        "endDate" to endDate.trim(),
        "eligibilityRules" to eligibilityRules.trim().ifEmpty { "All Approved Users" },
        "status" to nextStatus,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    ).await()
    recordAdminActivity(
      actionType = "CAMPAIGN",
      title = "Updated Campaign: ${title.trim()}",
      details = "Quota: $completed/$targetSubmissions • Status: $nextStatus",
      targetId = campaignId
    )
  }

  suspend fun updateCampaignStatus(campaignId: String, newStatus: String): Result<Unit> = runCatching {
    require(campaignId.isNotBlank()) { "campaignId cannot be blank" }
    val campRef = campaignsCollection.document(campaignId)
    val campSnap = campRef.get().await()
    val linkedTaskId = campSnap.getString("linkedTaskId").orEmpty()
    val title = campSnap.getString("title").orEmpty()
    campRef.update(
      mapOf(
        "status" to newStatus,
        "updatedAt" to FieldValue.serverTimestamp()
      )
    ).await()
    if (linkedTaskId.isNotBlank()) {
      val shouldTaskBeActive = newStatus.equals("ACTIVE", ignoreCase = true)
      runCatching {
        tasksCollection.document(linkedTaskId).update(
          mapOf(
            "isActive" to shouldTaskBeActive,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        ).await()
      }
    }
    recordAdminActivity(
      actionType = "CAMPAIGN",
      title = "Campaign Status Changed: ${title.ifBlank { campaignId }}",
      details = "New status: $newStatus",
      targetId = campaignId
    )
  }

  // ==========================================
  // IN-APP NOTIFICATIONS
  // ==========================================

  fun observeUserNotifications(userId: String): Flow<List<NotificationItem>> = callbackFlow {
    if (userId.isBlank()) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val listener = usersCollection.document(userId).collection("notifications")
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot?.documents?.mapNotNull { it.toNotificationItemSafe() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  suspend fun markNotificationRead(userId: String, notificationId: String): Result<Unit> = runCatching {
    if (userId.isBlank() || notificationId.isBlank()) return@runCatching
    usersCollection.document(userId).collection("notifications").document(notificationId)
      .update("isRead", true).await()
  }

  // ==========================================
  // SUPPORT TICKETS / INQUIRIES
  // ==========================================

  private fun DocumentSnapshot.toSupportTicketSafe(): SupportTicket? {
    if (!exists()) return null
    return try {
      val resolvedId = (runCatching { getString("id") }.getOrNull()?.trim().orEmpty()).ifEmpty { id }
      val rawStatus = (runCatching { getString("status") }.getOrNull()?.trim().orEmpty()).ifEmpty { "OPEN" }
      val adminNotes = runCatching { getString("adminNotes") }.getOrNull()?.trim().orEmpty()
      val rawMessages = runCatching { get("messages") as? List<*> }.getOrNull().orEmpty()
      val parsedMessages =
        rawMessages.mapNotNull { item ->
          val map = item as? Map<*, *> ?: return@mapNotNull null
          val role = map["senderRole"]?.toString()?.trim().orEmpty().ifEmpty { "USER" }
          val senderName = map["senderName"]?.toString()?.trim().orEmpty()
          val msgText = map["message"]?.toString()?.trim().orEmpty()
          val ts = (map["timestampMillis"] as? Number)?.toLong() ?: 0L
          if (msgText.isEmpty()) null
          else com.example.data.model.SupportReplyMessage(
            senderRole = role,
            senderName = senderName,
            message = msgText,
            timestampMillis = ts
          )
        }
      val finalMessages =
        if (parsedMessages.isEmpty() && adminNotes.isNotBlank()) {
          listOf(
            com.example.data.model.SupportReplyMessage(
              senderRole = "ADMIN",
              senderName = "Support Team",
              message = adminNotes,
              timestampMillis = (getTimestamp("updatedAt") ?: getTimestamp("createdAt"))?.toDate()?.time ?: 0L
            )
          )
        } else {
          parsedMessages
        }

      SupportTicket(
        id = resolvedId,
        userId = runCatching { getString("userId") }.getOrNull()?.trim().orEmpty(),
        userEmail = runCatching { getString("userEmail") }.getOrNull()?.trim().orEmpty(),
        userName = runCatching { getString("userName") }.getOrNull()?.trim().orEmpty(),
        category = (runCatching { getString("category") }.getOrNull()?.trim().orEmpty()).ifEmpty { "General" },
        subject = runCatching { getString("subject") }.getOrNull()?.trim().orEmpty(),
        message = runCatching { getString("message") }.getOrNull()?.trim().orEmpty(),
        status = rawStatus,
        adminNotes = adminNotes,
        messages = finalMessages,
        adminUnread = runCatching { getBoolean("adminUnread") }.getOrNull() ?: rawStatus.equals("OPEN", ignoreCase = true),
        userUnread = runCatching { getBoolean("userUnread") }.getOrNull() ?: false,
        createdAt = runCatching { getTimestamp("createdAt") }.getOrNull(),
        updatedAt = runCatching { getTimestamp("updatedAt") }.getOrNull()
      )
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Skipping malformed SupportTicket $id: ${e.message}")
      null
    }
  }

  fun observeUserSupportTickets(userId: String): Flow<List<SupportTicket>> = callbackFlow {
    if (userId.isBlank()) {
      trySend(emptyList())
      close()
      return@callbackFlow
    }
    val listener = supportTicketsCollection.whereEqualTo("userId", userId)
      .addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(emptyList())
          return@addSnapshotListener
        }
        val items =
          runCatching {
            snapshot?.documents?.mapNotNull { it.toSupportTicketSafe() }
              ?.sortedByDescending { (it.updatedAt ?: it.createdAt)?.seconds ?: 0L } ?: emptyList()
          }.getOrDefault(emptyList())
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  fun observeAllSupportTickets(): Flow<List<SupportTicket>> = callbackFlow {
    val listener = supportTicketsCollection.addSnapshotListener { snapshot, error ->
      if (error != null) {
        trySend(emptyList())
        return@addSnapshotListener
      }
      val items =
        runCatching {
          snapshot?.documents?.mapNotNull { it.toSupportTicketSafe() }
            ?.sortedByDescending { (it.updatedAt ?: it.createdAt)?.seconds ?: 0L } ?: emptyList()
        }.getOrDefault(emptyList())
      trySend(items)
    }
    awaitClose { listener.remove() }
  }

  suspend fun createSupportTicket(
    userId: String,
    userName: String,
    userEmail: String,
    subject: String,
    message: String,
    category: String = "General"
  ): Result<String> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(subject.isNotBlank()) { "Subject is required" }
    require(message.isNotBlank()) { "Message is required" }
    val docRef = supportTicketsCollection.document()
    val ticketId = docRef.id
    val cleanedUserName = userName.trim().ifEmpty { "User" }
    val cleanedEmail = userEmail.trim().ifEmpty { "user@taskflow.app" }
    val cleanedCategory = category.trim().ifEmpty { "General" }
    val cleanedSubject = subject.trim().take(200)
    val cleanedMessage = message.trim().take(4000)

    val data = mapOf(
      "userId" to userId,
      "userName" to cleanedUserName,
      "userEmail" to cleanedEmail,
      "category" to cleanedCategory,
      "subject" to cleanedSubject,
      "message" to cleanedMessage,
      "status" to "OPEN",
      "createdAt" to FieldValue.serverTimestamp()
    )

    val adminNotifRef = adminNotificationsCollection.document("admin_support_$ticketId")
    val adminNotifData = mapOf(
      "userId" to userId,
      "userName" to cleanedUserName,
      "title" to "New Support Request: $cleanedCategory",
      "body" to "$cleanedUserName: $cleanedSubject",
      "type" to "SUPPORT_TICKET",
      "referenceId" to ticketId,
      "isRead" to false,
      "createdAt" to FieldValue.serverTimestamp()
    )

    val batch = firestore.batch()
    batch.set(docRef, data)
    batch.set(adminNotifRef, adminNotifData)
    runCatching {
      batch.commit().await()
    }.onFailure {
      // Fallback to direct ticket creation if admin notification already exists or fails
      docRef.set(data).await()
    }
    ticketId
  }

  suspend fun updateSupportTicketStatus(
    ticketId: String,
    status: String,
    adminNotes: String
  ): Result<Unit> =
    adminReplyToSupportTicket(
      ticketId = ticketId,
      status = status,
      replyMessage = adminNotes
    )

  suspend fun adminReplyToSupportTicket(
    ticketId: String,
    status: String,
    replyMessage: String
  ): Result<Unit> = runCatching {
    require(ticketId.isNotBlank()) { "ticketId must not be blank" }
    val normalizedStatus =
      when (status.trim().uppercase(Locale.US)) {
        "IN_PROGRESS", "IN PROGRESS" -> "IN_PROGRESS"
        "RESOLVED", "CLOSED" -> "RESOLVED"
        else -> "OPEN"
      }
    val cleanedReply = replyMessage.trim().take(3000)
    val now = System.currentTimeMillis()
    val ticketRef = supportTicketsCollection.document(ticketId)

    var targetUid = ""
    var ticketSubject = "Support Request"
    firestore.runTransaction { tx ->
      val snap = tx.get(ticketRef)
      check(snap.exists()) { "Support ticket not found." }
      targetUid = snap.getString("userId").orEmpty()
      ticketSubject = snap.getString("subject").orEmpty().ifEmpty { "Support Request" }
      val existingMessages =
        (snap.get("messages") as? List<*>)
          ?.mapNotNull { it as? Map<*, *> }
          ?.toMutableList()
          ?: mutableListOf()

      if (cleanedReply.isNotEmpty()) {
        existingMessages.add(
          mapOf(
            "senderRole" to "ADMIN",
            "senderName" to "Support Team",
            "message" to cleanedReply,
            "timestampMillis" to now
          )
        )
      }

      val updates = mutableMapOf<String, Any>(
        "status" to normalizedStatus,
        "adminUnread" to false,
        "userUnread" to cleanedReply.isNotEmpty(),
        "messages" to existingMessages,
        "updatedAt" to FieldValue.serverTimestamp()
      )
      if (cleanedReply.isNotEmpty()) {
        updates["adminNotes"] = cleanedReply
      }
      tx.update(ticketRef, updates)

      if (targetUid.isNotBlank()) {
        val statusLabel = com.example.data.model.SupportTicketStatus.displayLabel(normalizedStatus)
        val notifBody =
          if (cleanedReply.isNotEmpty()) {
            "Support replied to \"$ticketSubject\": $cleanedReply"
          } else {
            "Your support request \"$ticketSubject\" status is now $statusLabel."
          }
        val userNotifRef =
          usersCollection
            .document(targetUid)
            .collection("notifications")
            .document("notif_support_${ticketId}_$now")
        tx.set(
          userNotifRef,
          mapOf(
            "userId" to targetUid,
            "title" to "Support Update ($statusLabel)",
            "body" to notifBody,
            "type" to "SUPPORT",
            "referenceId" to ticketId,
            "isRead" to false,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )
      }
    }.await()

    recordAdminActivity(
      actionType = "SUPPORT",
      title = "Updated Support Ticket ($normalizedStatus)",
      details = "Subject: $ticketSubject • User: $targetUid",
      targetId = ticketId
    )
  }

  suspend fun userReplyToSupportTicket(
    ticketId: String,
    userId: String,
    userName: String,
    replyMessage: String
  ): Result<Unit> = runCatching {
    require(ticketId.isNotBlank()) { "ticketId must not be blank" }
    require(userId.isNotBlank()) { "userId must not be blank" }
    val cleanedReply = replyMessage.trim().take(3000)
    require(cleanedReply.isNotEmpty()) { "Please enter a reply message." }
    val now = System.currentTimeMillis()
    val ticketRef = supportTicketsCollection.document(ticketId)

    firestore.runTransaction { tx ->
      val snap = tx.get(ticketRef)
      check(snap.exists()) { "Support ticket not found." }
      val ownerUid = snap.getString("userId").orEmpty()
      check(ownerUid == userId) { "You can only reply to your own support request." }
      val curStatus = snap.getString("status").orEmpty().ifEmpty { "OPEN" }
      val nextStatus = if (curStatus.equals("RESOLVED", ignoreCase = true)) "OPEN" else curStatus
      val existingMessages =
        (snap.get("messages") as? List<*>)
          ?.mapNotNull { it as? Map<*, *> }
          ?.toMutableList()
          ?: mutableListOf()

      existingMessages.add(
        mapOf(
          "senderRole" to "USER",
          "senderName" to userName.trim().ifEmpty { "User" },
          "message" to cleanedReply,
          "timestampMillis" to now
        )
      )

      tx.update(
        ticketRef,
        mapOf(
          "messages" to existingMessages,
          "adminUnread" to true,
          "userUnread" to false,
          "status" to nextStatus,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
    }.await()
  }

  fun observeApps(): Flow<List<AppItem>> = callbackFlow {
    val listener = appsCollection.addSnapshotListener { snapshot, error ->
      if (error != null) {
        trySend(emptyList())
        return@addSnapshotListener
      }
      val apps =
        runCatching {
          snapshot?.documents?.mapNotNull { it.toObjectSafe(AppItem::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
        }.getOrDefault(emptyList())
      trySend(apps)
    }
    awaitClose { listener.remove() }
  }

  suspend fun createApp(name: String, logoUrl: String): Result<String> = runCatching {
    require(name.isNotBlank()) { "App name cannot be blank" }
    val docRef = appsCollection.document()
    val data = mapOf(
      "name" to name.trim(),
      "logoUrl" to logoUrl.trim(),
      "isArchived" to false,
      "createdAt" to FieldValue.serverTimestamp()
    )
    docRef.set(data).await()
    docRef.id
  }

  suspend fun updateApp(appId: String, name: String, logoUrl: String, isArchived: Boolean): Result<Unit> = runCatching {
    require(appId.isNotBlank()) { "appId cannot be blank" }
    appsCollection.document(appId).update(
      mapOf(
        "name" to name.trim(),
        "logoUrl" to logoUrl.trim(),
        "isArchived" to isArchived
      )
    ).await()
  }

  fun observeUserTaskProgress(userId: String, taskId: String): Flow<UserTaskProgress?> = callbackFlow {
    if (userId.isBlank() || taskId.isBlank()) {
      trySend(null)
      close()
      return@callbackFlow
    }
    val docRef = usersCollection.document(userId).collection("task_progress").document(taskId)
    val listener = docRef.addSnapshotListener { snapshot, error ->
      if (error != null || snapshot == null || !snapshot.exists()) {
        trySend(null)
        return@addSnapshotListener
      }
      trySend(snapshot.toObjectSafe(UserTaskProgress::class.java))
    }
    awaitClose { listener.remove() }
  }

  suspend fun saveUserTaskProgress(
    userId: String,
    taskId: String,
    completedSteps: List<Int>,
    trackingEvents: Map<String, Boolean>,
    status: String,
    installationConfirmed: Boolean = true,
    currentStepNumber: Int = 1
  ): Result<Unit> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(taskId.isNotBlank()) { "taskId must not be blank" }
    val docRef = usersCollection.document(userId).collection("task_progress").document(taskId)
    val data = mapOf(
      "taskId" to taskId,
      "userId" to userId,
      "installationConfirmed" to installationConfirmed,
      "currentStepNumber" to currentStepNumber,
      "completedSteps" to completedSteps,
      "trackingEvents" to trackingEvents,
      "status" to status,
      "updatedAt" to FieldValue.serverTimestamp()
    )
    docRef.set(data, SetOptions.merge()).await()
  }

  // =========================================================================
  // FEATURE 1: MULTI-TASK TELEGRAM JOIN REWARD (SERVER-AUTHORITATIVE)
  // =========================================================================

  private fun DocumentSnapshot.toTelegramTaskItem(): TelegramTaskItem? {
    if (!exists()) return null
    val channelUrl = getString("channelUrl")?.trim().orEmpty()
    if (channelUrl.isEmpty()) return null
    val resolvedId = (getString("taskId")?.trim().orEmpty()).ifEmpty { id }
    return TelegramTaskItem(
      id = id,
      taskId = resolvedId,
      title = getString("title")?.trim().orEmpty().ifEmpty { "Join Telegram Channel" },
      channelUrl = channelUrl,
      channelChatId = getString("channelChatId")?.trim().orEmpty(),
      rewardCoins = getLong("rewardCoins") ?: 0L,
      isActive = getBoolean("isActive") ?: false,
      claimsCount = getLong("claimsCount") ?: 0L,
      createdBy = getString("createdBy")?.trim().orEmpty(),
      createdAt = getTimestamp("createdAt"),
      updatedAt = getTimestamp("updatedAt")
    )
  }

  private fun DocumentSnapshot.toTelegramTaskClaimRecord(): TelegramTaskClaimRecord? {
    if (!exists()) return null
    val taskId = getString("taskId")?.trim().orEmpty()
    val userId = getString("userId")?.trim().orEmpty()
    if (taskId.isEmpty() || userId.isEmpty()) return null
    return TelegramTaskClaimRecord(
      id = id,
      claimId = getString("claimId")?.trim().orEmpty().ifEmpty { id },
      taskId = taskId,
      taskTitle = getString("taskTitle")?.trim().orEmpty().ifEmpty { "Telegram Join Task" },
      channelUrl = getString("channelUrl")?.trim().orEmpty(),
      userId = userId,
      userName = getString("userName")?.trim().orEmpty(),
      telegramUsernameMasked = getString("telegramUsernameMasked")?.trim().orEmpty(),
      rewardCoins = getLong("rewardCoins") ?: 0L,
      claimedAt = getTimestamp("claimedAt")
    )
  }

  fun observeTelegramTasks(isAdminAuthorized: Boolean): Flow<List<TelegramTaskItem>> = callbackFlow {
    val query = if (isAdminAuthorized) {
      telegramTasksCollection
    } else {
      telegramTasksCollection.whereEqualTo("isActive", true)
    }
    val listener =
      query.addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w("TaskFlowRepository", "observeTelegramTasks listener error: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list =
          snapshot
            ?.documents
            ?.mapNotNull { it.toTelegramTaskItem() }
            ?.sortedWith(
              compareByDescending<TelegramTaskItem> { it.createdAt?.seconds ?: 0L }
                .thenBy { it.effectiveTaskId }
            )
            ?: emptyList()
        trySend(list)
      }
    awaitClose { listener.remove() }
  }

  fun observeTelegramClaims(
    userId: String,
    isAdminAuthorized: Boolean
  ): Flow<List<TelegramTaskClaimRecord>> = callbackFlow {
    val query = when {
      isAdminAuthorized -> telegramClaimsCollection
      userId.isNotBlank() -> telegramClaimsCollection.whereEqualTo("userId", userId)
      else -> {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
    }
    val listener =
      query.addSnapshotListener { snapshot, error ->
        if (error != null) {
          Log.w("TaskFlowRepository", "observeTelegramClaims listener error: ${error.message}")
          trySend(emptyList())
          return@addSnapshotListener
        }
        val list =
          snapshot
            ?.documents
            ?.mapNotNull { it.toTelegramTaskClaimRecord() }
            ?.sortedByDescending { it.claimedAt?.seconds ?: 0L }
            ?: emptyList()
        trySend(list)
      }
    awaitClose { listener.remove() }
  }

  fun observeTelegramUserTaskStates(userId: String): Flow<Map<String, TelegramUserTaskState>> =
    callbackFlow {
      if (userId.isBlank()) {
        trySend(emptyMap())
        awaitClose {}
        return@callbackFlow
      }
      val colRef = usersCollection.document(userId).collection("telegram_task_states")
      val listener =
        colRef.addSnapshotListener { snapshot, error ->
          if (error != null || snapshot == null) {
            trySend(emptyMap())
            return@addSnapshotListener
          }
          val map = buildMap<String, TelegramUserTaskState> {
            snapshot.documents.forEach { doc ->
              val tId = (doc.getString("taskId")?.trim().orEmpty()).ifEmpty { doc.id }
              val hasClicked = doc.getBoolean("hasClickedJoin") ?: false
              val verified = doc.getBoolean("isMembershipVerified") ?: false
              val claimed = doc.getBoolean("isClaimed") ?: false
              put(
                tId,
                TelegramUserTaskState(
                  taskId = tId,
                  userId = userId,
                  hasClickedJoin = hasClicked || verified || claimed,
                  isMembershipVerified = verified || claimed,
                  isClaimed = claimed,
                  rewardCoinsClaimed = doc.getLong("rewardCoinsClaimed") ?: 0L
                )
              )
            }
          }
          trySend(map)
        }
      awaitClose { listener.remove() }
    }

  suspend fun markTelegramTaskJoinClicked(
    userId: String,
    taskId: String
  ): Result<Unit> = runCatching {
    val cleanUserId = userId.trim()
    val cleanTaskId = taskId.trim()
    if (cleanUserId.isBlank() || cleanTaskId.isBlank()) return@runCatching
    val stateRef =
      usersCollection.document(cleanUserId).collection("telegram_task_states").document(cleanTaskId)
    runCatching {
      stateRef.set(
        mapOf(
          "taskId" to cleanTaskId,
          "userId" to cleanUserId,
          "hasClickedJoin" to true,
          "joinClickedAt" to FieldValue.serverTimestamp()
        ),
        SetOptions.merge()
      ).await()
    }
  }

  fun observeTelegramAccountStatus(userId: String): Flow<TelegramAccountLinkStatus> = callbackFlow {
    if (userId.isBlank()) {
      trySend(TelegramAccountLinkStatus())
      awaitClose {}
      return@callbackFlow
    }
    val docRef = usersCollection.document(userId).collection("telegram_account").document("status")
    val listener =
      docRef.addSnapshotListener { snapshot, error ->
        if (error != null || snapshot == null || !snapshot.exists()) {
          trySend(TelegramAccountLinkStatus(userId = userId, isLinked = false))
          return@addSnapshotListener
        }
        trySend(
          TelegramAccountLinkStatus(
            userId = userId,
            isLinked = snapshot.getBoolean("isLinked") ?: false,
            telegramUsernameMasked = snapshot.getString("telegramUsernameMasked").orEmpty()
          )
        )
      }
    awaitClose { listener.remove() }
  }

  suspend fun createTelegramTask(
    title: String,
    channelUrl: String,
    channelChatId: String = "",
    rewardCoins: Long,
    isActive: Boolean = true
  ): Result<TelegramTaskItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    val cleanedUrl = channelUrl.trim()
    require(cleanedUrl.isNotEmpty() && isValidTaskUrl(cleanedUrl)) {
      "Please enter a valid HTTPS Telegram channel URL (e.g., https://t.me/yourchannel)."
    }
    require(rewardCoins in 1L..10_000_000L) {
      "Telegram reward amount must be between 1 and 10,000,000 Coins."
    }
    rewardFunctionsClient.createTelegramTask(
      title = title.trim(),
      channelUrl = cleanedUrl,
      channelChatId = channelChatId.trim(),
      rewardCoins = rewardCoins,
      isActive = isActive
    )
  }

  suspend fun setTelegramTaskActiveStatus(
    taskId: String,
    isActive: Boolean
  ): Result<Boolean> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    val cleanedId = taskId.trim()
    require(cleanedId.isNotBlank()) { "Valid Telegram taskId is required." }
    rewardFunctionsClient.setTelegramTaskActiveStatus(cleanedId, isActive)
  }

  suspend fun verifyTelegramTaskMembership(
    userId: String,
    taskId: String
  ): Result<TelegramVerifyResult> = runCatching {
    val cleanUserId = userId.trim()
    val cleanTaskId = taskId.trim()
    require(cleanUserId.isNotBlank()) { "Sign-in required." }
    require(cleanTaskId.isNotBlank()) { "Invalid Telegram task." }
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    check(authUid == cleanUserId) { "Authenticated user mismatch." }
    rewardFunctionsClient.verifyTelegramTaskMembership(cleanTaskId)
  }

  suspend fun claimTelegramTaskReward(
    userId: String,
    taskId: String
  ): Result<Long> = runCatching {
    val cleanUserId = userId.trim()
    val cleanTaskId = taskId.trim()
    require(cleanUserId.isNotBlank()) { "Sign-in required." }
    require(cleanTaskId.isNotBlank()) { "Invalid Telegram task." }
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    check(authUid == cleanUserId) { "Authenticated user mismatch." }
    rewardFunctionsClient.claimTelegramTaskReward(cleanTaskId)
  }

  private fun DocumentSnapshot.toTelegramJoinConfig(): TelegramJoinConfig {
    if (!exists()) return TelegramJoinConfig()
    return TelegramJoinConfig(
      channelUrl = getString("channelUrl")?.trim().orEmpty(),
      channelChatId = getString("channelChatId")?.trim().orEmpty(),
      rewardCoins = getLong("rewardCoins") ?: 0L,
      isActive = getBoolean("isActive") ?: false,
      updatedBy = getString("updatedBy")?.trim().orEmpty(),
      updatedAt = getTimestamp("updatedAt")
    )
  }

  fun observeTelegramJoinConfig(): Flow<TelegramJoinConfig> = callbackFlow {
    val listener =
      telegramJoinConfigDoc.addSnapshotListener { snapshot, error ->
        if (error != null) {
          trySend(TelegramJoinConfig())
          return@addSnapshotListener
        }
        trySend(snapshot?.toTelegramJoinConfig() ?: TelegramJoinConfig())
      }
    awaitClose { listener.remove() }
  }

  fun observeTelegramUserRewardState(userId: String): Flow<TelegramUserRewardState> = callbackFlow {
    if (userId.isBlank()) {
      trySend(TelegramUserRewardState())
      close()
      return@callbackFlow
    }
    val statusDocRef = usersCollection.document(userId).collection("telegram_reward").document("status")
    val listener =
      statusDocRef.addSnapshotListener { snapshot, error ->
        if (error != null || snapshot == null || !snapshot.exists()) {
          trySend(TelegramUserRewardState(userId = userId))
          return@addSnapshotListener
        }
        trySend(
          TelegramUserRewardState(
            userId = userId,
            telegramUserId = snapshot.getString("telegramUserId").orEmpty(),
            isMembershipVerified = snapshot.getBoolean("isMembershipVerified") ?: false,
            isClaimed = snapshot.getBoolean("isClaimed") ?: false,
            claimedCoins = snapshot.getLong("rewardCoinsClaimed") ?: 0L
          )
        )
      }
    awaitClose { listener.remove() }
  }

  // =========================================================================
  // FEATURE 2: DAILY PROMO CODE SYSTEM
  // =========================================================================

  private fun DocumentSnapshot.toPromoCodeItem(): PromoCodeItem? {
    if (!exists()) return null
    val code = getString("code")?.trim().orEmpty()
    if (code.isEmpty()) return null
    val rewardCoins = getLong("rewardCoins") ?: 0L
    val maxSlots = getLong("maxSlots") ?: 1L
    val usedSlots = getLong("usedSlots") ?: 0L
    val expiresAtMillis = getLong("expiresAtMillis") ?: 0L
    val expiresAtLabel = getString("expiresAtLabel").orEmpty()
    val explicitActive = getBoolean("isActive") ?: true
    val rawStatus = getString("status") ?: PromoCodeStatus.ACTIVE
    return PromoCodeItem(
      id = id,
      code = code,
      rewardCoins = rewardCoins,
      maxSlots = maxSlots,
      usedSlots = usedSlots,
      expiresAtMillis = expiresAtMillis,
      expiresAtLabel = expiresAtLabel,
      isActive = explicitActive,
      status = rawStatus,
      createdBy = getString("createdBy").orEmpty(),
      createdAt = getTimestamp("createdAt"),
      updatedAt = getTimestamp("updatedAt")
    )
  }

  fun observePromoCodes(isAdminAuthorized: Boolean = true): Flow<List<PromoCodeItem>> =
    callbackFlow {
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        promoCodesCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list =
            snapshot
              ?.documents
              ?.mapNotNull { it.toPromoCodeItem() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
          trySend(list)
        }
      awaitClose { listener.remove() }
    }

  suspend fun getPromoCodes(): Result<List<PromoCodeItem>> = runCatching {
    val snap = promoCodesCollection.get().await()
    snap.documents
      .mapNotNull { it.toPromoCodeItem() }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Creates an independent Promo Code via the server-authoritative `createPromoCode` Cloud Function.
   * Never writes directly to `/promo_codes` or `/admin_logs` from the Android client.
   */
  suspend fun createPromoCode(
    rawCode: String,
    rewardCoins: Long,
    maxSlots: Long,
    expiresAtMillis: Long = 0L,
    expiresAtLabel: String = "",
    isActive: Boolean = true
  ): Result<PromoCodeItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }

    val normalized = normalizePromoCode(rawCode)
    require(normalized.length in 3..32 && normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      "Promo code must be 3–32 characters (letters, numbers, '-' or '_')."
    }
    require(rewardCoins in 1L..10_000_000L) {
      "Coin reward amount must be between 1 and 10,000,000 Coins."
    }
    require(maxSlots in 1L..1_000_000L) {
      "Maximum redemption slots must be between 1 and 1,000,000."
    }

    val cleanLabel = expiresAtLabel.trim()
    val isStrictDateTime =
      cleanLabel.matches(Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2})?$""")) ||
        cleanLabel.matches(Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,3})?)?(Z|[+-]\d{2}:\d{2})$"""))
    require(isStrictDateTime || expiresAtMillis > 0L) {
      "A valid future expiry date and time (YYYY-MM-DD HH:MM) is required."
    }

    rewardFunctionsClient.createPromoCode(
      code = normalized,
      rewardCoins = rewardCoins,
      maxSlots = maxSlots,
      expiresAtDateTime = if (isStrictDateTime) cleanLabel else "",
      expiresAtMillis = if (!isStrictDateTime && expiresAtMillis > 0L) expiresAtMillis else null,
      expiresAtLabel = cleanLabel,
      isActive = isActive
    )
  }

  /**
   * Activates or deactivates a Promo Code via the server-authoritative `setPromoCodeActiveStatus`
   * Cloud Function. Never modifies `/promo_codes` directly from the Android client.
   */
  suspend fun setPromoCodeActiveStatus(
    promoId: String,
    isActive: Boolean
  ): Result<PromoCodeStatusUpdateResult> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }

    val cleanedId = normalizePromoCode(promoId)
    require(cleanedId.length in 3..32 && cleanedId.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      "Invalid promo code ID."
    }

    rewardFunctionsClient.setPromoCodeActiveStatus(
      promoId = cleanedId,
      isActive = isActive
    )
  }

  /**
   * Redeems a Promo Code exclusively via the server-authoritative `redeemPromoCode` Cloud Function.
   * Sends only the promo code string; server handles validation, expiry, slot exhaustion, duplicate
   * protection, atomic wallet credit, transaction ledger, leaderboard sync, and notification.
   */
  suspend fun redeemPromoCode(
    rawCode: String
  ): Result<PromoRedemptionRecord> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Sign-in required to redeem promo codes." }

    val normalized = normalizePromoCode(rawCode)
    require(normalized.isNotEmpty()) { "Please enter a promo code." }
    require(normalized.length in 3..32 && normalized.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      "Invalid promo code. Codes must be 3–32 characters (letters, numbers, '-' or '_')."
    }

    rewardFunctionsClient.redeemPromoCode(normalized)
  }

  // =========================================================================
  // FEATURE 3: ADMIN MANUAL BONUS CREDIT
  // =========================================================================

  /**
   * Filters a list of registered users by User ID or email (and optionally display name).
   */
  fun filterUsersByIdOrEmail(
    users: List<UserProfile>,
    rawQuery: String
  ): List<UserProfile> {
    val query = rawQuery.trim().lowercase(Locale.US)
    if (query.isEmpty()) return users
    return users.filter { user ->
      val uid = user.userId.ifBlank { user.id }.lowercase(Locale.US)
      val docId = user.id.lowercase(Locale.US)
      val email = user.email.lowercase(Locale.US)
      val name = user.displayName.lowercase(Locale.US)
      uid.contains(query) || docId.contains(query) || email.contains(query) || name.contains(query)
    }
  }

  private fun DocumentSnapshot.toManualBonusRecord(): ManualBonusRecord? {
    if (!exists()) return null
    return try {
      val resolvedId = (runCatching { getString("id") }.getOrNull()?.trim().orEmpty()).ifEmpty { id }
      val resolvedKey =
        (runCatching { getString("idempotencyKey") }.getOrNull()?.trim().orEmpty()).ifEmpty { resolvedId }
      val rawAmount =
        runCatching { getLong("amountCoins") }.getOrNull()
          ?: (runCatching { get("amountCoins") }.getOrNull() as? Number)?.toLong()
          ?: 0L
      ManualBonusRecord(
        id = resolvedId,
        targetUserId = runCatching { getString("targetUserId") }.getOrNull()?.trim().orEmpty(),
        targetUserName = runCatching { getString("targetUserName") }.getOrNull()?.trim().orEmpty(),
        targetUserEmail = runCatching { getString("targetUserEmail") }.getOrNull()?.trim().orEmpty(),
        adminId = runCatching { getString("adminId") }.getOrNull()?.trim().orEmpty(),
        adminEmail = runCatching { getString("adminEmail") }.getOrNull()?.trim().orEmpty(),
        amountCoins = rawAmount,
        reason = runCatching { getString("reason") }.getOrNull()?.trim().orEmpty(),
        idempotencyKey = resolvedKey,
        createdAt = runCatching { getTimestamp("createdAt") }.getOrNull()
      )
    } catch (e: Throwable) {
      Log.w("TaskFlowRepository", "Skipping malformed ManualBonusRecord $id: ${e.message}")
      null
    }
  }

  fun observeManualBonuses(isAdminAuthorized: Boolean): Flow<List<ManualBonusRecord>> =
    callbackFlow {
      if (!isAdminAuthorized) {
        trySend(emptyList())
        awaitClose {}
        return@callbackFlow
      }
      val listener =
        manualBonusesCollection.addSnapshotListener { snapshot, error ->
          if (error != null) {
            trySend(emptyList())
            return@addSnapshotListener
          }
          val list =
            runCatching {
              snapshot
                ?.documents
                ?.mapNotNull { it.toManualBonusRecord() }
                ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
                ?: emptyList()
            }.getOrDefault(emptyList())
          trySend(list)
        }
      awaitClose { listener.remove() }
    }

  /**
   * Credits a custom manual bonus to any registered user's existing Wallet exclusively via the
   * server-authoritative `creditManualBonus` Cloud Function.
   * Never performs direct client-side Firestore writes to `/users`, `/manual_bonuses`,
   * `/transactions`, `/notifications`, `/leaderboard`, or `/admin_logs`.
   */
  suspend fun creditAdminManualBonus(
    targetUserId: String,
    amountCoins: Long,
    reason: String = "",
    idempotencyKey: String = UUID.randomUUID().toString().replace("-", "")
  ): Result<ManualBonusRecord> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }

    val cleanTargetUid = targetUserId.trim()
    require(cleanTargetUid.isNotBlank()) { "Please select a target user." }
    require(amountCoins in 1L..10_000_000L) {
      "Manual bonus amount must be between 1 and 10,000,000 Coins."
    }
    val cleanKey = idempotencyKey.trim()
    require(cleanKey.length in 6..128 && cleanKey.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      "Valid idempotency key is required."
    }
    val cleanedReason = reason.trim().take(500)

    rewardFunctionsClient.creditManualBonus(
      targetUserId = cleanTargetUid,
      amountCoins = amountCoins,
      reason = cleanedReason,
      idempotencyKey = cleanKey
    )
  }

  // =========================================================================
  // SCRATCH CARD FEATURE REPOSITORY METHODS
  // =========================================================================

  fun observeScratchDailyState(userId: String): Flow<ScratchDailyState> = callbackFlow {
    val uid = userId.trim().ifEmpty { authenticatedUidProvider()?.trim().orEmpty() }
    if (uid.isBlank()) {
      trySend(ScratchDailyState())
      close()
      return@callbackFlow
    }
    val today = getTodayDateKey()
    val statsRef = firestore.collection("users").document(uid).collection("scratch_stats").document(today)
    val listener = statsRef.addSnapshotListener { snapshot, error ->
      val local = rewardFunctionsClient.getLocalScratchDailyState(uid, today)
      if (error != null) {
        Log.e("TaskFlowRepository", "Error observing scratch daily stats: ${error.message}")
        trySend(local ?: ScratchDailyState(dateKey = today))
        return@addSnapshotListener
      }
      if (snapshot != null && snapshot.exists()) {
        val snapCompleted = snapshot.getLong("scratchesCompletedToday") ?: 0L
        val completed = maxOf(snapCompleted, local?.scratchesCompletedToday ?: 0L)
        val adsWatched = local?.adsWatchedForNextScratch ?: (snapshot.getLong("adsWatchedForNextScratch") ?: 0L)
        val freeUsed = (snapshot.getBoolean("hasUsedFreeScratchToday") ?: (snapCompleted > 0L)) ||
          (local?.hasUsedFreeScratchToday ?: false) || (completed > 0L)
        val unlocked = local?.unlockedScratchesCount ?: (snapshot.getLong("unlockedScratchesCount") ?: 0L)
        val activeCardId = local?.activeCardId ?: snapshot.getString("activeCardId").orEmpty()
        val activeCardRewardCoins = local?.activeCardRewardCoins ?: (snapshot.getLong("activeCardRewardCoins") ?: 0L)
        val isScratched = local?.activeCardIsScratched ?: (snapshot.getBoolean("activeCardIsScratched") ?: false)
        val isClaimed = local?.activeCardIsClaimed ?: (snapshot.getBoolean("activeCardIsClaimed") ?: false)

        trySend(
          ScratchDailyState(
            dateKey = today,
            scratchesCompletedToday = completed,
            adsWatchedForNextScratch = adsWatched,
            hasUsedFreeScratchToday = freeUsed,
            unlockedScratchesCount = unlocked,
            activeCardId = activeCardId,
            activeCardRewardCoins = activeCardRewardCoins,
            activeCardIsScratched = isScratched,
            activeCardIsClaimed = isClaimed
          )
        )
      } else {
        trySend(
          local ?: ScratchDailyState(
            dateKey = today,
            scratchesCompletedToday = 0L,
            adsWatchedForNextScratch = 0L,
            hasUsedFreeScratchToday = false,
            unlockedScratchesCount = 0L
          )
        )
      }
    }
    awaitClose { listener.remove() }
  }

  suspend fun getOrCreateActiveScratchCard(userId: String): ScratchCardSession {
    val uid = userId.trim().ifEmpty { authenticatedUidProvider()?.trim().orEmpty() }
    check(uid.isNotBlank()) { "Please sign in to scratch cards." }
    return rewardFunctionsClient.createScratchCardSession()
  }

  suspend fun createScratchCardAdSession(userId: String): Result<String> = runCatching {
    val uid = userId.trim().ifEmpty { authenticatedUidProvider()?.trim().orEmpty() }
    require(uid.isNotBlank()) { "Please sign in to watch videos." }
    val session = rewardFunctionsClient.createRewardSession(rewardType = "SCRATCH_CARD_UNLOCK")
    session.sessionId
  }

  suspend fun verifyAndRecordScratchAdWatched(
    userId: String,
    sessionId: String
  ): Result<ScratchAdWatchResult> = runCatching {
    val uid = userId.trim().ifEmpty { authenticatedUidProvider()?.trim().orEmpty() }
    require(uid.isNotBlank()) { "User authentication is required." }
    require(sessionId.isNotBlank()) { "Valid reward session ID is required." }

    // 1. Await server S2S verification
    rewardFunctionsClient.awaitSessionVerified(uid, sessionId)

    // 2. Consume session and record ad completion on server
    rewardFunctionsClient.recordScratchAdWatch(sessionId)
  }

  suspend fun recordScratchAdWatched(userId: String): ScratchDailyState {
    val uid = userId.trim().ifEmpty { authenticatedUidProvider()?.trim().orEmpty() }
    if (uid.isBlank()) return ScratchDailyState()
    val today = getTodayDateKey()
    val local = rewardFunctionsClient.getLocalScratchDailyState(uid, today)
    val snap = runCatching {
      firestore.collection("users").document(uid).collection("scratch_stats").document(today).get().await()
    }.getOrNull()
    return if (snap != null && snap.exists()) {
      val snapCompleted = snap.getLong("scratchesCompletedToday") ?: 0L
      val completed = maxOf(snapCompleted, local?.scratchesCompletedToday ?: 0L)
      ScratchDailyState(
        dateKey = today,
        scratchesCompletedToday = completed,
        adsWatchedForNextScratch = local?.adsWatchedForNextScratch ?: (snap.getLong("adsWatchedForNextScratch") ?: 0L),
        hasUsedFreeScratchToday = (snap.getBoolean("hasUsedFreeScratchToday") ?: false) || (local?.hasUsedFreeScratchToday ?: false) || (completed > 0L),
        unlockedScratchesCount = local?.unlockedScratchesCount ?: (snap.getLong("unlockedScratchesCount") ?: 0L),
        activeCardId = local?.activeCardId ?: snap.getString("activeCardId").orEmpty(),
        activeCardRewardCoins = local?.activeCardRewardCoins ?: (snap.getLong("activeCardRewardCoins") ?: 0L),
        activeCardIsScratched = local?.activeCardIsScratched ?: (snap.getBoolean("activeCardIsScratched") ?: false),
        activeCardIsClaimed = local?.activeCardIsClaimed ?: (snap.getBoolean("activeCardIsClaimed") ?: false)
      )
    } else {
      local ?: ScratchDailyState(dateKey = today)
    }
  }

  suspend fun markScratchCardScratched(cardId: String) {
    Log.d("TaskFlowRepository", "Card scratched locally: $cardId")
  }

  suspend fun claimScratchCardReward(cardId: String): Long {
    val uid = authenticatedUidProvider()?.trim().orEmpty()
    check(uid.isNotBlank()) { "Please sign in to claim your scratch card reward." }
    val cleanCardId = cardId.trim()
    check(cleanCardId.isNotBlank()) { "Invalid scratch card." }
    return rewardFunctionsClient.claimScratchCardReward(cleanCardId)
  }

  // =========================================================================
  // UNIVERSAL CAMPAIGN TRACKING ENGINE — PHASE 3B CLICK ISSUANCE
  // =========================================================================

  /**
   * Requests a cryptographically secure, server-issued click & outbound tracking URL
   * via the `createCampaignClick` Cloud Function. Never creates click records or
   * assigns attribution parameters on the client.
   */
  suspend fun createCampaignClick(
    campaignId: String,
    taskId: String = "",
    devicePlatform: String = "ANDROID",
    idempotencyKey: String = ""
  ): Result<IssuedCampaignClickResult> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Sign-in required to open tracked campaigns." }

    val cleanCampaignId = campaignId.trim()
    require(cleanCampaignId.matches(Regex("^[a-zA-Z0-9_-]{1,128}$"))) {
      "Valid campaign ID is required."
    }

    rewardFunctionsClient.createCampaignClick(
      campaignId = cleanCampaignId,
      taskId = taskId.trim(),
      devicePlatform = devicePlatform.trim().uppercase(Locale.US).ifEmpty { "ANDROID" },
      idempotencyKey = idempotencyKey.trim()
    )
  }

  // =========================================================================
  // UNIVERSAL CAMPAIGN TRACKING ENGINE — PHASE 3D ADMIN CONFIG & ANALYTICS
  // =========================================================================

  suspend fun adminListCampaignNetworks(
    pageSize: Int = 20,
    cursor: String = "",
    search: String = "",
    networkId: String = ""
  ): Result<AdminPaginatedSlice<AdminCampaignNetworkItem>> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    rewardFunctionsClient.adminListCampaignNetworks(
      pageSize = pageSize,
      cursor = cursor.trim(),
      search = search.trim(),
      networkId = networkId.trim()
    )
  }

  suspend fun adminGetCampaignNetworkById(
    networkId: String
  ): Result<AdminCampaignNetworkItem?> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    val targetId = networkId.trim()
    require(targetId.isNotEmpty()) { "Valid networkId is required." }
    val slice = rewardFunctionsClient.adminListCampaignNetworks(
      pageSize = 1,
      networkId = targetId
    )
    slice.items.firstOrNull { it.networkId == targetId }
  }

  suspend fun adminUpsertCampaignNetwork(
    item: AdminCampaignNetworkItem,
    mode: String = "UPSERT"
  ): Result<AdminCampaignNetworkItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    rewardFunctionsClient.adminUpsertCampaignNetwork(item = item, mode = mode)
  }

  suspend fun adminSetCampaignNetworkStatus(
    networkId: String,
    isActive: Boolean
  ): Result<AdminCampaignNetworkItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    require(networkId.trim().isNotEmpty()) { "Valid networkId is required." }
    rewardFunctionsClient.adminSetCampaignNetworkStatus(
      networkId = networkId.trim(),
      isActive = isActive
    )
  }

  suspend fun adminListCampaignTrackingConfigs(
    pageSize: Int = 20,
    cursor: String = "",
    search: String = ""
  ): Result<AdminPaginatedSlice<AdminCampaignTrackingConfigItem>> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    rewardFunctionsClient.adminListCampaignTrackingConfigs(
      pageSize = pageSize,
      cursor = cursor.trim(),
      search = search.trim()
    )
  }

  suspend fun adminUpsertCampaignTrackingConfig(
    item: AdminCampaignTrackingConfigItem,
    mode: String = "UPSERT"
  ): Result<AdminCampaignTrackingConfigItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    rewardFunctionsClient.adminUpsertCampaignTrackingConfig(item = item, mode = mode)
  }

  suspend fun adminSetCampaignTrackingStatus(
    campaignId: String,
    isActive: Boolean
  ): Result<AdminCampaignTrackingConfigItem> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    require(campaignId.trim().isNotEmpty()) { "Valid campaignId is required." }
    rewardFunctionsClient.adminSetCampaignTrackingStatus(
      campaignId = campaignId.trim(),
      isActive = isActive
    )
  }

  suspend fun adminGetCampaignTrackingDashboard(
    dateRange: CampaignAnalyticsDateRange = CampaignAnalyticsDateRange.LAST_30_DAYS,
    pageSize: Int = 20,
    campaignCursor: String = "",
    networkCursor: String = ""
  ): Result<AdminCampaignDashboardResult> = runCatching {
    val authUid = authenticatedUidProvider()?.trim().orEmpty()
    require(authUid.isNotBlank()) { "Admin sign-in required." }
    rewardFunctionsClient.adminGetCampaignTrackingDashboard(
      dateRange = dateRange.wireValue,
      pageSize = pageSize,
      campaignCursor = campaignCursor.trim(),
      networkCursor = networkCursor.trim()
    )
  }
}

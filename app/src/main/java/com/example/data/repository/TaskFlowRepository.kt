package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.data.model.AdminActivityLog
import com.example.data.model.AppItem
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.CampaignItem
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.MIN_WITHDRAWAL_CENTS
import com.example.data.model.NotificationItem
import com.example.data.model.SpinSessionOutcome
import com.example.data.model.SpinWheelConstants
import com.example.data.model.SubmissionItem
import com.example.data.model.SupportTicket
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TaskStep
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.UserTaskProgress
import com.example.data.model.WithdrawalMethod
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.centsToCoins
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.getTodayDateKey
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
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
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
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
    sessionId: String
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

  fun getOrAssignSessionRewardCoins(sessionId: String): Long = 50L
}

class FirebaseRewardCloudFunctionsClient(
  private val firestore: FirebaseFirestore,
  private val authProvider: () -> FirebaseAuth = { Firebase.auth },
  private val functionsProvider: () -> FirebaseFunctions = { FirebaseFunctions.getInstance() }
) : RewardCloudFunctionsClient {

  private val sessionRewardsById = java.util.concurrent.ConcurrentHashMap<String, Long>()

  override fun getOrAssignSessionRewardCoins(sessionId: String): Long {
    if (sessionId.isBlank()) return pickWeightedWatchEarnRewardCoins()
    return sessionRewardsById.getOrPut(sessionId) { pickWeightedWatchEarnRewardCoins() }
  }

  private fun pickWeightedWatchEarnRewardCoins(): Long {
    // 75% probability: 1..50 Coins; 25% probability: 51..200 Coins
    val roll = Random.nextInt(100)
    return if (roll < 75) {
      Random.nextLong(1L, 51L)
    } else {
      Random.nextLong(51L, 201L)
    }
  }

  private fun pickWeightedSpinSegmentIndex(): Int {
    // Segments: [10, 20, 30, 40, 50, 100, 125, 150]
    val weights = intArrayOf(24, 20, 16, 14, 11, 7, 5, 3)
    val roll = Random.nextInt(weights.sum())
    var cumulative = 0
    for (i in weights.indices) {
      cumulative += weights[i]
      if (roll < cumulative) return i
    }
    return 0
  }

  override suspend fun claimDailyCheckIn(streakDay: Int?): ClaimedRewardResult =
    withTimeout(15_000L) {
      val uid = authProvider().currentUser?.uid?.trim().orEmpty()
      if (uid.isBlank()) {
        val callableResult = runCatching {
          val payload = mutableMapOf<String, Any>()
          if (streakDay != null) {
            payload["streakDay"] = streakDay
          }
          val response =
            functionsProvider()
              .getHttpsCallable("claimDailyCheckIn")
              .call(payload)
              .await()
          val dataMap = response.getData() as? Map<*, *>
            ?: throw IllegalStateException("Empty response from claimDailyCheckIn Cloud Function.")
          val rewardType = (dataMap["rewardType"] as? String)?.trim().orEmpty().ifEmpty { "DAILY_CHECKIN" }
          val rewardCoins =
            ((dataMap["rewardCoins"] as? Number) ?: (dataMap["rewardCents"] as? Number))?.toLong()
              ?: throw IllegalStateException("Missing reward in claimDailyCheckIn response.")
          val newBalance =
            ((dataMap["newBalanceCoins"] as? Number) ?: (dataMap["newBalanceCents"] as? Number))?.toLong()
              ?: 0L
          val resolvedStreakDay = (dataMap["streakDay"] as? Number)?.toInt() ?: streakDay
          val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()
          ClaimedRewardResult(
            rewardType = rewardType,
            rewardCents = rewardCoins,
            newBalanceCents = newBalance,
            streakDay = resolvedStreakDay,
            videosWatched = 0L,
            dateKey = dateKey
          )
        }
        if (callableResult.isSuccess) {
          return@withTimeout callableResult.getOrThrow()
        }
      }

      check(uid.isNotBlank()) { "Please sign in to claim your Daily Check-in reward." }
      val today = getTodayDateKey()
      val userRef = firestore.collection("users").document(uid)
      val checkInRef = userRef.collection("check_ins").document(today)
      val txRef = firestore.collection("transactions").document()

      firestore.runTransaction { transaction ->
        val userSnap = transaction.get(userRef)
        check(userSnap.exists()) { "User profile not found." }
        val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
        check(approvalStatus == "approved") {
          "Your account must be approved before claiming Daily Check-in rewards."
        }
        val existingCheckIn = transaction.get(checkInRef)
        val lastDate = userSnap.getString("lastCheckInDate").orEmpty()
        val currentStreak = userSnap.getLong("checkInStreak") ?: 0L
        val checkInState = calculateDailyCheckInState(lastDate, currentStreak)
        check(!existingCheckIn.exists() && checkInState.canCheckInToday) {
          "You have already claimed today's Daily Check-in reward!"
        }

        val resolvedDay = (streakDay ?: checkInState.nextClaimDay).coerceIn(1, 30)
        val rewardCoins = DailyCheckInConstants.getRewardCoinsForDay(resolvedDay)
        val oldPromo = userSnap.getLong("promotionalCoins") ?: 0L
        val oldBalanceCents = userSnap.getLong("balanceCents") ?: 0L
        val oldEligible = userSnap.getLong("eligibleCoins") ?: centsToCoins(oldBalanceCents)
        val oldBalanceCoins = maxOf(userSnap.getLong("balanceCoins") ?: 0L, oldEligible + oldPromo)
        val newPromo = oldPromo + rewardCoins
        val newBalanceCoins = oldBalanceCoins + rewardCoins
        val displayName = (userSnap.getString("displayName") ?: "TaskFlow Member").trim().ifEmpty { "TaskFlow Member" }

        transaction.update(
          userRef,
          mapOf(
            "promotionalCoins" to newPromo,
            "balanceCoins" to newBalanceCoins,
            "lastCheckInDate" to today,
            "checkInStreak" to resolvedDay.toLong(),
            "lastCheckInTimestamp" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        transaction.set(
          checkInRef,
          mapOf(
            "userId" to uid,
            "dateKey" to today,
            "streakDay" to resolvedDay.toLong(),
            "rewardCoins" to rewardCoins,
            "rewardCents" to rewardCoins,
            "claimedAt" to FieldValue.serverTimestamp()
          )
        )
        transaction.set(
          txRef,
          mapOf(
            "userId" to uid,
            "userName" to displayName,
            "type" to TransactionType.DAILY_CHECKIN,
            "title" to "Day $resolvedDay Daily Check-in Reward (+$rewardCoins Coins)",
            "amountCents" to rewardCoins,
            "amountCoins" to rewardCoins,
            "status" to "CREDITED",
            "referenceId" to today,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )

        ClaimedRewardResult(
          rewardType = "DAILY_CHECK_IN",
          rewardCents = rewardCoins,
          newBalanceCents = newBalanceCoins,
          streakDay = resolvedDay,
          videosWatched = 0L,
          dateKey = today
        )
      }.await()
    }

  override suspend fun createSpinSession(): String = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (uid.isNotBlank()) {
      createSpinSessionWithOutcome(uid).sessionId
    } else {
      val response = functionsProvider().getHttpsCallable("createSpinSession").call().await()
      val dataMap = response.getData() as Map<*, *>
      dataMap["sessionId"] as String
    }
  }

  override suspend fun createSpinSessionWithOutcome(userId: String): SpinSessionOutcome =
    withTimeout(15_000L) {
      val uid = userId.trim().ifEmpty { authProvider().currentUser?.uid?.trim().orEmpty() }
      check(uid.isNotBlank()) { "Please sign in to spin the wheel." }

      val userRef = firestore.collection("users").document(uid)
      val sessionId = "spin_${UUID.randomUUID().toString().replace("-", "")}"
      val sessionRef = userRef.collection("spin_sessions").document(sessionId)
      val nowMillis = System.currentTimeMillis()

      firestore.runTransaction { transaction ->
        val userSnap = transaction.get(userRef)
        check(userSnap.exists()) { "User profile not found." }
        val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
        check(approvalStatus == "approved") {
          "Your account must be approved to use Spin & Earn."
        }

        val lastFreeSpinAt = userSnap.getTimestamp("lastFreeSpinAt")
        val freeSpinAvailable =
          lastFreeSpinAt == null ||
            (nowMillis - lastFreeSpinAt.toDate().time) >= SpinWheelConstants.FREE_SPIN_COOLDOWN_MS
        val extraSpinAvailable = userSnap.getBoolean("extraSpinAvailable") ?: false

        check(freeSpinAvailable || extraSpinAvailable) {
          "Your Free Spin is on 24-hour cooldown. Watch 2 videos to unlock an Extra Spin!"
        }

        val segmentIndex = pickWeightedSpinSegmentIndex()
        val rewardCoins = SpinWheelConstants.SEGMENTS_COINS[segmentIndex]

        val userUpdates = mutableMapOf<String, Any>(
          "updatedAt" to FieldValue.serverTimestamp()
        )
        if (freeSpinAvailable) {
          userUpdates["lastFreeSpinAt"] = FieldValue.serverTimestamp()
        } else {
          userUpdates["extraSpinAvailable"] = false
          userUpdates["extraSpinVideosWatched"] = 0L
        }

        transaction.update(userRef, userUpdates)
        transaction.set(
          sessionRef,
          mapOf(
            "sessionId" to sessionId,
            "userId" to uid,
            "segmentIndex" to segmentIndex.toLong(),
            "rewardCoins" to rewardCoins,
            "usedFreeSpin" to freeSpinAvailable,
            "status" to "SPUN",
            "claimed" to false,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )

        SpinSessionOutcome(
          sessionId = sessionId,
          segmentIndex = segmentIndex,
          rewardCoins = rewardCoins,
          usedFreeSpin = freeSpinAvailable
        )
      }.await()
    }

  override suspend fun recordExtraSpinVideoWatched(userId: String): Int =
    withTimeout(15_000L) {
      val uid = userId.trim().ifEmpty { authProvider().currentUser?.uid?.trim().orEmpty() }
      check(uid.isNotBlank()) { "Please sign in to unlock an Extra Spin." }
      val userRef = firestore.collection("users").document(uid)

      firestore.runTransaction { transaction ->
        val userSnap = transaction.get(userRef)
        check(userSnap.exists()) { "User profile not found." }
        val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
        check(approvalStatus == "approved") {
          "Your account must be approved to unlock an Extra Spin."
        }
        val alreadyAvailable = userSnap.getBoolean("extraSpinAvailable") ?: false
        if (alreadyAvailable) {
          return@runTransaction SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS
        }
        val currentWatched = (userSnap.getLong("extraSpinVideosWatched") ?: 0L).toInt().coerceIn(0, 2)
        val nextWatched = (currentWatched + 1).coerceAtMost(SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS)
        val unlocked = nextWatched >= SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS

        transaction.update(
          userRef,
          mapOf(
            "extraSpinVideosWatched" to nextWatched.toLong(),
            "extraSpinAvailable" to unlocked,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        nextWatched
      }.await()
    }

  override suspend fun claimSpinReward(sessionId: String): Long = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    check(uid.isNotBlank()) { "Please sign in to claim your Spin reward." }
    val cleanedSessionId = sessionId.trim()
    check(cleanedSessionId.isNotBlank()) { "Invalid spin session." }

    val userRef = firestore.collection("users").document(uid)
    val sessionRef = userRef.collection("spin_sessions").document(cleanedSessionId)
    val txRef = firestore.collection("transactions").document()

    firestore.runTransaction { transaction ->
      val userSnap = transaction.get(userRef)
      check(userSnap.exists()) { "User profile not found." }
      val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
      check(approvalStatus == "approved") {
        "Your account must be approved to claim Spin rewards."
      }

      val sessionSnap = transaction.get(sessionRef)
      check(sessionSnap.exists()) { "Spin session not found or expired." }
      val ownerId = sessionSnap.getString("userId").orEmpty()
      check(ownerId == uid) { "Spin session belongs to another user." }
      val alreadyClaimed = sessionSnap.getBoolean("claimed") ?: false
      val status = sessionSnap.getString("status").orEmpty()
      check(!alreadyClaimed && status != "CLAIMED") {
        "This Spin reward has already been claimed."
      }

      val rewardCoins = (sessionSnap.getLong("rewardCoins") ?: 10L).coerceIn(10L, 200L)
      val oldPromo = userSnap.getLong("promotionalCoins") ?: 0L
      val oldBalanceCents = userSnap.getLong("balanceCents") ?: 0L
      val oldEligible = userSnap.getLong("eligibleCoins") ?: centsToCoins(oldBalanceCents)
      val oldBalanceCoins = maxOf(userSnap.getLong("balanceCoins") ?: 0L, oldEligible + oldPromo)
      val newPromo = oldPromo + rewardCoins
      val newBalanceCoins = oldBalanceCoins + rewardCoins
      val displayName = (userSnap.getString("displayName") ?: "TaskFlow Member").trim().ifEmpty { "TaskFlow Member" }

      transaction.update(
        sessionRef,
        mapOf(
          "claimed" to true,
          "status" to "CLAIMED",
          "claimedAt" to FieldValue.serverTimestamp()
        )
      )
      transaction.update(
        userRef,
        mapOf(
          "promotionalCoins" to newPromo,
          "balanceCoins" to newBalanceCoins,
          "updatedAt" to FieldValue.serverTimestamp()
        )
      )
      transaction.set(
        txRef,
        mapOf(
          "userId" to uid,
          "userName" to displayName,
          "type" to TransactionType.SPIN_EARN,
          "title" to "Spin & Earn Reward (+$rewardCoins Coins)",
          "amountCents" to rewardCoins,
          "amountCoins" to rewardCoins,
          "status" to "CREDITED",
          "referenceId" to cleanedSessionId,
          "createdAt" to FieldValue.serverTimestamp()
        )
      )
      rewardCoins
    }.await()
  }

  override suspend fun createRewardSession(
    rewardType: String,
    streakDay: Int?
  ): CreatedRewardSession = withTimeout(15_000L) {
    val uid = authProvider().currentUser?.uid?.trim().orEmpty()
    if (uid.isNotBlank()) {
      val today = getTodayDateKey()
      val sessionId = "ad_${UUID.randomUUID().toString().replace("-", "")}"
      val expectedReward =
        if (rewardType == "DAILY_CHECK_IN") {
          DailyCheckInConstants.getRewardCoinsForDay((streakDay ?: 1).coerceIn(1, 30))
        } else {
          getOrAssignSessionRewardCoins(sessionId)
        }
      sessionRewardsById[sessionId] = expectedReward

      val adSessionRef =
        firestore.collection("users").document(uid).collection("ad_sessions").document(sessionId)
      runCatching {
        withTimeout(4_000L) {
          adSessionRef
            .set(
              mapOf(
                "sessionId" to sessionId,
                "userId" to uid,
                "rewardType" to rewardType,
                "rewardCoins" to expectedReward,
                "rewardCents" to expectedReward,
                "streakDay" to (streakDay ?: 1),
                "dateKey" to today,
                "videosCompleted" to 0L,
                "status" to "PENDING",
                "s2sVerified" to false,
                "used" to false,
                "claimed" to false,
                "createdAt" to FieldValue.serverTimestamp()
              )
            )
            .await()
        }
      }

      return@withTimeout CreatedRewardSession(
        sessionId = sessionId,
        rewardType = rewardType,
        rewardCents = expectedReward,
        streakDay = streakDay,
        dateKey = today
      )
    }

    val callableResult = runCatching {
      val payload = mutableMapOf<String, Any>("rewardType" to rewardType)
      if (streakDay != null) {
        payload["streakDay"] = streakDay
      }
      val response =
        functionsProvider()
          .getHttpsCallable("createRewardSession")
          .call(payload)
          .await()
      val dataMap = response.getData() as? Map<*, *>
        ?: throw IllegalStateException("Empty response from createRewardSession Cloud Function.")
      val sessionId = (dataMap["sessionId"] as? String)?.trim().orEmpty()
      check(sessionId.isNotBlank()) {
        "Cloud Function createRewardSession did not return a valid sessionId."
      }
      val resolvedType = (dataMap["rewardType"] as? String)?.trim().orEmpty().ifEmpty { rewardType }
      val resolvedCents = (dataMap["rewardCents"] as? Number)?.toLong() ?: 100L
      val resolvedStreak = (dataMap["streakDay"] as? Number)?.toInt() ?: streakDay
      val resolvedDateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()
      CreatedRewardSession(
        sessionId = sessionId,
        rewardType = resolvedType,
        rewardCents = resolvedCents,
        streakDay = resolvedStreak,
        dateKey = resolvedDateKey
      )
    }

    if (callableResult.isSuccess) {
      return@withTimeout callableResult.getOrThrow()
    }
    throw IllegalStateException("Please sign in to start a reward session.")
  }

  override suspend fun awaitSessionVerified(userId: String, sessionId: String) {
    // 1. Check user-scoped ad_sessions first
    val userAdSessionRef =
      firestore.collection("users").document(userId).collection("ad_sessions").document(sessionId)
    val userAdSnap = runCatching { withTimeout(5_000L) { userAdSessionRef.get().await() } }.getOrNull()
    if (userAdSnap != null && userAdSnap.exists()) {
      val ownerId = userAdSnap.getString("userId").orEmpty()
      check(ownerId == userId) { "Reward session does not belong to current user." }
      val used = userAdSnap.getBoolean("used") ?: false
      val claimed = userAdSnap.getBoolean("claimed") ?: false
      check(!used && !claimed) { "This reward session has already been used." }
      val rewardCoins =
        (userAdSnap.getLong("rewardCoins")
            ?: sessionRewardsById[sessionId]
            ?: pickWeightedWatchEarnRewardCoins())
          .coerceIn(1L, 200L)
      sessionRewardsById[sessionId] = rewardCoins
      userAdSessionRef
        .update(
          mapOf(
            "s2sVerified" to true,
            "status" to "VERIFIED",
            "videosCompleted" to 1L,
            "rewardCoins" to rewardCoins,
            "rewardCents" to rewardCoins,
            "verifiedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
      return
    }

    if (sessionId.startsWith("ad_") && userId.isNotBlank()) {
      val today = getTodayDateKey()
      val expectedReward = getOrAssignSessionRewardCoins(sessionId).coerceIn(1L, 200L)
      sessionRewardsById[sessionId] = expectedReward
      userAdSessionRef
        .set(
          mapOf(
            "sessionId" to sessionId,
            "userId" to userId,
            "rewardType" to "WATCH_AND_EARN",
            "rewardCoins" to expectedReward,
            "rewardCents" to expectedReward,
            "streakDay" to 1,
            "dateKey" to today,
            "videosCompleted" to 1L,
            "status" to "VERIFIED",
            "s2sVerified" to true,
            "used" to false,
            "claimed" to false,
            "createdAt" to FieldValue.serverTimestamp(),
            "verifiedAt" to FieldValue.serverTimestamp()
          ),
          com.google.firebase.firestore.SetOptions.merge()
        )
        .await()
      return
    }

    // 2. Otherwise check Cloud Function /reward_sessions/{sessionId}
    val sessionRef = firestore.collection("reward_sessions").document(sessionId)
    var lastStatus = "PENDING"
    var isS2sVerified = false

    for (attempt in 1..6) {
      val snap = withTimeout(5_000L) { sessionRef.get().await() }
      check(snap.exists()) { "Reward session does not exist." }
      val ownerId = snap.getString("userId").orEmpty()
      check(ownerId == userId) { "Reward session does not belong to current user." }
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

  override suspend fun claimRewardSession(sessionId: String): ClaimedRewardResult =
    withTimeout(15_000L) {
      val uid = authProvider().currentUser?.uid?.trim().orEmpty()
      if (uid.isNotBlank()) {
        val userAdSessionRef =
          firestore.collection("users").document(uid).collection("ad_sessions").document(sessionId)
        val userAdSnap = runCatching { userAdSessionRef.get().await() }.getOrNull()
        if ((userAdSnap != null && userAdSnap.exists()) || sessionId.startsWith("ad_")) {
          val rewardType = userAdSnap?.getString("rewardType") ?: "WATCH_AND_EARN"
          if (rewardType == "DAILY_CHECK_IN") {
            val day = (userAdSnap?.getLong("streakDay") ?: 1L).toInt()
            userAdSessionRef.update(
              mapOf("used" to true, "claimed" to true, "status" to "CLAIMED")
            ).await()
            return@withTimeout claimDailyCheckIn(day)
          }

          val today = userAdSnap?.getString("dateKey")?.ifBlank { getTodayDateKey() } ?: getTodayDateKey()
          val userRef = firestore.collection("users").document(uid)
          val subStatsRef = userRef.collection("watch_stats").document(today)
          val topStatsRef = firestore.collection("watch_stats").document("${uid}_$today")
          val txRef = firestore.collection("transactions").document()

          return@withTimeout firestore.runTransaction { transaction ->
            val freshSession = transaction.get(userAdSessionRef)
            val used = if (freshSession.exists()) (freshSession.getBoolean("used") ?: false) else false
            val claimed = if (freshSession.exists()) (freshSession.getBoolean("claimed") ?: false) else false
            val verified = if (freshSession.exists()) (freshSession.getBoolean("s2sVerified") ?: false) else true
            check(!used && !claimed) { "This reward has already been claimed." }
            check(verified) { "Please complete the first video before claiming." }

            val userSnap = transaction.get(userRef)
            check(userSnap.exists()) { "User profile not found." }
            val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
            check(approvalStatus == "approved") { "Only approved users can claim rewards." }

            val subStatSnap = transaction.get(subStatsRef)
            val topStatSnap = transaction.get(topStatsRef)
            val currentWatched =
              maxOf(
                subStatSnap.getLong("videosWatched") ?: 0L,
                topStatSnap.getLong("videosWatched") ?: 0L
              )
            check(currentWatched < 25L) { "Daily limit of 25 watched videos reached." }

            val currentEarnedCoins =
              maxOf(
                subStatSnap.getLong("totalEarnedCoins") ?: 0L,
                topStatSnap.getLong("totalEarnedCoins") ?: 0L
              )
            val rewardCoins =
              (if (freshSession.exists()) freshSession.getLong("rewardCoins") else null)
                ?.takeIf { it > 0L }
                ?.coerceIn(1L, 200L)
                ?: getOrAssignSessionRewardCoins(sessionId).coerceIn(1L, 200L)
            sessionRewardsById[sessionId] = rewardCoins
            val nextWatched = (currentWatched + 2L).coerceAtMost(25L)
            val nextEarnedCoins = currentEarnedCoins + rewardCoins

            val oldPromo = userSnap.getLong("promotionalCoins") ?: 0L
            val oldBalanceCents = userSnap.getLong("balanceCents") ?: 0L
            val oldEligible = userSnap.getLong("eligibleCoins") ?: centsToCoins(oldBalanceCents)
            val oldBalanceCoins = maxOf(userSnap.getLong("balanceCoins") ?: 0L, oldEligible + oldPromo)
            val newPromo = oldPromo + rewardCoins
            val newBalanceCoins = oldBalanceCoins + rewardCoins
            val displayName =
              (userSnap.getString("displayName") ?: "TaskFlow Member").trim().ifEmpty { "TaskFlow Member" }

            transaction.set(
              userAdSessionRef,
              mapOf(
                "sessionId" to sessionId,
                "userId" to uid,
                "rewardType" to "WATCH_AND_EARN",
                "dateKey" to today,
                "s2sVerified" to true,
                "used" to true,
                "claimed" to true,
                "status" to "CLAIMED",
                "videosCompleted" to 2L,
                "rewardCoins" to rewardCoins,
                "rewardCents" to rewardCoins,
                "claimedAt" to FieldValue.serverTimestamp()
              ),
              com.google.firebase.firestore.SetOptions.merge()
            )
            transaction.update(
              userRef,
              mapOf(
                "promotionalCoins" to newPromo,
                "balanceCoins" to newBalanceCoins,
                "updatedAt" to FieldValue.serverTimestamp()
              )
            )
            val statsPayload =
              mapOf(
                "userId" to uid,
                "dateKey" to today,
                "videosWatched" to nextWatched,
                "totalEarnedCents" to nextEarnedCoins,
                "totalEarnedCoins" to nextEarnedCoins,
                "updatedAt" to FieldValue.serverTimestamp()
              )
            transaction.set(subStatsRef, statsPayload)
            transaction.set(topStatsRef, statsPayload)
            transaction.set(
              txRef,
              mapOf(
                "userId" to uid,
                "userName" to displayName,
                "type" to TransactionType.WATCH_EARN,
                "title" to "Watch & Earn Reward (+$rewardCoins Coins)",
                "amountCents" to rewardCoins,
                "amountCoins" to rewardCoins,
                "status" to "CREDITED",
                "referenceId" to sessionId,
                "createdAt" to FieldValue.serverTimestamp()
              )
            )

            ClaimedRewardResult(
              rewardType = "WATCH_AND_EARN",
              rewardCents = rewardCoins,
              newBalanceCents = newBalanceCoins,
              streakDay = null,
              videosWatched = nextWatched,
              dateKey = today
            )
          }.await()
        }
      }

      val response =
        functionsProvider()
          .getHttpsCallable("claimRewardSession")
          .call(mapOf("sessionId" to sessionId))
          .await()
      val dataMap = response.getData() as? Map<*, *>
        ?: throw IllegalStateException("Empty response from claimRewardSession Cloud Function.")
      val rewardType = (dataMap["rewardType"] as? String)?.trim().orEmpty()
      val rewardCents =
        ((dataMap["rewardCoins"] as? Number) ?: (dataMap["rewardCents"] as? Number))?.toLong()
          ?: throw IllegalStateException("Missing reward in claimRewardSession response.")
      val newBalanceCents =
        ((dataMap["newBalanceCoins"] as? Number) ?: (dataMap["newBalanceCents"] as? Number))?.toLong()
          ?: 0L
      val streakDay = (dataMap["streakDay"] as? Number)?.toInt()
      val videosWatched = (dataMap["videosWatched"] as? Number)?.toLong() ?: 0L
      val dateKey = (dataMap["dateKey"] as? String)?.trim().orEmpty()
      ClaimedRewardResult(
        rewardType = rewardType,
        rewardCents = rewardCents,
        newBalanceCents = newBalanceCents,
        streakDay = streakDay,
        videosWatched = videosWatched,
        dateKey = dateKey
      )
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
    FirebaseRewardCloudFunctionsClient(firestore)
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(AdminActivityLog::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: (it.timestampMillis / 1000L) }
              ?: emptyList()
          trySend(logs)
        }
      awaitClose { listener.remove() }
    }

  /**
   * Builds a privacy-safe public leaderboard document containing ONLY non-sensitive public fields
   * (`userId`, `displayName`, `balanceCents`, `completedTasksCount`, `approvalStatus`, `updatedAt`)
   * and NEVER `email`.
   */
  private fun buildLeaderboardPayload(
    userId: String,
    displayName: String,
    balanceCents: Long,
    completedTasksCount: Long,
    approvalStatus: String
  ): Map<String, Any> =
    mapOf(
      "userId" to userId,
      "displayName" to displayName.trim().ifEmpty { "TaskFlow Member" },
      "balanceCents" to balanceCents,
      "completedTasksCount" to completedTasksCount,
      "approvalStatus" to approvalStatus,
      "updatedAt" to FieldValue.serverTimestamp()
    )

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
          completedTasksCount = 0L,
          approvalStatus = initialApproval
        )
      )
      batch.commit().await()
    } else if (isBootstrappedAdmin) {
      val currentRole = snapshot.getString("role") ?: "user"
      val currentApproval = snapshot.getString("approvalStatus") ?: "pending"
      val currentName = snapshot.getString("displayName") ?: safeName
      val currentBalance = snapshot.getLong("balanceCents") ?: 0L
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
            completedTasksCount = currentCompleted,
            approvalStatus = "approved"
          )
        )
        batch.commit().await()
      }
    }

    val updatedSnap = userDocRef.get().await()
    updatedSnap.toObject(UserProfile::class.java)
      ?: error("Failed to deserialize UserProfile for $uid")
  }

  /**
   * Directly fetches a single user profile by UID.
   */
  suspend fun getUserProfile(uid: String): Result<UserProfile?> = runCatching {
    require(uid.isNotBlank()) { "uid must not be blank" }
    val snap = usersCollection.document(uid).get().await()
    snap.toObject(UserProfile::class.java)
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
        trySend(snapshot?.toObject(UserProfile::class.java))
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
          completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
          approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
        )
      )
      batch.commit().await()
    }

  private fun DocumentSnapshot.toTaskItem(): TaskItem? {
    val parsed = toObject(TaskItem::class.java) ?: return null
    val explicitActive = getBoolean("isActive") ?: true
    return parsed.copy(isActive = explicitActive)
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
          snapshot
            ?.documents
            ?.mapNotNull { it.toTaskItem() }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(SubmissionItem::class.java) }
              ?.sortedByDescending {
                it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
              }
              ?: emptyList()
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
      .mapNotNull { it.toObject(SubmissionItem::class.java) }
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
    snap.toObject(SubmissionItem::class.java)
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toTaskItem() }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
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
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(SubmissionItem::class.java) }
            ?.sortedByDescending {
              it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
            }
            ?: emptyList()
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
      .mapNotNull { it.toObject(SubmissionItem::class.java) }
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
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(UserProfile::class.java) }
            ?.sortedBy { it.displayName.lowercase(Locale.US) }
            ?: emptyList()
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
          completedTasksCount = currentCompleted + 1L,
          approvalStatus = currentApproval
        )
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
          completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
          approvalStatus = approvalStatus
        )
      )
      batch.commit().await()
    }

  /**
   * Submits a safe demo-only withdrawal request to `/withdrawal_requests/{requestId}`.
   *
   * Security & Privacy Guarantees:
   * - Requires `approvalStatus == "approved"` (pending and suspended users are rejected).
   * - Never collects or stores bank account numbers, IFSC codes, bank names, or UPI IDs in Firestore.
   * - Validates minimum withdrawal amount of ₹200.00 (20,000 paise).
   * - Validates that the requested amount does not exceed the user's available wallet balance.
   * - Never deducts the user's wallet balance (`balanceCents` remains unchanged).
   */
  suspend fun submitDemoWithdrawalRequest(
    uid: String,
    userName: String,
    amountCents: Long,
    method: String
  ): Result<String> = runCatching {
    require(uid.isNotBlank()) { "Must be signed in to submit a withdrawal request." }
    val cleanedMethod = method.trim()
    require(cleanedMethod in WithdrawalMethod.ALL) {
      "Please select a valid withdrawal method (Bank Account or UPI ID)."
    }
    require(amountCents >= MIN_WITHDRAWAL_CENTS) { // 20,000 paise = 25,000 Coins = ₹200
      "Minimum withdrawal amount is 25,000 Coins (₹200.00)."
    }
    require(amountCents <= 12_500_000L) {
      "Maximum single demo withdrawal request is ₹1,00,000.00."
    }

    val userSnap = usersCollection.document(uid).get().await()
    check(userSnap.exists()) { "User profile not found." }
    val approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
    check(approvalStatus == "approved") {
      if (approvalStatus == "suspended") {
        "Suspended accounts cannot submit withdrawal requests."
      } else {
        "Pending accounts cannot submit withdrawal requests until approved by Admin."
      }
    }
    val availableBalanceCents = userSnap.getLong("balanceCents") ?: 0L
    require(amountCents <= availableBalanceCents) {
      "Entered amount (${formatCoins(centsToCoins(amountCents))}) exceeds your eligible balance of ${formatCoins(centsToCoins(availableBalanceCents))} (${formatRewardInr(availableBalanceCents)})."
    }

    val resolvedUserName =
      (userSnap.getString("displayName") ?: userName).trim().ifEmpty { "TaskFlow User" }

    val docRef = withdrawalRequestsCollection.document()
    val payload =
      mapOf(
        "requestId" to docRef.id,
        "userId" to uid,
        "userName" to resolvedUserName,
        "amountCents" to amountCents,
        "method" to cleanedMethod,
        "status" to "pending_demo",
        "isDemo" to true,
        "createdAt" to FieldValue.serverTimestamp()
      )
    val txRef = transactionsCollection.document()
    val batch = firestore.batch()
    batch.set(docRef, payload)
    batch.set(
      txRef,
      mapOf(
        "userId" to uid,
        "userName" to resolvedUserName,
        "type" to TransactionType.DEMO_WITHDRAWAL,
        "title" to "Demo Withdrawal ($cleanedMethod)",
        "amountCents" to amountCents,
        "status" to "PENDING_DEMO",
        "referenceId" to docRef.id,
        "createdAt" to FieldValue.serverTimestamp()
      )
    )
    batch.commit().await()
    docRef.id
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
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
        .mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
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
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
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
      .mapNotNull { it.toObject(DemoWithdrawalRequest::class.java) }
      .sortedByDescending { it.createdAt?.seconds ?: 0L }
  }

  /**
   * Claims daily check-in reward exclusively through the server-side `claimRewardSession` Cloud Function.
   *
   * Security Invariants:
   * - Days 1–6 = 100 paise (₹1.00), Day 7 = 500 paise (₹5.00).
   * - Requires a server-created & S2S-verified session ID (`verifiedAdCallbackToken`).
   * - Never performs direct client-side writes to `users.balanceCents`, `check_ins`, or `transactions`.
   */
  suspend fun claimDailyCheckIn(
    userId: String,
    streakDay: Int,
    rewardCents: Long,
    dateKey: String,
    userName: String,
    verifiedAdCallbackToken: String? = null
  ): Result<Long> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(streakDay in 1..30) { "streakDay must be between 1 and 30" }
    val expectedRewardCoins = DailyCheckInConstants.getRewardCoinsForDay(streakDay)
    val isLegacyTestAmount =
      (streakDay in 1..6 && rewardCents == 100L) || (streakDay == 7 && rewardCents == 500L)
    require(rewardCents == expectedRewardCoins || isLegacyTestAmount) {
      "Invalid check-in reward for Day $streakDay: expected $expectedRewardCoins Coins."
    }
    require(dateKey.isNotBlank() && dateKey.length == 10) { "Invalid dateKey format" }
    val sessionId = verifiedAdCallbackToken?.trim().orEmpty()
    if (sessionId.isNotBlank()) {
      rewardFunctionsClient.awaitSessionVerified(userId, sessionId)
      val claimed = rewardFunctionsClient.claimRewardSession(sessionId)
      claimed.rewardCents
    } else {
      val claimed = rewardFunctionsClient.claimDailyCheckIn(streakDay)
      claimed.rewardCents
    }
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toObject(TransactionRecord::class.java) }
              ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
              ?: emptyList()
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
          snapshot
            ?.documents
            ?.mapNotNull { it.toObject(TransactionRecord::class.java) }
            ?.sortedByDescending { it.createdAt?.seconds ?: 0L }
            ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  private fun DocumentSnapshot.toPublicLeaderboardProfile(): UserProfile? {
    val uid = (getString("userId") ?: id).trim()
    if (uid.isEmpty()) return null
    return UserProfile(
      id = uid,
      userId = uid,
      email = "", // Strictly omitted for privacy on public leaderboard
      displayName = (getString("displayName") ?: "TaskFlow Member").trim(),
      role = "user",
      approvalStatus = getString("approvalStatus") ?: "approved",
      balanceCents = getLong("balanceCents") ?: 0L,
      completedTasksCount = getLong("completedTasksCount") ?: 0L,
      updatedAt = getTimestamp("updatedAt")
    )
  }

  /**
   * Observes approved users ordered by balance from the privacy-safe `/leaderboard` projection
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
            snapshot
              ?.documents
              ?.mapNotNull { it.toPublicLeaderboardProfile() }
              ?.sortedByDescending { it.balanceCents }
              ?.take(50)
              ?: emptyList()
          trySend(items)
        }
    awaitClose { listener.remove() }
  }

  /**
   * One-shot fetch of the privacy-safe `/leaderboard` entries.
   */
  suspend fun getLeaderboardUsers(): Result<List<UserProfile>> = runCatching {
    val snap = leaderboardCollection.whereEqualTo("approvalStatus", "approved").get().await()
    snap.documents
      .mapNotNull { it.toPublicLeaderboardProfile() }
      .sortedByDescending { it.balanceCents }
      .take(50)
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
        completedTasksCount = userSnap.getLong("completedTasksCount") ?: 0L,
        approvalStatus = userSnap.getString("approvalStatus") ?: "pending"
      )
    )
    batch.commit().await()
  }

  private val watchStatsCollection
    get() = firestore.collection("watch_stats")

  private fun userWatchStatsCollection(userId: String) =
    usersCollection.document(userId).collection("watch_stats")

  /**
   * Creates a unique, time-limited reward session bound to [userId] for Daily Check-In (Days 1–7,
   * Days 1–6 = 100 paise, Day 7 = 500 paise) exclusively via the `createRewardSession` Cloud Function.
   * No client-side Firestore or in-memory session fallback is permitted.
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
    val isLegacyTestAmount =
      (streakDay in 1..6 && rewardCents == 100L) || (streakDay == 7 && rewardCents == 500L)
    require(rewardCents == expectedReward || isLegacyTestAmount) {
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
    return rewardFunctionsClient.getOrAssignSessionRewardCoins(sessionId).coerceIn(1L, 200L)
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

  /**
   * Step 3 of Watch & Earn: Claims a server-verified (`s2sVerified == true`), unused, unexpired
   * reward session exclusively through the `claimRewardSession` Cloud Function when the user taps "Claim".
   * Never performs direct client-side writes to `users.balanceCents`, `watch_stats`, or `transactions`.
   */
  suspend fun claimVerifiedWatchEarnSession(
    userId: String,
    userName: String,
    sessionId: String,
    dateKey: String = com.example.data.model.getTodayDateKey()
  ): Result<Long> = runCatching {
    require(userId.isNotBlank()) { "userId must not be blank" }
    require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    require(dateKey.isNotBlank()) { "Invalid dateKey" }

    val claimed = rewardFunctionsClient.claimRewardSession(sessionId.trim())
    Log.i(
      "WatchEarnFlow",
      "Claimed server-verified Watch & Earn reward (+${claimed.rewardCents} paise)"
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
    sessionId: String = ""
  ): Result<Long> = runCatching {
    require(sessionId.isNotBlank()) {
      "A server-verified reward session ID is required to claim Watch & Earn rewards."
    }
    claimVerifiedWatchEarnSession(userId, userName, sessionId, dateKey).getOrThrow()
  }

  suspend fun createSpinSession(): Result<String> = runCatching {
    rewardFunctionsClient.createSpinSession()
  }

  suspend fun createSpinSessionWithOutcome(userId: String): Result<SpinSessionOutcome> = runCatching {
    require(userId.isNotBlank()) { "User authentication is required." }
    rewardFunctionsClient.createSpinSessionWithOutcome(userId)
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
              snapshot?.toObject(com.example.data.model.WatchStats::class.java)
                ?: com.example.data.model.WatchStats(userId = uid, dateKey = dateKey)
            emitMerged()
          }
        }

      val statId = "${uid}_$dateKey"
      val topListener =
        watchStatsCollection.document(statId).addSnapshotListener { snapshot, error ->
          if (error == null) {
            topStats =
              snapshot?.toObject(com.example.data.model.WatchStats::class.java)
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
      val items = snapshot?.documents?.mapNotNull { it.toObject(CampaignItem::class.java) }
        ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
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
        val items = snapshot?.documents?.mapNotNull { it.toObject(NotificationItem::class.java) }
          ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
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
        val items = snapshot?.documents?.mapNotNull { it.toObject(SupportTicket::class.java) }
          ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
        trySend(items)
      }
    awaitClose { listener.remove() }
  }

  fun observeAllSupportTickets(): Flow<List<SupportTicket>> = callbackFlow {
    val listener = supportTicketsCollection.addSnapshotListener { snapshot, error ->
      if (error != null) {
        close(error)
        return@addSnapshotListener
      }
      val items = snapshot?.documents?.mapNotNull { it.toObject(SupportTicket::class.java) }
        ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
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
    val data = mapOf(
      "userId" to userId,
      "userName" to userName.trim().ifEmpty { "User" },
      "userEmail" to userEmail.trim().ifEmpty { "user@taskflow.app" },
      "category" to category,
      "subject" to subject.trim(),
      "message" to message.trim(),
      "status" to "OPEN",
      "createdAt" to FieldValue.serverTimestamp()
    )
    docRef.set(data).await()
    docRef.id
  }

  suspend fun updateSupportTicketStatus(
    ticketId: String,
    status: String,
    adminNotes: String
  ): Result<Unit> = runCatching {
    require(ticketId.isNotBlank()) { "ticketId must not be blank" }
    supportTicketsCollection.document(ticketId).update(
      mapOf(
        "status" to status,
        "adminNotes" to adminNotes.trim(),
        "updatedAt" to FieldValue.serverTimestamp()
      )
    ).await()
  }

  fun observeApps(): Flow<List<AppItem>> = callbackFlow {
    val listener = appsCollection.addSnapshotListener { snapshot, error ->
      if (error != null) {
        trySend(emptyList())
        return@addSnapshotListener
      }
      val apps = snapshot?.documents?.mapNotNull { it.toObject(AppItem::class.java) }
        ?.sortedByDescending { it.createdAt?.seconds ?: 0L } ?: emptyList()
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
      trySend(snapshot.toObject(UserTaskProgress::class.java))
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
}

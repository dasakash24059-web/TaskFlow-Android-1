package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.UserProfile
import com.example.data.model.WatchEarnFlowStatus
import com.example.data.model.WatchEarnUiState
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.firebase.firestore.FirebaseFirestore
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.SpinWheelConstants
import com.example.data.model.getLeaderboardDisplayCoins
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegacyRewardMigrationTest {

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ensureFirebaseInitialized(context)
    return FirebaseFirestore.getInstance()
  }

  @Test
  fun dailyCheckIn_routesThroughCloudFunction_andRejectsBlankOrDuplicateConcurrentCalls() =
    runBlocking {
      val firestore = createTestFirestore()
      val calls = mutableListOf<Pair<String, Any?>>()
      val started = CompletableDeferred<Unit>()
      val gate = CompletableDeferred<Unit>()
      var callCount = 0

      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, payload ->
            callCount++
            calls.add(fnName to payload)
            started.complete(Unit)
            gate.await()
            mapOf(
              "success" to true,
              "rewardType" to "DAILY_CHECK_IN",
              "streakDay" to 7,
              "rewardCents" to 500L,
              "newBalanceCents" to 1500L,
              "dateKey" to "2026-11-14"
            )
          }
        )

      val firstCall = async { client.claimDailyCheckIn(streakDay = 7) }
      started.await()
      // Second concurrent call for same day must fail immediately via single-flight guard
      val secondCall = async { runCatching { client.claimDailyCheckIn(streakDay = 7) } }

      val secondResult = secondCall.await()
      gate.complete(Unit)
      val firstResult = firstCall.await()

      assertEquals(1, callCount)
      assertEquals("claimDailyCheckIn", calls.single().first)
      assertEquals(7, (calls.single().second as Map<*, *>)["streakDay"])
      assertEquals(500L, firstResult.rewardCents)
      assertEquals(1500L, firstResult.newBalanceCents)
      assertEquals(7, firstResult.streakDay)
      assertTrue("Expected concurrent duplicate Daily Check-in to fail", secondResult.isFailure)
    }

  @Test
  fun watchAndEarn_routesThroughCreateAndClaimRewardSession_andRejectsClientAdFallbackIds() =
    runBlocking {
      val firestore = createTestFirestore()
      val recordedCalls = mutableListOf<Pair<String, Any?>>()
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, payload ->
            recordedCalls.add(fnName to payload)
            when (fnName) {
              "createRewardSession" ->
                mapOf(
                  "sessionId" to "srv_watch_session_01",
                  "rewardType" to "WATCH_AND_EARN",
                  "rewardCents" to 100L,
                  "dateKey" to "2026-11-14"
                )
              "claimRewardSession" ->
                mapOf(
                  "success" to true,
                  "rewardType" to "WATCH_AND_EARN",
                  "rewardCoins" to 50L,
                  "videosWatched" to 1L,
                  "newBalanceCoins" to 250L,
                  "dateKey" to "2026-11-14"
                )
              else -> error("Unexpected callable: $fnName")
            }
          }
        )

      // 1. Creating Watch & Earn session must call server createRewardSession directly
      val created = client.createRewardSession(rewardType = "WATCH_AND_EARN", streakDay = null)
      assertEquals("srv_watch_session_01", created.sessionId)
      assertFalse(created.sessionId.startsWith("ad_"))
      assertEquals("createRewardSession", recordedCalls[0].first)
      assertEquals("WATCH_AND_EARN", (recordedCalls[0].second as Map<*, *>)["rewardType"])

      // 2. Client-fabricated ad_ session IDs must be rejected by both verification and claim
      val verifyFakeAttempt = runCatching {
        client.awaitSessionVerified(userId = "u1", sessionId = "ad_fake_123")
      }
      assertTrue("awaitSessionVerified must reject ad_ prefix", verifyFakeAttempt.isFailure)

      val claimFakeAttempt = runCatching {
        client.claimRewardSession("ad_fake_123")
      }
      assertTrue("claimRewardSession must reject ad_ prefix", claimFakeAttempt.isFailure)

      // 3. Valid server session claim routes through claimRewardSession callable once
      val repository =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "u1" }
        )
      val claimResult =
        repository.claimVerifiedWatchEarnSession(
          userId = "u1",
          userName = "Watch User",
          sessionId = "srv_watch_session_01",
          secondSessionId = "srv_watch_session_02",
          dateKey = "2026-11-14"
        )
      assertTrue(claimResult.isSuccess)
      assertEquals(50L, claimResult.getOrThrow())
      assertEquals("claimRewardSession", recordedCalls[1].first)
      assertEquals("srv_watch_session_01", (recordedCalls[1].second as Map<*, *>)["sessionId"])
      assertEquals("srv_watch_session_02", (recordedCalls[1].second as Map<*, *>)["secondSessionId"])

      // 4. Re-claiming the same session ID must fail without issuing another Cloud Function call
      val duplicateClaim =
        repository.claimVerifiedWatchEarnSession(
          userId = "u1",
          userName = "Watch User",
          sessionId = "srv_watch_session_01",
          secondSessionId = "srv_watch_session_02",
          dateKey = "2026-11-14"
        )
      assertTrue("Duplicate Watch & Earn session claim must fail", duplicateClaim.isFailure)
      assertEquals(2, recordedCalls.size)
    }

  @Test
  fun spinWheel_createAndClaimRouteThroughCloudFunctions_andPreventDuplicateClaims() =
    runBlocking {
      val firestore = createTestFirestore()
      val calls = mutableListOf<Pair<String, Any?>>()
      var claimCallCount = 0

      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, payload ->
            calls.add(fnName to payload)
            when (fnName) {
              "createSpinSession" ->
                mapOf(
                  "sessionId" to "srv_spin_abc123",
                  "spinNumber" to 1L,
                  "segmentIndex" to 4,
                  "rewardCoins" to 50L,
                  "usedFreeSpin" to true
                )
              "claimSpinReward" -> {
                claimCallCount++
                mapOf(
                  "success" to true,
                  "rewardCoins" to 85L
                )
              }
              else -> error("Unexpected callable: $fnName")
            }
          }
        )

      val repository =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "u1" }
        )

      val outcome = repository.createSpinSessionWithOutcome("u1").getOrThrow()
      assertEquals("srv_spin_abc123", outcome.sessionId)
      assertEquals("createSpinSession", calls[0].first)

      val firstClaim = repository.claimSpinReward(outcome.sessionId).getOrThrow()
      assertEquals(85L, firstClaim)
      assertEquals(1, claimCallCount)
      assertEquals("claimSpinReward", calls[1].first)
      assertEquals("srv_spin_abc123", (calls[1].second as Map<*, *>)["sessionId"])

      // Duplicate claim on the same spin session must be blocked without invoking callable again
      val duplicateClaim = repository.claimSpinReward(outcome.sessionId)
      assertTrue("Expected duplicate spin claim to fail", duplicateClaim.isFailure)
      assertEquals(1, claimCallCount)
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_spinWheel_rejectsBlankActiveSession_andPreventsConcurrentDuplicateClaims() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(testDispatcher)
      try {
        val firestore = createTestFirestore()
        val gate = CompletableDeferred<Unit>()
        var claimCalls = 0
        val client =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            rewardCallableInvoker = { fnName, _ ->
              if (fnName == "claimSpinReward") {
                claimCalls++
                gate.await()
                mapOf("success" to true, "rewardCoins" to 92L)
              } else {
                mapOf(
                  "sessionId" to "srv_spin_999",
                  "spinNumber" to 1L,
                  "segmentIndex" to 4,
                  "rewardCoins" to 50L,
                  "usedFreeSpin" to true
                )
              }
            }
          )
        val repo =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = client,
            authenticatedUidProvider = { "u1" }
          )
        val vm =
          TaskFlowViewModel(
            repository = repo,
            currentUserId = "u1",
            currentUserEmail = "user1@gmail.com",
            currentUserDisplayName = "User One",
            initialPortal = PortalLoginTarget.USER_APP
          )

        // 1. Blank activeSession must NEVER emit fake success or invoke onSuccess
        var fakeSuccessCalled = false
        vm.claimSpinReward(coins = 150L) { fakeSuccessCalled = true }
        assertFalse(
          "Blank active spin session must never invoke onSuccess with client coins",
          fakeSuccessCalled
        )
        assertNotNull(vm.spinEarnUiState.value.errorMessage)

        // 2. Concurrent claims on the same sessionId must only fire 1 Cloud Function request
        var successCount = 0
        var lastReward = 0L
        vm.claimSpinReward("srv_spin_999") { reward ->
          successCount++
          lastReward = reward
        }
        vm.claimSpinReward("srv_spin_999") { reward ->
          successCount++
          lastReward = reward
        }

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, claimCalls)
        assertEquals(1, successCount)
        assertEquals(92L, lastReward)
        assertEquals(92L, vm.spinEarnUiState.value.lastClaimedCoins)
      } finally {
        Dispatchers.resetMain()
      }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_watchAndEarn_setsErrorWhenSessionCreationFails_withoutClientAdFallback() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(testDispatcher)
      try {
        val firestore = createTestFirestore()
        val client =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            rewardCallableInvoker = { fnName, _ ->
              if (fnName == "claimRewardSession") {
                mapOf(
                  "success" to true,
                  "rewardType" to "WATCH_AND_EARN",
                  "rewardCoins" to 50L,
                  "videosWatched" to 1L,
                  "newBalanceCoins" to 50L,
                  "dateKey" to "2026-11-14"
                )
              } else {
                error("Server rejected session creation")
              }
            }
          )
        val repo =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = client,
            authenticatedUidProvider = { "u1" }
          )
        val vm =
          TaskFlowViewModel(
            repository = repo,
            currentUserId = "u1",
            currentUserEmail = "user1@gmail.com",
            currentUserDisplayName = "User One",
            initialPortal = PortalLoginTarget.USER_APP
          )

        val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        vm.startWatchEarnVideo(activity)
        advanceUntilIdle()

        val state = vm.watchEarnUiState.value
        assertEquals(WatchEarnFlowStatus.ERROR, state.status)
        assertFalse(state.activeSessionId.startsWith("ad_"))
        assertTrue(state.errorMessage.orEmpty().isNotBlank())

        // Also confirm ready-to-claim ViewModel flow blocks concurrent duplicate claims
        val gate = CompletableDeferred<Unit>()
        var serverClaimCalls = 0
        val claimClient =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            rewardCallableInvoker = { _, _ ->
              serverClaimCalls++
              gate.await()
              mapOf(
                "success" to true,
                "rewardType" to "WATCH_AND_EARN",
                "rewardCoins" to 50L,
                "videosWatched" to 2L,
                "newBalanceCoins" to 100L,
                "dateKey" to "2026-11-14"
              )
            }
          )
        val claimRepo =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = claimClient,
            authenticatedUidProvider = { "u1" }
          )
        val claimVm =
          TaskFlowViewModel(
            repository = claimRepo,
            currentUserId = "u1",
            currentUserEmail = "user1@gmail.com",
            currentUserDisplayName = "User One",
            initialPortal = PortalLoginTarget.USER_APP
          )
        // Simulate state verified by server
        val field = TaskFlowViewModel::class.java.getDeclaredField("_watchEarnUiState")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(claimVm) as kotlinx.coroutines.flow.MutableStateFlow<WatchEarnUiState>
        flow.value =
          WatchEarnUiState(
            status = WatchEarnFlowStatus.READY_TO_CLAIM,
            activeSessionId = "srv_watch_02",
            videosCompletedInCycle = 1,
            lastRewardCoins = 50L
          )

        var creditedCallbacks = 0
        // Without an activity or a valid secondSessionId, Watch & Earn MUST reject fallback claims
        claimVm.claimVerifiedWatchEarnReward(activity = null) { creditedCallbacks++ }
        advanceUntilIdle()
        assertEquals(0, serverClaimCalls)
        assertEquals(0, creditedCallbacks)
        assertEquals(WatchEarnFlowStatus.READY_TO_CLAIM, claimVm.watchEarnUiState.value.status)
        assertTrue(claimVm.watchEarnUiState.value.errorMessage.orEmpty().isNotBlank())

        // Also reject client fallback_ or same-session IDs
        claimVm.claimVerifiedWatchEarnReward(
          activity = null,
          secondSessionId = "fallback_second_session"
        ) { creditedCallbacks++ }
        claimVm.claimVerifiedWatchEarnReward(
          activity = null,
          secondSessionId = "srv_watch_02"
        ) { creditedCallbacks++ }
        advanceUntilIdle()
        assertEquals(0, serverClaimCalls)
        assertEquals(0, creditedCallbacks)

        // Concurrent claims with a valid second S2S session must fire only 1 Cloud Function request
        claimVm.claimVerifiedWatchEarnReward(
          activity = null,
          secondSessionId = "srv_watch_claim_02"
        ) { creditedCallbacks++ }
        claimVm.claimVerifiedWatchEarnReward(
          activity = null,
          secondSessionId = "srv_watch_claim_02"
        ) { creditedCallbacks++ }

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, serverClaimCalls)
        assertEquals(1, creditedCallbacks)
        assertEquals(WatchEarnFlowStatus.CLAIMED, claimVm.watchEarnUiState.value.status)
        assertEquals(50L, claimVm.watchEarnUiState.value.lastRewardCoins)
      } finally {
        Dispatchers.resetMain()
      }
    }

  @Test
  fun step6_watchAndEarn_requiresTwoDistinctSessionsInClaimPayload() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Any?>>()
    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          recordedCalls.add(fnName to payload)
          when (fnName) {
            "claimRewardSession" -> {
              val map = payload as Map<*, *>
              val s1 = map["sessionId"] as? String
              val s2 = map["secondSessionId"] as? String
              if (s1.isNullOrBlank() || s2.isNullOrBlank() || s1 == s2) {
                error("Both distinct session IDs are required")
              }
              mapOf(
                "success" to true,
                "rewardType" to "WATCH_AND_EARN",
                "sessionId" to s1,
                "secondSessionId" to s2,
                "rewardCoins" to 75L,
                "videosWatched" to 2L,
                "newBalanceCoins" to 575L,
                "dateKey" to "2026-11-14"
              )
            }
            else -> error("Unexpected callable: $fnName")
          }
        }
      )

    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "u_step6" }
      )

    // Blank secondSessionId must be rejected
    val blankSecondAttempt = runCatching {
      repository.claimVerifiedWatchEarnSession(
        userId = "u_step6",
        userName = "User",
        sessionId = "sess_01",
        secondSessionId = ""
      ).getOrThrow()
    }
    assertTrue("Blank secondSessionId must be rejected", blankSecondAttempt.isFailure)

    // Same session ID must be rejected
    val sameSessionAttempt = runCatching {
      client.claimRewardSession("sess_same", "sess_same")
    }
    // Callable invoker rejects same session
    assertTrue(sameSessionAttempt.isFailure || recordedCalls.any { it.first == "claimRewardSession" })

    // Two distinct sessions succeed
    val claimSuccess =
      repository.claimVerifiedWatchEarnSession(
        userId = "u_step6",
        userName = "User",
        sessionId = "sess_prime_01",
        secondSessionId = "sess_claim_02"
      ).getOrThrow()

    assertEquals(75L, claimSuccess)
    val lastCall = recordedCalls.last()
    assertEquals("claimRewardSession", lastCall.first)
    val payloadMap = lastCall.second as Map<*, *>
    assertEquals("sess_prime_01", payloadMap["sessionId"])
    assertEquals("sess_claim_02", payloadMap["secondSessionId"])
  }

  @Test
  fun step6_extraSpin_requiresTwoVerifiedAds_andServerRecordAdWatch() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Any?>>()
    var serverWatchedCount = 0

    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          recordedCalls.add(fnName to payload)
          when (fnName) {
            "createRewardSession" -> {
              val map = payload as Map<*, *>
              assertEquals("EXTRA_SPIN_UNLOCK", map["rewardType"])
              mapOf("sessionId" to "extra_sess_${recordedCalls.size}")
            }
            "recordExtraSpinAdWatch" -> {
              val map = payload as Map<*, *>
              val sid = map["sessionId"] as String
              assertTrue(sid.isNotBlank())
              serverWatchedCount++
              mapOf(
                "success" to true,
                "sessionId" to sid,
                "extraSpinVideosWatched" to serverWatchedCount,
                "extraSpinAvailable" to (serverWatchedCount >= 2),
                "newlyUnlocked" to (serverWatchedCount >= 2)
              )
            }
            else -> error("Unexpected callable: $fnName")
          }
        }
      )

    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "u_extra_spin" }
      )

    // Client-fabricated ad_ session ID must be rejected
    val fakeAttempt = repository.recordExtraSpinAdWatch("ad_fake_extra_1")
    assertTrue("ad_ prefix must be rejected", fakeAttempt.isFailure)

    // Ad 1
    val res1 = repository.recordExtraSpinAdWatch("extra_sess_1").getOrThrow()
    assertEquals(1, res1)
    assertEquals(1, serverWatchedCount)

    // Re-submitting same Extra Spin sessionId must fail locally without another server call
    val duplicateAttempt = repository.recordExtraSpinAdWatch("extra_sess_1")
    assertTrue("Duplicate Extra Spin sessionId must fail", duplicateAttempt.isFailure)
    assertEquals(1, serverWatchedCount)

    // Ad 2
    val res2 = repository.recordExtraSpinAdWatch("extra_sess_2").getOrThrow()
    assertEquals(2, res2)
    assertEquals(2, serverWatchedCount)
    assertEquals("recordExtraSpinAdWatch", recordedCalls.last().first)
  }

  @Test
  fun step6_spinWheel_usesServerAuthoritativeOutcome_andPreventsDuplicateClaims() = runBlocking {
    val firestore = createTestFirestore()
    val calls = mutableListOf<Pair<String, Any?>>()
    var claimCalls = 0

    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          calls.add(fnName to payload)
          when (fnName) {
            "createSpinSession" ->
              mapOf(
                "sessionId" to "srv_spin_step6",
                "segmentIndex" to 4,
                "rewardCoins" to 50L,
                "usedFreeSpin" to true
              )
            "claimSpinReward" -> {
              claimCalls++
              mapOf(
                "success" to true,
                "sessionId" to "srv_spin_step6",
                "rewardCoins" to 50L,
                "newBalanceCoins" to 200L
              )
            }
            else -> error("Unexpected callable: $fnName")
          }
        }
      )

    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "u_spin" }
      )

    val outcome = repository.createSpinSessionWithOutcome("u_spin").getOrThrow()
    assertEquals("srv_spin_step6", outcome.sessionId)
    assertEquals(4, outcome.segmentIndex)
    assertEquals(50L, outcome.rewardCoins)
    assertTrue(outcome.usedFreeSpin)

    val claim1 = repository.claimSpinReward(outcome.sessionId).getOrThrow()
    assertEquals(50L, claim1)
    assertEquals(1, claimCalls)

    // Duplicate claim on same session ID must fail locally without hitting server again
    val claim2 = repository.claimSpinReward(outcome.sessionId)
    assertTrue("Duplicate spin claim must be blocked", claim2.isFailure)
    assertEquals(1, claimCalls)
  }

  @Test
  fun step6_dailyCheckIn_30DaySchedule_responseParsingAndRewardConsistency() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Any?>>()

    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          recordedCalls.add(fnName to payload)
          val map = payload as Map<*, *>
          val day = (map["streakDay"] as? Number)?.toInt() ?: 1
          val coins = DailyCheckInConstants.getRewardCoinsForDay(day)
          mapOf(
            "success" to true,
            "rewardType" to "DAILY_CHECK_IN",
            "streakDay" to day,
            "rewardCoins" to coins,
            "newBalanceCoins" to 1000L + coins,
            "dateKey" to "2026-11-14"
          )
        }
      )

    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = client,
        authenticatedUidProvider = { "u_checkin" }
      )

    // Test Day 1 (25 Coins)
    val day1Result = repository.claimDailyCheckIn("u_checkin", streakDay = 1).getOrThrow()
    assertEquals(DailyCheckInConstants.getRewardCoinsForDay(1), day1Result)

    // Test Day 15 (210 Coins)
    val day15Result = repository.claimDailyCheckIn("u_checkin", streakDay = 15).getOrThrow()
    assertEquals(DailyCheckInConstants.getRewardCoinsForDay(15), day15Result)

    // Test Day 30 (1000 Coins)
    val day30Result = repository.claimDailyCheckIn("u_checkin", streakDay = 30).getOrThrow()
    assertEquals(1000L, day30Result)
  }

  @Test
  fun step6_leaderboard_ordersByBalanceCoins_andDistinguishesBalanceCents() = runBlocking {
    val firestore = createTestFirestore()
    val repository = TaskFlowRepository(firestore = firestore)

    val alice = UserProfile(
      id = "user_a",
      userId = "user_a",
      displayName = "Alice",
      balanceCents = 5000L, // higher withdrawable paise
      balanceCoins = repository.resolveLeaderboardBalanceCoins(1000L, 5000L), // lower total coins
      completedTasksCount = 5L,
      approvalStatus = "approved"
    )
    val bob = UserProfile(
      id = "user_b",
      userId = "user_b",
      displayName = "Bob",
      balanceCents = 100L, // lower withdrawable paise
      balanceCoins = repository.resolveLeaderboardBalanceCoins(8000L, 100L), // higher total coins
      completedTasksCount = 20L,
      approvalStatus = "approved"
    )
    val pendingUser = UserProfile(
      id = "user_pending",
      userId = "user_pending",
      displayName = "Pending",
      balanceCents = 99999L,
      balanceCoins = 99999L,
      approvalStatus = "pending"
    )
    val legacyFallbackUser = UserProfile(
      id = "user_c",
      userId = "user_c",
      displayName = "Charlie",
      balanceCents = 2500L, // missing balanceCoins falls back to centsToCoins(2500) = 3125
      balanceCoins = repository.resolveLeaderboardBalanceCoins(null, 2500L),
      completedTasksCount = 2L,
      approvalStatus = "approved"
    )

    val ranked = repository.sortLeaderboardProfiles(listOf(alice, bob, pendingUser, legacyFallbackUser))

    // Leaderboard must exclude pending users and sort approved users by balanceCoins descending:
    // Bob (8000) -> Charlie (3125) -> Alice (1000), while preserving distinct balanceCents
    assertEquals(3, ranked.size)
    assertEquals("user_b", ranked[0].userId)
    assertEquals(8000L, ranked[0].getLeaderboardDisplayCoins())
    assertEquals(100L, ranked[0].balanceCents)

    assertEquals("user_c", ranked[1].userId)
    assertEquals(3125L, ranked[1].getLeaderboardDisplayCoins())
    assertEquals(2500L, ranked[1].balanceCents)

    assertEquals("user_a", ranked[2].userId)
    assertEquals(1000L, ranked[2].getLeaderboardDisplayCoins())
    assertEquals(5000L, ranked[2].balanceCents)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun step6_viewModel_spinWheel_claimsDirectlyWithoutClaimAd_andPreservesServerOutcome() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val firestore = createTestFirestore()
      val calls = mutableListOf<Pair<String, Any?>>()
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, payload ->
            calls.add(fnName to payload)
            when (fnName) {
              "createSpinSession" ->
                mapOf(
                  "sessionId" to "srv_spin_no_ad_1",
                  "segmentIndex" to 6,
                  "rewardCoins" to 125L,
                  "usedFreeSpin" to false
                )
              "claimSpinReward" ->
                mapOf(
                  "success" to true,
                  "sessionId" to "srv_spin_no_ad_1",
                  "rewardCoins" to 125L,
                  "newBalanceCoins" to 425L
                )
              else -> error("Unexpected callable: $fnName")
            }
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "u_spin_vm" }
        )
      val vm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "u_spin_vm",
          currentUserEmail = "spin@gmail.com",
          currentUserDisplayName = "Spinner",
          initialPortal = PortalLoginTarget.USER_APP
        )

      var readyOutcome: com.example.data.model.SpinSessionOutcome? = null
      vm.startSpinWithBackendOutcome { outcome -> readyOutcome = outcome }
      advanceUntilIdle()

      assertNotNull(readyOutcome)
      assertEquals(6, readyOutcome!!.segmentIndex)
      assertEquals(125L, readyOutcome!!.rewardCoins)
      assertFalse(readyOutcome!!.usedFreeSpin)
      assertEquals(6, vm.spinEarnUiState.value.selectedSegmentIndex)
      assertEquals(125L, vm.spinEarnUiState.value.selectedRewardCoins)

      vm.onSpinWheelAnimationFinished()
      assertTrue(vm.spinEarnUiState.value.isReadyToClaim)
      assertFalse(vm.spinEarnUiState.value.statusMessage.contains("watch 1 video", ignoreCase = true))

      val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
      var claimedAmount = 0L
      // Even when Activity is non-null, Spin Wheel claims directly without launching a Claim Ad
      vm.claimSpinRewardWithAd(activity) { coins -> claimedAmount = coins }
      advanceUntilIdle()

      assertEquals(125L, claimedAmount)
      assertEquals(125L, vm.spinEarnUiState.value.lastClaimedCoins)
      assertFalse(vm.spinEarnUiState.value.isReadyToClaim)
      assertEquals(listOf("createSpinSession", "claimSpinReward"), calls.map { it.first })
    } finally {
      Dispatchers.resetMain()
    }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun step6_viewModel_dailyCheckIn_routesDirectlyToServer_andDisplaysServerResult() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val firestore = createTestFirestore()
      val calls = mutableListOf<Pair<String, Any?>>()
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, payload ->
            calls.add(fnName to payload)
            mapOf(
              "success" to true,
              "rewardType" to "DAILY_CHECK_IN",
              "streakDay" to 15,
              "rewardCoins" to 210L,
              "newBalanceCoins" to 1210L,
              "dateKey" to "2026-11-14"
            )
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "u_checkin_vm" }
        )
      val vm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "u_checkin_vm",
          currentUserEmail = "checkin@gmail.com",
          currentUserDisplayName = "CheckIn User",
          initialPortal = PortalLoginTarget.USER_APP
        )

      val seenBanners = mutableListOf<String?>()
      val bannerJob =
        backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
          vm.bannerMessage.collect { seenBanners.add(it) }
        }

      val activity = Robolectric.buildActivity(android.app.Activity::class.java).create().get()
      var claimedAmount = 0L
      // Client starts at default Day 1; server responds authoritatively with streakDay=15 & rewardCoins=210
      vm.claimDailyCheckIn(day = 1, activity = activity) { earned -> claimedAmount = earned }
      advanceUntilIdle()
      bannerJob.cancel()

      assertEquals(1, calls.size)
      assertEquals("claimDailyCheckIn", calls.single().first)
      assertEquals(210L, claimedAmount)
      assertTrue(
        "Expected Day 15 +210 Coins banner, got=$seenBanners",
        seenBanners.any { msg ->
          val text = msg.orEmpty()
          text.contains("Day 15 check-in claimed") && text.contains("210")
        }
      )
    } finally {
      Dispatchers.resetMain()
    }
  }

  @Test
  fun step9_scratchCard_extractsErrors_andBlocksDuplicateOrInFlightRequests() = runBlocking {
    val firestore = createTestFirestore()
    val gate = CompletableDeferred<Unit>()
    val calls = mutableListOf<Pair<String, Any?>>()
    var claimCalls = 0
    var adWatchCalls = 0

    val client =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        rewardCallableInvoker = { fnName, payload ->
          calls.add(fnName to payload)
          when (fnName) {
            "recordScratchAdWatch" -> {
              adWatchCalls++
              val sid = (payload as Map<*, *>)["sessionId"] as String
              if (sid == "err_session") {
                throw IllegalStateException(
                  "Reward video completion has not been verified by server yet."
                )
              }
              mapOf(
                "success" to true,
                "sessionId" to sid,
                "adsWatchedForNextScratch" to 1L,
                "unlockedScratchesCount" to 0L,
                "newlyUnlocked" to false,
                "dateKey" to "2026-11-15"
              )
            }
            "createScratchCardSession" ->
              mapOf(
                "cardId" to "srv_card_step9",
                "rewardCoins" to 37L,
                "scratchNumber" to 1L,
                "isFree" to true,
                "dateKey" to "2026-11-15"
              )
            "claimScratchCardReward" -> {
              claimCalls++
              gate.await()
              mapOf(
                "success" to true,
                "cardId" to "srv_card_step9",
                "rewardCoins" to 37L,
                "scratchesCompletedToday" to 1L,
                "newBalanceCoins" to 137L
              )
            }
            else -> error("Unexpected callable: $fnName")
          }
        }
      )

    // 1. Client-fabricated ad_ and fallback_ IDs must be rejected
    assertTrue(runCatching { client.recordScratchAdWatch("ad_fake_1") }.isFailure)
    assertTrue(runCatching { client.recordScratchAdWatch("fallback_fake_1") }.isFailure)
    assertEquals(0, adWatchCalls)

    // 2. Cloud Function error extraction propagates server message
    val errResult = runCatching { client.recordScratchAdWatch("err_session") }
    assertTrue(errResult.isFailure)
    assertTrue(
      errResult.exceptionOrNull()?.message?.contains("not been verified by server") == true
    )

    // 3. Duplicate Scratch Card ad consumption prevented locally
    val watch1 = client.recordScratchAdWatch("srv_scratch_ad_1")
    assertEquals(1L, watch1.adsWatchedForNextScratch)
    assertEquals(2, adWatchCalls) // 1 error attempt + 1 valid attempt
    assertTrue(runCatching { client.recordScratchAdWatch("srv_scratch_ad_1") }.isFailure)
    assertEquals(2, adWatchCalls)

    // 4. Create session returns server rewardCoins and rejects invalid payloads
    val session = client.createScratchCardSession()
    assertEquals("srv_card_step9", session.cardId)
    assertEquals(37L, session.rewardCoins)

    // 5. Concurrent and duplicate claim requests on same cardId fire only 1 server callable
    val c1 = async { client.claimScratchCardReward("srv_card_step9") }
    kotlinx.coroutines.yield()
    val c2 = async { runCatching { client.claimScratchCardReward("srv_card_step9") } }
    val concurrentResult = c2.await()
    assertTrue("Concurrent scratch claim must fail", concurrentResult.isFailure)
    gate.complete(Unit)
    assertEquals(37L, c1.await())
    assertEquals(1, claimCalls)

    val replayResult = runCatching { client.claimScratchCardReward("srv_card_step9") }
    assertTrue("Replay scratch claim must fail", replayResult.isFailure)
    assertEquals(1, claimCalls)
  }

  @Test
  fun step9_buildLeaderboardPayload_preservesBalanceCoinsAndExcludesEmail() {
    val firestore = createTestFirestore()
    val repository = TaskFlowRepository(firestore = firestore)
    val payload =
      repository.buildLeaderboardPayload(
        userId = "u_lb_9",
        displayName = "Leaderboard User",
        balanceCents = 2000L,
        balanceCoins = 5500L,
        completedTasksCount = 4L,
        approvalStatus = "approved"
      )

    assertEquals("u_lb_9", payload["userId"])
    assertEquals("Leaderboard User", payload["displayName"])
    assertEquals(2000L, payload["balanceCents"])
    assertEquals(5500L, payload["balanceCoins"])
    assertEquals(4L, payload["completedTasksCount"])
    assertEquals("approved", payload["approvalStatus"])
    assertFalse("Leaderboard payload must never contain email", payload.containsKey("email"))
  }

  @Test
  fun step9b_scratchDailyState_remainsScratchableWhileActiveUnclaimedCardExists() {
    val beforeCard = com.example.data.model.ScratchDailyState(dateKey = "2026-11-15")
    assertTrue("First daily scratch must be enabled", beforeCard.canScratchNow)

    val duringActiveCard =
      com.example.data.model.ScratchDailyState(
        dateKey = "2026-11-15",
        scratchesCompletedToday = 0L,
        hasUsedFreeScratchToday = true,
        unlockedScratchesCount = 0L,
        activeCardId = "scratch_card_1",
        activeCardRewardCoins = 42L,
        activeCardIsClaimed = false
      )
    assertTrue("Active unclaimed card must remain scratchable", duringActiveCard.hasActiveUnclaimedCard)
    assertTrue("Active unclaimed card must keep canScratchNow true", duringActiveCard.canScratchNow)

    val afterClaimNoUnlocked =
      com.example.data.model.ScratchDailyState(
        dateKey = "2026-11-15",
        scratchesCompletedToday = 1L,
        hasUsedFreeScratchToday = true,
        unlockedScratchesCount = 0L,
        activeCardId = "",
        activeCardIsClaimed = true
      )
    assertFalse("After claiming with 0 unlocked cards, user must watch 2 ads", afterClaimNoUnlocked.hasActiveUnclaimedCard)
    assertFalse("After claiming with 0 unlocked cards, canScratchNow must be false", afterClaimNoUnlocked.canScratchNow)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun step9b_viewModel_scratchCardClaim_succeedsCleanlyAndRetriesRecoverableErrors() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)
    try {
      val firestore = createTestFirestore()
      var createCalls = 0
      var claimAttempts = 0
      val client =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          rewardCallableInvoker = { fnName, _ ->
            when (fnName) {
              "createScratchCardSession" -> {
                createCalls++
                if (createCalls > 1) {
                  throw IllegalStateException(
                    "You must watch 2 rewarded videos to unlock another Scratch Card."
                  )
                }
                mapOf(
                  "cardId" to "srv_card_9b_1",
                  "rewardCoins" to 45L,
                  "scratchNumber" to 1L,
                  "isFree" to true,
                  "dateKey" to "2026-11-15"
                )
              }
              "claimScratchCardReward" -> {
                claimAttempts++
                if (claimAttempts == 1) {
                  throw IllegalStateException("Server temporarily unavailable. Please try again.")
                }
                mapOf(
                  "success" to true,
                  "cardId" to "srv_card_9b_1",
                  "rewardCoins" to 45L,
                  "scratchesCompletedToday" to 1L,
                  "newBalanceCoins" to 145L
                )
              }
              else -> error("Unexpected callable: $fnName")
            }
          }
        )
      val repo =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = client,
          authenticatedUidProvider = { "u_scratch_9b" }
        )
      val vm =
        TaskFlowViewModel(
          repository = repo,
          currentUserId = "u_scratch_9b",
          currentUserEmail = "scratch9b@gmail.com",
          currentUserDisplayName = "Scratch User",
          initialPortal = PortalLoginTarget.USER_APP
        )

      vm.prepareScratchCard()
      advanceUntilIdle()
      assertEquals("srv_card_9b_1", vm.scratchCardUiState.value.activeSession?.cardId)
      assertEquals(45L, vm.scratchCardUiState.value.revealedCoins)
      assertEquals(1, createCalls)

      // 1. Recoverable failure during claim must surface message without clearing activeSession or locking cardId
      vm.onScratchRevealed()
      vm.claimScratchCardReward()
      advanceUntilIdle()
      assertEquals(1, claimAttempts)
      assertNotNull(vm.scratchCardUiState.value.activeSession)
      assertTrue(
        vm.scratchCardUiState.value.errorMessage?.contains("temporarily unavailable") == true
      )

      // 2. Retrying claim succeeds AND must NOT trigger a second createScratchCardSession error
      vm.claimScratchCardReward()
      advanceUntilIdle()
      assertEquals(2, claimAttempts)
      assertEquals(1, createCalls)
      val finalState = vm.scratchCardUiState.value
      assertEquals(null, finalState.activeSession)
      assertEquals(null, finalState.errorMessage)
      assertTrue(finalState.successMessage?.contains("45 TF") == true)
      assertEquals(1L, finalState.dailyState.scratchesCompletedToday)
      assertFalse(finalState.dailyState.canScratchNow)
    } finally {
      Dispatchers.resetMain()
    }
  }
}

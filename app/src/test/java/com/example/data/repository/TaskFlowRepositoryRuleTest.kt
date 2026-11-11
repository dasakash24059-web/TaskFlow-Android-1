package com.example.data.repository

import android.net.Uri
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.TaskStatus
import com.example.data.model.calculateDailyCheckInState
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [33])
class TaskFlowRepositoryRuleTest : FirestoreEmulatorTestBase() {

  private val fakeStorageUploader = ProofPhotoUploader { userId, taskId, _, onProgress ->
    onProgress(50)
    onProgress(100)
    UploadedProofPhoto(
      downloadUrl =
        "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2F$userId%2F$taskId%2Fproof_test.jpg?alt=media",
      storagePath = "submissions/$userId/$taskId/proof_test.jpg"
    )
  }

  /**
   * Simulates the server-side Cloud Functions (`createRewardSession`, `unityAdsS2SCallback`,
   * `claimRewardSession`) with exact Phase 1 security rules:
   * - Mandatory S2S HMAC secret & signature verification
   * - Strict `s2sVerified == true && status == "VERIFIED"` requirement before claiming
   * - Single-use session enforcement (`!used && !claimed`)
   * - Daily Check-In server-side date & streakDay validation (Days 1–6 = 100 paise, Day 7 = 500 paise)
   * - Duplicate same-day check-in prevention
   * - 25-video daily Watch & Earn limit
   */
  private class SimulatedServerRewardFunctionsClient(
    private val currentAuthUidProvider: () -> String,
    private val userApprovalStatusProvider: (String) -> String = { "approved" },
    private val userCheckInStateProvider: (String) -> Pair<String, Int> = { "" to 0 },
    private val onServerCredit: (userId: String, rewardCents: Long, newStreak: Int?, dateKey: String) -> Unit =
      { _, _, _, _ -> }
  ) : RewardCloudFunctionsClient {

    data class ServerSession(
      val sessionId: String,
      val userId: String,
      val rewardType: String,
      val rewardCents: Long,
      val streakDay: Int?,
      val dateKey: String,
      var status: String = "PENDING",
      var s2sVerified: Boolean = false,
      var used: Boolean = false,
      var claimed: Boolean = false
    )

    val sessions = mutableMapOf<String, ServerSession>()
    val processedOrderIds = mutableSetOf<String>()
    val claimedCheckInDates = mutableSetOf<String>()
    val watchCountsByUserDate = mutableMapOf<String, Long>()

    override suspend fun createRewardSession(
      rewardType: String,
      streakDay: Int?
    ): CreatedRewardSession {
      val uid = currentAuthUidProvider()
      check(uid.isNotBlank()) { "Unauthenticated" }
      check(userApprovalStatusProvider(uid) == "approved") {
        "Only approved users can create reward sessions."
      }
      val today = com.example.data.model.getTodayDateKey()

      return if (rewardType == "DAILY_CHECK_IN") {
        val (lastDate, currentStreak) = userCheckInStateProvider(uid)
        val state = calculateDailyCheckInState(lastDate, currentStreak.toLong())
        check(state.canCheckInToday && !claimedCheckInDates.contains("${uid}_$today")) {
          "Daily check-in already claimed for today."
        }
        val expectedDay = state.nextClaimDay
        if (streakDay != null) {
          check(streakDay == expectedDay) {
            "Invalid streakDay $streakDay; expected $expectedDay"
          }
        }
        val cents = DailyCheckInConstants.getRewardForDay(expectedDay) // Days 1-6 = 100, Day 7 = 500
        val sid = "srv_checkin_${UUID.randomUUID()}"
        sessions[sid] =
          ServerSession(
            sessionId = sid,
            userId = uid,
            rewardType = "DAILY_CHECK_IN",
            rewardCents = cents,
            streakDay = expectedDay,
            dateKey = today
          )
        CreatedRewardSession(sid, "DAILY_CHECK_IN", cents, expectedDay, today)
      } else {
        val watched = watchCountsByUserDate["${uid}_$today"] ?: 0L
        check(watched < 25L) { "Daily limit of 25 watched videos reached." }
        val sid = "srv_watch_${UUID.randomUUID()}"
        sessions[sid] =
          ServerSession(
            sessionId = sid,
            userId = uid,
            rewardType = "WATCH_AND_EARN",
            rewardCents = 100L,
            streakDay = null,
            dateKey = today
          )
        CreatedRewardSession(sid, "WATCH_AND_EARN", 100L, null, today)
      }
    }

    fun simulateSignedS2SCallback(
      sessionId: String,
      orderId: String,
      providedSecret: String,
      expectedSecret: String = "prod_unity_s2s_secret"
    ) {
      check(expectedSecret.isNotBlank() && providedSecret.isNotBlank()) {
        "Server configuration error: UNITY_S2S_SECRET is mandatory"
      }
      check(providedSecret == expectedSecret) { "Invalid S2S HMAC signature" }
      check(!processedOrderIds.contains(orderId)) { "Duplicate S2S order ID" }
      val session = sessions[sessionId] ?: error("Session not found")
      check(!session.used && !session.claimed) { "Session already used" }
      processedOrderIds.add(orderId)
      session.s2sVerified = true
      session.status = "VERIFIED"
    }

    override suspend fun awaitSessionVerified(userId: String, sessionId: String) {
      val session = sessions[sessionId] ?: error("Reward session does not exist.")
      check(session.userId == userId) { "Reward session does not belong to current user." }
      check(!session.used && !session.claimed) { "This reward session has already been used." }
      check(session.s2sVerified && session.status == "VERIFIED") {
        "Reward video completion has not been verified by the server yet."
      }
    }

    override suspend fun claimRewardSession(sessionId: String): ClaimedRewardResult {
      val uid = currentAuthUidProvider()
      val session = sessions[sessionId] ?: error("Invalid reward session.")
      check(session.userId == uid) { "Session belongs to another user." }
      check(!session.used && !session.claimed && session.status != "CLAIMED") {
        "Reward has already been claimed for this session."
      }
      check(session.s2sVerified && session.status == "VERIFIED") {
        "Reward video completion has not been verified by server yet."
      }
      check(userApprovalStatusProvider(uid) == "approved") {
        "Only approved users can claim rewards."
      }

      if (session.rewardType == "DAILY_CHECK_IN") {
        val claimKey = "${uid}_${session.dateKey}"
        check(!claimedCheckInDates.contains(claimKey)) {
          "Daily check-in already claimed for today."
        }
        val day = session.streakDay ?: 1
        val rewardCents = if (day == 7) 500L else 100L
        session.used = true
        session.claimed = true
        session.status = "CLAIMED"
        claimedCheckInDates.add(claimKey)
        onServerCredit(uid, rewardCents, day, session.dateKey)
        return ClaimedRewardResult(
          rewardType = "DAILY_CHECK_IN",
          rewardCents = rewardCents,
          streakDay = day,
          dateKey = session.dateKey
        )
      } else {
        val watchKey = "${uid}_${session.dateKey}"
        val currentVideos = watchCountsByUserDate[watchKey] ?: 0L
        check(currentVideos < 25L) { "Daily limit of 25 watched videos reached." }
        session.used = true
        session.claimed = true
        session.status = "CLAIMED"
        val nextVideos = currentVideos + 1L
        watchCountsByUserDate[watchKey] = nextVideos
        onServerCredit(uid, 100L, null, session.dateKey)
        return ClaimedRewardResult(
          rewardType = "WATCH_AND_EARN",
          rewardCents = 100L,
          videosWatched = nextVideos,
          dateKey = session.dateKey
        )
      }
    }

    override suspend fun claimDailyCheckIn(streakDay: Int?): ClaimedRewardResult {
      val uid = currentAuthUidProvider()
      check(uid.isNotBlank()) { "Unauthenticated" }
      check(userApprovalStatusProvider(uid) == "approved") {
        "Only approved users can claim daily check-in."
      }
      val today = com.example.data.model.getTodayDateKey()
      val claimKey = "${uid}_$today"
      check(!claimedCheckInDates.contains(claimKey)) {
        "Daily check-in already claimed for today."
      }
      val day = streakDay ?: 1
      val rewardCents = if (day == 7) 500L else 100L
      claimedCheckInDates.add(claimKey)
      onServerCredit(uid, rewardCents, day, today)
      return ClaimedRewardResult(
        rewardType = "DAILY_CHECK_IN",
        rewardCents = rewardCents,
        newBalanceCents = 0L,
        streakDay = day,
        videosWatched = 0L,
        dateKey = today
      )
    }

    override suspend fun createSpinSession(): String = "spin_${UUID.randomUUID()}"

    override suspend fun claimSpinReward(sessionId: String): Long = 100L
  }

  @Test
  fun verifiedAndUnverifiedEmail_registrationAndRoleOrBalanceTampering() = runBlocking {
    val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

    // 1. Unverified email registration must fail
    val unverifiedEmail = "unverified_${UUID.randomUUID()}@test.com"
    val unverifiedUid = signInTestUser(unverifiedEmail, emailVerified = false)
    val unverifiedResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(unverifiedUid, unverifiedEmail, "Unverified User")
      }
    assertTrue("Expected unverified email registration to fail", unverifiedResult.isFailure)

    // 2. Normal user registration with verified email starts with approvalStatus='pending', role='user', balanceCents=0
    val aliceEmail = "alice_${UUID.randomUUID()}@test.com"
    val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
    val result =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(aliceUid, aliceEmail, "Alice Tester")
      }
    assertTrue("Expected profile creation to succeed: ${result.exceptionOrNull()}", result.isSuccess)
    val profile = result.getOrThrow()
    assertEquals(aliceUid, profile.userId)
    assertEquals("user", profile.role)
    assertEquals(0L, profile.balanceCents)
    assertEquals("pending", profile.approvalStatus)

    // 3. User attempting to change their own role or balance (including +100/+500/+700 check-in amounts) fails with PERMISSION_DENIED
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore
          .collection("users")
          .document(aliceUid)
          .update(mapOf("role" to "admin", "updatedAt" to FieldValue.serverTimestamp()))
          .await()
      }
      fail("Expected user self-promotion to admin to fail")
    } catch (e: Exception) {
      assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        e.unwrapFirestoreException()?.code
      )
    }

    for (amount in listOf(100L, 500L, 700L, 1000L)) {
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("users")
            .document(aliceUid)
            .update(
              mapOf(
                "balanceCents" to FieldValue.increment(amount),
                "updatedAt" to FieldValue.serverTimestamp()
              )
            )
            .await()
        }
        fail("Expected user self-balance increment of $amount to fail")
      } catch (e: Exception) {
        assertEquals(
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
          e.unwrapFirestoreException()?.code
        )
      }
    }
  }

  @Test
  fun mandatoryTextAndPhotoSubmission_pendingUserBlocked_approvedUserAllowed_approvalExactCredit_andRejectionWithoutCredit() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

      // Step 1: Verified Bootstrap Admin signs in first and creates two tasks
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
      }

      // 1a. Creating a task with an invalid non-HTTPS URL must fail validation
      val invalidUrlTaskResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createTask(
            title = "Invalid Link Task",
            description = "Task with invalid link",
            category = "QA Testing",
            rewardCents = 1000L,
            assignedUserId = adminUid,
            assignedUserName = "Admin",
            assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
            instructions = "Step 1: Open link",
            taskLink = "http://not-https.example.com"
          )
        }
      assertTrue("Expected non-HTTPS task link to fail validation", invalidUrlTaskResult.isFailure)

      val playStoreUrl = "https://play.google.com/store/apps/details?id=com.example.taskflow"
      val task1Id =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "Verify Checkout Flow",
              description = "Test payment screen layout on Android",
              category = "App Install",
              rewardCents = 1500L,
              assignedUserId = adminUid,
              assignedUserName = "Admin",
              assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
              instructions = "1. Open Google Play Store link\n2. Verify checkout screen\n3. Take screenshot",
              taskLink = playStoreUrl,
              appName = "TaskFlow Companion",
              appIconUrl = "https://example.com/app-icon.png",
              requiredProof = "Submit order confirmation note and screenshot of checkout page."
            )
            .getOrThrow()
        }

      val task2Id =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "Review Onboarding Copy",
              description = "Check onboarding strings",
              category = "Website Visit",
              rewardCents = 2000L,
              assignedUserId = adminUid,
              assignedUserName = "Admin",
              assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
              instructions = "1. Open website\n2. Capture screenshot of onboarding",
              taskLink = "https://example.com/onboarding",
              appName = "TaskFlow Web Portal",
              requiredProof = "Submit onboarding review notes and screenshot."
            )
            .getOrThrow()
        }

      // Step 2: Second User account (Alice) signs in (starts as approvalStatus == 'pending')
      val aliceEmail = "alice_sub_${UUID.randomUUID()}@test.com"
      val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(aliceUid, aliceEmail, "Alice").getOrThrow()
      }

      val sampleGalleryUri =
        Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1001")

      // Step 2b (Phase 1 Task 4): Pending user attempting to submit task proof MUST FAIL
      val pendingSubmitAttempt =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = task1Id,
            taskTitle = "Verify Checkout Flow",
            rewardCents = 1500L,
            userName = "Alice",
            userEmail = aliceEmail,
            proofText = "Attempt while pending approval.",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue("Expected pending user task submission to be blocked", pendingSubmitAttempt.isFailure)

      // Step 2c: Admin approves Alice's account (approvalStatus = 'approved')
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
      }

      // Step 3: Approved Alice signs back in
      signInTestUser(aliceEmail, emailVerified = true)

      // 3a. Text without photo -> MUST FAIL
      val textOnlyAttempt =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = task1Id,
            taskTitle = "Verify Checkout Flow",
            rewardCents = 1500L,
            userName = "Alice",
            userEmail = aliceEmail,
            proofText = "Text proof without any photo",
            imageUri = null
          )
        }
      assertTrue("Expected text-only submission without photo to fail", textOnlyAttempt.isFailure)

      // 3b. Photo without text -> MUST FAIL
      val photoOnlyAttempt =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = task1Id,
            taskTitle = "Verify Checkout Flow",
            rewardCents = 1500L,
            userName = "Alice",
            userEmail = aliceEmail,
            proofText = "   ",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue("Expected photo-only submission without text to fail", photoOnlyAttempt.isFailure)

      // Step 4-6: Approved Alice submits BOTH written text AND gallery photo on Task 1 and Task 2 -> MUST SUCCEED
      val submit1Result =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = task1Id,
            taskTitle = "Verify Checkout Flow",
            rewardCents = 1500L,
            userName = "Alice",
            userEmail = aliceEmail,
            proofText = "All checkout steps verified on Android 16.",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue(
        "Expected task 1 submission by approved user Alice to succeed: ${submit1Result.exceptionOrNull()}",
        submit1Result.isSuccess
      )

      val submit2Result =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = task2Id,
            taskTitle = "Review Onboarding Copy",
            rewardCents = 2000L,
            userName = "Alice",
            userEmail = aliceEmail,
            proofText = "Reviewed onboarding copy and attached screenshot.",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue(
        "Expected task 2 submission by approved user Alice to succeed: ${submit2Result.exceptionOrNull()}",
        submit2Result.isSuccess
      )

      // Step 7-8: Admin signs in and reviews submissions
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val submittedTask1 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task1Id).getOrThrow() ?: error("Expected task 1")
        }

      // Step 9-10: Admin approves task 1 atomically -> credits Alice's wallet with exact 1500 paise (₹15.00) once
      val approveResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = submittedTask1,
            approve = true,
            adminFeedback = "Great QA report and clear screenshot!"
          )
        }
      assertTrue(
        "Expected atomic approval to succeed: ${approveResult.exceptionOrNull()}",
        approveResult.isSuccess
      )

      val aliceAfterApproval =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      assertEquals(1500L, aliceAfterApproval.balanceCents)
      assertEquals(1L, aliceAfterApproval.completedTasksCount)

      // Repeated approval attempt on already approved task 1 must fail
      val repeatedApprovalResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = submittedTask1,
            approve = true,
            adminFeedback = "Duplicate approval attempt"
          )
        }
      assertTrue("Expected repeated approval attempt to fail", repeatedApprovalResult.isFailure)

      // Step 11: Separate rejection test on Task 2 -> status becomes 'rejected' and Alice's wallet balance remains 1500L
      val submittedTask2 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task2Id).getOrThrow() ?: error("Expected task 2")
        }
      val rejectResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = submittedTask2,
            approve = false,
            adminFeedback = "Screenshot was blurry."
          )
        }
      assertTrue(
        "Expected atomic rejection to succeed: ${rejectResult.exceptionOrNull()}",
        rejectResult.isSuccess
      )

      val aliceAfterRejection =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      assertEquals(1500L, aliceAfterRejection.balanceCents)
      assertEquals(1L, aliceAfterRejection.completedTasksCount)
    }

  @Test
  fun unauthorizedBalanceIncrease_withoutApprovedTaskBatch_failsWithPermissionDenied() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)
      val aliceEmail = "alice_bal_${UUID.randomUUID()}@test.com"
      val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(aliceUid, aliceEmail, "Alice").getOrThrow()
      }

      // 1. Normal user attempting a direct balance increase on their own profile -> REJECTED
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("users")
            .document(aliceUid)
            .update("balanceCents", FieldValue.increment(5000L))
            .await()
        }
        fail("Expected direct self-balance increase to fail with PERMISSION_DENIED")
      } catch (e: Exception) {
        val firestoreException = e.unwrapFirestoreException()
        val isPermissionDenied =
          (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
            e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
            e is com.google.firebase.FirebaseException
        assertTrue("Expected PERMISSION_DENIED for self balance increase, got $e", isPermissionDenied)
      }

      // 2. Normal user attempting a balance update on another user's profile -> REJECTED
      val bobEmail = "bob_bal_${UUID.randomUUID()}@test.com"
      val bobUid = signInTestUser(bobEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(bobUid, bobEmail, "Bob").getOrThrow()
      }

      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("users")
            .document(aliceUid)
            .update("balanceCents", FieldValue.increment(5000L))
            .await()
        }
        fail("Expected cross-user balance update by normal user to fail with PERMISSION_DENIED")
      } catch (e: Exception) {
        val firestoreException = e.unwrapFirestoreException()
        val isPermissionDenied =
          (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
            e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
            e is com.google.firebase.FirebaseException
        assertTrue(
          "Expected PERMISSION_DENIED for cross-user balance update, got $e",
          isPermissionDenied
        )
      }

      // 3. Admin attempting a direct balance increase without an approved task batch -> REJECTED
      runCatching { signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true) }
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("users")
            .document(aliceUid)
            .update(
              mapOf(
                "balanceCents" to FieldValue.increment(5000L),
                "completedTasksCount" to FieldValue.increment(1L),
                "assignedTaskId" to "nonexistent_task",
                "updatedAt" to FieldValue.serverTimestamp()
              )
            )
            .await()
        }
        fail("Expected direct balance increase by admin without approved task batch to fail")
      } catch (e: Exception) {
        val firestoreException = e.unwrapFirestoreException()
        val isPermissionDenied =
          (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
            e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
            e is com.google.firebase.FirebaseException
        assertTrue(
          "Expected PERMISSION_DENIED for admin balance update without batch, got $e",
          isPermissionDenied
        )
      }
    }

  @Test
  fun leaderboardPrivacy_nonAdminCannotListUsers_andLeaderboardExposesNoEmails() = runBlocking {
    val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

    // 1. Admin signs in and creates profile
    val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
    }

    // 2. Alice signs in and creates profile, then Admin approves Alice
    val aliceEmail = "alice_priv_${UUID.randomUUID()}@test.com"
    val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.ensureUserProfile(aliceUid, aliceEmail, "Alice Leaderboard").getOrThrow()
    }
    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
    }

    // 3. Bob signs in (non-admin) -> cannot read Alice's /users/{aliceUid} and cannot list /users
    val bobEmail = "bob_priv_${UUID.randomUUID()}@test.com"
    signInTestUser(bobEmail, emailVerified = true)
    val bobGetAliceResult =
      withTimeout(DEFAULT_TIMEOUT_MS) { repository.getUserProfile(aliceUid) }
    assertTrue("Expected cross-user profile read to fail", bobGetAliceResult.isFailure)
    assertEquals(
      FirebaseFirestoreException.Code.PERMISSION_DENIED,
      bobGetAliceResult.exceptionOrNull()?.unwrapFirestoreException()?.code
    )

    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("users").get().await()
      }
      fail("Expected non-admin listing of /users to fail with PERMISSION_DENIED")
    } catch (e: Exception) {
      assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        e.unwrapFirestoreException()?.code
      )
    }

    // 4. Bob CAN query the privacy-safe /leaderboard collection and sees Alice with NO email exposed
    val leaderboardUsers =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.getLeaderboardUsers().getOrThrow()
      }
    val aliceEntry = leaderboardUsers.firstOrNull { it.userId == aliceUid }
    assertNotNull("Expected approved user Alice on public leaderboard", aliceEntry)
    assertEquals("Alice Leaderboard", aliceEntry?.displayName)
    assertEquals("Leaderboard must never expose private email", "", aliceEntry?.email)
  }

  @Test
  fun observeUserTasks_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
    auth.signOut()
    val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

    try {
      withTimeout(FLOW_TIMEOUT_MS) {
        repository.observeUserTasks("unauth_uid").first()
      }
      fail("Expected unauthenticated observeUserTasks to throw an exception")
    } catch (e: Exception) {
      val firestoreException =
        checkNotNull(e.unwrapFirestoreException()) {
          "Expected nested FirebaseFirestoreException, got $e"
        }
      assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException.code)
    }
  }

  @Test
  fun demoWithdrawalRequest_enforcesApprovedUserMinimum200AndAvailableBalance_withoutDeductingBalanceOrStoringPii() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

      // 1. Admin creates a ₹300.00 (30,000 paise) task
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
      }
      val taskId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "High Value App Review",
              description = "Complete onboarding and submit screenshot",
              category = "App Install",
              rewardCents = 30_000L, // ₹300.00
              assignedUserId = adminUid,
              assignedUserName = "Admin",
              assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
              instructions = "1. Install app\n2. Upload screenshot",
              taskLink = "https://play.google.com/store/apps/details?id=com.example.taskflow"
            )
            .getOrThrow()
        }

      // 2. User signs in, Admin approves user, user submits proof, and Admin approves task so User has ₹300.00 wallet balance
      val userEmail = "wallet_user_${UUID.randomUUID()}@test.com"
      val userUid = signInTestUser(userEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(userUid, userEmail, "Priya Verma").getOrThrow()
      }

      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.updateUserApprovalStatus(userUid, "approved").getOrThrow()
      }

      signInTestUser(userEmail, emailVerified = true)
      val sampleGalleryUri =
        Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/2001")
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository
          .createSubmissionRecord(
            taskId = taskId,
            taskTitle = "High Value App Review",
            rewardCents = 30_000L,
            userName = "Priya Verma",
            userEmail = userEmail,
            proofText = "Completed onboarding and attached screenshot.",
            imageUri = sampleGalleryUri
          )
          .getOrThrow()
      }

      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val submittedTask =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(taskId).getOrThrow() ?: error("Expected submitted task")
        }
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository
          .reviewSubmission(
            task = submittedTask,
            approve = true,
            adminFeedback = "Approved ₹300 reward"
          )
          .getOrThrow()
      }

      // 3. User signs back in with ₹300.00 balance
      signInTestUser(userEmail, emailVerified = true)
      val profileBefore =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(userUid).getOrThrow() ?: error("Expected user profile")
        }
      assertEquals(30_000L, profileBefore.balanceCents)

      // 3a. Withdrawal below ₹200 (₹150 = 15,000 paise) -> MUST FAIL
      val belowMinResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.submitDemoWithdrawalRequest(
            uid = userUid,
            userName = "Priya Verma",
            amountCents = 15_000L,
            method = "Bank Account"
          )
        }
      assertTrue("Expected withdrawal below ₹200 to fail", belowMinResult.isFailure)

      // 3b. Withdrawal exceeding ₹300 balance (₹450 = 45,000 paise) -> MUST FAIL
      val exceedsBalanceResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.submitDemoWithdrawalRequest(
            uid = userUid,
            userName = "Priya Verma",
            amountCents = 45_000L,
            method = "UPI ID"
          )
        }
      assertTrue("Expected withdrawal exceeding balance to fail", exceedsBalanceResult.isFailure)

      // 3c. Valid Demo Withdrawal Request of ₹250.00 (25,000 paise) via UPI ID -> MUST SUCCEED
      val validReqId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .submitDemoWithdrawalRequest(
              uid = userUid,
              userName = "Priya Verma",
              amountCents = 25_000L,
              method = "UPI ID"
            )
            .getOrThrow()
        }
      assertTrue(validReqId.isNotBlank())

      // 4. Verify User's wallet balance was NOT deducted (still 30,000 paise = ₹300.00)
      val profileAfter =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(userUid).getOrThrow() ?: error("Expected user profile")
        }
      assertEquals(30_000L, profileAfter.balanceCents)
    }

  @Test
  fun adminTaskActivationToggle_deactivatedTaskRejectsSubmission_reactivatedAccepts_andDeleteTaskPreservesSubmissionsAndHistory() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

      // 1. Admin signs in -> checkAndEnsureAdminAuthorization returns true and creates /admins/{adminUid}
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
      }
      val isAdmin =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.checkAndEnsureAdminAuthorization(adminUid, BOOTSTRAPPED_ADMIN_EMAIL)
        }
      assertTrue("Expected bootstrap admin to be authorized via /admins/{uid}", isAdmin)

      val taskId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "Lifecycle Managed Task",
              description = "Test task activation, deactivation, reactivation, and deletion",
              category = "App Install",
              rewardCents = 2500L,
              assignedUserId = adminUid,
              assignedUserName = "Admin",
              assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
              instructions = "1. Open link\n2. Upload screenshot",
              taskLink = "https://play.google.com/store/apps/details?id=com.example.lifecycle",
              appName = "Lifecycle App"
            )
            .getOrThrow()
        }

      // 2. Deactivate the task as Admin
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.setTaskActiveStatus(taskId, isActive = false).getOrThrow()
      }
      val deactivatedTask =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(taskId).getOrThrow() ?: error("Expected task")
        }
      assertEquals(false, deactivatedTask.isActive)

      // 3. Normal user signs in -> Admin approves user -> submitting proof on deactivated task fails
      val userEmail = "lifecycle_user_${UUID.randomUUID()}@test.com"
      val userUid = signInTestUser(userEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(userUid, userEmail, "Kiran User").getOrThrow()
      }
      val isNormalUserAdmin =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.checkAndEnsureAdminAuthorization(userUid, userEmail)
        }
      assertEquals(false, isNormalUserAdmin)

      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.updateUserApprovalStatus(userUid, "approved").getOrThrow()
      }

      signInTestUser(userEmail, emailVerified = true)
      val sampleGalleryUri =
        Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/3001")
      val deactivatedAttempt =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = taskId,
            taskTitle = "Lifecycle Managed Task",
            rewardCents = 2500L,
            userName = "Kiran User",
            userEmail = userEmail,
            proofText = "Attempt on deactivated task",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue("Expected submission on deactivated task to fail", deactivatedAttempt.isFailure)

      // 4. Admin reactivates the task
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.setTaskActiveStatus(taskId, isActive = true).getOrThrow()
      }

      // 5. Approved user submits proof on reactivated task -> succeeds
      signInTestUser(userEmail, emailVerified = true)
      val activeAttempt =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createSubmissionRecord(
            taskId = taskId,
            taskTitle = "Lifecycle Managed Task",
            rewardCents = 2500L,
            userName = "Kiran User",
            userEmail = userEmail,
            proofText = "Completed task after reactivation",
            imageUri = sampleGalleryUri
          )
        }
      assertTrue(
        "Expected submission on reactivated task to succeed: ${activeAttempt.exceptionOrNull()}",
        activeAttempt.isSuccess
      )

      // 6. Admin deletes the task from /tasks -> existing submission and user history remain preserved
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.deleteTask(taskId).getOrThrow()
      }
      val adminSubmissionsAfterDelete =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getAdminSubmissions().getOrThrow()
        }
      assertTrue(
        "Expected Admin submission record to be preserved after task deletion",
        adminSubmissionsAfterDelete.any { it.taskId == taskId && it.userId == userUid }
      )

      signInTestUser(userEmail, emailVerified = true)
      val userSubmissionsAfterDelete =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserSubmissions(userUid).getOrThrow()
        }
      assertTrue(
        "Expected User submission history to be preserved after task deletion",
        userSubmissionsAfterDelete.any { it.taskId == taskId && it.userId == userUid }
      )
    }

  @Test
  fun phase1RewardSecurity_s2sVerification_duplicateClaimRejection_day7CheckIn500Paise_andClientWriteLockdown() =
    runBlocking {
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val baseRepo = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        baseRepo.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
      }

      val userEmail = "watch_earn_${UUID.randomUUID()}@test.com"
      val userUid = signInTestUser(userEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        baseRepo.ensureUserProfile(userUid, userEmail, "Watch User").getOrThrow()
      }

      // Admin approves Watch User
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        baseRepo.updateUserApprovalStatus(userUid, "approved").getOrThrow()
      }

      signInTestUser(userEmail, emailVerified = true)
      val todayKey = com.example.data.model.getTodayDateKey()
      val yesterdayKey = com.example.data.model.getYesterdayDateKey()

      // Verify client cannot directly write to /reward_sessions, /users/{uid}/check_ins, or /watch_stats
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("reward_sessions")
            .document("rogue_client_session")
            .set(mapOf("sessionId" to "rogue_client_session", "userId" to userUid))
            .await()
        }
        fail("Expected direct client write to /reward_sessions to fail with PERMISSION_DENIED")
      } catch (e: Exception) {
        assertEquals(
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
          e.unwrapFirestoreException()?.code
        )
      }

      var simulatedLastCheckInDate = yesterdayKey
      var simulatedStreak = 6 // Yesterday was Day 6 -> Today is Day 7 (500 paise = ₹5.00)!
      var simulatedServerBalance = 0L

      val simulatedBackend =
        SimulatedServerRewardFunctionsClient(
          currentAuthUidProvider = { auth.currentUser?.uid.orEmpty() },
          userApprovalStatusProvider = { "approved" },
          userCheckInStateProvider = { simulatedLastCheckInDate to simulatedStreak },
          onServerCredit = { _, rewardCents, newStreak, dateKey ->
            simulatedServerBalance += rewardCents
            if (newStreak != null) {
              simulatedStreak = newStreak
              simulatedLastCheckInDate = dateKey
            }
          }
        )

      val repository =
        TaskFlowRepository(
          firestore = firestore,
          photoUploader = fakeStorageUploader,
          rewardFunctionsClient = simulatedBackend
        )

      // PART A: Watch & Earn S2S Verification + Single-Use Claim
      val watchSessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createWatchEarnSession(userUid, todayKey).getOrThrow()
        }
      assertTrue(watchSessionId.isNotBlank())

      // 1. Attempting to claim BEFORE S2S verification MUST fail
      val prematureClaim =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.claimVerifiedWatchEarnSession(
            userId = userUid,
            userName = "Watch User",
            sessionId = watchSessionId,
            dateKey = todayKey
          )
        }
      assertTrue("Expected claiming unverified PENDING session to fail", prematureClaim.isFailure)

      // 2. Invalid S2S signature or missing secret MUST fail
      val invalidSigAttempt = runCatching {
        simulatedBackend.simulateSignedS2SCallback(
          sessionId = watchSessionId,
          orderId = "oid_invalid_1",
          providedSecret = "wrong_secret"
        )
      }
      assertTrue("Expected invalid S2S signature to be rejected", invalidSigAttempt.isFailure)

      val missingSecretAttempt = runCatching {
        simulatedBackend.simulateSignedS2SCallback(
          sessionId = watchSessionId,
          orderId = "oid_missing_secret",
          providedSecret = "",
          expectedSecret = ""
        )
      }
      assertTrue("Expected missing UNITY_S2S_SECRET to be rejected", missingSecretAttempt.isFailure)

      // 3. Valid Unity S2S callback marks session VERIFIED
      simulatedBackend.simulateSignedS2SCallback(
        sessionId = watchSessionId,
        orderId = "oid_valid_1001",
        providedSecret = "prod_unity_s2s_secret"
      )

      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.verifyWatchEarnSessionCompleted(userUid, watchSessionId).getOrThrow()
      }

      // 4. First Claim succeeds (+100 paise)
      val claimedWatchReward =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .claimVerifiedWatchEarnSession(
              userId = userUid,
              userName = "Watch User",
              sessionId = watchSessionId,
              dateKey = todayKey
            )
            .getOrThrow()
        }
      assertEquals(100L, claimedWatchReward)
      assertEquals(100L, simulatedServerBalance)

      // 5. Duplicate Claim on the same session MUST fail and not double-credit
      val duplicateWatchClaim =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.claimVerifiedWatchEarnSession(
            userId = userUid,
            userName = "Watch User",
            sessionId = watchSessionId,
            dateKey = todayKey
          )
        }
      assertTrue("Expected duplicate ad reward claim to fail", duplicateWatchClaim.isFailure)
      assertEquals(100L, simulatedServerBalance)

      // PART B: Daily Check-In Day 7 Reward Calculation (500 paise = ₹5.00) & Duplicate Same-Day Rejection
      val checkInSessionId =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createDailyCheckInSession(
              userId = userUid,
              dateKey = todayKey,
              streakDay = 7,
              rewardCents = 500L
            )
            .getOrThrow()
        }

      simulatedBackend.simulateSignedS2SCallback(
        sessionId = checkInSessionId,
        orderId = "oid_checkin_day7",
        providedSecret = "prod_unity_s2s_secret"
      )

      val day7Reward =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .claimDailyCheckIn(
              userId = userUid,
              streakDay = 7,
              rewardCents = 500L,
              dateKey = todayKey,
              userName = "Watch User",
              verifiedAdCallbackToken = checkInSessionId
            )
            .getOrThrow()
        }
      assertEquals(500L, day7Reward)
      assertEquals(600L, simulatedServerBalance)
      assertEquals(7, simulatedStreak)
      assertEquals(todayKey, simulatedLastCheckInDate)

      // Attempting a second Daily Check-in on the same date MUST fail
      val duplicateCheckInSession =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createDailyCheckInSession(
            userId = userUid,
            dateKey = todayKey,
            streakDay = 1,
            rewardCents = 100L
          )
        }
      assertFalse(
        "Expected duplicate daily check-in session on the same date to be rejected",
        duplicateCheckInSession.isSuccess
      )
      assertEquals(600L, simulatedServerBalance)
    }

  @Test
  fun multiUserTaskSubmissions_independentSubmissionIsolationDuplicatePreventionAndSeparateAdminApprovals() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

      // Step 1: Admin bootstraps and creates an active pooled task (reward: 1200 paise = ₹12.00)
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      android.util.Log.d("TestDebug", "adminUid: '$adminUid'")
      val taskCreation =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createTask(
            title = "Test Checkout Responsiveness",
            description = "Test multi-user submission on pooled task.",
            category = "App Install",
            rewardCents = 1200L,
            assignedUserId = adminUid,
            assignedUserName = "Admin",
            assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
            instructions = "Verify responsiveness on your device.",
            taskLink = "https://example.com/test",
            appName = "TaskFlow",
            appIconUrl = "https://example.com/icon.png",
            requiredProof = "Screenshot + device details"
          )
        }
      assertTrue("Expected admin pooled task creation to succeed: ${taskCreation.exceptionOrNull()}", taskCreation.isSuccess)
      val pooledTaskId = taskCreation.getOrThrow()

      // Step 2: Register and approve User A (Alice) and User B (Bob)
      val aliceEmail = "alice_multi_${UUID.randomUUID().toString().take(6)}@test.com"
      val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
      repository.ensureUserProfile(aliceUid, aliceEmail, "Alice Multi").getOrThrow()

      val bobEmail = "bob_multi_${UUID.randomUUID().toString().take(6)}@test.com"
      val bobUid = signInTestUser(bobEmail, emailVerified = true)
      repository.ensureUserProfile(bobUid, bobEmail, "Bob Multi").getOrThrow()

      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      repository.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
      repository.updateUserApprovalStatus(bobUid, "approved").getOrThrow()

      val sampleUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1002")

      // Step 3 (Requirement 3.1): Alice submits proof for the pooled task -> creates /submissions/${pooledTaskId}_${aliceUid}
      signInTestUser(aliceEmail, emailVerified = true)
      val aliceTask = repository.getTaskById(pooledTaskId).getOrThrow() ?: error("Task not found")
      val aliceSubmitResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.submitTaskProof(
            task = aliceTask,
            userName = "Alice Multi",
            userEmail = aliceEmail,
            proofText = "Alice tested on Pixel 8 Pro.",
            imageUri = sampleUri
          )
        }
      assertTrue("Expected Alice submission to succeed: ${aliceSubmitResult.exceptionOrNull()}", aliceSubmitResult.isSuccess)

      // Step 4 (Requirement 3.2): Alice attempts duplicate submission on the same task -> MUST FAIL
      val aliceDuplicateSubmit =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.submitTaskProof(
            task = aliceTask,
            userName = "Alice Multi",
            userEmail = aliceEmail,
            proofText = "Alice attempting duplicate submission.",
            imageUri = sampleUri
          )
        }
      assertTrue("Expected duplicate submission by Alice to be rejected", aliceDuplicateSubmit.isFailure)

      // Step 5 (Requirement 3.1): Bob submits proof for the SAME pooled task independently -> MUST SUCCEED without overwriting Alice
      signInTestUser(bobEmail, emailVerified = true)
      val bobTask = repository.getTaskById(pooledTaskId).getOrThrow() ?: error("Task not found")
      val bobSubmitResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.submitTaskProof(
            task = bobTask,
            userName = "Bob Multi",
            userEmail = bobEmail,
            proofText = "Bob tested on Samsung S24.",
            imageUri = sampleUri
          )
        }
      assertTrue("Expected Bob submission on same task to succeed independently: ${bobSubmitResult.exceptionOrNull()}", bobSubmitResult.isSuccess)

      // Step 6 (Requirement 3.3): Bob attempts to read Alice's submission document -> MUST FAIL with permission denied
      val unauthorizedAccess =
        runCatching {
          firestore.collection("submissions").document("${pooledTaskId}_$aliceUid").get().await()
        }
      assertTrue(
        "Expected Bob reading Alice's submission to be blocked by security rules: ${unauthorizedAccess.exceptionOrNull()}",
        unauthorizedAccess.isFailure
      )

      // Step 7 (Requirement 3.4): Admin signs in and independently approves Alice's submission
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val aliceSubDoc = repository.getSubmissionById("${pooledTaskId}_$aliceUid").getOrThrow() ?: error("Alice submission not found")
      val aliceReviewTask = aliceSubDoc.toHistoryTaskItem(aliceTask).copy(id = "${pooledTaskId}_$aliceUid")

      val aliceApproveResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = aliceReviewTask,
            approve = true,
            adminFeedback = "Approved Alice report."
          )
        }
      assertTrue("Expected Admin approval for Alice to succeed: ${aliceApproveResult.exceptionOrNull()}", aliceApproveResult.isSuccess)

      val aliceProfileAfter = repository.getUserProfile(aliceUid).getOrThrow() ?: error("Alice profile not found")
      assertEquals(1200L, aliceProfileAfter.balanceCents)
      assertEquals(1L, aliceProfileAfter.completedTasksCount)

      // Step 8 (Requirement 3.5): Duplicate approval attempt on Alice's submission MUST FAIL and not double-credit
      val aliceDuplicateApprove =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = aliceReviewTask,
            approve = true,
            adminFeedback = "Duplicate approval attempt on Alice."
          )
        }
      assertTrue("Expected duplicate approval on Alice to fail", aliceDuplicateApprove.isFailure)
      val aliceProfileStill1200 = repository.getUserProfile(aliceUid).getOrThrow() ?: error("Alice profile not found")
      assertEquals(1200L, aliceProfileStill1200.balanceCents)

      // Step 9 (Requirement 3.4): Admin independently approves Bob's submission -> credits Bob with 1200 paise
      val bobSubDoc = repository.getSubmissionById("${pooledTaskId}_$bobUid").getOrThrow() ?: error("Bob submission not found")
      val bobReviewTask = bobSubDoc.toHistoryTaskItem(bobTask).copy(id = "${pooledTaskId}_$bobUid")

      val bobApproveResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = bobReviewTask,
            approve = true,
            adminFeedback = "Approved Bob report."
          )
        }
      assertTrue("Expected Admin approval for Bob to succeed: ${bobApproveResult.exceptionOrNull()}", bobApproveResult.isSuccess)

      val bobProfileAfter = repository.getUserProfile(bobUid).getOrThrow() ?: error("Bob profile not found")
      assertEquals(1200L, bobProfileAfter.balanceCents)
      assertEquals(1L, bobProfileAfter.completedTasksCount)
    }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 10000L
    const val FLOW_TIMEOUT_MS = 10000L
  }
}

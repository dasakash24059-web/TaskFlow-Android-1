package com.example.data.repository

import android.net.Uri
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.TaskStatus
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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

    // 3. User attempting to change their own role or balance fails with PERMISSION_DENIED
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

    try {
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore
          .collection("users")
          .document(aliceUid)
          .update(
            mapOf(
              "balanceCents" to FieldValue.increment(1000L),
              "updatedAt" to FieldValue.serverTimestamp()
            )
          )
          .await()
      }
      fail("Expected user self-balance increment to fail")
    } catch (e: Exception) {
      assertEquals(
        FirebaseFirestoreException.Code.PERMISSION_DENIED,
        e.unwrapFirestoreException()?.code
      )
    }
  }

  @Test
  fun mandatoryTextAndPhotoSubmission_approvalExactCredit_repeatedApprovalFailure_andRejectionWithoutCredit() =
    runBlocking {
      val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)

      // Step 1: Verified Bootstrap Admin signs in first and creates two tasks (even when Admin is the only user at creation time!)
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

      val createdTask1 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task1Id).getOrThrow() ?: error("Expected created task 1")
        }
      assertEquals(playStoreUrl, createdTask1.taskLink)
      assertEquals("TaskFlow Companion", createdTask1.appName)
      assertEquals("https://example.com/app-icon.png", createdTask1.appIconUrl)
      assertTrue(createdTask1.requiredProof.contains("screenshot of checkout page"))
      assertTrue(createdTask1.instructions.contains("Open Google Play Store link"))

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

      // Step 2: Second User account (Alice) signs in, sees the Admin-created tasks, and submits proof
      val aliceEmail = "alice_sub_${UUID.randomUUID()}@test.com"
      val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(aliceUid, aliceEmail, "Alice").getOrThrow()
      }

      val sampleGalleryUri = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1001")

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

      // Step 3-6: Alice submits BOTH written text AND gallery photo on Task 1 and Task 2 -> MUST SUCCEED
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
        "Expected task 1 submission with text + photo by second user account to succeed: ${submit1Result.exceptionOrNull()}",
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
        "Expected task 2 submission with text + photo by second user account to succeed: ${submit2Result.exceptionOrNull()}",
        submit2Result.isSuccess
      )

      // Step 7-8: Admin signs in and verifies that /submissions contains both submissions with Alice's UID, name, text proof, and image URL
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val adminSubmissions =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getAdminSubmissions().getOrThrow()
        }
      val sub1 = adminSubmissions.firstOrNull { it.taskId == task1Id }
      assertNotNull("Expected Task 1 submission to be visible in Admin submissions list", sub1)
      assertEquals(aliceUid, sub1?.userId)
      assertEquals("Alice", sub1?.userName)
      assertEquals("All checkout steps verified on Android 16.", sub1?.proofText)
      assertEquals(TaskStatus.SUBMITTED, sub1?.status)
      assertTrue(sub1?.proofImageUrl?.startsWith("https://firebasestorage.googleapis.com/") == true)

      val submittedTask1 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task1Id).getOrThrow() ?: error("Expected task 1")
        }
      assertEquals(aliceUid, submittedTask1.assignedUserId)
      assertEquals("Alice", submittedTask1.assignedUserName)
      assertTrue(submittedTask1.proofImageUrl.startsWith("https://firebasestorage.googleapis.com/"))
      assertEquals("submissions/$aliceUid/$task1Id/proof_test.jpg", submittedTask1.proofStoragePath)

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

      // Step 11: Separate rejection test on Task 2 -> status becomes 'rejected' and Alice's wallet balance remains 1500L (no reward credited)
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

      // Newly registered user Bob signs up and can immediately see tasks created/updated by Admin
      val bobEmail = "bob_reassign_${UUID.randomUUID()}@test.com"
      val bobUid = signInTestUser(bobEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(bobUid, bobEmail, "Bob").getOrThrow()
        val bobVisibleTasks = repository.getUserTasks(bobUid).getOrThrow()
        assertTrue(
          "Expected newly registered user Bob to see tasks created by Admin",
          bobVisibleTasks.any { it.id == task1Id } && bobVisibleTasks.any { it.id == task2Id }
        )
      }

      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val task3Id =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "Reassignable Task",
              description = "Initially assigned to Alice",
              category = "QA Testing",
              rewardCents = 900L,
              assignedUserId = aliceUid,
              assignedUserName = "Alice",
              assignedUserEmail = aliceEmail,
              instructions = "Follow link",
              taskLink = "https://example.com/task3"
            )
            .getOrThrow()
        }

      val aliceBeforeReassign =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      assertEquals(task3Id, aliceBeforeReassign.assignedTaskId)

      // Admin attempting to clear Alice's assignedTaskId alone while task3Id is still assigned to Alice must fail
      try {
        withTimeout(DEFAULT_TIMEOUT_MS) {
          firestore
            .collection("users")
            .document(aliceUid)
            .update(mapOf("assignedTaskId" to "", "updatedAt" to FieldValue.serverTimestamp()))
            .await()
        }
        fail("Expected clearing assignedTaskId alone while task remains assigned to Alice to fail")
      } catch (e: Exception) {
        assertEquals(
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
          e.unwrapFirestoreException()?.code
        )
      }

      // Clearing Alice's assignedTaskId atomically via task reassignment to Bob must succeed
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository
          .updateTaskDetails(
            taskId = task3Id,
            title = "Reassigned Task",
            description = "Reassigned from Alice to Bob",
            category = "QA Testing",
            rewardCents = 900L,
            assignedUserId = bobUid,
            assignedUserName = "Bob",
            assignedUserEmail = bobEmail,
            instructions = "Updated instructions",
            taskLink = "https://example.com/task3-updated"
          )
          .getOrThrow()
      }

      val aliceAfterReassign =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      val bobAfterReassign =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(bobUid).getOrThrow() ?: error("Expected Bob profile")
        }
      assertEquals("", aliceAfterReassign.assignedTaskId)
      assertEquals(task3Id, bobAfterReassign.assignedTaskId)

      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.deleteTask(task3Id).getOrThrow()
      }

      val bobAfterDelete =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(bobUid).getOrThrow() ?: error("Expected Bob profile")
        }
      assertEquals("", bobAfterDelete.assignedTaskId)
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
        val isPermissionDenied = (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
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
        val isPermissionDenied = (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
          e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
          e is com.google.firebase.FirebaseException
        assertTrue("Expected PERMISSION_DENIED for cross-user balance update, got $e", isPermissionDenied)
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
        fail("Expected direct balance increase by admin without approved task batch to fail with PERMISSION_DENIED")
      } catch (e: Exception) {
        val firestoreException = e.unwrapFirestoreException()
        val isPermissionDenied = (firestoreException?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) ||
          e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true ||
          e is com.google.firebase.FirebaseException
        assertTrue("Expected PERMISSION_DENIED for admin balance update without batch, got $e", isPermissionDenied)
      }
    }

  @Test
  fun getUserProfile_crossUserAccess_failsWithPermissionDenied() = runBlocking {
    val aliceEmail = "alice_iso_${UUID.randomUUID()}@test.com"
    val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
    val repository = TaskFlowRepository(firestore, photoUploader = fakeStorageUploader)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repository.ensureUserProfile(aliceUid, aliceEmail, "Alice").getOrThrow()
    }

    val bobEmail = "bob_iso_${UUID.randomUUID()}@test.com"
    signInTestUser(bobEmail, emailVerified = true)
    val bobGetAliceResult =
      withTimeout(DEFAULT_TIMEOUT_MS) { repository.getUserProfile(aliceUid) }
    assertTrue("Expected cross-user profile read to fail", bobGetAliceResult.isFailure)
    val firestoreException = bobGetAliceResult.exceptionOrNull()?.unwrapFirestoreException()
    assertNotNull(firestoreException)
    assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, firestoreException?.code)
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
  fun demoWithdrawalRequest_enforcesMinimum200AndAvailableBalance_withoutDeductingBalanceOrStoringPii() =
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

      // 2. User signs in, submits proof, and Admin approves so User has ₹300.00 (30,000 paise) wallet balance
      val userEmail = "wallet_user_${UUID.randomUUID()}@test.com"
      val userUid = signInTestUser(userEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(userUid, userEmail, "Priya Verma").getOrThrow()
      }
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

      // 5. Verify Admin can read the Demo Withdrawal Request in Admin Dashboard
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val adminWithdrawals =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getAdminWithdrawalRequests().getOrThrow()
        }
      val recordedReq = adminWithdrawals.firstOrNull { it.id == validReqId || it.requestId == validReqId }
      assertNotNull("Expected demo withdrawal request to be visible to Admin", recordedReq)
      assertEquals("Priya Verma", recordedReq?.userName)
      assertEquals(25_000L, recordedReq?.amountCents)
      assertEquals("UPI ID", recordedReq?.method)
      assertEquals("pending_demo", recordedReq?.status)
      assertEquals(true, recordedReq?.isDemo)
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

      // 3. Normal user signs in -> checkAndEnsureAdminAuthorization returns false, and submitting proof on deactivated task fails
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

      // 5. User submits proof on reactivated task -> succeeds
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

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 10000L
    const val FLOW_TIMEOUT_MS = 10000L
  }
}


package com.example.data.repository

import android.net.Uri
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
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

      // 1. Alice registers her profile (starts as 'pending')
      val aliceEmail = "alice_sub_${UUID.randomUUID()}@test.com"
      val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(aliceUid, aliceEmail, "Alice").getOrThrow()
      }

      // 2. Verified Bootstrap Admin signs in, approves Alice, and creates two tasks with HTTPS links & step-by-step instructions
      val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
        repository.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
      }

      // 2a. Creating a task with an invalid non-HTTPS URL must fail validation
      val invalidUrlTaskResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.createTask(
            title = "Invalid Link Task",
            description = "Task with invalid link",
            category = "QA Testing",
            rewardCents = 1000L,
            assignedUserId = aliceUid,
            assignedUserName = "Alice",
            assignedUserEmail = aliceEmail,
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
              category = "QA Testing",
              rewardCents = 1500L,
              assignedUserId = aliceUid,
              assignedUserName = "Alice",
              assignedUserEmail = aliceEmail,
              instructions = "1. Open Google Play Store link\n2. Verify checkout screen\n3. Take screenshot",
              taskLink = playStoreUrl
            )
            .getOrThrow()
        }

      val createdTask1 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task1Id).getOrThrow() ?: error("Expected created task 1")
        }
      assertEquals(playStoreUrl, createdTask1.taskLink)
      assertTrue(createdTask1.instructions.contains("Open Google Play Store link"))

      val aliceAfterTask1Assigned =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      assertEquals(task1Id, aliceAfterTask1Assigned.assignedTaskId)

      val task2Id =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository
            .createTask(
              title = "Review Onboarding Copy",
              description = "Check onboarding strings",
              category = "Content Review",
              rewardCents = 2000L,
              assignedUserId = aliceUid,
              assignedUserName = "Alice",
              assignedUserEmail = aliceEmail,
              instructions = "1. Open website\n2. Capture screenshot of onboarding",
              taskLink = "https://example.com/onboarding"
            )
            .getOrThrow()
        }

      val aliceAfterTask2Assigned =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getUserProfile(aliceUid).getOrThrow() ?: error("Expected Alice profile")
        }
      assertEquals(task2Id, aliceAfterTask2Assigned.assignedTaskId)

      // 3. Alice signs in and attempts text-only and photo-only submissions (both MUST be blocked)
      signInTestUser(aliceEmail, emailVerified = true)
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

      // 3c. Both written text AND gallery photo -> MUST SUCCEED
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
        "Expected task 1 submission with text + photo to succeed: ${submit1Result.exceptionOrNull()}",
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
        "Expected task 2 submission with text + photo to succeed: ${submit2Result.exceptionOrNull()}",
        submit2Result.isSuccess
      )

      // 4. Admin approves task 1 atomically -> credits exact 1500 cents and +1 completedTasksCount
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
      val submittedTask1 =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.getTaskById(task1Id).getOrThrow() ?: error("Expected task 1")
        }
      assertTrue(submittedTask1.proofImageUrl.startsWith("https://firebasestorage.googleapis.com/"))
      assertEquals("submissions/$aliceUid/$task1Id/proof_test.jpg", submittedTask1.proofStoragePath)

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

      // 5. Repeated approval attempt on already approved task 1 must fail
      val repeatedApprovalResult =
        withTimeout(DEFAULT_TIMEOUT_MS) {
          repository.reviewSubmission(
            task = submittedTask1,
            approve = true,
            adminFeedback = "Duplicate approval attempt"
          )
        }
      assertTrue("Expected repeated approval attempt to fail", repeatedApprovalResult.isFailure)

      // 6. Admin rejects task 2 atomically -> status becomes 'rejected' and balance remains 1500L
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

      // 7. Newly registered user Bob signs up and can immediately see tasks created/updated by Admin
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
              category = "General",
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

      // 7a. Admin attempting to clear Alice's assignedTaskId alone while task3Id is still assigned to Alice must fail
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

      // 7b. Clearing Alice's assignedTaskId atomically via task reassignment to Bob must succeed
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repository
          .updateTaskDetails(
            taskId = task3Id,
            title = "Reassigned Task",
            description = "Reassigned from Alice to Bob",
            category = "General",
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

      // Sign in as verified bootstrap admin and attempt an unrelated balance increase
      signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
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
        fail("Expected direct balance increase without an approved task batch to fail")
      } catch (e: Exception) {
        val firestoreException = e.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException, got $e", firestoreException)
        assertEquals(
          FirebaseFirestoreException.Code.PERMISSION_DENIED,
          firestoreException?.code
        )
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

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 10000L
    const val FLOW_TIMEOUT_MS = 10000L
  }
}

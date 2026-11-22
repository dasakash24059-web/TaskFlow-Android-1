package com.example.data.repository

import android.net.Uri
import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.TaskStatus
import com.example.data.model.coinsToCents
import com.google.firebase.firestore.FirebaseFirestoreException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Real Firebase Auth + Firestore Emulator Security Rules & Financial Verification Test for P0 Remediation:
 * - C-01: Blocks direct client wallet inflation & lastRewardId bypass; allows permitted profile edits
 * - C-02: Blocks direct client writes to reward sessions/stats & forged CREDITED transactions; allows owner reads
 * - C-04: Enforces canonical 125 TF = 100 paise on task creation, submission, and atomic admin approval + blocks rewardCoins forgery
 * - C-05: Blocks cross-user /transactions get/list (including status == CREDITED scans); preserves owner & admin reads
 */
@Config(sdk = [33])
class P0FirestoreRulesEmulatorTest : FirestoreEmulatorTestBase() {

  private val fakeStorageUploader = ProofPhotoUploader { userId, taskId, _, onProgress ->
    onProgress(100)
    UploadedProofPhoto(
      downloadUrl =
        "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2F$userId%2F$taskId%2Fproof_p0.jpg?alt=media",
      storagePath = "submissions/$userId/$taskId/proof_p0.jpg"
    )
  }

  private suspend fun expectPermissionDenied(actionDescription: String, block: suspend () -> Unit) {
    try {
      withTimeout(DEFAULT_TIMEOUT_MS) { block() }
      fail("Expected PERMISSION_DENIED for: $actionDescription")
    } catch (e: Exception) {
      val code = e.unwrapFirestoreException()?.code
      val matches =
        code == FirebaseFirestoreException.Code.PERMISSION_DENIED ||
          e.message?.contains("PERMISSION_DENIED", ignoreCase = true) == true
      assertTrue("Expected PERMISSION_DENIED for $actionDescription, got $e", matches)
    }
  }

  private fun seedEmulatorDocument(documentPath: String, fieldsJson: JSONObject) {
    val cleanPath = documentPath.trimStart('/')
    val docName = "projects/demo-no-project/databases/$databaseId/documents/$cleanPath"
    val url =
      URL("http://127.0.0.1:8085/v1/projects/demo-no-project/databases/$databaseId/documents:commit")
    val conn =
      (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        connectTimeout = 5000
        readTimeout = 5000
        setRequestProperty("Authorization", "Bearer owner")
        setRequestProperty("Content-Type", "application/json")
        doOutput = true
      }
    val writeObj =
      JSONObject().put("update", JSONObject().put("name", docName).put("fields", fieldsJson))
    val body = JSONObject().put("writes", JSONArray().put(writeObj)).toString()
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val status = conn.responseCode
    val errText =
      if (status !in 200..299) conn.errorStream?.bufferedReader()?.readText().orEmpty() else ""
    conn.disconnect()
    check(status in 200..299) {
      "Failed to seed emulator document '$cleanPath' (HTTP $status): $errText"
    }
  }

  private fun strField(value: String): JSONObject = JSONObject().put("stringValue", value)

  private fun intField(value: Long): JSONObject = JSONObject().put("integerValue", value.toString())

  private fun boolField(value: Boolean): JSONObject = JSONObject().put("booleanValue", value)

  @Test
  fun c01_c02_c04_c05_firestoreRulesEnforceZeroTrustWalletSessionsTaskMathAndTxPrivacy() = runBlocking {
    val repo = TaskFlowRepository(firestore = firestore, photoUploader = fakeStorageUploader)

    // Bootstrap admin and two approved users (Alice & Bob)
    val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
    }

    val suffix = UUID.randomUUID().toString().take(8)
    val aliceEmail = "alice_p0_$suffix@test.com"
    val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(aliceUid, aliceEmail, "Alice P0").getOrThrow()
    }

    val bobEmail = "bob_p0_$suffix@test.com"
    val bobUid = signInTestUser(bobEmail, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(bobUid, bobEmail, "Bob P0").getOrThrow()
    }

    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
      repo.updateUserApprovalStatus(bobUid, "approved").getOrThrow()
    }

    // Seed server-owned sessions & transactions via Admin SDK simulation (Bearer owner)
    seedEmulatorDocument(
      "users/$aliceUid/spin_sessions/srv_spin_$suffix",
      JSONObject()
        .put("userId", strField(aliceUid))
        .put("rewardCoins", intField(25L))
        .put("claimed", boolField(false))
    )
    seedEmulatorDocument(
      "users/$aliceUid/scratch_sessions/srv_scratch_$suffix",
      JSONObject()
        .put("userId", strField(aliceUid))
        .put("rewardCoins", intField(30L))
        .put("serverGenerated", boolField(true))
    )
    seedEmulatorDocument(
      "users/$aliceUid/scratch_stats/2026-11-22",
      JSONObject().put("userId", strField(aliceUid)).put("scratchesCompletedToday", intField(1L))
    )
    seedEmulatorDocument(
      "users/$aliceUid/check_ins/2026-11-22",
      JSONObject().put("userId", strField(aliceUid)).put("rewardCoins", intField(10L))
    )
    seedEmulatorDocument(
      "users/$aliceUid/watch_stats/2026-11-22",
      JSONObject().put("userId", strField(aliceUid)).put("videosWatched", intField(1L))
    )
    seedEmulatorDocument(
      "transactions/tx_alice_$suffix",
      JSONObject()
        .put("userId", strField(aliceUid))
        .put("type", strField("DAILY_CHECK_IN"))
        .put("status", strField("CREDITED"))
        .put("amountCoins", intField(10L))
        .put("amountCents", intField(0L))
    )
    seedEmulatorDocument(
      "transactions/tx_bob_$suffix",
      JSONObject()
        .put("userId", strField(bobUid))
        .put("type", strField("CAMPAIGN_REWARD"))
        .put("status", strField("CREDITED"))
        .put("amountCoins", intField(500L))
        .put("amountCents", intField(400L))
    )

    // =========================================================================
    // C-01: DIRECT CLIENT WALLET INFLATION & lastRewardId BYPASS BLOCKED
    // =========================================================================
    signInTestUser(aliceEmail, emailVerified = true)
    val aliceRef = firestore.collection("users").document(aliceUid)

    expectPermissionDenied("C-01: direct balanceCoins update") {
      aliceRef.update("balanceCoins", 999999L).await()
    }
    expectPermissionDenied("C-01: direct eligibleCoins update") {
      aliceRef.update("eligibleCoins", 999999L).await()
    }
    expectPermissionDenied("C-01: direct promotionalCoins update") {
      aliceRef.update("promotionalCoins", 999999L).await()
    }
    expectPermissionDenied("C-01: direct balanceCents update") {
      aliceRef.update("balanceCents", 999999L).await()
    }
    expectPermissionDenied("C-01: direct completedTasksCount update") {
      aliceRef.update("completedTasksCount", 99L).await()
    }
    expectPermissionDenied("C-01: direct checkInStreak update") {
      aliceRef.update("checkInStreak", 30L).await()
    }
    expectPermissionDenied("C-01: direct extraSpinAvailable update") {
      aliceRef.update("extraSpinAvailable", 10L).await()
    }
    expectPermissionDenied("C-01: lastRewardId bypass attempting wallet inflation") {
      aliceRef
        .update(
          mapOf(
            "lastRewardId" to "forged_reward_$suffix",
            "balanceCoins" to 500000L,
            "eligibleCoins" to 500000L,
            "promotionalCoins" to 50000L,
            "balanceCents" to 400000L,
            "completedTasksCount" to 50L,
            "checkInStreak" to 25L,
            "extraSpinAvailable" to 5L
          )
        )
        .await()
    }
    expectPermissionDenied("C-01: self role escalation") {
      aliceRef.update("role", "admin").await()
    }

    expectPermissionDenied("C-01: unpermitted profile field outside allowlist") {
      aliceRef.update(mapOf("displayName" to "Alice Renamed", "phone" to "9876543210")).await()
    }

    // ALLOWED: Permitted profile fields (displayName + updatedAt) remain writable by account owner
    withTimeout(DEFAULT_TIMEOUT_MS) {
      aliceRef
        .update(
          mapOf(
            "displayName" to "Alice Renamed",
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    val updatedAlice = withTimeout(DEFAULT_TIMEOUT_MS) { aliceRef.get().await() }
    assertEquals("Alice Renamed", updatedAlice.getString("displayName"))

    // =========================================================================
    // C-02: SERVER-AUTHORITATIVE REWARD SESSIONS & FORGED TRANSACTIONS LOCKED
    // =========================================================================
    expectPermissionDenied("C-02: client create spin_sessions") {
      aliceRef
        .collection("spin_sessions")
        .document("fake_spin_$suffix")
        .set(mapOf("userId" to aliceUid, "rewardCoins" to 5000L, "claimed" to false))
        .await()
    }
    expectPermissionDenied("C-02: client create scratch_sessions") {
      aliceRef
        .collection("scratch_sessions")
        .document("fake_scratch_$suffix")
        .set(mapOf("userId" to aliceUid, "rewardCoins" to 5000L, "serverGenerated" to true))
        .await()
    }
    expectPermissionDenied("C-02: client write scratch_stats") {
      aliceRef
        .collection("scratch_stats")
        .document("2026-11-23")
        .set(mapOf("userId" to aliceUid, "unlockedScratchesCount" to 50L))
        .await()
    }
    expectPermissionDenied("C-02: client write check_ins") {
      aliceRef
        .collection("check_ins")
        .document("2026-11-23")
        .set(mapOf("userId" to aliceUid, "rewardCoins" to 500L))
        .await()
    }
    expectPermissionDenied("C-02: client write watch_stats") {
      aliceRef
        .collection("watch_stats")
        .document("2026-11-23")
        .set(mapOf("userId" to aliceUid, "videosWatched" to 0L))
        .await()
    }
    expectPermissionDenied("C-02: client write promo_redemptions") {
      firestore
        .collection("promo_redemptions")
        .document("FAKE_$aliceUid")
        .set(mapOf("userId" to aliceUid, "promoId" to "FAKE", "rewardCoins" to 500L))
        .await()
    }
    expectPermissionDenied("C-02: client write telegram_claims") {
      firestore
        .collection("telegram_claims")
        .document("tg_$aliceUid")
        .set(mapOf("userId" to aliceUid, "rewardCoins" to 500L))
        .await()
    }
    expectPermissionDenied("C-02: client write manual_bonuses") {
      firestore
        .collection("manual_bonuses")
        .document("bonus_$suffix")
        .set(mapOf("userId" to aliceUid, "rewardCoins" to 500L))
        .await()
    }
    expectPermissionDenied("C-02: client create forged CREDITED transaction") {
      firestore
        .collection("transactions")
        .document("forged_tx_$suffix")
        .set(
          mapOf(
            "userId" to aliceUid,
            "type" to "PROMO_CODE",
            "status" to "CREDITED",
            "amountCoins" to 99999L,
            "amountCents" to 0L
          )
        )
        .await()
    }

    // ALLOWED: Owner reads own server-seeded sessions/stats/transactions
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRef.collection("spin_sessions").document("srv_spin_$suffix").get().await().exists()
      }
    )
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRef.collection("scratch_sessions").document("srv_scratch_$suffix").get().await().exists()
      }
    )
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRef.collection("scratch_stats").document("2026-11-22").get().await().exists()
      }
    )
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRef.collection("check_ins").document("2026-11-22").get().await().exists()
      }
    )
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        aliceRef.collection("watch_stats").document("2026-11-22").get().await().exists()
      }
    )

    // =========================================================================
    // C-05: CROSS-USER TRANSACTION ISOLATION (OWNER & ADMIN ONLY)
    // =========================================================================
    // ALLOWED: Alice reads her own transaction
    val aliceTx =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("transactions").document("tx_alice_$suffix").get().await()
      }
    assertTrue(aliceTx.exists())
    val aliceOwnList =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("transactions").whereEqualTo("userId", aliceUid).get().await()
      }
    assertTrue(aliceOwnList.documents.any { it.id == "tx_alice_$suffix" })

    // DENIED: Alice cannot read Bob's transaction by ID
    expectPermissionDenied("C-05: User A get User B transaction") {
      firestore.collection("transactions").document("tx_bob_$suffix").get().await()
    }
    // DENIED: Alice cannot list all CREDITED transactions across users
    expectPermissionDenied("C-05: User A list all CREDITED transactions") {
      firestore.collection("transactions").whereEqualTo("status", "CREDITED").get().await()
    }
    // DENIED: Alice cannot list Bob's transactions
    expectPermissionDenied("C-05: User A list User B transactions") {
      firestore.collection("transactions").whereEqualTo("userId", bobUid).get().await()
    }

    // ALLOWED: Admin can read all transactions (both Alice & Bob)
    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("transactions").document("tx_alice_$suffix").get().await().exists()
      }
    )
    assertTrue(
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("transactions").document("tx_bob_$suffix").get().await().exists()
      }
    )

    // =========================================================================
    // C-04: CANONICAL 125 TF = ₹1.00 = 100 PAISE TASK CREATION, SUBMISSION & APPROVAL
    // =========================================================================
    val createdTaskId =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo
          .createTask(
            title = "Canonical 125 TF Task $suffix",
            description = "Submit screenshot and note",
            rewardCents = coinsToCents(125L),
            rewardCoins = 125L,
            category = "Survey",
            assignedUserId = aliceUid,
            assignedUserName = "Alice Renamed",
            assignedUserEmail = aliceEmail,
            instructions = "1. Complete survey\n2. Upload screenshot",
            taskLink = "https://example.com/survey"
          )
          .getOrThrow()
      }
    val taskSnap =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("tasks").document(createdTaskId).get().await()
      }
    assertEquals(125L, taskSnap.getLong("rewardCoins"))
    assertEquals(100L, taskSnap.getLong("rewardCents"))

    val createdTask =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.getTaskById(createdTaskId).getOrThrow() ?: error("Expected created task")
      }
    assertEquals(125L, createdTask.getEffectiveRewardCoins())
    assertEquals(100L, createdTask.rewardCents)

    // Alice tries to submit with forged rewardCoins = 99999 -> MUST BE DENIED by firestore.rules
    signInTestUser(aliceEmail, emailVerified = true)
    expectPermissionDenied("C-04: client forging rewardCoins on submission create") {
      firestore
        .collection("submissions")
        .document("${createdTaskId}_forged_$aliceUid")
        .set(
          mapOf(
            "taskId" to createdTaskId,
            "taskTitle" to "Canonical 125 TF Task $suffix",
            "userId" to aliceUid,
            "userName" to "Alice Renamed",
            "userEmail" to aliceEmail,
            "rewardCents" to 100L,
            "rewardCoins" to 99999L,
            "proofText" to "Forged proof",
            "proofImageUrl" to "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/submissions%2F$aliceUid%2F$createdTaskId%2Fproof.jpg?alt=media",
            "proofImageStoragePath" to "submissions/$aliceUid/$createdTaskId/proof.jpg",
            "status" to TaskStatus.SUBMITTED
          )
        )
        .await()
    }

    // Legitimate Alice submission succeeds with canonical rewardCoins = 125, rewardCents = 100
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo
        .submitTaskProof(
          task = createdTask,
          userName = "Alice Renamed",
          userEmail = aliceEmail,
          proofText = "Completed survey confirmation #12345",
          imageUri = Uri.parse("content://test/proof.jpg")
        )
        .getOrThrow()
    }

    // Admin approves submission atomically -> exact 125 TF / 100 paise credited (NOT 156 TF / 125 paise!)
    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    val submittedTask =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.getTaskById(createdTaskId).getOrThrow() ?: error("Expected submitted task")
      }
    val subSnap =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("submissions").whereEqualTo("taskId", createdTaskId).get().await().documents.single()
      }
    val subId = subSnap.id
    assertTrue("Expected submission ID on submitted task", subId.isNotBlank())
    assertEquals(125L, subSnap.getLong("rewardCoins"))
    assertEquals(100L, subSnap.getLong("rewardCents"))

    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.reviewSubmission(task = submittedTask, approve = true, adminFeedback = "Verified proof").getOrThrow()
    }

    val aliceAfterApproval =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("users").document(aliceUid).get().await()
      }
    assertEquals(125L, aliceAfterApproval.getLong("balanceCoins"))
    assertEquals(125L, aliceAfterApproval.getLong("eligibleCoins"))
    assertEquals(100L, aliceAfterApproval.getLong("balanceCents"))
    assertEquals(1L, aliceAfterApproval.getLong("completedTasksCount"))

    val ledgerTx =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("transactions").document("task_reward_$subId").get().await()
      }
    assertTrue("Deterministic task_reward_<submissionId> ledger tx must exist", ledgerTx.exists())
    assertEquals(125L, ledgerTx.getLong("amountCoins"))
    assertEquals(100L, ledgerTx.getLong("amountCents"))
    assertEquals("CREDITED", ledgerTx.getString("status"))

    // Duplicate approval MUST fail and MUST NOT double-credit Alice
    val dupResult =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        repo.reviewSubmission(task = submittedTask, approve = true, adminFeedback = "Duplicate attempt")
      }
    assertTrue("Duplicate task approval must be blocked", dupResult.isFailure)
    val aliceAfterDup =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("users").document(aliceUid).get().await()
      }
    assertEquals(125L, aliceAfterDup.getLong("balanceCoins"))
    assertEquals(100L, aliceAfterDup.getLong("balanceCents"))
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 15_000L
  }
}

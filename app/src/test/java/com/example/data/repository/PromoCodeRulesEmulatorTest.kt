package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.annotation.Config

@Config(sdk = [33])
class PromoCodeRulesEmulatorTest : FirestoreEmulatorTestBase() {

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

  /**
   * Seeds server-owned documents directly into the Firestore Emulator using `Authorization: Bearer owner`
   * (simulating Admin SDK Cloud Functions) without bypassing client rules during test assertions.
   */
  private fun seedEmulatorDocument(documentPath: String, fieldsJson: JSONObject) {
    val cleanPath = documentPath.trimStart('/')
    val docName = "projects/demo-no-project/databases/$databaseId/documents/$cleanPath"
    val url =
      URL(
        "http://127.0.0.1:8085/v1/projects/demo-no-project/databases/$databaseId/documents:commit"
      )
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
      JSONObject()
        .put("update", JSONObject().put("name", docName).put("fields", fieldsJson))
    val body = JSONObject().put("writes", org.json.JSONArray().put(writeObj)).toString()
    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
    val status = conn.responseCode
    val errText = if (status !in 200..299) conn.errorStream?.bufferedReader()?.readText().orEmpty() else ""
    conn.disconnect()
    check(status in 200..299) {
      "Failed to seed emulator document '$cleanPath' (HTTP $status): $errText"
    }
  }

  private fun strField(value: String): JSONObject = JSONObject().put("stringValue", value)

  private fun intField(value: Long): JSONObject = JSONObject().put("integerValue", value.toString())

  private fun boolField(value: Boolean): JSONObject = JSONObject().put("booleanValue", value)

  private fun seedPromoAndRedemptions(
    promoId: String,
    isActive: Boolean,
    ownerUid: String,
    rewardCoins: Long = 250L
  ) {
    seedEmulatorDocument(
      "promo_codes/$promoId",
      JSONObject()
        .put("code", strField(promoId))
        .put("rewardCoins", intField(rewardCoins))
        .put("maxSlots", intField(100L))
        .put("usedSlots", intField(1L))
        .put("isActive", boolField(isActive))
        .put("status", strField(if (isActive) "ACTIVE" else "INACTIVE"))
        .put("expiresAtLabel", strField("2099-12-31 23:59"))
        .put("createdBy", strField("admin_seed"))
    )
    seedEmulatorDocument(
      "promo_codes/$promoId/redemptions/$ownerUid",
      JSONObject()
        .put("promoId", strField(promoId))
        .put("code", strField(promoId))
        .put("userId", strField(ownerUid))
        .put("userName", strField("Alice Approved"))
        .put("rewardCoins", intField(rewardCoins))
    )
    seedEmulatorDocument(
      "promo_redemptions/${promoId}_$ownerUid",
      JSONObject()
        .put("id", strField("${promoId}_$ownerUid"))
        .put("promoId", strField(promoId))
        .put("code", strField(promoId))
        .put("userId", strField(ownerUid))
        .put("userName", strField("Alice Approved"))
        .put("rewardCoins", intField(rewardCoins))
    )
  }

  @Test
  fun promoCodesAndWalletRules_enforceZeroTrustAndAdminReadIsolation() = runBlocking {
    val repo = TaskFlowRepository(firestore)

    // Bootstrap Admin + create and approve Alice & Bob
    val adminUid = signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(adminUid, BOOTSTRAPPED_ADMIN_EMAIL, "Admin").getOrThrow()
    }

    val suffix = UUID.randomUUID().toString().take(8)
    val aliceEmail = "alice_promo_rule_$suffix@test.com"
    val aliceUid = signInTestUser(aliceEmail, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(aliceUid, aliceEmail, "Alice Approved").getOrThrow()
    }

    val bobEmail = "bob_promo_rule_$suffix@test.com"
    val bobUid = signInTestUser(bobEmail, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.ensureUserProfile(bobUid, bobEmail, "Bob Approved").getOrThrow()
    }

    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    withTimeout(DEFAULT_TIMEOUT_MS) {
      repo.updateUserApprovalStatus(aliceUid, "approved").getOrThrow()
      repo.updateUserApprovalStatus(bobUid, "approved").getOrThrow()
    }

    val activePromoId = "PUBLIC_${suffix.uppercase()}"
    val privatePromoId = "PRIVATE_${suffix.uppercase()}"
    seedPromoAndRedemptions(activePromoId, isActive = true, ownerUid = aliceUid, rewardCoins = 250L)
    seedPromoAndRedemptions(privatePromoId, isActive = false, ownerUid = aliceUid, rewardCoins = 900L)

    // 1. Unauthenticated reads & writes denied
    auth.signOut()
    expectPermissionDenied("unauth list promo_codes") {
      firestore.collection("promo_codes").get().await()
    }
    expectPermissionDenied("unauth get promo_codes/$activePromoId") {
      firestore.collection("promo_codes").document(activePromoId).get().await()
    }
    expectPermissionDenied("unauth get promo_redemptions") {
      firestore.collection("promo_redemptions").document("${activePromoId}_$aliceUid").get().await()
    }
    expectPermissionDenied("unauth get nested redemption") {
      firestore
        .collection("promo_codes")
        .document(activePromoId)
        .collection("redemptions")
        .document(aliceUid)
        .get()
        .await()
    }
    expectPermissionDenied("unauth write promo_codes") {
      firestore
        .collection("promo_codes")
        .document("UNAUTH_CODE")
        .set(mapOf("code" to "UNAUTH_CODE", "rewardCoins" to 100L))
        .await()
    }

    // 2. Ordinary approved users cannot list or read active, inactive, or private promo_codes
    signInTestUser(aliceEmail, emailVerified = true)
    expectPermissionDenied("ordinary user list promo_codes") {
      firestore.collection("promo_codes").get().await()
    }
    expectPermissionDenied("ordinary user get active promo code") {
      firestore.collection("promo_codes").document(activePromoId).get().await()
    }
    expectPermissionDenied("ordinary user get private inactive promo code") {
      firestore.collection("promo_codes").document(privatePromoId).get().await()
    }

    // 3. Ordinary users cannot create, edit, or delete promo_codes
    expectPermissionDenied("ordinary user create promo_codes") {
      firestore
        .collection("promo_codes")
        .document("CLIENT_CREATED")
        .set(
          mapOf(
            "code" to "CLIENT_CREATED",
            "rewardCoins" to 5000L,
            "maxSlots" to 10L,
            "usedSlots" to 0L,
            "isActive" to true
          )
        )
        .await()
    }
    expectPermissionDenied("ordinary user update promo_codes") {
      firestore
        .collection("promo_codes")
        .document(activePromoId)
        .update(mapOf("usedSlots" to 2L, "rewardCoins" to 99999L))
        .await()
    }
    expectPermissionDenied("ordinary user delete promo_codes") {
      firestore.collection("promo_codes").document(activePromoId).delete().await()
    }

    // 4. Users cannot create, edit, or delete promo_redemptions or nested redemptions
    expectPermissionDenied("user create promo_redemptions") {
      firestore
        .collection("promo_redemptions")
        .document("${activePromoId}_forged_$aliceUid")
        .set(
          mapOf(
            "promoId" to activePromoId,
            "userId" to aliceUid,
            "rewardCoins" to 250L
          )
        )
        .await()
    }
    expectPermissionDenied("user update promo_redemptions") {
      firestore
        .collection("promo_redemptions")
        .document("${activePromoId}_$aliceUid")
        .update("rewardCoins", 9999L)
        .await()
    }
    expectPermissionDenied("user delete promo_redemptions") {
      firestore.collection("promo_redemptions").document("${activePromoId}_$aliceUid").delete().await()
    }
    expectPermissionDenied("user create nested redemption") {
      firestore
        .collection("promo_codes")
        .document(privatePromoId)
        .collection("redemptions")
        .document("${aliceUid}_forged")
        .set(
          mapOf(
            "promoId" to privatePromoId,
            "userId" to aliceUid,
            "rewardCoins" to 900L
          )
        )
        .await()
    }
    expectPermissionDenied("user delete nested redemption") {
      firestore
        .collection("promo_codes")
        .document(activePromoId)
        .collection("redemptions")
        .document(aliceUid)
        .delete()
        .await()
    }

    // 5. Approved users cannot directly credit or modify wallet balances on /users/{uid}
    //    or forge PROMO_CODE transactions / PROMO_CODE_REWARD notifications
    expectPermissionDenied("user direct promotionalCoins & balanceCoins inflation") {
      firestore
        .collection("users")
        .document(aliceUid)
        .update(
          mapOf(
            "promotionalCoins" to 50000L,
            "balanceCoins" to 50000L,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    expectPermissionDenied("user small +100 promotionalCoins direct self-credit") {
      firestore
        .collection("users")
        .document(aliceUid)
        .update(
          mapOf(
            "promotionalCoins" to 100L,
            "balanceCoins" to 100L,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    expectPermissionDenied("user direct balanceCents modification") {
      firestore
        .collection("users")
        .document(aliceUid)
        .update(
          mapOf(
            "balanceCents" to 5000L,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    expectPermissionDenied("user direct eligibleCoins modification") {
      firestore
        .collection("users")
        .document(aliceUid)
        .update(
          mapOf(
            "eligibleCoins" to 5000L,
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    expectPermissionDenied("user forged PROMO_CODE transaction") {
      firestore
        .collection("transactions")
        .document("promo_${activePromoId}_$aliceUid")
        .set(
          mapOf(
            "userId" to aliceUid,
            "userName" to "Alice Approved",
            "type" to "PROMO_CODE",
            "title" to "Forged Promo",
            "amountCents" to 250L,
            "amountCoins" to 250L,
            "status" to "CREDITED",
            "referenceId" to activePromoId,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    expectPermissionDenied("user forged PROMO_CODE_REWARD notification") {
      firestore
        .collection("users")
        .document(aliceUid)
        .collection("notifications")
        .document("forged_promo_notif")
        .set(
          mapOf(
            "userId" to aliceUid,
            "title" to "Promo Redeemed",
            "body" to "Fake +5000 Coins",
            "type" to "PROMO_CODE_REWARD",
            "referenceId" to activePromoId,
            "isRead" to false,
            "createdAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }

    // 6. Owner can read own redemption records; cross-user redemption reads are denied
    val aliceOwnRedemption =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("promo_redemptions").document("${activePromoId}_$aliceUid").get().await()
      }
    assertTrue("Owner should be able to read own promo_redemptions doc", aliceOwnRedemption.exists())

    val aliceNestedRedemption =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore
          .collection("promo_codes")
          .document(activePromoId)
          .collection("redemptions")
          .document(aliceUid)
          .get()
          .await()
      }
    assertTrue("Owner should be able to read own nested redemption doc", aliceNestedRedemption.exists())

    // Legitimate owner profile update (displayName) still succeeds
    withTimeout(DEFAULT_TIMEOUT_MS) {
      firestore
        .collection("users")
        .document(aliceUid)
        .update(
          mapOf(
            "displayName" to "Alice Renamed",
            "updatedAt" to FieldValue.serverTimestamp()
          )
        )
        .await()
    }
    val updatedAlice =
      withTimeout(DEFAULT_TIMEOUT_MS) { repo.getUserProfile(aliceUid).getOrThrow()!! }
    assertEquals("Alice Renamed", updatedAlice.displayName)
    assertEquals(0L, updatedAlice.promotionalCoins)

    // Cross-user checks as Bob
    signInTestUser(bobEmail, emailVerified = true)
    expectPermissionDenied("cross-user get promo_redemptions") {
      firestore.collection("promo_redemptions").document("${activePromoId}_$aliceUid").get().await()
    }
    expectPermissionDenied("cross-user list all promo_redemptions") {
      firestore.collection("promo_redemptions").get().await()
    }
    expectPermissionDenied("cross-user get nested redemption") {
      firestore
        .collection("promo_codes")
        .document(activePromoId)
        .collection("redemptions")
        .document(aliceUid)
        .get()
        .await()
    }
    expectPermissionDenied("cross-user list nested redemptions") {
      firestore.collection("promo_codes").document(activePromoId).collection("redemptions").get().await()
    }

    // 7. Existing Scratch Card security rules remain locked down
    expectPermissionDenied("client write scratch_sessions") {
      firestore
        .collection("users")
        .document(bobUid)
        .collection("scratch_sessions")
        .document("fake_scratch")
        .set(mapOf("rewardCoins" to 500L, "status" to "ACTIVE"))
        .await()
    }
    expectPermissionDenied("client write scratch_stats") {
      firestore
        .collection("users")
        .document(bobUid)
        .collection("scratch_stats")
        .document("2026-11-14")
        .set(mapOf("unlockedScratchesCount" to 99L))
        .await()
    }

    // 8. Authorized admin reads work for promo_codes, promo_redemptions, and nested redemptions,
    //    while direct client writes on promo_codes remain denied even for admin
    signInTestUser(BOOTSTRAPPED_ADMIN_EMAIL, emailVerified = true)
    val adminPromoSnap =
      withTimeout(DEFAULT_TIMEOUT_MS) { repo.getPromoCodes().getOrThrow() }
    assertTrue("Admin should read active promo code", adminPromoSnap.any { it.code == activePromoId })
    assertTrue("Admin should read private promo code", adminPromoSnap.any { it.code == privatePromoId })

    val adminRedemptionSnap =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("promo_redemptions").document("${activePromoId}_$aliceUid").get().await()
      }
    assertTrue("Admin should read promo_redemptions doc", adminRedemptionSnap.exists())

    val adminNestedList =
      withTimeout(DEFAULT_TIMEOUT_MS) {
        firestore.collection("promo_codes").document(activePromoId).collection("redemptions").get().await()
      }
    assertFalse("Admin should list nested redemptions", adminNestedList.isEmpty)

    expectPermissionDenied("admin direct client write promo_codes") {
      firestore.collection("promo_codes").document(activePromoId).update("rewardCoins", 999L).await()
    }
  }

  private companion object {
    const val DEFAULT_TIMEOUT_MS = 10000L
  }
}

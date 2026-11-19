package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.PromoAdminUiFeedback
import com.example.data.model.PromoCodeItem
import com.example.data.model.PromoCodeStatus
import com.example.data.model.PromoRedemptionUiState
import com.example.data.repository.FirebaseRewardCloudFunctionsClient
import com.example.data.repository.TaskFlowRepository
import com.example.ui.admin.AdminPromoCodeManagerCard
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.PromoCodeRedemptionScreen
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PromoCodeCloudMigrationTest {

  @get:Rule val composeTestRule = createComposeRule()

  private fun createTestFirestore(): FirebaseFirestore {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ensureFirebaseInitialized(context)
    return FirebaseFirestore.getInstance()
  }

  @Test
  fun createPromoCode_callsCreatePromoCodeCallable_withoutClientAuthoritativeStatusOrUsedSlots() =
    runBlocking {
      val firestore = createTestFirestore()
      val recordedCalls = mutableListOf<Pair<String, Map<*, *>>>()
      val functionsClient =
        FirebaseRewardCloudFunctionsClient(
          firestore = firestore,
          promoCallableInvoker = { fnName, rawPayload ->
            val map = rawPayload as Map<*, *>
            recordedCalls.add(fnName to map)
            mapOf(
              "success" to true,
              "id" to "SAVE500",
              "code" to "SAVE500",
              "rewardCoins" to 500L,
              "maxSlots" to 25L,
              "usedSlots" to 0L,
              "expiresAtMillis" to 1_900_000_000_000L,
              "expiresAtLabel" to "2030-03-18 12:00",
              "isActive" to true,
              "status" to "ACTIVE",
              "createdBy" to "admin_uid_1"
            )
          }
        )
      val repository =
        TaskFlowRepository(
          firestore = firestore,
          rewardFunctionsClient = functionsClient,
          authenticatedUidProvider = { "admin_uid_1" }
        )

      val result =
        repository.createPromoCode(
          rawCode = " save500 ",
          rewardCoins = 500L,
          maxSlots = 25L,
          expiresAtMillis = 0L,
          expiresAtLabel = "2030-03-18 12:00",
          isActive = true
        )

      assertTrue("Expected createPromoCode to succeed", result.isSuccess)
      assertEquals(1, recordedCalls.size)
      val (calledFunction, sentPayload) = recordedCalls.single()
      assertEquals("createPromoCode", calledFunction)
      assertEquals("SAVE500", sentPayload["code"])
      assertEquals(500L, sentPayload["rewardCoins"])
      assertEquals(25L, sentPayload["maxSlots"])
      assertEquals("2030-03-18 12:00", sentPayload["expiresAtDateTime"])
      assertEquals(true, sentPayload["isActive"])
      // Verify client does not send server-authoritative tracking fields
      assertFalse(sentPayload.containsKey("usedSlots"))
      assertFalse(sentPayload.containsKey("status"))
      assertFalse(sentPayload.containsKey("createdBy"))
      assertFalse(sentPayload.containsKey("balanceCoins"))
      assertFalse(sentPayload.containsKey("promotionalCoins"))

      val created = result.getOrThrow()
      assertEquals("SAVE500", created.code)
      assertEquals(500L, created.rewardCoins)
      assertEquals(25L, created.maxSlots)
      assertEquals(0L, created.usedSlots)
      assertEquals("ACTIVE", created.status)
      assertEquals("admin_uid_1", created.createdBy)
    }

  @Test
  fun setPromoCodeActiveStatus_callsServerCallable_andParsesUpdatedStatus() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Map<*, *>>>()
    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        promoCallableInvoker = { fnName, rawPayload ->
          val map = rawPayload as Map<*, *>
          recordedCalls.add(fnName to map)
          mapOf(
            "success" to true,
            "promoId" to "SAVE500",
            "isActive" to false,
            "status" to "INACTIVE"
          )
        }
      )
    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = functionsClient,
        authenticatedUidProvider = { "admin_uid_1" }
      )

    val result = repository.setPromoCodeActiveStatus(promoId = " save500 ", isActive = false)

    assertTrue(result.isSuccess)
    assertEquals(1, recordedCalls.size)
    val (calledFunction, sentPayload) = recordedCalls.single()
    assertEquals("setPromoCodeActiveStatus", calledFunction)
    assertEquals(setOf("promoId", "isActive"), sentPayload.keys)
    assertEquals("SAVE500", sentPayload["promoId"])
    assertEquals(false, sentPayload["isActive"])

    val updated = result.getOrThrow()
    assertEquals("SAVE500", updated.promoId)
    assertFalse(updated.isActive)
    assertEquals("INACTIVE", updated.status)
  }

  @Test
  fun redeemPromoCode_callsRedeemPromoCodeCallable_sendingOnlyNormalizedCode() = runBlocking {
    val firestore = createTestFirestore()
    val recordedCalls = mutableListOf<Pair<String, Map<*, *>>>()
    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        promoCallableInvoker = { fnName, rawPayload ->
          val map = rawPayload as Map<*, *>
          recordedCalls.add(fnName to map)
          mapOf(
            "success" to true,
            "id" to "WELCOME250_user_1",
            "promoId" to "WELCOME250",
            "code" to "WELCOME250",
            "userId" to "user_1",
            "userName" to "Member One",
            "rewardCoins" to 250L,
            "newPromotionalCoins" to 750L,
            "newBalanceCoins" to 1250L
          )
        }
      )
    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = functionsClient,
        authenticatedUidProvider = { "user_1" }
      )

    val result = repository.redeemPromoCode("  welcome250 ")

    assertTrue(result.isSuccess)
    assertEquals(1, recordedCalls.size)
    val (calledFunction, sentPayload) = recordedCalls.single()
    assertEquals("redeemPromoCode", calledFunction)
    // Strictly ONLY 'code' may be sent from client
    assertEquals(setOf("code"), sentPayload.keys)
    assertEquals("WELCOME250", sentPayload["code"])

    val record = result.getOrThrow()
    assertEquals("WELCOME250_user_1", record.id)
    assertEquals("WELCOME250", record.promoId)
    assertEquals("WELCOME250", record.code)
    assertEquals("user_1", record.userId)
    assertEquals("Member One", record.userName)
    assertEquals(250L, record.rewardCoins)
    assertEquals(750L, record.newPromotionalCoins)
    assertEquals(1250L, record.newBalanceCoins)
  }

  @Test
  fun createPromoCode_rejectsCodesLongerThan32Characters_beforeNetworkCall() = runBlocking {
    val firestore = createTestFirestore()
    var invoked = false
    val functionsClient =
      FirebaseRewardCloudFunctionsClient(
        firestore = firestore,
        promoCallableInvoker = { _, _ ->
          invoked = true
          emptyMap<String, Any>()
        }
      )
    val repository =
      TaskFlowRepository(
        firestore = firestore,
        rewardFunctionsClient = functionsClient,
        authenticatedUidProvider = { "admin_uid_1" }
      )

    val result =
      repository.createPromoCode(
        rawCode = "THIS_PROMO_CODE_IS_WAY_OVER_32_CHARS",
        rewardCoins = 100L,
        maxSlots = 10L,
        expiresAtLabel = "2030-05-01 12:00"
      )

    assertTrue(result.isFailure)
    assertFalse("Oversized promo code must be rejected before callable invocation", invoked)
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_redeemPromoCode_blocksDuplicateRequestsWhileInFlight_andShowsServerMessage() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(testDispatcher)
      try {
        val firestore = createTestFirestore()
        val gate = CompletableDeferred<Unit>()
        var serverCalls = 0
        val functionsClient =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            promoCallableInvoker = { _, _ ->
              serverCalls++
              gate.await()
              mapOf(
                "success" to true,
                "id" to "BONUS100_u1",
                "promoId" to "BONUS100",
                "code" to "BONUS100",
                "userId" to "u1",
                "userName" to "User One",
                "rewardCoins" to 100L,
                "newPromotionalCoins" to 100L,
                "newBalanceCoins" to 100L
              )
            }
          )
        val repo =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = functionsClient,
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

        vm.redeemPromoCode("bonus100")
        assertTrue(vm.promoRedemptionUiState.value.isRedeeming)

        // Second rapid tap while first request is still awaiting server must be ignored
        vm.redeemPromoCode("bonus100")

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, serverCalls)
        assertFalse(vm.promoRedemptionUiState.value.isRedeeming)
        assertEquals("BONUS100", vm.promoRedemptionUiState.value.lastRedeemedCode)
        assertEquals(100L, vm.promoRedemptionUiState.value.lastCreditedCoins)
        assertTrue(
          vm.promoRedemptionUiState.value.successMessage.orEmpty().contains("+100 TF")
        )
        assertNull(vm.promoRedemptionUiState.value.errorMessage)
      } finally {
        Dispatchers.resetMain()
      }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun viewModel_adminCreateAndTogglePromoCode_blockConcurrentDuplicates_andPropagateServerErrors() =
    runTest {
      val testDispatcher = StandardTestDispatcher(testScheduler)
      Dispatchers.setMain(testDispatcher)
      try {
        val firestore = createTestFirestore()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var shouldFail = false
        val functionsClient =
          FirebaseRewardCloudFunctionsClient(
            firestore = firestore,
            promoCallableInvoker = { fnName, _ ->
              calls++
              gate.await()
              if (shouldFail) {
                throw IllegalStateException("Promo code already exists on server.")
              }
              if (fnName == "createPromoCode") {
                mapOf(
                  "success" to true,
                  "id" to "FEST150",
                  "code" to "FEST150",
                  "rewardCoins" to 150L,
                  "maxSlots" to 10L,
                  "usedSlots" to 0L,
                  "expiresAtMillis" to 1_900_000_000_000L,
                  "expiresAtLabel" to "2030-01-01 12:00",
                  "isActive" to true,
                  "status" to "ACTIVE",
                  "createdBy" to "admin_1"
                )
              } else {
                mapOf(
                  "success" to true,
                  "promoId" to "FEST150",
                  "isActive" to false,
                  "status" to "INACTIVE"
                )
              }
            }
          )
        val repo =
          TaskFlowRepository(
            firestore = firestore,
            rewardFunctionsClient = functionsClient,
            authenticatedUidProvider = { "admin_1" }
          )
        val vm =
          TaskFlowViewModel(
            repository = repo,
            currentUserId = "admin_1",
            currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
            currentUserDisplayName = "Primary Admin",
            initialPortal = PortalLoginTarget.ADMIN_DASHBOARD
          )

        vm.adminCreatePromoCode(
          rawCode = "fest150",
          rewardCoins = 150L,
          maxSlots = 10L,
          expiresAtMillis = 1_900_000_000_000L,
          expiresAtLabel = "2030-01-01 12:00",
          isActive = true
        )
        assertTrue(vm.promoAdminFeedback.value.isLoading)

        // Duplicate clicks while loading are blocked
        vm.adminCreatePromoCode(
          rawCode = "fest150",
          rewardCoins = 150L,
          maxSlots = 10L,
          expiresAtMillis = 1_900_000_000_000L,
          expiresAtLabel = "2030-01-01 12:00",
          isActive = true
        )
        vm.adminTogglePromoCodeActive("FEST150", false)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, calls)
        assertFalse(vm.promoAdminFeedback.value.isLoading)
        assertEquals("FEST150", vm.promoAdminFeedback.value.lastCreatedCode)
        assertNotNull(vm.promoAdminFeedback.value.successMessage)

        // Now verify server error propagation stops loading state cleanly
        shouldFail = true
        vm.adminTogglePromoCodeActive("FEST150", false)
        advanceUntilIdle()
        assertFalse(vm.promoAdminFeedback.value.isLoading)
        assertTrue(
          vm.promoAdminFeedback.value.errorMessage.orEmpty()
            .contains("Promo code already exists on server.")
        )
      } finally {
        Dispatchers.resetMain()
      }
    }

  @Test
  fun promoCodeRedemptionScreen_disablesInputAndButtonWhileRedeeming() {
    var clicks = 0
    composeTestRule.setContent {
      TaskFlowTheme {
        PromoCodeRedemptionScreen(
          uiState = PromoRedemptionUiState(codeInput = "SAVE100", isRedeeming = true),
          onRedeemCode = { clicks++ },
          onBack = {}
        )
      }
    }

    composeTestRule.onNodeWithTag("promo_code_input_field").assertIsNotEnabled()
    composeTestRule.onNodeWithTag("promo_code_redeem_button").assertIsNotEnabled()
    composeTestRule.onNodeWithText("Redeeming...").assertIsDisplayed()
    assertEquals(0, clicks)
  }

  @Test
  fun adminPromoCodeManagerCard_rejectsInvalidDatesWithoutFallback_andDisablesExpiredOrBusyControls() {
    var createCount = 0
    val expiredPromo =
      PromoCodeItem(
        id = "OLD50",
        code = "OLD50",
        rewardCoins = 50L,
        maxSlots = 10L,
        usedSlots = 2L,
        expiresAtMillis = 1_000L,
        expiresAtLabel = "2020-01-01 10:00",
        isActive = false,
        status = PromoCodeStatus.EXPIRED
      )
    val activePromo =
      PromoCodeItem(
        id = "LIVE50",
        code = "LIVE50",
        rewardCoins = 50L,
        maxSlots = 10L,
        usedSlots = 2L,
        expiresAtMillis = System.currentTimeMillis() + 86_400_000L,
        expiresAtLabel = "2030-01-01 10:00",
        isActive = true,
        status = PromoCodeStatus.ACTIVE
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          AdminPromoCodeManagerCard(
            promoCodes = listOf(expiredPromo, activePromo),
            isBusy = false,
            promoAdminFeedback = PromoAdminUiFeedback(),
            onCreatePromoCode = { _, _, _, _, _, _ -> createCount++ },
            onTogglePromoCodeActive = { _, _ -> }
          )
        }
      }
    }

    // Expired promo toggle button must be disabled; active promo toggle button enabled
    composeTestRule.onNodeWithTag("admin_promo_toggle_OLD50").performScrollTo().assertIsNotEnabled()
    composeTestRule.onNodeWithTag("admin_promo_toggle_LIVE50").performScrollTo().assertIsEnabled()

    // Invalid calendar date (Feb 31) must show validation error and NOT fall back to +24h
    composeTestRule.onNodeWithTag("admin_promo_code_text_input").performScrollTo().performTextInput("VALID100")
    composeTestRule.onNodeWithTag("admin_promo_reward_coins_input").performScrollTo().performTextInput("100")
    composeTestRule.onNodeWithTag("admin_promo_max_slots_input").performScrollTo().performTextInput("10")
    composeTestRule.onNodeWithTag("admin_promo_expiry_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("admin_promo_expiry_input").performTextInput("2026-02-31 12:00")
    composeTestRule.onNodeWithTag("admin_promo_create_button").performScrollTo().performClick()

    composeTestRule.onNodeWithTag("admin_promo_validation_error").performScrollTo().assertIsDisplayed()
    assertEquals(0, createCount)
  }
}

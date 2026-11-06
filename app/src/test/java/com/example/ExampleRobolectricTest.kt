package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.AppVersionInfo
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.data.model.calculateDailyCheckInState
import com.example.data.model.getTodayDateKey
import com.example.data.model.getYesterdayDateKey
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.TaskFlowSplashScreen
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserProfileScreen
import com.example.ui.user.UserTaskDetailScreen
import com.example.ui.user.UserTrackingScreen
import com.example.ui.user.UserWalletScreen
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.UiState
import android.os.Bundle
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialResponse
import com.example.data.repository.TaskFlowRepository
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.auth.VERIFIED_TASKFLOW_WEB_CLIENT_ID
import com.example.ui.auth.extractGoogleIdToken
import com.example.ui.auth.handleSignInResult
import com.example.ui.auth.resolveWebClientId
import com.example.ui.viewmodel.TaskFlowViewModel
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `verify app strings and firebase oauth resources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("TaskFlow", context.getString(R.string.app_name))
    assertEquals("Complete Tasks. Track Progress.", context.getString(R.string.splash_tagline))
    assertEquals("Welcome to TaskFlow", context.getString(R.string.welcome_to_taskflow))
    assertEquals("Sign in to continue", context.getString(R.string.sign_in_to_continue))
    assertEquals("Continue with Google", context.getString(R.string.sign_in_with_google))
    assertEquals(
      "ai-studio-android-taskflow-f47fe501-b47e-4e0e-8222-905e3d5397ff",
      context.getString(R.string.firestore_database_id)
    )
    assertTrue(context.getString(R.string.default_web_client_id).isNotEmpty())
    assertEquals(VERIFIED_TASKFLOW_WEB_CLIENT_ID, resolveWebClientId(context))
  }

  @Test
  fun `extractGoogleIdToken and handleSignInResult support both GoogleIdToken and SIWG credentials and always stop loading state`() =
    runBlocking {
      val tokenKey = "com.google.android.libraries.identity.googleid.BUNDLE_KEY_ID_TOKEN"

      // 1. Standard TYPE_GOOGLE_ID_TOKEN_CREDENTIAL bundle
      val standardBundle = Bundle().apply { putString(tokenKey, "sample.jwt.token.standard") }
      val standardCred = CustomCredential(TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, standardBundle)
      assertEquals("sample.jwt.token.standard", extractGoogleIdToken(standardCred))

      // 2. Sign-In With Google TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL bundle
      val siwgBundle = Bundle().apply { putString(tokenKey, "sample.jwt.token.siwg") }
      val siwgCred = CustomCredential(TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL, siwgBundle)
      assertEquals("sample.jwt.token.siwg", extractGoogleIdToken(siwgCred))

      // 3. Verify handleSignInResult never leaves isLoading stuck on true even when token is empty
      var isLoading = true
      var capturedError: String? = null
      val emptyCred = CustomCredential(TYPE_GOOGLE_ID_TOKEN_CREDENTIAL, Bundle())
      handleSignInResult(
        result = GetCredentialResponse(emptyCred),
        onLoadingChange = { isLoading = it },
        onError = { capturedError = it }
      )
      assertFalse("Expected isLoading to be reset to false", isLoading)
      assertNotNull("Expected clear error message for empty token", capturedError)
    }

  @Test
  fun `TaskFlowViewModel immediately routes Admin Gmail to Admin Dashboard and other Gmail accounts to User Dashboard without blocking`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ensureFirebaseInitialized(context)
    val repo = TaskFlowRepository(FirebaseFirestore.getInstance())

    // 1. Bootstrapped Admin Gmail (dasakash24059@gmail.com) -> Immediately opens Admin Dashboard
    val adminVm =
      TaskFlowViewModel(
        repository = repo,
        currentUserId = "admin_uid_1",
        currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        currentUserDisplayName = "Primary Admin",
        initialPortal = PortalLoginTarget.USER_APP
      )
    assertEquals(PortalLoginTarget.ADMIN_DASHBOARD, adminVm.activePortal.value)
    assertTrue(adminVm.isAdminAuthorized.value)

    // 2. Any other Gmail account -> Immediately opens User Dashboard
    val userVm =
      TaskFlowViewModel(
        repository = repo,
        currentUserId = "user_uid_2",
        currentUserEmail = "member@gmail.com",
        currentUserDisplayName = "TaskFlow Member",
        initialPortal = PortalLoginTarget.USER_APP
      )
    assertEquals(PortalLoginTarget.USER_APP, userVm.activePortal.value)
    assertFalse(userVm.isAdminAuthorized.value)
  }

  @Test
  fun `launch MainActivity does not crash and initializes Firebase`() {
    Robolectric.buildActivity(MainActivity::class.java).use { controller ->
      controller.setup()
      Shadows.shadowOf(Looper.getMainLooper()).idle()
      val context = ApplicationProvider.getApplicationContext<Context>()
      assertTrue(ensureFirebaseInitialized(context))
    }
  }

  @Test
  fun `TaskFlowSplashScreen displays only logo, app name, and tagline and triggers auth check`() {
    var splashCompleted = false
    composeTestRule.setContent {
      TaskFlowTheme {
        TaskFlowSplashScreen(
          durationMillis = 300L,
          onSplashFinished = { splashCompleted = true }
        )
      }
    }
    composeTestRule.onNodeWithTag("splash_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("splash_app_name").assertIsDisplayed()
    composeTestRule.onNodeWithText("TaskFlow").assertIsDisplayed()
    composeTestRule.onNodeWithTag("splash_tagline").assertIsDisplayed()
    composeTestRule.onNodeWithText("Complete Tasks. Track Progress.").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()

    composeTestRule.waitForIdle()
    assertTrue("Expected splash screen to complete and trigger auth check", splashCompleted)
  }

  @Test
  fun `AuthScreen displays clean Google Login screen with Welcome to TaskFlow, Sign in to continue, and Continue with Google button`() {
    composeTestRule.setContent {
      TaskFlowTheme {
        AuthScreen(onAuthSuccess = {})
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("google_login_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("login_app_name").assertIsDisplayed()
    composeTestRule.onNodeWithText("TaskFlow").assertIsDisplayed()
    composeTestRule.onNodeWithTag("login_welcome_heading").assertIsDisplayed()
    composeTestRule.onNodeWithText("Welcome to TaskFlow").assertIsDisplayed()
    composeTestRule.onNodeWithTag("login_subtitle").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sign in to continue").assertIsDisplayed()
    composeTestRule.onNodeWithTag("google_sign_in_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Continue with Google").assertIsDisplayed()
    // Ensure no unnecessary portal tabs or admin email are shown on login screen
    composeTestRule.onNodeWithTag("portal_user_tab").assertDoesNotExist()
    composeTestRule.onNodeWithTag("portal_admin_tab").assertDoesNotExist()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
  }

  @Test
  fun `UserHomeScreen displays welcome message, Available Balance in INR, Available Tasks button, compact task cards, and recent activity without any submission form`() {
    val playStoreUrl = "https://play.google.com/store/apps/details?id=com.example.app"
    val sampleProfile =
      UserProfile(
        id = "user_1",
        userId = "user_1",
        email = "worker@example.com",
        displayName = "Test Worker",
        role = "user",
        approvalStatus = "approved",
        balanceCents = 2500L,
        completedTasksCount = 2L,
        assignedTaskId = "task_101"
      )
    val sampleTask =
      TaskItem(
        id = "task_101",
        title = "Verify Checkout UI",
        appName = "TaskFlow Store",
        description = "Test the mobile checkout flow",
        instructions = "1. Open Play Store link\n2. Install & test checkout",
        requiredProof = "Screenshot of checkout confirmation",
        taskLink = playStoreUrl,
        category = "QA Testing",
        rewardCents = 1500L,
        assignedUserId = "user_1",
        assignedUserName = "Test Worker",
        assignedUserEmail = "worker@example.com",
        status = TaskStatus.PENDING,
        isActive = true
      )

    var navigatedFilter = ""
    var selectedTaskId = ""

    composeTestRule.setContent {
      TaskFlowTheme {
        UserHomeScreen(
          profileState = UiState.Success(sampleProfile),
          tasksState = UiState.Success(listOf(sampleTask)),
          isAdminAuthorized = false,
          onSelectTask = { selectedTaskId = it },
          onNavigateToTracking = { navigatedFilter = it },
          onSwitchToAdmin = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Welcome back,").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_welcome_message").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_available_balance", useUnmergedTree = true).assertTextEquals("₹25.00")
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_available_tasks_section"))
    composeTestRule.onNodeWithTag("home_available_tasks_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("browse_available_tasks_button").assertIsDisplayed().performClick()
    assertEquals(TaskStatus.PENDING, navigatedFilter)

    // Compact task card on Home shows View Task button and NO submission form
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("start_or_view_task_button_task_101"))
    composeTestRule.onNodeWithText("₹15.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("Verify Checkout UI").assertIsDisplayed()
    composeTestRule.onNodeWithTag("submit_proof_modal").assertDoesNotExist()
    composeTestRule.onNodeWithTag("task_proof_input").assertDoesNotExist()
    composeTestRule
      .onNodeWithTag("start_or_view_task_button_task_101")
      .assertIsDisplayed()
      .performClick()
    assertEquals("task_101", selectedTaskId)

    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_recent_activity_section"))
    composeTestRule
      .onNodeWithTag("home_recent_activity_section")
      .assertIsDisplayed()
  }

  @Test
  fun `UserTrackingScreen displays active tasks as compact cards with View Task button and excludes deactivated tasks`() {
    val activeTask =
      TaskItem(
        id = "task_active_1",
        title = "Install Active App",
        appName = "Active Companion",
        description = "Full description that should not clutter the compact card",
        category = "App Install",
        rewardCents = 4000L,
        status = TaskStatus.PENDING,
        isActive = true
      )
    val deactivatedTask =
      TaskItem(
        id = "task_deactivated_2",
        title = "Paused Campaign Task",
        appName = "Paused App",
        description = "Deactivated by admin",
        category = "Website Visit",
        rewardCents = 2000L,
        status = TaskStatus.PENDING,
        isActive = false
      )

    var openedTaskId = ""
    composeTestRule.setContent {
      TaskFlowTheme {
        UserTrackingScreen(
          tasksState = UiState.Success(listOf(activeTask, deactivatedTask)),
          selectedFilter = "all",
          onSelectFilter = {},
          onSelectTask = { openedTaskId = it },
          currentUserId = "user_1"
        )
      }
    }
    composeTestRule.waitForIdle()

    // Active task is shown as a compact card with "View Task" button
    composeTestRule.onNodeWithTag("available_tasks_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_card_task_active_1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Install Active App").assertIsDisplayed()
    composeTestRule.onNodeWithText("Active Companion").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹40.00").assertIsDisplayed()
    composeTestRule.onNodeWithTag("start_or_view_task_button_task_active_1").assertIsDisplayed().performClick()
    assertEquals("task_active_1", openedTaskId)

    // Deactivated pending task is excluded from Available Tasks list
    composeTestRule.onNodeWithTag("task_card_task_deactivated_2").assertDoesNotExist()
    // Full description and submission form are NOT shown on the task list
    composeTestRule.onNodeWithText("Full description that should not clutter the compact card").assertDoesNotExist()
    composeTestRule.onNodeWithTag("submit_proof_modal").assertDoesNotExist()
  }

  @Test
  fun `UserTaskDetailScreen does NOT auto-open submission form, opens SubmitProofModalDialog only when Submit Proof is tapped, and enforces mandatory text AND photo proof`() {
    val playStoreUrl = "https://play.google.com/store/apps/details?id=com.example.app"
    val sampleTask =
      TaskItem(
        id = "task_202",
        title = "Install & Review App",
        appName = "TaskFlow Companion",
        appIconUrl = "https://example.com/icon.png",
        description = "Download our app and verify onboarding",
        instructions = "Step 1: Tap the link\nStep 2: Screenshot the welcome screen",
        requiredProof = "Provide your test username and welcome screen screenshot",
        taskLink = playStoreUrl,
        category = "App Install",
        rewardCents = 5000L,
        assignedUserId = "admin_1",
        assignedUserName = "Primary Admin",
        assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        status = TaskStatus.PENDING,
        isActive = true
      )
    val fakePhotoUri = Uri.parse("content://media/picker/0/photo_proof_1.jpg")
    var submittedText: String? = null
    var submittedUri: Uri? = null

    composeTestRule.setContent {
      TaskFlowTheme {
        UserTaskDetailScreen(
          task = sampleTask,
          isBusy = false,
          onBack = {},
          onSubmitProof = { text, uri ->
            submittedText = text
            submittedUri = uri
          },
          userApprovalStatus = "pending",
          currentUserId = "user_2",
          initialSelectedImageUri = fakePhotoUri
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Verify all dedicated task detail sections are displayed
    composeTestRule.onNodeWithTag("task_detail_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_app_info_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_app_icon").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_reward_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_description").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_instructions").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_required_proof_section").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_clickable_link").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("start_task_open_link_button").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("open_submit_proof_button").performScrollTo().assertIsDisplayed()

    // 2. Verify submission form modal is NOT automatically opened on Task Details screen
    composeTestRule.onNodeWithTag("submit_proof_modal").assertDoesNotExist()
    composeTestRule.onNodeWithTag("task_proof_input").assertDoesNotExist()

    // 3. Tap "Start Task / Open Link" -> launches ACTION_VIEW Intent
    composeTestRule.onNodeWithTag("start_task_open_link_button").performScrollTo().performClick()
    val context = ApplicationProvider.getApplicationContext<Context>()
    val nextStartedActivity = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
    assertNotNull("Expected ACTION_VIEW Intent when tapping Start Task / Open Link", nextStartedActivity)
    assertEquals(Intent.ACTION_VIEW, nextStartedActivity.action)
    assertEquals(Uri.parse(playStoreUrl), nextStartedActivity.data)

    // 4. Tap "Submit Proof" -> opens the clean SubmitProofModalDialog
    composeTestRule.onNodeWithTag("open_submit_proof_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("submit_proof_modal").assertIsDisplayed()

    // Case 1: Photo is selected (initialSelectedImageUri), but written text proof is empty -> Submit button MUST be disabled
    composeTestRule.onNodeWithTag("submit_proof_button").assertIsNotEnabled()

    // Case 2: Enter written text proof while photo is selected -> Submit button MUST become enabled
    composeTestRule
      .onNodeWithTag("task_proof_input")
      .performTextInput("Completed app installation and verified welcome screen.")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("submit_proof_button").assertIsEnabled()

    // Case 3: Remove the selected photo while text proof is present (text-only) -> Submit button MUST become disabled
    composeTestRule.onNodeWithTag("remove_photo_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("submit_proof_button").assertIsNotEnabled()
    assertEquals(null, submittedText)
    assertEquals(null, submittedUri)
  }

  @Test
  fun `UserTaskDetailScreen submits when Submit Proof button is tapped and both written text and gallery photo are provided`() {
    val sampleTask =
      TaskItem(
        id = "task_203",
        title = "Complete Survey & Screenshot",
        appName = "Survey Hub",
        description = "Finish survey form",
        instructions = "1. Open link\n2. Submit screenshot",
        requiredProof = "Confirmation code and screenshot",
        taskLink = "https://example.com/survey",
        category = "UX Research",
        rewardCents = 3000L,
        assignedUserId = "user_1",
        assignedUserName = "Test Worker",
        assignedUserEmail = "worker@example.com",
        status = TaskStatus.PENDING,
        isActive = true
      )
    val fakePhotoUri = Uri.parse("content://media/picker/0/survey_screenshot.jpg")
    var submittedText: String? = null
    var submittedUri: Uri? = null

    composeTestRule.setContent {
      TaskFlowTheme {
        UserTaskDetailScreen(
          task = sampleTask,
          isBusy = false,
          onBack = {},
          onSubmitProof = { text, uri ->
            submittedText = text
            submittedUri = uri
          },
          userApprovalStatus = "approved",
          currentUserId = "user_1",
          initialSelectedImageUri = fakePhotoUri
        )
      }
    }
    composeTestRule.waitForIdle()

    // Tap "Submit Proof" button to open the submission modal
    composeTestRule.onNodeWithTag("open_submit_proof_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("task_proof_input")
      .performTextInput("Survey completed with confirmation code #9821.")
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("submit_proof_button")
      .assertIsEnabled()
      .performClick()
    composeTestRule.waitForIdle()

    assertEquals("Survey completed with confirmation code #9821.", submittedText)
    assertEquals(fakePhotoUri, submittedUri)
  }

  @Test
  fun `AdminCreateTask form validates HTTPS URL and creates task with all 9 fields`() {
    val workerUser =
      UserProfile(
        id = "user_1",
        userId = "user_1",
        email = "worker@example.com",
        displayName = "Test Worker",
        role = "user",
        approvalStatus = "approved"
      )
    var createdTitle = ""
    var createdAppName = ""
    var createdLink = ""
    var createdInstructions = ""
    var createdRequiredProof = ""
    var createdReward = ""

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.TASKS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(listOf(workerUser)),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {},
          onCreateTaskExtended = {
              title,
              _,
              _,
              rewardStr,
              _,
              instructions,
              taskLink,
              appName,
              _,
              requiredProof ->
            createdTitle = title
            createdReward = rewardStr
            createdInstructions = instructions
            createdLink = taskLink
            createdAppName = appName
            createdRequiredProof = requiredProof
          }
        )
      }
    }
    composeTestRule.waitForIdle()

    // Open Create Task Dialog
    composeTestRule.onNodeWithTag("admin_create_task_fab").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("task_modal_title_input").performTextInput("Play Store Review Task")
    composeTestRule.onNodeWithTag("task_modal_app_name_input").performTextInput("TaskFlow Mobile")
    composeTestRule
      .onNodeWithTag("task_modal_desc_input")
      .performScrollTo()
      .performTextInput("Install app and test login")
    composeTestRule
      .onNodeWithTag("task_modal_instructions_input")
      .performScrollTo()
      .performTextInput("1. Tap Play Store link\n2. Take screenshot of home screen")
    composeTestRule
      .onNodeWithTag("task_modal_required_proof_input")
      .performScrollTo()
      .performTextClearance()
    composeTestRule
      .onNodeWithTag("task_modal_required_proof_input")
      .performScrollTo()
      .performTextInput("Username and home screen screenshot")

    // Enter invalid HTTP URL -> Save button must be disabled
    composeTestRule
      .onNodeWithTag("task_modal_link_input")
      .performScrollTo()
      .performTextInput("http://invalid-link.com")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("task_modal_save_button").assertIsNotEnabled()

    // Replace with valid HTTPS Google Play Store URL -> Save button becomes enabled
    composeTestRule.onNodeWithTag("task_modal_link_input").performTextClearance()
    composeTestRule
      .onNodeWithTag("task_modal_link_input")
      .performTextInput("https://play.google.com/store/apps/details?id=com.example.app")
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("task_modal_save_button").assertIsEnabled().performClick()
    composeTestRule.waitForIdle()

    assertEquals("Play Store Review Task", createdTitle)
    assertEquals("TaskFlow Mobile", createdAppName)
    assertEquals("https://play.google.com/store/apps/details?id=com.example.app", createdLink)
    assertEquals("1. Tap Play Store link\n2. Take screenshot of home screen", createdInstructions)
    assertEquals("Username and home screen screenshot", createdRequiredProof)
    assertEquals("15.00", createdReward)
  }

  @Test
  fun `AdminTasksTab allows Admin to Deactivate, Reactivate, and Delete tasks with Confirmation Dialog`() {
    val activeTask =
      TaskItem(
        id = "task_manage_1",
        title = "Manageable Task",
        appName = "TaskFlow App",
        description = "Task with activate/deactivate and delete confirmation",
        category = "App Install",
        rewardCents = 2500L,
        assignedUserId = "admin_1",
        assignedUserName = "Admin",
        status = TaskStatus.PENDING,
        isActive = true
      )

    var toggledTaskId = ""
    var toggledActiveState: Boolean? = null
    var deletedTaskId = ""

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.TASKS,
          adminTasksState = UiState.Success(listOf(activeTask)),
          registeredUsersState = UiState.Success(emptyList()),
          submissionFilter = TaskStatus.SUBMITTED,
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = { deletedTaskId = it },
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {},
          onToggleTaskActive = { task, newActive ->
            toggledTaskId = task.id
            toggledActiveState = newActive
          }
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Verify Active badge and Deactivate button
    composeTestRule.onNodeWithTag("task_active_badge_task_manage_1").assertIsDisplayed()
    composeTestRule.onNodeWithText("Active").assertIsDisplayed()
    composeTestRule.onNodeWithTag("toggle_task_active_task_manage_1").performScrollTo().performClick()
    assertEquals("task_manage_1", toggledTaskId)
    assertEquals(false, toggledActiveState)

    // 2. Tap Delete button -> opens Delete Confirmation Dialog before deleting
    composeTestRule.onNodeWithTag("delete_task_task_manage_1").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("delete_task_confirm_dialog").assertIsDisplayed()
    assertEquals("", deletedTaskId)

    // Confirm deletion -> triggers onDeleteTask
    composeTestRule.onNodeWithTag("confirm_delete_task_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals("task_manage_1", deletedTaskId)
  }

  @Test
  fun `AdminSubmissionsTab displays submission from adminSubmissionsState with written proof, uploaded image, full-size inspection, User UID, Task ID, and approves`() {
    val submissionRecord =
      SubmissionItem(
        id = "task_review_501",
        taskId = "task_review_501",
        taskTitle = "Verify Payment Gateway",
        appName = "PayFlow Checkout",
        rewardCents = 4500L,
        userId = "worker_uid_99",
        userName = "Aarav Sharma",
        userEmail = "aarav@example.com",
        proofText = "Verified checkout screen and attached screenshot.",
        proofImageUrl = "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/proof.jpg?alt=media",
        proofStoragePath = "submissions/worker_uid_99/task_review_501/proof_1.jpg",
        status = TaskStatus.SUBMITTED
      )

    var reviewedTask: TaskItem? = null
    var reviewedApprove: Boolean? = null
    var reviewedNote: String? = null

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.SUBMISSIONS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(emptyList()),
          submissionFilter = TaskStatus.SUBMITTED,
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { task, approve, note ->
            reviewedTask = task
            reviewedApprove = approve
            reviewedNote = note
          },
          onToggleUserStatus = {},
          adminSubmissionsState = UiState.Success(listOf(submissionRecord))
        )
      }
    }
    composeTestRule.waitForIdle()

    // Verify User Name, User UID, Task ID, Written Proof, and Uploaded Image preview are visible
    composeTestRule
      .onNodeWithTag("review_user_name_task_review_501")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("review_user_uid_task_review_501")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("review_task_id_task_review_501")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("review_proof_text_task_review_501")
      .performScrollTo()
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("review_proof_image_task_review_501")
      .performScrollTo()
      .assertIsDisplayed()

    // Open Full-Size Image Dialog for inspection
    composeTestRule
      .onNodeWithTag("view_full_image_task_review_501")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("full_size_image_dialog_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag("close_full_image_dialog_button").performClick()
    composeTestRule.waitForIdle()

    // Enter optional review note and Approve submission
    composeTestRule
      .onNodeWithTag("admin_feedback_input_task_review_501")
      .performScrollTo()
      .performTextInput("Screenshot verified and approved.")
    composeTestRule
      .onNodeWithTag("approve_submission_task_review_501")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    assertEquals("task_review_501", reviewedTask?.id)
    assertEquals(true, reviewedApprove)
    assertEquals("Screenshot verified and approved.", reviewedNote)
  }

  @Test
  fun `AdminDashboardScreen blocks unauthorized user and allows authorized admin`() {
    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = false,
          currentUserId = "user_2",
          currentUserEmail = "regular@example.com",
          currentUserDisplayName = "Regular User",
          activeTab = AdminTab.TASKS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(emptyList()),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Admin Access Restricted").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
  }

  @Test
  fun `AdminDashboardScreen renders 4 statistics cards and hides admin email`() {
    val adminUser =
      UserProfile(
        id = "admin_1",
        userId = "admin_1",
        email = BOOTSTRAPPED_ADMIN_EMAIL,
        displayName = "Primary Admin",
        role = "admin",
        approvalStatus = "approved"
      )
    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.USERS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(listOf(adminUser)),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_metric_total_tasks").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_metric_needs_review").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_metric_approved").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_metric_users").assertIsDisplayed()
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
  }

  @Test
  fun `UserProfileScreen shows only name and email at top, 5 menu cards in order, hides admin email, and preserves History and Activity when task is deleted`() {
    var signedOut = false
    val adminProfile =
      UserProfile(
        id = "admin_1",
        userId = "admin_1",
        email = BOOTSTRAPPED_ADMIN_EMAIL,
        displayName = "Primary Admin",
        role = "admin",
        approvalStatus = "approved",
        balanceCents = 1500L,
        completedTasksCount = 1L
      )
    // Simulate a task that was deleted from /tasks (tasksState is empty), but preserved in /submissions (submissionsState)
    val preservedSubmission =
      SubmissionItem(
        id = "hist_task_1",
        taskId = "hist_task_1",
        taskTitle = "Install TaskFlow Partner App",
        appName = "Partner App",
        rewardCents = 5000L,
        userId = "admin_1",
        userName = "Primary Admin",
        status = TaskStatus.APPROVED,
        proofText = "Installed and verified partner app.",
        adminFeedback = "Approved by Admin"
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserProfileScreen(
          profileState = UiState.Success(adminProfile),
          tasksState = UiState.Success(emptyList()),
          userEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          userUid = "admin_1",
          onUpdateDisplayName = {},
          onOpenSecurityInfo = {},
          onSelectTask = {},
          onSignOut = { signedOut = true },
          submissionsState = UiState.Success(listOf(preservedSubmission))
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Top header shows ONLY display name and masked admin email
    composeTestRule.onNodeWithTag("profile_display_name").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_email_address").assertIsDisplayed()
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()

    // 2. Verify all 6 menu cards exist in order: Activity, History, Rewards, Settings, Help & Support, About TaskFlow
    composeTestRule.onNodeWithTag("profile_menu_activity").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_history").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_rewards").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_settings").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_support").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_about").performScrollTo().assertIsDisplayed()

    // 3. Open separate History screen and verify preserved submission card & filters (All, Approved, Pending, Rejected)
    composeTestRule.onNodeWithTag("profile_menu_history").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("user_history_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_filter_all").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_filter_approved").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_filter_pending").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_filter_rejected").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_card_hist_task_1").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    // Submission details dialog opens when history card is tapped
    composeTestRule.onNodeWithTag("history_submission_details_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("history_detail_close_button").performClick()
    composeTestRule.waitForIdle()

    // Return to main Profile screen
    composeTestRule.onNodeWithTag("history_back_button").performClick()
    composeTestRule.waitForIdle()

    // 4. Open separate Activity screen and verify real preserved user/task activity items
    composeTestRule.onNodeWithTag("profile_menu_activity").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("user_activity_screen").assertIsDisplayed()
    composeTestRule.onNodeWithText("Task Approved: Install TaskFlow Partner App").assertIsDisplayed()
    composeTestRule.onNodeWithTag("activity_back_button").performClick()
    composeTestRule.waitForIdle()

    // 5. Sign Out at bottom
    composeTestRule.onNodeWithTag("sign_out_button").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }

  @Test
  fun `UserWalletScreen shows Available Balance in INR, Minimum Withdrawal 200, no Add Money button, and validates Bank Account and UPI ID demo withdrawal requests`() {
    val userProfile =
      UserProfile(
        id = "user_wallet_1",
        userId = "user_wallet_1",
        email = "rahul@example.com",
        displayName = "Rahul Sharma",
        role = "user",
        approvalStatus = "approved",
        balanceCents = 35_000L // ₹350.00 available balance
      )

    var submittedAmount = ""
    var submittedMethod = ""
    var submittedUpi = ""

    composeTestRule.setContent {
      TaskFlowTheme {
        UserWalletScreen(
          profileState = UiState.Success(userProfile),
          tasksState = UiState.Success(emptyList()),
          withdrawalRequestsState = UiState.Success(emptyList()),
          currentUserId = "user_wallet_1",
          onSubmitDemoWithdrawal = { amountInr, method, _, _, _, _, upiId, onSuccess ->
            submittedAmount = amountInr
            submittedMethod = method
            submittedUpi = upiId
            onSuccess()
          }
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Verify Available Balance in INR (₹350.00), Minimum Withdrawal: ₹200, Withdraw button, and NO Add Money option
    composeTestRule.onNodeWithTag("wallet_available_balance").assertIsDisplayed().assertTextEquals("₹350.00")
    composeTestRule.onNodeWithTag("wallet_min_withdrawal_text").assertIsDisplayed()
    composeTestRule.onNodeWithText("Minimum Withdrawal: ₹200").assertIsDisplayed()
    composeTestRule.onNodeWithTag("wallet_withdraw_button").assertIsDisplayed()
    composeTestRule.onNodeWithText("Add Money", substring = true).assertDoesNotExist()

    // 2. Tap Withdraw -> Opens Select Withdrawal Method dialog with Bank Account and UPI ID options
    composeTestRule.onNodeWithTag("wallet_withdraw_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("withdrawal_method_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("Select Withdrawal Method").assertIsDisplayed()
    composeTestRule.onNodeWithTag("withdrawal_method_bank").assertIsDisplayed()
    composeTestRule.onNodeWithTag("withdrawal_method_upi").assertIsDisplayed()

    // Bank Account fields are displayed by default
    composeTestRule.onNodeWithTag("bank_holder_name_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("bank_name_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("bank_account_number_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("bank_ifsc_input").performScrollTo().assertIsDisplayed()

    // Switch to UPI ID option -> shows UPI ID field
    composeTestRule.onNodeWithTag("withdrawal_method_upi").performScrollTo().performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("upi_id_input").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("upi_id_input").performTextInput("rahul@upi")

    // 3. Enter amount below ₹200 (e.g. 150) -> prevented with clear validation message
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performTextInput("150")
    composeTestRule.onNodeWithTag("withdrawal_submit_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("withdrawal_validation_error").performScrollTo().assertIsDisplayed()
    assertEquals("", submittedAmount)

    // 4. Enter amount exceeding available balance (e.g. 500 > ₹350.00) -> prevented with clear validation message
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performTextInput("500")
    composeTestRule.onNodeWithTag("withdrawal_submit_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("withdrawal_validation_error").performScrollTo().assertIsDisplayed()
    assertEquals("", submittedAmount)

    // 5. Enter valid amount (₹250) -> submits demo withdrawal request
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performScrollTo().performTextClearance()
    composeTestRule.onNodeWithTag("withdrawal_amount_input").performTextInput("250")
    composeTestRule.onNodeWithTag("withdrawal_submit_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals("250", submittedAmount)
    assertEquals(WithdrawalMethod.UPI_ID, submittedMethod)
    assertEquals("rahul@upi", submittedUpi)
  }

  @Test
  fun `AdminDashboardScreen displays submitted Demo Withdrawal Requests with user name, amount in INR, method, and Demo Pending status`() {
    val demoRequest =
      DemoWithdrawalRequest(
        id = "req_demo_901",
        requestId = "req_demo_901",
        userId = "user_wallet_1",
        userName = "Rahul Sharma",
        amountCents = 25_000L, // ₹250.00
        method = WithdrawalMethod.BANK_ACCOUNT,
        status = "pending_demo",
        isDemo = true
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.SUBMISSIONS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(emptyList()),
          submissionFilter = TaskStatus.SUBMITTED,
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {},
          adminWithdrawalRequestsState = UiState.Success(listOf(demoRequest))
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("admin_withdrawal_requests_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_withdrawal_card_req_demo_901").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_withdrawal_user_req_demo_901").assertIsDisplayed()
    composeTestRule.onNodeWithText("Rahul Sharma").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_withdrawal_amount_req_demo_901").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹250.00").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_withdrawal_status_req_demo_901").assertIsDisplayed()
    composeTestRule.onNodeWithText("Demo / Pending").assertIsDisplayed()
  }

  @Test
  fun `ThemePreferences switches and persists System Default, Light Mode, and Dark Mode`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    com.example.ui.theme.ThemePreferences.init(context)

    // Default is SYSTEM_DEFAULT
    com.example.ui.theme.ThemePreferences.setThemeMode(context, com.example.ui.theme.AppThemeMode.LIGHT)
    assertEquals(com.example.ui.theme.AppThemeMode.LIGHT, com.example.ui.theme.ThemePreferences.currentThemeMode.value)

    com.example.ui.theme.ThemePreferences.setThemeMode(context, com.example.ui.theme.AppThemeMode.DARK)
    assertEquals(com.example.ui.theme.AppThemeMode.DARK, com.example.ui.theme.ThemePreferences.currentThemeMode.value)

    com.example.ui.theme.ThemePreferences.setThemeMode(context, com.example.ui.theme.AppThemeMode.SYSTEM_DEFAULT)
    assertEquals(com.example.ui.theme.AppThemeMode.SYSTEM_DEFAULT, com.example.ui.theme.ThemePreferences.currentThemeMode.value)
  }

  @Test
  fun `TaskFlowFaqData contains all 15 topics and accurately answers user inquiries`() {
    assertEquals(15, com.example.data.model.TaskFlowFaqData.ITEMS.size)
    assertEquals(5, com.example.data.model.TaskFlowFaqData.COMMON_QUESTIONS.size)

    val withdrawResponse = com.example.data.model.TaskFlowFaqData.findAnswer("What is the minimum withdrawal?")
    assertNotNull(withdrawResponse)
    assertTrue(withdrawResponse!!.answer.contains("₹200", ignoreCase = true))
    assertEquals("wallet", withdrawResponse.targetScreen)

    val darkResponse = com.example.data.model.TaskFlowFaqData.findAnswer("how to enable dark mode?")
    assertNotNull(darkResponse)
    assertTrue(darkResponse!!.answer.contains("Dark Mode", ignoreCase = true))
    assertEquals("settings", darkResponse.targetScreen)

    val proofResponse = com.example.data.model.TaskFlowFaqData.findAnswer("Why are text and photo both mandatory?")
    assertNotNull(proofResponse)
    assertTrue(proofResponse!!.answer.contains("written", ignoreCase = true))
  }

  @Test
  fun `UserSupportScreen displays FAQ and interactive chat assistant responds with screen navigation`() {
    var navigatedScreen = ""
    composeTestRule.setContent {
      TaskFlowTheme {
        com.example.ui.user.UserSupportScreen(
          onBack = {},
          onNavigateScreen = { navigatedScreen = it }
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("user_support_screen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tab_faq_browse").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tab_assistant_chat").assertIsDisplayed()

    // 1. Browse FAQ tab is visible with 15 FAQs search and categories
    composeTestRule.onNodeWithTag("faq_search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("faq_chip_All").assertIsDisplayed()
    composeTestRule.onNodeWithTag("faq_card_faq_account_signin").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    // 2. Switch to Ask Assistant chat tab
    composeTestRule.onNodeWithTag("tab_assistant_chat").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("chat_input_field").performTextInput("How do I find Available Tasks?")
    composeTestRule.onNodeWithTag("chat_send_button").performClick()
    composeTestRule.waitForIdle()

    // Assistant provides reply with action button
    composeTestRule.onNodeWithTag("chat_action_btn").performScrollTo().assertIsDisplayed().performClick()
    assertEquals("tracking", navigatedScreen)
  }

  @Test
  fun `AdminUsersTabContent allows searching by name or email, filtering by Pending, Approved, and Suspended, and opening detail dialog`() {
    val pendingUser = UserProfile(
      id = "user_p1",
      userId = "user_p1",
      displayName = "Amina Begum",
      email = "amina@example.com",
      role = "user",
      approvalStatus = "pending",
      balanceCents = 0L
    )
    val approvedUser = UserProfile(
      id = "user_a1",
      userId = "user_a1",
      displayName = "Vikram Patel",
      email = "vikram@example.com",
      role = "user",
      approvalStatus = "approved",
      balanceCents = 1500L
    )
    val suspendedUser = UserProfile(
      id = "user_s1",
      userId = "user_s1",
      displayName = "Suresh Kumar",
      email = "suresh@example.com",
      role = "user",
      approvalStatus = "suspended",
      balanceCents = 500L
    )

    var toggledUser: UserProfile? = null

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.USERS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(listOf(pendingUser, approvedUser, suspendedUser)),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = { toggledUser = it }
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("admin_users_tab_content").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_search_input").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_filter_all").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_filter_pending").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_filter_approved").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_filter_suspended").assertIsDisplayed()

    // Filter by Pending
    composeTestRule.onNodeWithTag("admin_user_filter_pending").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_card_user_p1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_card_user_a1").assertDoesNotExist()

    // Filter by Suspended
    composeTestRule.onNodeWithTag("admin_user_filter_suspended").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_card_user_s1").assertIsDisplayed()

    // Search by email "vikram" with filter All
    composeTestRule.onNodeWithTag("admin_user_filter_all").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_search_input").performTextInput("vikram")
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_card_user_a1").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_card_user_p1").assertDoesNotExist()

    // Tap card to open user detail dialog
    composeTestRule.onNodeWithTag("admin_user_card_user_a1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_detail_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("Close").performClick()
    composeTestRule.waitForIdle()

    // Click Suspend on approved user -> opens confirmation dialog
    composeTestRule.onNodeWithTag("toggle_user_status_user_a1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_confirm_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("admin_user_confirm_status_btn").performClick()
    composeTestRule.waitForIdle()
    assertEquals(approvedUser.userId, toggledUser?.userId)
  }

  @Test
  fun `verify 7-day daily check-in calculation, streak progression, and 13 rupee total reward`() {
    // 1. Structure check: Days 1-6 = 100 paise (₹1.00), Day 7 = 700 paise (₹7.00), Total = 1300 paise (₹13.00)
    assertEquals(1300L, DailyCheckInConstants.TOTAL_7_DAY_CENTS)
    for (day in 1..6) {
      assertEquals(100L, DailyCheckInConstants.getRewardForDay(day))
    }
    assertEquals(700L, DailyCheckInConstants.getRewardForDay(7))

    val today = getTodayDateKey()
    val yesterday = getYesterdayDateKey()

    // 2. First-time or reset user
    val initialState = calculateDailyCheckInState(lastDate = "", streak = 0L)
    assertTrue(initialState.canCheckInToday)
    assertEquals(1, initialState.nextClaimDay)
    assertEquals(100L, initialState.nextRewardCents)

    // 3. User who checked in yesterday with streak = 3 -> Continuing streak to Day 4
    val streakState = calculateDailyCheckInState(lastDate = yesterday, streak = 3L)
    assertTrue(streakState.canCheckInToday)
    assertEquals(4, streakState.nextClaimDay)
    assertEquals(100L, streakState.nextRewardCents)

    // 4. User who checked in yesterday with streak = 6 -> Next is Day 7 big reward (₹7.00)
    val day7State = calculateDailyCheckInState(lastDate = yesterday, streak = 6L)
    assertTrue(day7State.canCheckInToday)
    assertEquals(7, day7State.nextClaimDay)
    assertEquals(700L, day7State.nextRewardCents)

    // 5. User who already claimed today -> canCheckInToday is false
    val claimedTodayState = calculateDailyCheckInState(lastDate = today, streak = 4L)
    assertFalse(claimedTodayState.canCheckInToday)
    assertEquals(5, claimedTodayState.nextClaimDay)

    // 6. User who missed a day (last check-in was 2+ days ago) -> resets to Day 1
    val missedDayState = calculateDailyCheckInState(lastDate = "2026-01-01", streak = 5L)
    assertTrue(missedDayState.canCheckInToday)
    assertEquals(1, missedDayState.nextClaimDay)
    assertEquals(100L, missedDayState.nextRewardCents)
  }

  @Test
  fun `UserHomeScreen displays Daily Check-in card, Watch & Earn card, Leaderboard top 3, and recent transactions`() {
    val sampleProfile =
      UserProfile(
        id = "user_home_99",
        userId = "user_home_99",
        displayName = "Pooja Roy",
        email = "pooja@example.com",
        balanceCents = 12_850L // ₹128.50
      )
    val topUsers =
      listOf(
        UserProfile(id = "u1", displayName = "Amit Verma", balanceCents = 55_000L),
        UserProfile(id = "u2", displayName = "Rohan Das", balanceCents = 32_000L),
        UserProfile(id = "u3", displayName = "Sneha Sen", balanceCents = 21_000L)
      )
    val sampleTx =
      TransactionRecord(
        id = "tx_1",
        userId = "user_home_99",
        userName = "Pooja Roy",
        type = TransactionType.DAILY_CHECKIN,
        title = "Day 1 Daily Check-in Reward",
        amountCents = 100L
      )

    var dailyCheckInClaimed = false

    composeTestRule.setContent {
      TaskFlowTheme {
        UserHomeScreen(
          profileState = UiState.Success(sampleProfile),
          tasksState = UiState.Success(emptyList()),
          isAdminAuthorized = false,
          onSelectTask = {},
          onNavigateToTracking = {},
          onSwitchToAdmin = {},
          dailyCheckInState =
            DailyCheckInState(
              currentStreak = 0,
              nextClaimDay = 1,
              canCheckInToday = true,
              nextRewardCents = 100L
            ),
          onClaimDailyCheckIn = { dailyCheckInClaimed = true },
          transactionsState = UiState.Success(listOf(sampleTx)),
          leaderboardState = UiState.Success(topUsers)
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Verify Compact Balance indicator and Welcome message
    composeTestRule.onNodeWithTag("home_compact_balance").assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_available_balance", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("home_available_balance", useUnmergedTree = true).assertTextEquals("₹128.50")
    composeTestRule.onNodeWithTag("home_welcome_message").assertIsDisplayed()
    composeTestRule.onNodeWithText("Pooja Roy").assertIsDisplayed()

    // 2. Verify Recent Transactions section
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_recent_transactions_section"))
    composeTestRule.onNodeWithTag("home_recent_transactions_section").assertIsDisplayed()
    composeTestRule.onNodeWithText("Day 1 Daily Check-in Reward").assertIsDisplayed()

    // 3. Verify Daily Check-in card and claim action
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_daily_checkin_card"))
    composeTestRule.onNodeWithTag("home_daily_checkin_card").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("claim_daily_checkin_button"))
    composeTestRule.onNodeWithTag("claim_daily_checkin_button").assertIsDisplayed().performClick()
    assertTrue(dailyCheckInClaimed)

    // 4. Verify Watch & Earn card and transparent info dialog
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_watch_earn_card"))
    composeTestRule.onNodeWithTag("home_watch_earn_card").assertIsDisplayed()
    composeTestRule.onNodeWithTag("watch_earn_badge").assertIsDisplayed()
    composeTestRule.onNodeWithTag("watch_ad_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("watch_earn_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("close_watch_dialog_button").performClick()
    composeTestRule.waitForIdle()

    // 5. Verify Available Tasks section
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_available_tasks_section"))
    composeTestRule.onNodeWithTag("home_available_tasks_section").assertIsDisplayed()

    // 6. Verify Leaderboard top 3 and View All dialog
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_leaderboard_section"))
    composeTestRule.onNodeWithTag("home_leaderboard_section").assertIsDisplayed()
    composeTestRule.onNodeWithText("Amit Verma").assertIsDisplayed()
    composeTestRule.onNodeWithText("Rohan Das").assertIsDisplayed()
    composeTestRule.onNodeWithText("Sneha Sen").assertIsDisplayed()
    composeTestRule.onNodeWithTag("view_all_leaderboard_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("leaderboard_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithTag("close_leaderboard_dialog_button").performClick()
    composeTestRule.waitForIdle()

    // 7. Verify Wallet section on Home
    composeTestRule
      .onNodeWithTag("user_home_screen")
      .performScrollToNode(hasTestTag("home_wallet_section"))
    composeTestRule.onNodeWithTag("home_wallet_section").assertIsDisplayed()
  }

  @Test
  fun `UserWalletScreen displays Total Earnings, Pending Rewards, and Transaction History list`() {
    val sampleProfile =
      UserProfile(
        id = "user_w_5",
        userId = "user_w_5",
        displayName = "Karan Patel",
        balanceCents = 24_000L // ₹240.00
      )
    val submittedTask =
      TaskItem(
        id = "task_sub_1",
        title = "Install Security App",
        rewardCents = 3500L,
        assignedUserId = "user_w_5",
        status = TaskStatus.SUBMITTED
      )
    val txRecord =
      TransactionRecord(
        id = "tx_record_88",
        userId = "user_w_5",
        type = TransactionType.TASK_REWARD,
        title = "Task Reward: Install Security App",
        amountCents = 3500L
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserWalletScreen(
          profileState = UiState.Success(sampleProfile),
          tasksState = UiState.Success(listOf(submittedTask)),
          withdrawalRequestsState = UiState.Success(emptyList()),
          currentUserId = "user_w_5",
          submissionsState = UiState.Success(emptyList()),
          transactionsState = UiState.Success(listOf(txRecord))
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("wallet_available_balance").assertIsDisplayed()
    composeTestRule.onNodeWithTag("wallet_total_earnings").assertIsDisplayed()
    composeTestRule.onNodeWithTag("wallet_pending_rewards").assertIsDisplayed().assertTextEquals("₹35.00")
    composeTestRule
      .onNodeWithTag("user_wallet_screen")
      .performScrollToNode(hasTestTag("wallet_transaction_history_section"))
    composeTestRule.onNodeWithTag("wallet_transaction_history_section").assertIsDisplayed()

    composeTestRule
      .onNodeWithTag("user_wallet_screen")
      .performScrollToNode(hasTestTag("wallet_transaction_item_tx_record_88"))
    composeTestRule.onNodeWithTag("wallet_transaction_item_tx_record_88").assertIsDisplayed()
    composeTestRule.onNodeWithText("Task Reward: Install Security App").assertIsDisplayed()
  }

  @Test
  fun `AdminUsersTabContent allows Admin to edit User Display Name from detail dialog`() {
    val targetUser =
      UserProfile(
        id = "user_edit_1",
        userId = "user_edit_1",
        displayName = "Original Name",
        email = "member@example.com",
        role = "user",
        approvalStatus = "approved"
      )
    var updatedName: String? = null

    composeTestRule.setContent {
      TaskFlowTheme {
        AdminDashboardScreen(
          isAdminAuthorized = true,
          currentUserId = "admin_1",
          currentUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          currentUserDisplayName = "Primary Admin",
          activeTab = AdminTab.USERS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(listOf(targetUser)),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {},
          onUpdateUserName = { user, newName -> updatedName = newName }
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Open User Detail Dialog
    composeTestRule.onNodeWithTag("admin_user_card_user_edit_1").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_user_detail_dialog").assertIsDisplayed()

    // 2. Click Edit Name button
    composeTestRule.onNodeWithTag("admin_start_edit_user_name_btn").performClick()
    composeTestRule.waitForIdle()

    // 3. Enter new display name and save
    composeTestRule.onNodeWithTag("admin_edit_user_name_input").performTextClearance()
    composeTestRule.onNodeWithTag("admin_edit_user_name_input").performTextInput("Updated Akash")
    composeTestRule.onNodeWithTag("admin_save_user_name_btn").performClick()
    composeTestRule.waitForIdle()

    assertEquals("Updated Akash", updatedName)
  }

  @Test
  fun `AppUpdateDialog displays update info, release notes, and handles update actions`() {
    val versionInfo = AppVersionInfo(
      versionCode = 5,
      versionName = "1.2.0",
      apkUrl = "https://example.com/taskflow.apk",
      releaseNotes = "New rewards and performance upgrades",
      forceUpdate = false
    )
    var updateClicked = false

    composeTestRule.setContent {
      TaskFlowTheme {
        com.example.ui.components.AppUpdateDialog(
          versionInfo = versionInfo,
          onUpdateNow = { updateClicked = true },
          onInstallDownloaded = {},
          onDismiss = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("update_dialog").assertIsDisplayed()
    composeTestRule.onNodeWithText("New Update Available").assertIsDisplayed()
    composeTestRule.onNodeWithText("Version 1.2.0 (Code: 5) is available.").assertIsDisplayed()
    composeTestRule.onNodeWithText("New rewards and performance upgrades").assertIsDisplayed()
    composeTestRule.onNodeWithTag("update_now_button").assertIsDisplayed().performClick()
    assertTrue(updateClicked)
  }
}



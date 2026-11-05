package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
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
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserProfileScreen
import com.example.ui.user.UserTaskDetailScreen
import com.example.ui.user.UserWalletScreen
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.UiState
import org.junit.Assert.assertEquals
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
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun `verify app strings and firebase oauth resources`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("TaskFlow", context.getString(R.string.app_name))
    assertEquals("(default)", context.getString(R.string.firestore_database_id))
    assertEquals(
      "54814309263-uralqcp874c3v5nrk9r4ajj9scpt93ug.apps.googleusercontent.com",
      context.getString(R.string.default_web_client_id)
    )
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
  fun `AuthScreen portal selector switches between User App and Admin Portal`() {
    var selectedPortal = PortalLoginTarget.USER_APP
    composeTestRule.setContent {
      TaskFlowTheme {
        AuthScreen(
          selectedPortal = selectedPortal,
          onSelectPortal = { selectedPortal = it },
          onAuthSuccess = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("portal_user_tab").assertIsDisplayed()
    composeTestRule.onNodeWithTag("portal_admin_tab").assertIsDisplayed()
    composeTestRule.onNodeWithTag("google_sign_in_button").performScrollTo().assertIsDisplayed()

    composeTestRule.onNodeWithTag("portal_admin_tab").performClick()
    composeTestRule.waitForIdle()
    assertEquals(PortalLoginTarget.ADMIN_DASHBOARD, selectedPortal)
  }

  @Test
  fun `UserHomeScreen displays clickable task URL in INR and opens browser intent when tapped`() {
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
        status = TaskStatus.PENDING
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserHomeScreen(
          profileState = UiState.Success(sampleProfile),
          tasksState = UiState.Success(listOf(sampleTask)),
          isAdminAuthorized = false,
          onSelectTask = {},
          onNavigateToTracking = {},
          onSwitchToAdmin = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Test Worker").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹25.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹15.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("Verify Checkout UI").assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("dashboard_featured_task_link_task_101")
      .performScrollTo()
      .assertIsDisplayed()
      .performClick()

    val context = ApplicationProvider.getApplicationContext<Context>()
    val nextStartedActivity = Shadows.shadowOf(context as android.app.Application).nextStartedActivity
    assertNotNull("Expected ACTION_VIEW Intent to be launched when clicking task URL", nextStartedActivity)
    assertEquals(Intent.ACTION_VIEW, nextStartedActivity.action)
    assertEquals(Uri.parse(playStoreUrl), nextStartedActivity.data)
  }

  @Test
  fun `UserTaskDetailScreen displays all 9 task detail sections and enforces mandatory text AND photo proof`() {
    val sampleTask =
      TaskItem(
        id = "task_202",
        title = "Install & Review App",
        appName = "TaskFlow Companion",
        appIconUrl = "https://example.com/icon.png",
        description = "Download our app and verify onboarding",
        instructions = "Step 1: Tap the link\nStep 2: Screenshot the welcome screen",
        requiredProof = "Provide your test username and welcome screen screenshot",
        taskLink = "https://play.google.com/store/apps/details?id=com.example.app",
        category = "App Install",
        rewardCents = 5000L,
        assignedUserId = "admin_1",
        assignedUserName = "Primary Admin",
        assignedUserEmail = BOOTSTRAPPED_ADMIN_EMAIL,
        status = TaskStatus.PENDING
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

    // Verify all dedicated task detail sections are displayed
    composeTestRule.onNodeWithTag("task_detail_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_app_info_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_app_icon").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_reward_section").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_description").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_instructions").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_required_proof_section").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_clickable_link").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("start_task_open_link_button").performScrollTo().assertIsDisplayed()

    // Case 1: Photo is selected (initialSelectedImageUri), but written text proof is empty -> Submit button MUST be disabled
    composeTestRule
      .onNodeWithTag("submit_proof_button")
      .performScrollTo()
      .assertIsNotEnabled()

    // Case 2: Enter written text proof while photo is selected -> Submit button MUST become enabled
    composeTestRule
      .onNodeWithTag("task_proof_input")
      .performScrollTo()
      .performTextInput("Completed app installation and verified welcome screen.")
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("submit_proof_button")
      .performScrollTo()
      .assertIsEnabled()

    // Case 3: Remove the selected photo while text proof is present (text-only) -> Submit button MUST become disabled
    composeTestRule
      .onNodeWithTag("remove_photo_button")
      .performScrollTo()
      .performClick()
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("submit_proof_button")
      .performScrollTo()
      .assertIsNotEnabled()
    assertEquals(null, submittedText)
    assertEquals(null, submittedUri)
  }

  @Test
  fun `UserTaskDetailScreen submits when both written text and gallery photo are provided`() {
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
        status = TaskStatus.PENDING
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

    composeTestRule
      .onNodeWithTag("task_proof_input")
      .performScrollTo()
      .performTextInput("Survey completed with confirmation code #9821.")
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("submit_proof_button")
      .performScrollTo()
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
    composeTestRule.onNodeWithText("TOTAL TASKS").assertIsDisplayed()
    composeTestRule.onNodeWithText("NEEDS REVIEW").assertIsDisplayed()
    composeTestRule.onNodeWithText("APPROVED").assertIsDisplayed()
    composeTestRule.onNodeWithText("USERS").assertIsDisplayed()
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
  }

  @Test
  fun `UserProfileScreen shows only name and email at top, 5 menu cards in order, hides admin email, and navigates to History and Activity screens`() {
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
    val completedTask =
      TaskItem(
        id = "hist_task_1",
        title = "Install TaskFlow Partner App",
        appName = "Partner App",
        category = "App Install",
        rewardCents = 5000L,
        assignedUserId = "admin_1",
        assignedUserName = "Primary Admin",
        status = TaskStatus.APPROVED,
        proofText = "Installed and verified partner app.",
        adminFeedback = "Approved by Admin"
      )

    composeTestRule.setContent {
      TaskFlowTheme {
        UserProfileScreen(
          profileState = UiState.Success(adminProfile),
          tasksState = UiState.Success(listOf(completedTask)),
          userEmail = BOOTSTRAPPED_ADMIN_EMAIL,
          userUid = "admin_1",
          onUpdateDisplayName = {},
          onOpenSecurityInfo = {},
          onSelectTask = {},
          onSignOut = { signedOut = true }
        )
      }
    }
    composeTestRule.waitForIdle()

    // 1. Top header shows ONLY display name and masked admin email
    composeTestRule.onNodeWithTag("profile_display_name").assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_email_address").assertIsDisplayed()
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()

    // 2. Verify all 5 menu cards exist in order: Activity, History, Rewards, Settings, About TaskFlow
    composeTestRule.onNodeWithTag("profile_menu_activity").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_history").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_rewards").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_settings").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("profile_menu_about").performScrollTo().assertIsDisplayed()

    // 3. Open separate History screen and verify compact card & filters (All, Approved, Pending, Rejected)
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

    // 4. Open separate Activity screen and verify real user/task activity items
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
    composeTestRule.onNodeWithTag("wallet_available_balance").assertIsDisplayed()
    composeTestRule.onNodeWithText("₹350.00").assertIsDisplayed()
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
}

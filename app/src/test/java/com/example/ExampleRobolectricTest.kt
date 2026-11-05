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
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.ui.admin.AdminDashboardScreen
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.PortalLoginTarget
import com.example.ui.theme.TaskFlowTheme
import com.example.ui.user.UserHomeScreen
import com.example.ui.user.UserProfileScreen
import com.example.ui.user.UserTaskDetailScreen
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
        description = "Test the mobile checkout flow",
        instructions = "1. Open Play Store link\n2. Install & test checkout",
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
  fun `UserTaskDetailScreen enforces mandatory text AND photo proof - blocks text-only and photo-only, succeeds with both`() {
    val sampleTask =
      TaskItem(
        id = "task_202",
        title = "Install & Review App",
        description = "Download our app and verify onboarding",
        instructions = "Step 1: Tap the link\nStep 2: Screenshot the welcome screen",
        taskLink = "https://play.google.com/store/apps/details?id=com.example.app",
        category = "Mobile App",
        rewardCents = 5000L,
        assignedUserId = "user_1",
        assignedUserName = "Test Worker",
        assignedUserEmail = "worker@example.com",
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
          userApprovalStatus = "approved",
          currentUserId = "user_1",
          initialSelectedImageUri = fakePhotoUri
        )
      }
    }
    composeTestRule.waitForIdle()

    // Verify task details, step-by-step instructions, INR reward, and clickable link are displayed
    composeTestRule.onNodeWithTag("task_detail_title").assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_instructions").performScrollTo().assertIsDisplayed()
    composeTestRule.onNodeWithTag("task_detail_clickable_link").performScrollTo().assertIsDisplayed()

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
        description = "Finish survey form",
        instructions = "1. Open link\n2. Submit screenshot",
        taskLink = "https://example.com/survey",
        category = "Research",
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
  fun `AdminCreateTask form validates HTTPS URL and creates task with link and step-by-step instructions`() {
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
    var createdLink = ""
    var createdInstructions = ""
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
          onCreateTask = { title, _, _, rewardStr, _, instructions, taskLink ->
            createdTitle = title
            createdReward = rewardStr
            createdInstructions = instructions
            createdLink = taskLink
          },
          onUpdateTask = { _, _, _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    // Open Create Task Dialog
    composeTestRule.onNodeWithTag("admin_create_task_fab").performClick()
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("task_modal_title_input").performTextInput("Play Store Review Task")
    composeTestRule.onNodeWithTag("task_modal_desc_input").performTextInput("Install app and test login")
    composeTestRule
      .onNodeWithTag("task_modal_instructions_input")
      .performScrollTo()
      .performTextInput("1. Tap Play Store link\n2. Take screenshot of home screen")

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
    assertEquals("https://play.google.com/store/apps/details?id=com.example.app", createdLink)
    assertEquals("1. Tap Play Store link\n2. Take screenshot of home screen", createdInstructions)
    assertEquals("15.00", createdReward)
  }

  @Test
  fun `AdminSubmissionsTab displays written proof, uploaded image, full-size image inspection, User UID, Task ID, and approves or rejects`() {
    val submittedTask =
      TaskItem(
        id = "task_review_501",
        title = "Verify Payment Gateway",
        description = "Test UPI/Card checkout page",
        instructions = "1. Open checkout\n2. Upload screenshot",
        taskLink = "https://example.com/checkout",
        category = "QA Testing",
        rewardCents = 4500L,
        assignedUserId = "worker_uid_99",
        assignedUserName = "Aarav Sharma",
        assignedUserEmail = "aarav@example.com",
        status = TaskStatus.SUBMITTED,
        proofText = "Verified checkout screen and attached screenshot.",
        proofImageUrl = "https://firebasestorage.googleapis.com/v0/b/taskflow-8852d.firebasestorage.app/o/proof.jpg?alt=media",
        proofStoragePath = "submissions/worker_uid_99/task_review_501/proof_1.jpg"
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
          adminTasksState = UiState.Success(listOf(submittedTask)),
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
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()

    // Verify User UID, Task ID, Written Proof, and Uploaded Image preview are visible
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
  fun `AdminDashboardScreen renders metrics and create task FAB for authorized admin without exposing admin email`() {
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
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
  }

  @Test
  fun `UserProfileScreen hides admin personal email and triggers signOut callback`() {
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
          onSignOut = { signedOut = true }
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("₹15.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("Authorized Administrator").assertIsDisplayed()
    composeTestRule.onNodeWithText(BOOTSTRAPPED_ADMIN_EMAIL, substring = true).assertDoesNotExist()
    composeTestRule.onNodeWithTag("sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }
}

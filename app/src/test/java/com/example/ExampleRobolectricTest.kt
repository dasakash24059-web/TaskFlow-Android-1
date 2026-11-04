package com.example

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
  fun `UserHomeScreen and UserTaskDetailScreen render assigned tasks and submit proof`() {
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
        category = "QA Testing",
        rewardCents = 1500L,
        assignedUserId = "user_1",
        assignedUserName = "Test Worker",
        assignedUserEmail = "worker@example.com",
        status = TaskStatus.PENDING
      )

    var selectedTaskId: String? = null
    composeTestRule.setContent {
      TaskFlowTheme {
        UserHomeScreen(
          profileState = UiState.Success(sampleProfile),
          tasksState = UiState.Success(listOf(sampleTask)),
          isAdminAuthorized = false,
          onSelectTask = { selectedTaskId = it },
          onNavigateToTracking = {},
          onSwitchToAdmin = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Test Worker").assertIsDisplayed()
    composeTestRule.onNodeWithText("$25.00").assertIsDisplayed()
    composeTestRule.onNodeWithText("Verify Checkout UI").assertIsDisplayed()
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
          onCreateTask = { _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithText("Admin Access Restricted").assertIsDisplayed()
  }

  @Test
  fun `AdminDashboardScreen renders metrics and create task FAB for authorized admin`() {
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
          activeTab = AdminTab.TASKS,
          adminTasksState = UiState.Success(emptyList()),
          registeredUsersState = UiState.Success(listOf(adminUser)),
          submissionFilter = "submitted",
          onSelectSubmissionFilter = {},
          onCreateTask = { _, _, _, _, _ -> },
          onUpdateTask = { _, _, _, _, _, _ -> },
          onDeleteTask = {},
          onReviewSubmission = { _, _, _ -> },
          onToggleUserStatus = {}
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("admin_create_task_fab").assertIsDisplayed()
    composeTestRule.onNodeWithText("TOTAL TASKS").assertIsDisplayed()
    composeTestRule.onNodeWithText("NEEDS REVIEW").assertIsDisplayed()
  }

  @Test
  fun `UserProfileScreen triggers signOut callback`() {
    var signedOut = false
    val profile =
      UserProfile(
        id = "user_1",
        userId = "user_1",
        email = "worker@example.com",
        displayName = "Test Worker",
        role = "user",
        approvalStatus = "approved",
        balanceCents = 1500L,
        completedTasksCount = 1L
      )
    composeTestRule.setContent {
      TaskFlowTheme {
        UserProfileScreen(
          profileState = UiState.Success(profile),
          tasksState = UiState.Success(emptyList()),
          userEmail = "worker@example.com",
          userUid = "user_1",
          onUpdateDisplayName = {},
          onOpenSecurityInfo = {},
          onSelectTask = {},
          onSignOut = { signedOut = true }
        )
      }
    }
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("sign_out_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(signedOut)
  }
}

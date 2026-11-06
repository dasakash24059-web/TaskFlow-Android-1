package com.example.ui.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.SubmissionItem
import com.example.data.model.TaskCategories
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.WithdrawalMethod
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.data.model.isValidImageUrlOrDataUri
import com.example.data.model.isValidTaskUrl
import com.example.data.repository.encodeGalleryImageAsDataUri
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.ClickableTaskLinkCard
import com.example.ui.components.FullSizeImageDialog
import com.example.ui.components.ProofImageView
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
import com.example.ui.components.resolveAppOrWebsiteLabel
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun AdminDashboardScreen(
  isAdminAuthorized: Boolean,
  currentUserId: String,
  currentUserEmail: String,
  currentUserDisplayName: String,
  activeTab: AdminTab,
  adminTasksState: UiState<List<TaskItem>>,
  registeredUsersState: UiState<List<UserProfile>>,
  submissionFilter: String,
  onSelectSubmissionFilter: (String) -> Unit,
  onCreateTask: (String, String, String, String, UserProfile, String, String) -> Unit,
  onUpdateTask: (String, String, String, String, String, UserProfile, String, String) -> Unit,
  onDeleteTask: (String) -> Unit,
  onReviewSubmission: (TaskItem, Boolean, String) -> Unit,
  onToggleUserStatus: (UserProfile) -> Unit,
  onUpdateUserStatus: ((UserProfile, String) -> Unit)? = null,
  adminSubmissionsState: UiState<List<SubmissionItem>> = UiState.Success(emptyList()),
  adminWithdrawalRequestsState: UiState<List<DemoWithdrawalRequest>> = UiState.Success(emptyList()),
  onToggleTaskActive: (TaskItem, Boolean) -> Unit = { _, _ -> },
  onCreateTaskExtended:
    ((String, String, String, String, UserProfile, String, String, String, String, String) -> Unit)? =
    null,
  onUpdateTaskExtended:
    ((String, String, String, String, String, UserProfile, String, String, String, String, String) -> Unit)? =
    null
) {
  if (!isAdminAuthorized) {
    UnauthorizedAdminGate(currentUserEmail = currentUserEmail)
    return
  }

  val tasks = (adminTasksState as? UiState.Success)?.data ?: emptyList()
  val submissions = (adminSubmissionsState as? UiState.Success)?.data ?: emptyList()
  val withdrawalRequests =
    (adminWithdrawalRequestsState as? UiState.Success)?.data ?: emptyList()
  val registeredUsers =
    (registeredUsersState as? UiState.Success)?.data.let { list ->
      if (list.isNullOrEmpty()) {
        listOf(
          UserProfile(
            id = currentUserId,
            userId = currentUserId,
            email = currentUserEmail,
            displayName = currentUserDisplayName.ifBlank { "Admin User" },
            role = "admin",
            approvalStatus = "approved"
          )
        )
      } else {
        list
      }
    }

  // Active tasks currently in /tasks, enriched with submission details
  val activeManagedTasks =
    remember(tasks, submissions) {
      val subByTaskId = submissions.associateBy { it.taskId.ifBlank { it.id } }
      tasks
        .map { task ->
          val sub = subByTaskId[task.id]
          if (sub != null) {
            task.copy(
              status = sub.status.ifBlank { task.status },
              assignedUserId = sub.userId.ifBlank { task.assignedUserId },
              assignedUserName = sub.userName.ifBlank { task.assignedUserName },
              assignedUserEmail = sub.userEmail.ifBlank { task.assignedUserEmail },
              proofText = sub.proofText.ifBlank { task.proofText },
              proofImageUrl = sub.proofImageUrl.ifBlank { task.proofImageUrl },
              proofStoragePath = sub.proofStoragePath.ifBlank { task.proofStoragePath },
              adminFeedback = sub.adminFeedback.ifBlank { task.adminFeedback },
              updatedAt = sub.updatedAt ?: task.updatedAt
            )
          } else {
            task
          }
        }
        .sortedByDescending { it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L }
    }

  // Merge tasks and /submissions records so any submission in /submissions/{taskId} is always visible to Admin even if the task is deleted
  val mergedReviewTasks =
    remember(tasks, submissions) {
      val taskById = tasks.associateBy { it.id }.toMutableMap()
      submissions.forEach { sub ->
        val key = sub.taskId.ifBlank { sub.id }
        val existingTask = taskById[key]
        if (existingTask != null) {
          taskById[key] =
            existingTask.copy(
              status = sub.status.ifBlank { existingTask.status },
              assignedUserId = sub.userId.ifBlank { existingTask.assignedUserId },
              assignedUserName = sub.userName.ifBlank { existingTask.assignedUserName },
              assignedUserEmail = sub.userEmail.ifBlank { existingTask.assignedUserEmail },
              proofText = sub.proofText.ifBlank { existingTask.proofText },
              proofImageUrl = sub.proofImageUrl.ifBlank { existingTask.proofImageUrl },
              proofStoragePath = sub.proofStoragePath.ifBlank { existingTask.proofStoragePath },
              adminFeedback = sub.adminFeedback.ifBlank { existingTask.adminFeedback },
              updatedAt = sub.updatedAt ?: existingTask.updatedAt
            )
        } else if (key.isNotBlank()) {
          taskById[key] =
            TaskItem(
              id = key,
              title = sub.taskTitle.ifBlank { "Submitted Task" },
              appName = sub.appName,
              appIconUrl = sub.appIconUrl,
              description = "Submitted proof for ${sub.taskTitle}",
              rewardCents = sub.rewardCents,
              assignedUserId = sub.userId,
              assignedUserName = sub.userName,
              assignedUserEmail = sub.userEmail,
              status = sub.status.ifBlank { TaskStatus.SUBMITTED },
              proofText = sub.proofText,
              proofImageUrl = sub.proofImageUrl,
              proofStoragePath = sub.proofStoragePath,
              adminFeedback = sub.adminFeedback,
              createdAt = sub.createdAt,
              updatedAt = sub.updatedAt
            )
        }
      }
      taskById.values.sortedByDescending {
        it.updatedAt?.seconds ?: it.createdAt?.seconds ?: 0L
      }
    }

  var showTaskModal by remember { mutableStateOf(false) }
  var editingTask by remember { mutableStateOf<TaskItem?>(null) }
  var preselectedAssignee by remember { mutableStateOf<UserProfile?>(null) }

  if (showTaskModal) {
    TaskEditorDialog(
      existingTask = editingTask,
      registeredUsers = registeredUsers,
      defaultAssignee = preselectedAssignee ?: registeredUsers.firstOrNull(),
      onDismiss = {
        showTaskModal = false
        editingTask = null
        preselectedAssignee = null
      },
      onSave = {
          title,
          desc,
          cat,
          rewardStr,
          assignee,
          instructions,
          taskLink,
          appName,
          appIconUrl,
          requiredProof ->
        val target = editingTask
        if (target == null) {
          if (onCreateTaskExtended != null) {
            onCreateTaskExtended(
              title,
              desc,
              cat,
              rewardStr,
              assignee,
              instructions,
              taskLink,
              appName,
              appIconUrl,
              requiredProof
            )
          } else {
            onCreateTask(title, desc, cat, rewardStr, assignee, instructions, taskLink)
          }
        } else {
          if (onUpdateTaskExtended != null) {
            onUpdateTaskExtended(
              target.id,
              title,
              desc,
              cat,
              rewardStr,
              assignee,
              instructions,
              taskLink,
              appName,
              appIconUrl,
              requiredProof
            )
          } else {
            onUpdateTask(target.id, title, desc, cat, rewardStr, assignee, instructions, taskLink)
          }
        }
        showTaskModal = false
        editingTask = null
        preselectedAssignee = null
      }
    )
  }

  val pendingSubmissionsCount =
    mergedReviewTasks.count { it.status == TaskStatus.SUBMITTED }
  val approvedSubmissionsCount =
    mergedReviewTasks.count { it.status == TaskStatus.APPROVED }

  Scaffold(
    floatingActionButton = {
      if (activeTab == AdminTab.TASKS) {
        FloatingActionButton(
          onClick = {
            editingTask = null
            preselectedAssignee = null
            showTaskModal = true
          },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.testTag("admin_create_task_fab")
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Create Task")
            Text("New Task", style = MaterialTheme.typography.labelLarge)
          }
        }
      }
    }
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
      // 4 Dashboard Statistics Cards: Total Tasks, Needs Review (Pending), Approved, Users
      AdminMetricsBanner(
        totalTasks = tasks.size,
        pendingReviews = pendingSubmissionsCount,
        approvedSubmissions = approvedSubmissionsCount,
        totalUsers = registeredUsers.size
      )

      Spacer(modifier = Modifier.height(14.dp))

      when (activeTab) {
        AdminTab.TASKS -> {
          AdminTasksTabContent(
            tasks = activeManagedTasks,
            withdrawalRequests = withdrawalRequests,
            onCreateClick = {
              editingTask = null
              preselectedAssignee = null
              showTaskModal = true
            },
            onEditTask = { task ->
              editingTask = task
              showTaskModal = true
            },
            onToggleTaskActive = onToggleTaskActive,
            onDeleteTask = onDeleteTask
          )
        }
        AdminTab.SUBMISSIONS -> {
          AdminSubmissionsTabContent(
            tasks = mergedReviewTasks,
            withdrawalRequests = withdrawalRequests,
            selectedFilter = submissionFilter,
            onSelectFilter = onSelectSubmissionFilter,
            onReviewSubmission = onReviewSubmission
          )
        }
        AdminTab.USERS -> {
          AdminUsersTabContent(
            users = registeredUsers,
            onAssignTaskToUser = { user ->
              editingTask = null
              preselectedAssignee = user
              showTaskModal = true
            },
            onToggleUserStatus = onToggleUserStatus,
            onUpdateUserStatus = onUpdateUserStatus
          )
        }
        AdminTab.WITHDRAWALS -> {
          LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
          ) {
            item {
              AdminWithdrawalRequestsSection(withdrawalRequests = withdrawalRequests)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminMetricsBanner(
  totalTasks: Int,
  pendingReviews: Int,
  approvedSubmissions: Int,
  totalUsers: Int
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      AdminMetricCard(
        label = "TOTAL TASKS",
        value = totalTasks.toString(),
        icon = Icons.Default.Task,
        modifier = Modifier.weight(1f)
      )
      AdminMetricCard(
        label = "NEEDS REVIEW",
        value = pendingReviews.toString(),
        icon = Icons.Default.RateReview,
        modifier = Modifier.weight(1f)
      )
      AdminMetricCard(
        label = "APPROVED",
        value = approvedSubmissions.toString(),
        icon = Icons.Default.CheckCircle,
        modifier = Modifier.weight(1f)
      )
      AdminMetricCard(
        label = "USERS",
        value = totalUsers.toString(),
        icon = Icons.Default.People,
        modifier = Modifier.weight(1f)
      )
    }
  }
}

@Composable
private fun AdminMetricCard(
  label: String,
  value: String,
  icon: ImageVector,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = modifier.testTag("admin_metric_${label.lowercase().replace(' ', '_')}")
  ) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(14.dp)
        )
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
private fun AdminWithdrawalRequestsSection(
  withdrawalRequests: List<DemoWithdrawalRequest>
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("admin_withdrawal_requests_section"),
    shape = RoundedCornerShape(20.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Withdrawal Requests (${withdrawalRequests.size})",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.primaryContainer
        ) {
          Text(
            text = "Demo / Request Only",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
          )
        }
      }

      if (withdrawalRequests.isEmpty()) {
        Text(
          text = "No withdrawal requests submitted yet.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      } else {
        withdrawalRequests.forEach { req ->
          val reqId = req.id.ifBlank { req.requestId }
          val dateStr =
            req.createdAt?.toDate()?.let {
              SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
            } ?: "Recently submitted"
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier =
              Modifier.fillMaxWidth()
                .testTag("admin_withdrawal_card_$reqId")
          ) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = req.userName.ifBlank { "TaskFlow User" },
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.testTag("admin_withdrawal_user_$reqId")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "Method: ${req.method} • Date: $dateStr",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("admin_withdrawal_method_$reqId")
                )
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                  shape = RoundedCornerShape(50),
                  color = MaterialTheme.colorScheme.secondaryContainer,
                  modifier = Modifier.testTag("admin_withdrawal_status_$reqId")
                ) {
                  Text(
                    text = "Demo / Pending",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                  )
                }
              }
              Text(
                text = formatRewardInr(req.amountCents),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag("admin_withdrawal_amount_$reqId")
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminTasksTabContent(
  tasks: List<TaskItem>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  onCreateClick: () -> Unit,
  onEditTask: (TaskItem) -> Unit,
  onToggleTaskActive: (TaskItem, Boolean) -> Unit,
  onDeleteTask: (String) -> Unit
) {
  var taskPendingDeletion by remember { mutableStateOf<TaskItem?>(null) }

  taskPendingDeletion?.let { targetTask ->
    AlertDialog(
      onDismissRequest = { taskPendingDeletion = null },
      modifier = Modifier.testTag("delete_task_confirm_dialog"),
      title = {
        Text(
          text = "Delete Task?",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Text(
          text =
            "Are you sure you want to delete \"${targetTask.title}\"? Existing user submissions, review records, and user history will be preserved.",
          style = MaterialTheme.typography.bodyMedium
        )
      },
      confirmButton = {
        Button(
          onClick = {
            val idToDelete = targetTask.id
            taskPendingDeletion = null
            onDeleteTask(idToDelete)
          },
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.error,
              contentColor = MaterialTheme.colorScheme.onError
            ),
          modifier = Modifier.testTag("confirm_delete_task_button")
        ) {
          Text("Delete")
        }
      },
      dismissButton = {
        TextButton(
          onClick = { taskPendingDeletion = null },
          modifier = Modifier.testTag("cancel_delete_task_button")
        ) {
          Text("Cancel")
        }
      }
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 88.dp)
  ) {
    if (withdrawalRequests.isNotEmpty()) {
      item {
        AdminWithdrawalRequestsSection(withdrawalRequests = withdrawalRequests)
      }
    }

    if (tasks.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          shape = RoundedCornerShape(24.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Icon(
              imageVector = Icons.Default.AdminPanelSettings,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(44.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "No Tasks Created Yet",
              style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "Create your first task with an App/Website name, HTTPS link, step-by-step instructions, and required proof details.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(18.dp))
            Button(
              onClick = onCreateClick,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("empty_state_create_task_button")
            ) {
              Icon(imageVector = Icons.Default.Add, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text("Create First Task")
            }
          }
        }
      }
    } else {
      items(tasks, key = { it.id }) { task ->
        val appOrSiteLabel = resolveAppOrWebsiteLabel(task)
        Card(
          modifier = Modifier.fillMaxWidth().testTag("admin_task_card_${task.id}"),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                StatusBadge(status = task.status, isActive = task.isActive)
                Surface(
                  shape = RoundedCornerShape(50),
                  color =
                    if (task.isActive) {
                      MaterialTheme.colorScheme.primaryContainer
                    } else {
                      MaterialTheme.colorScheme.errorContainer
                    },
                  modifier = Modifier.testTag("task_active_badge_${task.id}")
                ) {
                  Text(
                    text = if (task.isActive) "Active" else "Deactivated",
                    style = MaterialTheme.typography.labelSmall,
                    color =
                      if (task.isActive) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                      } else {
                        MaterialTheme.colorScheme.onErrorContainer
                      },
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                  )
                }
              }
              RewardBadge(rewardCents = task.rewardCents)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              AppIconThumbnail(
                appIconUrl = task.appIconUrl,
                appName = task.appName,
                category = task.category,
                size = 48.dp
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(text = task.title, style = MaterialTheme.typography.titleLarge)
                Text(
                  text = "$appOrSiteLabel • ${task.category}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
              text = task.description,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (task.instructions.isNotBlank()) {
              Spacer(modifier = Modifier.height(8.dp))
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    text = "STEP-BY-STEP INSTRUCTIONS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = task.instructions,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }

            if (task.requiredProof.isNotBlank()) {
              Spacer(modifier = Modifier.height(8.dp))
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(10.dp)) {
                  Text(
                    text = "REQUIRED PROOF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    fontWeight = FontWeight.Bold
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = task.requiredProof,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }

            if (task.taskLink.isNotBlank()) {
              Spacer(modifier = Modifier.height(10.dp))
              ClickableTaskLinkCard(
                url = task.taskLink,
                label = "TASK LINK",
                testTag = "admin_task_link_${task.id}"
              )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Assignee: ${task.assignedUserName}",
                  style = MaterialTheme.typography.labelLarge
                )
                Text(
                  text = "${formatVisibleUserEmail(task.assignedUserEmail)} • ${task.category}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                OutlinedButton(
                  onClick = { onToggleTaskActive(task, !task.isActive) },
                  shape = RoundedCornerShape(10.dp),
                  contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                  modifier = Modifier.testTag("toggle_task_active_${task.id}")
                ) {
                  Text(
                    text = if (task.isActive) "Deactivate" else "Activate",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                  )
                }
                if (task.status == TaskStatus.PENDING) {
                  IconButton(
                    onClick = { onEditTask(task) },
                    modifier = Modifier.testTag("edit_task_${task.id}")
                  ) {
                    Icon(
                      imageVector = Icons.Default.Edit,
                      contentDescription = "Edit Task",
                      tint = MaterialTheme.colorScheme.primary
                    )
                  }
                }
                IconButton(
                  onClick = { taskPendingDeletion = task },
                  modifier = Modifier.testTag("delete_task_${task.id}")
                ) {
                  Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Task",
                    tint = MaterialTheme.colorScheme.error
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminSubmissionsTabContent(
  tasks: List<TaskItem>,
  withdrawalRequests: List<DemoWithdrawalRequest>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onReviewSubmission: (TaskItem, Boolean, String) -> Unit
) {
  val filtered =
    remember(tasks, selectedFilter) {
      when (selectedFilter) {
        "all" -> tasks.filter { it.status != TaskStatus.PENDING }
        "withdrawals" -> emptyList()
        else -> tasks.filter { it.status == selectedFilter }
      }
    }

  val filterOptions =
    listOf(
      TaskStatus.SUBMITTED to "Needs Review (${tasks.count { it.status == TaskStatus.SUBMITTED }})",
      TaskStatus.APPROVED to "Approved (${tasks.count { it.status == TaskStatus.APPROVED }})",
      TaskStatus.REJECTED to "Rejected (${tasks.count { it.status == TaskStatus.REJECTED }})",
      "all" to "All Submissions (${tasks.count { it.status != TaskStatus.PENDING }})",
      "withdrawals" to "Withdrawal Requests (${withdrawalRequests.size})"
    )

  Column(modifier = Modifier.fillMaxSize()) {
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      filterOptions.forEach { (key, label) ->
        FilterChip(
          selected = selectedFilter == key,
          onClick = { onSelectFilter(key) },
          label = { Text(label) },
          modifier = Modifier.testTag("admin_submission_filter_$key")
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(14.dp),
      contentPadding = PaddingValues(bottom = 24.dp)
    ) {
      if (selectedFilter == "withdrawals" || withdrawalRequests.isNotEmpty()) {
        item {
          AdminWithdrawalRequestsSection(withdrawalRequests = withdrawalRequests)
        }
      }

      if (selectedFilter != "withdrawals") {
        if (filtered.isEmpty()) {
          item {
            Box(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "No submissions in this queue.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        } else {
          items(filtered, key = { it.id }) { task ->
            SubmissionReviewCard(
              task = task,
              onReview = { approve, feedback -> onReviewSubmission(task, approve, feedback) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SubmissionReviewCard(
  task: TaskItem,
  onReview: (Boolean, String) -> Unit
) {
  var feedbackInput by rememberSaveable(task.id) { mutableStateOf(task.adminFeedback) }
  var showFullSizeImage by rememberSaveable(task.id) { mutableStateOf(false) }
  val isReviewable = task.status == TaskStatus.SUBMITTED
  val submissionDateStr =
    task.updatedAt?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently submitted"
  val appOrSiteLabel = resolveAppOrWebsiteLabel(task)

  if (showFullSizeImage && task.proofImageUrl.isNotBlank()) {
    FullSizeImageDialog(
      imageUrl = task.proofImageUrl,
      title = "Proof Screenshot • ${task.title}",
      storagePath = task.proofStoragePath,
      onDismiss = { showFullSizeImage = false }
    )
  }

  Card(
    modifier = Modifier.fillMaxWidth().testTag("submission_review_card_${task.id}"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        StatusBadge(status = task.status)
        RewardBadge(rewardCents = task.rewardCents)
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        AppIconThumbnail(
          appIconUrl = task.appIconUrl,
          appName = task.appName,
          category = task.category,
          size = 46.dp
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(text = task.title, style = MaterialTheme.typography.titleLarge)
          Text(
            text = "$appOrSiteLabel • Submitted by ${task.assignedUserName} (${formatVisibleUserEmail(task.assignedUserEmail)})",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Submission Metadata: User Name, User UID, Task ID, and Submission Date/Time
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = "User Name: ${task.assignedUserName}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("review_user_name_${task.id}")
          )
          Text(
            text = "User UID: ${task.assignedUserId}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("review_user_uid_${task.id}")
          )
          Text(
            text = "Task ID: ${task.id}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("review_task_id_${task.id}")
          )
          Text(
            text = "Submitted: $submissionDateStr",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("review_submission_date_${task.id}")
          )
        }
      }

      if (task.taskLink.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        ClickableTaskLinkCard(
          url = task.taskLink,
          label = "TASK LINK",
          testTag = "review_task_link_${task.id}"
        )
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 1. Written Proof Section
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
            text = "1. WRITTEN TEXT PROOF",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = task.proofText.ifBlank { "(No proof text submitted)" },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("review_proof_text_${task.id}")
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 2. Uploaded Photo / Screenshot Proof Section
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "2. UPLOADED PHOTO / SCREENSHOT PROOF",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          if (task.proofImageUrl.isNotBlank()) {
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .height(190.dp)
                  .clip(RoundedCornerShape(12.dp))
                  .background(MaterialTheme.colorScheme.surface)
                  .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(12.dp)
                  )
                  .clickable { showFullSizeImage = true }
                  .testTag("review_proof_image_${task.id}"),
              contentAlignment = Alignment.Center
            ) {
              ProofImageView(
                imageSource = task.proofImageUrl,
                contentDescription = "Submitted proof screenshot",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
            }

            if (task.proofStoragePath.isNotBlank()) {
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Storage Ref: ${task.proofStoragePath}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("review_proof_storage_path_${task.id}")
              )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
              onClick = { showFullSizeImage = true },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth().testTag("view_full_image_${task.id}")
            ) {
              Icon(
                imageVector = Icons.Default.ZoomIn,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Inspect Full-Size Screenshot")
            }
          } else {
            Text(
              text = "(No proof image URL attached)",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = if (isReviewable) feedbackInput else task.adminFeedback,
        onValueChange = { if (isReviewable) feedbackInput = it },
        enabled = isReviewable,
        label = { Text("Admin Review Note (Optional)") },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().testTag("admin_feedback_input_${task.id}")
      )

      if (isReviewable) {
        Spacer(modifier = Modifier.height(14.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          OutlinedButton(
            onClick = { onReview(false, feedbackInput) },
            shape = RoundedCornerShape(12.dp),
            colors =
              ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
              ),
            modifier = Modifier.weight(1f).testTag("reject_submission_${task.id}")
          ) {
            Icon(
              imageVector = Icons.Default.Close,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Reject")
          }

          Button(
            onClick = { onReview(true, feedbackInput) },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1.3f).testTag("approve_submission_${task.id}")
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Approve & Credit")
          }
        }
      }
    }
  }
}

@Composable
private fun AdminUsersTabContent(
  users: List<UserProfile>,
  onAssignTaskToUser: (UserProfile) -> Unit,
  onToggleUserStatus: (UserProfile) -> Unit,
  onUpdateUserStatus: ((UserProfile, String) -> Unit)? = null
) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var selectedFilter by rememberSaveable { mutableStateOf("ALL") }
  var selectedUserForDetail by remember { mutableStateOf<UserProfile?>(null) }
  var pendingStatusChange by remember { mutableStateOf<Pair<UserProfile, String>?>(null) }

  val pendingCount = remember(users) {
    users.count { it.approvalStatus.equals("pending", ignoreCase = true) }
  }
  val approvedCount = remember(users) {
    users.count { it.approvalStatus.equals("approved", ignoreCase = true) }
  }
  val suspendedCount = remember(users) {
    users.count { it.approvalStatus.equals("suspended", ignoreCase = true) }
  }

  val filteredUsers = remember(users, searchQuery, selectedFilter) {
    users.filter { user ->
      val matchesFilter = when (selectedFilter) {
        "PENDING" -> user.approvalStatus.equals("pending", ignoreCase = true)
        "APPROVED" -> user.approvalStatus.equals("approved", ignoreCase = true)
        "SUSPENDED" -> user.approvalStatus.equals("suspended", ignoreCase = true)
        else -> true
      }
      val matchesSearch = if (searchQuery.isBlank()) {
        true
      } else {
        val query = searchQuery.trim().lowercase(Locale.US)
        user.displayName.lowercase(Locale.US).contains(query) ||
          user.email.lowercase(Locale.US).contains(query) ||
          user.userId.lowercase(Locale.US).contains(query)
      }
      matchesFilter && matchesSearch
    }
  }

  // Confirmation Dialog before modifying user approval status
  pendingStatusChange?.let { (user, targetStatus) ->
    AlertDialog(
      onDismissRequest = { pendingStatusChange = null },
      modifier = Modifier.testTag("admin_user_confirm_dialog"),
      title = {
        Text(
          text = "Confirm Status Change",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        Text(
          text = "Are you sure you want to change ${user.displayName.ifBlank { "this user" }} status to ${targetStatus.uppercase(Locale.US)}?\n\nEmail: ${user.email}",
          style = MaterialTheme.typography.bodyMedium
        )
      },
      confirmButton = {
        Button(
          onClick = {
            if (onUpdateUserStatus != null) {
              onUpdateUserStatus(user, targetStatus)
            } else {
              onToggleUserStatus(user)
            }
            pendingStatusChange = null
            if (selectedUserForDetail?.id == user.id || selectedUserForDetail?.userId == user.userId) {
              selectedUserForDetail = null
            }
          },
          modifier = Modifier.testTag("admin_user_confirm_status_btn")
        ) {
          Text("Confirm ${targetStatus.replaceFirstChar { it.uppercase() }}")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingStatusChange = null }) {
          Text("Cancel")
        }
      }
    )
  }

  // User Detail Dialog
  selectedUserForDetail?.let { user ->
    val uid = user.userId.ifBlank { user.id }
    val formattedDate =
      (user.createdAt ?: user.updatedAt)?.toDate()?.let {
        SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
      } ?: "Recently registered"

    AlertDialog(
      onDismissRequest = { selectedUserForDetail = null },
      modifier = Modifier.testTag("admin_user_detail_dialog"),
      title = {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Box(
            modifier =
              Modifier.size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = (user.displayName.firstOrNull() ?: 'U').uppercaseChar().toString(),
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onPrimaryContainer
            )
          }
          Column {
            Text(
              text = user.displayName.ifBlank { "User Profile" },
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = user.email,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          AdminDetailInfoRow(label = "User ID", value = uid)
          AdminDetailInfoRow(label = "Role", value = user.role.uppercase(Locale.US))
          AdminDetailInfoRow(label = "Approval Status", value = user.approvalStatus.uppercase(Locale.US))
          AdminDetailInfoRow(label = "Available Balance", value = formatRewardInr(user.balanceCents))
          AdminDetailInfoRow(label = "Completed Tasks", value = "${user.completedTasksCount}")
          if (user.assignedTaskId.isNotBlank()) {
            AdminDetailInfoRow(label = "Assigned Task ID", value = user.assignedTaskId)
          }
          AdminDetailInfoRow(label = "Registered At", value = formattedDate)

          Spacer(modifier = Modifier.height(8.dp))

          Text(
            text = "Status Actions:",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            if (user.approvalStatus != "approved") {
              Button(
                onClick = { pendingStatusChange = Pair(user, "approved") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).testTag("dialog_approve_user_btn")
              ) {
                Text("Approve")
              }
            }
            if (user.approvalStatus != "suspended") {
              OutlinedButton(
                onClick = { pendingStatusChange = Pair(user, "suspended") },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).testTag("dialog_suspend_user_btn")
              ) {
                Text("Suspend")
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = { selectedUserForDetail = null }) {
          Text("Close")
        }
      }
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize().testTag("admin_users_tab_content"),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 24.dp)
  ) {
    // 1. Search Bar
    item {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("Search users by name, email or ID...") },
        leadingIcon = {
          Icon(Icons.Default.Search, contentDescription = "Search Users")
        },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Default.Close, contentDescription = "Clear search")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().testTag("admin_user_search_input")
      )
    }

    // 2. Filter Tabs (Pending, Approved, Suspended, All)
    item {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        FilterChip(
          selected = selectedFilter == "ALL",
          onClick = { selectedFilter = "ALL" },
          label = { Text("All (${users.size})") },
          modifier = Modifier.testTag("admin_user_filter_all")
        )
        FilterChip(
          selected = selectedFilter == "PENDING",
          onClick = { selectedFilter = "PENDING" },
          label = { Text("Pending ($pendingCount)") },
          modifier = Modifier.testTag("admin_user_filter_pending")
        )
        FilterChip(
          selected = selectedFilter == "APPROVED",
          onClick = { selectedFilter = "APPROVED" },
          label = { Text("Approved ($approvedCount)") },
          modifier = Modifier.testTag("admin_user_filter_approved")
        )
        FilterChip(
          selected = selectedFilter == "SUSPENDED",
          onClick = { selectedFilter = "SUSPENDED" },
          label = { Text("Suspended ($suspendedCount)") },
          modifier = Modifier.testTag("admin_user_filter_suspended")
        )
      }
    }

    // 3. User Cards or Empty State
    if (filteredUsers.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
          )
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.People,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(40.dp)
            )
            Text(
              text = if (searchQuery.isNotBlank()) "No users match '$searchQuery'" else "No users in this category",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              textAlign = TextAlign.Center
            )
            Text(
              text = "Try switching the filter tab or clearing your search query.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center
            )
          }
        }
      }
    } else {
      items(filteredUsers, key = { it.userId.ifBlank { it.id } }) { user ->
        val uid = user.userId.ifBlank { user.id }
        val statusUpper = user.approvalStatus.uppercase(Locale.US)
        val statusColor = when (user.approvalStatus.lowercase(Locale.US)) {
          "approved" -> androidx.compose.ui.graphics.Color(0xFF16A34A)
          "suspended" -> MaterialTheme.colorScheme.error
          else -> androidx.compose.ui.graphics.Color(0xFFD97706)
        }
        val statusBg = statusColor.copy(alpha = 0.12f)

        Card(
          modifier =
            Modifier.fillMaxWidth()
              .clip(RoundedCornerShape(20.dp))
              .clickable { selectedUserForDetail = user }
              .testTag("admin_user_card_$uid"),
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
              ) {
                Box(
                  modifier =
                    Modifier.size(44.dp)
                      .clip(CircleShape)
                      .background(MaterialTheme.colorScheme.primaryContainer),
                  contentAlignment = Alignment.Center
                ) {
                  Text(
                    text = (user.displayName.firstOrNull() ?: 'U').uppercaseChar().toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                  )
                }
                Column {
                  Text(
                    text = user.displayName.ifBlank { "User" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = formatVisibleUserEmail(user.email, user.role),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
              RewardBadge(rewardCents = user.balanceCents)
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Surface(
                  shape = RoundedCornerShape(50),
                  color = statusBg
                ) {
                  Text(
                    text = statusUpper,
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                  )
                }
                Surface(
                  shape = RoundedCornerShape(50),
                  color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                  Text(
                    text = user.role.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                  )
                }
              }

              Text(
                text = "Completed: ${user.completedTasksCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              // Status Toggle Button with Confirmation
              when (user.approvalStatus.lowercase(Locale.US)) {
                "approved" -> {
                  OutlinedButton(
                    onClick = { pendingStatusChange = Pair(user, "suspended") },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                      contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f).testTag("toggle_user_status_$uid")
                  ) {
                    Text("Suspend")
                  }
                }
                "suspended" -> {
                  Button(
                    onClick = { pendingStatusChange = Pair(user, "approved") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("toggle_user_status_$uid")
                  ) {
                    Text("Reactivate")
                  }
                }
                else -> {
                  // Pending
                  Button(
                    onClick = { pendingStatusChange = Pair(user, "approved") },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("toggle_user_status_$uid")
                  ) {
                    Text("Approve")
                  }
                  OutlinedButton(
                    onClick = { pendingStatusChange = Pair(user, "suspended") },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                      contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.weight(1f).testTag("reject_user_status_$uid")
                  ) {
                    Text("Reject")
                  }
                }
              }

              Button(
                onClick = { onAssignTaskToUser(user) },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1.1f).testTag("assign_task_to_user_$uid")
              ) {
                Icon(
                  imageVector = Icons.Default.Add,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Assign Task")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AdminDetailInfoRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold
    )
  }
}

/**
 * Admin Create / Edit Task Dialog supporting all 9 fields:
 * 1. Task Title
 * 2. App Name or Website Name
 * 3. Optional App Icon / Image (HTTPS URL or Gallery Picker)
 * 4. Category
 * 5. Reward Amount (INR ₹)
 * 6. Download / Website URL
 * 7. Task Overview
 * 8. Step-by-Step Instructions
 * 9. Required Proof Description
 */
@Composable
private fun TaskEditorDialog(
  existingTask: TaskItem?,
  registeredUsers: List<UserProfile>,
  defaultAssignee: UserProfile?,
  onDismiss: () -> Unit,
  onSave: (
    title: String,
    description: String,
    category: String,
    rewardInr: String,
    assignee: UserProfile,
    instructions: String,
    taskLink: String,
    appName: String,
    appIconUrl: String,
    requiredProof: String
  ) -> Unit
) {
  val context = LocalContext.current
  var title by rememberSaveable { mutableStateOf(existingTask?.title ?: "") }
  var appName by rememberSaveable { mutableStateOf(existingTask?.appName ?: "") }
  var appIconUrl by rememberSaveable { mutableStateOf(existingTask?.appIconUrl ?: "") }
  var taskLink by rememberSaveable { mutableStateOf(existingTask?.taskLink ?: "") }
  var category by rememberSaveable {
    mutableStateOf(existingTask?.category ?: TaskCategories.ALL.first())
  }
  var rewardInr by rememberSaveable {
    mutableStateOf(
      existingTask?.let { String.format(Locale.US, "%.2f", it.rewardCents / 100.0) } ?: "15.00"
    )
  }
  var description by rememberSaveable { mutableStateOf(existingTask?.description ?: "") }
  var instructions by rememberSaveable { mutableStateOf(existingTask?.instructions ?: "") }
  var requiredProof by rememberSaveable {
    mutableStateOf(
      existingTask?.requiredProof
        ?: "Submit written completion notes and a clear screenshot showing the completed task."
    )
  }

  val iconPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
      if (uri != null) {
        val dataUri =
          encodeGalleryImageAsDataUri(context, uri, maxDimensionPx = 256, initialQuality = 75)
        appIconUrl = dataUri ?: uri.toString()
      }
    }

  var selectedAssignee by remember {
    mutableStateOf(
      existingTask?.let { t ->
        registeredUsers.firstOrNull { (it.userId.ifBlank { it.id }) == t.assignedUserId }
      } ?: defaultAssignee ?: registeredUsers.first()
    )
  }

  val isLinkValid = taskLink.isBlank() || isValidTaskUrl(taskLink)
  val isIconValid = isValidImageUrlOrDataUri(appIconUrl)
  val isRewardValid = (rewardInr.trim().toDoubleOrNull() ?: 0.0) > 0.0
  val canSave =
    title.isNotBlank() &&
      description.isNotBlank() &&
      isRewardValid &&
      isLinkValid &&
      isIconValid

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (existingTask == null) "Create & Publish Task" else "Edit Task",
        style = MaterialTheme.typography.titleLarge
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        // 1. Task Title
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Task Title") },
          placeholder = { Text("e.g., Install & Review App on Google Play") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_title_input")
        )

        // 2. App Name or Website Name
        OutlinedTextField(
          value = appName,
          onValueChange = { appName = it },
          label = { Text("App Name or Website Name") },
          placeholder = { Text("e.g., TaskFlow Companion / Acme Store") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_app_name_input")
        )

        // 3. Optional App Icon / Image
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          AppIconThumbnail(
            appIconUrl = appIconUrl,
            appName = appName,
            category = category,
            size = 48.dp
          )
          Column(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
              value =
                if (appIconUrl.startsWith("data:image/")) {
                  "(Gallery Image Attached)"
                } else {
                  appIconUrl
                },
              onValueChange = {
                if (!it.startsWith("(Gallery")) {
                  appIconUrl = it
                }
              },
              label = { Text("App Icon URL (Optional HTTPS)") },
              placeholder = { Text("https://example.com/icon.png") },
              singleLine = true,
              isError = !isIconValid,
              modifier = Modifier.fillMaxWidth().testTag("task_modal_app_icon_input")
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(
                onClick = {
                  iconPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                  )
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.testTag("task_modal_pick_icon_button")
              ) {
                Icon(
                  imageVector = Icons.Default.AddPhotoAlternate,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Gallery Icon", style = MaterialTheme.typography.labelSmall)
              }
              if (appIconUrl.isNotBlank()) {
                TextButton(
                  onClick = { appIconUrl = "" },
                  contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Text("Clear Icon", style = MaterialTheme.typography.labelSmall)
                }
              }
            }
          }
        }

        // 4. Task Category
        Text(
          text = "Task Category",
          style = MaterialTheme.typography.labelLarge
        )
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          TaskCategories.ALL.forEach { cat ->
            FilterChip(
              selected = category == cat,
              onClick = { category = cat },
              label = { Text(cat) },
              modifier = Modifier.testTag("task_modal_category_$cat")
            )
          }
        }

        // 5. Reward Amount (INR ₹)
        OutlinedTextField(
          value = rewardInr,
          onValueChange = { rewardInr = it },
          label = { Text("Reward Amount (INR ₹)") },
          singleLine = true,
          isError = rewardInr.isNotBlank() && !isRewardValid,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_reward_input")
        )

        // 6. Download / Website URL
        OutlinedTextField(
          value = taskLink,
          onValueChange = { taskLink = it },
          label = { Text("Download / Website Link (HTTPS URL)") },
          placeholder = { Text("https://play.google.com/store/apps/details?id=...") },
          singleLine = true,
          isError = taskLink.isNotBlank() && !isLinkValid,
          supportingText = {
            if (taskLink.isNotBlank() && !isLinkValid) {
              Text(
                text = "Enter a valid HTTPS URL starting with https:// (e.g., Google Play Store or website link).",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("task_modal_link_error")
              )
            } else {
              Text("Supports Google Play Store links, website links, or any valid https:// URL.")
            }
          },
          modifier = Modifier.fillMaxWidth().testTag("task_modal_link_input")
        )

        // 7. Task Overview / Description
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Task Overview / Description") },
          placeholder = { Text("Summarize the goal and overview of this task…") },
          minLines = 2,
          maxLines = 5,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_desc_input")
        )

        // 8. Step-by-Step Instructions
        OutlinedTextField(
          value = instructions,
          onValueChange = { instructions = it },
          label = { Text("Step-by-Step Instructions") },
          placeholder = {
            Text(
              "1. Tap the task link above\n2. Complete the action\n3. Take a screenshot & submit written + photo proof…"
            )
          },
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_instructions_input")
        )

        // 9. Required Proof Description
        OutlinedTextField(
          value = requiredProof,
          onValueChange = { requiredProof = it },
          label = { Text("Required Proof Description") },
          placeholder = {
            Text("Specify what written details and screenshot the user must submit…")
          },
          minLines = 2,
          maxLines = 4,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_required_proof_input")
        )

        Text(
          text = "Initial Highlighted Assignee (All users can view & complete)",
          style = MaterialTheme.typography.labelLarge
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          registeredUsers.forEach { user ->
            val uid = user.userId.ifBlank { user.id }
            val isSelected = (selectedAssignee.userId.ifBlank { selectedAssignee.id }) == uid
            Surface(
              shape = RoundedCornerShape(12.dp),
              color =
                if (isSelected) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
              modifier =
                Modifier.fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .clickable { selectedAssignee = user }
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = user.displayName,
                    style = MaterialTheme.typography.labelLarge
                  )
                  Text(
                    text = formatVisibleUserEmail(user.email, user.role),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                if (isSelected) {
                  Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                  )
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          onSave(
            title.trim(),
            description.trim(),
            category,
            rewardInr.trim(),
            selectedAssignee,
            instructions.trim().ifBlank { description.trim() },
            taskLink.trim(),
            appName.trim(),
            appIconUrl.trim(),
            requiredProof.trim()
          )
        },
        enabled = canSave,
        modifier = Modifier.testTag("task_modal_save_button")
      ) {
        Text(if (existingTask == null) "Create Task" else "Save Changes")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun UnauthorizedAdminGate(currentUserEmail: String) {
  Box(
    modifier = Modifier.fillMaxSize().padding(24.dp),
    contentAlignment = Alignment.Center
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = Icons.Default.Lock,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.error,
          modifier = Modifier.size(44.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Admin Access Restricted",
          style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
          text =
            "Signed in as ${formatVisibleUserEmail(currentUserEmail)}.\n\n" +
              "TaskFlow's zero-trust Firestore rules restrict task creation, submission approval, and user management to verified administrator accounts registered in /admins/{adminId}.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

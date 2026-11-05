package com.example.ui.admin

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
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RateReview
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.TaskCategories
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.formatVisibleUserEmail
import com.example.data.model.isValidTaskUrl
import com.example.ui.components.ClickableTaskLinkCard
import com.example.ui.components.FullSizeImageDialog
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
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
  onToggleUserStatus: (UserProfile) -> Unit
) {
  if (!isAdminAuthorized) {
    UnauthorizedAdminGate(currentUserEmail = currentUserEmail)
    return
  }

  val tasks = (adminTasksState as? UiState.Success)?.data ?: emptyList()
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
      onSave = { title, desc, cat, rewardStr, assignee, instructions, taskLink ->
        val target = editingTask
        if (target == null) {
          onCreateTask(title, desc, cat, rewardStr, assignee, instructions, taskLink)
        } else {
          onUpdateTask(target.id, title, desc, cat, rewardStr, assignee, instructions, taskLink)
        }
        showTaskModal = false
        editingTask = null
        preselectedAssignee = null
      }
    )
  }

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
      // Top Summary Row
      AdminMetricsBanner(
        totalTasks = tasks.size,
        pendingReviews = tasks.count { it.status == TaskStatus.SUBMITTED },
        totalUsers = registeredUsers.size
      )

      Spacer(modifier = Modifier.height(16.dp))

      when (activeTab) {
        AdminTab.TASKS -> {
          AdminTasksTabContent(
            tasks = tasks,
            onCreateClick = {
              editingTask = null
              preselectedAssignee = null
              showTaskModal = true
            },
            onEditTask = { task ->
              editingTask = task
              showTaskModal = true
            },
            onDeleteTask = onDeleteTask
          )
        }
        AdminTab.SUBMISSIONS -> {
          AdminSubmissionsTabContent(
            tasks = tasks,
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
            onToggleUserStatus = onToggleUserStatus
          )
        }
      }
    }
  }
}

@Composable
private fun AdminMetricsBanner(
  totalTasks: Int,
  pendingReviews: Int,
  totalUsers: Int
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(10.dp)
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
      label = "USERS",
      value = totalUsers.toString(),
      icon = Icons.Default.People,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
private fun AdminMetricCard(
  label: String,
  value: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    modifier = modifier
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp)
        )
        Text(
          text = label,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
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
private fun AdminTasksTabContent(
  tasks: List<TaskItem>,
  onCreateClick: () -> Unit,
  onEditTask: (TaskItem) -> Unit,
  onDeleteTask: (String) -> Unit
) {
  if (tasks.isEmpty()) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
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
          text = "Create your first task with an HTTPS link and step-by-step instructions to test the end-to-end submission and approval workflow.",
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
  } else {
    LazyColumn(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(bottom = 88.dp)
    ) {
      items(tasks, key = { it.id }) { task ->
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
              StatusBadge(status = task.status)
              RewardBadge(rewardCents = task.rewardCents)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = task.title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
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
                    color = MaterialTheme.colorScheme.primary
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
              if (task.status == TaskStatus.PENDING) {
                Row {
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
                  IconButton(
                    onClick = { onDeleteTask(task.id) },
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
}

@Composable
private fun AdminSubmissionsTabContent(
  tasks: List<TaskItem>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onReviewSubmission: (TaskItem, Boolean, String) -> Unit
) {
  val filtered =
    remember(tasks, selectedFilter) {
      when (selectedFilter) {
        "all" -> tasks.filter { it.status != TaskStatus.PENDING }
        else -> tasks.filter { it.status == selectedFilter }
      }
    }

  val filterOptions =
    listOf(
      TaskStatus.SUBMITTED to "Needs Review (${tasks.count { it.status == TaskStatus.SUBMITTED }})",
      TaskStatus.APPROVED to "Approved (${tasks.count { it.status == TaskStatus.APPROVED }})",
      TaskStatus.REJECTED to "Rejected (${tasks.count { it.status == TaskStatus.REJECTED }})",
      "all" to "All Submissions"
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

    if (filtered.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Text(
          text = "No submissions in this queue.",
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
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

      Spacer(modifier = Modifier.height(10.dp))

      Text(text = task.title, style = MaterialTheme.typography.titleLarge)
      Text(
        text = "Submitted by ${task.assignedUserName} (${formatVisibleUserEmail(task.assignedUserEmail)})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(8.dp))

      // Submission Metadata: User UID, Task ID, and Submission Date
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
              AsyncImage(
                model = task.proofImageUrl,
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
  onToggleUserStatus: (UserProfile) -> Unit
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    contentPadding = PaddingValues(bottom = 24.dp)
  ) {
    items(users, key = { it.userId.ifBlank { it.id } }) { user ->
      val uid = user.userId.ifBlank { user.id }
      Card(
        modifier = Modifier.fillMaxWidth().testTag("admin_user_card_$uid"),
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
                Text(text = user.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                  text = formatVisibleUserEmail(user.email, user.role),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
            RewardBadge(rewardCents = user.balanceCents)
          }

          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text =
                "Role: ${user.role.uppercase(Locale.US)} • Status: ${user.approvalStatus.uppercase(Locale.US)} • Completed: ${user.completedTasksCount}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            OutlinedButton(
              onClick = { onToggleUserStatus(user) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f).testTag("toggle_user_status_$uid")
            ) {
              Text(if (user.approvalStatus == "approved") "Suspend" else "Approve User")
            }
            Button(
              onClick = { onAssignTaskToUser(user) },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1.2f).testTag("assign_task_to_user_$uid")
            ) {
              Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Assign Task")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TaskEditorDialog(
  existingTask: TaskItem?,
  registeredUsers: List<UserProfile>,
  defaultAssignee: UserProfile?,
  onDismiss: () -> Unit,
  onSave: (String, String, String, String, UserProfile, String, String) -> Unit
) {
  var title by rememberSaveable { mutableStateOf(existingTask?.title ?: "") }
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

  var selectedAssignee by remember {
    mutableStateOf(
      existingTask?.let { t ->
        registeredUsers.firstOrNull { (it.userId.ifBlank { it.id }) == t.assignedUserId }
      } ?: defaultAssignee ?: registeredUsers.first()
    )
  }

  val isLinkValid = taskLink.isBlank() || isValidTaskUrl(taskLink)
  val isRewardValid = (rewardInr.trim().toDoubleOrNull() ?: 0.0) > 0.0
  val canSave =
    title.isNotBlank() &&
      description.isNotBlank() &&
      isRewardValid &&
      isLinkValid

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (existingTask == null) "Create & Assign Task" else "Edit Task",
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

        // 2. Task Link (URL)
        OutlinedTextField(
          value = taskLink,
          onValueChange = { taskLink = it },
          label = { Text("Task Link (HTTPS URL)") },
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

        // 3. Task Category
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

        // 4. Reward Amount (INR ₹)
        OutlinedTextField(
          value = rewardInr,
          onValueChange = { rewardInr = it },
          label = { Text("Reward Amount (INR ₹)") },
          singleLine = true,
          isError = rewardInr.isNotBlank() && !isRewardValid,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_reward_input")
        )

        // 5. Task Description
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Task Description") },
          placeholder = { Text("Summarize the goal and overview of this task…") },
          minLines = 2,
          maxLines = 5,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_desc_input")
        )

        // 6. Step-by-step Instructions
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

        Text(
          text = "Assign to Registered User",
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
            taskLink.trim()
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

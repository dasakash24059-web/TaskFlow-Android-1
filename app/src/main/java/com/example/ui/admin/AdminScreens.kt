package com.example.ui.admin

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Task
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.TaskCategories
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.formatRewardUsd
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
import com.example.ui.viewmodel.AdminTab
import com.example.ui.viewmodel.UiState
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
  onCreateTask: (String, String, String, String, UserProfile) -> Unit,
  onUpdateTask: (String, String, String, String, String, UserProfile) -> Unit,
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
      onSave = { title, desc, cat, rewardStr, assignee ->
        val target = editingTask
        if (target == null) {
          onCreateTask(title, desc, cat, rewardStr, assignee)
        } else {
          onUpdateTask(target.id, title, desc, cat, rewardStr, assignee)
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
          text = "Create your first task and assign it to a registered user to test the end-to-end submission and approval workflow.",
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
                  text = "${task.assignedUserEmail} • ${task.category}",
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
  val isReviewable = task.status == TaskStatus.SUBMITTED

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
        text = "Submitted by ${task.assignedUserName} (${task.assignedUserEmail})",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(12.dp))

      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(14.dp)) {
          Text(
            text = "USER PROOF SUBMISSION (TEXT-ONLY)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = task.proofText.ifBlank { "(No proof text submitted)" },
            style = MaterialTheme.typography.bodyLarge
          )
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
                  text = user.email,
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
  onSave: (String, String, String, String, UserProfile) -> Unit
) {
  var title by rememberSaveable { mutableStateOf(existingTask?.title ?: "") }
  var description by rememberSaveable { mutableStateOf(existingTask?.description ?: "") }
  var category by rememberSaveable {
    mutableStateOf(existingTask?.category ?: TaskCategories.ALL.first())
  }
  var rewardDollars by rememberSaveable {
    mutableStateOf(
      existingTask?.let { String.format(Locale.US, "%.2f", it.rewardCents / 100.0) } ?: "15.00"
    )
  }
  var selectedAssignee by remember {
    mutableStateOf(
      existingTask?.let { t ->
        registeredUsers.firstOrNull { (it.userId.ifBlank { it.id }) == t.assignedUserId }
      } ?: defaultAssignee ?: registeredUsers.first()
    )
  }

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
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Task Title") },
          placeholder = { Text("e.g., Verify Mobile Checkout Flow") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_title_input")
        )

        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text("Description & Acceptance Criteria") },
          placeholder = { Text("Describe the exact steps and required text proof…") },
          minLines = 3,
          maxLines = 6,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_desc_input")
        )

        OutlinedTextField(
          value = rewardDollars,
          onValueChange = { rewardDollars = it },
          label = { Text("Reward Amount (USD $)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("task_modal_reward_input")
        )

        Text(
          text = "Category",
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
              label = { Text(cat) }
            )
          }
        }

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
                    text = user.email,
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
          onSave(title, description, category, rewardDollars, selectedAssignee)
        },
        enabled = title.isNotBlank() && description.isNotBlank(),
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
            "Signed in as $currentUserEmail.\n\n" +
              "TaskFlow's zero-trust Firestore rules restrict task creation, submission approval, and user management to the verified admin account ($BOOTSTRAPPED_ADMIN_EMAIL) or UIDs registered in /admins/{adminId}.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center
        )
      }
    }
  }
}

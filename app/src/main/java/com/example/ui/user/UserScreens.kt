package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
import com.example.ui.components.TaskItemCard
import com.example.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun UserHomeScreen(
  profileState: UiState<UserProfile?>,
  tasksState: UiState<List<TaskItem>>,
  isAdminAuthorized: Boolean,
  onSelectTask: (String) -> Unit,
  onNavigateToTracking: (String) -> Unit,
  onSwitchToAdmin: () -> Unit
) {
  val profile = (profileState as? UiState.Success)?.data
  val tasks = (tasksState as? UiState.Success)?.data ?: emptyList()

  // Currently assigned task: prioritize the highlighted assignedTaskId if pending, or most recent pending task
  val currentlyAssignedTask =
    remember(tasks, profile?.assignedTaskId) {
      tasks.firstOrNull {
        it.id == profile?.assignedTaskId && it.status == TaskStatus.PENDING
      } ?: tasks.firstOrNull { it.status == TaskStatus.PENDING }
        ?: tasks.firstOrNull { it.status == TaskStatus.SUBMITTED }
    }

  val pendingCount = tasks.count { it.status == TaskStatus.PENDING }
  val submittedCount = tasks.count { it.status == TaskStatus.SUBMITTED }
  val approvedCount = tasks.count { it.status == TaskStatus.APPROVED }
  val rejectedCount = tasks.count { it.status == TaskStatus.REJECTED }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(18.dp)
  ) {
    // 1. User Balance & Greeting Hero Banner
    item {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
              Brush.linearGradient(
                colors =
                  listOf(
                    Color(0xFF0B132B),
                    Color(0xFF1C2541),
                    Color(0xFF064E3B)
                  )
              )
            )
      ) {
        Canvas(modifier = Modifier.matchParentSize()) {
          drawCircle(
            color = Color(0xFF34D399).copy(alpha = 0.14f),
            radius = size.height * 0.7f,
            center = Offset(size.width * 0.9f, size.height * 0.15f)
          )
        }
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "WELCOME BACK",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF34D399)
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = profile?.displayName?.ifBlank { "TaskFlow Member" } ?: "TaskFlow Member",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
              )
            }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color.White.copy(alpha = 0.1f)
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.AccountBalanceWallet,
                  contentDescription = "Balance",
                  tint = Color(0xFF34D399),
                  modifier = Modifier.size(20.dp)
                )
                Column {
                  Text(
                    text = "EARNED REWARDS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                  )
                  Text(
                    text = formatRewardInr(profile?.balanceCents ?: 0L),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(18.dp))

          // 4 Status Pills inside Hero
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            QuickStatChip(
              label = "Pending",
              count = pendingCount,
              color = Color(0xFFFBBF24),
              onClick = { onNavigateToTracking(TaskStatus.PENDING) },
              modifier = Modifier.weight(1f)
            )
            QuickStatChip(
              label = "Submitted",
              count = submittedCount,
              color = Color(0xFF38BDF8),
              onClick = { onNavigateToTracking(TaskStatus.SUBMITTED) },
              modifier = Modifier.weight(1f)
            )
            QuickStatChip(
              label = "Approved",
              count = approvedCount,
              color = Color(0xFF34D399),
              onClick = { onNavigateToTracking(TaskStatus.APPROVED) },
              modifier = Modifier.weight(1f)
            )
            QuickStatChip(
              label = "Rejected",
              count = rejectedCount,
              color = Color(0xFFFB7185),
              onClick = { onNavigateToTracking(TaskStatus.REJECTED) },
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    // 2. Currently Assigned Task Section
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = stringResource(R.string.currently_assigned_task),
          style = MaterialTheme.typography.titleLarge
        )
        TextButton(
          onClick = { onNavigateToTracking("all") },
          modifier = Modifier.testTag("view_all_tracking_button")
        ) {
          Text("Track All (${tasks.size})")
        }
      }
    }

    when (tasksState) {
      is UiState.Loading -> {
        item {
          Box(
            modifier = Modifier.fillMaxWidth().padding(36.dp),
            contentAlignment = Alignment.Center
          ) {
            CircularProgressIndicator()
          }
        }
      }
      is UiState.Error -> {
        item {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth()
          ) {
            Text(
              text = tasksState.message,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(16.dp)
            )
          }
        }
      }
      is UiState.Success -> {
        if (currentlyAssignedTask != null) {
          item {
            HighlightedAssignedTaskCard(
              task = currentlyAssignedTask,
              onOpenTask = { onSelectTask(currentlyAssignedTask.id) }
            )
          }
        } else {
          item {
            EmptyAssignedTaskCard(
              isAdminAuthorized = isAdminAuthorized,
              onSwitchToAdmin = onSwitchToAdmin
            )
          }
        }

        // Additional Assigned Tasks List (if more than 1)
        val otherTasks = tasks.filter { it.id != currentlyAssignedTask?.id }
        if (otherTasks.isNotEmpty()) {
          item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Other Assigned Tasks",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          items(otherTasks, key = { it.id }) { task ->
            TaskItemCard(
              task = task,
              onClick = { onSelectTask(task.id) }
            )
          }
        }
      }
    }
  }
}

@Composable
private fun QuickStatChip(
  label: String,
  count: Int,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = Color.White.copy(alpha = 0.08f),
    modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Text(
        text = count.toString(),
        style = MaterialTheme.typography.titleLarge,
        color = color,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFCBD5E1)
      )
    }
  }
}

@Composable
private fun HighlightedAssignedTaskCard(
  task: TaskItem,
  onOpenTask: () -> Unit
) {
  Card(
    modifier =
      Modifier.fillMaxWidth()
        .border(
          width = 1.5.dp,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
          shape = RoundedCornerShape(24.dp)
        )
        .testTag("highlighted_assigned_task_card"),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
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

      Spacer(modifier = Modifier.height(14.dp))

      Text(
        text = task.category.uppercase(Locale.US),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.secondary
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = task.title,
        style = MaterialTheme.typography.headlineMedium
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = task.description,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      Spacer(modifier = Modifier.height(20.dp))

      Button(
        onClick = onOpenTask,
        shape = RoundedCornerShape(14.dp),
        modifier =
          Modifier.fillMaxWidth()
            .height(50.dp)
            .testTag("open_assigned_task_button")
      ) {
        Icon(
          imageVector =
            if (task.status == TaskStatus.PENDING) Icons.AutoMirrored.Filled.Send else Icons.Default.TaskAlt,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            if (task.status == TaskStatus.PENDING) {
              "View Details & Submit Proof"
            } else {
              "View Submission Status"
            }
        )
      }
    }
  }
}

@Composable
private fun EmptyAssignedTaskCard(
  isAdminAuthorized: Boolean,
  onSwitchToAdmin: () -> Unit
) {
  Card(
    modifier = Modifier.fillMaxWidth().testTag("empty_assigned_task_card"),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(28.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      Box(
        modifier =
          Modifier.size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.Assignment,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(32.dp)
        )
      }
      Spacer(modifier = Modifier.height(14.dp))
      Text(
        text = stringResource(R.string.no_assigned_task_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(6.dp))
      Text(
        text = stringResource(R.string.no_assigned_task_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
      )
      if (isAdminAuthorized) {
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedButton(
          onClick = onSwitchToAdmin,
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("switch_to_admin_create_task_button")
        ) {
          Icon(
            imageVector = Icons.Default.AdminPanelSettings,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text("Open Admin Dashboard to Create & Assign a Task")
        }
      }
    }
  }
}

@Composable
fun UserTaskDetailScreen(
  task: TaskItem,
  isBusy: Boolean,
  onBack: () -> Unit,
  onSubmitProof: (String) -> Unit,
  userApprovalStatus: String = "approved"
) {
  BackHandler(onBack = onBack)

  val isUserApproved = userApprovalStatus == "approved"
  var proofInput by rememberSaveable(task.id) { mutableStateOf(task.proofText) }
  val createdDateStr =
    task.createdAt?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently assigned"

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Back Row
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      IconButton(
        onClick = onBack,
        modifier = Modifier.testTag("task_detail_back_button")
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "Back to tasks"
        )
      }
      Text(
        text = "Task Details & Proof",
        style = MaterialTheme.typography.titleLarge
      )
    }

    // Header Card
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          StatusBadge(status = task.status)
          RewardBadge(rewardCents = task.rewardCents)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = task.category.uppercase(Locale.US),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = task.title,
          style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Assigned to ${task.assignedUserName} • $createdDateStr",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "TASK INSTRUCTIONS & REQUIREMENTS",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = task.description,
              style = MaterialTheme.typography.bodyLarge
            )
          }
        }
      }
    }

    // Admin Review Feedback Card (if present)
    if (task.adminFeedback.isNotBlank() || task.status == TaskStatus.APPROVED || task.status == TaskStatus.REJECTED) {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors =
          CardDefaults.cardColors(
            containerColor =
              if (task.status == TaskStatus.APPROVED) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
              } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
              }
          )
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
          Text(
            text =
              if (task.status == TaskStatus.APPROVED) {
                "ADMIN REVIEW: APPROVED (${formatRewardInr(task.rewardCents)} CREDITED)"
              } else {
                "ADMIN REVIEW: REJECTED"
              },
            style = MaterialTheme.typography.labelSmall,
            color =
              if (task.status == TaskStatus.APPROVED) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.error
              }
          )
          if (task.adminFeedback.isNotBlank()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = task.adminFeedback,
              style = MaterialTheme.typography.bodyLarge
            )
          }
        }
      }
    }

    // Text-Only Task Proof Submission Section
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(24.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
      Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
        Text(
          text = stringResource(R.string.submit_proof_title),
          style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text =
            when {
              task.status != TaskStatus.PENDING ->
                "This task is currently ${TaskStatus.label(task.status)}. Submitted proof is locked against further edits by Firestore security rules."
              !isUserApproved ->
                "Your account status is currently '${userApprovalStatus.uppercase(Locale.US)}'. An administrator must approve your user profile before you can submit task proof."
              else ->
                "Enter your completion report or verification details below. Only text submissions are accepted (no file/image uploads)."
            },
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (task.status == TaskStatus.PENDING && !isUserApproved) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        OutlinedTextField(
          value = if (task.status == TaskStatus.PENDING) proofInput else task.proofText,
          onValueChange = { if (task.status == TaskStatus.PENDING) proofInput = it },
          enabled = task.status == TaskStatus.PENDING && isUserApproved && !isBusy,
          label = { Text("Completion Proof Text") },
          placeholder = { Text(stringResource(R.string.submit_proof_hint)) },
          minLines = 5,
          maxLines = 10,
          shape = RoundedCornerShape(16.dp),
          modifier =
            Modifier.fillMaxWidth()
              .testTag("task_proof_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
          text = "${(if (task.status == TaskStatus.PENDING) proofInput else task.proofText).length} / 4000 characters",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (task.status == TaskStatus.PENDING) {
          Spacer(modifier = Modifier.height(16.dp))
          Button(
            onClick = { onSubmitProof(proofInput) },
            enabled = !isBusy && isUserApproved && proofInput.trim().isNotEmpty(),
            shape = RoundedCornerShape(14.dp),
            modifier =
              Modifier.fillMaxWidth()
                .height(52.dp)
                .testTag("submit_proof_button")
          ) {
            if (isBusy) {
              CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
              )
            } else {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text(stringResource(R.string.submit_proof_cta))
            }
          }
        }
      }
    }
  }
}

@Composable
fun UserTrackingScreen(
  tasksState: UiState<List<TaskItem>>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onSelectTask: (String) -> Unit
) {
  val tasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  val filteredTasks =
    remember(tasks, selectedFilter) {
      if (selectedFilter == "all") {
        tasks
      } else {
        tasks.filter { it.status.equals(selectedFilter, ignoreCase = true) }
      }
    }

  val filters =
    listOf(
      "all" to "All (${tasks.size})",
      TaskStatus.PENDING to "Pending (${tasks.count { it.status == TaskStatus.PENDING }})",
      TaskStatus.SUBMITTED to "Submitted (${tasks.count { it.status == TaskStatus.SUBMITTED }})",
      TaskStatus.APPROVED to "Approved (${tasks.count { it.status == TaskStatus.APPROVED }})",
      TaskStatus.REJECTED to "Rejected (${tasks.count { it.status == TaskStatus.REJECTED }})"
    )

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
    Text(
      text = "Task Status Tracking",
      style = MaterialTheme.typography.headlineMedium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "Real-time Firestore pipeline across Pending, Submitted, Approved, and Rejected tasks.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(14.dp))

    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      filters.forEach { (key, label) ->
        val isSelected = selectedFilter == key
        FilterChip(
          selected = isSelected,
          onClick = { onSelectFilter(key) },
          label = { Text(label) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
          modifier = Modifier.testTag("tracking_filter_$key")
        )
      }
    }

    Spacer(modifier = Modifier.height(14.dp))

    if (filteredTasks.isEmpty()) {
      Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Icon(
            imageVector = Icons.Default.HourglassTop,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp)
          )
          Spacer(modifier = Modifier.height(12.dp))
          Text(
            text = "No tasks in ${if (selectedFilter == "all") "any" else TaskStatus.label(selectedFilter)} status",
            style = MaterialTheme.typography.titleMedium
          )
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "Tasks matching this workflow stage will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        items(filteredTasks, key = { it.id }) { task ->
          TaskItemCard(
            task = task,
            onClick = { onSelectTask(task.id) }
          )
        }
      }
    }
  }
}

@Composable
fun UserProfileScreen(
  profileState: UiState<UserProfile?>,
  tasksState: UiState<List<TaskItem>>,
  userEmail: String,
  userUid: String,
  onUpdateDisplayName: (String) -> Unit,
  onOpenSecurityInfo: () -> Unit,
  onSelectTask: (String) -> Unit,
  onSignOut: () -> Unit
) {
  val profile = (profileState as? UiState.Success)?.data
  val tasks = (tasksState as? UiState.Success)?.data ?: emptyList()
  var showEditNameDialog by remember { mutableStateOf(false) }
  var editedName by remember(profile?.displayName) {
    mutableStateOf(profile?.displayName ?: "")
  }

  if (showEditNameDialog) {
    AlertDialog(
      onDismissRequest = { showEditNameDialog = false },
      title = { Text("Update Display Name") },
      text = {
        OutlinedTextField(
          value = editedName,
          onValueChange = { editedName = it },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_display_name_input")
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            onUpdateDisplayName(editedName)
            showEditNameDialog = false
          },
          modifier = Modifier.testTag("save_display_name_button")
        ) {
          Text("Save")
        }
      },
      dismissButton = {
        TextButton(onClick = { showEditNameDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Profile Header Card
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
      ) {
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier =
                  Modifier.size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text =
                    (profile?.displayName?.firstOrNull() ?: userEmail.firstOrNull() ?: 'U')
                      .uppercaseChar()
                      .toString(),
                  style = MaterialTheme.typography.headlineMedium,
                  color = MaterialTheme.colorScheme.onPrimaryContainer
                )
              }
              Column {
                Text(
                  text = profile?.displayName?.ifBlank { "TaskFlow User" } ?: "TaskFlow User",
                  style = MaterialTheme.typography.titleLarge
                )
                Text(
                  text =
                    formatVisibleUserEmail(
                      email = profile?.email?.ifBlank { userEmail } ?: userEmail,
                      role = profile?.role
                    ),
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
              }
            }
            IconButton(
              onClick = {
                editedName = profile?.displayName ?: ""
                showEditNameDialog = true
              },
              modifier = Modifier.testTag("edit_profile_name_button")
            ) {
              Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit Display Name"
              )
            }
          }

          Spacer(modifier = Modifier.height(18.dp))

          // Role, Approval Status, Balance Row
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            ProfileMetricBox(
              label = "EARNED BALANCE",
              value = formatRewardInr(profile?.balanceCents ?: 0L),
              modifier = Modifier.weight(1f)
            )
            ProfileMetricBox(
              label = "COMPLETED",
              value = "${profile?.completedTasksCount ?: 0L} Tasks",
              modifier = Modifier.weight(1f)
            )
            ProfileMetricBox(
              label = "ROLE / STATUS",
              value =
                "${(profile?.role ?: "user").uppercase(Locale.US)} • ${(profile?.approvalStatus ?: "pending").uppercase(Locale.US)}",
              modifier = Modifier.weight(1.2f)
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = "UID: $userUid",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Spacer(modifier = Modifier.height(16.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            OutlinedButton(
              onClick = onOpenSecurityInfo,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.weight(1f).testTag("open_security_info_button")
            ) {
              Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text("Firebase Config")
            }
            OutlinedButton(
              onClick = onSignOut,
              shape = RoundedCornerShape(12.dp),
              colors =
                ButtonDefaults.outlinedButtonColors(
                  contentColor = MaterialTheme.colorScheme.error
                ),
              modifier = Modifier.weight(1f).testTag("sign_out_button")
            ) {
              Text(stringResource(R.string.sign_out))
            }
          }
        }
      }
    }

    // Task History Section
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Task History (${tasks.size})",
          style = MaterialTheme.typography.titleLarge
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
          )
          Text(
            text = "${tasks.count { it.status == TaskStatus.APPROVED }} Approved",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
          )
        }
      }
    }

    if (tasks.isEmpty()) {
      item {
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "No task history yet. Assigned and completed tasks will be recorded here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center
          )
        }
      }
    } else {
      items(tasks, key = { it.id }) { task ->
        TaskItemCard(
          task = task,
          onClick = { onSelectTask(task.id) }
        )
      }
    }
  }
}

@Composable
private fun ProfileMetricBox(
  label: String,
  value: String,
  modifier: Modifier = Modifier
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    modifier = modifier
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      horizontalAlignment = Alignment.Start
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = value,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

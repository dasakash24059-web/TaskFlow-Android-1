package com.example.ui.user

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.ZoomIn
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.UserProfile
import com.example.data.model.formatRewardInr
import com.example.data.model.formatVisibleUserEmail
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.ClickableTaskLinkCard
import com.example.ui.components.FullSizeImageDialog
import com.example.ui.components.ProofImageView
import com.example.ui.components.RewardBadge
import com.example.ui.components.StatusBadge
import com.example.ui.components.TaskFlowBrandLogo
import com.example.ui.components.TaskItemCard
import com.example.ui.components.openExternalTaskUrl
import com.example.ui.components.resolveAppOrWebsiteLabel
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
  val currentUserId = profile?.userId?.ifBlank { profile.id } ?: ""
  val tasks = (tasksState as? UiState.Success)?.data ?: emptyList()

  // Highlighted task: prioritize the highlighted assignedTaskId if pending, or most recent pending task
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
    // 1. Branded TaskFlow Header, Welcome & INR Wallet Balance Hero Card
    item {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(
              Brush.linearGradient(
                colors =
                  listOf(
                    Color(0xFF4C1D95),
                    Color(0xFF6D28D9),
                    Color(0xFF7C3AED)
                  )
              )
            )
            .border(
              width = 1.dp,
              color = Color(0xFFC4B5FD).copy(alpha = 0.35f),
              shape = RoundedCornerShape(26.dp)
            )
      ) {
        Canvas(modifier = Modifier.matchParentSize()) {
          drawCircle(
            color = Color.White.copy(alpha = 0.10f),
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
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              TaskFlowBrandLogo(size = 44.dp)
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "TASKFLOW USER PORTAL",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFFEDE9FE),
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = profile?.displayName?.ifBlank { "TaskFlow Member" } ?: "TaskFlow Member",
                  style = MaterialTheme.typography.headlineMedium,
                  color = Color.White,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = Color.White.copy(alpha = 0.15f),
              modifier =
                Modifier.border(
                  1.dp,
                  Color.White.copy(alpha = 0.30f),
                  RoundedCornerShape(16.dp)
                )
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.AccountBalanceWallet,
                  contentDescription = "Wallet Balance",
                  tint = Color.White,
                  modifier = Modifier.size(20.dp)
                )
                Column {
                  Text(
                    text = "WALLET (INR)",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFEDE9FE)
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

          // 4 Status Summary Pills inside Hero
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            QuickStatChip(
              label = "Available",
              count = pendingCount,
              color = Color(0xFFFBBF24),
              onClick = { onNavigateToTracking(TaskStatus.PENDING) },
              modifier = Modifier.weight(1f)
            )
            QuickStatChip(
              label = "Pending",
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

    // 2. Featured / Currently Assigned Task Section
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

        // Additional Available & Completed Tasks List
        val otherTasks = tasks.filter { it.id != currentlyAssignedTask?.id }
        if (otherTasks.isNotEmpty()) {
          item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "All Platform Tasks (${otherTasks.size})",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
          items(otherTasks, key = { it.id }) { task ->
            TaskItemCard(
              task = task,
              onClick = { onSelectTask(task.id) },
              showProofPreview =
                isAdminAuthorized ||
                  (currentUserId.isNotBlank() && task.assignedUserId == currentUserId)
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
  val appOrSiteLabel = resolveAppOrWebsiteLabel(task)

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

      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
      ) {
        AppIconThumbnail(
          appIconUrl = task.appIconUrl,
          appName = task.appName,
          category = task.category,
          size = 56.dp
        )

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = task.category.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = task.title,
            style = MaterialTheme.typography.headlineMedium
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = appOrSiteLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Text(
        text = task.description,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (task.taskLink.isNotBlank()) {
        Spacer(modifier = Modifier.height(14.dp))
        ClickableTaskLinkCard(
          url = task.taskLink,
          label = "TASK LINK (TAP TO OPEN)",
          testTag = "dashboard_featured_task_link_${task.id}"
        )
      }

      Spacer(modifier = Modifier.height(18.dp))

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
            if (task.status == TaskStatus.PENDING) Icons.Default.PlayArrow else Icons.Default.TaskAlt,
          contentDescription = null,
          modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text =
            if (task.status == TaskStatus.PENDING) {
              "Start Task & Submit Proof"
            } else {
              "View Task & Submission Status"
            },
          fontWeight = FontWeight.Bold
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
          Text("Open Admin Dashboard to Create & Publish a Task")
        }
      }
    }
  }
}

/**
 * Dedicated Task Details Screen with 9 clearly separated sections:
 * 1. Task Overview
 * 2. App or Website Information
 * 3. App Icon or Image (when available)
 * 4. Reward Amount (INR ₹)
 * 5. Download or Website Link
 * 6. Step-by-Step Instructions
 * 7. Required Proof Details
 * 8. Start Task / Open Link Action
 * 9. Submit Proof Section (Mandatory Written Text + Mandatory Gallery Screenshot)
 */
@Composable
fun UserTaskDetailScreen(
  task: TaskItem,
  isBusy: Boolean,
  onBack: () -> Unit,
  onSubmitProof: (String, Uri?) -> Unit,
  userApprovalStatus: String = "approved",
  currentUserId: String = "",
  uploadProgress: Int? = null,
  submissionError: String? = null,
  initialSelectedImageUri: Uri? = null
) {
  BackHandler(onBack = onBack)

  val context = LocalContext.current
  val isUserActive = userApprovalStatus != "suspended"
  val isTaskOwnerOrUnspecified = currentUserId.isBlank() || task.assignedUserId == currentUserId
  val appOrWebsiteName = resolveAppOrWebsiteLabel(task)

  var proofInput by rememberSaveable(task.id) {
    mutableStateOf(if (isTaskOwnerOrUnspecified && task.status != TaskStatus.PENDING) task.proofText else "")
  }
  var selectedImageUriString by rememberSaveable(task.id) {
    mutableStateOf(initialSelectedImageUri?.toString())
  }
  val selectedImageUri = selectedImageUriString?.let { Uri.parse(it) }
  var showFullSizeSubmittedImage by rememberSaveable(task.id) { mutableStateOf(false) }

  val photoPickerLauncher =
    rememberLauncherForActivityResult(
      contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
      if (uri != null) {
        selectedImageUriString = uri.toString()
      }
    }

  val createdDateStr =
    task.createdAt?.toDate()?.let {
      SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
    } ?: "Recently published"

  if (showFullSizeSubmittedImage && task.proofImageUrl.isNotBlank()) {
    FullSizeImageDialog(
      imageUrl = task.proofImageUrl,
      title = "Submitted Proof Screenshot",
      storagePath = task.proofStoragePath,
      onDismiss = { showFullSizeSubmittedImage = false }
    )
  }

  Column(
    modifier =
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 18.dp, vertical = 14.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Top Back Navigation Bar
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
      Column {
        Text(
          text = "Task Details & Proof Submission",
          style = MaterialTheme.typography.titleLarge
        )
        Text(
          text = "Complete every step below and submit written + screenshot proof",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    // Hero Card: Sections 2 (App/Website Info), 3 (App Icon/Image), and 4 (Reward Amount INR ₹)
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
          // Section 4: Reward Amount (INR ₹)
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.testTag("task_detail_reward_section")
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Text(
                text = "REWARD:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
              )
              Text(
                text = formatRewardInr(task.rewardCents),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.ExtraBold
              )
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Sections 2 & 3: App Icon/Image + App/Website Information & Task Title
        Row(
          modifier = Modifier.fillMaxWidth().testTag("task_detail_app_info_section"),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          AppIconThumbnail(
            appIconUrl = task.appIconUrl,
            appName = task.appName,
            category = task.category,
            size = 64.dp,
            modifier = Modifier.testTag("task_detail_app_icon")
          )

          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = task.category.uppercase(Locale.US),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.secondary,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.testTag("task_detail_category")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = task.title,
              style = MaterialTheme.typography.headlineMedium,
              modifier = Modifier.testTag("task_detail_title")
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "App / Website: $appOrWebsiteName",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.testTag("task_detail_app_name")
            )
            Text(
              text = "Published $createdDateStr",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Section 1: Task Overview
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "1. TASK OVERVIEW",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = task.description,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.testTag("task_detail_description")
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Section 6: Step-by-Step Instructions
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
          modifier = Modifier.fillMaxWidth().testTag("task_detail_instructions_card")
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = "2. STEP-BY-STEP INSTRUCTIONS",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            val rawInstructions = task.instructions.ifBlank { task.description }
            Text(
              text = rawInstructions,
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.testTag("task_detail_instructions")
            )
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Section 7: Required Proof Details
        val requiredProofText =
          task.requiredProof.ifBlank {
            "Submit (1) written confirmation notes describing your completion and (2) a clear Gallery screenshot showing the completed task screen."
          }
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
          modifier =
            Modifier.fillMaxWidth()
              .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(16.dp)
              )
              .testTag("task_detail_required_proof_section")
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.FactCheck,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "3. REQUIRED PROOF DETAILS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.Bold
              )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = requiredProofText,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.testTag("task_detail_required_proof_text")
            )
          }
        }

        // Sections 5 & 8: Download or Website Link + Start Task / Open Link Action
        if (task.taskLink.isNotBlank()) {
          Spacer(modifier = Modifier.height(14.dp))
          ClickableTaskLinkCard(
            url = task.taskLink,
            label = "4. DOWNLOAD OR WEBSITE LINK (TAP TO OPEN)",
            testTag = "task_detail_clickable_link"
          )
          Spacer(modifier = Modifier.height(10.dp))
          Button(
            onClick = { openExternalTaskUrl(context, task.taskLink) },
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary
              ),
            modifier =
              Modifier.fillMaxWidth()
                .height(48.dp)
                .testTag("start_task_open_link_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.OpenInNew,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Start Task / Open Destination Link",
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }

    // Admin Review Feedback Card (if task was reviewed)
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
              },
            fontWeight = FontWeight.Bold
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

    // Section 9: Mandatory Text + Photo Proof Submission Section
    val hasValidTextProof = proofInput.trim().isNotEmpty()
    val hasSelectedPhoto = selectedImageUri != null
    val canSubmitProof =
      task.status == TaskStatus.PENDING &&
        !isBusy &&
        isUserActive &&
        hasValidTextProof &&
        hasSelectedPhoto

    Card(
      modifier = Modifier.fillMaxWidth().testTag("submit_task_proof_section"),
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
                "This task is currently ${TaskStatus.label(task.status)}. Submitted text and screenshot proof are locked for Admin verification."
              !isUserActive ->
                "Your account is currently suspended and cannot submit task proof."
              else ->
                "Mandatory Requirement: You MUST provide BOTH (1) Written text proof AND (2) At least one photo or screenshot selected from your phone Gallery."
            },
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (task.status == TaskStatus.PENDING && !isUserActive) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        // 1. Written Text Proof Input
        Text(
          text = "1. Written Text Proof (Mandatory)",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(6.dp))

        val displayedProofText =
          if (task.status == TaskStatus.PENDING) {
            proofInput
          } else if (isTaskOwnerOrUnspecified) {
            task.proofText
          } else {
            "(Submitted proof is visible only to the submitting user and Admin)"
          }

        OutlinedTextField(
          value = displayedProofText,
          onValueChange = { if (task.status == TaskStatus.PENDING) proofInput = it },
          enabled = task.status == TaskStatus.PENDING && isUserActive && !isBusy,
          label = { Text("Completion Proof Text") },
          placeholder = { Text(stringResource(R.string.submit_proof_hint)) },
          minLines = 4,
          maxLines = 8,
          shape = RoundedCornerShape(16.dp),
          modifier =
            Modifier.fillMaxWidth()
              .testTag("task_proof_input")
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
          text = "${displayedProofText.length} / 4000 characters",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 2. Gallery Photo / Screenshot Proof
        Text(
          text = "2. Gallery Photo / Screenshot Proof (Mandatory)",
          style = MaterialTheme.typography.labelLarge,
          fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (task.status == TaskStatus.PENDING) {
          if (selectedImageUri == null) {
            OutlinedButton(
              onClick = {
                photoPickerLauncher.launch(
                  PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
              },
              enabled = isUserActive && !isBusy,
              shape = RoundedCornerShape(14.dp),
              modifier =
                Modifier.fillMaxWidth()
                  .height(52.dp)
                  .testTag("select_photo_button")
            ) {
              Icon(
                imageVector = Icons.Default.AddPhotoAlternate,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(8.dp))
              Text("Select Photo / Screenshot from Gallery")
            }
          } else {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier =
                Modifier.fillMaxWidth()
                  .testTag("selected_photo_preview_card")
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Text(
                  text = "SELECTED SCREENSHOT PREVIEW",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                  modifier =
                    Modifier.fillMaxWidth()
                      .height(190.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .background(MaterialTheme.colorScheme.surface)
                      .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(12.dp)
                      ),
                  contentAlignment = Alignment.Center
                ) {
                  AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "Selected proof screenshot preview",
                    contentScale = ContentScale.Crop,
                    modifier =
                      Modifier.fillMaxSize()
                        .testTag("selected_photo_preview_image")
                  )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                  OutlinedButton(
                    onClick = {
                      photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                      )
                    },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f).testTag("replace_photo_button")
                  ) {
                    Icon(
                      imageVector = Icons.Default.Refresh,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Replace Photo")
                  }
                  OutlinedButton(
                    onClick = { selectedImageUriString = null },
                    enabled = !isBusy,
                    shape = RoundedCornerShape(10.dp),
                    colors =
                      ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                      ),
                    modifier = Modifier.weight(1f).testTag("remove_photo_button")
                  ) {
                    Icon(
                      imageVector = Icons.Default.DeleteOutline,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove Photo")
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Mandatory Requirement Checklist
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth().testTag("proof_validation_status_card")
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              ProofRequirementRow(
                label = "Written text proof entered",
                fulfilled = hasValidTextProof,
                testTag = "requirement_text_proof"
              )
              ProofRequirementRow(
                label = "Gallery photo / screenshot attached",
                fulfilled = hasSelectedPhoto,
                testTag = "requirement_photo_proof"
              )
            }
          }

          if (uploadProgress != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Column(
              modifier = Modifier.fillMaxWidth().testTag("upload_progress_indicator"),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              LinearProgressIndicator(
                progress = { (uploadProgress.coerceIn(0, 100)) / 100f },
                modifier = Modifier.fillMaxWidth()
              )
              Text(
                text = "Uploading & saving proof submission… ${uploadProgress.coerceIn(0, 100)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }

          if (!submissionError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.errorContainer,
              modifier = Modifier.fillMaxWidth().testTag("submission_error_card")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.ErrorOutline,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.onErrorContainer,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = submissionError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onErrorContainer
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          Button(
            onClick = { onSubmitProof(proofInput, selectedImageUri) },
            enabled = canSubmitProof,
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
        } else {
          // Submitted / Approved / Rejected photo display for task owner
          if (isTaskOwnerOrUnspecified && task.proofImageUrl.isNotBlank()) {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier = Modifier.fillMaxWidth().testTag("submitted_photo_proof_card")
            ) {
              Column(modifier = Modifier.padding(14.dp)) {
                Box(
                  modifier =
                    Modifier.fillMaxWidth()
                      .height(190.dp)
                      .clip(RoundedCornerShape(12.dp))
                      .background(MaterialTheme.colorScheme.surface)
                      .clickable { showFullSizeSubmittedImage = true },
                  contentAlignment = Alignment.Center
                ) {
                  ProofImageView(
                    imageSource = task.proofImageUrl,
                    contentDescription = "Submitted proof screenshot",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                  )
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                  onClick = { showFullSizeSubmittedImage = true },
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.fillMaxWidth().testTag("user_view_full_image_button")
                ) {
                  Icon(
                    imageVector = Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                  )
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("View Full-Size Submitted Screenshot")
                }
              }
            }
          } else {
            Text(
              text =
                if (isTaskOwnerOrUnspecified) {
                  "No photo proof recorded."
                } else {
                  "Submitted photo proof is restricted to the submitting user and Admin."
                },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ProofRequirementRow(
  label: String,
  fulfilled: Boolean,
  testTag: String
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier.testTag(testTag)
  ) {
    Icon(
      imageVector =
        if (fulfilled) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
      contentDescription = null,
      tint =
        if (fulfilled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(16.dp)
    )
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color =
        if (fulfilled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = if (fulfilled) FontWeight.SemiBold else FontWeight.Normal
    )
  }
}

@Composable
fun UserTrackingScreen(
  tasksState: UiState<List<TaskItem>>,
  selectedFilter: String,
  onSelectFilter: (String) -> Unit,
  onSelectTask: (String) -> Unit,
  currentUserId: String = ""
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
      TaskStatus.PENDING to "Available (${tasks.count { it.status == TaskStatus.PENDING }})",
      TaskStatus.SUBMITTED to "Pending Review (${tasks.count { it.status == TaskStatus.SUBMITTED }})",
      TaskStatus.APPROVED to "Approved (${tasks.count { it.status == TaskStatus.APPROVED }})",
      TaskStatus.REJECTED to "Rejected (${tasks.count { it.status == TaskStatus.REJECTED }})"
    )

  Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
    Text(
      text = "Task Status & Submissions",
      style = MaterialTheme.typography.headlineMedium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
      text = "Real-time Firestore pipeline across Available, Pending Review, Approved, and Rejected tasks.",
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
            onClick = { onSelectTask(task.id) },
            showProofPreview = currentUserId.isNotBlank() && task.assignedUserId == currentUserId
          )
        }
      }
    }
  }
}

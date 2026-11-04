package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.BOOTSTRAPPED_ADMIN_EMAIL
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.formatRewardUsd
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
  val (bgColor, fgColor, icon) =
    when (status.lowercase(Locale.US)) {
      TaskStatus.PENDING ->
        Triple(
          Color(0xFFF59E0B).copy(alpha = 0.16f),
          Color(0xFFFBBF24),
          Icons.Default.HourglassEmpty
        )
      TaskStatus.SUBMITTED ->
        Triple(
          Color(0xFF0EA5E9).copy(alpha = 0.16f),
          Color(0xFF38BDF8),
          Icons.AutoMirrored.Filled.Send
        )
      TaskStatus.APPROVED ->
        Triple(
          Color(0xFF10B981).copy(alpha = 0.18f),
          Color(0xFF34D399),
          Icons.Default.CheckCircle
        )
      TaskStatus.REJECTED ->
        Triple(
          Color(0xFFF43F5E).copy(alpha = 0.16f),
          Color(0xFFFB7185),
          Icons.Default.Cancel
        )
      else ->
        Triple(
          MaterialTheme.colorScheme.surfaceVariant,
          MaterialTheme.colorScheme.onSurfaceVariant,
          Icons.Default.Info
        )
    }

  Surface(
    shape = RoundedCornerShape(50),
    color = bgColor,
    modifier = modifier.border(1.dp, fgColor.copy(alpha = 0.4f), RoundedCornerShape(50))
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = fgColor,
        modifier = Modifier.size(14.dp)
      )
      Text(
        text = TaskStatus.label(status),
        style = MaterialTheme.typography.labelSmall,
        color = fgColor,
        fontWeight = FontWeight.Bold
      )
    }
  }
}

@Composable
fun RewardBadge(rewardCents: Long, modifier: Modifier = Modifier) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f),
    modifier = modifier
  ) {
    Text(
      text = formatRewardUsd(rewardCents),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
    )
  }
}

@Composable
fun TaskItemCard(
  task: TaskItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  showAssignee: Boolean = false
) {
  val dateStr =
    task.updatedAt?.toDate()?.let {
      SimpleDateFormat("MMM d, h:mm a", Locale.US).format(it)
    } ?: "Just now"

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(20.dp))
        .clickable(onClick = onClick)
        .testTag("task_card_${task.id}"),
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
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ) {
          Text(
            text = task.category.uppercase(Locale.US),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
          )
        }
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          RewardBadge(rewardCents = task.rewardCents)
          StatusBadge(status = task.status)
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Text(
        text = task.title,
        style = MaterialTheme.typography.titleLarge,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      Spacer(modifier = Modifier.height(6.dp))

      Text(
        text = task.description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )

      if (task.proofText.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(10.dp)) {
            Text(
              text = "SUBMITTED TEXT PROOF",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              text = task.proofText,
              style = MaterialTheme.typography.bodyMedium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text =
            if (showAssignee) {
              "Assigned: ${task.assignedUserName} • $dateStr"
            } else {
              "Updated $dateStr"
            },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = if (task.status == TaskStatus.PENDING) "Submit Proof" else "Details",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
          )
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
          )
        }
      }
    }
  }
}

@Composable
fun FirebaseConfigSecurityDialog(
  databaseId: String,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Box(
        modifier =
          Modifier.size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = Icons.Default.VerifiedUser,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
      }
    },
    title = {
      Text(
        text = "Firebase Config & Backend Security",
        style = MaterialTheme.typography.titleLarge
      )
    },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        ConfigSection(
          title = "1. Provisioned Firebase Values",
          body =
            "• Project ID: taskflow-8852d\n" +
              "• Package Name: com.aistudio.taskflow.kxmpzq\n" +
              "• Firestore Named DB: $databaseId\n" +
              "• Bootstrapped Admin: $BOOTSTRAPPED_ADMIN_EMAIL\n" +
              "• Config Files: app/google-services.json & res/values/firebase_applet_config.xml"
        )
        ConfigSection(
          title = "2. Enforced Zero-Trust Security Rules",
          body =
            "• Users cannot modify their own role, approvalStatus, or balanceCents.\n" +
              "• Task proof submissions are text-only and locked once status leaves 'pending'.\n" +
              "• PII isolation prevents non-admin users from reading other users' profiles.\n" +
              "• Admin SDK private keys are never present in client code."
        )
        ConfigSection(
          title = "3. Manual Console / Server Steps Remaining",
          body =
            "• Auth Providers: Google Sign-In via Credential Manager is active. If you also want Email/Password Auth outside AI Studio, enable the Email/Password provider manually in the Firebase Console > Authentication > Sign-in method.\n" +
              "• Server-Side Ledger / Payouts: Real payments/withdrawals are intentionally omitted. Before launching real payouts, move balance crediting to a Cloud Function triggered on task approval using the Firebase Admin SDK."
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("close_security_dialog_button")
      ) {
        Text("Got It")
      }
    }
  )
}

@Composable
private fun ConfigSection(title: String, body: String) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface
      )
    }
  }
}

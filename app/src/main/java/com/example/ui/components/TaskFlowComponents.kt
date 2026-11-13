package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.data.model.formatRewardInr
import com.example.data.model.isValidTaskUrl
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Clean Minimal Background container adapting to Light Minimal (#F8FAFC) or Dark theme.
 */
@Composable
fun NeonBackgroundBox(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    content()
  }
}

/**
 * Clean Minimal Card container with subtle border and crisp surface elevation.
 */
@Composable
fun NeonGlassCard(
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape = RoundedCornerShape(18.dp),
  borderColor: Color = Color.Unspecified,
  backgroundColor: Color = Color.Unspecified,
  onClick: (() -> Unit)? = null,
  content: @Composable () -> Unit
) {
  val isLegacyTranslucentBg =
    backgroundColor == Color.Unspecified ||
      backgroundColor == Color(0x2E141938) ||
      backgroundColor == Color(0x40141938) ||
      backgroundColor == Color(0x3B1F174B) ||
      backgroundColor == Color(0x350F172A) ||
      backgroundColor == Color(0x30141938)
  val resolvedBg =
    if (!isLegacyTranslucentBg) {
      backgroundColor
    } else {
      MaterialTheme.colorScheme.surface
    }

  val isLegacyTranslucentBorder =
    borderColor == Color.Unspecified ||
      borderColor == Color(0x50A855F7) ||
      borderColor == Color(0x70A855F7) ||
      borderColor == Color(0x60A855F7) ||
      borderColor == Color(0x40A855F7) ||
      borderColor == Color(0x45A855F7)
  val resolvedBorder =
    if (!isLegacyTranslucentBorder) {
      borderColor
    } else {
      MaterialTheme.colorScheme.outlineVariant
    }

  val baseModifier = modifier
    .clip(shape)
    .background(resolvedBg)
    .border(
      border = BorderStroke(width = 1.dp, color = resolvedBorder),
      shape = shape
    )

  val finalModifier = if (onClick != null) {
    baseModifier.clickable(onClick = onClick)
  } else {
    baseModifier
  }

  Box(modifier = finalModifier) {
    content()
  }
}

/**
 * Account Verification Badge - STRICT Backend Checked!
 * Only rendered when actual backend user verification status == "approved".
 */
@Composable
fun VerifiedBadge(
  isVerified: Boolean,
  modifier: Modifier = Modifier,
  label: String = "Verified"
) {
  if (!isVerified) return

  Surface(
    shape = RoundedCornerShape(50),
    color = Color(0xFFE0F2FE),
    border = BorderStroke(1.dp, Color(0xFF38BDF8)),
    modifier = modifier
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
      Icon(
        imageVector = Icons.Default.Verified,
        contentDescription = "Verified Account",
        tint = Color(0xFF0284C7),
        modifier = Modifier.size(14.dp)
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF0369A1),
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp
      )
    }
  }
}

/**
 * High-contrast Coin Badge component for displaying Coin rewards and balances clearly on light & dark cards.
 */
@Composable
fun NeonCoinBadge(
  coins: Long,
  modifier: Modifier = Modifier,
  fontSize: Int = 13
) {
  Surface(
    shape = RoundedCornerShape(50),
    color = Color(0xFFFEF3C7),
    border = BorderStroke(1.dp, Color(0xFFF59E0B)),
    modifier = modifier
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
      Icon(
        imageVector = Icons.Default.MonetizationOn,
        contentDescription = "Coins",
        tint = Color(0xFFD97706),
        modifier = Modifier.size(16.dp)
      )
      Text(
        text = "$coins Coins",
        style = MaterialTheme.typography.labelMedium,
        color = Color(0xFF92400E),
        fontWeight = FontWeight.ExtraBold,
        fontSize = fontSize.sp
      )
    }
  }
}




fun openExternalTaskUrl(context: Context, rawUrl: String): Boolean {
  val trimmed = rawUrl.trim()
  if (!isValidTaskUrl(trimmed)) {
    runCatching {
      Toast.makeText(context, "Invalid HTTPS Task URL.", Toast.LENGTH_SHORT).show()
    }
    return false
  }
  return try {
    val intent =
      Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    context.startActivity(intent)
    true
  } catch (e: Throwable) {
    runCatching {
      Toast.makeText(context, "Unable to open link on this device.", Toast.LENGTH_SHORT).show()
    }
    false
  }
}

/**
 * Extracts a clean display name for the target app or website when appName is not explicitly set.
 */
fun resolveAppOrWebsiteLabel(task: TaskItem): String {
  if (task.appName.isNotBlank()) return task.appName.trim()
  val link = task.taskLink.trim()
  if (link.isNotEmpty()) {
    if (link.contains("play.google.com", ignoreCase = true)) {
      return "Google Play Store App"
    }
    val host = runCatching { URI(link).host?.removePrefix("www.") }.getOrNull()
    if (!host.isNullOrBlank()) return host
  }
  return task.category.ifBlank { "Verified Platform Task" }
}

/**
 * Official TaskFlow brand emblem featuring the white T symbol, golden-orange swoosh,
 * game controller, gold coins, and dark navy-purple color combination.
 */
@Composable
fun TaskFlowBrandLogo(
  modifier: Modifier = Modifier,
  size: Dp = 44.dp,
  isLightMode: Boolean = false
) {
  Box(
    modifier =
      modifier
        .size(size)
        .clip(RoundedCornerShape(size * 0.22f)),
    contentAlignment = Alignment.Center
  ) {
    Image(
      painter = painterResource(id = R.drawable.ic_taskflow_logo),
      contentDescription = "TaskFlow Official Brand Logo",
      contentScale = ContentScale.Fit,
      modifier = Modifier.fillMaxSize()
    )
  }
}

/**
 * Renders an image from an HTTPS URL, content:// URI, or inline data:image/...;base64,... string.
 */
@Composable
fun ProofImageView(
  imageSource: String,
  contentDescription: String?,
  modifier: Modifier = Modifier,
  contentScale: ContentScale = ContentScale.Crop
) {
  val trimmed = imageSource.trim()
  val decodedBitmap =
    remember(trimmed) {
      if (trimmed.startsWith("data:image/", ignoreCase = true) && trimmed.contains("base64,")) {
        runCatching {
          val base64Part = trimmed.substringAfter("base64,")
          val bytes = Base64.decode(base64Part, Base64.DEFAULT)
          BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
      } else {
        null
      }
    }

  if (decodedBitmap != null) {
    Image(
      bitmap = decodedBitmap,
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier
    )
  } else {
    AsyncImage(
      model = trimmed,
      contentDescription = contentDescription,
      contentScale = contentScale,
      modifier = modifier
    )
  }
}

/**
 * Displays the task's App/Website Icon when available, or a polished category icon badge.
 */
@Composable
fun AppIconThumbnail(
  appIconUrl: String,
  appName: String,
  category: String,
  modifier: Modifier = Modifier,
  size: Dp = 48.dp
) {
  val cornerShape = RoundedCornerShape(size * 0.28f)
  if (appIconUrl.isNotBlank()) {
    Box(
      modifier =
        modifier
          .size(size)
          .clip(cornerShape)
          .background(MaterialTheme.colorScheme.surfaceVariant)
          .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
            shape = cornerShape
          ),
      contentAlignment = Alignment.Center
    ) {
      ProofImageView(
        imageSource = appIconUrl,
        contentDescription = appName.ifBlank { category },
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
      )
    }
  } else {
    val catLower = category.lowercase(Locale.US)
    val fallbackIcon =
      when {
        catLower.contains("app") || catLower.contains("install") || catLower.contains("mobile") ->
          Icons.Default.Android
        catLower.contains("web") || catLower.contains("visit") ->
          Icons.Default.Language
        catLower.contains("qa") || catLower.contains("test") ->
          Icons.Default.BugReport
        catLower.contains("doc") ->
          Icons.Default.Description
        else ->
          Icons.AutoMirrored.Filled.FactCheck
      }
    Box(
      modifier =
        modifier
          .size(size)
          .clip(cornerShape)
          .background(
            Brush.linearGradient(
              colors =
                listOf(
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                  MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                )
            )
          )
          .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            shape = cornerShape
          ),
      contentAlignment = Alignment.Center
    ) {
      val initial = appName.trim().firstOrNull()?.uppercaseChar()
      if (initial != null) {
        Text(
          text = initial.toString(),
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.ExtraBold
        )
      } else {
        Icon(
          imageVector = fallbackIcon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(size * 0.5f)
        )
      }
    }
  }
}

@Composable
fun ClickableTaskLinkCard(
  url: String,
  modifier: Modifier = Modifier,
  label: String = "DOWNLOAD / WEBSITE LINK",
  testTag: String = "clickable_task_link",
  onLinkOpened: ((String) -> Unit)? = null
) {
  val trimmed = url.trim()
  if (trimmed.isEmpty()) return
  val context = LocalContext.current

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .border(
          width = 1.dp,
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
          shape = RoundedCornerShape(14.dp)
        )
        .clickable {
          openExternalTaskUrl(context, trimmed)
          onLinkOpened?.invoke(trimmed)
        }
        .testTag(testTag)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.weight(1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Box(
          modifier =
            Modifier.size(36.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = Icons.Default.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = trimmed,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            textDecoration = TextDecoration.Underline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
        }
      }
      Spacer(modifier = Modifier.width(8.dp))
      Icon(
        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
        contentDescription = "Open Task Link",
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp)
      )
    }
  }
}

@Composable
fun FullSizeImageDialog(
  imageUrl: String,
  title: String,
  storagePath: String = "",
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleLarge
      )
    },
    text = {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("full_size_image_dialog_content"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Box(
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 240.dp, max = 460.dp)
              .clip(RoundedCornerShape(16.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant),
          contentAlignment = Alignment.Center
        ) {
          ProofImageView(
            imageSource = imageUrl,
            contentDescription = "Full-size proof screenshot",
            contentScale = ContentScale.Fit,
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = 240.dp, max = 460.dp)
                .testTag("full_size_proof_image")
          )
        }
        if (storagePath.isNotBlank()) {
          Text(
            text = "Storage Ref: $storagePath",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        val displayUrl =
          if (imageUrl.startsWith("data:image/")) {
            "Inline Verified JPEG Screenshot (${imageUrl.length / 1024} KB)"
          } else {
            imageUrl
          }
        Text(
          text = "Image Source: $displayUrl",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("close_full_image_dialog_button")
      ) {
        Text("Close Preview")
      }
    }
  )
}

@Composable
fun StatusBadge(
  status: String,
  modifier: Modifier = Modifier,
  isActive: Boolean = true
) {
  val isDeactivated = !isActive && status.lowercase(Locale.US) == TaskStatus.PENDING
  val (bgColor, fgColor, icon) =
    if (isDeactivated) {
      Triple(
        Color(0xFF64748B).copy(alpha = 0.18f),
        Color(0xFF475569),
        Icons.Default.Cancel
      )
    } else {
      when (status.lowercase(Locale.US)) {
        TaskStatus.PENDING ->
          Triple(
            Color(0xFFF59E0B).copy(alpha = 0.16f),
            Color(0xFFD97706),
            Icons.Default.HourglassEmpty
          )
        TaskStatus.SUBMITTED ->
          Triple(
            Color(0xFF0EA5E9).copy(alpha = 0.16f),
            Color(0xFF0284C7),
            Icons.AutoMirrored.Filled.Send
          )
        TaskStatus.APPROVED ->
          Triple(
            Color(0xFF10B981).copy(alpha = 0.18f),
            Color(0xFF059669),
            Icons.Default.CheckCircle
          )
        TaskStatus.REJECTED ->
          Triple(
            Color(0xFFF43F5E).copy(alpha = 0.16f),
            Color(0xFFE11D48),
            Icons.Default.Cancel
          )
        else ->
          Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Icons.Default.Info
          )
      }
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
        text = TaskStatus.label(status, isActive),
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
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
    modifier = modifier
  ) {
    Text(
      text = formatRewardInr(rewardCents),
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      fontWeight = FontWeight.ExtraBold,
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
    )
  }
}

/**
 * Compact horizontal Task Card for the User Dashboard & Available Tasks list (Light Minimal UI).
 * Displays:
 * - App/campaign icon
 * - Task title
 * - Short description
 * - Reward coins (125 Coins = ₹1)
 * - Task status badge
 * - Clear Open / View action button
 */
@Composable
fun TaskItemCard(
  task: TaskItem,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  showAssignee: Boolean = false,
  showProofPreview: Boolean = false
) {
  val appOrSiteLabel = resolveAppOrWebsiteLabel(task)
  val shortDescription =
    task.description.trim().ifBlank { "$appOrSiteLabel • ${task.category}" }
  val coinsText = com.example.data.model.formatCoins(task.getEffectiveRewardCoins())

  Card(
    modifier =
      modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable(onClick = onClick)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
        .testTag("task_card_${task.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
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
          Text(
            text = task.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(2.dp))
          Text(
            text = shortDescription,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
          )
          Spacer(modifier = Modifier.height(4.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = appOrSiteLabel,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (task.targetSubmissions > 0L) {
              Text(
                text = "• Quota: ${task.completedSubmissions}/${task.targetSubmissions}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }

        Column(
          horizontalAlignment = Alignment.End,
          verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primaryContainer
          ) {
            Text(
              text = coinsText,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              fontWeight = FontWeight.ExtraBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
          RewardBadge(rewardCents = task.rewardCents)
        }
      }

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
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
          ) {
            Text(
              text = task.category,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
          }
        }

        Button(
          onClick = onClick,
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primary,
              contentColor = MaterialTheme.colorScheme.onPrimary
            ),
          modifier = Modifier.testTag("start_or_view_task_button_${task.id}")
        ) {
          Text(
            text = "View Task",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold
          )
          Spacer(modifier = Modifier.width(4.dp))
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            modifier = Modifier.size(15.dp)
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
              "• Storage Bucket: taskflow-8852d.firebasestorage.app\n" +
              "• Admin Access: Enforced via verified server-side Admin Registry (/admins/{adminId})\n" +
              "• Config Files: app/google-services.json & res/values/firebase_applet_config.xml"
        )
        ConfigSection(
          title = "2. Enforced Zero-Trust Security Rules",
          body =
            "• Users cannot modify their own role, approvalStatus, or balanceCents.\n" +
              "• Task proof submissions require BOTH written text proof and an uploaded gallery photo/screenshot, and are locked once status leaves 'Available'.\n" +
              "• Any active authenticated user can complete open tasks and submit proof; Admin approval credits the submitting user's INR wallet once.\n" +
              "• PII isolation prevents non-admin users from reading other users' profiles."
        )
        ConfigSection(
          title = "3. Manual Console / Server Steps Remaining",
          body =
            "• Auth Providers: Google Sign-In via Credential Manager is active.\n" +
              "• Server-Side Ledger / Payouts: Real payments/withdrawals are intentionally omitted and disabled."
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

@Composable
fun AppUpdateDialog(
  versionInfo: com.example.data.model.AppVersionInfo,
  onUpdateNow: () -> Unit,
  onDismiss: () -> Unit,
  downloadProgress: com.example.data.model.UpdateDownloadProgress? = null,
  onInstallNow: (() -> Unit)? = null,
  onOpenSettings: (() -> Unit)? = null,
  onReopenApp: (() -> Unit)? = null
) {
  val isChecking = downloadProgress?.isChecking == true
  val isDownloading = downloadProgress?.isDownloading == true
  val isVerifying = downloadProgress?.isVerifying == true
  val isDownloadCompleted = downloadProgress?.isDownloadCompleted == true
  val isReadyToInstall = downloadProgress?.isReadyToInstall == true
  val isPermissionRequired = downloadProgress?.isPermissionRequired == true
  val isWaitingForInstallConfirmation = downloadProgress?.isWaitingForInstallConfirmation == true
  val isInstallationStarted = downloadProgress?.isInstallationStarted == true
  val isUpdateCompleted = downloadProgress?.isUpdateCompleted == true
  val hasFailed = downloadProgress?.hasFailed == true

  AlertDialog(
    onDismissRequest = {
      if (isUpdateCompleted) {
        onDismiss()
      }
    },
    properties = androidx.compose.ui.window.DialogProperties(
      dismissOnBackPress = isUpdateCompleted,
      dismissOnClickOutside = isUpdateCompleted
    ),
    modifier = Modifier.testTag("update_dialog"),
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(
          imageVector = when {
            isUpdateCompleted -> Icons.Default.CheckCircle
            hasFailed -> Icons.Default.Cancel
            isInstallationStarted || isWaitingForInstallConfirmation -> Icons.Default.HourglassEmpty
            isDownloadCompleted || isReadyToInstall -> Icons.Default.CheckCircle
            isDownloading || isVerifying || isChecking -> Icons.Default.HourglassEmpty
            else -> Icons.Default.Info
          },
          contentDescription = "Update Status",
          tint = when {
            isUpdateCompleted -> Color(0xFF059669)
            hasFailed -> MaterialTheme.colorScheme.error
            isInstallationStarted || isWaitingForInstallConfirmation -> MaterialTheme.colorScheme.primary
            isDownloadCompleted || isReadyToInstall -> Color(0xFF059669)
            isDownloading || isVerifying || isChecking -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primary
          }
        )
        Text(
          text = when {
            isUpdateCompleted -> "Update Completed!"
            isInstallationStarted -> "Installing Update..."
            isWaitingForInstallConfirmation -> "Installation Pending"
            isDownloading -> "Downloading Update..."
            isVerifying -> "Verifying Update..."
            isReadyToInstall || isDownloadCompleted -> "Update Ready to Install"
            isPermissionRequired -> "Permission Required"
            hasFailed -> "Update Failed"
            isChecking -> "Checking for Updates..."
            else -> "New Update Available"
          }
        )
      }
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (isUpdateCompleted) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFECFDF5),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA7F3D0)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = Color(0xFF059669),
                modifier = Modifier.size(48.dp)
              )
              Text(
                text = "Installation Confirmed ✓",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF065F46)
              )
              Text(
                text = downloadProgress?.statusMessage?.ifBlank {
                  "TaskFlow has been updated successfully with all latest features and UI enhancements."
                } ?: "TaskFlow has been updated successfully.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF047857),
                textAlign = TextAlign.Center
              )
            }
          }
        } else {
          Text(
            text = "Version ${versionInfo.versionName} (Code: ${versionInfo.versionCode}) is available.",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
          )

          if (versionInfo.releaseNotes.isNotBlank()) {
            Text(
              text = "Release Notes:",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary
            )
            Text(
              text = versionInfo.releaseNotes,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }

          // Live Progress / Status Section
          if (downloadProgress != null && downloadProgress.status != com.example.data.model.UpdateDownloadStatus.IDLE) {
            Surface(
              shape = RoundedCornerShape(16.dp),
              color = when {
                hasFailed -> Color(0xFFFEF2F2)
                isDownloadCompleted || isReadyToInstall -> Color(0xFFECFDF5)
                isPermissionRequired -> Color(0xFFFFFBEB)
                isInstallationStarted || isWaitingForInstallConfirmation -> Color(0xFFEFF6FF)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
              },
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                if (isDownloading) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = "${downloadProgress.progressPercent}%",
                      style = MaterialTheme.typography.titleMedium,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.primary
                    )
                    val formattedSize = downloadProgress.getFormattedSize()
                    if (formattedSize.isNotEmpty()) {
                      Text(
                        text = formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                  LinearProgressIndicator(
                    progress = { downloadProgress.progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                    color = MaterialTheme.colorScheme.primary
                  )
                } else if (isVerifying) {
                  LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary
                  )
                }

                Text(
                  text = downloadProgress.statusMessage.ifBlank {
                    downloadProgress.errorMessage ?: ""
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = when {
                    hasFailed -> MaterialTheme.colorScheme.error
                    isDownloadCompleted || isReadyToInstall -> Color(0xFF047857)
                    isPermissionRequired -> Color(0xFFB45309)
                    isInstallationStarted || isWaitingForInstallConfirmation -> Color(0xFF1D4ED8)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                  },
                  fontWeight = if (hasFailed || isReadyToInstall || isDownloadCompleted) FontWeight.SemiBold else FontWeight.Normal
                )

                if (isPermissionRequired) {
                  Text(
                    text = "Android requires explicit authorization to allow TaskFlow to install updates. Tap 'Grant Permission in Settings' below, enable 'Allow from this source', and return here to automatically resume installation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF78350F)
                  )
                }

                if (isInstallationStarted || isWaitingForInstallConfirmation) {
                  Text(
                    text = "The Android Package Installer has been launched. Please review the installation prompt and tap 'Update' in the system dialog to complete full APK replacement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF1E40AF)
                  )
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = "This is a required update. You must install the latest APK to continue using TaskFlow.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    },
    confirmButton = {
      when {
        isUpdateCompleted -> {
          Button(
            onClick = {
              if (onReopenApp != null) {
                onReopenApp()
              } else {
                onDismiss()
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("reopen_app_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
          ) {
            Text("Reopen App", fontWeight = FontWeight.Bold)
          }
        }
        isInstallationStarted || isWaitingForInstallConfirmation -> {
          Button(
            onClick = { onInstallNow?.invoke() },
            modifier = Modifier.fillMaxWidth().testTag("install_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
          ) {
            Text("Launch Installer Again", fontWeight = FontWeight.Bold)
          }
        }
        isDownloadCompleted || isReadyToInstall -> {
          Button(
            onClick = { onInstallNow?.invoke() },
            modifier = Modifier.fillMaxWidth().testTag("install_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
          ) {
            Text("Install Now", fontWeight = FontWeight.Bold)
          }
        }
        isPermissionRequired -> {
          Button(
            onClick = { onOpenSettings?.invoke() },
            modifier = Modifier.fillMaxWidth().testTag("grant_permission_button"),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706))
          ) {
            Text("Grant Permission in Settings", fontWeight = FontWeight.Bold)
          }
        }
        isDownloading || isVerifying || isChecking -> {
          Button(
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth().testTag("update_now_button"),
            colors = ButtonDefaults.buttonColors(
              disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
              disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
              )
              Text(
                when {
                  isChecking -> "Checking for updates..."
                  isVerifying -> "Verifying APK..."
                  else -> "Downloading (${downloadProgress?.progressPercent ?: 0}%)..."
                }
              )
            }
          }
        }
        hasFailed -> {
          Button(
            onClick = onUpdateNow,
            modifier = Modifier.fillMaxWidth().testTag("update_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          ) {
            Text("Retry Update", fontWeight = FontWeight.Bold)
          }
        }
        else -> {
          Button(
            onClick = onUpdateNow,
            modifier = Modifier.fillMaxWidth().testTag("update_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          ) {
            Text("Update Now", fontWeight = FontWeight.Bold)
          }
        }
      }
    },
    dismissButton = null
  )
}


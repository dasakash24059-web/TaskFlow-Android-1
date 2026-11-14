package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.DailyCheckInConstants
import com.example.data.model.DailyCheckInState
import com.example.data.model.formatCoins
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonGlassCard
import java.util.Calendar
import java.util.Locale

/**
 * Dedicated Daily Check-in Screen with a compact 5 columns × 6 rows 30-day grid
 * of rounded square cards and a prominent separate Claim button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DedicatedDailyCheckInScreen(
  dailyCheckInState: DailyCheckInState,
  approvedEarningsCents: Long = 0L,
  onClaimDay: (Int) -> Unit,
  onBack: () -> Unit,
  isBusy: Boolean = false
) {
  BackHandler(onBack = onBack)

  val timeRemainingToTomorrow = remember {
    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 24)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    val diff = (cal.timeInMillis - now).coerceAtLeast(0L)
    val hours = diff / (1000 * 60 * 60)
    val minutes = (diff / (1000 * 60)) % 60
    val seconds = (diff / 1000) % 60
    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
  }

  val nextClaimDay = dailyCheckInState.nextClaimDay.coerceIn(1, 30)
  val todayRewardCoins = DailyCheckInConstants.getRewardCoinsForDay(nextClaimDay)

  NeonBackgroundBox {
    LazyColumn(
      modifier =
        Modifier.fillMaxSize().testTag("dedicated_daily_checkin_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top Navigation Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          IconButton(
            onClick = onBack,
            modifier =
              Modifier.clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .testTag("daily_checkin_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Home",
              tint = MaterialTheme.colorScheme.onSurface
            )
          }
          Text(
            text = "Daily Check-in",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold
          )
          Surface(
            shape = RoundedCornerShape(50),
            color = Color(0xFFFEF3C7),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFD97706),
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${dailyCheckInState.currentStreak}d Streak",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF92400E)
              )
            }
          }
        }
      }

      // 1. Hero Banner + Separate Prominent Claim Button
      item {
        NeonGlassCard(
          shape = RoundedCornerShape(22.dp),
          borderColor = Color(0xFFCBD5E1),
          backgroundColor = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_hero_card")
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "30-DAY COIN REWARD CYCLE",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF1E3A8A),
                  fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text =
                    if (dailyCheckInState.canCheckInToday) {
                      "Day $nextClaimDay Ready: +${formatCoins(todayRewardCoins)}"
                    } else {
                      "Today's Check-in Claimed ✓"
                    },
                  style = MaterialTheme.typography.titleLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontWeight = FontWeight.Bold
                )
              }
              Surface(
                shape = RoundedCornerShape(50),
                color = Color(0xFFFEF3C7),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B))
              ) {
                Text(
                  text = "Day $nextClaimDay / 30",
                  style = MaterialTheme.typography.labelSmall,
                  color = Color(0xFF92400E),
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
              }
            }

            // Status / Timer Notice
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color(0xFFF8FAFC),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE2E8F0)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.Schedule,
                  contentDescription = null,
                  tint = Color(0xFF1E3A8A),
                  modifier = Modifier.size(16.dp)
                )
                Text(
                  text =
                    if (dailyCheckInState.canCheckInToday) {
                      "Claim today's +${formatCoins(todayRewardCoins)} streak reward below."
                    } else {
                      "Next daily check-in unlocks in $timeRemainingToTomorrow"
                    },
                  style = MaterialTheme.typography.bodySmall,
                  color = Color(0xFF334155),
                  fontWeight = FontWeight.Medium
                )
              }
            }

            // Separate Clearly Visible Claim Button
            Button(
              onClick = { onClaimDay(nextClaimDay) },
              enabled = dailyCheckInState.canCheckInToday && !isBusy,
              shape = RoundedCornerShape(14.dp),
              colors =
                ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF1E3A8A),
                  contentColor = Color.White,
                  disabledContainerColor = Color(0xFFE2E8F0),
                  disabledContentColor = Color(0xFF64748B)
                ),
              modifier =
                Modifier.fillMaxWidth()
                  .height(50.dp)
                  .testTag("claim_daily_checkin_button")
            ) {
              if (isBusy) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  strokeWidth = 2.dp,
                  color = Color.White
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                  text = "Processing Check-in...",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
              } else if (dailyCheckInState.canCheckInToday) {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Claim Day $nextClaimDay (+${formatCoins(todayRewardCoins)})",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
              } else {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Claimed for Today ✓",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
        }
      }

      // 2. Legend Row (Claimed / Today / Locked)
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "30-Day Schedule",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            GridLegendDot(color = Color(0xFF059669), label = "Claimed")
            GridLegendDot(color = Color(0xFF1E3A8A), label = "Today")
            GridLegendDot(color = Color(0xFF64748B), label = "Locked")
          }
        }
      }

      // 3. Compact 5 Columns × 6 Rows Grid of Rounded Square Cards (Days 1..30)
      item {
        Column(
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_30day_grid"),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          for (row in 0 until 6) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              for (col in 0 until 5) {
                val day = row * 5 + col + 1
                val rewardCoins = DailyCheckInConstants.getRewardCoinsForDay(day)
                val isClaimed =
                  if (!dailyCheckInState.canCheckInToday) {
                    day <= dailyCheckInState.currentStreak
                  } else {
                    day < nextClaimDay
                  }
                val isCurrent = dailyCheckInState.canCheckInToday && day == nextClaimDay
                val isGrandBonus = day == 30 || day % 5 == 0

                CompactDayGridSquareCard(
                  day = day,
                  rewardCoins = rewardCoins,
                  isClaimed = isClaimed,
                  isCurrent = isCurrent,
                  isMilestone = isGrandBonus,
                  isBusy = isBusy,
                  onClick = {
                    if (isCurrent && !isBusy) {
                      onClaimDay(day)
                    }
                  },
                  modifier = Modifier.weight(1f)
                )
              }
            }
          }
        }
      }

      // 4. Concise Milestone Summary Card
      item {
        NeonGlassCard(
          shape = RoundedCornerShape(16.dp),
          borderColor = MaterialTheme.colorScheme.outlineVariant,
          backgroundColor = MaterialTheme.colorScheme.surface,
          modifier = Modifier.fillMaxWidth().testTag("daily_checkin_rules_card")
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Icon(
              imageVector = Icons.Default.EmojiEvents,
              contentDescription = null,
              tint = Color(0xFFD97706),
              modifier = Modifier.size(20.dp)
            )
            Text(
              text = "Milestone bonuses on Days 5, 10, 15, 20, 25 & 1,000 Coins on Day 30.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontWeight = FontWeight.Medium
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GridLegendDot(color: Color, label: String) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp)
  ) {
    Box(
      modifier = Modifier.size(8.dp).clip(CircleShape).background(color)
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      fontWeight = FontWeight.SemiBold,
      fontSize = 10.sp
    )
  }
}

@Composable
private fun CompactDayGridSquareCard(
  day: Int,
  rewardCoins: Long,
  isClaimed: Boolean,
  isCurrent: Boolean,
  isMilestone: Boolean,
  isBusy: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val borderColor =
    when {
      isCurrent -> Color(0xFF0284C7)
      isClaimed -> Color(0xFF10B981)
      isMilestone -> Color(0xFFF59E0B)
      else -> Color(0xFFCBD5E1)
    }

  val bgBrush =
    when {
      isCurrent ->
        Brush.verticalGradient(
          colors = listOf(Color(0xFFE0F2FE), Color(0xFFBAE6FD))
        )
      isClaimed ->
        Brush.verticalGradient(
          colors = listOf(Color(0xFFECFDF5), Color(0xFFD1FAE5))
        )
      isMilestone ->
        Brush.verticalGradient(
          colors = listOf(Color(0xFFFFFBEB), Color(0xFFFEF3C7))
        )
      else ->
        Brush.verticalGradient(
          colors = listOf(Color(0xFFFFFFFF), Color(0xFFF1F5F9))
        )
    }

  Box(
    modifier =
      modifier
        .aspectRatio(0.92f)
        .clip(RoundedCornerShape(14.dp))
        .background(bgBrush)
        .border(
          width = if (isCurrent) 2.dp else 1.2.dp,
          color = borderColor,
          shape = RoundedCornerShape(14.dp)
        )
        .clickable(enabled = isCurrent && !isBusy, onClick = onClick)
        .padding(4.dp)
        .testTag("day_${day}_card"),
    contentAlignment = Alignment.Center
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxSize().padding(vertical = 2.dp)
    ) {
      // Top Day Header
      Text(
        text = "D$day",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color =
          when {
            isCurrent -> Color(0xFF0369A1)
            isClaimed -> Color(0xFF047857)
            isMilestone -> Color(0xFFB45309)
            else -> Color(0xFF475569)
          }
      )

      // Center Status Icon or Coin Value
      Box(
        modifier = Modifier.size(22.dp),
        contentAlignment = Alignment.Center
      ) {
        when {
          isClaimed -> {
            Surface(
              shape = CircleShape,
              color = Color(0xFF10B981),
              modifier = Modifier.size(20.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = "Claimed",
                  tint = Color.White,
                  modifier = Modifier.size(13.dp)
                )
              }
            }
          }
          isCurrent -> {
            Surface(
              shape = CircleShape,
              color = Color(0xFF0284C7),
              modifier = Modifier.size(20.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.LocalFireDepartment,
                  contentDescription = "Claimable Today",
                  tint = Color(0xFFFDE047),
                  modifier = Modifier.size(14.dp)
                )
              }
            }
          }
          isMilestone -> {
            Icon(
              imageVector = Icons.Default.EmojiEvents,
              contentDescription = "Bonus Day",
              tint = Color(0xFFD97706),
              modifier = Modifier.size(16.dp)
            )
          }
          else -> {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Locked",
              tint = Color(0xFF64748B),
              modifier = Modifier.size(13.dp)
            )
          }
        }
      }

      // Bottom Coin Amount
      Text(
        text = "+$rewardCoins",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        color =
          when {
            isCurrent -> Color(0xFF0C4A6E)
            isClaimed -> Color(0xFF065F46)
            isMilestone -> Color(0xFF92400E)
            else -> Color(0xFF0F172A)
          },
        textAlign = TextAlign.Center
      )
    }
  }
}

@Composable
fun DailyCheckInPendingDialog(
  streakDay: Int,
  rewardCents: Long,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    icon = {
      Surface(
        shape = CircleShape,
        color = Color(0xFFFEF3C7),
        modifier = Modifier.size(54.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.HourglassEmpty,
            contentDescription = null,
            tint = Color(0xFFD97706),
            modifier = Modifier.size(30.dp)
          )
        }
      }
    },
    title = {
      Text(
        text = "Daily Check-in Notice",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center
      )
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        Text(
          text = "Day $streakDay Reward: +${formatCoins(rewardCents)}",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.ExtraBold
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97706)),
        modifier = Modifier.testTag("dismiss_pending_dialog_button")
      ) {
        Text("Understood", fontWeight = FontWeight.Bold)
      }
    }
  )
}

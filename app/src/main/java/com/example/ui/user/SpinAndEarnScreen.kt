package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.SpinEarnUiState
import com.example.data.model.SpinSessionOutcome
import com.example.data.model.SpinWheelConstants
import com.example.data.model.formatCoins
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonGlassCard
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class SpinPrize(
  val coins: Long,
  val label: String,
  val color: Color
)

val PRIZES: List<SpinPrize> =
  listOf(
    SpinPrize(10L, "10 Coins", Color(0xFF7C3AED)),
    SpinPrize(20L, "20 Coins", Color(0xFF06B6D4)),
    SpinPrize(30L, "30 Coins", Color(0xFFEC4899)),
    SpinPrize(40L, "40 Coins", Color(0xFFF59E0B)),
    SpinPrize(50L, "50 Coins", Color(0xFF10B981)),
    SpinPrize(100L, "100 Coins", Color(0xFF6366F1)),
    SpinPrize(125L, "125 Coins", Color(0xFFF43F5E)),
    SpinPrize(150L, "150 Coins", Color(0xFFEAB308))
  )

/**
 * Computes the exact clockwise target rotation so that the center of [segmentIndex]
 * aligns with the top pointer at 270° in Compose Canvas drawArc coordinates (where 0° is 3 o'clock).
 */
fun calculateTargetWheelRotation(
  currentRotation: Float,
  segmentIndex: Int,
  segmentCount: Int = PRIZES.size,
  fullRotations: Int = 6
): Float {
  val sweepAngle = 360f / segmentCount. coerceAtLeast(1)
  val segmentCenter = (segmentIndex.mod(segmentCount)) * sweepAngle + (sweepAngle / 2f)
  val targetMod = (270f - segmentCenter + 360f) % 360f
  val currentMod = ((currentRotation % 360f) + 360f) % 360f
  val delta = (targetMod - currentMod + 360f) % 360f
  return currentRotation + (360f * fullRotations) + delta
}

private fun formatCooldownDuration(remainingMs: Long): String {
  if (remainingMs <= 0L) return "00:00:00"
  val totalSeconds = remainingMs / 1000L
  val hours = totalSeconds / 3600L
  val minutes = (totalSeconds % 3600L) / 60L
  val seconds = totalSeconds % 60L
  return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

@Composable
fun SpinAndEarnScreen(
  onBack: () -> Unit,
  spinState: SpinEarnUiState = SpinEarnUiState(freeSpinAvailable = true),
  onStartSpin: ((onOutcomeReady: (SpinSessionOutcome) -> Unit) -> Unit)? = null,
  onWheelAnimationFinished: () -> Unit = {},
  onWatchExtraSpinVideo: () -> Unit = {},
  onClaimSpinRewardWithAd: () -> Unit = {},
  onClaimReward: (Long) -> Unit = {},
  onShowRewardedAd: (onAdCompleted: () -> Unit) -> Unit = { it() },
  onShowInterstitialAd: (onAdCompleted: () -> Unit) -> Unit = { it() }
) {
  BackHandler(onBack = onBack)

  val rotation = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()

  // Live 1-second ticker for the 24-hour Free Spin cooldown display
  var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(spinState.freeSpinNextAvailableAtMillis, spinState.freeSpinAvailable) {
    while (!spinState.freeSpinAvailable && spinState.freeSpinNextAvailableAtMillis > System.currentTimeMillis()) {
      nowMillis = System.currentTimeMillis()
      delay(1_000L)
    }
    nowMillis = System.currentTimeMillis()
  }

  val remainingCooldownMs =
    (spinState.freeSpinNextAvailableAtMillis - nowMillis).coerceAtLeast(0L)
  val isFreeSpinReady =
    spinState.freeSpinAvailable ||
      (spinState.freeSpinNextAvailableAtMillis > 0L && remainingCooldownMs == 0L)
  val isExtraSpinReady =
    spinState.extraSpinAvailable ||
      spinState.extraSpinVideosWatched >= SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS
  val canSpinNow =
    (isFreeSpinReady || isExtraSpinReady) &&
      !spinState.isSpinning &&
      !spinState.isReadyToClaim &&
      !spinState.isLoadingAd &&
      !spinState.isClaiming

  val triggerWheelSpin: () -> Unit = {
    if (canSpinNow) {
      if (onStartSpin != null) {
        onStartSpin { outcome ->
          scope.launch {
            val targetRotation =
              calculateTargetWheelRotation(
                currentRotation = rotation.value,
                segmentIndex = outcome.segmentIndex,
                segmentCount = PRIZES.size
              )
            rotation.animateTo(
              targetValue = targetRotation,
              animationSpec = tween(durationMillis = 3600, easing = FastOutSlowInEasing)
            )
            onWheelAnimationFinished()
          }
        }
      } else {
        // Fallback for isolated preview/tests without ViewModel callback
        scope.launch {
          val idx = 0
          val targetRotation =
            calculateTargetWheelRotation(
              currentRotation = rotation.value,
              segmentIndex = idx,
              segmentCount = PRIZES.size
            )
          rotation.animateTo(
            targetValue = targetRotation,
            animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing)
          )
          onClaimReward(PRIZES[idx].coins)
        }
      }
    }
  }

  // Result / Claim Dialog shown after wheel stops on the server-selected segment
  if (spinState.isReadyToClaim && spinState.selectedRewardCoins != null) {
    val rewardCoins = spinState.selectedRewardCoins
    AlertDialog(
      onDismissRequest = { /* Prevent accidental dismissal before claiming */ },
      modifier = Modifier.testTag("spin_result_claim_dialog"),
      shape = RoundedCornerShape(28.dp),
      containerColor = Color(0xFF0F172A),
      title = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Surface(
            shape = CircleShape,
            color = Color(0x35F59E0B),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFBBF24)),
            modifier = Modifier.size(72.dp)
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.Default.EmojiEvents,
                contentDescription = null,
                tint = Color(0xFFFBBF24),
                modifier = Modifier.size(42.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          Text(
            text = "Congratulations!",
            style = MaterialTheme.typography.headlineMedium,
            color = Color(0xFFFDE047),
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
          )
        }
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0x401E293B),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x60A855F7)),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
          ) {
            Column(
              modifier = Modifier.padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "WHEEL STOPPED ON",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "+${formatCoins(rewardCoins)}",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFFDE047),
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.testTag("spin_result_coins_text")
              )
            }
          }

          Text(
            text = "Tap Claim below to watch 1 short video and credit +${formatCoins(rewardCoins)} to your wallet.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFCBD5E1),
            textAlign = TextAlign.Center
          )

          if (!spinState.errorMessage.isNullOrBlank()) {
            Text(
              text = spinState.errorMessage,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFFCA5A5),
              textAlign = TextAlign.Center
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            if (onStartSpin != null) {
              onClaimSpinRewardWithAd()
            } else {
              onClaimReward(rewardCoins)
            }
          },
          enabled = !spinState.isLoadingAd && !spinState.isClaiming,
          shape = RoundedCornerShape(18.dp),
          colors =
            ButtonDefaults.buttonColors(
              containerColor = Color(0xFF10B981),
              contentColor = Color.White
            ),
          modifier = Modifier.fillMaxWidth().height(52.dp).testTag("claim_spin_reward_button")
        ) {
          if (spinState.isLoadingAd || spinState.isClaiming) {
            CircularProgressIndicator(
              color = Color.White,
              strokeWidth = 2.dp,
              modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
              text = "Verifying Claim...",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold
            )
          } else {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = "Claim +${formatCoins(rewardCoins)}",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.ExtraBold,
              color = Color.White
            )
          }
        }
      }
    )
  }

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 14.dp)
          .testTag("spin_and_earn_screen"),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Top Navigation Bar
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
              .testTag("spin_back_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface
          )
        }
        Text(
          text = "Spin & Earn",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.onBackground,
          fontWeight = FontWeight.Bold
        )
        Surface(
          shape = RoundedCornerShape(50),
          color = if (isFreeSpinReady) Color(0xFFECFDF5) else Color(0xFFFEF3C7),
          border =
            androidx.compose.foundation.BorderStroke(
              1.dp,
              if (isFreeSpinReady) Color(0xFF10B981) else Color(0xFFF59E0B)
            )
        ) {
          Text(
            text =
              when {
                isFreeSpinReady -> "1 Free Spin Ready"
                isExtraSpinReady -> "Extra Spin Ready"
                else -> "${spinState.extraSpinVideosWatched} / 2 Videos"
              },
            style = MaterialTheme.typography.labelMedium,
            color = if (isFreeSpinReady) Color(0xFF047857) else Color(0xFF92400E),
            fontWeight = FontWeight.Bold,
            modifier =
              Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag("spin_status_badge")
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Prize Wheel Section
      Box(
        modifier = Modifier.size(310.dp).padding(8.dp),
        contentAlignment = Alignment.Center
      ) {
        // Outer Glowing Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
          drawCircle(
            brush =
              Brush.sweepGradient(
                colors =
                  listOf(
                    Color(0xFFA855F7),
                    Color(0xFF06B6D4),
                    Color(0xFFEC4899),
                    Color(0xFFF59E0B),
                    Color(0xFFA855F7)
                  )
              ),
            radius = size.width / 2f,
            style = Stroke(width = 8.dp.toPx())
          )
        }

        // Rotating Wheel Canvas
        Box(
          modifier = Modifier.size(280.dp).rotate(rotation.value),
          contentAlignment = Alignment.Center
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = size.width
            val center = Offset(canvasSize / 2f, canvasSize / 2f)
            val radius = canvasSize / 2f
            val sweepAngle = 360f / PRIZES.size

            val textPaint =
              android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 13.sp.toPx()
                typeface =
                  android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                  )
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
              }

            PRIZES.forEachIndexed { index, prize ->
              val startAngle = index * sweepAngle
              drawArc(
                color = prize.color,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                size = Size(canvasSize, canvasSize)
              )
              drawArc(
                color = Color.White.copy(alpha = 0.28f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                size = Size(canvasSize, canvasSize),
                style = Stroke(width = 1.5.dp.toPx())
              )

              val midAngleRad = Math.toRadians((startAngle + sweepAngle / 2f).toDouble())
              val textRadius = radius * 0.65f
              val textX = center.x + (textRadius * cos(midAngleRad)).toFloat()
              val textY =
                center.y + (textRadius * sin(midAngleRad)).toFloat() + (textPaint.textSize / 3f)

              drawContext.canvas.nativeCanvas.save()
              drawContext.canvas.nativeCanvas.rotate(
                (startAngle + sweepAngle / 2f + 90f),
                textX,
                textY
              )
              drawContext.canvas.nativeCanvas.drawText("${prize.coins}", textX, textY, textPaint)
              drawContext.canvas.nativeCanvas.restore()
            }
          }
        }

        // Center Spin Button / Hub
        Surface(
          shape = CircleShape,
          color = Color(0xFF0F172A),
          border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF22D3EE)),
          modifier =
            Modifier.size(74.dp)
              .clip(CircleShape)
              .clickable(enabled = canSpinNow) { triggerWheelSpin() }
              .testTag("spin_wheel_center_button")
        ) {
          Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Spin Wheel",
                tint = if (canSpinNow) Color(0xFF22D3EE) else Color(0xFF94A3B8),
                modifier = Modifier.size(24.dp)
              )
              Text(
                text = "SPIN",
                style = MaterialTheme.typography.labelSmall,
                color = if (canSpinNow) Color.White else Color(0xFF94A3B8),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp
              )
            }
          }
        }

        // Top Pointer Indicator (aligned at 270° = 12 o'clock)
        Canvas(
          modifier = Modifier.align(Alignment.TopCenter).size(28.dp)
        ) {
          val path =
            Path().apply {
              moveTo(size.width / 2f, size.height)
              lineTo(0f, 0f)
              lineTo(size.width, 0f)
              close()
            }
          drawPath(path = path, color = Color(0xFFF59E0B))
          drawPath(path = path, color = Color(0xFF0F172A), style = Stroke(width = 2.dp.toPx()))
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      // Spin Status & Extra Spin Card
      NeonGlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = MaterialTheme.colorScheme.outlineVariant,
        backgroundColor = MaterialTheme.colorScheme.surface
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(18.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Text(
            text = "Spin Access & Rewards (10–150 Coins)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
          )

          if (isFreeSpinReady) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = Color(0xFFECFDF5),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981)),
              modifier = Modifier.fillMaxWidth().testTag("free_spin_ready_banner")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = Color(0xFF059669),
                  modifier = Modifier.size(22.dp)
                )
                Column {
                  Text(
                    text = "Daily Free Spin Available!",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF065F46),
                    fontWeight = FontWeight.ExtraBold
                  )
                  Text(
                    text = "1 Free Spin every 24 hours. Tap SPIN NOW to spin the wheel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF047857)
                  )
                }
              }
            }
          } else {
            // 24-Hour Free Spin Cooldown Banner
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = Color(0xFFE0F2FE),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8)),
              modifier = Modifier.fillMaxWidth().testTag("free_spin_cooldown_banner")
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.AccessTime,
                  contentDescription = null,
                  tint = Color(0xFF0284C7),
                  modifier = Modifier.size(22.dp)
                )
                Column {
                  Text(
                    text = "Next Free Spin in ${formatCooldownDuration(remainingCooldownMs)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF075985),
                    fontWeight = FontWeight.ExtraBold
                  )
                  Text(
                    text = "Want to spin right now? Watch 2 short videos below to unlock 1 Extra Spin!",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF0369A1)
                  )
                }
              }
            }

            // Extra Spin (2 Rewarded Videos -> 1 Extra Spin)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Extra Spin Progress",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.onSurface,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text =
                    if (isExtraSpinReady) {
                      "2 / 2 Videos Completed ✓"
                    } else {
                      "${spinState.extraSpinVideosWatched} / ${SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS} Videos Completed"
                    },
                  style = MaterialTheme.typography.labelMedium,
                  color = if (isExtraSpinReady) Color(0xFF059669) else Color(0xFFD97706),
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.testTag("extra_spin_progress_text")
                )
              }

              if (!isExtraSpinReady) {
                Button(
                  onClick = { onWatchExtraSpinVideo() },
                  enabled = !spinState.isLoadingAd && !spinState.isSpinning && !spinState.isClaiming,
                  shape = RoundedCornerShape(12.dp),
                  colors =
                    ButtonDefaults.buttonColors(
                      containerColor = Color(0xFF4F46E5),
                      contentColor = Color.White,
                      disabledContainerColor = Color(0xFFE2E8F0),
                      disabledContentColor = Color(0xFF64748B)
                    ),
                  modifier =
                    Modifier.fillMaxWidth()
                      .height(48.dp)
                      .testTag("watch_extra_spin_video_button")
                ) {
                  if (spinState.isLoadingAd) {
                    CircularProgressIndicator(
                      color = Color.White,
                      strokeWidth = 2.dp,
                      modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Loading Video...", fontWeight = FontWeight.Bold)
                  } else {
                    Icon(
                      Icons.Default.PlayArrow,
                      contentDescription = null,
                      modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text =
                        "Watch Video for Extra Spin (${spinState.extraSpinVideosWatched} / ${SpinWheelConstants.EXTRA_SPIN_REQUIRED_VIDEOS})",
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          }

          if (!spinState.statusMessage.isNullOrBlank()) {
            Text(
              text = spinState.statusMessage,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF0284C7),
              fontWeight = FontWeight.SemiBold
            )
          }

          if (!spinState.errorMessage.isNullOrBlank()) {
            Text(
              text = spinState.errorMessage,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFDC2626),
              fontWeight = FontWeight.SemiBold
            )
          }

          Button(
            onClick = { triggerWheelSpin() },
            enabled = canSpinNow,
            shape = RoundedCornerShape(14.dp),
            colors =
              ButtonDefaults.buttonColors(
                containerColor = Color(0xFF059669),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFE2E8F0),
                disabledContentColor = Color(0xFF475569)
              ),
            modifier = Modifier.fillMaxWidth().height(50.dp).testTag("spin_now_main_button")
          ) {
            Text(
              text =
                when {
                  spinState.isSpinning -> "SPINNING..."
                  isFreeSpinReady -> "SPIN NOW (FREE)"
                  isExtraSpinReady -> "USE EXTRA SPIN NOW"
                  else -> "WATCH 2 VIDEOS TO UNLOCK SPIN"
                },
              fontWeight = FontWeight.ExtraBold,
              fontSize = 15.sp,
              color = if (canSpinNow) Color.White else Color(0xFF475569)
            )
          }
        }
      }
    }
  }
}

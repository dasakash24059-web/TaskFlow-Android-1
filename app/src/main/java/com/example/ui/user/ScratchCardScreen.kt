package com.example.ui.user

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ScratchCardConstants
import com.example.data.model.ScratchCardUiState
import com.example.data.model.formatCoins
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.TaskFlowBrandLogo
import com.example.util.findActivity

/**
 * Premium Scratch Card Screen:
 * - Realistic interactive scratch surface with smooth touch erase.
 * - Subtle diagonal TaskFlow watermark behind the revealed reward.
 * - Clear coin amount display.
 * - Clean Congratulations popup with simple CLAIM button.
 * - Daily rules: 1st free per day, then 2 completed videos unlock 1 scratch (max 50/day).
 * - Avoids unnecessary text, badges, counters or decorative sections.
 */
@Composable
fun ScratchCardScreen(
  uiState: ScratchCardUiState,
  onBack: () -> Unit,
  onWatchAd: (Activity) -> Unit,
  onScratchRevealed: () -> Unit,
  onClaimReward: () -> Unit,
  onDismissCongratulations: () -> Unit,
  onClearFeedback: () -> Unit = {},
  onRetryPrepare: () -> Unit = {}
) {
  BackHandler(onBack = onBack)
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }

  val dailyState = uiState.dailyState
  val isDailyLimitReached = dailyState.isDailyLimitReached
  val canScratchNow = dailyState.canScratchNow && uiState.activeSession != null
  val isScratched = uiState.isScratched
  val revealedCoins = uiState.revealedCoins

  // Interactive scratch tracking state
  val scratchPath = remember(uiState.activeSession?.cardId) { Path() }
  val scratchedCells = remember(uiState.activeSession?.cardId) { BooleanArray(100) }
  var pathVersion by remember(uiState.activeSession?.cardId) { mutableIntStateOf(0) }
  var localRevealed by remember(uiState.activeSession?.cardId, isScratched) { mutableStateOf(isScratched) }

  LaunchedEffect(isScratched) {
    if (isScratched) localRevealed = true
  }

  val foilAlpha by animateFloatAsState(
    targetValue = if (localRevealed) 0f else 1f,
    animationSpec = tween(durationMillis = 350),
    label = "foil_fade"
  )

  // Congratulations Popup
  if (uiState.showCongratulations && revealedCoins > 0L) {
    AlertDialog(
      onDismissRequest = onDismissCongratulations,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(24.dp),
      icon = {
        Surface(
          shape = CircleShape,
          color = Color(0xFFFEF3C7),
          border = BorderStroke(2.dp, Color(0xFFF59E0B)),
          modifier = Modifier.size(64.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Default.EmojiEvents,
              contentDescription = null,
              tint = Color(0xFFD97706),
              modifier = Modifier.size(36.dp)
            )
          }
        }
      },
      title = {
        Text(
          text = "Congratulations!",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().testTag("congratulations_title")
        )
      },
      text = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "You won",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "+${formatCoins(revealedCoins)} Coins",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Black,
            color = Color(0xFFD97706),
            modifier = Modifier.testTag("scratch_reward_amount_text")
          )
        }
      },
      confirmButton = {
        Button(
          onClick = onClaimReward,
          enabled = !uiState.isClaiming,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFD97706),
            contentColor = Color.White
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("claim_scratch_reward_button")
        ) {
          if (uiState.isClaiming) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              color = Color.White,
              strokeWidth = 2.5.dp
            )
          } else {
            Text("CLAIM", fontWeight = FontWeight.Bold, fontSize = 16.sp)
          }
        }
      }
    )
  }

  NeonBackgroundBox {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 14.dp)
        .testTag("scratch_card_screen"),
      verticalArrangement = Arrangement.spacedBy(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally
    ) {
      // Top Navigation Bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .testTag("scratch_card_back_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = MaterialTheme.colorScheme.onSurface
          )
        }
        TaskFlowBrandLogo(size = 30.dp)
        Text(
          text = "Scratch & Win",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground
        )
      }

      // Feedback / Error Banner
      AnimatedVisibility(visible = uiState.errorMessage != null) {
        uiState.errorMessage?.let { error ->
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onClearFeedback() }
              .testTag("scratch_error_banner")
          ) {
            Text(
              text = error,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp)
            )
          }
        }
      }

      AnimatedVisibility(visible = uiState.successMessage != null) {
        uiState.successMessage?.let { success ->
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFDCFCE7),
            modifier = Modifier
              .fillMaxWidth()
              .clickable { onClearFeedback() }
              .testTag("scratch_success_banner")
          ) {
            Text(
              text = success,
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.SemiBold,
              color = Color(0xFF166534),
              modifier = Modifier.padding(12.dp)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // The Realistic Scratch Card Container
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(280.dp)
          .shadow(12.dp, RoundedCornerShape(24.dp))
          .clip(RoundedCornerShape(24.dp))
          .testTag("interactive_scratch_card")
      ) {
        // LAYER 1: Underneath (Revealed Reward Layer with subtle diagonal TaskFlow watermark)
        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(
              Brush.verticalGradient(
                colors = listOf(Color(0xFF2E1065), Color(0xFF4C1D95), Color(0xFF1E1B4B))
              )
            )
            .border(2.dp, Color(0xFFC084FC).copy(alpha = 0.5f), RoundedCornerShape(24.dp))
        ) {
          // Subtle diagonal watermark
          Canvas(modifier = Modifier.fillMaxSize()) {
            val textPaint = android.graphics.Paint().apply {
              color = android.graphics.Color.WHITE
              alpha = 20
              textSize = 28.sp.toPx()
              isFakeBoldText = true
              isAntiAlias = true
            }
            drawIntoCanvas { canvas ->
              val nativeCanvas = canvas.nativeCanvas
              nativeCanvas.save()
              nativeCanvas.rotate(-30f, size.width / 2f, size.height / 2f)
              val stepX = 140.dp.toPx()
              val stepY = 60.dp.toPx()
              val startX = -size.width
              val endX = size.width * 2
              val startY = -size.height
              val endY = size.height * 2
              var currX = startX
              while (currX <= endX) {
                var currY = startY
                while (currY <= endY) {
                  nativeCanvas.drawText("TaskFlow", currX, currY, textPaint)
                  currY += stepY
                }
                currX += stepX
              }
              nativeCanvas.restore()
            }
          }

          // Center revealed reward content
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
          ) {
            Surface(
              shape = CircleShape,
              color = Color(0xFFFEF3C7),
              border = BorderStroke(2.dp, Color(0xFFF59E0B)),
              modifier = Modifier.size(56.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.MonetizationOn,
                  contentDescription = null,
                  tint = Color(0xFFD97706),
                  modifier = Modifier.size(34.dp)
                )
              }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
              text = "+${formatCoins(revealedCoins)}",
              fontSize = 44.sp,
              fontWeight = FontWeight.Black,
              color = Color(0xFFFDE047),
              letterSpacing = 1.sp
            )
            Text(
              text = "COINS",
              fontSize = 15.sp,
              fontWeight = FontWeight.ExtraBold,
              color = Color(0xFFFBBF24),
              letterSpacing = 2.sp
            )
          }
        }

        // LAYER 2: Scratch Foil Surface (Erased smoothly on user drag)
        if (foilAlpha > 0f) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .alpha(foilAlpha)
          ) {
            Canvas(
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .pointerInput(canScratchNow, localRevealed) {
                  if (!canScratchNow || localRevealed) return@pointerInput
                  detectDragGestures(
                    onDragStart = { offset ->
                      scratchPath.moveTo(offset.x, offset.y)
                      val cx = ((offset.x / size.width) * 10).toInt().coerceIn(0, 9)
                      val cy = ((offset.y / size.height) * 10).toInt().coerceIn(0, 9)
                      scratchedCells[cy * 10 + cx] = true
                      pathVersion++
                    },
                    onDrag = { change, _ ->
                      change.consume()
                      val pos = change.position
                      scratchPath.lineTo(pos.x, pos.y)
                      val cx = ((pos.x / size.width) * 10).toInt().coerceIn(0, 9)
                      val cy = ((pos.y / size.height) * 10).toInt().coerceIn(0, 9)
                      scratchedCells[cy * 10 + cx] = true
                      pathVersion++

                      // Check scratch percentage threshold (40%)
                      val filled = scratchedCells.count { it }
                      if (filled >= 40 && !localRevealed) {
                        localRevealed = true
                        onScratchRevealed()
                      }
                    }
                  )
                }
            ) {
              // Read pathVersion to trigger recomposition of canvas draw
              @Suppress("UNUSED_VARIABLE")
              val trigger = pathVersion

              // 1. Draw metallic foil gradient
              drawRoundRect(
                brush = Brush.linearGradient(
                  colors = listOf(
                    Color(0xFFF1F5F9),
                    Color(0xFFCBD5E1),
                    Color(0xFF94A3B8),
                    Color(0xFFE2E8F0),
                    Color(0xFFCBD5E1)
                  ),
                  start = Offset(0f, 0f),
                  end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(24.dp.toPx(), 24.dp.toPx())
              )

              // 2. Erase the scratched path with BlendMode.Clear
              drawPath(
                path = scratchPath,
                color = Color.Transparent,
                style = Stroke(
                  width = 46.dp.toPx(),
                  cap = StrokeCap.Round,
                  join = StrokeJoin.Round
                ),
                blendMode = BlendMode.Clear
              )
            }

            // Instructional text on the foil (fades out as path is scratched)
            if (pathVersion == 0 && !localRevealed) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Surface(
                  shape = CircleShape,
                  color = Color.White.copy(alpha = 0.5f),
                  border = BorderStroke(1.dp, Color(0xFF64748B)),
                  modifier = Modifier.size(50.dp)
                ) {
                  Box(contentAlignment = Alignment.Center) {
                    Icon(
                      imageVector = Icons.Default.CardGiftcard,
                      contentDescription = null,
                      tint = Color(0xFF334155),
                      modifier = Modifier.size(28.dp)
                    )
                  }
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                  text = if (canScratchNow) "SCRATCH HERE" else "LOCKED",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.ExtraBold,
                  letterSpacing = 2.sp,
                  color = Color(0xFF1E293B)
                )
                if (canScratchNow) {
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "Swipe to reveal your reward",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475569)
                  )
                }
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Action / Unlock Area
      if (isDailyLimitReached) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier.fillMaxWidth()
        ) {
          Text(
            text = "You have completed all available scratches for today. Please check back tomorrow!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(16.dp)
          )
        }
      } else if (localRevealed && uiState.activeSession != null) {
        Button(
          onClick = onClaimReward,
          enabled = !uiState.isClaiming,
          shape = RoundedCornerShape(14.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFFD97706),
            contentColor = Color.White
          ),
          modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .testTag("claim_scratch_button")
        ) {
          if (uiState.isClaiming) {
            CircularProgressIndicator(
              modifier = Modifier.size(22.dp),
              color = Color.White,
              strokeWidth = 2.5.dp
            )
          } else {
            Text("CLAIM REWARD", fontWeight = FontWeight.Bold, fontSize = 16.sp)
          }
        }
      } else if (dailyState.canScratchNow && uiState.activeSession == null) {
        NeonGlassCard(
          shape = RoundedCornerShape(20.dp),
          borderColor = Color(0xFF93C5FD).copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            if (uiState.isLoading) {
              CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
              Text(
                text = "Preparing your Scratch Card...",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
              )
            } else {
              Text(
                text = "Your Scratch Card is ready",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
              )
              Button(
                onClick = onRetryPrepare,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
                  contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(48.dp)
                  .testTag("retry_prepare_scratch_button")
              ) {
                Icon(
                  imageVector = Icons.Default.Refresh,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Retry Loading Scratch Card",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp
                )
              }
            }
          }
        }
      } else if (!canScratchNow) {
        // User needs to watch rewarded ads to unlock the next scratch
        NeonGlassCard(
          shape = RoundedCornerShape(20.dp),
          borderColor = Color(0xFF93C5FD).copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            val adsWatched = dailyState.adsWatchedForNextScratch
            val stepLabel = if (adsWatched == 0L) "Video 1 of 2" else "Video 2 of 2"

            Text(
              text = "Watch 2 videos to unlock your next Scratch Card",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              textAlign = TextAlign.Center
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Surface(
                shape = RoundedCornerShape(50),
                color = if (adsWatched >= 1L) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, if (adsWatched >= 1L) Color(0xFF16A34A) else Color(0xFFCBD5E1))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Icon(
                    imageVector = if (adsWatched >= 1L) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (adsWatched >= 1L) Color(0xFF16A34A) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                  )
                  Text(
                    text = "Video 1",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (adsWatched >= 1L) Color(0xFF166534) else Color(0xFF64748B)
                  )
                }
              }

              Surface(
                shape = RoundedCornerShape(50),
                color = if (adsWatched >= 2L) Color(0xFFDCFCE7) else Color(0xFFF1F5F9),
                border = BorderStroke(1.dp, if (adsWatched >= 2L) Color(0xFF16A34A) else Color(0xFFCBD5E1))
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Icon(
                    imageVector = if (adsWatched >= 2L) Icons.Default.CheckCircle else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = if (adsWatched >= 2L) Color(0xFF16A34A) else Color(0xFF64748B),
                    modifier = Modifier.size(16.dp)
                  )
                  Text(
                    text = "Video 2",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (adsWatched >= 2L) Color(0xFF166534) else Color(0xFF64748B)
                  )
                }
              }
            }

            Button(
              onClick = {
                if (activity != null) {
                  onWatchAd(activity)
                }
              },
              enabled = !uiState.isAdLoading,
              shape = RoundedCornerShape(14.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              ),
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("watch_scratch_ad_button")
            ) {
              if (uiState.isAdLoading) {
                CircularProgressIndicator(
                  modifier = Modifier.size(20.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  strokeWidth = 2.dp
                )
              } else {
                Icon(
                  imageVector = Icons.Default.PlayArrow,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Watch $stepLabel",
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp
                )
              }
            }
          }
        }
      }
    }
  }
}

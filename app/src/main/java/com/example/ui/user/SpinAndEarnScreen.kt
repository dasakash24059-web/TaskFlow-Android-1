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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonGlassCard
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class SpinPrize(
  val coins: Long,
  val label: String,
  val color: Color
)

val PRIZES = listOf(
  SpinPrize(50L, "50 Coins", Color(0xFFA855F7)),
  SpinPrize(100L, "100 Coins", Color(0xFF06B6D4)),
  SpinPrize(125L, "125 Coins", Color(0xFFEC4899)),
  SpinPrize(200L, "200 Coins", Color(0xFFF59E0B)),
  SpinPrize(250L, "250 Coins", Color(0xFF10B981)),
  SpinPrize(300L, "300 Coins", Color(0xFF6366F1)),
  SpinPrize(400L, "400 Coins", Color(0xFFF43F5E)),
  SpinPrize(500L, "500 Coins", Color(0xFFEAB308))
)

@Composable
fun SpinAndEarnScreen(
  onBack: () -> Unit,
  onClaimReward: (Long) -> Unit = {},
  onShowRewardedAd: (onAdCompleted: () -> Unit) -> Unit = { it() },
  onShowInterstitialAd: (onAdCompleted: () -> Unit) -> Unit = { it() }
) {
  BackHandler(onBack = onBack)

  var spinsUsed by rememberSaveable { mutableIntStateOf(0) }
  val maxSpins = 50

  // Requirement flow: 1st spin free, then 2 rewarded ads + 1 interstitial ad per spin
  var adsWatchedForCurrentSpin by rememberSaveable { mutableIntStateOf(0) }
  var interstitialWatchedForCurrentSpin by rememberSaveable { mutableStateOf(false) }

  var isSpinning by remember { mutableStateOf(false) }
  var winningPrize by remember { mutableStateOf<SpinPrize?>(null) }
  var showRewardDialog by remember { mutableStateOf(false) }
  var adStatusMessage by remember { mutableStateOf("") }

  val rotation = remember { Animatable(0f) }
  val scope = rememberCoroutineScope()

  val isFirstSpin = spinsUsed == 0
  val isAdRequirementMet = isFirstSpin || (adsWatchedForCurrentSpin >= 2 && interstitialWatchedForCurrentSpin)

  if (showRewardDialog && winningPrize != null) {
    AlertDialog(
      onDismissRequest = {
        showRewardDialog = false
      },
      shape = RoundedCornerShape(28.dp),
      containerColor = Color(0xFF0F172A),
      title = {
        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          // Trophy Icon / Gold Laurel Box
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
          horizontalAlignment = Alignment.CenterHorizontally
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
                text = "YOU WON",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF94A3B8),
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "+${winningPrize?.coins} Coins",
                style = MaterialTheme.typography.displaySmall,
                color = Color(0xFFFDE047),
                fontWeight = FontWeight.ExtraBold
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            winningPrize?.let { onClaimReward(it.coins) }
            showRewardDialog = false
          },
          shape = RoundedCornerShape(18.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF7C3AED),
            contentColor = Color.White
          ),
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("claim_spin_reward_button")
        ) {
          Text(
            text = "Awesome!",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
          )
        }
      }
    )
  }

  NeonBackgroundBox {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 14.dp),
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
          modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x30A855F7))
            .border(1.dp, Color(0x60A855F7), CircleShape)
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back",
            tint = Color.White
          )
        }
        Text(
          text = "Spin & Earn",
          style = MaterialTheme.typography.titleLarge,
          color = Color.White,
          fontWeight = FontWeight.Bold
        )
        Surface(
          shape = RoundedCornerShape(50),
          color = Color(0x30F59E0B),
          border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x80FBBF24))
        ) {
          Text(
            text = "$spinsUsed / $maxSpins Spins",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFFDE047),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      // Prize Wheel Section
      Box(
        modifier = Modifier
          .size(310.dp)
          .padding(8.dp),
        contentAlignment = Alignment.Center
      ) {
        // Outer Glowing Ring
        Canvas(modifier = Modifier.fillMaxSize()) {
          drawCircle(
            brush = Brush.sweepGradient(
              colors = listOf(
                Color(0xFFA855F7), Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFFF59E0B), Color(0xFFA855F7)
              )
            ),
            radius = size.width / 2f,
            style = Stroke(width = 8.dp.toPx())
          )
        }

        // Rotating Wheel Canvas
        Box(
          modifier = Modifier
            .size(280.dp)
            .rotate(rotation.value),
          contentAlignment = Alignment.Center
        ) {
          Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasSize = size.width
            val center = Offset(canvasSize / 2f, canvasSize / 2f)
            val radius = canvasSize / 2f
            val sweepAngle = 360f / PRIZES.size

            val textPaint = android.graphics.Paint().apply {
              color = android.graphics.Color.WHITE
              textSize = 13.sp.toPx()
              typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
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
                color = Color.White.copy(alpha = 0.2f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = true,
                size = Size(canvasSize, canvasSize),
                style = Stroke(width = 1.dp.toPx())
              )

              // Draw Prize Number Text in center of segment
              val midAngleRad = Math.toRadians((startAngle + sweepAngle / 2f).toDouble())
              val textRadius = radius * 0.65f
              val textX = center.x + (textRadius * cos(midAngleRad)).toFloat()
              val textY = center.y + (textRadius * sin(midAngleRad)).toFloat() + (textPaint.textSize / 3f)

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
          color = Color(0xFF070A17),
          border = androidx.compose.foundation.BorderStroke(3.dp, Color(0xFF22D3EE)),
          modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .clickable(enabled = !isSpinning && spinsUsed < maxSpins) {
              if (!isAdRequirementMet) {
                adStatusMessage = "Please watch the required ads first to unlock your next spin."
              } else {
                scope.launch {
                  isSpinning = true
                  adStatusMessage = ""
                  val winningIndex = Random.nextInt(PRIZES.size)
                  val targetPrize = PRIZES[winningIndex]
                  winningPrize = targetPrize

                  val degreesPerSegment = 360f / PRIZES.size
                  val targetAngle = 360f * 5 + (360f - (winningIndex * degreesPerSegment + degreesPerSegment / 2f))

                  rotation.animateTo(
                    targetValue = rotation.value + targetAngle,
                    animationSpec = tween(durationMillis = 3500, easing = FastOutSlowInEasing)
                  )

                  spinsUsed += 1
                  adsWatchedForCurrentSpin = 0
                  interstitialWatchedForCurrentSpin = false
                  isSpinning = false
                  showRewardDialog = true
                }
              }
            }
        ) {
          Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = Color(0xFF22D3EE),
                modifier = Modifier.size(24.dp)
              )
              Text(
                text = "SPIN",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 11.sp
              )
            }
          }
        }

        // Top Pointer Indicator
        Canvas(
          modifier = Modifier
            .align(Alignment.TopCenter)
            .size(28.dp)
        ) {
          val path = Path().apply {
            moveTo(size.width / 2f, size.height)
            lineTo(0f, 0f)
            lineTo(size.width, 0f)
            close()
          }
          drawPath(path = path, color = Color(0xFFFDE047))
          drawPath(path = path, color = Color.White, style = Stroke(width = 2.dp.toPx()))
        }
      }

      Spacer(modifier = Modifier.height(20.dp))

      // Ad Requirement Progress Section
      NeonGlassCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = Color(0x60A855F7),
        backgroundColor = Color(0x40141938)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Text(
            text = "Spin Access Status",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            fontWeight = FontWeight.Bold
          )

          if (isFirstSpin) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = Color(0x2510B981),
              border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x8034D399)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "🎉 1st Spin is FREE! Tap SPIN to win your initial reward.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF34D399),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(12.dp)
              )
            }
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = "Watch 2 Rewarded Video Ads + 1 Interstitial Ad to unlock each spin:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8)
              )

              // Button 1: Rewarded Ads (Need 2)
              Button(
                onClick = {
                  onShowRewardedAd {
                    if (adsWatchedForCurrentSpin < 2) {
                      adsWatchedForCurrentSpin += 1
                    }
                  }
                },
                enabled = adsWatchedForCurrentSpin < 2 && !isSpinning,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF7C3AED),
                  disabledContainerColor = Color(0x307C3AED)
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = "Watch Video Ad (${adsWatchedForCurrentSpin} / 2 Complete)",
                  fontWeight = FontWeight.Bold
                )
              }

              // Button 2: Interstitial Ad (Need 1)
              Button(
                onClick = {
                  onShowInterstitialAd {
                    interstitialWatchedForCurrentSpin = true
                  }
                },
                enabled = !interstitialWatchedForCurrentSpin && !isSpinning,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                  containerColor = Color(0xFF0284C7),
                  disabledContainerColor = Color(0x300284C7)
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                  text = if (interstitialWatchedForCurrentSpin) "Interstitial Ad Viewed ✓" else "Watch Screen Interstitial Ad",
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }

          if (adStatusMessage.isNotBlank()) {
            Text(
              text = adStatusMessage,
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFFFCA5A5),
              fontWeight = FontWeight.SemiBold
            )
          }

          Button(
            onClick = {
              if (isAdRequirementMet && spinsUsed < maxSpins && !isSpinning) {
                scope.launch {
                  isSpinning = true
                  adStatusMessage = ""
                  val winningIndex = Random.nextInt(PRIZES.size)
                  val targetPrize = PRIZES[winningIndex]
                  winningPrize = targetPrize

                  val degreesPerSegment = 360f / PRIZES.size
                  val targetAngle = 360f * 5 + (360f - (winningIndex * degreesPerSegment + degreesPerSegment / 2f))

                  rotation.animateTo(
                    targetValue = rotation.value + targetAngle,
                    animationSpec = tween(durationMillis = 3500, easing = FastOutSlowInEasing)
                  )

                  spinsUsed += 1
                  adsWatchedForCurrentSpin = 0
                  interstitialWatchedForCurrentSpin = false
                  isSpinning = false
                  showRewardDialog = true
                }
              }
            },
            enabled = isAdRequirementMet && !isSpinning && spinsUsed < maxSpins,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = Color(0xFF10B981),
              disabledContainerColor = Color(0x3010B981)
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("spin_now_main_button")
          ) {
            Text(
              text = if (isSpinning) "SPINNING..." else "SPIN NOW",
              fontWeight = FontWeight.ExtraBold,
              fontSize = 16.sp
            )
          }
        }
      }
    }
  }
}

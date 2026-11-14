package com.example.ui.user

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.DemoWithdrawalRequest
import com.example.data.model.WithdrawalAvailabilityConfig
import com.example.data.model.WithdrawalCatalog
import com.example.data.model.WithdrawalDenomination
import com.example.data.model.WithdrawalMethodType
import com.example.data.model.WithdrawalStatus
import com.example.data.model.formatCoins
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonCoinBadge
import com.example.ui.viewmodel.WithdrawalScreenRoute
import java.text.SimpleDateFormat
import java.util.Locale

private data class MethodTileMeta(
  val title: String,
  val methodKey: String,
  val route: WithdrawalScreenRoute,
  val icon: ImageVector,
  val accentColor: Color,
  val containerTint: Color,
  val testTag: String
)

/**
 * Clean, minimal Withdrawal Main Screen displaying the 4 payout/redemption options in a
 * 2-column grid plus an entry to Withdrawal History.
 * Intentionally omits conversion rates, minimum withdrawal rules, pending counts, and
 * financial explanations per product requirements.
 */
@Composable
fun WithdrawalMainScreen(
  totalBalanceCoins: Long,
  availability: WithdrawalAvailabilityConfig,
  onSelectRoute: (WithdrawalScreenRoute) -> Unit,
  onOpenHistory: () -> Unit,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  val options =
    remember {
      listOf(
        MethodTileMeta(
          title = "UPI",
          methodKey = WithdrawalMethodType.UPI,
          route = WithdrawalScreenRoute.UPI,
          icon = Icons.Default.QrCode2,
          accentColor = Color(0xFF1E3A8A),
          containerTint = Color(0xFFDBEAFE),
          testTag = "withdrawal_option_upi"
        ),
        MethodTileMeta(
          title = "Amazon Gift Card",
          methodKey = WithdrawalMethodType.AMAZON_GIFT_CARD,
          route = WithdrawalScreenRoute.AMAZON_GIFT_CARD,
          icon = Icons.Default.CardGiftcard,
          accentColor = Color(0xFFD97706),
          containerTint = Color(0xFFFEF3C7),
          testTag = "withdrawal_option_amazon"
        ),
        MethodTileMeta(
          title = "Google Play Gift Code",
          methodKey = WithdrawalMethodType.GOOGLE_PLAY_GIFT_CARD,
          route = WithdrawalScreenRoute.GOOGLE_PLAY_GIFT_CARD,
          icon = Icons.Default.PlayArrow,
          accentColor = Color(0xFF0F766E),
          containerTint = Color(0xFFCCFBF1),
          testTag = "withdrawal_option_google_play"
        ),
        MethodTileMeta(
          title = "Flipkart Gift Card",
          methodKey = WithdrawalMethodType.FLIPKART_GIFT_CARD,
          route = WithdrawalScreenRoute.FLIPKART_GIFT_CARD,
          icon = Icons.Default.ShoppingBag,
          accentColor = Color(0xFF1D4ED8),
          containerTint = Color(0xFFDBEAFE),
          testTag = "withdrawal_option_flipkart"
        )
      )
    }

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp)
          .testTag("withdrawal_main_screen"),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("withdrawal_main_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back to Wallet"
            )
          }
          Text(
            text = "Withdraw",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
          )
        }

        Surface(
          shape = RoundedCornerShape(50),
          color = Color(0xFFFEF3C7),
          border = BorderStroke(1.dp, Color(0xFFF59E0B))
        ) {
          Text(
            text = formatCoins(totalBalanceCoins),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFB45309),
            modifier =
              Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("withdrawal_main_balance_text")
          )
        }
      }

      Text(
        text = "Choose a redemption option",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      options.chunked(2).forEach { rowItems ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          rowItems.forEach { item ->
            val enabled = availability.isMethodEnabled(item.methodKey)
            WithdrawalMethodGridCard(
              meta = item,
              enabled = enabled,
              onClick = { onSelectRoute(item.route) },
              modifier = Modifier.weight(1f)
            )
          }
        }
      }

      Card(
        modifier =
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onOpenHistory)
            .testTag("withdrawal_open_history_button"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
              modifier = Modifier.size(44.dp)
            ) {
              Box(contentAlignment = Alignment.Center) {
                Icon(
                  imageVector = Icons.Default.History,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(22.dp)
                )
              }
            }
            Column {
              Text(
                text = "Withdrawal History",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Track requests & view gift codes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Open Withdrawal History",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}

@Composable
private fun WithdrawalMethodGridCard(
  meta: MethodTileMeta,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier =
      modifier
        .height(152.dp)
        .clip(RoundedCornerShape(18.dp))
        .clickable(onClick = onClick)
        .testTag(meta.testTag),
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    border =
      BorderStroke(
        width = 1.dp,
        color =
          if (enabled) MaterialTheme.colorScheme.outlineVariant
          else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
      ),
    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize().padding(16.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
      ) {
        com.example.ui.components.WithdrawalBrandLogo(
          methodKey = meta.methodKey,
          size = 48.dp
        )
        if (!enabled) {
          Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.errorContainer
          ) {
            Text(
              text = "Unavailable",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              fontWeight = FontWeight.SemiBold,
              modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
          }
        } else {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
          )
        }
      }

      Text(
        text = meta.title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color =
          if (enabled) MaterialTheme.colorScheme.onSurface
          else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

/**
 * UPI Redemption Screen with fixed selectable denominations ONLY:
 * - ₹200 = 33,000 Coins
 * - ₹500 = 82,500 Coins
 */
@Composable
fun UpiRedemptionScreen(
  totalBalanceCoins: Long,
  availability: WithdrawalAvailabilityConfig,
  isBusy: Boolean,
  validationError: String?,
  onClearError: () -> Unit,
  onSubmit: (amountRupees: Long, upiId: String) -> Unit,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  val denominations = WithdrawalCatalog.UPI_DENOMINATIONS
  var selectedRupees by rememberSaveable {
    mutableStateOf(
      denominations
        .firstOrNull { availability.isDenominationAvailable(WithdrawalMethodType.UPI, it.amountRupees) }
        ?.amountRupees ?: 200L
    )
  }
  var upiId by rememberSaveable { mutableStateOf("") }
  var localError by rememberSaveable { mutableStateOf<String?>(null) }

  val selectedDenom =
    denominations.firstOrNull { it.amountRupees == selectedRupees } ?: denominations.first()
  val isSelectedAvailable =
    availability.isDenominationAvailable(WithdrawalMethodType.UPI, selectedDenom.amountRupees)
  val displayedError = localError ?: validationError

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp)
          .testTag("upi_redemption_screen"),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("upi_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back"
            )
          }
          com.example.ui.components.WithdrawalBrandLogo(
            methodKey = WithdrawalMethodType.UPI,
            size = 38.dp
          )
          Text(
            text = "UPI Withdrawal",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        NeonCoinBadge(coins = totalBalanceCoins)
      }

      Text(
        text = "Select Amount",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        denominations.forEach { denom ->
          val available =
            availability.isDenominationAvailable(WithdrawalMethodType.UPI, denom.amountRupees)
          DenominationCard(
            denomination = denom,
            selected = selectedRupees == denom.amountRupees,
            available = available,
            accentColor = Color(0xFF1E3A8A),
            onClick = {
              if (available) {
                selectedRupees = denom.amountRupees
                localError = null
                onClearError()
              }
            },
            modifier = Modifier.weight(1f).testTag("upi_denom_${denom.amountRupees}")
          )
        }
      }

      OutlinedTextField(
        value = upiId,
        onValueChange = {
          upiId = it
          localError = null
          onClearError()
        },
        label = { Text("Enter UPI ID") },
        placeholder = { Text("yourname@upi") },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().testTag("upi_id_input")
      )

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Review Summary",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
          SummaryRow("Method", "UPI")
          SummaryRow("Amount", "₹${selectedDenom.amountRupees}")
          SummaryRow("Coins Required", formatCoins(selectedDenom.amountCoins))
          if (upiId.trim().isNotEmpty()) {
            SummaryRow("UPI ID", upiId.trim())
          }
        }
      }

      if (!displayedError.isNullOrBlank()) {
        ErrorNoticeBanner(
          message = displayedError,
          testTag = "upi_validation_error"
        )
      }

      Button(
        onClick = {
          val cleanedUpi = upiId.trim()
          if (!isSelectedAvailable) {
            localError = "Selected UPI denomination is currently unavailable."
            return@Button
          }
          if (cleanedUpi.isEmpty() || !cleanedUpi.contains("@") || cleanedUpi.contains(" ")) {
            localError = "Please enter a valid UPI ID (e.g., name@upi)."
            return@Button
          }
          if (totalBalanceCoins < selectedDenom.amountCoins) {
            localError =
              "Insufficient balance. You need ${formatCoins(selectedDenom.amountCoins)} to withdraw ₹${selectedDenom.amountRupees}."
            return@Button
          }
          localError = null
          onSubmit(selectedDenom.amountRupees, cleanedUpi)
        },
        enabled = !isBusy && isSelectedAvailable,
        shape = RoundedCornerShape(14.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E3A8A),
            contentColor = Color.White
          ),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("upi_submit_button")
      ) {
        if (isBusy) {
          CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp)
          )
        } else {
          Text(
            text = "Submit UPI Request",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}

/**
 * Separate Gift Card Redemption Screen supporting Amazon Gift Card, Google Play Gift Code, and
 * Flipkart Gift Card with fixed denominations ONLY:
 * - ₹150 = 33,000 Coins
 * - ₹200 = 44,000 Coins
 * - ₹250 = 55,000 Coins
 * Does NOT collect email or phone numbers; codes are delivered manually in-app.
 */
@Composable
fun GiftCardRedemptionScreen(
  method: String,
  totalBalanceCoins: Long,
  availability: WithdrawalAvailabilityConfig,
  isBusy: Boolean,
  validationError: String?,
  onClearError: () -> Unit,
  onSubmit: (amountRupees: Long) -> Unit,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  val denominations = WithdrawalCatalog.GIFT_CARD_DENOMINATIONS
  val methodTitle = WithdrawalMethodType.displayLabel(method)
  val accentColor = Color(0xFF1E3A8A)

  var selectedRupees by rememberSaveable(method) {
    mutableStateOf(
      denominations
        .firstOrNull { availability.isDenominationAvailable(method, it.amountRupees) }
        ?.amountRupees ?: 150L
    )
  }
  var localError by rememberSaveable(method) { mutableStateOf<String?>(null) }

  val selectedDenom =
    denominations.firstOrNull { it.amountRupees == selectedRupees } ?: denominations.first()
  val isSelectedAvailable =
    availability.isDenominationAvailable(method, selectedDenom.amountRupees)
  val displayedError = localError ?: validationError

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 18.dp, vertical = 14.dp)
          .testTag("gift_card_redemption_screen"),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier.size(48.dp).testTag("gift_card_back_button")
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back"
            )
          }
          com.example.ui.components.WithdrawalBrandLogo(
            methodKey = method,
            size = 38.dp
          )
          Text(
            text = methodTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
        NeonCoinBadge(coins = totalBalanceCoins)
      }

      Text(
        text = "Select Gift Card Value",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
      )

      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        denominations.forEach { denom ->
          val available = availability.isDenominationAvailable(method, denom.amountRupees)
          DenominationCard(
            denomination = denom,
            selected = selectedRupees == denom.amountRupees,
            available = available,
            accentColor = accentColor,
            onClick = {
              if (available) {
                selectedRupees = denom.amountRupees
                localError = null
                onClearError()
              }
            },
            modifier = Modifier.fillMaxWidth().testTag("gift_card_denom_${denom.amountRupees}")
          )
        }
      }

      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "Redemption Summary",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
          )
          SummaryRow("Gift Card", methodTitle)
          SummaryRow("Value", "₹${selectedDenom.amountRupees}")
          SummaryRow("Coins Required", formatCoins(selectedDenom.amountCoins))
          SummaryRow("Delivery", "In-App (Withdrawal History)")
        }
      }

      if (!displayedError.isNullOrBlank()) {
        ErrorNoticeBanner(
          message = displayedError,
          testTag = "gift_card_validation_error"
        )
      }

      Button(
        onClick = {
          if (!isSelectedAvailable) {
            localError = "This denomination is currently unavailable."
            return@Button
          }
          if (totalBalanceCoins < selectedDenom.amountCoins) {
            localError =
              "Insufficient balance. You need ${formatCoins(selectedDenom.amountCoins)} to redeem ₹${selectedDenom.amountRupees}."
            return@Button
          }
          localError = null
          onSubmit(selectedDenom.amountRupees)
        },
        enabled = !isBusy && isSelectedAvailable,
        shape = RoundedCornerShape(14.dp),
        colors =
          ButtonDefaults.buttonColors(
            containerColor = accentColor,
            contentColor = Color.White
          ),
        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("gift_card_submit_button")
      ) {
        if (isBusy) {
          CircularProgressIndicator(
            color = Color.White,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp)
          )
        } else {
          Text(
            text = "Redeem ₹${selectedDenom.amountRupees} Gift Card",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
        }
      }
    }
  }
}

@Composable
private fun DenominationCard(
  denomination: WithdrawalDenomination,
  selected: Boolean,
  available: Boolean,
  accentColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val borderColor =
    when {
      !available -> MaterialTheme.colorScheme.outlineVariant
      selected -> accentColor
      else -> MaterialTheme.colorScheme.outlineVariant
    }
  val containerColor =
    when {
      !available -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
      selected -> accentColor.copy(alpha = 0.08f)
      else -> MaterialTheme.colorScheme.surface
    }

  Card(
    modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = available, onClick = onClick),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = containerColor),
    border = BorderStroke(if (selected && available) 2.dp else 1.dp, borderColor)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "₹${denomination.amountRupees}",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color =
            if (available) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
          text = if (available) formatCoins(denomination.amountCoins) else "Currently Unavailable",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          color =
            when {
              !available -> MaterialTheme.colorScheme.error
              selected -> accentColor
              else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
      }
      if (selected && available) {
        Icon(
          imageVector = Icons.Default.CheckCircle,
          contentDescription = "Selected",
          tint = accentColor,
          modifier = Modifier.size(22.dp)
        )
      }
    }
  }
}

@Composable
private fun SummaryRow(label: String, value: String) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface
    )
  }
}

@Composable
private fun ErrorNoticeBanner(message: String, testTag: String) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.errorContainer,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    modifier = Modifier.fillMaxWidth().testTag(testTag)
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Icon(
        imageVector = Icons.Default.ErrorOutline,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.size(20.dp)
      )
      Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        fontWeight = FontWeight.SemiBold
      )
    }
  }
}

/**
 * Confirmation Screen displayed immediately after atomic coin deduction and request submission.
 */
@Composable
fun WithdrawalConfirmationScreen(
  request: DemoWithdrawalRequest?,
  onViewHistory: () -> Unit,
  onDone: () -> Unit
) {
  BackHandler { onDone() }

  NeonBackgroundBox {
    Column(
      modifier =
        Modifier.fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(24.dp)
          .testTag("withdrawal_confirmation_screen"),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Surface(
        shape = CircleShape,
        color = Color(0xFFDCFCE7),
        modifier = Modifier.size(80.dp)
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Success",
            tint = Color(0xFF16A34A),
            modifier = Modifier.size(44.dp)
          )
        }
      }

      Spacer(modifier = Modifier.height(18.dp))

      Text(
        text = "Request Submitted Successfully!",
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
      )

      Spacer(modifier = Modifier.height(8.dp))

      Text(
        text = "Your withdrawal request has been received. Estimated processing time: 24–48 hours.",
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )

      if (request != null) {
        Spacer(modifier = Modifier.height(20.dp))
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(18.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            SummaryRow("Method", request.getEffectiveMethodLabel())
            SummaryRow("Amount", "₹${request.getEffectiveAmountRupees()}")
            SummaryRow("Coins Deducted", formatCoins(request.getEffectiveAmountCoins()))
            if (request.upiId.isNotBlank()) {
              SummaryRow("UPI ID", request.upiId)
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "Request ID",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              Text(
                text = request.requestId.ifBlank { request.id },
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag("withdrawal_confirm_request_id")
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(24.dp))

      Button(
        onClick = onViewHistory,
        shape = RoundedCornerShape(14.dp),
        modifier =
          Modifier.fillMaxWidth().height(50.dp).testTag("withdrawal_confirm_history_button")
      ) {
        Text(
          text = "View Withdrawal History",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      OutlinedButton(
        onClick = onDone,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("withdrawal_confirm_done_button")
      ) {
        Text("Back to Wallet", fontWeight = FontWeight.SemiBold)
      }
    }
  }
}

/**
 * Full Withdrawal & Gift Card History Screen for the owner.
 * Displays status badges (Submitted, Under Review, Processing, Delayed, Completed, Rejected),
 * status notes, and owner-only Gift Code reveal + one-tap Copy Code button.
 */
@Composable
fun WithdrawalHistoryScreen(
  requests: List<DemoWithdrawalRequest>,
  onBack: () -> Unit
) {
  BackHandler { onBack() }

  val clipboardManager = LocalClipboardManager.current
  var filterKey by rememberSaveable { mutableStateOf("ALL") }
  var copiedRequestId by remember { mutableStateOf<String?>(null) }

  val filteredRequests =
    remember(requests, filterKey) {
      when (filterKey) {
        "ACTIVE" -> requests.filter { WithdrawalStatus.isPendingOrActive(it.status) }
        "COMPLETED" ->
          requests.filter {
            it.status.equals(WithdrawalStatus.COMPLETED, ignoreCase = true) ||
              it.status.equals("approved", ignoreCase = true)
          }
        "REJECTED" ->
          requests.filter { it.status.equals(WithdrawalStatus.REJECTED, ignoreCase = true) }
        else -> requests
      }
    }

  NeonBackgroundBox {
    Column(
      modifier = Modifier.fillMaxSize().testTag("withdrawal_history_screen")
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IconButton(
          onClick = onBack,
          modifier = Modifier.size(48.dp).testTag("withdrawal_history_back_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Back"
          )
        }
        Text(
          text = "Withdrawal History",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
      }

      Row(
        modifier =
          Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          "ALL" to "All (${requests.size})",
          "ACTIVE" to "Active",
          "COMPLETED" to "Completed",
          "REJECTED" to "Rejected"
        ).forEach { (key, label) ->
          FilterChip(
            selected = filterKey == key,
            onClick = { filterKey = key },
            label = { Text(label) },
            modifier = Modifier.testTag("withdrawal_history_filter_$key")
          )
        }
      }

      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        if (filteredRequests.isEmpty()) {
          item {
            Card(
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(18.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
              ) {
                Icon(
                  imageVector = Icons.Default.History,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                  text = "No Withdrawal Requests Found",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }
        } else {
          items(filteredRequests, key = { it.id.ifBlank { it.requestId } }) { req ->
            val reqId = req.id.ifBlank { req.requestId }
            val displayStatus = req.getDisplayStatus()
            val dateStr =
              req.createdAt?.toDate()?.let {
                SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US).format(it)
              } ?: "Just now"
            val (badgeBg, badgeFg) =
              when (displayStatus) {
                "Completed" -> Color(0xFFDCFCE7) to Color(0xFF15803D)
                "Rejected" -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
                "Delayed" -> Color(0xFFFFEDD5) to Color(0xFFC2410C)
                "Processing", "Under Review" -> Color(0xFFDBEAFE) to Color(0xFF1E3A8A)
                else -> Color(0xFFFEF3C7) to Color(0xFFB45309)
              }

            Card(
              modifier = Modifier.fillMaxWidth().testTag("withdrawal_history_card_$reqId"),
              shape = RoundedCornerShape(18.dp),
              colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
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
                    com.example.ui.components.WithdrawalBrandLogo(
                      methodKey = req.method,
                      size = 42.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = "${req.getEffectiveMethodLabel()} • ₹${req.getEffectiveAmountRupees()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                      )
                      Text(
                        text = "${formatCoins(req.getEffectiveAmountCoins())} • $dateStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                    }
                  }
                  Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeBg,
                    modifier = Modifier.testTag("withdrawal_history_status_$reqId")
                  ) {
                    Text(
                      text = displayStatus,
                      style = MaterialTheme.typography.labelSmall,
                      fontWeight = FontWeight.SemiBold,
                      color = badgeFg,
                      modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                  }
                }

                if (req.upiId.isNotBlank()) {
                  Text(
                    text = "UPI ID: ${req.upiId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                }

                if (req.paymentReference.isNotBlank()) {
                  Text(
                    text = "Payment Ref / UTR: ${req.paymentReference}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF15803D)
                  )
                }

                if (req.statusNote.isNotBlank()) {
                  Text(
                    text =
                      if (req.status.equals(WithdrawalStatus.REJECTED, ignoreCase = true)) {
                        "Reason: ${req.statusNote}"
                      } else {
                        "Note: ${req.statusNote}"
                      },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                      if (req.status.equals(WithdrawalStatus.REJECTED, ignoreCase = true)) {
                        MaterialTheme.colorScheme.error
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                      }
                  )
                }

                if (req.coinsRefunded) {
                  Text(
                    text = "Coins Refunded: +${formatCoins(req.getEffectiveAmountCoins())}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF15803D)
                  )
                }

                // Gift Card Code Reveal + Copy Code (owner-only visibility guaranteed by Firestore Rules)
                if (req.giftCode.isNotBlank() &&
                  req.status.equals(WithdrawalStatus.COMPLETED, ignoreCase = true)
                ) {
                  Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFFECFDF5),
                    border = BorderStroke(1.dp, Color(0xFF6EE7B7)),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Row(
                      modifier = Modifier.fillMaxWidth().padding(12.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                      Column(modifier = Modifier.weight(1f)) {
                        Text(
                          text = "YOUR GIFT CODE",
                          style = MaterialTheme.typography.labelSmall,
                          color = Color(0xFF065F46),
                          fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                          text = req.giftCode,
                          style = MaterialTheme.typography.titleMedium,
                          fontFamily = FontFamily.Monospace,
                          fontWeight = FontWeight.ExtraBold,
                          color = Color(0xFF047857),
                          modifier = Modifier.testTag("withdrawal_gift_code_$reqId")
                        )
                        if (copiedRequestId == reqId) {
                          Text(
                            text = "Code copied!",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Bold
                          )
                        }
                      }
                      Spacer(modifier = Modifier.width(8.dp))
                      Button(
                        onClick = {
                          clipboardManager.setText(AnnotatedString(req.giftCode))
                          copiedRequestId = reqId
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors =
                          ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF059669),
                            contentColor = Color.White
                          ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.height(48.dp).testTag("withdrawal_copy_code_$reqId")
                      ) {
                        Icon(
                          imageVector = Icons.Default.ContentCopy,
                          contentDescription = "Copy Gift Code",
                          modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Code", fontWeight = FontWeight.Bold)
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
  }
}

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.TaskItem
import com.example.data.model.TaskStatus
import com.example.ui.components.AppIconThumbnail
import com.example.ui.components.NeonBackgroundBox
import com.example.ui.components.NeonCoinBadge
import com.example.ui.components.NeonGlassCard
import com.example.ui.components.resolveAppOrWebsiteLabel

@Composable
fun OfferWallScreen(
  tasks: List<TaskItem> = emptyList(),
  onBack: () -> Unit,
  onSelectOffer: (String) -> Unit = {}
) {
  BackHandler(onBack = onBack)

  var selectedCategory by rememberSaveable { mutableStateOf("All") }
  val categories = listOf("All", "App Install", "Web Signups", "Surveys", "Quizzes")

  val availableOffers = remember(tasks, selectedCategory) {
    tasks.filter { task ->
      task.isActive && task.status == TaskStatus.PENDING &&
        (selectedCategory == "All" || task.category.contains(selectedCategory, ignoreCase = true))
    }
  }

  NeonBackgroundBox {
    LazyColumn(
      modifier = Modifier.fillMaxSize().testTag("offer_wall_screen"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
      // Top Bar
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          IconButton(
            onClick = onBack,
            modifier = Modifier
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.surface)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.ArrowBack,
              contentDescription = "Back",
              tint = MaterialTheme.colorScheme.onSurface
            )
          }
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Campaign,
              contentDescription = null,
              tint = Color(0xFF059669),
              modifier = Modifier.size(24.dp)
            )
            Text(
              text = "TaskFlow Offer Wall",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onBackground,
              fontWeight = FontWeight.Bold
            )
          }
          Spacer(modifier = Modifier.width(36.dp))
        }
      }

      // Hero Banner
      item {
        NeonGlassCard(
          modifier = Modifier.fillMaxWidth(),
          borderColor = Color(0xFFA7F3D0),
          backgroundColor = MaterialTheme.colorScheme.surface
        ) {
          Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
            Text(
              text = "High-Paying Partner Offers",
              style = MaterialTheme.typography.titleLarge,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Complete verified mobile app installs, website registrations, and surveys to earn up to 5,000 Coins per offer.",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }

      // Filter Chips
      item {
        LazyRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(vertical = 4.dp)
        ) {
          items(categories) { cat ->
            FilterChip(
              selected = selectedCategory == cat,
              onClick = { selectedCategory = cat },
              label = { Text(cat, fontWeight = FontWeight.Bold) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = Color(0xFF059669),
                selectedLabelColor = Color.White,
                containerColor = MaterialTheme.colorScheme.surface,
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant
              ),
              border = FilterChipDefaults.filterChipBorder(
                borderColor = MaterialTheme.colorScheme.outlineVariant,
                selectedBorderColor = Color(0xFF059669),
                enabled = true,
                selected = selectedCategory == cat
              )
            )
          }
        }
      }

      // Offers List Header
      item {
        Text(
          text = "Available Offers (${availableOffers.size})",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onBackground,
          fontWeight = FontWeight.Bold
        )
      }

      if (availableOffers.isEmpty()) {
        item {
          NeonGlassCard(
            modifier = Modifier.fillMaxWidth(),
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            backgroundColor = MaterialTheme.colorScheme.surface
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              Text(
                text = "No offers found in this category",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Check back soon or select another category above to view high-reward tasks.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
          }
        }
      } else {
        items(availableOffers, key = { "offer_${it.id}" }) { offer ->
          val coinReward = (offer.rewardCents * 125) / 100
          val appOrSiteLabel = resolveAppOrWebsiteLabel(offer)

          NeonGlassCard(
            modifier = Modifier.fillMaxWidth().testTag("offer_card_${offer.id}"),
            borderColor = MaterialTheme.colorScheme.outlineVariant,
            backgroundColor = MaterialTheme.colorScheme.surface,
            onClick = { onSelectOffer(offer.id) }
          ) {
            Column(
              modifier = Modifier.fillMaxWidth().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                AppIconThumbnail(
                  appIconUrl = offer.appIconUrl,
                  appName = offer.appName,
                  category = offer.category,
                  size = 48.dp
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = offer.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Spacer(modifier = Modifier.height(2.dp))
                  Text(
                    text = appOrSiteLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF059669),
                    fontWeight = FontWeight.SemiBold
                  )
                }

                NeonCoinBadge(coins = coinReward)
              }

              Text(
                text = offer.description.ifBlank { "Complete requirements to receive instant coin rewards." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Surface(
                  shape = RoundedCornerShape(50),
                  color = Color(0xFFECFDF5),
                  border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6EE7B7))
                ) {
                  Text(
                    text = offer.category.ifBlank { "Verified Offer" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF047857),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                  )
                }

                Button(
                  onClick = { onSelectOffer(offer.id) },
                  shape = RoundedCornerShape(12.dp),
                  colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF059669),
                    contentColor = Color.White
                  )
                ) {
                  Text("Start Offer", fontWeight = FontWeight.Bold)
                  Spacer(modifier = Modifier.width(4.dp))
                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
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

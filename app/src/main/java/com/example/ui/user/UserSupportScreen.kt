package com.example.ui.user

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.TaskFlowFaqData
import com.example.data.model.TaskFlowFaqItem
import kotlinx.coroutines.launch

data class ChatMessage(
  val id: String,
  val text: String,
  val isUser: Boolean,
  val relatedFaq: TaskFlowFaqItem? = null
)

@Composable
fun UserSupportScreen(
  onBack: () -> Unit,
  onNavigateScreen: (String) -> Unit,
  onSubmitTicket: ((String, String, String) -> Unit)? = null
) {
  BackHandler(onBack = onBack)
  var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: FAQ, 1: Assistant Chat, 2: Contact

  Column(
    modifier =
      Modifier.fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .testTag("user_support_screen")
  ) {
    // Header
    Surface(
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("support_back_button")) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Help & Support",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Knowledge base, assistant & inquiry tickets",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }

    TabRow(selectedTabIndex = selectedTab) {
      Tab(
        selected = selectedTab == 0,
        onClick = { selectedTab = 0 },
        text = { Text("FAQ", fontWeight = FontWeight.SemiBold) },
        icon = { Icon(Icons.AutoMirrored.Filled.Help, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_faq_browse")
      )
      Tab(
        selected = selectedTab == 1,
        onClick = { selectedTab = 1 },
        text = { Text("Assistant", fontWeight = FontWeight.SemiBold) },
        icon = { Icon(Icons.Default.QuestionAnswer, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_assistant_chat")
      )
      Tab(
        selected = selectedTab == 2,
        onClick = { selectedTab = 2 },
        text = { Text("Contact", fontWeight = FontWeight.SemiBold) },
        icon = { Icon(Icons.Default.SupportAgent, contentDescription = null, modifier = Modifier.size(18.dp)) },
        modifier = Modifier.testTag("tab_contact_support")
      )
    }

    when (selectedTab) {
      0 -> FaqBrowseView(onNavigateScreen = onNavigateScreen)
      1 -> SupportAssistantChatView(onNavigateScreen = onNavigateScreen)
      else -> ContactSupportTicketView(onSubmitTicket = onSubmitTicket)
    }
  }
}

@Composable
private fun ContactSupportTicketView(
  onSubmitTicket: ((String, String, String) -> Unit)?
) {
  var subject by rememberSaveable { mutableStateOf("") }
  var category by rememberSaveable { mutableStateOf("Task Issue") }
  var message by rememberSaveable { mutableStateOf("") }
  var isSubmitted by rememberSaveable { mutableStateOf(false) }

  val categories = listOf("Task Issue", "Reward / Wallet", "Account Approval", "App Bug", "Other")

  if (isSubmitted) {
    Card(
      modifier = Modifier.fillMaxWidth().padding(20.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Icon(
          imageVector = Icons.Default.SupportAgent,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = "Inquiry Submitted!",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
          text = "Thank you. Your support ticket has been received by our administrative team and will be reviewed promptly.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
          onClick = {
            subject = ""
            message = ""
            isSubmitted = false
          }
        ) {
          Text("Submit Another Inquiry")
        }
      }
    }
    return
  }

  Column(
    modifier = Modifier.fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(18.dp),
    verticalArrangement = Arrangement.spacedBy(14.dp)
  ) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
      Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Icon(Icons.Default.SupportAgent, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
          text = "Have an issue with a task, reward, or verification? Send a message directly to authorized staff.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    Text("Category", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      categories.forEach { cat ->
        FilterChip(
          selected = category == cat,
          onClick = { category = cat },
          label = { Text(cat) }
        )
      }
    }

    OutlinedTextField(
      value = subject,
      onValueChange = { subject = it },
      label = { Text("Subject") },
      placeholder = { Text("Brief summary of your issue") },
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(12.dp)
    )

    OutlinedTextField(
      value = message,
      onValueChange = { message = it },
      label = { Text("Detailed Description") },
      placeholder = { Text("Please describe what happened, task name, or questions...") },
      modifier = Modifier.fillMaxWidth(),
      minLines = 4,
      shape = RoundedCornerShape(12.dp)
    )

    Button(
      onClick = {
        onSubmitTicket?.invoke(subject.trim(), message.trim(), category)
        isSubmitted = true
      },
      enabled = subject.trim().isNotBlank() && message.trim().isNotBlank(),
      modifier = Modifier.fillMaxWidth().height(48.dp),
      shape = RoundedCornerShape(12.dp)
    ) {
      Text("Submit Support Ticket")
    }
  }
}

@Composable
private fun FaqBrowseView(onNavigateScreen: (String) -> Unit) {
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var selectedCategory by rememberSaveable { mutableStateOf("All") }
  val expandedItems = remember { mutableStateListOf<String>() }

  val categories = remember {
    listOf("All") + TaskFlowFaqData.ITEMS.map { it.category }.distinct()
  }

  val filteredFaqs =
    remember(searchQuery, selectedCategory) {
      TaskFlowFaqData.ITEMS.filter { item ->
        val matchesCategory = selectedCategory == "All" || item.category == selectedCategory
        val matchesSearch =
          searchQuery.isBlank() ||
            item.question.contains(searchQuery, ignoreCase = true) ||
            item.answer.contains(searchQuery, ignoreCase = true)
        matchesCategory && matchesSearch
      }
    }

  Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    OutlinedTextField(
      value = searchQuery,
      onValueChange = { searchQuery = it },
      label = { Text("Search questions, topics...") },
      leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
      singleLine = true,
      modifier = Modifier.fillMaxWidth().testTag("faq_search_input"),
      shape = RoundedCornerShape(14.dp)
    )

    Spacer(modifier = Modifier.height(10.dp))

    // Categories chip row
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      categories.forEach { cat ->
        FilterChip(
          selected = selectedCategory == cat,
          onClick = { selectedCategory = cat },
          label = { Text(cat) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
              selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
          modifier = Modifier.testTag("faq_chip_$cat")
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    if (filteredFaqs.isEmpty()) {
      Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Text(
            text = "No matching questions found.",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
          )
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Try switching to the 'Ask Assistant' tab for dynamic assistance.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
      ) {
        items(filteredFaqs, key = { it.id }) { item ->
          val isExpanded = expandedItems.contains(item.id)
          Card(
            modifier =
              Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                  if (isExpanded) expandedItems.remove(item.id) else expandedItems.add(item.id)
                }
                .testTag("faq_card_${item.id}"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
          ) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = item.question,
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.weight(1f)
                )
                Icon(
                  imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = if (isExpanded) "Collapse" else "Expand",
                  tint = MaterialTheme.colorScheme.primary
                )
              }

              if (isExpanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                  text = item.answer,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.actionLabel != null && item.targetScreen != null) {
                  Spacer(modifier = Modifier.height(10.dp))
                  Button(
                    onClick = { onNavigateScreen(item.targetScreen) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.testTag("faq_action_${item.id}")
                  ) {
                    Text(item.actionLabel)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(16.dp))
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

@Composable
private fun SupportAssistantChatView(onNavigateScreen: (String) -> Unit) {
  val messages = remember {
    mutableStateListOf(
      ChatMessage(
        id = "welcome_1",
        text =
          "Welcome to TaskFlow Assistant! I can answer questions about finding tasks, submitting mandatory proof, approval rules, wallet withdrawals, and themes.",
        isUser = false
      )
    )
  }

  var inputText by rememberSaveable { mutableStateOf("") }
  val listState = rememberLazyListState()
  val scope = rememberCoroutineScope()

  fun sendQuery(query: String) {
    val clean = query.trim()
    if (clean.isBlank()) return
    messages.add(ChatMessage(id = "user_${System.currentTimeMillis()}", text = clean, isUser = true))
    inputText = ""

    val answerItem = TaskFlowFaqData.findAnswer(clean)
    if (answerItem != null) {
      messages.add(
        ChatMessage(
          id = "bot_${System.currentTimeMillis()}",
          text = "${answerItem.question}\n\n${answerItem.answer}",
          isUser = false,
          relatedFaq = answerItem
        )
      )
    } else {
      messages.add(
        ChatMessage(
          id = "bot_${System.currentTimeMillis()}",
          text =
            "I couldn't find a direct match in our offline knowledge base for that query. Please browse the 'Browse FAQ' tab for comprehensive topic breakdowns or select one of the suggested topics below.",
          isUser = false
        )
      )
    }

    scope.launch {
      listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }
  }

  Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
    // Quick question suggestion chips
    Text(
      text = "Suggested Questions:",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(6.dp))
    Row(
      modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      TaskFlowFaqData.COMMON_QUESTIONS.forEach { q ->
        Surface(
          shape = RoundedCornerShape(20.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          modifier =
            Modifier.clip(RoundedCornerShape(20.dp))
              .clickable { sendQuery(q) }
              .testTag("suggested_q_${q.take(15)}")
        ) {
          Text(
            text = q,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(10.dp))

    // Messages list
    LazyColumn(
      state = listState,
      modifier = Modifier.weight(1f).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(10.dp),
      contentPadding = PaddingValues(vertical = 8.dp)
    ) {
      items(messages, key = { it.id }) { msg ->
        val bubbleColor =
          if (msg.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val textColor =
          if (msg.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        val alignment = if (msg.isUser) Alignment.End else Alignment.Start

        Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = alignment
        ) {
          Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = if (msg.isUser) Arrangement.End else Arrangement.Start,
            modifier = Modifier.fillMaxWidth(0.92f)
          ) {
            if (!msg.isUser) {
              Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(32.dp).padding(end = 4.dp)
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    Icons.Default.SupportAgent,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }

            Surface(
              shape =
                RoundedCornerShape(
                  topStart = 16.dp,
                  topEnd = 16.dp,
                  bottomStart = if (msg.isUser) 16.dp else 4.dp,
                  bottomEnd = if (msg.isUser) 4.dp else 16.dp
                ),
              color = bubbleColor,
              modifier = Modifier.padding(horizontal = 4.dp).testTag(if (msg.isUser) "chat_bubble_user" else "chat_bubble_bot")
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(text = msg.text, style = MaterialTheme.typography.bodyMedium, color = textColor)
                if (msg.relatedFaq?.actionLabel != null && msg.relatedFaq.targetScreen != null) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Button(
                    onClick = { onNavigateScreen(msg.relatedFaq.targetScreen) },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("chat_action_btn")
                  ) {
                    Text(msg.relatedFaq.actionLabel)
                  }
                }
              }
            }
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Input Bar
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      OutlinedTextField(
        value = inputText,
        onValueChange = { inputText = it },
        placeholder = { Text("Ask about tasks, wallet, proof...") },
        singleLine = true,
        modifier = Modifier.weight(1f).testTag("chat_input_field"),
        shape = RoundedCornerShape(24.dp)
      )
      IconButton(
        onClick = { sendQuery(inputText) },
        enabled = inputText.isNotBlank(),
        modifier =
          Modifier.size(48.dp)
            .clip(CircleShape)
            .background(if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .testTag("chat_send_button")
      ) {
        Icon(
          Icons.AutoMirrored.Filled.Send,
          contentDescription = "Send",
          tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}

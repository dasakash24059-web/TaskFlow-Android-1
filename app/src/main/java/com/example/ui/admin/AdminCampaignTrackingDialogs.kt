package com.example.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.model.AdminCampaignConversionRequirements
import com.example.data.model.AdminCampaignNetworkItem
import com.example.data.model.AdminCampaignTrackingConfigItem
import com.example.data.model.AdminNetworkSignatureConfig
import com.example.data.model.CampaignDevicePlatform
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkParameterMapping
import com.example.data.model.NetworkSignatureAlgorithm
import com.example.data.model.REGISTERED_CAMPAIGN_SECRET_KEYS
import com.example.data.model.SUPPORTED_HMAC_ENCODINGS
import com.example.data.model.SUPPORTED_HMAC_SIGNING_MODES
import com.example.data.model.allowedSignatureAlgorithmsForMode
import com.example.data.model.coinsToCents
import com.example.data.model.formatCoins
import com.example.data.model.formatRewardInr
import com.example.data.model.formatUtcMillisToIsoDate
import com.example.data.model.parseStrictIsoDateToUtcMillis
import com.example.data.model.validateAdminCampaignConfigForm
import com.example.data.model.validateAdminNetworkForm
import com.example.ui.theme.taskFlowColors
import java.util.Locale

internal fun parseIsoDateToUtcMillis(rawDate: String, endOfDay: Boolean): Long? =
  parseStrictIsoDateToUtcMillis(rawDate, endOfDay = endOfDay)

@Composable
fun CampaignStatusConfirmationDialog(
  title: String,
  message: String,
  confirmLabel: String,
  isDestructive: Boolean,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("campaign_status_confirm_dialog"),
    icon = {
      Icon(
        imageVector = Icons.Default.Warning,
        contentDescription = null,
        tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
      )
    },
    title = {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        colors = if (isDestructive) {
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError
          )
        } else {
          ButtonDefaults.buttonColors()
        },
        modifier = Modifier.testTag("campaign_status_confirm_button")
      ) {
        Text(confirmLabel)
      }
    },
    dismissButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.testTag("campaign_status_cancel_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
fun CampaignNetworkEditorDialog(
  initial: AdminCampaignNetworkItem?,
  isSaving: Boolean,
  onDismiss: () -> Unit,
  onSave: (AdminCampaignNetworkItem, Boolean) -> Unit
) {
  val isEdit = initial != null
  val tfColors = taskFlowColors()

  var networkId by remember(initial) { mutableStateOf(initial?.networkId.orEmpty()) }
  var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
  var adapterType by remember(initial) {
    mutableStateOf(initial?.adapterType ?: CampaignVerificationMode.AFFILIATE_POSTBACK.name)
  }
  var isActive by remember(initial) { mutableStateOf(initial?.isActive ?: true) }
  var supportsIncentivized by remember(initial) {
    mutableStateOf(initial?.capabilities?.supportsIncentivizedTraffic ?: true)
  }
  var supportsPostback by remember(initial) {
    mutableStateOf(initial?.capabilities?.supportsPostback ?: true)
  }
  var supportsInstallAttribution by remember(initial) {
    mutableStateOf(initial?.capabilities?.supportsInstallAttribution ?: false)
  }
  var requiresManualReview by remember(initial) {
    mutableStateOf(initial?.capabilities?.requiresManualReview ?: false)
  }
  var allowedHostsText by remember(initial) {
    mutableStateOf(initial?.allowedDestinationHosts?.joinToString(", ").orEmpty())
  }
  var clickIdParam by remember(initial) {
    mutableStateOf(initial?.parameterMapping?.clickIdParam ?: "click_id")
  }
  var externalTxnParam by remember(initial) {
    mutableStateOf(initial?.parameterMapping?.externalTxnParam ?: "txn_id")
  }
  var statusParam by remember(initial) {
    mutableStateOf(initial?.parameterMapping?.statusParam ?: "status")
  }
  var payoutParam by remember(initial) {
    mutableStateOf(initial?.parameterMapping?.payoutParam ?: "payout")
  }
  var signatureAlgorithm by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.algorithm ?: NetworkSignatureAlgorithm.HMAC_SHA256.name)
  }
  var secretEnvKey by remember(initial) {
    mutableStateOf(
      initial?.signatureConfig?.secretEnvKey?.takeIf { REGISTERED_CAMPAIGN_SECRET_KEYS.contains(it) }
        ?: REGISTERED_CAMPAIGN_SECRET_KEYS.first()
    )
  }
  var headerName by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.headerName.orEmpty())
  }
  var queryParamName by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.queryParamName ?: "sig")
  }
  var signingMode by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.signingMode ?: "SORTED_PARAMS")
  }
  var encoding by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.encoding ?: "hex")
  }
  var signedFieldsText by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.signedFields?.joinToString(", ").orEmpty())
  }
  var fieldSeparator by remember(initial) {
    mutableStateOf(initial?.signatureConfig?.fieldSeparator ?: "|")
  }
  var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }

  val currentMode = CampaignVerificationMode.fromWireValue(adapterType)
    ?: CampaignVerificationMode.AFFILIATE_POSTBACK
  val allowedAlgorithms = remember(currentMode) { allowedSignatureAlgorithmsForMode(currentMode) }
  val isHmacAlgorithm =
    signatureAlgorithm == NetworkSignatureAlgorithm.HMAC_SHA256.name ||
      signatureAlgorithm == NetworkSignatureAlgorithm.HMAC_MD5.name

  AlertDialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    modifier = Modifier.testTag("campaign_network_editor_dialog"),
    title = {
      Text(
        text = if (isEdit) "Edit Campaign Network" else "Register Campaign Network",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (validationErrors.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("network_form_validation_error")
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              validationErrors.forEach { err ->
                Text(
                  text = "• $err",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer
                )
              }
            }
          }
        }

        OutlinedTextField(
          value = networkId,
          onValueChange = { if (!isEdit) networkId = it },
          enabled = !isEdit,
          label = { Text("Network ID (e.g., partner_alpha)") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("network_id_input")
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Display Name") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("network_name_input")
        )

        Text(
          text = "Adapter Mode",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          CampaignVerificationMode.entries.forEach { modeOption ->
            val selected = adapterType == modeOption.name
            FilterChip(
              selected = selected,
              onClick = {
                adapterType = modeOption.name
                val nextAllowed = allowedSignatureAlgorithmsForMode(modeOption)
                if (nextAllowed.none { it.name == signatureAlgorithm }) {
                  signatureAlgorithm = nextAllowed.first().name
                }
                if (modeOption == CampaignVerificationMode.AFFILIATE_POSTBACK ||
                  modeOption == CampaignVerificationMode.ADVERTISER_S2S_API
                ) {
                  supportsPostback = true
                }
              },
              label = { Text(modeOption.name.replace('_', ' ')) },
              modifier = Modifier.testTag("network_adapter_chip_${modeOption.name}")
            )
          }
        }

        EditorSwitchRow(
          label = "Network Active",
          checked = isActive,
          onCheckedChange = { isActive = it },
          tag = "network_active_switch"
        )
        EditorSwitchRow(
          label = "Supports Incentivized Traffic",
          checked = supportsIncentivized,
          onCheckedChange = { supportsIncentivized = it },
          tag = "network_incentivized_switch"
        )
        EditorSwitchRow(
          label = "Supports S2S Postback",
          checked = supportsPostback,
          onCheckedChange = { supportsPostback = it },
          tag = "network_postback_switch"
        )
        EditorSwitchRow(
          label = "Supports Install Attribution",
          checked = supportsInstallAttribution,
          onCheckedChange = { supportsInstallAttribution = it },
          tag = "network_install_attr_switch"
        )
        EditorSwitchRow(
          label = "Requires Manual Review",
          checked = requiresManualReview,
          onCheckedChange = { requiresManualReview = it },
          tag = "network_manual_review_switch"
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        Text(
          text = "Signature & Secret Manager Binding",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold
        )

        Surface(
          shape = RoundedCornerShape(10.dp),
          color = tfColors.neutralSurfaceBg,
          border = BorderStroke(1.dp, tfColors.neutralSurfaceBorder),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Lock,
              contentDescription = "Secret security notice",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = "Only registered Firebase Secret Manager key names are selectable. Raw secret values are never entered, stored, or returned.",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          allowedAlgorithms.forEach { alg ->
            FilterChip(
              selected = signatureAlgorithm == alg.name,
              onClick = { signatureAlgorithm = alg.name },
              label = { Text(alg.name) },
              modifier = Modifier.testTag("network_sig_alg_${alg.name}")
            )
          }
        }

        if (signatureAlgorithm != NetworkSignatureAlgorithm.NONE.name) {
          Text(
            text = "Registered Secret Key Name",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            REGISTERED_CAMPAIGN_SECRET_KEYS.forEach { keyName ->
              FilterChip(
                selected = secretEnvKey == keyName,
                onClick = { secretEnvKey = keyName },
                label = { Text(keyName, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.testTag("network_secret_key_$keyName")
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = headerName,
              onValueChange = { headerName = it },
              label = { Text("Header Name") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = queryParamName,
              onValueChange = { queryParamName = it },
              label = { Text("Query Param") },
              singleLine = true,
              modifier = Modifier.weight(1f)
            )
          }
        }

        if (isHmacAlgorithm) {
          Text(
            text = "HMAC Canonicalization Mode",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            SUPPORTED_HMAC_SIGNING_MODES.forEach { sm ->
              FilterChip(
                selected = signingMode == sm,
                onClick = { signingMode = sm },
                label = { Text(sm) },
                modifier = Modifier.testTag("network_signing_mode_$sm")
              )
            }
          }

          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Encoding:",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SUPPORTED_HMAC_ENCODINGS.forEach { enc ->
              FilterChip(
                selected = encoding == enc,
                onClick = { encoding = enc },
                label = { Text(enc.uppercase(Locale.US)) }
              )
            }
          }

          if (signingMode == "CANONICAL_FIELDS") {
            OutlinedTextField(
              value = signedFieldsText,
              onValueChange = { signedFieldsText = it },
              label = { Text("Signed Fields (comma-separated)") },
              singleLine = true,
              modifier = Modifier
                .fillMaxWidth()
                .testTag("network_signed_fields_input")
            )
            OutlinedTextField(
              value = fieldSeparator,
              onValueChange = { fieldSeparator = it },
              label = { Text("Field Separator (e.g., |)") },
              singleLine = true,
              modifier = Modifier.fillMaxWidth()
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        OutlinedTextField(
          value = allowedHostsText,
          onValueChange = { allowedHostsText = it },
          label = { Text("Allowed Destination Hosts (comma-separated)") },
          placeholder = { Text("track.partner.com, go.partner.com") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("network_allowed_hosts_input")
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = clickIdParam,
            onValueChange = { clickIdParam = it },
            label = { Text("Click ID Param") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = externalTxnParam,
            onValueChange = { externalTxnParam = it },
            label = { Text("Txn ID Param") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = statusParam,
            onValueChange = { statusParam = it },
            label = { Text("Status Param") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = payoutParam,
            onValueChange = { payoutParam = it },
            label = { Text("Payout Param") },
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
        }
      }
    },
    confirmButton = {
      Button(
        enabled = !isSaving,
        onClick = {
          val candidate = AdminCampaignNetworkItem(
            networkId = networkId.trim(),
            name = name.trim(),
            adapterType = adapterType,
            isActive = isActive,
            capabilities = NetworkCapabilitiesSpec(
              supportsIncentivizedTraffic = supportsIncentivized,
              supportsPostback = supportsPostback,
              supportsInstallAttribution = supportsInstallAttribution,
              supportsServerToServerPolling = initial?.capabilities?.supportsServerToServerPolling ?: false,
              requiresManualReview = requiresManualReview
            ),
            allowedDestinationHosts = allowedHostsText
              .split(",")
              .map { it.trim().lowercase(Locale.US) }
              .filter { it.isNotEmpty() },
            parameterMapping = NetworkParameterMapping(
              clickIdParam = clickIdParam.trim(),
              userIdParam = initial?.parameterMapping?.userIdParam.orEmpty(),
              campaignIdParam = initial?.parameterMapping?.campaignIdParam.orEmpty(),
              externalTxnParam = externalTxnParam.trim(),
              statusParam = statusParam.trim(),
              payoutParam = payoutParam.trim(),
              signatureParam = queryParamName.trim().ifEmpty { "sig" },
              extraStaticParams = initial?.parameterMapping?.extraStaticParams ?: emptyMap()
            ),
            postbackStatusMapping = initial?.postbackStatusMapping ?: emptyMap(),
            signatureConfig = AdminNetworkSignatureConfig(
              algorithm = signatureAlgorithm,
              headerName = headerName.trim(),
              queryParamName = queryParamName.trim(),
              secretEnvKey = if (signatureAlgorithm == NetworkSignatureAlgorithm.NONE.name) "" else secretEnvKey,
              signingMode = signingMode,
              encoding = encoding,
              signedFields = signedFieldsText
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() },
              fieldSeparator = fieldSeparator
            )
          )
          val errors = validateAdminNetworkForm(candidate)
          if (errors.isNotEmpty()) {
            validationErrors = errors
          } else {
            validationErrors = emptyList()
            onSave(candidate, isEdit)
          }
        },
        modifier = Modifier.testTag("network_form_save_button")
      ) {
        Text(if (isEdit) "Save Changes" else "Register Network")
      }
    },
    dismissButton = {
      TextButton(
        enabled = !isSaving,
        onClick = onDismiss,
        modifier = Modifier.testTag("network_form_cancel_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
fun CampaignTrackingConfigEditorDialog(
  initial: AdminCampaignTrackingConfigItem?,
  networks: List<AdminCampaignNetworkItem>,
  isSaving: Boolean,
  hasMoreNetworks: Boolean = false,
  isLoadingNetworks: Boolean = false,
  isLoadingNetworkLookup: Boolean = false,
  networkLookupError: String? = null,
  onLoadMoreNetworks: () -> Unit = {},
  onEnsureNetworkLoaded: (String) -> Unit = {},
  onDismiss: () -> Unit,
  onSave: (AdminCampaignTrackingConfigItem, Boolean) -> Unit
) {
  val isEdit = initial != null

  var campaignId by remember(initial) { mutableStateOf(initial?.campaignId.orEmpty()) }
  var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
  var selectedNetworkId by remember(initial) {
    mutableStateOf(
      initial?.networkId?.takeIf { it.isNotBlank() }
        ?: networks.firstOrNull { it.isActive }?.networkId
        ?: networks.firstOrNull()?.networkId.orEmpty()
    )
  }
  LaunchedEffect(networks) {
    if (selectedNetworkId.isBlank() && networks.isNotEmpty()) {
      selectedNetworkId =
        networks.firstOrNull { it.isActive }?.networkId
          ?: networks.first().networkId
    }
  }
  LaunchedEffect(initial?.networkId, networks) {
    val requiredNetworkId = initial?.networkId?.trim().orEmpty()
    if (requiredNetworkId.isNotEmpty() && networks.none { it.networkId == requiredNetworkId }) {
      onEnsureNetworkLoaded(requiredNetworkId)
    }
  }
  val selectedNetwork = remember(selectedNetworkId, networks) {
    networks.firstOrNull { it.networkId == selectedNetworkId }
  }
  var verificationMode by remember(initial) {
    mutableStateOf(
      initial?.verificationMode?.takeIf { it.isNotBlank() }
        ?: selectedNetwork?.adapterType
        ?: CampaignVerificationMode.AFFILIATE_POSTBACK.name
    )
  }
  LaunchedEffect(selectedNetwork) {
    if (!isEdit && selectedNetwork != null && initial?.verificationMode.isNullOrBlank()) {
      verificationMode = selectedNetwork.adapterType
    }
  }
  var isActive by remember(initial) { mutableStateOf(initial?.isActive ?: true) }
  var rewardTfText by remember(initial) { mutableStateOf((initial?.rewardTf ?: 125L).toString()) }
  var submissionBudgetText by remember(initial) {
    mutableStateOf((initial?.submissionBudget ?: 0L).toString())
  }
  var rewardBudgetTfText by remember(initial) {
    mutableStateOf((initial?.rewardBudgetTf ?: 0L).toString())
  }
  var startDate by remember(initial) {
    mutableStateOf(
      initial?.startDate?.takeIf { it.isNotBlank() }
        ?: formatUtcMillisToIsoDate(initial?.startAtMillis ?: 0L)
    )
  }
  var endDate by remember(initial) {
    mutableStateOf(
      initial?.endDate?.takeIf { it.isNotBlank() }
        ?: formatUtcMillisToIsoDate(initial?.expiresAtMillis ?: 0L)
    )
  }
  var countriesText by remember(initial) {
    mutableStateOf((initial?.allowedCountries ?: listOf("ALL")).joinToString(", "))
  }
  var selectedPlatforms by remember(initial) {
    mutableStateOf((initial?.allowedPlatforms ?: listOf(CampaignDevicePlatform.ANDROID.name)).toSet())
  }
  var trackingBaseUrl by remember(initial) { mutableStateOf(initial?.trackingBaseUrl.orEmpty()) }
  var allowedHostsText by remember(initial) {
    mutableStateOf(initial?.allowedDestinationHosts?.joinToString(", ").orEmpty())
  }
  var attributionWindowText by remember(initial) {
    mutableStateOf((initial?.conversionRequirements?.attributionWindowHours ?: 72).toString())
  }
  var clickTtlText by remember(initial) {
    mutableStateOf((initial?.clickTtlMinutes ?: 60).toString())
  }
  var requireAdminApproval by remember(initial) {
    mutableStateOf(initial?.conversionRequirements?.requireAdminApproval ?: false)
  }
  var autoApprovePostback by remember(initial) {
    mutableStateOf(initial?.conversionRequirements?.autoApproveVerifiedPostback ?: true)
  }
  var allowMultipleConversions by remember(initial) {
    mutableStateOf(initial?.conversionRequirements?.allowMultipleConversions ?: false)
  }
  var requireVerifiedCountry by remember(initial) {
    mutableStateOf(initial?.conversionRequirements?.requireVerifiedCountry ?: false)
  }
  var requireAppCheck by remember(initial) {
    mutableStateOf(initial?.conversionRequirements?.requireAppCheck ?: false)
  }
  var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }

  val parsedRewardTf = rewardTfText.trim().toLongOrNull() ?: 0L

  AlertDialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    modifier = Modifier.testTag("campaign_config_editor_dialog"),
    title = {
      Text(
        text = if (isEdit) "Edit Tracked Campaign" else "Create Tracked Campaign",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (validationErrors.isNotEmpty()) {
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("campaign_config_form_validation_error")
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
              validationErrors.forEach { err ->
                Text(
                  text = "• $err",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer
                )
              }
            }
          }
        }

        OutlinedTextField(
          value = campaignId,
          onValueChange = { if (!isEdit) campaignId = it },
          enabled = !isEdit,
          label = { Text("Campaign ID (e.g., camp_offer_01)") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_id_input")
        )

        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Campaign Title") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_title_input")
        )

        Text(
          text = "Registered Network",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold
        )
        if (isLoadingNetworkLookup && selectedNetwork == null) {
          Text(
            text = "Loading assigned network '$selectedNetworkId'...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag("campaign_network_lookup_loading")
          )
        } else if (networkLookupError != null && selectedNetwork == null && selectedNetworkId.isNotBlank()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .testTag("campaign_network_lookup_error"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = networkLookupError,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.weight(1f)
            )
            TextButton(
              onClick = { onEnsureNetworkLoaded(selectedNetworkId) },
              modifier = Modifier.testTag("campaign_network_lookup_retry")
            ) {
              Text("Retry")
            }
          }
        }
        if (networks.isEmpty() && !isLoadingNetworkLookup) {
          Text(
            text = "No networks registered yet. Register a network first.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
          )
        } else if (networks.isNotEmpty()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            networks.forEach { net ->
              FilterChip(
                selected = selectedNetworkId == net.networkId,
                onClick = {
                  selectedNetworkId = net.networkId
                  verificationMode = net.adapterType
                },
                label = {
                  Text(
                    text = if (net.isActive) "${net.name} (${net.adapterType.replace('_', ' ')})"
                    else "${net.name} [Disabled]"
                  )
                },
                modifier = Modifier.testTag("campaign_network_chip_${net.networkId}")
              )
            }
            if (hasMoreNetworks) {
              FilterChip(
                selected = false,
                enabled = !isLoadingNetworks,
                onClick = onLoadMoreNetworks,
                label = { Text(if (isLoadingNetworks) "Loading..." else "Load More...") },
                modifier = Modifier.testTag("campaign_editor_load_more_networks_chip")
              )
            }
          }
        }

        Text(
          text = "Verification Mode: ${verificationMode.replace('_', ' ')}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary
        )

        OutlinedTextField(
          value = rewardTfText,
          onValueChange = { rewardTfText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Server-Authoritative Reward (TF Coins)") },
          supportingText = {
            val preview = if (parsedRewardTf > 0L) {
              "${formatCoins(parsedRewardTf)} = ${formatRewardInr(coinsToCents(parsedRewardTf))} (125 TF = ₹1)"
            } else {
              "Enter positive TF amount (125 TF = ₹1)"
            }
            Text(preview)
          },
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_reward_input")
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = submissionBudgetText,
            onValueChange = { submissionBudgetText = it.filter { ch -> ch.isDigit() } },
            label = { Text("Max Conversions") },
            supportingText = { Text("0 = unlimited") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("campaign_config_sub_budget_input")
          )
          OutlinedTextField(
            value = rewardBudgetTfText,
            onValueChange = { rewardBudgetTfText = it.filter { ch -> ch.isDigit() } },
            label = { Text("TF Budget Cap") },
            supportingText = { Text("0 = unlimited") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("campaign_config_reward_budget_input")
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = startDate,
            onValueChange = { startDate = it },
            label = { Text("Start (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("campaign_config_start_date_input")
          )
          OutlinedTextField(
            value = endDate,
            onValueChange = { endDate = it },
            label = { Text("End (YYYY-MM-DD)") },
            singleLine = true,
            modifier = Modifier
              .weight(1f)
              .testTag("campaign_config_end_date_input")
          )
        }

        OutlinedTextField(
          value = countriesText,
          onValueChange = { countriesText = it },
          label = { Text("Eligible Countries (ALL or IN, US)") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_countries_input")
        )

        Text(
          text = "Eligible Platforms",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.SemiBold
        )
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          CampaignDevicePlatform.entries.forEach { platform ->
            val selected = selectedPlatforms.contains(platform.name)
            FilterChip(
              selected = selected,
              onClick = {
                selectedPlatforms =
                  if (selected) selectedPlatforms - platform.name
                  else selectedPlatforms + platform.name
              },
              label = { Text(platform.name) },
              modifier = Modifier.testTag("campaign_platform_chip_${platform.name}")
            )
          }
        }

        OutlinedTextField(
          value = trackingBaseUrl,
          onValueChange = { trackingBaseUrl = it },
          label = { Text("Tracking Base URL (HTTPS)") },
          placeholder = { Text("https://track.partner.com/click?offer=123") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("campaign_config_tracking_url_input")
        )

        OutlinedTextField(
          value = allowedHostsText,
          onValueChange = { allowedHostsText = it },
          label = { Text("Extra Allowed Hosts (optional, comma-separated)") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          OutlinedTextField(
            value = attributionWindowText,
            onValueChange = { attributionWindowText = it.filter { ch -> ch.isDigit() } },
            label = { Text("Attr Window (hrs)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
          OutlinedTextField(
            value = clickTtlText,
            onValueChange = { clickTtlText = it.filter { ch -> ch.isDigit() } },
            label = { Text("Click TTL (mins)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
          )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        EditorSwitchRow(
          label = "Campaign Active",
          checked = isActive,
          onCheckedChange = { isActive = it },
          tag = "campaign_config_active_switch"
        )
        EditorSwitchRow(
          label = "Require Admin Approval (Manual Review)",
          checked = requireAdminApproval,
          onCheckedChange = {
            requireAdminApproval = it
            if (it) autoApprovePostback = false
          },
          tag = "campaign_config_manual_review_switch"
        )
        EditorSwitchRow(
          label = "Auto-Approve Verified Postback",
          checked = autoApprovePostback,
          onCheckedChange = {
            autoApprovePostback = it
            if (it) requireAdminApproval = false
          },
          tag = "campaign_config_auto_approve_switch"
        )
        EditorSwitchRow(
          label = "Allow Multiple Conversions Per User",
          checked = allowMultipleConversions,
          onCheckedChange = { allowMultipleConversions = it },
          tag = "campaign_config_multi_conv_switch"
        )
        EditorSwitchRow(
          label = "Require Verified Country",
          checked = requireVerifiedCountry,
          onCheckedChange = { requireVerifiedCountry = it },
          tag = "campaign_config_verified_country_switch"
        )
        EditorSwitchRow(
          label = "Require Firebase App Check",
          checked = requireAppCheck,
          onCheckedChange = { requireAppCheck = it },
          tag = "campaign_config_app_check_switch"
        )
        Spacer(modifier = Modifier.height(4.dp))
      }
    },
    confirmButton = {
      Button(
        enabled = !isSaving,
        onClick = {
          // MEDIUM-1 / MEDIUM-2: Clearing startDate/endDate explicitly clears startAtMillis/expiresAtMillis
          // to 0L rather than retaining stale timestamps, and invalid dates remain null so
          // validateAdminCampaignConfigForm surfaces an explicit validation error.
          val cleanStartDate = startDate.trim()
          val cleanEndDate = endDate.trim()
          val parsedStartMillis = parseStrictIsoDateToUtcMillis(cleanStartDate, endOfDay = false)
          val parsedEndMillis = parseStrictIsoDateToUtcMillis(cleanEndDate, endOfDay = true)
          val startMillis = parsedStartMillis ?: 0L
          val endMillis = parsedEndMillis ?: 0L
          val parsedAttributionHours = attributionWindowText.trim().toIntOrNull() ?: 0
          val parsedClickTtlMinutes = clickTtlText.trim().toIntOrNull() ?: 0
          val candidate = AdminCampaignTrackingConfigItem(
            configId = campaignId.trim(),
            campaignId = campaignId.trim(),
            title = title.trim(),
            isActive = isActive,
            startDate = cleanStartDate,
            endDate = cleanEndDate,
            startAtMillis = startMillis,
            expiresAtMillis = endMillis,
            submissionBudget = submissionBudgetText.trim().toLongOrNull() ?: 0L,
            usedSubmissions = initial?.usedSubmissions ?: 0L,
            reservedClickSlots = initial?.reservedClickSlots ?: 0L,
            issuedClicksCount = initial?.issuedClicksCount ?: 0L,
            rewardBudgetTf = rewardBudgetTfText.trim().toLongOrNull() ?: 0L,
            spentBudgetTf = initial?.spentBudgetTf ?: 0L,
            allowedCountries = countriesText
              .split(",")
              .map { it.trim().uppercase(Locale.US) }
              .filter { it.isNotEmpty() },
            allowedPlatforms = selectedPlatforms.toList(),
            rewardTf = parsedRewardTf,
            rewardCents = coinsToCents(parsedRewardTf),
            verificationMode = verificationMode,
            networkId = selectedNetworkId,
            trackingNetworkRef = selectedNetworkId,
            trackingBaseUrl = trackingBaseUrl.trim(),
            allowedDestinationHosts = allowedHostsText
              .split(",")
              .map { it.trim().lowercase(Locale.US) }
              .filter { it.isNotEmpty() },
            parameterOverrides = initial?.parameterOverrides ?: emptyMap(),
            conversionRequirements = AdminCampaignConversionRequirements(
              requireUniqueClickId = initial?.conversionRequirements?.requireUniqueClickId ?: true,
              requireInstallAttribution = initial?.conversionRequirements?.requireInstallAttribution ?: false,
              autoApproveVerifiedPostback = autoApprovePostback,
              requireAdminApproval = requireAdminApproval,
              allowMultipleConversions = allowMultipleConversions,
              attributionWindowHours = parsedAttributionHours,
              requireVerifiedCountry = requireVerifiedCountry,
              requireAppCheck = requireAppCheck
            ),
            clickTtlMinutes = parsedClickTtlMinutes
          )
          val errors = validateAdminCampaignConfigForm(candidate, selectedNetwork)
          if (errors.isNotEmpty()) {
            validationErrors = errors
          } else {
            validationErrors = emptyList()
            onSave(candidate, isEdit)
          }
        },
        modifier = Modifier.testTag("campaign_config_form_save_button")
      ) {
        Text(if (isEdit) "Save Campaign" else "Create Campaign")
      }
    },
    dismissButton = {
      TextButton(
        enabled = !isSaving,
        onClick = onDismiss,
        modifier = Modifier.testTag("campaign_config_form_cancel_button")
      ) {
        Text("Cancel")
      }
    }
  )
}

@Composable
private fun EditorSwitchRow(
  label: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  tag: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f).padding(end = 8.dp)
    )
    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(tag)
    )
  }
}

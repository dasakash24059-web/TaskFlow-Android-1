package com.example.data.model

import java.util.Locale

/**
 * Explicitly registered Firebase Secret Manager key names accepted by the backend
 * `CAMPAIGN_POSTBACK_SECRET_REGISTRY`. Actual secret values are NEVER retrieved,
 * displayed, stored, or transmitted to Android clients.
 */
val REGISTERED_CAMPAIGN_SECRET_KEYS: List<String> = listOf(
  "CAMPAIGN_POSTBACK_SECRET",
  "CAMPAIGN_POSTBACK_SECRET_ALPHA",
  "CAMPAIGN_POSTBACK_SECRET_BETA",
  "CAMPAIGN_POSTBACK_SECRET_S2S"
)

/**
 * Supported HMAC canonicalization signing modes implemented by the backend
 * `verifyCampaignPostbackSignature`.
 */
val SUPPORTED_HMAC_SIGNING_MODES: List<String> = listOf(
  "SORTED_PARAMS",
  "SORTED_PARAMS_COMMA",
  "RAW_BODY",
  "CANONICAL_FIELDS"
)

val SUPPORTED_HMAC_ENCODINGS: List<String> = listOf("hex", "base64")

enum class CampaignAnalyticsDateRange(
  val wireValue: String,
  val label: String
) {
  TODAY("TODAY", "Today"),
  LAST_7_DAYS("7D", "7 Days"),
  LAST_30_DAYS("30D", "30 Days"),
  ALL_TIME("ALL", "All Time")
}

data class AdminNetworkSignatureConfig(
  val algorithm: String = NetworkSignatureAlgorithm.HMAC_SHA256.name,
  val headerName: String = "",
  val queryParamName: String = "sig",
  val secretEnvKey: String = REGISTERED_CAMPAIGN_SECRET_KEYS.first(),
  val signingMode: String = "SORTED_PARAMS",
  val encoding: String = "hex",
  val signedFields: List<String> = emptyList(),
  val fieldSeparator: String = "|"
)

data class AdminCampaignNetworkItem(
  val networkId: String = "",
  val name: String = "",
  val adapterType: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val isActive: Boolean = true,
  val capabilities: NetworkCapabilitiesSpec = NetworkCapabilitiesSpec(
    supportsIncentivizedTraffic = true,
    supportsPostback = true
  ),
  val allowedDestinationHosts: List<String> = emptyList(),
  val parameterMapping: NetworkParameterMapping = NetworkParameterMapping(),
  val postbackStatusMapping: Map<String, String> = emptyMap(),
  val signatureConfig: AdminNetworkSignatureConfig = AdminNetworkSignatureConfig(),
  val createdAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L
)

data class AdminCampaignConversionRequirements(
  val requireUniqueClickId: Boolean = true,
  val requireInstallAttribution: Boolean = false,
  val autoApproveVerifiedPostback: Boolean = true,
  val requireAdminApproval: Boolean = false,
  val allowMultipleConversions: Boolean = false,
  val attributionWindowHours: Int = 72,
  val requireVerifiedCountry: Boolean = false,
  val requireAppCheck: Boolean = false
)

data class AdminCampaignTrackingConfigItem(
  val configId: String = "",
  val campaignId: String = "",
  val title: String = "",
  val isActive: Boolean = true,
  val startDate: String = "",
  val endDate: String = "",
  val startAtMillis: Long = 0L,
  val expiresAtMillis: Long = 0L,
  val submissionBudget: Long = 0L,
  val usedSubmissions: Long = 0L,
  val reservedClickSlots: Long = 0L,
  val issuedClicksCount: Long = 0L,
  val rewardBudgetTf: Long = 0L,
  val spentBudgetTf: Long = 0L,
  val allowedCountries: List<String> = listOf("ALL"),
  val allowedPlatforms: List<String> = listOf(CampaignDevicePlatform.ANDROID.name),
  val rewardTf: Long = 125L,
  val rewardCents: Long = coinsToCents(125L),
  val verificationMode: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val networkId: String = "",
  val trackingNetworkRef: String = "",
  val trackingBaseUrl: String = "",
  val allowedDestinationHosts: List<String> = emptyList(),
  val parameterOverrides: Map<String, String> = emptyMap(),
  val conversionRequirements: AdminCampaignConversionRequirements = AdminCampaignConversionRequirements(),
  val clickTtlMinutes: Int = 60,
  val createdAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L
)

data class CampaignTrackingSummaryMetrics(
  val totalCampaigns: Int = 0,
  val activeCampaigns: Int = 0,
  val totalNetworks: Int = 0,
  val activeNetworks: Int = 0,
  val totalClicks: Long = 0L,
  val totalConversions: Long = 0L,
  val pendingConversions: Long = 0L,
  val approvedConversions: Long = 0L,
  val rejectedConversions: Long = 0L,
  val reversedConversions: Long = 0L,
  val approvalRatePercent: Double? = null,
  val hasConversionData: Boolean = false,
  val grossCreditedTf: Long = 0L,
  val reversedTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val netCreditedCents: Long = 0L
)

data class CampaignPerformanceRow(
  val campaignId: String = "",
  val title: String = "",
  val networkId: String = "",
  val verificationMode: String = "",
  val isActive: Boolean = false,
  val rewardTf: Long = 0L,
  val clicksCount: Long = 0L,
  val totalConversions: Long = 0L,
  val pendingConversions: Long = 0L,
  val approvedConversions: Long = 0L,
  val rejectedConversions: Long = 0L,
  val reversedConversions: Long = 0L,
  val approvalRatePercent: Double? = null,
  val grossCreditedTf: Long = 0L,
  val reversedTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val netCreditedCents: Long = 0L,
  val submissionBudget: Long = 0L,
  val usedSubmissions: Long = 0L,
  val reservedClickSlots: Long = 0L,
  val rewardBudgetTf: Long = 0L,
  val spentBudgetTf: Long = 0L,
  val budgetUtilizationPercent: Double? = null
)

data class NetworkPerformanceRow(
  val networkId: String = "",
  val name: String = "",
  val adapterType: String = "",
  val isActive: Boolean = false,
  val campaignsCount: Int = 0,
  val clicksCount: Long = 0L,
  val totalConversions: Long = 0L,
  val pendingConversions: Long = 0L,
  val approvedConversions: Long = 0L,
  val rejectedConversions: Long = 0L,
  val reversedConversions: Long = 0L,
  val approvalRatePercent: Double? = null,
  val grossCreditedTf: Long = 0L,
  val reversedTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val netCreditedCents: Long = 0L
)

data class AdminPaginatedSlice<T>(
  val items: List<T> = emptyList(),
  val nextCursor: String = "",
  val hasMore: Boolean = false,
  val totalCount: Int = 0
)

data class AdminCampaignDashboardResult(
  val dateRange: String = CampaignAnalyticsDateRange.LAST_30_DAYS.wireValue,
  val startAtMillis: Long = 0L,
  val endAtMillis: Long = 0L,
  val summary: CampaignTrackingSummaryMetrics = CampaignTrackingSummaryMetrics(),
  val campaignPerformance: AdminPaginatedSlice<CampaignPerformanceRow> = AdminPaginatedSlice(),
  val networkPerformance: AdminPaginatedSlice<NetworkPerformanceRow> = AdminPaginatedSlice()
)

data class AdminCampaignTrackingUiState(
  val isLoadingDashboard: Boolean = false,
  val dashboardError: String? = null,
  val hasLoadedDashboard: Boolean = false,
  val selectedDateRange: CampaignAnalyticsDateRange = CampaignAnalyticsDateRange.LAST_30_DAYS,
  val summary: CampaignTrackingSummaryMetrics = CampaignTrackingSummaryMetrics(),
  val campaignPerformance: AdminPaginatedSlice<CampaignPerformanceRow> = AdminPaginatedSlice(),
  val networkPerformance: AdminPaginatedSlice<NetworkPerformanceRow> = AdminPaginatedSlice(),
  val isLoadingNetworks: Boolean = false,
  val networksError: String? = null,
  val networks: AdminPaginatedSlice<AdminCampaignNetworkItem> = AdminPaginatedSlice(),
  val isLoadingConfigs: Boolean = false,
  val configsError: String? = null,
  val configs: AdminPaginatedSlice<AdminCampaignTrackingConfigItem> = AdminPaginatedSlice(),
  val isSaving: Boolean = false,
  val feedbackMessage: String? = null,
  val feedbackIsError: Boolean = false
)

/**
 * Returns the backend-supported signature algorithms for a given verification mode.
 */
fun allowedSignatureAlgorithmsForMode(mode: CampaignVerificationMode): List<NetworkSignatureAlgorithm> =
  when (mode) {
    CampaignVerificationMode.AFFILIATE_POSTBACK,
    CampaignVerificationMode.ADVERTISER_S2S_API -> listOf(
      NetworkSignatureAlgorithm.HMAC_SHA256,
      NetworkSignatureAlgorithm.HMAC_MD5,
      NetworkSignatureAlgorithm.SHARED_TOKEN_HEADER,
      NetworkSignatureAlgorithm.SHARED_TOKEN_QUERY
    )
    CampaignVerificationMode.TELEGRAM_BOT_API -> listOf(
      NetworkSignatureAlgorithm.TELEGRAM_SECRET_HEADER,
      NetworkSignatureAlgorithm.NONE
    )
    CampaignVerificationMode.MANUAL_PROOF,
    CampaignVerificationMode.CUSTOM_APPROVED -> listOf(
      NetworkSignatureAlgorithm.NONE,
      NetworkSignatureAlgorithm.HMAC_SHA256,
      NetworkSignatureAlgorithm.HMAC_MD5,
      NetworkSignatureAlgorithm.SHARED_TOKEN_HEADER,
      NetworkSignatureAlgorithm.SHARED_TOKEN_QUERY
    )
  }

/**
 * Mirrors server-side validation rules for Campaign Network forms so admins receive immediate
 * clear feedback before submission (backend always re-validates authoritatively).
 */
fun validateAdminNetworkForm(item: AdminCampaignNetworkItem): List<String> {
  val errors = mutableListOf<String>()
  val id = item.networkId.trim()
  if (!TRACKING_ID_REGEX.matches(id)) {
    errors += "Network ID must be 1-128 letters, numbers, '_' or '-'."
  }
  if (item.name.trim().isEmpty() || item.name.trim().length > 120) {
    errors += "Network name must be 1-120 characters."
  }
  val mode = CampaignVerificationMode.fromWireValue(item.adapterType)
  if (mode == null) {
    errors += "Select a valid adapter mode."
    return errors
  }
  val postbackRequired =
    mode == CampaignVerificationMode.AFFILIATE_POSTBACK ||
      mode == CampaignVerificationMode.ADVERTISER_S2S_API
  if (postbackRequired && !item.capabilities.supportsPostback) {
    errors += "${mode.name} requires Postback support to be enabled."
  }
  if (item.parameterMapping.clickIdParam.trim().isEmpty() ||
    item.parameterMapping.clickIdParam.length > 64
  ) {
    errors += "Click ID parameter name must be 1-64 characters."
  }

  val alg = NetworkSignatureAlgorithm.fromWireValue(item.signatureConfig.algorithm)
  val allowedAlgs = allowedSignatureAlgorithmsForMode(mode)
  if (alg == null || !allowedAlgs.contains(alg)) {
    errors += "Signature algorithm '${item.signatureConfig.algorithm}' is not supported for ${mode.name}."
  } else {
    if ((postbackRequired || item.capabilities.supportsPostback) && alg == NetworkSignatureAlgorithm.NONE) {
      errors += "Postback-enabled networks cannot use NONE signature verification."
    }
    if (alg != NetworkSignatureAlgorithm.NONE &&
      !REGISTERED_CAMPAIGN_SECRET_KEYS.contains(item.signatureConfig.secretEnvKey.trim())
    ) {
      errors += "Secret key must be one of the 4 registered Firebase Secret Manager keys."
    }
    if (alg == NetworkSignatureAlgorithm.HMAC_SHA256 || alg == NetworkSignatureAlgorithm.HMAC_MD5) {
      val signingMode = item.signatureConfig.signingMode.trim().uppercase(Locale.US)
      if (!SUPPORTED_HMAC_SIGNING_MODES.contains(signingMode)) {
        errors += "Unsupported HMAC canonicalization mode '$signingMode'."
      }
      if (!SUPPORTED_HMAC_ENCODINGS.contains(item.signatureConfig.encoding.trim().lowercase(Locale.US))) {
        errors += "HMAC encoding must be hex or base64."
      }
      if (signingMode == "CANONICAL_FIELDS" && item.signatureConfig.signedFields.none { it.isNotBlank() }) {
        errors += "CANONICAL_FIELDS signing mode requires at least one signed field."
      }
    }
  }
  item.allowedDestinationHosts.forEach { host ->
    val h = host.trim().lowercase(Locale.US)
    if (h.isNotEmpty() && (!h.contains(".") || h.contains("://") || h.contains(" ") || h == "localhost")) {
      errors += "Destination host '$h' must be a valid public domain (e.g., partner.example.com)."
    }
  }
  return errors
}

/**
 * Mirrors server-side validation rules for Campaign Tracking Configuration forms.
 */
fun validateAdminCampaignConfigForm(
  item: AdminCampaignTrackingConfigItem,
  selectedNetwork: AdminCampaignNetworkItem?
): List<String> {
  val errors = mutableListOf<String>()
  val id = item.campaignId.trim()
  if (!TRACKING_ID_REGEX.matches(id)) {
    errors += "Campaign ID must be 1-128 letters, numbers, '_' or '-'."
  }
  if (item.title.trim().isEmpty() || item.title.trim().length > 120) {
    errors += "Campaign title must be 1-120 characters."
  }
  if (item.networkId.trim().isEmpty() || selectedNetwork == null) {
    errors += "Select a registered campaign network."
  }
  val mode = CampaignVerificationMode.fromWireValue(item.verificationMode)
  if (mode == null) {
    errors += "Select a valid verification mode."
  } else if (selectedNetwork != null) {
    if (!selectedNetwork.adapterType.equals(mode.name, ignoreCase = true)) {
      errors += "Selected network adapter (${selectedNetwork.adapterType}) must match verification mode (${mode.name})."
    }
    if (item.isActive && !selectedNetwork.isActive) {
      errors += "Cannot activate campaign while selected network is disabled."
    }
    if ((mode == CampaignVerificationMode.AFFILIATE_POSTBACK ||
        mode == CampaignVerificationMode.ADVERTISER_S2S_API)
    ) {
      if (item.isActive && !selectedNetwork.capabilities.supportsIncentivizedTraffic) {
        errors += "Selected network does not permit incentivized traffic."
      }
      if (item.trackingBaseUrl.trim().isEmpty()) {
        errors += "Tracking Base URL is required for ${mode.name} campaigns."
      }
    }
  }
  if (item.rewardTf <= 0L || item.rewardTf > 1_000_000L) {
    errors += "Reward TF must be between 1 and 1,000,000 TF."
  }
  if (item.submissionBudget < 0L) {
    errors += "Submission limit cannot be negative."
  }
  if (item.submissionBudget > 0L && item.submissionBudget < item.usedSubmissions) {
    errors += "Submission limit cannot be less than used submissions (${item.usedSubmissions})."
  }
  if (item.rewardBudgetTf < 0L) {
    errors += "Reward TF budget cannot be negative."
  }
  if (item.rewardBudgetTf > 0L && item.rewardBudgetTf < item.rewardTf) {
    errors += "Reward TF budget cannot be smaller than a single reward (${item.rewardTf} TF)."
  }
  if (item.rewardBudgetTf > 0L && item.rewardBudgetTf < item.spentBudgetTf) {
    errors += "Reward TF budget cannot be less than already spent budget (${item.spentBudgetTf} TF)."
  }
  if (item.startAtMillis > 0L && item.expiresAtMillis > 0L && item.expiresAtMillis < item.startAtMillis) {
    errors += "Expiration date must be on or after the start date."
  }
  val countries = item.allowedCountries.map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
  if (countries.isEmpty() || countries.any { !COUNTRY_CODE_REGEX.matches(it) }) {
    errors += "Allowed countries must be 'ALL' or valid 2-letter ISO codes (e.g., IN, US)."
  }
  val platforms = item.allowedPlatforms.map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }
  if (platforms.isEmpty() || platforms.any { CampaignDevicePlatform.fromWireValue(it) == null }) {
    errors += "Select at least one valid target platform."
  }
  if (item.trackingBaseUrl.isNotBlank() && !isValidTaskUrl(item.trackingBaseUrl)) {
    errors += "Tracking Base URL must be a valid HTTPS URL."
  }
  if (item.conversionRequirements.attributionWindowHours !in 1..2160) {
    errors += "Attribution window must be between 1 and 2160 hours."
  }
  return errors
}

fun formatApprovalRateDisplay(ratePercent: Double?, totalConversions: Long): String {
  if (totalConversions <= 0L || ratePercent == null) return "N/A"
  return String.format(Locale.US, "%.1f%%", ratePercent)
}

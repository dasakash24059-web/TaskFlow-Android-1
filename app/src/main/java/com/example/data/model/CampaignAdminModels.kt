package com.example.data.model

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val MAX_CAMPAIGN_TRACKING_URL_LENGTH: Int = 2000
val STRICT_ISO_DATE_REGEX: Regex = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val IPV4_HOST_REGEX: Regex = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")

/**
 * Strictly parses a YYYY-MM-DD calendar date in UTC.
 * - Blank input returns 0L (unbounded / cleared).
 * - Invalid format or impossible calendar dates (e.g. 2026-02-30) return null so forms surface an
 *   explicit validation error instead of silently converting to 0L.
 */
fun parseStrictIsoDateToUtcMillis(rawDate: String, endOfDay: Boolean): Long? {
  val clean = rawDate.trim()
  if (clean.isEmpty()) return 0L
  if (!STRICT_ISO_DATE_REGEX.matches(clean)) return null
  return runCatching {
    val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
      isLenient = false
      timeZone = TimeZone.getTimeZone("UTC")
    }
    val parsed = formatter.parse(clean) ?: return null
    if (formatter.format(parsed) != clean) return null
    if (endOfDay) parsed.time + 86_399_999L else parsed.time
  }.getOrNull()
}

fun formatUtcMillisToIsoDate(millis: Long): String {
  if (millis <= 0L) return ""
  val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
    isLenient = false
    timeZone = TimeZone.getTimeZone("UTC")
  }
  return formatter.format(Date(millis))
}

/**
 * Validates a campaign tracking destination URL with full parity to backend
 * `validateDestinationUrl` (HTTPS only, max 2000 chars, no embedded credentials or control chars,
 * public domain host blocking localhost/.local/.internal/IPv4).
 */
fun isValidCampaignTrackingUrl(rawUrl: String): Boolean {
  val trimmed = rawUrl.trim()
  if (trimmed.isEmpty() || trimmed.length > MAX_CAMPAIGN_TRACKING_URL_LENGTH) return false
  if (!trimmed.startsWith("https://", ignoreCase = true)) return false
  if (trimmed.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F || it == '<' || it == '>' }) {
    return false
  }
  return try {
    val uri = URI(trimmed)
    val scheme = uri.scheme?.lowercase(Locale.US)
    val host = uri.host?.lowercase(Locale.US).orEmpty()
    if (scheme != "https") return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    if (host.isEmpty() || !host.contains(".")) return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".internal")) return false
    if (IPV4_HOST_REGEX.matches(host) || host.contains(":")) return false
    true
  } catch (_: Exception) {
    false
  }
}

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
  val updatedAtMillis: Long = 0L,
  val activeCampaignsCount: Int = 0
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
  val recoveredTf: Long = 0L,
  val shortfallTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val netWalletImpactTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val reversedCents: Long = 0L,
  val recoveredCents: Long = 0L,
  val shortfallCents: Long = 0L,
  val netCreditedCents: Long = 0L,
  val netWalletImpactCents: Long = 0L,
  val orphanedClicksCount: Long = 0L,
  val orphanedConversionsCount: Long = 0L,
  val orphanedGrossCreditedTf: Long = 0L,
  val orphanedReversedTf: Long = 0L,
  val orphanedCampaignCount: Int = 0,
  val orphanedNetworkCount: Int = 0
)

data class CampaignPerformanceRow(
  val campaignId: String = "",
  val title: String = "",
  val networkId: String = "",
  val verificationMode: String = "",
  val isActive: Boolean = false,
  val isOrphaned: Boolean = false,
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
  val recoveredTf: Long = 0L,
  val shortfallTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val reversedCents: Long = 0L,
  val recoveredCents: Long = 0L,
  val shortfallCents: Long = 0L,
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
  val isOrphaned: Boolean = false,
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
  val recoveredTf: Long = 0L,
  val shortfallTf: Long = 0L,
  val netCreditedTf: Long = 0L,
  val grossCreditedCents: Long = 0L,
  val reversedCents: Long = 0L,
  val recoveredCents: Long = 0L,
  val shortfallCents: Long = 0L,
  val netCreditedCents: Long = 0L
)

data class CampaignAnalyticsScanLimits(
  val clicksScanLimit: Int = 5000,
  val conversionsScanLimit: Int = 5000,
  val campaignsScanLimit: Int = 1000,
  val networksScanLimit: Int = 500,
  val clicksScanned: Int = 0,
  val conversionsScanned: Int = 0,
  val clicksTruncated: Boolean = false,
  val conversionsTruncated: Boolean = false,
  val campaignsTruncated: Boolean = false,
  val networksTruncated: Boolean = false,
  val isTruncated: Boolean = false,
  val queryStrategy: String = "INDEXED_TIMESTAMP_RANGE"
)

data class AdminPaginatedSlice<T>(
  val items: List<T> = emptyList(),
  val nextCursor: String = "",
  val hasMore: Boolean = false,
  val totalCount: Int = 0,
  val truncated: Boolean = false
)

data class AdminCampaignDashboardResult(
  val dateRange: String = CampaignAnalyticsDateRange.LAST_30_DAYS.wireValue,
  val timezone: String = "Asia/Kolkata",
  val startAtMillis: Long = 0L,
  val endAtMillis: Long = 0L,
  val summary: CampaignTrackingSummaryMetrics = CampaignTrackingSummaryMetrics(),
  val scanLimits: CampaignAnalyticsScanLimits = CampaignAnalyticsScanLimits(),
  val campaignPerformance: AdminPaginatedSlice<CampaignPerformanceRow> = AdminPaginatedSlice(),
  val networkPerformance: AdminPaginatedSlice<NetworkPerformanceRow> = AdminPaginatedSlice()
)

data class AdminCampaignTrackingUiState(
  val isLoadingDashboard: Boolean = false,
  val dashboardError: String? = null,
  val hasLoadedDashboard: Boolean = false,
  val selectedDateRange: CampaignAnalyticsDateRange = CampaignAnalyticsDateRange.LAST_30_DAYS,
  val timezone: String = "Asia/Kolkata",
  val summary: CampaignTrackingSummaryMetrics = CampaignTrackingSummaryMetrics(),
  val scanLimits: CampaignAnalyticsScanLimits = CampaignAnalyticsScanLimits(),
  val campaignPerformance: AdminPaginatedSlice<CampaignPerformanceRow> = AdminPaginatedSlice(),
  val networkPerformance: AdminPaginatedSlice<NetworkPerformanceRow> = AdminPaginatedSlice(),
  val isLoadingNetworks: Boolean = false,
  val networksError: String? = null,
  val networks: AdminPaginatedSlice<AdminCampaignNetworkItem> = AdminPaginatedSlice(),
  val resolvedNetworksById: Map<String, AdminCampaignNetworkItem> = emptyMap(),
  val isLoadingNetworkLookup: Boolean = false,
  val networkLookupError: String? = null,
  val isLoadingConfigs: Boolean = false,
  val configsError: String? = null,
  val configs: AdminPaginatedSlice<AdminCampaignTrackingConfigItem> = AdminPaginatedSlice(),
  val isLoadingConversions: Boolean = false,
  val conversionsError: String? = null,
  val conversionsFilterStatus: AdminConversionStatusFilter = AdminConversionStatusFilter.PENDING,
  val conversionsFilterCampaignId: String = "",
  val conversionsFilterNetworkId: String = "",
  val conversions: AdminPaginatedSlice<AdminCampaignConversionItem> = AdminPaginatedSlice(),
  val conversionsScanLimit: Int = 2000,
  val reviewingModerationId: String? = null,
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
      if (item.isActive && !selectedNetwork.capabilities.supportsPostback) {
        errors += "Selected network does not support S2S postbacks."
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

  // Strict ISO date validation (MEDIUM-1 / MEDIUM-2): never silently coerce invalid dates to 0L.
  val parsedStartMillis =
    if (item.startDate.trim().isNotEmpty()) parseStrictIsoDateToUtcMillis(item.startDate, endOfDay = false)
    else 0L
  val parsedEndMillis =
    if (item.endDate.trim().isNotEmpty()) parseStrictIsoDateToUtcMillis(item.endDate, endOfDay = true)
    else 0L
  if (item.startDate.trim().isNotEmpty() && parsedStartMillis == null) {
    errors += "Start date must be a valid YYYY-MM-DD calendar date."
  }
  if (item.endDate.trim().isNotEmpty() && parsedEndMillis == null) {
    errors += "End date must be a valid YYYY-MM-DD calendar date."
  }
  if (parsedStartMillis != null && parsedEndMillis != null &&
    parsedStartMillis > 0L && parsedEndMillis > 0L &&
    parsedEndMillis < parsedStartMillis
  ) {
    errors += "End date must be on or after the start date."
  } else if (item.startAtMillis > 0L && item.expiresAtMillis > 0L && item.expiresAtMillis < item.startAtMillis) {
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
  if (item.trackingBaseUrl.isNotBlank() && !isValidCampaignTrackingUrl(item.trackingBaseUrl)) {
    errors += "Tracking Base URL must be a valid public HTTPS URL (max $MAX_CAMPAIGN_TRACKING_URL_LENGTH characters)."
  }
  if (item.conversionRequirements.attributionWindowHours !in 1..2160) {
    errors += "Attribution window must be between 1 and 2160 hours."
  }
  if (item.clickTtlMinutes !in 1..43200) {
    errors += "Click TTL must be between 1 and 43200 minutes."
  }
  return errors
}

fun formatApprovalRateDisplay(ratePercent: Double?, totalConversions: Long): String {
  if (totalConversions <= 0L || ratePercent == null) return "N/A"
  return String.format(Locale.US, "%.1f%%", ratePercent)
}

// ============================================================================
// PHASE 3E.1 — USER TRACKED CAMPAIGN DISCOVERY & READ-ONLY REWARD ACTIVITY
// ============================================================================

data class UserTrackedCampaignItem(
  val campaignId: String = "",
  val title: String = "",
  val description: String = "",
  val category: String = "Tracked Offer",
  val rewardTf: Long = 0L,
  val rewardCents: Long = 0L,
  val verificationMode: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val requiresAdminApproval: Boolean = false,
  val attributionWindowHours: Int = 72,
  val expiresAtMillis: Long = 0L,
  val remainingSubmissions: Long? = null,
  val userStatus: String = "AVAILABLE", // AVAILABLE, PENDING, COMPLETED
  val canClick: Boolean = true,
  val allowMultipleConversions: Boolean = false
)

data class UserTrackedCampaignsUiState(
  val isLoading: Boolean = false,
  val error: String? = null,
  val hasLoaded: Boolean = false,
  val accountApproved: Boolean = true,
  val items: List<UserTrackedCampaignItem> = emptyList(),
  val nextCursor: String = "",
  val hasMore: Boolean = false,
  val totalCount: Int = 0,
  val launchingCampaignId: String? = null,
  val feedbackMessage: String? = null,
  val feedbackIsError: Boolean = false
)

data class UserTrackedRewardActivityItem(
  val activityId: String = "",
  val campaignId: String = "",
  val campaignTitle: String = "",
  val status: String = "PENDING", // PENDING, APPROVED, REJECTED, REVERSED
  val statusLabel: String = "Pending Verification",
  val rewardTf: Long = 0L,
  val rewardCents: Long = 0L,
  val rewardCredited: Boolean = false,
  val createdAtMillis: Long = 0L,
  val verifiedAtMillis: Long = 0L,
  val rejectedAtMillis: Long = 0L,
  val reversedAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L
)

data class UserTrackedRewardActivityCounts(
  val pending: Int = 0,
  val approved: Int = 0,
  val rejected: Int = 0,
  val reversed: Int = 0
) {
  val total: Int get() = pending + approved + rejected + reversed
}

data class UserTrackedRewardActivityUiState(
  val isLoading: Boolean = false,
  val error: String? = null,
  val hasLoaded: Boolean = false,
  val selectedStatusFilter: String = "ALL", // ALL, PENDING, APPROVED, REJECTED, REVERSED
  val items: List<UserTrackedRewardActivityItem> = emptyList(),
  val counts: UserTrackedRewardActivityCounts = UserTrackedRewardActivityCounts(),
  val nextCursor: String = "",
  val hasMore: Boolean = false,
  val totalCount: Int = 0,
  val truncated: Boolean = false
)

/**
 * Translates server-side `createCampaignClick` failure messages into clear, user-actionable
 * copy covering cooldown, budget exhaustion, expiry, geo-ineligibility, account approval,
 * authentication, and App Check / device attestation errors.
 */
fun formatUserCampaignClickError(rawMessage: String?): String {
  val msg = rawMessage?.trim().orEmpty()
  if (msg.isEmpty()) return "Unable to start this tracked offer right now. Please try again shortly."
  val lower = msg.lowercase(Locale.US)
  return when {
    lower.contains("unauthenticated") || lower.contains("sign in") || lower.contains("authentication") ->
      "Please sign in again to start this tracked offer."
    lower.contains("app check") || lower.contains("attestation") || lower.contains("device verification") ->
      "Device security verification failed. Please update TaskFlow from Play Store and try again."
    lower.contains("not approved") || lower.contains("approval") ->
      "Your account must be approved before participating in tracked reward offers."
    lower.contains("cooldown") || lower.contains("too many") || lower.contains("rate limit") || lower.contains("wait") ->
      "Please wait a moment before starting this offer again (cooldown active)."
    lower.contains("budget") || lower.contains("exhausted") || lower.contains("slots") || lower.contains("full") ->
      "This offer has reached its reward budget limit and is no longer accepting new entries."
    lower.contains("expired") || lower.contains("not started") || lower.contains("ended") ->
      "This tracked offer is currently outside its active schedule."
    lower.contains("country") || lower.contains("region") || lower.contains("geo") ->
      "This offer is not available in your verified region."
    lower.contains("already completed") || lower.contains("already converted") ->
      "You have already completed this tracked offer."
    lower.contains("inactive") || lower.contains("paused") || lower.contains("disabled") ->
      "This offer is currently paused. Please choose another active offer."
    else -> msg
  }
}

// ============================================================================
// PHASE 3E.2 — ADMIN CONVERSION MODERATION QUEUE MODELS
// ============================================================================

enum class AdminConversionStatusFilter(
  val wireValue: String,
  val label: String
) {
  PENDING("PENDING", "Pending"),
  APPROVED("APPROVED", "Approved"),
  REJECTED("REJECTED", "Rejected"),
  REVERSED("REVERSED", "Reversed"),
  ALL("ALL", "All")
}

enum class AdminConversionReviewAction(
  val wireValue: String,
  val label: String
) {
  APPROVE("APPROVE", "Approve"),
  REJECT("REJECT", "Reject"),
  REVERSE("REVERSE", "Reverse")
}

data class AdminCampaignConversionItem(
  val moderationId: String = "",
  val displayRef: String = "",
  val campaignId: String = "",
  val campaignTitle: String = "",
  val networkId: String = "",
  val networkName: String = "",
  val userId: String = "",
  val userDisplayName: String = "",
  val verificationMode: String = "",
  val state: String = "PENDING",
  val rewardTf: Long = 0L,
  val rewardCents: Long = 0L,
  val rewardCredited: Boolean = false,
  val recoveredTf: Long = 0L,
  val recoveredCents: Long = 0L,
  val shortfallTf: Long = 0L,
  val shortfallCents: Long = 0L,
  val expectedRecoverableTf: Long = 0L,
  val expectedRecoverableCents: Long = 0L,
  val estimatedShortfallTf: Long = 0L,
  val estimatedShortfallCents: Long = 0L,
  val statusReason: String = "",
  val sourceCallbackType: String = "",
  val approvedByAdminId: String = "",
  val rejectedByAdminId: String = "",
  val reversedByAdminId: String = "",
  val createdAtMillis: Long = 0L,
  val verifiedAtMillis: Long = 0L,
  val rejectedAtMillis: Long = 0L,
  val reversedAtMillis: Long = 0L,
  val updatedAtMillis: Long = 0L
)

data class AdminConversionReviewResult(
  val moderationId: String = "",
  val campaignId: String = "",
  val networkId: String = "",
  val userId: String = "",
  val state: String = "",
  val duplicate: Boolean = false,
  val rewardTf: Long = 0L,
  val rewardCredited: Boolean = false,
  val recoveredCoins: Long = 0L,
  val shortfallCoins: Long = 0L
)


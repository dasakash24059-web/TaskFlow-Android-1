package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import java.util.Locale

/**
 * Canonical Firestore collection names for the Universal Campaign Tracking Engine (Phase 3A Foundation).
 * All collections below are strictly server-only (`allow read, write: if false;`) in `firestore.rules`.
 */
object CampaignTrackingCollections {
  const val CAMPAIGN_TRACKING_CONFIGS = "campaign_tracking_configs"
  const val CAMPAIGN_CLICKS = "campaign_clicks"
  const val CAMPAIGN_CONVERSIONS = "campaign_conversions"
  const val CAMPAIGN_NETWORKS = "campaign_networks"
  const val CAMPAIGN_CLICK_GUARDS = "campaign_click_guards"
}

const val CAMPAIGN_CONVERSION_LOCKS_COLLECTION = "campaign_conversion_locks"

/**
 * Supported verification modes for campaigns tracked by TaskFlow.
 */
enum class CampaignVerificationMode {
  AFFILIATE_POSTBACK,
  ADVERTISER_S2S_API,
  TELEGRAM_BOT_API,
  MANUAL_PROOF,
  CUSTOM_APPROVED;

  companion object {
    fun fromWireValue(raw: String?): CampaignVerificationMode? {
      val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
      return entries.firstOrNull { it.name == normalized }
    }
  }
}

/**
 * Lifecycle states for a tracked campaign conversion.
 */
enum class CampaignConversionState {
  PENDING,
  APPROVED,
  REJECTED,
  REVERSED;

  val isTerminal: Boolean
    get() = this == REJECTED || this == REVERSED

  fun canTransitionTo(nextState: CampaignConversionState): Boolean =
    when (this) {
      PENDING -> nextState == APPROVED || nextState == REJECTED
      APPROVED -> nextState == REVERSED
      REJECTED -> false
      REVERSED -> false
    }

  companion object {
    fun fromWireValue(raw: String?): CampaignConversionState? {
      val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
      return entries.firstOrNull { it.name == normalized }
    }
  }
}

enum class CampaignClickStatus {
  ISSUED,
  PENDING_VERIFICATION,
  CONVERTED,
  EXPIRED,
  CANCELLED;

  companion object {
    fun fromWireValue(raw: String?): CampaignClickStatus? {
      val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
      return entries.firstOrNull { it.name == normalized }
    }
  }
}

enum class CampaignDevicePlatform {
  ANDROID,
  IOS,
  WEB,
  ALL;

  companion object {
    fun fromWireValue(raw: String?): CampaignDevicePlatform? {
      val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
      return entries.firstOrNull { it.name == normalized }
    }
  }
}

enum class NetworkSignatureAlgorithm {
  NONE,
  HMAC_SHA256,
  HMAC_MD5,
  SHARED_TOKEN_HEADER,
  SHARED_TOKEN_QUERY,
  TELEGRAM_SECRET_HEADER;

  companion object {
    fun fromWireValue(raw: String?): NetworkSignatureAlgorithm? {
      val normalized = raw?.trim()?.uppercase(Locale.US).orEmpty()
      return entries.firstOrNull { it.name == normalized }
    }
  }
}

internal val TRACKING_ID_REGEX = Regex("^[a-zA-Z0-9_-]{1,128}$")
internal val COUNTRY_CODE_REGEX = Regex("^(ALL|[A-Z]{2})$")
internal val SENSITIVE_TRACKING_KEYS = setOf(
  "postbackSecret",
  "postbackSecretHash",
  "hmacSecret",
  "apiKey",
  "apiSecret",
  "clientSecret",
  "webhookSecret",
  "botToken",
  "privateSecretRef",
  "authToken"
)

/**
 * Reusable campaign eligibility specification covering active status, schedule windows,
 * submission/TF budgets, allowed countries & platforms, TF reward, verification mode,
 * and tracking network configuration reference.
 */
@Keep
@IgnoreExtraProperties
data class CampaignEligibilitySpec(
  @get:PropertyName("isActive")
  @PropertyName("isActive")
  val isActive: Boolean = true,
  val startDate: String = "",
  val endDate: String = "",
  val startAtMillis: Long = 0L,
  val expiresAtMillis: Long = 0L,
  val submissionBudget: Long = 0L,
  val usedSubmissions: Long = 0L,
  val rewardBudgetTf: Long = 0L,
  val spentBudgetTf: Long = 0L,
  val allowedCountries: List<String> = listOf("ALL"),
  val allowedPlatforms: List<String> = listOf(CampaignDevicePlatform.ANDROID.name),
  val rewardTf: Long = 0L,
  val verificationMode: String = CampaignVerificationMode.MANUAL_PROOF.name,
  val networkId: String = "",
  val trackingNetworkRef: String = ""
) {
  fun normalizedCountries(): List<String> =
    allowedCountries.map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }.distinct()

  fun normalizedPlatforms(): List<String> =
    allowedPlatforms.map { it.trim().uppercase(Locale.US) }.filter { it.isNotEmpty() }.distinct()

  fun resolvedVerificationMode(): CampaignVerificationMode? =
    CampaignVerificationMode.fromWireValue(verificationMode)

  fun isCountryAllowed(countryCode: String): Boolean {
    val countries = normalizedCountries()
    if (countries.isEmpty() || countries.contains("ALL")) return true
    val target = countryCode.trim().uppercase(Locale.US)
    if (target.isEmpty()) return false
    return countries.contains(target)
  }

  fun isPlatformAllowed(platform: String): Boolean {
    val platforms = normalizedPlatforms()
    if (platforms.isEmpty() || platforms.contains(CampaignDevicePlatform.ALL.name)) return true
    val target = platform.trim().uppercase(Locale.US)
    if (target.isEmpty()) return false
    return platforms.contains(target)
  }

  fun isWithinSchedule(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (startAtMillis > 0L && nowMillis < startAtMillis) return false
    if (expiresAtMillis > 0L && nowMillis > expiresAtMillis) return false
    return true
  }

  fun hasRemainingBudget(requestedRewardTf: Long = rewardTf): Boolean {
    if (submissionBudget > 0L && usedSubmissions >= submissionBudget) return false
    if (rewardBudgetTf > 0L && (spentBudgetTf + requestedRewardTf) > rewardBudgetTf) return false
    return true
  }

  fun isEligible(
    countryCode: String,
    platform: String,
    nowMillis: Long = System.currentTimeMillis()
  ): Boolean {
    if (!isActive) return false
    if (validate().isNotEmpty()) return false
    return isWithinSchedule(nowMillis) &&
      hasRemainingBudget(rewardTf) &&
      isCountryAllowed(countryCode) &&
      isPlatformAllowed(platform)
  }

  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (rewardTf <= 0L) {
      errors += "rewardTf must be greater than 0."
    }
    if (submissionBudget < 0L || usedSubmissions < 0L) {
      errors += "submissionBudget and usedSubmissions must be non-negative."
    }
    if (submissionBudget > 0L && usedSubmissions > submissionBudget) {
      errors += "usedSubmissions cannot exceed submissionBudget."
    }
    if (rewardBudgetTf < 0L || spentBudgetTf < 0L) {
      errors += "rewardBudgetTf and spentBudgetTf must be non-negative."
    }
    if (rewardBudgetTf > 0L && rewardBudgetTf < rewardTf) {
      errors += "rewardBudgetTf cannot be less than a single rewardTf payout."
    }
    if (startAtMillis < 0L || expiresAtMillis < 0L) {
      errors += "startAtMillis and expiresAtMillis must be non-negative."
    }
    if (startAtMillis > 0L && expiresAtMillis > 0L && expiresAtMillis < startAtMillis) {
      errors += "expiresAtMillis must be greater than or equal to startAtMillis."
    }
    val mode = resolvedVerificationMode()
    if (mode == null) {
      errors += "verificationMode must be a valid CampaignVerificationMode."
    }
    if (networkId.trim().isEmpty() || !TRACKING_ID_REGEX.matches(networkId.trim())) {
      errors += "networkId must be a valid identifier (1-128 alphanumeric/'_'/'-' chars)."
    }
    if (trackingNetworkRef.trim().isEmpty()) {
      errors += "trackingNetworkRef cannot be blank."
    }
    val countries = normalizedCountries()
    if (countries.isEmpty()) {
      errors += "allowedCountries must contain at least one entry (e.g., ALL or ISO-3166 alpha-2)."
    } else if (countries.size > 50 || countries.any { !COUNTRY_CODE_REGEX.matches(it) }) {
      errors += "allowedCountries entries must be valid ISO alpha-2 codes or ALL (max 50)."
    }
    val platforms = normalizedPlatforms()
    if (platforms.isEmpty()) {
      errors += "allowedPlatforms must contain at least one platform."
    } else if (platforms.size > 10 || platforms.any { CampaignDevicePlatform.fromWireValue(it) == null }) {
      errors += "allowedPlatforms contains unsupported device platform entries."
    }
    return errors
  }
}

@Keep
@IgnoreExtraProperties
data class NetworkCapabilitiesSpec(
  val supportsIncentivizedTraffic: Boolean = false,
  val supportsPostback: Boolean = false,
  val supportsInstallAttribution: Boolean = false,
  val supportsServerToServerPolling: Boolean = false,
  val requiresManualReview: Boolean = false
)

@Keep
@IgnoreExtraProperties
data class NetworkParameterMapping(
  val clickIdParam: String = "click_id",
  val userIdParam: String = "",
  val campaignIdParam: String = "",
  val externalTxnParam: String = "txn_id",
  val statusParam: String = "status",
  val payoutParam: String = "payout",
  val signatureParam: String = "sig",
  val extraStaticParams: Map<String, String> = emptyMap()
) {
  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (clickIdParam.trim().isEmpty() || clickIdParam.length > 64) {
      errors += "clickIdParam must be 1..64 characters."
    }
    if (externalTxnParam.length > 64 || statusParam.length > 64 || payoutParam.length > 64) {
      errors += "Parameter names must not exceed 64 characters."
    }
    if (extraStaticParams.size > 20) {
      errors += "extraStaticParams cannot exceed 20 entries."
    }
    return errors
  }
}

/**
 * Describes HOW a network's callback authenticity is verified.
 * Only holds key/reference metadata (e.g., `secretEnvKey`) — NEVER stores raw secrets.
 */
@Keep
@IgnoreExtraProperties
data class NetworkSignatureSpec(
  val algorithm: String = NetworkSignatureAlgorithm.HMAC_SHA256.name,
  val headerName: String = "",
  val queryParamName: String = "sig",
  val secretEnvKey: String = ""
) {
  fun validate(verificationMode: CampaignVerificationMode?): List<String> {
    val errors = mutableListOf<String>()
    val parsedAlg = NetworkSignatureAlgorithm.fromWireValue(algorithm)
    if (parsedAlg == null) {
      errors += "algorithm must be a valid NetworkSignatureAlgorithm."
    }
    if ((verificationMode == CampaignVerificationMode.AFFILIATE_POSTBACK ||
        verificationMode == CampaignVerificationMode.ADVERTISER_S2S_API) &&
      parsedAlg == NetworkSignatureAlgorithm.NONE
    ) {
      errors += "$verificationMode cannot use signature algorithm NONE."
    }
    if (parsedAlg != null && parsedAlg != NetworkSignatureAlgorithm.NONE && secretEnvKey.isBlank()) {
      errors += "secretEnvKey reference name is required when signature verification is enabled."
    }
    return errors
  }
}

/**
 * Server-only network configuration document (`campaign_networks/{networkId}`).
 * Never readable by Android clients or ordinary Admin UI queries.
 */
@Keep
@IgnoreExtraProperties
data class CampaignNetworkConfig(
  val networkId: String = "",
  val name: String = "",
  val adapterType: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  @get:PropertyName("isActive")
  @PropertyName("isActive")
  val isActive: Boolean = true,
  val capabilities: NetworkCapabilitiesSpec = NetworkCapabilitiesSpec(),
  val parameterMapping: NetworkParameterMapping = NetworkParameterMapping(),
  val postbackStatusMapping: Map<String, String> = emptyMap(),
  val signatureConfig: NetworkSignatureSpec = NetworkSignatureSpec(),
  val createdBy: String = "",
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null
) {
  fun canonicalRef(): String = "${CampaignTrackingCollections.CAMPAIGN_NETWORKS}/$networkId"

  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (!TRACKING_ID_REGEX.matches(networkId.trim())) {
      errors += "networkId must match ^[a-zA-Z0-9_-]{1,128}$."
    }
    if (name.trim().isEmpty() || name.length > 120) {
      errors += "name must be 1..120 characters."
    }
    val mode = CampaignVerificationMode.fromWireValue(adapterType)
    if (mode == null) {
      errors += "adapterType must be a valid CampaignVerificationMode."
    }
    errors += parameterMapping.validate()
    errors += signatureConfig.validate(mode)
    postbackStatusMapping.values.forEach { targetState ->
      if (CampaignConversionState.fromWireValue(targetState) == null) {
        errors += "postbackStatusMapping maps to invalid conversion state '$targetState'."
      }
    }
    return errors
  }

  fun toClientSafeSummary(): CampaignNetworkSummary =
    CampaignNetworkSummary(
      networkId = networkId,
      name = name,
      adapterType = adapterType,
      isActive = isActive,
      supportsIncentivizedTraffic = capabilities.supportsIncentivizedTraffic,
      supportsPostback = capabilities.supportsPostback,
      supportsInstallAttribution = capabilities.supportsInstallAttribution
    )
}

@Keep
@IgnoreExtraProperties
data class CampaignNetworkSummary(
  val networkId: String = "",
  val name: String = "",
  val adapterType: String = "",
  @get:PropertyName("isActive")
  @PropertyName("isActive")
  val isActive: Boolean = true,
  val supportsIncentivizedTraffic: Boolean = false,
  val supportsPostback: Boolean = false,
  val supportsInstallAttribution: Boolean = false
)

@Keep
@IgnoreExtraProperties
data class CampaignConversionRequirements(
  val requireUniqueClickId: Boolean = true,
  val requireInstallAttribution: Boolean = false,
  val autoApproveVerifiedPostback: Boolean = false,
  val requireAdminApproval: Boolean = false,
  val allowMultipleConversions: Boolean = false,
  val attributionWindowHours: Int = 72
) {
  fun validate(mode: CampaignVerificationMode?): List<String> {
    val errors = mutableListOf<String>()
    if (attributionWindowHours !in 1..2160) {
      errors += "attributionWindowHours must be between 1 and 2160 hours."
    }
    if (mode == CampaignVerificationMode.AFFILIATE_POSTBACK && !requireUniqueClickId) {
      errors += "AFFILIATE_POSTBACK requires unique click ID correlation."
    }
    return errors
  }
}

/**
 * Server-only campaign tracking configuration (`campaign_tracking_configs/{configId}`).
 * Stores attribution rules, eligibility gates, network references, and server secret refs.
 * Private secret references and postback hashes are NEVER readable by Android clients or Admin UI queries.
 */
@Keep
@IgnoreExtraProperties
data class CampaignTrackingConfig(
  val configId: String = "",
  val campaignId: String = "",
  @get:PropertyName("isActive")
  @PropertyName("isActive")
  val isActive: Boolean = true,
  val startDate: String = "",
  val endDate: String = "",
  val startAtMillis: Long = 0L,
  val expiresAtMillis: Long = 0L,
  val startAt: Timestamp? = null,
  val expiresAt: Timestamp? = null,
  val submissionBudget: Long = 0L,
  val usedSubmissions: Long = 0L,
  val rewardBudgetTf: Long = 0L,
  val spentBudgetTf: Long = 0L,
  val allowedCountries: List<String> = listOf("ALL"),
  val allowedPlatforms: List<String> = listOf(CampaignDevicePlatform.ANDROID.name),
  val rewardTf: Long = 0L,
  val verificationMode: String = CampaignVerificationMode.MANUAL_PROOF.name,
  val networkId: String = "",
  val trackingNetworkRef: String = "",
  val trackingBaseUrl: String = "",
  val parameterOverrides: Map<String, String> = emptyMap(),
  val conversionRequirements: CampaignConversionRequirements = CampaignConversionRequirements(),
  val privateSecretRef: String = "",
  val postbackSecretHash: String = "",
  val createdBy: String = "",
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null
) {
  fun toEligibilitySpec(): CampaignEligibilitySpec =
    CampaignEligibilitySpec(
      isActive = isActive,
      startDate = startDate,
      endDate = endDate,
      startAtMillis = startAtMillis,
      expiresAtMillis = expiresAtMillis,
      submissionBudget = submissionBudget,
      usedSubmissions = usedSubmissions,
      rewardBudgetTf = rewardBudgetTf,
      spentBudgetTf = spentBudgetTf,
      allowedCountries = allowedCountries,
      allowedPlatforms = allowedPlatforms,
      rewardTf = rewardTf,
      verificationMode = verificationMode,
      networkId = networkId,
      trackingNetworkRef = trackingNetworkRef.ifBlank {
        if (networkId.isNotBlank()) "${CampaignTrackingCollections.CAMPAIGN_NETWORKS}/$networkId" else ""
      }
    )

  /**
   * Produces a redacted, secret-free summary projection.
   * Never includes `privateSecretRef`, `postbackSecretHash`, or raw credentials.
   */
  fun toClientSafeSummary(): CampaignTrackingSummary =
    CampaignTrackingSummary(
      configId = configId,
      campaignId = campaignId,
      isActive = isActive,
      verificationMode = verificationMode,
      networkId = networkId,
      rewardTf = rewardTf,
      submissionBudget = submissionBudget,
      usedSubmissions = usedSubmissions,
      allowedCountries = allowedCountries,
      allowedPlatforms = allowedPlatforms,
      startAtMillis = startAtMillis,
      expiresAtMillis = expiresAtMillis
    )

  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (!TRACKING_ID_REGEX.matches(configId.trim())) {
      errors += "configId must match ^[a-zA-Z0-9_-]{1,128}$."
    }
    if (!TRACKING_ID_REGEX.matches(campaignId.trim())) {
      errors += "campaignId must match ^[a-zA-Z0-9_-]{1,128}$."
    }
    errors += toEligibilitySpec().validate()
    val mode = CampaignVerificationMode.fromWireValue(verificationMode)
    errors += conversionRequirements.validate(mode)
    if (trackingBaseUrl.isNotBlank() && !isValidTaskUrl(trackingBaseUrl)) {
      errors += "trackingBaseUrl must be a valid HTTPS URL when provided."
    }
    if (parameterOverrides.size > 20) {
      errors += "parameterOverrides cannot exceed 20 entries."
    }
    return errors
  }

  companion object {
    /**
     * Verifies a serialized summary map contains no private secret/credential fields.
     */
    fun hasNoSecretFields(serializedKeys: Set<String>): Boolean =
      serializedKeys.none { key ->
        SENSITIVE_TRACKING_KEYS.any { sensitive ->
          key.equals(sensitive, ignoreCase = true) || key.contains("secret", ignoreCase = true)
        }
      }
  }
}

/**
 * Redacted tracking summary view without private secret references or credentials.
 */
@Keep
@IgnoreExtraProperties
data class CampaignTrackingSummary(
  val configId: String = "",
  val campaignId: String = "",
  @get:PropertyName("isActive")
  @PropertyName("isActive")
  val isActive: Boolean = true,
  val verificationMode: String = "",
  val networkId: String = "",
  val rewardTf: Long = 0L,
  val submissionBudget: Long = 0L,
  val usedSubmissions: Long = 0L,
  val allowedCountries: List<String> = emptyList(),
  val allowedPlatforms: List<String> = emptyList(),
  val startAtMillis: Long = 0L,
  val expiresAtMillis: Long = 0L
) {
  fun toSafeMap(): Map<String, Any> = mapOf(
    "configId" to configId,
    "campaignId" to campaignId,
    "isActive" to isActive,
    "verificationMode" to verificationMode,
    "networkId" to networkId,
    "rewardTf" to rewardTf,
    "submissionBudget" to submissionBudget,
    "usedSubmissions" to usedSubmissions,
    "allowedCountries" to allowedCountries,
    "allowedPlatforms" to allowedPlatforms,
    "startAtMillis" to startAtMillis,
    "expiresAtMillis" to expiresAtMillis
  )
}

/**
 * Server-only click tracking document schema (`campaign_clicks/{clickId}`).
 * Created exclusively by server-side Cloud Functions (future phase); read/write blocked for all clients.
 */
@Keep
@IgnoreExtraProperties
data class CampaignClickRecord(
  val clickId: String = "",
  val userId: String = "",
  val campaignId: String = "",
  val networkId: String = "",
  val trackingNetworkRef: String = "",
  val verificationMode: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val countryCode: String = "",
  val devicePlatform: String = CampaignDevicePlatform.ANDROID.name,
  val resolvedParameters: Map<String, String> = emptyMap(),
  val status: String = CampaignClickStatus.ISSUED.name,
  val expiresAtMillis: Long = 0L,
  val expiresAt: Timestamp? = null,
  val createdAt: Timestamp? = null
) {
  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (clickId.trim().length < 16 || !TRACKING_ID_REGEX.matches(clickId.trim())) {
      errors += "clickId must be an unpredictable identifier of 16-128 safe characters."
    }
    if (!TRACKING_ID_REGEX.matches(userId.trim())) {
      errors += "userId must be a valid UID."
    }
    if (!TRACKING_ID_REGEX.matches(campaignId.trim())) {
      errors += "campaignId must be valid."
    }
    if (!TRACKING_ID_REGEX.matches(networkId.trim())) {
      errors += "networkId must be valid."
    }
    if (trackingNetworkRef.isBlank()) {
      errors += "trackingNetworkRef cannot be blank."
    }
    if (CampaignVerificationMode.fromWireValue(verificationMode) == null) {
      errors += "verificationMode is invalid."
    }
    if (CampaignDevicePlatform.fromWireValue(devicePlatform) == null) {
      errors += "devicePlatform is invalid."
    }
    if (CampaignClickStatus.fromWireValue(status) == null) {
      errors += "status must be a valid CampaignClickStatus."
    }
    if (expiresAtMillis <= 0L) {
      errors += "expiresAtMillis must be greater than 0."
    }
    return errors
  }
}

/**
 * Server-only conversion tracking document schema (`campaign_conversions/{conversionId}`).
 * Maintains conversion lifecycle (`PENDING`, `APPROVED`, `REJECTED`, `REVERSED`) and idempotency keys.
 * Strictly server-only in Firestore rules; `rewardCredited` remains `false` in Phase 3A foundation.
 */
@Keep
@IgnoreExtraProperties
data class CampaignConversionRecord(
  val conversionId: String = "",
  val clickId: String = "",
  val userId: String = "",
  val campaignId: String = "",
  val networkId: String = "",
  val trackingNetworkRef: String = "",
  val verificationMode: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val state: String = CampaignConversionState.PENDING.name,
  val rewardTf: Long = 0L,
  val externalTransactionId: String = "",
  val idempotencyKey: String = "",
  val statusReason: String = "",
  val sourceCallbackType: String = "",
  val rewardCredited: Boolean = false,
  val verifiedAt: Timestamp? = null,
  val reversedAt: Timestamp? = null,
  val createdAt: Timestamp? = null,
  val updatedAt: Timestamp? = null
) {
  fun resolvedState(): CampaignConversionState? = CampaignConversionState.fromWireValue(state)

  fun canTransitionTo(nextState: CampaignConversionState): Boolean {
    val current = resolvedState() ?: return false
    return current.canTransitionTo(nextState)
  }

  fun validate(): List<String> {
    val errors = mutableListOf<String>()
    if (!TRACKING_ID_REGEX.matches(conversionId.trim())) {
      errors += "conversionId must match ^[a-zA-Z0-9_-]{1,128}$."
    }
    if (!TRACKING_ID_REGEX.matches(userId.trim())) {
      errors += "userId must be valid."
    }
    if (!TRACKING_ID_REGEX.matches(campaignId.trim())) {
      errors += "campaignId must be valid."
    }
    if (!TRACKING_ID_REGEX.matches(networkId.trim())) {
      errors += "networkId must be valid."
    }
    val mode = CampaignVerificationMode.fromWireValue(verificationMode)
    if (mode == null) {
      errors += "verificationMode is invalid."
    } else if ((mode == CampaignVerificationMode.AFFILIATE_POSTBACK ||
        mode == CampaignVerificationMode.ADVERTISER_S2S_API) &&
      clickId.trim().isEmpty()
    ) {
      errors += "clickId is required for $mode conversions."
    }
    if (CampaignConversionState.fromWireValue(state) == null) {
      errors += "state must be PENDING, APPROVED, REJECTED, or REVERSED."
    }
    if (rewardTf <= 0L) {
      errors += "rewardTf must be positive."
    }
    if (idempotencyKey.trim().length < 6 || !TRACKING_ID_REGEX.matches(idempotencyKey.trim())) {
      errors += "idempotencyKey must be 6-128 safe characters."
    }
    return errors
  }
}

/**
 * Safe, secret-free server response returned by the `createCampaignClick` Cloud Function.
 */
data class IssuedCampaignClickResult(
  val clickId: String = "",
  val campaignId: String = "",
  val networkId: String = "",
  val verificationMode: String = CampaignVerificationMode.AFFILIATE_POSTBACK.name,
  val trackingUrl: String = "",
  val expiresAtMillis: Long = 0L,
  val deduplicated: Boolean = false
) {
  fun resolvedVerificationMode(): CampaignVerificationMode? =
    CampaignVerificationMode.fromWireValue(verificationMode)
}


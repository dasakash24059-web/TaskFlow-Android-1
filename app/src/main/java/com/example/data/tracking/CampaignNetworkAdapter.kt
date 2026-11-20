package com.example.data.tracking

import com.example.data.model.CampaignConversionState
import com.example.data.model.CampaignNetworkConfig
import com.example.data.model.CampaignTrackingConfig
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkSignatureAlgorithm
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class OutboundTrackingContext(
  val clickId: String,
  val userId: String,
  val campaignId: String,
  val countryCode: String = "IN",
  val devicePlatform: String = "ANDROID",
  val extraParams: Map<String, String> = emptyMap()
)

data class OutboundTrackingSpec(
  val trackingUrl: String,
  val resolvedParameters: Map<String, String>,
  val networkId: String,
  val verificationMode: CampaignVerificationMode
)

data class RawCallbackInput(
  val queryParams: Map<String, String> = emptyMap(),
  val headers: Map<String, String> = emptyMap(),
  val bodyParams: Map<String, String> = emptyMap()
) {
  fun mergedValues(): Map<String, String> = buildMap {
    putAll(queryParams)
    putAll(bodyParams)
  }
}

data class NormalizedConversionEvent(
  val clickId: String,
  val externalTransactionId: String,
  val rawStatus: String,
  val normalizedState: CampaignConversionState,
  val payoutRaw: String,
  val signatureValue: String,
  val networkId: String,
  val verificationMode: CampaignVerificationMode
)

data class SignatureVerificationContract(
  val algorithm: NetworkSignatureAlgorithm,
  val requiredFieldName: String,
  val secretEnvKey: String,
  val requiresSecret: Boolean
)

/**
 * Extensible adapter contract for heterogeneous tracking networks and verification backends.
 * Adapters normalize outbound parameter mapping, inbound callback parsing, and signature contracts
 * without hardcoding a single affiliate network format and without assuming click == install.
 */
interface CampaignNetworkAdapter {
  val adapterId: String
  val supportedVerificationMode: CampaignVerificationMode
  val defaultCapabilities: NetworkCapabilitiesSpec

  fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec

  fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent

  fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract
}

/**
 * Parameter-driven Affiliate Postback adapter that works across arbitrary affiliate networks
 * using `network.parameterMapping` and `network.postbackStatusMapping` without hardcoded vendor logic.
 */
class ConfigurableAffiliatePostbackAdapter : CampaignNetworkAdapter {
  override val adapterId: String = "configurable_affiliate_postback"
  override val supportedVerificationMode: CampaignVerificationMode =
    CampaignVerificationMode.AFFILIATE_POSTBACK
  override val defaultCapabilities: NetworkCapabilitiesSpec =
    NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = false,
      supportsPostback = true,
      supportsInstallAttribution = true,
      supportsServerToServerPolling = false,
      requiresManualReview = false
    )

  override fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec {
    val mapping = network.parameterMapping
    val resolved = linkedMapOf<String, String>()
    resolved[mapping.clickIdParam] = context.clickId
    if (mapping.userIdParam.isNotBlank()) {
      resolved[mapping.userIdParam] = context.userId
    }
    if (mapping.campaignIdParam.isNotBlank()) {
      resolved[mapping.campaignIdParam] = context.campaignId
    }
    mapping.extraStaticParams.forEach { (k, v) -> if (k.isNotBlank()) resolved[k] = v }
    config.parameterOverrides.forEach { (k, v) -> if (k.isNotBlank()) resolved[k] = v }
    context.extraParams.forEach { (k, v) -> if (k.isNotBlank()) resolved[k] = v }

    val url = appendQueryParameters(config.trackingBaseUrl, resolved)
    return OutboundTrackingSpec(
      trackingUrl = url,
      resolvedParameters = resolved,
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )
  }

  override fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent {
    val merged = input.mergedValues()
    val mapping = network.parameterMapping
    val clickId = merged[mapping.clickIdParam].orEmpty().trim()
    val externalTxnId = merged[mapping.externalTxnParam].orEmpty().trim()
    val rawStatus = merged[mapping.statusParam].orEmpty().trim()
    val payoutRaw = merged[mapping.payoutParam].orEmpty().trim()
    val signature =
      merged[mapping.signatureParam]?.trim()
        ?: merged[network.signatureConfig.queryParamName]?.trim()
        ?: headerLookup(input.headers, network.signatureConfig.headerName)

    val normalizedState = resolveStateFromMapping(rawStatus, network.postbackStatusMapping)
    return NormalizedConversionEvent(
      clickId = clickId,
      externalTransactionId = externalTxnId,
      rawStatus = rawStatus,
      normalizedState = normalizedState,
      payoutRaw = payoutRaw,
      signatureValue = signature,
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )
  }

  override fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract {
    val alg =
      NetworkSignatureAlgorithm.fromWireValue(network.signatureConfig.algorithm)
        ?: NetworkSignatureAlgorithm.HMAC_SHA256
    val fieldName =
      network.signatureConfig.headerName.ifBlank {
        network.signatureConfig.queryParamName.ifBlank { network.parameterMapping.signatureParam }
      }
    return SignatureVerificationContract(
      algorithm = alg,
      requiredFieldName = fieldName,
      secretEnvKey = network.signatureConfig.secretEnvKey,
      requiresSecret = alg != NetworkSignatureAlgorithm.NONE
    )
  }
}

class AdvertiserS2SApiAdapter : CampaignNetworkAdapter {
  override val adapterId: String = "advertiser_s2s_api"
  override val supportedVerificationMode: CampaignVerificationMode =
    CampaignVerificationMode.ADVERTISER_S2S_API
  override val defaultCapabilities: NetworkCapabilitiesSpec =
    NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = true,
      supportsPostback = true,
      supportsInstallAttribution = true,
      supportsServerToServerPolling = true,
      requiresManualReview = false
    )

  private val delegate = ConfigurableAffiliatePostbackAdapter()

  override fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec =
    delegate.buildTrackingSpec(config, network, context).copy(
      verificationMode = supportedVerificationMode
    )

  override fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent =
    delegate.normalizeConversionCallback(input, network).copy(
      verificationMode = supportedVerificationMode
    )

  override fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract =
    delegate.describeSignatureContract(network)
}

class TelegramBotVerificationAdapter : CampaignNetworkAdapter {
  override val adapterId: String = "telegram_bot_api"
  override val supportedVerificationMode: CampaignVerificationMode =
    CampaignVerificationMode.TELEGRAM_BOT_API
  override val defaultCapabilities: NetworkCapabilitiesSpec =
    NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = true,
      supportsPostback = false,
      supportsInstallAttribution = false,
      supportsServerToServerPolling = true,
      requiresManualReview = false
    )

  override fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec =
    OutboundTrackingSpec(
      trackingUrl = config.trackingBaseUrl,
      resolvedParameters = mapOf("click_ref" to context.clickId),
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )

  override fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent {
    val merged = input.mergedValues()
    val rawStatus = merged[network.parameterMapping.statusParam].orEmpty().ifBlank { "pending" }
    val normalized = resolveStateFromMapping(rawStatus, network.postbackStatusMapping)
    return NormalizedConversionEvent(
      clickId = merged[network.parameterMapping.clickIdParam].orEmpty(),
      externalTransactionId = merged[network.parameterMapping.externalTxnParam].orEmpty(),
      rawStatus = rawStatus,
      normalizedState = normalized,
      payoutRaw = "",
      signatureValue = headerLookup(input.headers, network.signatureConfig.headerName),
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )
  }

  override fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract =
    SignatureVerificationContract(
      algorithm = NetworkSignatureAlgorithm.TELEGRAM_SECRET_HEADER,
      requiredFieldName = network.signatureConfig.headerName.ifBlank { "X-Telegram-Bot-Api-Secret-Token" },
      secretEnvKey = network.signatureConfig.secretEnvKey.ifBlank { "TELEGRAM_WEBHOOK_SECRET" },
      requiresSecret = true
    )
}

class ManualProofVerificationAdapter : CampaignNetworkAdapter {
  override val adapterId: String = "manual_proof"
  override val supportedVerificationMode: CampaignVerificationMode =
    CampaignVerificationMode.MANUAL_PROOF
  override val defaultCapabilities: NetworkCapabilitiesSpec =
    NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = true,
      supportsPostback = false,
      supportsInstallAttribution = false,
      supportsServerToServerPolling = false,
      requiresManualReview = true
    )

  override fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec =
    OutboundTrackingSpec(
      trackingUrl = config.trackingBaseUrl,
      resolvedParameters = emptyMap(),
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )

  override fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent {
    val merged = input.mergedValues()
    val rawStatus = merged["status"].orEmpty().ifBlank { "PENDING" }
    return NormalizedConversionEvent(
      clickId = merged["click_id"].orEmpty(),
      externalTransactionId = merged["submission_id"].orEmpty(),
      rawStatus = rawStatus,
      normalizedState = resolveStateFromMapping(rawStatus, network.postbackStatusMapping),
      payoutRaw = "",
      signatureValue = "",
      networkId = network.networkId,
      verificationMode = supportedVerificationMode
    )
  }

  override fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract =
    SignatureVerificationContract(
      algorithm = NetworkSignatureAlgorithm.NONE,
      requiredFieldName = "",
      secretEnvKey = "",
      requiresSecret = false
    )
}

class CustomApprovedVerificationAdapter : CampaignNetworkAdapter {
  override val adapterId: String = "custom_approved"
  override val supportedVerificationMode: CampaignVerificationMode =
    CampaignVerificationMode.CUSTOM_APPROVED
  override val defaultCapabilities: NetworkCapabilitiesSpec =
    NetworkCapabilitiesSpec(
      supportsIncentivizedTraffic = true,
      supportsPostback = true,
      supportsInstallAttribution = false,
      supportsServerToServerPolling = true,
      requiresManualReview = true
    )

  private val delegate = ConfigurableAffiliatePostbackAdapter()

  override fun buildTrackingSpec(
    config: CampaignTrackingConfig,
    network: CampaignNetworkConfig,
    context: OutboundTrackingContext
  ): OutboundTrackingSpec =
    delegate.buildTrackingSpec(config, network, context).copy(
      verificationMode = supportedVerificationMode
    )

  override fun normalizeConversionCallback(
    input: RawCallbackInput,
    network: CampaignNetworkConfig
  ): NormalizedConversionEvent =
    delegate.normalizeConversionCallback(input, network).copy(
      verificationMode = supportedVerificationMode
    )

  override fun describeSignatureContract(network: CampaignNetworkConfig): SignatureVerificationContract =
    delegate.describeSignatureContract(network)
}

/**
 * Registry mapping verification modes and optional network-specific adapter IDs
 * without coupling core tracking logic to any single affiliate network implementation.
 */
class CampaignNetworkAdapterRegistry(
  initialAdapters: List<CampaignNetworkAdapter> = defaultAdapters()
) {
  private val modeAdapters = mutableMapOf<CampaignVerificationMode, CampaignNetworkAdapter>()
  private val namedAdapters = mutableMapOf<String, CampaignNetworkAdapter>()

  init {
    initialAdapters.forEach { adapter ->
      modeAdapters[adapter.supportedVerificationMode] = adapter
      namedAdapters[adapter.adapterId.lowercase(Locale.US)] = adapter
    }
  }

  fun registerCustomAdapter(networkOrAdapterId: String, adapter: CampaignNetworkAdapter) {
    namedAdapters[networkOrAdapterId.trim().lowercase(Locale.US)] = adapter
  }

  fun resolve(
    mode: CampaignVerificationMode,
    networkOrAdapterId: String? = null
  ): CampaignNetworkAdapter {
    val specific = networkOrAdapterId?.trim()?.lowercase(Locale.US)
    if (!specific.isNullOrEmpty()) {
      namedAdapters[specific]?.let { return it }
    }
    return modeAdapters[mode]
      ?: throw IllegalStateException("No adapter registered for verification mode $mode")
  }

  companion object {
    fun defaultAdapters(): List<CampaignNetworkAdapter> = listOf(
      ConfigurableAffiliatePostbackAdapter(),
      AdvertiserS2SApiAdapter(),
      TelegramBotVerificationAdapter(),
      ManualProofVerificationAdapter(),
      CustomApprovedVerificationAdapter()
    )
  }
}

private fun resolveStateFromMapping(
  rawStatus: String,
  statusMapping: Map<String, String>
): CampaignConversionState {
  val cleaned = rawStatus.trim()
  if (cleaned.isEmpty()) return CampaignConversionState.PENDING
  val mapped =
    statusMapping.entries
      .firstOrNull { (k, _) -> k.equals(cleaned, ignoreCase = true) }
      ?.value
  if (mapped != null) {
    CampaignConversionState.fromWireValue(mapped)?.let { return it }
  }
  return when (cleaned.uppercase(Locale.US)) {
    "APPROVED", "CONFIRMED", "COMPLETED", "SUCCESS", "PAID", "1" -> CampaignConversionState.APPROVED
    "REJECTED", "DECLINED", "FAILED", "INVALID", "FRAUD", "0" -> CampaignConversionState.REJECTED
    "REVERSED", "CHARGEBACK", "CANCELLED", "REFUNDED", "-1" -> CampaignConversionState.REVERSED
    else -> CampaignConversionState.PENDING
  }
}

private fun headerLookup(headers: Map<String, String>, headerName: String): String {
  if (headerName.isBlank()) return ""
  return headers.entries
    .firstOrNull { (k, _) -> k.equals(headerName.trim(), ignoreCase = true) }
    ?.value
    ?.trim()
    .orEmpty()
}

private fun appendQueryParameters(baseUrl: String, params: Map<String, String>): String {
  if (baseUrl.isBlank() || params.isEmpty()) return baseUrl
  val uri = URI(baseUrl.trim())
  val existingParams = linkedMapOf<String, String>()
  val rawQuery = uri.rawQuery.orEmpty()
  if (rawQuery.isNotEmpty()) {
    rawQuery.split("&").forEach { pair ->
      val idx = pair.indexOf('=')
      if (idx > 0) {
        val key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8.name())
        val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8.name())
        existingParams[key] = value
      }
    }
  }
  existingParams.putAll(params)
  val encodedQuery = existingParams.entries.joinToString("&") { (k, v) ->
    "${URLEncoder.encode(k, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(v, StandardCharsets.UTF_8.name())}"
  }
  return URI(uri.scheme, uri.authority, uri.path, encodedQuery, uri.fragment).toString()
}

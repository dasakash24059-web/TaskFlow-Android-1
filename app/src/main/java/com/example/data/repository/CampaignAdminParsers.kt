package com.example.data.repository

import com.example.data.model.AdminCampaignConversionRequirements
import com.example.data.model.AdminCampaignDashboardResult
import com.example.data.model.AdminCampaignNetworkItem
import com.example.data.model.AdminCampaignTrackingConfigItem
import com.example.data.model.AdminNetworkSignatureConfig
import com.example.data.model.AdminPaginatedSlice
import com.example.data.model.CampaignAnalyticsDateRange
import com.example.data.model.CampaignAnalyticsScanLimits
import com.example.data.model.CampaignDevicePlatform
import com.example.data.model.CampaignPerformanceRow
import com.example.data.model.CampaignTrackingSummaryMetrics
import com.example.data.model.CampaignVerificationMode
import com.example.data.model.NetworkCapabilitiesSpec
import com.example.data.model.NetworkParameterMapping
import com.example.data.model.NetworkPerformanceRow
import com.example.data.model.NetworkSignatureAlgorithm
import com.example.data.model.REGISTERED_CAMPAIGN_SECRET_KEYS
import com.example.data.model.coinsToCents

internal object CampaignAdminParsers {

  private fun Map<*, *>.str(key: String, default: String = ""): String =
    (this[key] as? String)?.trim() ?: default

  private fun Map<*, *>.bool(key: String, default: Boolean = false): Boolean =
    this[key] as? Boolean ?: default

  private fun Map<*, *>.long(key: String, default: Long = 0L): Long =
    (this[key] as? Number)?.toLong() ?: default

  private fun Map<*, *>.int(key: String, default: Int = 0): Int =
    (this[key] as? Number)?.toInt() ?: default

  private fun Map<*, *>.doubleOrNull(key: String): Double? =
    (this[key] as? Number)?.toDouble()

  private fun Map<*, *>.strList(key: String): List<String> =
    (this[key] as? List<*>)?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
      ?: emptyList()

  private fun Map<*, *>.strMap(key: String): Map<String, String> =
    (this[key] as? Map<*, *>)?.entries
      ?.mapNotNull { (k, v) ->
        val ks = k?.toString()?.trim().orEmpty()
        val vs = v?.toString()?.trim().orEmpty()
        if (ks.isNotEmpty()) ks to vs else null
      }
      ?.toMap()
      ?: emptyMap()

  fun parseNetwork(map: Map<*, *>): AdminCampaignNetworkItem {
    val caps = map["capabilities"] as? Map<*, *> ?: emptyMap<String, Any>()
    val paramMap = map["parameterMapping"] as? Map<*, *> ?: emptyMap<String, Any>()
    val sig = map["signatureConfig"] as? Map<*, *> ?: emptyMap<String, Any>()
    val rawSecretKey = sig.str("secretEnvKey")
    val safeSecretKey =
      if (REGISTERED_CAMPAIGN_SECRET_KEYS.contains(rawSecretKey)) rawSecretKey else ""

    return AdminCampaignNetworkItem(
      networkId = map.str("networkId"),
      name = map.str("name"),
      adapterType = map.str("adapterType", CampaignVerificationMode.AFFILIATE_POSTBACK.name),
      isActive = map.bool("isActive", false),
      capabilities = NetworkCapabilitiesSpec(
        supportsIncentivizedTraffic = caps.bool("supportsIncentivizedTraffic"),
        supportsPostback = caps.bool("supportsPostback"),
        supportsInstallAttribution = caps.bool("supportsInstallAttribution"),
        supportsServerToServerPolling = caps.bool("supportsServerToServerPolling"),
        requiresManualReview = caps.bool("requiresManualReview")
      ),
      allowedDestinationHosts = map.strList("allowedDestinationHosts"),
      parameterMapping = NetworkParameterMapping(
        clickIdParam = paramMap.str("clickIdParam", "click_id"),
        userIdParam = paramMap.str("userIdParam"),
        campaignIdParam = paramMap.str("campaignIdParam"),
        externalTxnParam = paramMap.str("externalTxnParam", "txn_id"),
        statusParam = paramMap.str("statusParam", "status"),
        payoutParam = paramMap.str("payoutParam", "payout"),
        signatureParam = paramMap.str("signatureParam", "sig"),
        extraStaticParams = paramMap.strMap("extraStaticParams")
      ),
      postbackStatusMapping = map.strMap("postbackStatusMapping"),
      signatureConfig = AdminNetworkSignatureConfig(
        algorithm = sig.str("algorithm", NetworkSignatureAlgorithm.HMAC_SHA256.name),
        headerName = sig.str("headerName"),
        queryParamName = sig.str("queryParamName", "sig"),
        secretEnvKey = safeSecretKey,
        signingMode = sig.str("signingMode", "SORTED_PARAMS"),
        encoding = sig.str("encoding", "hex"),
        signedFields = sig.strList("signedFields"),
        fieldSeparator = (sig["fieldSeparator"] as? String) ?: "|"
      ),
      createdAtMillis = map.long("createdAtMillis"),
      updatedAtMillis = map.long("updatedAtMillis"),
      activeCampaignsCount = map.int("activeCampaignsCount", 0).coerceAtLeast(0)
    )
  }

  fun parseNetworkPage(dataMap: Map<*, *>): AdminPaginatedSlice<AdminCampaignNetworkItem> {
    val rawItems = dataMap["items"] as? List<*> ?: emptyList<Any>()
    val items = rawItems.mapNotNull { (it as? Map<*, *>)?.let(::parseNetwork) }
    return AdminPaginatedSlice(
      items = items,
      nextCursor = dataMap.str("nextCursor"),
      hasMore = dataMap.bool("hasMore"),
      totalCount = dataMap.int("totalCount", items.size),
      truncated = dataMap.bool("truncated")
    )
  }

  fun parseCampaignConfig(map: Map<*, *>): AdminCampaignTrackingConfigItem {
    val reqs = map["conversionRequirements"] as? Map<*, *> ?: emptyMap<String, Any>()
    val rewardTf = map.long("rewardTf")
    return AdminCampaignTrackingConfigItem(
      configId = map.str("configId", map.str("campaignId")),
      campaignId = map.str("campaignId", map.str("configId")),
      title = map.str("title", map.str("campaignId")),
      isActive = map.bool("isActive"),
      startDate = map.str("startDate"),
      endDate = map.str("endDate"),
      startAtMillis = map.long("startAtMillis"),
      expiresAtMillis = map.long("expiresAtMillis"),
      submissionBudget = map.long("submissionBudget"),
      usedSubmissions = map.long("usedSubmissions"),
      reservedClickSlots = map.long("reservedClickSlots"),
      issuedClicksCount = map.long("issuedClicksCount"),
      rewardBudgetTf = map.long("rewardBudgetTf"),
      spentBudgetTf = map.long("spentBudgetTf"),
      allowedCountries = map.strList("allowedCountries").ifEmpty { listOf("ALL") },
      allowedPlatforms = map.strList("allowedPlatforms").ifEmpty { listOf(CampaignDevicePlatform.ANDROID.name) },
      rewardTf = rewardTf,
      rewardCents = map.long("rewardCents", coinsToCents(rewardTf)),
      verificationMode = map.str("verificationMode", CampaignVerificationMode.MANUAL_PROOF.name),
      networkId = map.str("networkId"),
      trackingNetworkRef = map.str("trackingNetworkRef"),
      trackingBaseUrl = map.str("trackingBaseUrl"),
      allowedDestinationHosts = map.strList("allowedDestinationHosts"),
      parameterOverrides = map.strMap("parameterOverrides"),
      conversionRequirements = AdminCampaignConversionRequirements(
        requireUniqueClickId = reqs.bool("requireUniqueClickId", true),
        requireInstallAttribution = reqs.bool("requireInstallAttribution"),
        autoApproveVerifiedPostback = reqs.bool("autoApproveVerifiedPostback"),
        requireAdminApproval = reqs.bool("requireAdminApproval"),
        allowMultipleConversions = reqs.bool("allowMultipleConversions"),
        attributionWindowHours = reqs.int("attributionWindowHours", 72),
        requireVerifiedCountry = reqs.bool("requireVerifiedCountry"),
        requireAppCheck = reqs.bool("requireAppCheck")
      ),
      clickTtlMinutes = map.int("clickTtlMinutes", 60),
      createdAtMillis = map.long("createdAtMillis"),
      updatedAtMillis = map.long("updatedAtMillis")
    )
  }

  fun parseCampaignConfigPage(dataMap: Map<*, *>): AdminPaginatedSlice<AdminCampaignTrackingConfigItem> {
    val rawItems = dataMap["items"] as? List<*> ?: emptyList<Any>()
    val items = rawItems.mapNotNull { (it as? Map<*, *>)?.let(::parseCampaignConfig) }
    return AdminPaginatedSlice(
      items = items,
      nextCursor = dataMap.str("nextCursor"),
      hasMore = dataMap.bool("hasMore"),
      totalCount = dataMap.int("totalCount", items.size),
      truncated = dataMap.bool("truncated")
    )
  }

  fun parseCampaignPerformanceRow(map: Map<*, *>): CampaignPerformanceRow {
    val reversedTf = map.long("reversedTf")
    val recoveredTf = map.long("recoveredTf", reversedTf)
    val shortfallTf = map.long("shortfallTf", (reversedTf - recoveredTf).coerceAtLeast(0L))
    return CampaignPerformanceRow(
      campaignId = map.str("campaignId"),
      title = map.str("title", map.str("campaignId")),
      networkId = map.str("networkId"),
      verificationMode = map.str("verificationMode"),
      isActive = map.bool("isActive"),
      isOrphaned = map.bool("isOrphaned"),
      rewardTf = map.long("rewardTf"),
      clicksCount = map.long("clicksCount"),
      totalConversions = map.long("totalConversions"),
      pendingConversions = map.long("pendingConversions"),
      approvedConversions = map.long("approvedConversions"),
      rejectedConversions = map.long("rejectedConversions"),
      reversedConversions = map.long("reversedConversions"),
      approvalRatePercent = map.doubleOrNull("approvalRatePercent"),
      grossCreditedTf = map.long("grossCreditedTf"),
      reversedTf = reversedTf,
      recoveredTf = recoveredTf,
      shortfallTf = shortfallTf,
      netCreditedTf = map.long("netCreditedTf"),
      grossCreditedCents = map.long("grossCreditedCents"),
      reversedCents = map.long("reversedCents", coinsToCents(reversedTf)),
      recoveredCents = map.long("recoveredCents", coinsToCents(recoveredTf)),
      shortfallCents = map.long("shortfallCents", coinsToCents(shortfallTf)),
      netCreditedCents = map.long("netCreditedCents"),
      submissionBudget = map.long("submissionBudget"),
      usedSubmissions = map.long("usedSubmissions"),
      reservedClickSlots = map.long("reservedClickSlots"),
      rewardBudgetTf = map.long("rewardBudgetTf"),
      spentBudgetTf = map.long("spentBudgetTf"),
      budgetUtilizationPercent = map.doubleOrNull("budgetUtilizationPercent")
    )
  }

  fun parseNetworkPerformanceRow(map: Map<*, *>): NetworkPerformanceRow {
    val reversedTf = map.long("reversedTf")
    val recoveredTf = map.long("recoveredTf", reversedTf)
    val shortfallTf = map.long("shortfallTf", (reversedTf - recoveredTf).coerceAtLeast(0L))
    return NetworkPerformanceRow(
      networkId = map.str("networkId"),
      name = map.str("name", map.str("networkId")),
      adapterType = map.str("adapterType"),
      isActive = map.bool("isActive"),
      isOrphaned = map.bool("isOrphaned"),
      campaignsCount = map.int("campaignsCount"),
      clicksCount = map.long("clicksCount"),
      totalConversions = map.long("totalConversions"),
      pendingConversions = map.long("pendingConversions"),
      approvedConversions = map.long("approvedConversions"),
      rejectedConversions = map.long("rejectedConversions"),
      reversedConversions = map.long("reversedConversions"),
      approvalRatePercent = map.doubleOrNull("approvalRatePercent"),
      grossCreditedTf = map.long("grossCreditedTf"),
      reversedTf = reversedTf,
      recoveredTf = recoveredTf,
      shortfallTf = shortfallTf,
      netCreditedTf = map.long("netCreditedTf"),
      grossCreditedCents = map.long("grossCreditedCents"),
      reversedCents = map.long("reversedCents", coinsToCents(reversedTf)),
      recoveredCents = map.long("recoveredCents", coinsToCents(recoveredTf)),
      shortfallCents = map.long("shortfallCents", coinsToCents(shortfallTf)),
      netCreditedCents = map.long("netCreditedCents")
    )
  }

  fun parseScanLimits(map: Map<*, *>?): CampaignAnalyticsScanLimits {
    if (map == null) return CampaignAnalyticsScanLimits()
    val clicksTrunc = map.bool("clicksTruncated")
    val convTrunc = map.bool("conversionsTruncated")
    val campTrunc = map.bool("campaignsTruncated")
    val netTrunc = map.bool("networksTruncated")
    return CampaignAnalyticsScanLimits(
      clicksScanLimit = map.int("clicksScanLimit", 5000),
      conversionsScanLimit = map.int("conversionsScanLimit", 5000),
      campaignsScanLimit = map.int("campaignsScanLimit", 1000),
      networksScanLimit = map.int("networksScanLimit", 500),
      clicksScanned = map.int("clicksScanned"),
      conversionsScanned = map.int("conversionsScanned"),
      clicksTruncated = clicksTrunc,
      conversionsTruncated = convTrunc,
      campaignsTruncated = campTrunc,
      networksTruncated = netTrunc,
      isTruncated = map.bool("isTruncated", clicksTrunc || convTrunc || campTrunc || netTrunc),
      queryStrategy = map.str("queryStrategy", "INDEXED_TIMESTAMP_RANGE")
    )
  }

  fun parseDashboardResult(dataMap: Map<*, *>): AdminCampaignDashboardResult {
    val summaryMap = dataMap["summary"] as? Map<*, *> ?: emptyMap<String, Any>()
    val campPageMap = dataMap["campaignPerformance"] as? Map<*, *> ?: emptyMap<String, Any>()
    val netPageMap = dataMap["networkPerformance"] as? Map<*, *> ?: emptyMap<String, Any>()
    val scanLimitsMap = dataMap["scanLimits"] as? Map<*, *>

    val campItems =
      (campPageMap["items"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::parseCampaignPerformanceRow) }
        ?: emptyList()
    val netItems =
      (netPageMap["items"] as? List<*>)?.mapNotNull { (it as? Map<*, *>)?.let(::parseNetworkPerformanceRow) }
        ?: emptyList()

    val grossTf = summaryMap.long("grossCreditedTf")
    val reversedTf = summaryMap.long("reversedTf")
    val recoveredTf = summaryMap.long("recoveredTf", reversedTf)
    val shortfallTf = summaryMap.long("shortfallTf", (reversedTf - recoveredTf).coerceAtLeast(0L))
    val netTf = summaryMap.long("netCreditedTf", grossTf - reversedTf)
    val netWalletImpactTf = summaryMap.long("netWalletImpactTf", grossTf - recoveredTf)

    val summary = CampaignTrackingSummaryMetrics(
      totalCampaigns = summaryMap.int("totalCampaigns"),
      activeCampaigns = summaryMap.int("activeCampaigns"),
      totalNetworks = summaryMap.int("totalNetworks"),
      activeNetworks = summaryMap.int("activeNetworks"),
      totalClicks = summaryMap.long("totalClicks"),
      totalConversions = summaryMap.long("totalConversions"),
      pendingConversions = summaryMap.long("pendingConversions"),
      approvedConversions = summaryMap.long("approvedConversions"),
      rejectedConversions = summaryMap.long("rejectedConversions"),
      reversedConversions = summaryMap.long("reversedConversions"),
      approvalRatePercent = summaryMap.doubleOrNull("approvalRatePercent"),
      hasConversionData = summaryMap.bool("hasConversionData"),
      grossCreditedTf = grossTf,
      reversedTf = reversedTf,
      recoveredTf = recoveredTf,
      shortfallTf = shortfallTf,
      netCreditedTf = netTf,
      netWalletImpactTf = netWalletImpactTf,
      grossCreditedCents = summaryMap.long("grossCreditedCents"),
      reversedCents = summaryMap.long("reversedCents", coinsToCents(reversedTf)),
      recoveredCents = summaryMap.long("recoveredCents", coinsToCents(recoveredTf)),
      shortfallCents = summaryMap.long("shortfallCents", coinsToCents(shortfallTf)),
      netCreditedCents = summaryMap.long("netCreditedCents"),
      netWalletImpactCents = summaryMap.long("netWalletImpactCents"),
      orphanedClicksCount = summaryMap.long("orphanedClicksCount"),
      orphanedConversionsCount = summaryMap.long("orphanedConversionsCount"),
      orphanedGrossCreditedTf = summaryMap.long("orphanedGrossCreditedTf"),
      orphanedReversedTf = summaryMap.long("orphanedReversedTf"),
      orphanedCampaignCount = summaryMap.int("orphanedCampaignCount"),
      orphanedNetworkCount = summaryMap.int("orphanedNetworkCount")
    )

    return AdminCampaignDashboardResult(
      dateRange = dataMap.str("dateRange", CampaignAnalyticsDateRange.LAST_30_DAYS.wireValue),
      timezone = dataMap.str("timezone", "Asia/Kolkata"),
      startAtMillis = dataMap.long("startAtMillis"),
      endAtMillis = dataMap.long("endAtMillis"),
      summary = summary,
      scanLimits = parseScanLimits(scanLimitsMap),
      campaignPerformance = AdminPaginatedSlice(
        items = campItems,
        nextCursor = campPageMap.str("nextCursor"),
        hasMore = campPageMap.bool("hasMore"),
        totalCount = campPageMap.int("totalCount", campItems.size)
      ),
      networkPerformance = AdminPaginatedSlice(
        items = netItems,
        nextCursor = netPageMap.str("nextCursor"),
        hasMore = netPageMap.bool("hasMore"),
        totalCount = netPageMap.int("totalCount", netItems.size)
      )
    )
  }

  fun toUpsertNetworkPayload(item: AdminCampaignNetworkItem, mode: String): Map<String, Any> =
    mapOf(
      "mode" to mode,
      "networkId" to item.networkId.trim(),
      "name" to item.name.trim(),
      "adapterType" to item.adapterType.trim().uppercase(),
      "isActive" to item.isActive,
      "capabilities" to mapOf(
        "supportsIncentivizedTraffic" to item.capabilities.supportsIncentivizedTraffic,
        "supportsPostback" to item.capabilities.supportsPostback,
        "supportsInstallAttribution" to item.capabilities.supportsInstallAttribution,
        "supportsServerToServerPolling" to item.capabilities.supportsServerToServerPolling,
        "requiresManualReview" to item.capabilities.requiresManualReview
      ),
      "allowedDestinationHosts" to item.allowedDestinationHosts.map { it.trim() }.filter { it.isNotEmpty() },
      "parameterMapping" to mapOf(
        "clickIdParam" to item.parameterMapping.clickIdParam.trim(),
        "userIdParam" to item.parameterMapping.userIdParam.trim(),
        "campaignIdParam" to item.parameterMapping.campaignIdParam.trim(),
        "externalTxnParam" to item.parameterMapping.externalTxnParam.trim(),
        "statusParam" to item.parameterMapping.statusParam.trim(),
        "payoutParam" to item.parameterMapping.payoutParam.trim(),
        "signatureParam" to item.parameterMapping.signatureParam.trim(),
        "extraStaticParams" to item.parameterMapping.extraStaticParams
      ),
      "postbackStatusMapping" to item.postbackStatusMapping,
      "signatureConfig" to mapOf(
        "algorithm" to item.signatureConfig.algorithm.trim().uppercase(),
        "headerName" to item.signatureConfig.headerName.trim(),
        "queryParamName" to item.signatureConfig.queryParamName.trim(),
        "secretEnvKey" to item.signatureConfig.secretEnvKey.trim(),
        "signingMode" to item.signatureConfig.signingMode.trim().uppercase(),
        "encoding" to item.signatureConfig.encoding.trim().lowercase(),
        "signedFields" to item.signatureConfig.signedFields.map { it.trim() }.filter { it.isNotEmpty() },
        "fieldSeparator" to item.signatureConfig.fieldSeparator
      )
    )

  fun toUpsertCampaignConfigPayload(item: AdminCampaignTrackingConfigItem, mode: String): Map<String, Any> =
    mapOf(
      "mode" to mode,
      "campaignId" to item.campaignId.trim(),
      "title" to item.title.trim(),
      "isActive" to item.isActive,
      "startDate" to item.startDate.trim(),
      "endDate" to item.endDate.trim(),
      "startAtMillis" to item.startAtMillis,
      "expiresAtMillis" to item.expiresAtMillis,
      "submissionBudget" to item.submissionBudget,
      "rewardBudgetTf" to item.rewardBudgetTf,
      "allowedCountries" to item.allowedCountries.map { it.trim().uppercase() }.filter { it.isNotEmpty() },
      "allowedPlatforms" to item.allowedPlatforms.map { it.trim().uppercase() }.filter { it.isNotEmpty() },
      "rewardTf" to item.rewardTf,
      "verificationMode" to item.verificationMode.trim().uppercase(),
      "networkId" to item.networkId.trim(),
      "trackingBaseUrl" to item.trackingBaseUrl.trim(),
      "allowedDestinationHosts" to item.allowedDestinationHosts.map { it.trim() }.filter { it.isNotEmpty() },
      "parameterOverrides" to item.parameterOverrides,
      "conversionRequirements" to mapOf(
        "requireUniqueClickId" to item.conversionRequirements.requireUniqueClickId,
        "requireInstallAttribution" to item.conversionRequirements.requireInstallAttribution,
        "autoApproveVerifiedPostback" to item.conversionRequirements.autoApproveVerifiedPostback,
        "requireAdminApproval" to item.conversionRequirements.requireAdminApproval,
        "allowMultipleConversions" to item.conversionRequirements.allowMultipleConversions,
        "attributionWindowHours" to item.conversionRequirements.attributionWindowHours,
        "requireVerifiedCountry" to item.conversionRequirements.requireVerifiedCountry,
        "requireAppCheck" to item.conversionRequirements.requireAppCheck
      ),
      "clickTtlMinutes" to item.clickTtlMinutes
    )
}

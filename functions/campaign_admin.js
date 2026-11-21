/**
 * Universal Campaign Tracking Engine — Phase 3D Admin Configuration & Analytics Backend.
 *
 * All handlers require strict Firebase Authentication + `verifyAdminAccess`.
 * Protected Firestore collections (`campaign_networks`, `campaign_tracking_configs`,
 * `campaign_clicks`, `campaign_conversions`, `campaign_conversion_locks`, `campaign_click_guards`)
 * remain completely inaccessible to direct client reads/writes in `firestore.rules`.
 *
 * Never exposes secret values, secret hashes, or sensitive per-user attribution identifiers
 * (`clickId`, `externalTransactionId`) to Android clients or Admin UI responses.
 */

const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  ALLOWED_CAMPAIGN_SECRET_KEYS,
  isRegisteredCampaignSecretKey,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  isValidVerificationMode,
  isValidConversionState,
  isSecretKeyName,
  stripPrivateTrackingFields,
  tfToBalanceCents,
  signedTfToBalanceCents,
  toMillisValue,
  validateDestinationUrl,
  buildConversionModerationId,
  computeCampaignReversedWallet
} = require("./campaign_tracking");

const VALID_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;
const VALID_PARAM_KEY_REGEX = /^[a-zA-Z0-9_.-]{1,64}$/;
const VALID_HEADER_NAME_REGEX = /^[a-zA-Z0-9-]{1,64}$/;
const VALID_COUNTRY_REGEX = /^(ALL|[A-Z]{2})$/;
const VALID_DATE_YYYY_MM_DD_REGEX = /^\d{4}-\d{2}-\d{2}$/;
const ALLOWED_PLATFORMS = new Set(["ANDROID", "IOS", "WEB", "ALL"]);

const SUPPORTED_SIGNATURE_ALGORITHMS = Object.freeze([
  "HMAC_SHA256",
  "HMAC_MD5",
  "SHARED_TOKEN_HEADER",
  "SHARED_TOKEN_QUERY",
  "TELEGRAM_SECRET_HEADER",
  "NONE"
]);
const SUPPORTED_SIGNATURE_ALGORITHM_SET = new Set(SUPPORTED_SIGNATURE_ALGORITHMS);

const POSTBACK_REQUIRED_MODES = new Set([
  CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
  CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API
]);

const HMAC_ALGORITHMS = new Set(["HMAC_SHA256", "HMAC_MD5"]);

const SUPPORTED_HMAC_SIGNING_MODES = Object.freeze([
  "SORTED_PARAMS",
  "SORTED_PARAMS_COMMA",
  "RAW_BODY",
  "CANONICAL_FIELDS"
]);
const SUPPORTED_HMAC_SIGNING_MODE_SET = new Set(SUPPORTED_HMAC_SIGNING_MODES);

const SUPPORTED_HMAC_ENCODINGS = Object.freeze(["hex", "base64"]);
const SUPPORTED_HMAC_ENCODING_SET = new Set(SUPPORTED_HMAC_ENCODINGS);

const MAX_PAGE_SIZE = 50;
const DEFAULT_PAGE_SIZE = 20;
const MAX_ANALYTICS_SCAN_LIMIT = 5000;
const DEFAULT_SCAN_BATCH_SIZE = 500;
const MAX_NETWORKS_SCAN_LIMIT = 500;
const MAX_CAMPAIGNS_SCAN_LIMIT = 1000;
const MAX_CONVERSIONS_QUEUE_SCAN_LIMIT = 2000;
const IST_OFFSET_MS = 330 * 60 * 1000; // Asia/Kolkata (UTC+05:30, no DST)
const ANALYTICS_TIMEZONE = "Asia/Kolkata";

const ORPHANED_CAMPAIGN_SENTINEL = "__ORPHANED_CAMPAIGN__";
const ORPHANED_NETWORK_SENTINEL = "__ORPHANED_NETWORK__";

const MANAGED_NETWORK_TOP_LEVEL_KEYS = new Set([
  "networkId",
  "name",
  "adapterType",
  "adapterId",
  "isActive",
  "capabilities",
  "allowedDestinationHosts",
  "parameterMapping",
  "postbackStatusMapping",
  "signatureConfig",
  "createdBy",
  "updatedBy",
  "createdAt",
  "updatedAt"
]);

const MANAGED_CAMPAIGN_CONFIG_TOP_LEVEL_KEYS = new Set([
  "configId",
  "campaignId",
  "title",
  "isActive",
  "startDate",
  "endDate",
  "startAt",
  "expiresAt",
  "startAtMillis",
  "expiresAtMillis",
  "submissionBudget",
  "usedSubmissions",
  "reservedClickSlots",
  "issuedClicksCount",
  "rewardBudgetTf",
  "spentBudgetTf",
  "allowedCountries",
  "allowedPlatforms",
  "rewardTf",
  "rewardCents",
  "verificationMode",
  "networkId",
  "trackingNetworkRef",
  "trackingBaseUrl",
  "allowedDestinationHosts",
  "parameterOverrides",
  "conversionRequirements",
  "requireDeviceAttestation",
  "clickTtlMinutes",
  "clickCooldownMs",
  "maxClicksPerUserPerHour",
  "clickOverbookingBuffer",
  "createdBy",
  "updatedBy",
  "createdAt",
  "updatedAt"
]);

function extractPreservedSafeFields(existingData, managedKeys) {
  if (!existingData || typeof existingData !== "object") return {};
  const preserved = {};
  for (const [key, val] of Object.entries(existingData)) {
    if (managedKeys.has(key) || isSecretKeyName(key)) continue;
    preserved[key] = val;
  }
  return preserved;
}

function parseIsoDateToUtcMillis(dateStr, endOfDay = false) {
  const cleaned = String(dateStr || "").trim();
  if (!cleaned || !VALID_DATE_YYYY_MM_DD_REGEX.test(cleaned)) return 0;
  const [y, m, d] = cleaned.split("-").map(Number);
  const utcStart = Date.UTC(y, m - 1, d, 0, 0, 0, 0);
  return endOfDay ? utcStart + 86_399_999 : utcStart;
}

function assertNoRawSecretFields(obj, pathPrefix = "", HttpsErrorCtor = Error) {
  if (!obj || typeof obj !== "object") return;
  if (Array.isArray(obj)) {
    obj.forEach((item, idx) => assertNoRawSecretFields(item, `${pathPrefix}[${idx}]`, HttpsErrorCtor));
    return;
  }
  for (const [key, val] of Object.entries(obj)) {
    // Allow strictly the allowlisted reference key name `secretEnvKey` (never a raw value)
    if (key === "secretEnvKey") continue;
    if (isSecretKeyName(key)) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        `Raw secret or credential field '${pathPrefix ? `${pathPrefix}.${key}` : key}' is not permitted.`
      );
    }
    if (val && typeof val === "object") {
      assertNoRawSecretFields(val, pathPrefix ? `${pathPrefix}.${key}` : key, HttpsErrorCtor);
    }
  }
}

function clampPageSize(rawValue, defaultSize = DEFAULT_PAGE_SIZE) {
  const parsed = Number(rawValue);
  if (!Number.isFinite(parsed) || parsed <= 0) return defaultSize;
  return Math.min(MAX_PAGE_SIZE, Math.max(1, Math.floor(parsed)));
}

function paginateSortedArray(items, pageSize, cursor, getId) {
  const safeSize = clampPageSize(pageSize);
  const cleanCursor = String(cursor || "").trim();
  let startIndex = 0;
  if (cleanCursor) {
    const idx = items.findIndex((item) => getId(item) === cleanCursor);
    if (idx >= 0) {
      startIndex = idx + 1;
    }
  }
  const pageItems = items.slice(startIndex, startIndex + safeSize);
  const hasMore = startIndex + safeSize < items.length;
  const nextCursor = hasMore && pageItems.length > 0 ? getId(pageItems[pageItems.length - 1]) : "";
  return {
    items: pageItems,
    nextCursor,
    hasMore,
    totalCount: items.length
  };
}

function validateAllowedDestinationHosts(rawHosts, HttpsErrorCtor) {
  if (rawHosts === undefined || rawHosts === null) return [];
  if (!Array.isArray(rawHosts)) {
    throw new HttpsErrorCtor("invalid-argument", "allowedDestinationHosts must be an array of hostnames.");
  }
  if (rawHosts.length > 25) {
    throw new HttpsErrorCtor("invalid-argument", "allowedDestinationHosts cannot exceed 25 entries.");
  }
  const cleaned = [];
  const seen = new Set();
  for (const raw of rawHosts) {
    const host = String(raw || "").trim().toLowerCase().replace(/^\.+|\.+$/g, "");
    if (!host) continue;
    if (
      host.length > 253 ||
      !host.includes(".") ||
      /[\s/:@?#[\\\]{}]/.test(host) ||
      host === "localhost" ||
      host.endsWith(".localhost") ||
      host.endsWith(".local") ||
      host.endsWith(".internal") ||
      /^\d{1,3}(\.\d{1,3}){3}$/.test(host)
    ) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        `Invalid or unsafe destination host '${host}'. Provide a valid public domain.`
      );
    }
    if (!seen.has(host)) {
      seen.add(host);
      cleaned.push(host);
    }
  }
  return cleaned;
}

function validateStrictDateString(raw, fieldName, HttpsErrorCtor) {
  const cleaned = String(raw || "").trim();
  if (!cleaned) return "";
  if (!VALID_DATE_YYYY_MM_DD_REGEX.test(cleaned)) {
    throw new HttpsErrorCtor("invalid-argument", `${fieldName} must use YYYY-MM-DD format.`);
  }
  const [y, m, d] = cleaned.split("-").map((n) => Number(n));
  const checkDate = new Date(Date.UTC(y, m - 1, d));
  if (
    checkDate.getUTCFullYear() !== y ||
    checkDate.getUTCMonth() + 1 !== m ||
    checkDate.getUTCDate() !== d
  ) {
    throw new HttpsErrorCtor("invalid-argument", `${fieldName} is not a valid calendar date.`);
  }
  return cleaned;
}

function validateAndSanitizeNetworkInput(data, HttpsErrorCtor, existingNetwork = null) {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new HttpsErrorCtor("invalid-argument", "Network configuration payload is required.");
  }
  assertNoRawSecretFields(data, "", HttpsErrorCtor);

  const networkId = String(data.networkId || "").trim();
  if (!VALID_ID_REGEX.test(networkId)) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "networkId must be 1-128 alphanumeric, underscore, or hyphen characters."
    );
  }

  const name = String(data.name || "").trim();
  if (!name || name.length > 120) {
    throw new HttpsErrorCtor("invalid-argument", "Network name must be 1-120 characters.");
  }

  const adapterType = String(data.adapterType || "").trim().toUpperCase();
  if (!isValidVerificationMode(adapterType)) {
    throw new HttpsErrorCtor("invalid-argument", `Unsupported adapterType '${adapterType}'.`);
  }

  const isActive = data.isActive === undefined ? true : Boolean(data.isActive);

  const rawCaps = data.capabilities && typeof data.capabilities === "object" ? data.capabilities : {};
  const capabilities = {
    supportsIncentivizedTraffic: Boolean(rawCaps.supportsIncentivizedTraffic),
    supportsPostback: Boolean(rawCaps.supportsPostback),
    supportsInstallAttribution: Boolean(rawCaps.supportsInstallAttribution),
    supportsServerToServerPolling: Boolean(rawCaps.supportsServerToServerPolling),
    requiresManualReview: Boolean(rawCaps.requiresManualReview)
  };

  if (POSTBACK_REQUIRED_MODES.has(adapterType) && !capabilities.supportsPostback) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      `${adapterType} networks must enable supportsPostback.`
    );
  }

  const allowedDestinationHosts = validateAllowedDestinationHosts(
    data.allowedDestinationHosts,
    HttpsErrorCtor
  );

  // Parameter mapping validation
  const rawMap = data.parameterMapping && typeof data.parameterMapping === "object"
    ? data.parameterMapping
    : {};
  const clickIdParam = String(rawMap.clickIdParam || "click_id").trim();
  if (!VALID_PARAM_KEY_REGEX.test(clickIdParam) || isSecretKeyName(clickIdParam)) {
    throw new HttpsErrorCtor("invalid-argument", "clickIdParam must be a valid non-secret query parameter key.");
  }

  const sanitizeOptionalParam = (val, fallback = "") => {
    const cleaned = String(val ?? fallback).trim();
    if (!cleaned) return "";
    if (!VALID_PARAM_KEY_REGEX.test(cleaned) || isSecretKeyName(cleaned)) {
      throw new HttpsErrorCtor("invalid-argument", `Invalid or forbidden parameter key '${cleaned}'.`);
    }
    return cleaned;
  };

  const existingParamMap =
    existingNetwork?.parameterMapping && typeof existingNetwork.parameterMapping === "object"
      ? existingNetwork.parameterMapping
      : {};

  const extraStaticParams = {};
  const rawExtraStaticParams =
    rawMap.extraStaticParams !== undefined
      ? rawMap.extraStaticParams
      : existingParamMap.extraStaticParams;
  if (rawExtraStaticParams && typeof rawExtraStaticParams === "object" && !Array.isArray(rawExtraStaticParams)) {
    const entries = Object.entries(rawExtraStaticParams);
    if (entries.length > 20) {
      throw new HttpsErrorCtor("invalid-argument", "extraStaticParams cannot exceed 20 entries.");
    }
    for (const [k, v] of entries) {
      const key = String(k || "").trim();
      const val = String(v ?? "").trim();
      if (!VALID_PARAM_KEY_REGEX.test(key) || isSecretKeyName(key)) {
        throw new HttpsErrorCtor("invalid-argument", `Invalid extraStaticParams key '${key}'.`);
      }
      if (val.length > 256 || /[\r\n\x00]/.test(val)) {
        throw new HttpsErrorCtor("invalid-argument", `Invalid extraStaticParams value for '${key}'.`);
      }
      extraStaticParams[key] = val;
    }
  }

  const idempotencyParam =
    rawMap.idempotencyParam !== undefined
      ? sanitizeOptionalParam(rawMap.idempotencyParam, "")
      : sanitizeOptionalParam(existingParamMap.idempotencyParam, "");

  const parameterMapping = {
    clickIdParam,
    userIdParam: sanitizeOptionalParam(rawMap.userIdParam, ""),
    campaignIdParam: sanitizeOptionalParam(rawMap.campaignIdParam, ""),
    externalTxnParam: sanitizeOptionalParam(rawMap.externalTxnParam, "txn_id") || "txn_id",
    statusParam: sanitizeOptionalParam(rawMap.statusParam, "status") || "status",
    payoutParam: sanitizeOptionalParam(rawMap.payoutParam, "payout") || "payout",
    signatureParam: sanitizeOptionalParam(rawMap.signatureParam, "sig") || "sig",
    ...(idempotencyParam ? { idempotencyParam } : {}),
    extraStaticParams
  };

  // Postback status mapping validation (preserve existing mapping on update when omitted)
  const postbackStatusMapping = {};
  const rawPostbackStatusMapping =
    data.postbackStatusMapping !== undefined
      ? data.postbackStatusMapping
      : existingNetwork?.postbackStatusMapping;
  if (rawPostbackStatusMapping && typeof rawPostbackStatusMapping === "object" && !Array.isArray(rawPostbackStatusMapping)) {
    const entries = Object.entries(rawPostbackStatusMapping);
    if (entries.length > 30) {
      throw new HttpsErrorCtor("invalid-argument", "postbackStatusMapping cannot exceed 30 entries.");
    }
    for (const [rawStatus, mappedState] of entries) {
      const k = String(rawStatus || "").trim();
      const v = String(mappedState || "").trim().toUpperCase();
      if (!k || k.length > 64) {
        throw new HttpsErrorCtor("invalid-argument", "postbackStatusMapping keys must be 1-64 characters.");
      }
      if (!isValidConversionState(v)) {
        throw new HttpsErrorCtor(
          "invalid-argument",
          `postbackStatusMapping value '${v}' is not a valid CampaignConversionState.`
        );
      }
      postbackStatusMapping[k] = v;
    }
  }

  // Signature configuration validation (fail-closed)
  const rawSig = data.signatureConfig && typeof data.signatureConfig === "object"
    ? data.signatureConfig
    : {};
  const defaultAlg = POSTBACK_REQUIRED_MODES.has(adapterType)
    ? "HMAC_SHA256"
    : adapterType === CAMPAIGN_VERIFICATION_MODES.TELEGRAM_BOT_API
      ? "TELEGRAM_SECRET_HEADER"
      : "NONE";
  const algorithm = String(rawSig.algorithm || defaultAlg).trim().toUpperCase();

  if (!SUPPORTED_SIGNATURE_ALGORITHM_SET.has(algorithm)) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      `Unsupported signature algorithm '${algorithm}'. Supported: ${SUPPORTED_SIGNATURE_ALGORITHMS.join(", ")}.`
    );
  }

  if ((POSTBACK_REQUIRED_MODES.has(adapterType) || capabilities.supportsPostback) && algorithm === "NONE") {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "Postback-enabled networks cannot use signature algorithm NONE."
    );
  }

  if (
    adapterType === CAMPAIGN_VERIFICATION_MODES.TELEGRAM_BOT_API &&
    algorithm !== "TELEGRAM_SECRET_HEADER" &&
    algorithm !== "NONE"
  ) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "TELEGRAM_BOT_API networks must use TELEGRAM_SECRET_HEADER (or NONE when bot-polled)."
    );
  }

  const secretEnvKey = String(rawSig.secretEnvKey || "").trim();
  if (algorithm !== "NONE") {
    if (!isRegisteredCampaignSecretKey(secretEnvKey)) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        `secretEnvKey must be one of the registered Firebase Secret Manager keys: ${ALLOWED_CAMPAIGN_SECRET_KEYS.join(", ")}.`
      );
    }
  } else if (secretEnvKey && !isRegisteredCampaignSecretKey(secretEnvKey)) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      `secretEnvKey '${secretEnvKey}' is not in the registered allowlist.`
    );
  }

  const headerName = String(rawSig.headerName || "").trim();
  if (headerName && !VALID_HEADER_NAME_REGEX.test(headerName)) {
    throw new HttpsErrorCtor("invalid-argument", "signatureConfig.headerName contains invalid characters.");
  }

  const queryParamName = String(rawSig.queryParamName || parameterMapping.signatureParam || "sig").trim();
  if (
    (algorithm === "SHARED_TOKEN_QUERY" || HMAC_ALGORITHMS.has(algorithm)) &&
    queryParamName &&
    !VALID_PARAM_KEY_REGEX.test(queryParamName)
  ) {
    throw new HttpsErrorCtor("invalid-argument", "signatureConfig.queryParamName is invalid.");
  }

  let signingMode = "";
  let encoding = "hex";
  let signedFields = [];
  let fieldSeparator = "|";

  if (HMAC_ALGORITHMS.has(algorithm)) {
    signingMode = String(rawSig.signingMode || "SORTED_PARAMS").trim().toUpperCase();
    if (!SUPPORTED_HMAC_SIGNING_MODE_SET.has(signingMode)) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        `Unsupported HMAC signingMode '${signingMode}'. Supported: ${SUPPORTED_HMAC_SIGNING_MODES.join(", ")}.`
      );
    }
    encoding = String(rawSig.encoding || "hex").trim().toLowerCase();
    if (!SUPPORTED_HMAC_ENCODING_SET.has(encoding)) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        `Unsupported HMAC encoding '${encoding}'. Supported: hex, base64.`
      );
    }
    if (signingMode === "CANONICAL_FIELDS") {
      if (!Array.isArray(rawSig.signedFields) || rawSig.signedFields.length === 0 || rawSig.signedFields.length > 20) {
        throw new HttpsErrorCtor(
          "invalid-argument",
          "CANONICAL_FIELDS signingMode requires 1-20 valid signedFields."
        );
      }
      signedFields = rawSig.signedFields.map((f) => {
        const cleanField = String(f || "").trim();
        if (!VALID_PARAM_KEY_REGEX.test(cleanField) || isSecretKeyName(cleanField)) {
          throw new HttpsErrorCtor("invalid-argument", `Invalid signedFields entry '${cleanField}'.`);
        }
        return cleanField;
      });
      fieldSeparator = rawSig.fieldSeparator !== undefined ? String(rawSig.fieldSeparator) : "|";
      if (fieldSeparator.length > 4) {
        throw new HttpsErrorCtor("invalid-argument", "fieldSeparator cannot exceed 4 characters.");
      }
    }
  } else if (rawSig.signingMode && String(rawSig.signingMode).trim()) {
    const providedMode = String(rawSig.signingMode).trim().toUpperCase();
    if (!SUPPORTED_HMAC_SIGNING_MODE_SET.has(providedMode)) {
      throw new HttpsErrorCtor("invalid-argument", `Unsupported signingMode '${providedMode}'.`);
    }
  }

  const existingSig =
    existingNetwork?.signatureConfig && typeof existingNetwork.signatureConfig === "object"
      ? existingNetwork.signatureConfig
      : {};
  const rawSignaturePrefix =
    rawSig.signaturePrefix !== undefined
      ? String(rawSig.signaturePrefix || "").trim()
      : String(existingSig.signaturePrefix || "").trim();
  if (rawSignaturePrefix.length > 32 || /[\r\n\x00]/.test(rawSignaturePrefix)) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "signatureConfig.signaturePrefix must be at most 32 printable characters."
    );
  }

  let adapterId = "";
  if (data.adapterId !== undefined) {
    adapterId = String(data.adapterId || "").trim();
    if (adapterId && !VALID_ID_REGEX.test(adapterId)) {
      throw new HttpsErrorCtor(
        "invalid-argument",
        "adapterId must be 1-128 alphanumeric, underscore, or hyphen characters."
      );
    }
  } else if (existingNetwork?.adapterId) {
    const existingAdapterId = String(existingNetwork.adapterId || "").trim();
    if (VALID_ID_REGEX.test(existingAdapterId)) {
      adapterId = existingAdapterId;
    }
  }

  const signatureConfig = {
    algorithm,
    headerName:
      headerName ||
      (algorithm === "SHARED_TOKEN_HEADER"
        ? "x-postback-secret"
        : algorithm === "TELEGRAM_SECRET_HEADER"
          ? "x-telegram-bot-api-secret-token"
          : ""),
    queryParamName:
      algorithm === "SHARED_TOKEN_QUERY" || HMAC_ALGORITHMS.has(algorithm)
        ? queryParamName || "sig"
        : "",
    secretEnvKey: algorithm === "NONE" ? "" : secretEnvKey,
    ...(rawSignaturePrefix ? { signaturePrefix: rawSignaturePrefix } : {}),
    ...(HMAC_ALGORITHMS.has(algorithm)
      ? {
          signingMode,
          encoding,
          ...(signingMode === "CANONICAL_FIELDS" ? { signedFields, fieldSeparator } : {})
        }
      : {})
  };

  return {
    networkId,
    name,
    adapterType,
    ...(adapterId ? { adapterId } : {}),
    isActive,
    capabilities,
    allowedDestinationHosts,
    parameterMapping,
    postbackStatusMapping,
    signatureConfig
  };
}

function toSafeAdminNetworkDto(docData = {}, fallbackId = "") {
  const clean = stripPrivateTrackingFields(docData);
  const sig = docData.signatureConfig && typeof docData.signatureConfig === "object"
    ? docData.signatureConfig
    : {};
  const caps = docData.capabilities && typeof docData.capabilities === "object"
    ? docData.capabilities
    : {};
  const map = docData.parameterMapping && typeof docData.parameterMapping === "object"
    ? docData.parameterMapping
    : {};

  return {
    networkId: String(clean.networkId || fallbackId || "").trim(),
    name: String(clean.name || "").trim(),
    adapterType: String(clean.adapterType || CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK).trim(),
    ...(clean.adapterId ? { adapterId: String(clean.adapterId).trim() } : {}),
    isActive: clean.isActive === true,
    capabilities: {
      supportsIncentivizedTraffic: Boolean(caps.supportsIncentivizedTraffic),
      supportsPostback: Boolean(caps.supportsPostback),
      supportsInstallAttribution: Boolean(caps.supportsInstallAttribution),
      supportsServerToServerPolling: Boolean(caps.supportsServerToServerPolling),
      requiresManualReview: Boolean(caps.requiresManualReview)
    },
    allowedDestinationHosts: Array.isArray(clean.allowedDestinationHosts)
      ? clean.allowedDestinationHosts.map((h) => String(h || "").trim()).filter(Boolean)
      : [],
    parameterMapping: {
      clickIdParam: String(map.clickIdParam || "click_id"),
      userIdParam: String(map.userIdParam || ""),
      campaignIdParam: String(map.campaignIdParam || ""),
      externalTxnParam: String(map.externalTxnParam || "txn_id"),
      statusParam: String(map.statusParam || "status"),
      payoutParam: String(map.payoutParam || "payout"),
      signatureParam: String(map.signatureParam || "sig"),
      ...(map.idempotencyParam ? { idempotencyParam: String(map.idempotencyParam).trim() } : {}),
      extraStaticParams:
        map.extraStaticParams && typeof map.extraStaticParams === "object"
          ? stripPrivateTrackingFields(map.extraStaticParams)
          : {}
    },
    postbackStatusMapping:
      clean.postbackStatusMapping && typeof clean.postbackStatusMapping === "object"
        ? clean.postbackStatusMapping
        : {},
    signatureConfig: {
      algorithm: String(sig.algorithm || "HMAC_SHA256"),
      headerName: String(sig.headerName || ""),
      queryParamName: String(sig.queryParamName || "sig"),
      secretEnvKey: isRegisteredCampaignSecretKey(sig.secretEnvKey) ? String(sig.secretEnvKey) : "",
      ...(sig.signaturePrefix ? { signaturePrefix: String(sig.signaturePrefix).trim() } : {}),
      signingMode: String(sig.signingMode || "SORTED_PARAMS"),
      encoding: String(sig.encoding || "hex"),
      signedFields: Array.isArray(sig.signedFields) ? sig.signedFields.map(String) : [],
      fieldSeparator: sig.fieldSeparator !== undefined ? String(sig.fieldSeparator) : "|"
    },
    createdAtMillis: toMillisValue(docData.createdAt),
    updatedAtMillis: toMillisValue(docData.updatedAt)
  };
}

/**
 * Shared server-authoritative guard verifying that a campaign configuration and its
 * tracking network are mutually compatible and eligible for active traffic.
 * Used by campaign upsert, campaign reactivation, and network mutation safety checks.
 */
function validateCampaignActivationCompatibility({
  config,
  networkData,
  nowMillis = Date.now(),
  HttpsErrorCtor = Error,
  skipScheduleAndBudget = false
}) {
  const campaignId = String(config?.campaignId || config?.configId || "").trim() || "campaign";
  const networkId = String(config?.networkId || networkData?.networkId || "").trim();

  if (!networkData || networkData.isActive !== true) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Cannot activate campaign '${campaignId}' while network '${networkId}' is missing or disabled.`
    );
  }

  const verificationMode = String(config?.verificationMode || "").trim().toUpperCase();
  if (!isValidVerificationMode(verificationMode)) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' has an invalid verificationMode '${verificationMode}'.`
    );
  }

  const networkAdapterType = String(networkData.adapterType || "").trim().toUpperCase();
  if (networkAdapterType && networkAdapterType !== verificationMode) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' verificationMode '${verificationMode}' is incompatible with network '${networkId}' adapterType '${networkAdapterType}'.`
    );
  }

  const caps = networkData.capabilities && typeof networkData.capabilities === "object"
    ? networkData.capabilities
    : {};
  const sig = networkData.signatureConfig && typeof networkData.signatureConfig === "object"
    ? networkData.signatureConfig
    : {};
  const sigAlg = String(sig.algorithm || "NONE").trim().toUpperCase();

  if (caps.supportsIncentivizedTraffic !== true) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Network '${networkId}' does not explicitly permit incentivized traffic for campaign '${campaignId}'.`
    );
  }

  if (POSTBACK_REQUIRED_MODES.has(verificationMode)) {
    if (caps.supportsPostback !== true) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Network '${networkId}' does not support postback verification required by campaign '${campaignId}'.`
      );
    }
    if (
      !SUPPORTED_SIGNATURE_ALGORITHM_SET.has(sigAlg) ||
      sigAlg === "NONE" ||
      !isRegisteredCampaignSecretKey(sig.secretEnvKey)
    ) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Network '${networkId}' must configure a valid signature scheme and registered secret key for campaign '${campaignId}'.`
      );
    }
  } else if (
    verificationMode === CAMPAIGN_VERIFICATION_MODES.TELEGRAM_BOT_API &&
    sigAlg !== "NONE" &&
    (!SUPPORTED_SIGNATURE_ALGORITHM_SET.has(sigAlg) || !isRegisteredCampaignSecretKey(sig.secretEnvKey))
  ) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Network '${networkId}' has an invalid Telegram signature/secret configuration for campaign '${campaignId}'.`
    );
  }

  const trackingBaseUrl = String(config?.trackingBaseUrl || "").trim();
  const combinedAllowedHosts = [
    ...(Array.isArray(networkData.allowedDestinationHosts) ? networkData.allowedDestinationHosts : []),
    ...(Array.isArray(config?.allowedDestinationHosts) ? config.allowedDestinationHosts : [])
  ].filter(Boolean);

  if (POSTBACK_REQUIRED_MODES.has(verificationMode) && !trackingBaseUrl) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' requires a valid trackingBaseUrl for ${verificationMode}.`
    );
  }
  if (trackingBaseUrl) {
    const urlCheck = validateDestinationUrl(trackingBaseUrl, combinedAllowedHosts);
    if (!urlCheck.valid) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Campaign '${campaignId}' trackingBaseUrl failed validation against network '${networkId}': ${urlCheck.reason}`
      );
    }
  }

  if (skipScheduleAndBudget) {
    return;
  }

  const rewardTf = Math.floor(Number(config?.rewardTf || 0));
  if (!Number.isInteger(rewardTf) || rewardTf <= 0) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' has an invalid TF reward.`
    );
  }

  const startDate = String(config?.startDate || "").trim();
  const endDate = String(config?.endDate || "").trim();
  if (startDate && endDate && endDate < startDate) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' endDate (${endDate}) cannot be earlier than startDate (${startDate}).`
    );
  }

  const startAtMillis = Math.max(
    Number(config?.startAtMillis || 0),
    toMillisValue(config?.startAt),
    startDate ? parseIsoDateToUtcMillis(startDate, false) : 0
  );
  const expiresAtMillis = Math.max(
    Number(config?.expiresAtMillis || 0),
    toMillisValue(config?.expiresAt),
    endDate ? parseIsoDateToUtcMillis(endDate, true) : 0
  );
  if (startAtMillis > 0 && expiresAtMillis > 0 && expiresAtMillis < startAtMillis) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Campaign '${campaignId}' expiration time cannot precede start time.`
    );
  }
  if (expiresAtMillis > 0 && nowMillis > expiresAtMillis) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Cannot activate expired campaign '${campaignId}'. Extend its expiration date first.`
    );
  }

  const submissionBudget = Math.max(0, Math.floor(Number(config?.submissionBudget || 0)));
  const usedSubmissions = Math.max(0, Math.floor(Number(config?.usedSubmissions || 0)));
  if (submissionBudget > 0 && usedSubmissions >= submissionBudget) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Cannot activate campaign '${campaignId}' because its submission budget (${usedSubmissions}/${submissionBudget}) is exhausted.`
    );
  }

  const rewardBudgetTf = Math.max(0, Math.floor(Number(config?.rewardBudgetTf || 0)));
  const spentBudgetTf = Math.max(0, Math.floor(Number(config?.spentBudgetTf || 0)));
  if (rewardBudgetTf > 0 && spentBudgetTf + rewardTf > rewardBudgetTf) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Cannot activate campaign '${campaignId}' because its TF reward budget (${spentBudgetTf}/${rewardBudgetTf} TF) cannot fund another ${rewardTf} TF payout.`
    );
  }
}

function validateAndSanitizeCampaignConfigInput(
  data,
  networkData,
  existingConfig,
  HttpsErrorCtor,
  nowMillis = Date.now()
) {
  if (!data || typeof data !== "object" || Array.isArray(data)) {
    throw new HttpsErrorCtor("invalid-argument", "Campaign tracking configuration payload is required.");
  }
  assertNoRawSecretFields(data, "", HttpsErrorCtor);

  const campaignId = String(data.campaignId || data.configId || "").trim();
  if (!VALID_ID_REGEX.test(campaignId)) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "campaignId must be 1-128 alphanumeric, underscore, or hyphen characters."
    );
  }

  const title = String(data.title || existingConfig?.title || "").trim();
  if (!title || title.length > 120) {
    throw new HttpsErrorCtor("invalid-argument", "Campaign title must be 1-120 characters.");
  }

  const networkId = String(data.networkId || "").trim();
  if (!VALID_ID_REGEX.test(networkId) || !networkData) {
    throw new HttpsErrorCtor("invalid-argument", "A valid registered campaign network is required.");
  }

  const verificationMode = String(data.verificationMode || networkData.adapterType || "").trim().toUpperCase();
  if (!isValidVerificationMode(verificationMode)) {
    throw new HttpsErrorCtor("invalid-argument", `Unsupported verificationMode '${verificationMode}'.`);
  }

  // Enforce strict adapter-mode compatibility with the chosen network
  if (networkData.adapterType && networkData.adapterType !== verificationMode) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Selected network '${networkId}' uses adapter '${networkData.adapterType}' and does not match verificationMode '${verificationMode}'.`
    );
  }

  const isActive = data.isActive === undefined ? true : Boolean(data.isActive);
  if (isActive && networkData.isActive !== true) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `Cannot activate campaign while network '${networkId}' is disabled.`
    );
  }

  if (POSTBACK_REQUIRED_MODES.has(verificationMode)) {
    const caps = networkData.capabilities || {};
    const sig = networkData.signatureConfig || {};
    if (caps.supportsPostback !== true) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Network '${networkId}' does not support postback verification.`
      );
    }
    if (isActive && caps.supportsIncentivizedTraffic !== true) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Network '${networkId}' does not explicitly permit incentivized traffic.`
      );
    }
    if (String(sig.algorithm || "NONE").toUpperCase() === "NONE" || !isRegisteredCampaignSecretKey(sig.secretEnvKey)) {
      throw new HttpsErrorCtor(
        "failed-precondition",
        `Network '${networkId}' must have a valid signature algorithm and registered secret key.`
      );
    }
  }

  const rewardTf = Number(data.rewardTf);
  if (!Number.isInteger(rewardTf) || rewardTf <= 0 || rewardTf > 1_000_000) {
    throw new HttpsErrorCtor("invalid-argument", "rewardTf must be an integer between 1 and 1,000,000 TF.");
  }

  const submissionBudget = Number(data.submissionBudget ?? 0);
  if (!Number.isInteger(submissionBudget) || submissionBudget < 0 || submissionBudget > 10_000_000) {
    throw new HttpsErrorCtor("invalid-argument", "submissionBudget must be an integer between 0 and 10,000,000.");
  }

  const existingUsedSubmissions = Math.max(0, Math.floor(Number(existingConfig?.usedSubmissions || 0)));
  if (submissionBudget > 0 && submissionBudget < existingUsedSubmissions) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `submissionBudget (${submissionBudget}) cannot be less than already usedSubmissions (${existingUsedSubmissions}).`
    );
  }

  const rewardBudgetTf = Number(data.rewardBudgetTf ?? 0);
  if (!Number.isInteger(rewardBudgetTf) || rewardBudgetTf < 0 || rewardBudgetTf > 1_000_000_000) {
    throw new HttpsErrorCtor("invalid-argument", "rewardBudgetTf must be an integer between 0 and 1,000,000,000 TF.");
  }
  if (rewardBudgetTf > 0 && rewardBudgetTf < rewardTf) {
    throw new HttpsErrorCtor("invalid-argument", "rewardBudgetTf cannot be smaller than a single rewardTf payout.");
  }
  const existingSpentBudgetTf = Math.max(0, Math.floor(Number(existingConfig?.spentBudgetTf || 0)));
  if (rewardBudgetTf > 0 && rewardBudgetTf < existingSpentBudgetTf) {
    throw new HttpsErrorCtor(
      "failed-precondition",
      `rewardBudgetTf (${rewardBudgetTf}) cannot be less than already spentBudgetTf (${existingSpentBudgetTf}).`
    );
  }

  const startDate = validateStrictDateString(data.startDate, "startDate", HttpsErrorCtor);
  const endDate = validateStrictDateString(data.endDate, "endDate", HttpsErrorCtor);
  if (startDate && endDate && endDate < startDate) {
    throw new HttpsErrorCtor("invalid-argument", "endDate must be on or after startDate.");
  }

  let startAtMillis = Number(data.startAtMillis ?? 0);
  let expiresAtMillis = Number(data.expiresAtMillis ?? 0);
  if (!Number.isFinite(startAtMillis) || startAtMillis < 0 || !Number.isFinite(expiresAtMillis) || expiresAtMillis < 0) {
    throw new HttpsErrorCtor("invalid-argument", "startAtMillis and expiresAtMillis must be non-negative numbers.");
  }
  // Keep date strings and UTC millisecond timestamps consistent when millis are omitted/zero
  if (startDate && startAtMillis === 0) {
    startAtMillis = parseIsoDateToUtcMillis(startDate, false);
  }
  if (endDate && expiresAtMillis === 0) {
    expiresAtMillis = parseIsoDateToUtcMillis(endDate, true);
  }
  if (startAtMillis > 0 && expiresAtMillis > 0 && expiresAtMillis < startAtMillis) {
    throw new HttpsErrorCtor("invalid-argument", "Campaign expiration time must be >= start time.");
  }

  const rawCountries = Array.isArray(data.allowedCountries) ? data.allowedCountries : ["ALL"];
  const allowedCountries = Array.from(
    new Set(rawCountries.map((c) => String(c || "").trim().toUpperCase()).filter(Boolean))
  );
  if (
    allowedCountries.length === 0 ||
    allowedCountries.length > 50 ||
    allowedCountries.some((c) => !VALID_COUNTRY_REGEX.test(c))
  ) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      "allowedCountries must contain 1-50 valid ISO-3166 alpha-2 country codes or 'ALL'."
    );
  }

  const rawPlatforms = Array.isArray(data.allowedPlatforms) ? data.allowedPlatforms : ["ANDROID"];
  const allowedPlatforms = Array.from(
    new Set(rawPlatforms.map((p) => String(p || "").trim().toUpperCase()).filter(Boolean))
  );
  if (
    allowedPlatforms.length === 0 ||
    allowedPlatforms.length > 10 ||
    allowedPlatforms.some((p) => !ALLOWED_PLATFORMS.has(p))
  ) {
    throw new HttpsErrorCtor("invalid-argument", "allowedPlatforms must contain 1-10 valid platforms (ANDROID, IOS, WEB, ALL).");
  }

  const allowedDestinationHosts = validateAllowedDestinationHosts(
    data.allowedDestinationHosts,
    HttpsErrorCtor
  );

  const trackingBaseUrl = String(data.trackingBaseUrl || "").trim();
  const combinedAllowedHosts = [
    ...(Array.isArray(networkData.allowedDestinationHosts) ? networkData.allowedDestinationHosts : []),
    ...allowedDestinationHosts
  ].filter(Boolean);

  if (POSTBACK_REQUIRED_MODES.has(verificationMode) && !trackingBaseUrl) {
    throw new HttpsErrorCtor(
      "invalid-argument",
      `trackingBaseUrl is required for ${verificationMode} campaigns.`
    );
  }
  if (trackingBaseUrl) {
    const urlCheck = validateDestinationUrl(trackingBaseUrl, combinedAllowedHosts);
    if (!urlCheck.valid) {
      throw new HttpsErrorCtor("invalid-argument", urlCheck.reason);
    }
  }

  const parameterOverrides = {};
  if (data.parameterOverrides && typeof data.parameterOverrides === "object" && !Array.isArray(data.parameterOverrides)) {
    const entries = Object.entries(data.parameterOverrides);
    if (entries.length > 20) {
      throw new HttpsErrorCtor("invalid-argument", "parameterOverrides cannot exceed 20 entries.");
    }
    for (const [k, v] of entries) {
      const key = String(k || "").trim();
      const val = String(v ?? "").trim();
      if (!VALID_PARAM_KEY_REGEX.test(key) || isSecretKeyName(key)) {
        throw new HttpsErrorCtor("invalid-argument", `Invalid parameterOverrides key '${key}'.`);
      }
      if (val.length > 256 || /[\r\n\x00]/.test(val)) {
        throw new HttpsErrorCtor("invalid-argument", `Invalid parameterOverrides value for '${key}'.`);
      }
      parameterOverrides[key] = val;
    }
  }

  const existingReqs =
    existingConfig?.conversionRequirements && typeof existingConfig.conversionRequirements === "object"
      ? existingConfig.conversionRequirements
      : {};
  const rawReq = data.conversionRequirements && typeof data.conversionRequirements === "object"
    ? data.conversionRequirements
    : {};
  const hasRawReq = data.conversionRequirements && typeof data.conversionRequirements === "object";

  const attributionWindowHours = Number(
    rawReq.attributionWindowHours ?? existingReqs.attributionWindowHours ?? 72
  );
  if (!Number.isInteger(attributionWindowHours) || attributionWindowHours < 1 || attributionWindowHours > 2160) {
    throw new HttpsErrorCtor("invalid-argument", "attributionWindowHours must be an integer between 1 and 2160.");
  }

  const requireUniqueClickId =
    verificationMode === CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK
      ? true
      : rawReq.requireUniqueClickId !== undefined
        ? Boolean(rawReq.requireUniqueClickId)
        : existingReqs.requireUniqueClickId !== undefined
          ? Boolean(existingReqs.requireUniqueClickId)
          : true;

  const requireAdminApproval =
    verificationMode === CAMPAIGN_VERIFICATION_MODES.MANUAL_PROOF
      ? true
      : rawReq.requireAdminApproval !== undefined
        ? Boolean(rawReq.requireAdminApproval)
        : Boolean(existingReqs.requireAdminApproval);

  const autoApproveVerifiedPostback = requireAdminApproval
    ? false
    : rawReq.autoApproveVerifiedPostback !== undefined
      ? Boolean(rawReq.autoApproveVerifiedPostback)
      : existingReqs.autoApproveVerifiedPostback !== undefined
        ? Boolean(existingReqs.autoApproveVerifiedPostback)
        : true;

  const requireInstallAttribution =
    rawReq.requireInstallAttribution !== undefined
      ? Boolean(rawReq.requireInstallAttribution)
      : Boolean(existingReqs.requireInstallAttribution);

  const allowMultipleConversions =
    rawReq.allowMultipleConversions !== undefined
      ? Boolean(rawReq.allowMultipleConversions)
      : Boolean(existingReqs.allowMultipleConversions);

  const requireVerifiedCountry =
    rawReq.requireVerifiedCountry !== undefined
      ? Boolean(rawReq.requireVerifiedCountry)
      : Boolean(existingReqs.requireVerifiedCountry);

  // Canonical App Check flag synced to Phase 3B runtime `requireDeviceAttestation`
  const requireAppCheck =
    rawReq.requireAppCheck !== undefined
      ? Boolean(rawReq.requireAppCheck)
      : data.requireDeviceAttestation !== undefined
        ? Boolean(data.requireDeviceAttestation)
        : Boolean(existingReqs.requireAppCheck || existingConfig?.requireDeviceAttestation);

  const conversionRequirements = {
    requireUniqueClickId,
    requireInstallAttribution,
    autoApproveVerifiedPostback,
    requireAdminApproval,
    allowMultipleConversions,
    attributionWindowHours,
    requireVerifiedCountry,
    requireAppCheck
  };

  const clickTtlMinutes = Number(
    data.clickTtlMinutes !== undefined
      ? data.clickTtlMinutes
      : existingConfig?.clickTtlMinutes !== undefined
        ? existingConfig.clickTtlMinutes
        : 60
  );
  if (!Number.isInteger(clickTtlMinutes) || clickTtlMinutes < 1 || clickTtlMinutes > 43200) {
    throw new HttpsErrorCtor("invalid-argument", "clickTtlMinutes must be an integer between 1 and 43200.");
  }

  // Preserve or validate Phase 3B runtime-critical click throttling & overbooking fields
  const runtimeExtras = {};
  const resolveOptionalNonNegInt = (fieldName, maxVal) => {
    if (data[fieldName] !== undefined) {
      const val = Number(data[fieldName]);
      if (!Number.isInteger(val) || val < 0 || val > maxVal) {
        throw new HttpsErrorCtor(
          "invalid-argument",
          `${fieldName} must be an integer between 0 and ${maxVal}.`
        );
      }
      runtimeExtras[fieldName] = val;
    } else if (
      existingConfig &&
      existingConfig[fieldName] !== undefined &&
      Number.isFinite(Number(existingConfig[fieldName])) &&
      Number(existingConfig[fieldName]) >= 0
    ) {
      runtimeExtras[fieldName] = Math.floor(Number(existingConfig[fieldName]));
    }
  };
  const resolveOptionalPosInt = (fieldName, maxVal) => {
    if (data[fieldName] !== undefined) {
      const val = Number(data[fieldName]);
      if (!Number.isInteger(val) || val < 1 || val > maxVal) {
        throw new HttpsErrorCtor(
          "invalid-argument",
          `${fieldName} must be an integer between 1 and ${maxVal}.`
        );
      }
      runtimeExtras[fieldName] = val;
    } else if (
      existingConfig &&
      existingConfig[fieldName] !== undefined &&
      Number.isFinite(Number(existingConfig[fieldName])) &&
      Number(existingConfig[fieldName]) >= 1
    ) {
      runtimeExtras[fieldName] = Math.floor(Number(existingConfig[fieldName]));
    }
  };

  resolveOptionalNonNegInt("clickCooldownMs", 86_400_000);
  resolveOptionalPosInt("maxClicksPerUserPerHour", 10_000);
  resolveOptionalNonNegInt("clickOverbookingBuffer", 10_000);

  const sanitizedConfig = {
    configId: campaignId,
    campaignId,
    title,
    isActive,
    startDate,
    endDate,
    startAtMillis: Math.floor(startAtMillis),
    expiresAtMillis: Math.floor(expiresAtMillis),
    submissionBudget,
    rewardBudgetTf,
    allowedCountries,
    allowedPlatforms,
    rewardTf,
    rewardCents: tfToBalanceCents(rewardTf),
    verificationMode,
    networkId,
    trackingNetworkRef: `${CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS}/${networkId}`,
    trackingBaseUrl,
    allowedDestinationHosts,
    parameterOverrides,
    conversionRequirements,
    requireDeviceAttestation: requireAppCheck,
    clickTtlMinutes,
    ...runtimeExtras
  };

  if (isActive) {
    validateCampaignActivationCompatibility({
      config: {
        ...sanitizedConfig,
        usedSubmissions: existingUsedSubmissions,
        spentBudgetTf: existingSpentBudgetTf
      },
      networkData,
      nowMillis,
      HttpsErrorCtor
    });
  }

  return sanitizedConfig;
}

function toSafeAdminCampaignConfigDto(docData = {}, fallbackId = "") {
  const clean = stripPrivateTrackingFields(docData);
  const reqs = docData.conversionRequirements && typeof docData.conversionRequirements === "object"
    ? docData.conversionRequirements
    : {};
  const rewardTf = Math.max(0, Math.floor(Number(clean.rewardTf || 0)));
  const requireAppCheck = Boolean(reqs.requireAppCheck || clean.requireDeviceAttestation);

  return {
    configId: String(clean.configId || fallbackId || "").trim(),
    campaignId: String(clean.campaignId || fallbackId || "").trim(),
    title: String(clean.title || clean.campaignId || fallbackId || "").trim(),
    isActive: clean.isActive === true,
    startDate: String(clean.startDate || ""),
    endDate: String(clean.endDate || ""),
    startAtMillis: Math.max(Number(clean.startAtMillis || 0), toMillisValue(docData.startAt)),
    expiresAtMillis: Math.max(Number(clean.expiresAtMillis || 0), toMillisValue(docData.expiresAt)),
    submissionBudget: Math.max(0, Math.floor(Number(clean.submissionBudget || 0))),
    usedSubmissions: Math.max(0, Math.floor(Number(clean.usedSubmissions || 0))),
    reservedClickSlots: Math.max(0, Math.floor(Number(clean.reservedClickSlots || 0))),
    issuedClicksCount: Math.max(0, Math.floor(Number(clean.issuedClicksCount || 0))),
    rewardBudgetTf: Math.max(0, Math.floor(Number(clean.rewardBudgetTf || 0))),
    spentBudgetTf: Math.max(0, Math.floor(Number(clean.spentBudgetTf || 0))),
    allowedCountries: Array.isArray(clean.allowedCountries) ? clean.allowedCountries.map(String) : ["ALL"],
    allowedPlatforms: Array.isArray(clean.allowedPlatforms) ? clean.allowedPlatforms.map(String) : ["ANDROID"],
    rewardTf,
    rewardCents: tfToBalanceCents(rewardTf),
    verificationMode: String(clean.verificationMode || CAMPAIGN_VERIFICATION_MODES.MANUAL_PROOF),
    networkId: String(clean.networkId || ""),
    trackingNetworkRef: String(clean.trackingNetworkRef || ""),
    trackingBaseUrl: String(clean.trackingBaseUrl || ""),
    allowedDestinationHosts: Array.isArray(clean.allowedDestinationHosts)
      ? clean.allowedDestinationHosts.map(String)
      : [],
    parameterOverrides:
      clean.parameterOverrides && typeof clean.parameterOverrides === "object"
        ? stripPrivateTrackingFields(clean.parameterOverrides)
        : {},
    conversionRequirements: {
      requireUniqueClickId: reqs.requireUniqueClickId !== false,
      requireInstallAttribution: Boolean(reqs.requireInstallAttribution),
      autoApproveVerifiedPostback:
        reqs.autoApproveVerifiedPostback !== undefined
          ? Boolean(reqs.autoApproveVerifiedPostback)
          : !Boolean(reqs.requireAdminApproval),
      requireAdminApproval: Boolean(reqs.requireAdminApproval),
      allowMultipleConversions: Boolean(reqs.allowMultipleConversions),
      attributionWindowHours: Math.max(1, Math.floor(Number(reqs.attributionWindowHours || 72))),
      requireVerifiedCountry: Boolean(reqs.requireVerifiedCountry),
      requireAppCheck
    },
    requireDeviceAttestation: requireAppCheck,
    clickTtlMinutes: Math.max(1, Math.floor(Number(clean.clickTtlMinutes || 60))),
    createdAtMillis: toMillisValue(docData.createdAt),
    updatedAtMillis: toMillisValue(docData.updatedAt)
  };
}

function resolveAnalyticsDateWindow(data = {}, nowMillis = Date.now(), HttpsErrorCtor = Error) {
  const dateRange = String(data.dateRange || "30D").trim().toUpperCase();
  const dayMs = 24 * 60 * 60 * 1000;

  if (dateRange === "ALL") {
    return { dateRange: "ALL", startAtMillis: 0, endAtMillis: nowMillis, timezone: ANALYTICS_TIMEZONE };
  }
  if (dateRange === "TODAY") {
    // Align daily reporting window to Asia/Kolkata (IST, UTC+05:30), consistent with TaskFlow admin dates.
    const istShifted = new Date(nowMillis + IST_OFFSET_MS);
    istShifted.setUTCHours(0, 0, 0, 0);
    const startOfDayIstMillis = istShifted.getTime() - IST_OFFSET_MS;
    return {
      dateRange: "TODAY",
      startAtMillis: startOfDayIstMillis,
      endAtMillis: nowMillis,
      timezone: ANALYTICS_TIMEZONE
    };
  }
  if (dateRange === "7D") {
    return { dateRange: "7D", startAtMillis: nowMillis - 7 * dayMs, endAtMillis: nowMillis, timezone: ANALYTICS_TIMEZONE };
  }
  if (dateRange === "30D") {
    return { dateRange: "30D", startAtMillis: nowMillis - 30 * dayMs, endAtMillis: nowMillis, timezone: ANALYTICS_TIMEZONE };
  }
  if (dateRange === "CUSTOM") {
    const startAtMillis = Number(data.startAtMillis || 0);
    const endAtMillis = Number(data.endAtMillis || nowMillis);
    if (!Number.isFinite(startAtMillis) || !Number.isFinite(endAtMillis) || startAtMillis < 0 || endAtMillis < startAtMillis) {
      throw new HttpsErrorCtor("invalid-argument", "Invalid CUSTOM date range window.");
    }
    return {
      dateRange: "CUSTOM",
      startAtMillis: Math.floor(startAtMillis),
      endAtMillis: Math.floor(endAtMillis),
      timezone: ANALYTICS_TIMEZONE
    };
  }
  throw new HttpsErrorCtor(
    "invalid-argument",
    `Unsupported dateRange '${dateRange}'. Supported: TODAY, 7D, 30D, ALL, CUSTOM.`
  );
}

function isTimestampInRange(ms, startAtMillis, endAtMillis, dateRange) {
  if (dateRange === "ALL") return true;
  if (ms <= 0) return false;
  return ms >= startAtMillis && ms <= endAtMillis;
}

function computeApprovalRatePercent(approved, totalConversions) {
  if (!totalConversions || totalConversions <= 0) return null;
  return Math.round((approved / totalConversions) * 1000) / 10;
}

function resolveReversalRecoverySplit(conv, rewardTf) {
  const cleanReward = Math.max(0, Math.floor(Number(rewardTf || 0)));
  const hasRecovered =
    conv.recoveredCoins !== undefined &&
    conv.recoveredCoins !== null &&
    Number.isFinite(Number(conv.recoveredCoins));
  const hasShortfall =
    conv.shortfallCoins !== undefined &&
    conv.shortfallCoins !== null &&
    Number.isFinite(Number(conv.shortfallCoins));

  if (hasRecovered && hasShortfall) {
    const recoveredTf = Math.min(cleanReward, Math.max(0, Math.floor(Number(conv.recoveredCoins))));
    const shortfallTf = Math.min(
      cleanReward - recoveredTf,
      Math.max(0, Math.floor(Number(conv.shortfallCoins)))
    );
    return { recoveredTf, shortfallTf };
  }
  if (hasRecovered) {
    const recoveredTf = Math.min(cleanReward, Math.max(0, Math.floor(Number(conv.recoveredCoins))));
    return { recoveredTf, shortfallTf: Math.max(0, cleanReward - recoveredTf) };
  }
  if (hasShortfall) {
    const shortfallTf = Math.min(cleanReward, Math.max(0, Math.floor(Number(conv.shortfallCoins))));
    return { recoveredTf: Math.max(0, cleanReward - shortfallTf), shortfallTf };
  }
  // Legacy reversal record without explicit recovery fields: assume full recovery
  return { recoveredTf: cleanReward, shortfallTf: 0 };
}

/**
 * Pure server-side reversal-aware analytics aggregator matching Phase 3C ledger semantics:
 * - Credits attributed to `verifiedAt`/`creditedAt` (fallback `createdAt`).
 * - Reversals attributed to `reversedAt` (fallback `updatedAt`/`createdAt`).
 * - Reports Gross Credited TF, Reversed TF, Actually Recovered TF, Unrecovered Shortfall TF,
 *   and accurate signed Net TF (`grossCreditedTf - reversedTf`) without clamping negative periods to 0.
 * - Explicitly surfaces orphaned campaign/network buckets so summary totals always reconcile
 *   with campaignPerformance and networkPerformance sums.
 * - Never emits clickId, externalTransactionId, userId, or secret values.
 */
function computeCampaignTrackingAnalytics({
  campaigns = [],
  networks = [],
  clicks = [],
  conversions = [],
  window = { dateRange: "ALL", startAtMillis: 0, endAtMillis: Date.now(), timezone: ANALYTICS_TIMEZONE },
  pageSize = DEFAULT_PAGE_SIZE,
  campaignCursor = "",
  networkCursor = ""
}) {
  const registeredCampaignIds = new Set();
  const registeredNetworkIds = new Set();

  const campaignMap = new Map();
  for (const c of campaigns) {
    const id = String(c.campaignId || c.configId || "").trim();
    if (!id) continue;
    registeredCampaignIds.add(id);
    const rewardBudgetTf = Math.max(0, Math.floor(Number(c.rewardBudgetTf || 0)));
    const spentBudgetTf = Math.max(0, Math.floor(Number(c.spentBudgetTf || 0)));
    const submissionBudget = Math.max(0, Math.floor(Number(c.submissionBudget || 0)));
    const usedSubmissions = Math.max(0, Math.floor(Number(c.usedSubmissions || 0)));
    let budgetUtilizationPercent = null;
    if (rewardBudgetTf > 0) {
      budgetUtilizationPercent = Math.min(100, Math.round((spentBudgetTf / rewardBudgetTf) * 1000) / 10);
    } else if (submissionBudget > 0) {
      budgetUtilizationPercent = Math.min(100, Math.round((usedSubmissions / submissionBudget) * 1000) / 10);
    }

    campaignMap.set(id, {
      campaignId: id,
      title: String(c.title || id),
      networkId: String(c.networkId || ""),
      verificationMode: String(c.verificationMode || ""),
      isActive: c.isActive === true,
      isOrphaned: false,
      rewardTf: Math.max(0, Math.floor(Number(c.rewardTf || 0))),
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
      recoveredTf: 0,
      shortfallTf: 0,
      netCreditedTf: 0,
      submissionBudget,
      usedSubmissions,
      reservedClickSlots: Math.max(0, Math.floor(Number(c.reservedClickSlots || 0))),
      rewardBudgetTf,
      spentBudgetTf,
      budgetUtilizationPercent
    });
  }

  const networkMap = new Map();
  for (const n of networks) {
    const id = String(n.networkId || "").trim();
    if (!id) continue;
    registeredNetworkIds.add(id);
    networkMap.set(id, {
      networkId: id,
      name: String(n.name || id),
      adapterType: String(n.adapterType || ""),
      isActive: n.isActive === true,
      isOrphaned: false,
      campaignsCount: 0,
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
      recoveredTf: 0,
      shortfallTf: 0,
      netCreditedTf: 0
    });
  }

  const getOrCreateCampaignBucket = (rawCampaignId, fallbackNetworkId = "") => {
    const id = String(rawCampaignId || "").trim() || ORPHANED_CAMPAIGN_SENTINEL;
    if (campaignMap.has(id)) return campaignMap.get(id);
    const bucket = {
      campaignId: id,
      title:
        id === ORPHANED_CAMPAIGN_SENTINEL
          ? "Orphaned / Unattributed Campaign"
          : `Orphaned Campaign (${id})`,
      networkId: String(fallbackNetworkId || "").trim() || ORPHANED_NETWORK_SENTINEL,
      verificationMode: "ORPHANED",
      isActive: false,
      isOrphaned: true,
      rewardTf: 0,
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
      recoveredTf: 0,
      shortfallTf: 0,
      netCreditedTf: 0,
      submissionBudget: 0,
      usedSubmissions: 0,
      reservedClickSlots: 0,
      rewardBudgetTf: 0,
      spentBudgetTf: 0,
      budgetUtilizationPercent: null
    };
    campaignMap.set(id, bucket);
    return bucket;
  };

  const getOrCreateNetworkBucket = (rawNetworkId) => {
    const id = String(rawNetworkId || "").trim() || ORPHANED_NETWORK_SENTINEL;
    if (networkMap.has(id)) return networkMap.get(id);
    const bucket = {
      networkId: id,
      name:
        id === ORPHANED_NETWORK_SENTINEL
          ? "Orphaned / Unattributed Network"
          : `Orphaned Network (${id})`,
      adapterType: "ORPHANED",
      isActive: false,
      isOrphaned: true,
      campaignsCount: 0,
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
      recoveredTf: 0,
      shortfallTf: 0,
      netCreditedTf: 0
    };
    networkMap.set(id, bucket);
    return bucket;
  };

  for (const c of campaignMap.values()) {
    if (!c.isOrphaned && c.networkId && networkMap.has(c.networkId)) {
      networkMap.get(c.networkId).campaignsCount += 1;
    }
  }

  let totalClicks = 0;
  let orphanedClicksCount = 0;
  const seenClickIds = new Set();
  for (const clk of clicks) {
    const clkId = String(clk.clickId || clk._docId || "").trim();
    if (clkId && seenClickIds.has(clkId)) continue;
    if (clkId) seenClickIds.add(clkId);

    const clkMs = Math.max(
      toMillisValue(clk.createdAt),
      Number(clk.createdAtMillis || 0),
      Number(clk.issuedAtMillis || 0)
    );
    if (!isTimestampInRange(clkMs, window.startAtMillis, window.endAtMillis, window.dateRange)) {
      continue;
    }
    totalClicks += 1;
    const cId = String(clk.campaignId || "").trim();
    const nId = String(clk.networkId || "").trim();
    const campBucket = getOrCreateCampaignBucket(cId, nId);
    const netBucket = getOrCreateNetworkBucket(nId);
    campBucket.clicksCount += 1;
    netBucket.clicksCount += 1;
    if (campBucket.isOrphaned || netBucket.isOrphaned) {
      orphanedClicksCount += 1;
    }
  }

  let pendingConversions = 0;
  let approvedConversions = 0;
  let rejectedConversions = 0;
  let reversedConversions = 0;
  let grossCreditedTf = 0;
  let reversedTf = 0;
  let recoveredTf = 0;
  let shortfallTf = 0;
  let orphanedConversionsCount = 0;
  let orphanedGrossCreditedTf = 0;
  let orphanedReversedTf = 0;

  // Deduplicate conversion records by conversionId to guarantee no double-counting or double-subtraction
  const seenConversionIds = new Set();
  for (const conv of conversions) {
    const convId = String(conv.conversionId || conv._docId || "").trim();
    if (convId && seenConversionIds.has(convId)) continue;
    if (convId) seenConversionIds.add(convId);

    const state = String(conv.state || "").trim().toUpperCase();
    const rewardTf = Math.max(0, Math.floor(Number(conv.rewardTf || 0)));
    const wasCredited = conv.rewardCredited === true;

    const createdTs =
      toMillisValue(conv.createdAt) || Number(conv.createdAtMillis || 0);
    const creditTs =
      toMillisValue(conv.verifiedAt) ||
      toMillisValue(conv.creditedAt) ||
      createdTs;
    const rejectedTs =
      toMillisValue(conv.rejectedAt) ||
      toMillisValue(conv.updatedAt) ||
      createdTs;
    const reversalTs =
      toMillisValue(conv.reversedAt) ||
      toMillisValue(conv.updatedAt) ||
      creditTs;

    const cId = String(conv.campaignId || "").trim();
    const nId = String(conv.networkId || "").trim();
    const isOrphanedRecord =
      !cId || !registeredCampaignIds.has(cId) || !nId || !registeredNetworkIds.has(nId);

    if (state === CAMPAIGN_CONVERSION_STATES.PENDING) {
      if (!isTimestampInRange(createdTs, window.startAtMillis, window.endAtMillis, window.dateRange)) {
        continue;
      }
      const campBucket = getOrCreateCampaignBucket(cId, nId);
      const netBucket = getOrCreateNetworkBucket(nId);
      pendingConversions += 1;
      campBucket.totalConversions += 1;
      campBucket.pendingConversions += 1;
      netBucket.totalConversions += 1;
      netBucket.pendingConversions += 1;
      if (isOrphanedRecord) orphanedConversionsCount += 1;
    } else if (state === CAMPAIGN_CONVERSION_STATES.APPROVED) {
      if (!isTimestampInRange(creditTs, window.startAtMillis, window.endAtMillis, window.dateRange)) {
        continue;
      }
      const campBucket = getOrCreateCampaignBucket(cId, nId);
      const netBucket = getOrCreateNetworkBucket(nId);
      approvedConversions += 1;
      campBucket.totalConversions += 1;
      campBucket.approvedConversions += 1;
      netBucket.totalConversions += 1;
      netBucket.approvedConversions += 1;
      if (isOrphanedRecord) orphanedConversionsCount += 1;
      if (wasCredited) {
        grossCreditedTf += rewardTf;
        campBucket.grossCreditedTf += rewardTf;
        netBucket.grossCreditedTf += rewardTf;
        if (isOrphanedRecord) orphanedGrossCreditedTf += rewardTf;
      }
    } else if (state === CAMPAIGN_CONVERSION_STATES.REJECTED) {
      if (!isTimestampInRange(rejectedTs, window.startAtMillis, window.endAtMillis, window.dateRange)) {
        continue;
      }
      const campBucket = getOrCreateCampaignBucket(cId, nId);
      const netBucket = getOrCreateNetworkBucket(nId);
      rejectedConversions += 1;
      campBucket.totalConversions += 1;
      campBucket.rejectedConversions += 1;
      netBucket.totalConversions += 1;
      netBucket.rejectedConversions += 1;
      if (isOrphanedRecord) orphanedConversionsCount += 1;
    } else if (state === CAMPAIGN_CONVERSION_STATES.REVERSED) {
      const creditInRange =
        wasCredited &&
        isTimestampInRange(creditTs, window.startAtMillis, window.endAtMillis, window.dateRange);
      const reversalInRange = isTimestampInRange(
        reversalTs,
        window.startAtMillis,
        window.endAtMillis,
        window.dateRange
      );
      if (!creditInRange && !reversalInRange) {
        continue;
      }
      const campBucket = getOrCreateCampaignBucket(cId, nId);
      const netBucket = getOrCreateNetworkBucket(nId);

      // Credit attributed strictly to its actual verifiedAt/creditedAt window
      if (creditInRange) {
        grossCreditedTf += rewardTf;
        campBucket.grossCreditedTf += rewardTf;
        netBucket.grossCreditedTf += rewardTf;
        if (isOrphanedRecord) orphanedGrossCreditedTf += rewardTf;
      }

      // Reversal attributed strictly to its actual reversedAt window
      if (reversalInRange) {
        reversedConversions += 1;
        campBucket.totalConversions += 1;
        campBucket.reversedConversions += 1;
        netBucket.totalConversions += 1;
        netBucket.reversedConversions += 1;
        if (isOrphanedRecord) orphanedConversionsCount += 1;

        if (wasCredited) {
          const { recoveredTf: rec, shortfallTf: sf } = resolveReversalRecoverySplit(conv, rewardTf);
          reversedTf += rewardTf;
          recoveredTf += rec;
          shortfallTf += sf;
          campBucket.reversedTf += rewardTf;
          campBucket.recoveredTf += rec;
          campBucket.shortfallTf += sf;
          netBucket.reversedTf += rewardTf;
          netBucket.recoveredTf += rec;
          netBucket.shortfallTf += sf;
          if (isOrphanedRecord) orphanedReversedTf += rewardTf;
        }
      }
    }
  }

  const totalConversions =
    pendingConversions + approvedConversions + rejectedConversions + reversedConversions;
  // Signed net TF after reversals (can legitimately be negative if reversals exceed credits in the period)
  const netCreditedTf = grossCreditedTf - reversedTf;
  const netWalletImpactTf = grossCreditedTf - recoveredTf;

  const campaignRows = Array.from(campaignMap.values())
    .map((row) => {
      const rowNetTf = row.grossCreditedTf - row.reversedTf;
      return {
        ...row,
        netCreditedTf: rowNetTf,
        grossCreditedCents: tfToBalanceCents(row.grossCreditedTf),
        reversedCents: tfToBalanceCents(row.reversedTf),
        recoveredCents: tfToBalanceCents(row.recoveredTf),
        shortfallCents: tfToBalanceCents(row.shortfallTf),
        netCreditedCents: signedTfToBalanceCents(rowNetTf),
        approvalRatePercent: computeApprovalRatePercent(row.approvedConversions, row.totalConversions)
      };
    })
    .sort((a, b) => a.campaignId.localeCompare(b.campaignId));

  const networkRows = Array.from(networkMap.values())
    .map((row) => {
      const rowNetTf = row.grossCreditedTf - row.reversedTf;
      return {
        ...row,
        netCreditedTf: rowNetTf,
        grossCreditedCents: tfToBalanceCents(row.grossCreditedTf),
        reversedCents: tfToBalanceCents(row.reversedTf),
        recoveredCents: tfToBalanceCents(row.recoveredTf),
        shortfallCents: tfToBalanceCents(row.shortfallTf),
        netCreditedCents: signedTfToBalanceCents(rowNetTf),
        approvalRatePercent: computeApprovalRatePercent(row.approvedConversions, row.totalConversions)
      };
    })
    .sort((a, b) => a.networkId.localeCompare(b.networkId));

  const orphanedCampaignCount = campaignRows.filter((c) => c.isOrphaned).length;
  const orphanedNetworkCount = networkRows.filter((n) => n.isOrphaned).length;

  return {
    dateRange: window.dateRange,
    timezone: window.timezone || ANALYTICS_TIMEZONE,
    startAtMillis: window.startAtMillis,
    endAtMillis: window.endAtMillis,
    summary: {
      totalCampaigns: registeredCampaignIds.size,
      activeCampaigns: Array.from(campaignMap.values()).filter((c) => !c.isOrphaned && c.isActive).length,
      totalNetworks: registeredNetworkIds.size,
      activeNetworks: Array.from(networkMap.values()).filter((n) => !n.isOrphaned && n.isActive).length,
      totalClicks,
      totalConversions,
      pendingConversions,
      approvedConversions,
      rejectedConversions,
      reversedConversions,
      approvalRatePercent: computeApprovalRatePercent(approvedConversions, totalConversions),
      hasConversionData: totalConversions > 0,
      grossCreditedTf,
      reversedTf,
      recoveredTf,
      shortfallTf,
      netCreditedTf,
      netWalletImpactTf,
      grossCreditedCents: tfToBalanceCents(grossCreditedTf),
      reversedCents: tfToBalanceCents(reversedTf),
      recoveredCents: tfToBalanceCents(recoveredTf),
      shortfallCents: tfToBalanceCents(shortfallTf),
      netCreditedCents: signedTfToBalanceCents(netCreditedTf),
      netWalletImpactCents: signedTfToBalanceCents(netWalletImpactTf),
      orphanedClicksCount,
      orphanedConversionsCount,
      orphanedGrossCreditedTf,
      orphanedReversedTf,
      orphanedCampaignCount,
      orphanedNetworkCount
    },
    campaignPerformance: paginateSortedArray(
      campaignRows,
      pageSize,
      campaignCursor,
      (item) => item.campaignId
    ),
    networkPerformance: paginateSortedArray(
      networkRows,
      pageSize,
      networkCursor,
      (item) => item.networkId
    )
  };
}

function mapSnapshotDocs(snap) {
  const docs = snap?.docs || [];
  return docs.map((d) => ({
    _docId: d.id,
    ...(typeof d.data === "function" ? d.data() || {} : {})
  }));
}

/**
 * Paginates a Firestore query or collection in bounded batches using cursor `startAfter`
 * when supported, probing `maxLimit + 1` so `truncated` is true if and only if strictly
 * more than `maxLimit` documents exist.
 */
async function executePaginatedQuery(baseQuery, maxLimit, batchSize = DEFAULT_SCAN_BATCH_SIZE) {
  const safeMax = Math.max(1, Math.floor(Number(maxLimit || MAX_ANALYTICS_SCAN_LIMIT)));
  const safeBatch = Math.min(safeMax + 1, Math.max(1, Math.floor(Number(batchSize || DEFAULT_SCAN_BATCH_SIZE))));

  const canCursorPaginate =
    typeof baseQuery.startAfter === "function" && typeof baseQuery.limit === "function";

  if (!canCursorPaginate) {
    const q = typeof baseQuery.limit === "function" ? baseQuery.limit(safeMax + 1) : baseQuery;
    const snap = await q.get();
    const mapped = mapSnapshotDocs(snap);
    const truncated = mapped.length > safeMax;
    return {
      docs: truncated ? mapped.slice(0, safeMax) : mapped,
      truncated
    };
  }

  const collected = [];
  let lastDocSnap = null;
  let truncated = false;

  while (collected.length <= safeMax) {
    const remainingToProbe = safeMax + 1 - collected.length;
    const fetchSize = Math.min(safeBatch, remainingToProbe);
    let q = baseQuery;
    if (lastDocSnap) {
      q = q.startAfter(lastDocSnap);
    }
    q = q.limit(fetchSize);
    const snap = await q.get();
    const rawDocs = snap?.docs || [];
    if (rawDocs.length === 0) {
      break;
    }
    for (const d of rawDocs) {
      collected.push({
        _docId: d.id,
        ...(typeof d.data === "function" ? d.data() || {} : {})
      });
    }
    lastDocSnap = rawDocs[rawDocs.length - 1];
    if (collected.length > safeMax) {
      truncated = true;
      break;
    }
    if (rawDocs.length < fetchSize) {
      break;
    }
  }

  return {
    docs: truncated ? collected.slice(0, safeMax) : collected,
    truncated
  };
}

async function readCollectionBounded(
  firestoreDb,
  collectionName,
  limitCount = MAX_ANALYTICS_SCAN_LIMIT,
  batchSize = DEFAULT_SCAN_BATCH_SIZE
) {
  const colRef = firestoreDb.collection(collectionName);
  const orderedRef = typeof colRef.orderBy === "function" ? colRef.orderBy("__name__") : colRef;
  return executePaginatedQuery(orderedRef, limitCount, batchSize);
}

/**
 * Reads clicks for the requested analytics window using indexed single-field timestamp range
 * queries (`createdAt >= start && createdAt <= end`) when Firestore query APIs are available,
 * falling back to bounded paginated scanning for ALL or minimal mocks.
 */
async function readClicksForWindow(firestoreDb, window, maxLimit, batchSize) {
  const colRef = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS);
  if (
    window.dateRange !== "ALL" &&
    typeof colRef.where === "function" &&
    typeof colRef.orderBy === "function"
  ) {
    const rangeQuery = colRef
      .where("createdAt", ">=", new Date(window.startAtMillis))
      .where("createdAt", "<=", new Date(window.endAtMillis))
      .orderBy("createdAt", "desc");
    const result = await executePaginatedQuery(rangeQuery, maxLimit, batchSize);
    return { ...result, queryStrategy: "INDEXED_TIMESTAMP_RANGE" };
  }
  const result = await readCollectionBounded(
    firestoreDb,
    CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS,
    maxLimit,
    batchSize
  );
  return { ...result, queryStrategy: "BOUNDED_COLLECTION_SCAN" };
}

/**
 * Reads conversions relevant to the requested analytics window using single-field indexed
 * timestamp range queries across `createdAt`, `verifiedAt`, `rejectedAt`, and `reversedAt`,
 * deduplicated by document ID so cross-period credits, rejections, and reversals
 * (e.g., created in Period A, rejected or reversed in Period B) are always discovered accurately
 * without full-collection scans.
 */
async function readConversionsForWindow(firestoreDb, window, maxLimit, batchSize) {
  const colRef = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS);
  if (
    window.dateRange !== "ALL" &&
    typeof colRef.where === "function" &&
    typeof colRef.orderBy === "function"
  ) {
    const startDate = new Date(window.startAtMillis);
    const endDate = new Date(window.endAtMillis);
    const fields = ["createdAt", "verifiedAt", "rejectedAt", "reversedAt"];
    const results = await Promise.all(
      fields.map((field) =>
        executePaginatedQuery(
          colRef.where(field, ">=", startDate).where(field, "<=", endDate).orderBy(field, "desc"),
          maxLimit,
          batchSize
        )
      )
    );
    const dedup = new Map();
    let truncated = false;
    for (const res of results) {
      if (res.truncated) truncated = true;
      for (const doc of res.docs) {
        const key = String(doc.conversionId || doc._docId || "").trim();
        if (key && !dedup.has(key)) {
          dedup.set(key, doc);
        }
      }
    }
    return {
      docs: Array.from(dedup.values()),
      truncated,
      queryStrategy: "INDEXED_TIMESTAMP_RANGE"
    };
  }
  const result = await readCollectionBounded(
    firestoreDb,
    CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS,
    maxLimit,
    batchSize
  );
  return { ...result, queryStrategy: "BOUNDED_COLLECTION_SCAN" };
}

async function findActiveDependentCampaignsInTransaction(firestoreDb, transaction, networkId) {
  const configsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS);
  if (typeof configsCol.where === "function") {
    const query = configsCol.where("networkId", "==", networkId).where("isActive", "==", true);
    const snap = await transaction.get(query);
    return mapSnapshotDocs(snap).filter((c) => c.isActive === true && String(c.networkId || "").trim() === networkId);
  }
  // Fallback for lightweight unit-test mocks without `.where()`
  const snap = await configsCol.get();
  return mapSnapshotDocs(snap).filter(
    (c) => c.isActive === true && String(c.networkId || "").trim() === networkId
  );
}

function createCampaignAdminHandlers({ verifyAdminAccess, getDefaultDb, getDefaultServerTimestamp, HttpsError }) {
  async function handleAdminListCampaignNetworks(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can view campaign networks."
    );

    const data = request.data || {};
    const exactNetworkId = String(data.networkId || "").trim();
    if (exactNetworkId) {
      if (!VALID_ID_REGEX.test(exactNetworkId)) {
        throw new HttpsError("invalid-argument", "Invalid networkId lookup filter.");
      }
      const docSnap = await firestoreDb
        .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
        .doc(exactNetworkId)
        .get();
      let activeCampaignsCount = 0;
      if (docSnap.exists) {
        const configsCol = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS);
        if (typeof configsCol.where === "function") {
          const activeSnap = await configsCol
            .where("networkId", "==", exactNetworkId)
            .where("isActive", "==", true)
            .get();
          activeCampaignsCount = mapSnapshotDocs(activeSnap).filter(
            (c) => c.isActive === true && String(c.networkId || "").trim() === exactNetworkId
          ).length;
        } else {
          const allSnap = await configsCol.get();
          activeCampaignsCount = mapSnapshotDocs(allSnap).filter(
            (c) => c.isActive === true && String(c.networkId || "").trim() === exactNetworkId
          ).length;
        }
      }
      const items = docSnap.exists
        ? [{ ...toSafeAdminNetworkDto(docSnap.data() || {}, docSnap.id), activeCampaignsCount }]
        : [];
      return {
        success: true,
        items,
        nextCursor: "",
        hasMore: false,
        totalCount: items.length,
        truncated: false,
        registeredSecretKeys: [...ALLOWED_CAMPAIGN_SECRET_KEYS],
        supportedSignatureAlgorithms: [...SUPPORTED_SIGNATURE_ALGORITHMS],
        supportedSigningModes: [...SUPPORTED_HMAC_SIGNING_MODES]
      };
    }

    const pageSize = clampPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();
    const filterAdapterType = String(data.adapterType || "").trim().toUpperCase();
    const filterActive = typeof data.isActive === "boolean" ? data.isActive : null;
    const search = String(data.search || "").trim().toLowerCase();

    if (filterAdapterType && !isValidVerificationMode(filterAdapterType)) {
      throw new HttpsError("invalid-argument", `Invalid adapterType filter '${filterAdapterType}'.`);
    }

    const networkScanLimit = Math.max(1, Number(deps.maxNetworksScanLimit || MAX_NETWORKS_SCAN_LIMIT));
    const campaignScanLimit = Math.max(1, Number(deps.maxCampaignsScanLimit || MAX_CAMPAIGNS_SCAN_LIMIT));
    const scanBatchSize = Math.max(1, Number(deps.scanBatchSize || DEFAULT_SCAN_BATCH_SIZE));
    const [{ docs: rawDocs, truncated }, { docs: campaignDocs }] = await Promise.all([
      readCollectionBounded(
        firestoreDb,
        CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS,
        networkScanLimit,
        scanBatchSize
      ),
      readCollectionBounded(
        firestoreDb,
        CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS,
        campaignScanLimit,
        scanBatchSize
      )
    ]);

    const activeCountByNetwork = new Map();
    for (const cfg of campaignDocs) {
      if (cfg.isActive !== true) continue;
      const nId = String(cfg.networkId || "").trim();
      if (!nId) continue;
      activeCountByNetwork.set(nId, (activeCountByNetwork.get(nId) || 0) + 1);
    }

    const filtered = rawDocs
      .map((doc) => {
        const dto = toSafeAdminNetworkDto(doc, doc._docId);
        return {
          ...dto,
          activeCampaignsCount: activeCountByNetwork.get(dto.networkId) || 0
        };
      })
      .filter((item) => {
        if (filterAdapterType && item.adapterType !== filterAdapterType) return false;
        if (filterActive !== null && item.isActive !== filterActive) return false;
        if (search) {
          const hay = `${item.networkId} ${item.name} ${item.adapterType}`.toLowerCase();
          if (!hay.includes(search)) return false;
        }
        return true;
      })
      .sort((a, b) => a.networkId.localeCompare(b.networkId));

    const page = paginateSortedArray(filtered, pageSize, cursor, (item) => item.networkId);
    return {
      success: true,
      ...page,
      truncated,
      registeredSecretKeys: [...ALLOWED_CAMPAIGN_SECRET_KEYS],
      supportedSignatureAlgorithms: [...SUPPORTED_SIGNATURE_ALGORITHMS],
      supportedSigningModes: [...SUPPORTED_HMAC_SIGNING_MODES]
    };
  }

  async function handleAdminUpsertCampaignNetwork(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);

    const adminInfo = await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can manage campaign networks."
    );

    const data = request.data || {};
    const mode = String(data.mode || "UPSERT").trim().toUpperCase();
    if (!["CREATE", "UPDATE", "UPSERT"].includes(mode)) {
      throw new HttpsError("invalid-argument", "mode must be CREATE, UPDATE, or UPSERT.");
    }

    // Preliminary validation (strictly before opening transaction)
    const preValidated = validateAndSanitizeNetworkInput(data, HttpsError);
    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(preValidated.networkId);
    const logRef = firestoreDb.collection("admin_logs").doc();

    const saved = await firestoreDb.runTransaction(async (transaction) => {
      const existingSnap = await transaction.get(networkRef);
      if (mode === "CREATE" && existingSnap.exists) {
        throw new HttpsError(
          "already-exists",
          `Campaign network '${preValidated.networkId}' already exists.`
        );
      }
      if (mode === "UPDATE" && !existingSnap.exists) {
        throw new HttpsError(
          "not-found",
          `Campaign network '${preValidated.networkId}' does not exist.`
        );
      }
      const existingData = existingSnap.exists ? existingSnap.data() || {} : null;

      // Re-sanitize with existingData so Phase 3B/3C fields (adapterId, signaturePrefix, idempotencyParam) are preserved
      const sanitized = validateAndSanitizeNetworkInput(data, HttpsError, existingData);

      // Guard against silently invalidating active dependent campaigns during network edits
      if (existingSnap.exists) {
        const activeDependents = await findActiveDependentCampaignsInTransaction(
          firestoreDb,
          transaction,
          sanitized.networkId
        );
        if (activeDependents.length > 0) {
          for (const depCamp of activeDependents) {
            validateCampaignActivationCompatibility({
              config: depCamp,
              networkData: sanitized,
              nowMillis,
              HttpsErrorCtor: HttpsError,
              skipScheduleAndBudget: true
            });
          }
        }
      }

      const preservedExtras = extractPreservedSafeFields(existingData, MANAGED_NETWORK_TOP_LEVEL_KEYS);
      const docToWrite = {
        ...preservedExtras,
        ...sanitized,
        createdBy: existingData?.createdBy || adminInfo.uid,
        updatedBy: adminInfo.uid,
        createdAt: existingData?.createdAt || ts,
        updatedAt: ts
      };

      transaction.set(networkRef, docToWrite, { merge: false });
      transaction.set(logRef, {
        actionType: "CAMPAIGN_NETWORK",
        title: existingSnap.exists
          ? `Updated Network: ${sanitized.name}`
          : `Created Network: ${sanitized.name}`,
        details: `Network: ${sanitized.networkId} • Adapter: ${sanitized.adapterType} • Sig: ${sanitized.signatureConfig.algorithm} • Active: ${sanitized.isActive}`,
        targetId: sanitized.networkId,
        adminId: adminInfo.uid,
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return {
        isNew: !existingSnap.exists,
        network: toSafeAdminNetworkDto(
          { ...docToWrite, createdAt: existingData?.createdAt || new Date(nowMillis), updatedAt: new Date(nowMillis) },
          sanitized.networkId
        )
      };
    });

    return {
      success: true,
      ...saved
    };
  }

  async function handleAdminSetCampaignNetworkStatus(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);

    const adminInfo = await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can update campaign network status."
    );

    const data = request.data || {};
    const networkId = String(data.networkId || "").trim();
    if (!VALID_ID_REGEX.test(networkId)) {
      throw new HttpsError("invalid-argument", "A valid networkId is required.");
    }
    if (typeof data.isActive !== "boolean") {
      throw new HttpsError("invalid-argument", "isActive boolean is required.");
    }

    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(networkId);
    const logRef = firestoreDb.collection("admin_logs").doc();

    const updated = await firestoreDb.runTransaction(async (transaction) => {
      const snap = await transaction.get(networkRef);
      if (!snap.exists) {
        throw new HttpsError("not-found", `Campaign network '${networkId}' not found.`);
      }
      const prev = snap.data() || {};
      transaction.update(networkRef, {
        isActive: data.isActive,
        updatedBy: adminInfo.uid,
        updatedAt: ts
      });
      transaction.set(logRef, {
        actionType: "CAMPAIGN_NETWORK",
        title: data.isActive ? `Enabled Network: ${prev.name || networkId}` : `Disabled Network: ${prev.name || networkId}`,
        details: `Network: ${networkId} • Active: ${data.isActive}`,
        targetId: networkId,
        adminId: adminInfo.uid,
        timestampMillis: nowMillis,
        createdAt: ts
      });
      return toSafeAdminNetworkDto(
        { ...prev, isActive: data.isActive, updatedAt: new Date(nowMillis) },
        networkId
      );
    });

    return {
      success: true,
      network: updated
    };
  }

  async function handleAdminListCampaignTrackingConfigs(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can view campaign tracking configurations."
    );

    const data = request.data || {};
    const pageSize = clampPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();
    const filterNetworkId = String(data.networkId || "").trim();
    const filterMode = String(data.verificationMode || "").trim().toUpperCase();
    const filterActive = typeof data.isActive === "boolean" ? data.isActive : null;
    const search = String(data.search || "").trim().toLowerCase();

    if (filterMode && !isValidVerificationMode(filterMode)) {
      throw new HttpsError("invalid-argument", `Invalid verificationMode filter '${filterMode}'.`);
    }

    const campaignScanLimit = Math.max(1, Number(deps.maxCampaignsScanLimit || MAX_CAMPAIGNS_SCAN_LIMIT));
    const scanBatchSize = Math.max(1, Number(deps.scanBatchSize || DEFAULT_SCAN_BATCH_SIZE));
    const { docs: rawDocs, truncated } = await readCollectionBounded(
      firestoreDb,
      CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS,
      campaignScanLimit,
      scanBatchSize
    );

    const filtered = rawDocs
      .map((doc) => toSafeAdminCampaignConfigDto(doc, doc._docId))
      .filter((item) => {
        if (filterNetworkId && item.networkId !== filterNetworkId) return false;
        if (filterMode && item.verificationMode !== filterMode) return false;
        if (filterActive !== null && item.isActive !== filterActive) return false;
        if (search) {
          const hay = `${item.campaignId} ${item.title} ${item.networkId} ${item.verificationMode}`.toLowerCase();
          if (!hay.includes(search)) return false;
        }
        return true;
      })
      .sort((a, b) => a.campaignId.localeCompare(b.campaignId));

    const page = paginateSortedArray(filtered, pageSize, cursor, (item) => item.campaignId);
    return {
      success: true,
      ...page,
      truncated
    };
  }

  async function handleAdminUpsertCampaignTrackingConfig(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);

    const adminInfo = await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can manage campaign tracking configurations."
    );

    const data = request.data || {};
    const mode = String(data.mode || "UPSERT").trim().toUpperCase();
    if (!["CREATE", "UPDATE", "UPSERT"].includes(mode)) {
      throw new HttpsError("invalid-argument", "mode must be CREATE, UPDATE, or UPSERT.");
    }

    const campaignId = String(data.campaignId || data.configId || "").trim();
    const networkId = String(data.networkId || "").trim();
    if (!VALID_ID_REGEX.test(campaignId)) {
      throw new HttpsError("invalid-argument", "Valid campaignId is required.");
    }
    if (!VALID_ID_REGEX.test(networkId)) {
      throw new HttpsError("invalid-argument", "Valid networkId is required.");
    }

    const configRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
      .doc(campaignId);
    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(networkId);
    const publicCampaignRef = firestoreDb.collection("campaigns").doc(campaignId);
    const logRef = firestoreDb.collection("admin_logs").doc();

    const saved = await firestoreDb.runTransaction(async (transaction) => {
      const [existingConfigSnap, networkSnap, publicCampaignSnap] = await Promise.all([
        transaction.get(configRef),
        transaction.get(networkRef),
        transaction.get(publicCampaignRef)
      ]);

      if (!networkSnap.exists) {
        throw new HttpsError("not-found", `Selected network '${networkId}' does not exist.`);
      }
      if (mode === "CREATE" && existingConfigSnap.exists) {
        throw new HttpsError("already-exists", `Campaign config '${campaignId}' already exists.`);
      }
      if (mode === "UPDATE" && !existingConfigSnap.exists) {
        throw new HttpsError("not-found", `Campaign config '${campaignId}' does not exist.`);
      }

      const existingData = existingConfigSnap.exists ? existingConfigSnap.data() || {} : null;
      const networkData = { ...(networkSnap.data() || {}), networkId };

      const sanitized = validateAndSanitizeCampaignConfigInput(
        data,
        networkData,
        existingData,
        HttpsError,
        nowMillis
      );

      // Preserve server-authoritative runtime counters (never let client reset budgets/clicks)
      const usedSubmissions = Math.max(0, Math.floor(Number(existingData?.usedSubmissions || 0)));
      const spentBudgetTf = Math.max(0, Math.floor(Number(existingData?.spentBudgetTf || 0)));
      const reservedClickSlots = Math.max(0, Math.floor(Number(existingData?.reservedClickSlots || 0)));
      const issuedClicksCount = Math.max(0, Math.floor(Number(existingData?.issuedClicksCount || 0)));

      // Preserve any additional safe non-secret Phase 3B/3C runtime fields on existing config
      const preservedExtras = extractPreservedSafeFields(
        existingData,
        MANAGED_CAMPAIGN_CONFIG_TOP_LEVEL_KEYS
      );

      const docToWrite = {
        ...preservedExtras,
        ...sanitized,
        usedSubmissions,
        spentBudgetTf,
        reservedClickSlots,
        issuedClicksCount,
        createdBy: existingData?.createdBy || adminInfo.uid,
        updatedBy: adminInfo.uid,
        createdAt: existingData?.createdAt || ts,
        updatedAt: ts
      };

      transaction.set(configRef, docToWrite, { merge: false });

      // Mirror strictly non-secret public metadata into /campaigns/{campaignId}
      const prevPublic = publicCampaignSnap.exists ? publicCampaignSnap.data() || {} : {};
      transaction.set(
        publicCampaignRef,
        {
          campaignId,
          title: sanitized.title,
          clientName: String(prevPublic.clientName || networkData.name || "Tracked Partner"),
          description: String(prevPublic.description || `Tracked via ${sanitized.verificationMode}`),
          budgetCents: tfToBalanceCents(sanitized.rewardBudgetTf),
          spentCents: Math.max(0, Math.floor(Number(prevPublic.spentCents || tfToBalanceCents(spentBudgetTf)))),
          targetSubmissions: sanitized.submissionBudget,
          completedSubmissions: Math.max(0, Math.floor(Number(prevPublic.completedSubmissions || usedSubmissions))),
          status: sanitized.isActive ? "ACTIVE" : "PAUSED",
          category: String(prevPublic.category || "Tracked Campaign"),
          rewardPerTaskCents: sanitized.rewardCents,
          startDate: sanitized.startDate,
          endDate: sanitized.endDate,
          createdAt: prevPublic.createdAt || ts,
          updatedAt: ts
        },
        { merge: true }
      );

      transaction.set(logRef, {
        actionType: "CAMPAIGN_CONFIG",
        title: existingConfigSnap.exists
          ? `Updated Campaign Config: ${sanitized.title}`
          : `Created Campaign Config: ${sanitized.title}`,
        details: `Campaign: ${campaignId} • Network: ${networkId} • Mode: ${sanitized.verificationMode} • Reward: ${sanitized.rewardTf} TF • Active: ${sanitized.isActive}`,
        targetId: campaignId,
        adminId: adminInfo.uid,
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return {
        isNew: !existingConfigSnap.exists,
        config: toSafeAdminCampaignConfigDto(
          { ...docToWrite, createdAt: existingData?.createdAt || new Date(nowMillis), updatedAt: new Date(nowMillis) },
          campaignId
        )
      };
    });

    return {
      success: true,
      ...saved
    };
  }

  async function handleAdminSetCampaignTrackingStatus(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();
    const ts = getDefaultServerTimestamp(deps);

    const adminInfo = await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can activate or pause tracked campaigns."
    );

    const data = request.data || {};
    const campaignId = String(data.campaignId || "").trim();
    if (!VALID_ID_REGEX.test(campaignId)) {
      throw new HttpsError("invalid-argument", "A valid campaignId is required.");
    }
    if (typeof data.isActive !== "boolean") {
      throw new HttpsError("invalid-argument", "isActive boolean is required.");
    }

    const configRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
      .doc(campaignId);
    const publicCampaignRef = firestoreDb.collection("campaigns").doc(campaignId);
    const logRef = firestoreDb.collection("admin_logs").doc();

    const updated = await firestoreDb.runTransaction(async (transaction) => {
      const [configSnap, publicSnap] = await Promise.all([
        transaction.get(configRef),
        transaction.get(publicCampaignRef)
      ]);
      if (!configSnap.exists) {
        throw new HttpsError("not-found", `Campaign tracking config '${campaignId}' not found.`);
      }
      const prev = configSnap.data() || {};
      const networkId = String(prev.networkId || "").trim();

      if (data.isActive) {
        if (!networkId) {
          throw new HttpsError("failed-precondition", "Campaign has no valid networkId configured.");
        }
        const networkRef = firestoreDb
          .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
          .doc(networkId);
        const networkSnap = await transaction.get(networkRef);
        const networkData = networkSnap.exists ? { ...(networkSnap.data() || {}), networkId } : null;
        validateCampaignActivationCompatibility({
          config: { ...prev, campaignId },
          networkData,
          nowMillis,
          HttpsErrorCtor: HttpsError
        });
      }

      transaction.update(configRef, {
        isActive: data.isActive,
        updatedBy: adminInfo.uid,
        updatedAt: ts
      });
      if (publicSnap.exists) {
        transaction.update(publicCampaignRef, {
          status: data.isActive ? "ACTIVE" : "PAUSED",
          updatedAt: ts
        });
      }
      transaction.set(logRef, {
        actionType: "CAMPAIGN_CONFIG",
        title: data.isActive
          ? `Activated Campaign: ${prev.title || campaignId}`
          : `Paused Campaign: ${prev.title || campaignId}`,
        details: `Campaign: ${campaignId} • Active: ${data.isActive}`,
        targetId: campaignId,
        adminId: adminInfo.uid,
        timestampMillis: nowMillis,
        createdAt: ts
      });

      return toSafeAdminCampaignConfigDto(
        { ...prev, isActive: data.isActive, updatedAt: new Date(nowMillis) },
        campaignId
      );
    });

    return {
      success: true,
      config: updated
    };
  }

  async function handleAdminGetCampaignTrackingDashboard(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();
    const nowMillis = typeof deps.now === "function" ? deps.now() : Date.now();

    await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can view campaign tracking analytics."
    );

    const data = request.data || {};
    const window = resolveAnalyticsDateWindow(data, nowMillis, HttpsError);
    const pageSize = clampPageSize(data.pageSize);
    const campaignCursor = String(data.campaignCursor || "").trim();
    const networkCursor = String(data.networkCursor || "").trim();

    const maxAnalyticsScan = Math.max(1, Number(deps.maxAnalyticsScanLimit || MAX_ANALYTICS_SCAN_LIMIT));
    const maxCampaignsScan = Math.max(1, Number(deps.maxCampaignsScanLimit || MAX_CAMPAIGNS_SCAN_LIMIT));
    const maxNetworksScan = Math.max(1, Number(deps.maxNetworksScanLimit || MAX_NETWORKS_SCAN_LIMIT));
    const scanBatchSize = Math.max(1, Number(deps.scanBatchSize || DEFAULT_SCAN_BATCH_SIZE));

    const [campaignsRes, networksRes, clicksRes, conversionsRes] = await Promise.all([
      readCollectionBounded(
        firestoreDb,
        CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS,
        maxCampaignsScan,
        scanBatchSize
      ),
      readCollectionBounded(
        firestoreDb,
        CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS,
        maxNetworksScan,
        scanBatchSize
      ),
      readClicksForWindow(firestoreDb, window, maxAnalyticsScan, scanBatchSize),
      readConversionsForWindow(firestoreDb, window, maxAnalyticsScan, scanBatchSize)
    ]);

    const analytics = computeCampaignTrackingAnalytics({
      campaigns: campaignsRes.docs,
      networks: networksRes.docs,
      clicks: clicksRes.docs,
      conversions: conversionsRes.docs,
      window,
      pageSize,
      campaignCursor,
      networkCursor
    });

    const isTruncated = Boolean(
      clicksRes.truncated ||
      conversionsRes.truncated ||
      campaignsRes.truncated ||
      networksRes.truncated
    );

    return {
      success: true,
      ...analytics,
      scanLimits: {
        clicksScanLimit: maxAnalyticsScan,
        conversionsScanLimit: maxAnalyticsScan,
        campaignsScanLimit: maxCampaignsScan,
        networksScanLimit: maxNetworksScan,
        clicksScanned: clicksRes.docs.length,
        conversionsScanned: conversionsRes.docs.length,
        clicksTruncated: Boolean(clicksRes.truncated),
        conversionsTruncated: Boolean(conversionsRes.truncated),
        campaignsTruncated: Boolean(campaignsRes.truncated),
        networksTruncated: Boolean(networksRes.truncated),
        isTruncated,
        queryStrategy: conversionsRes.queryStrategy || clicksRes.queryStrategy || "BOUNDED_COLLECTION_SCAN"
      }
    };
  }

  /**
   * Phase 3E.2: Admin Conversion Moderation Queue (read-only).
   * Returns paginated, strictly sanitized conversion moderation items without
   * exposing `clickId`, raw `conv_<clickId>`, `externalTransactionId`, secrets,
   * raw signatures, or unnecessary partner metadata.
   */
  async function handleAdminListCampaignConversions(request, deps = {}) {
    const firestoreDb = deps.db || getDefaultDb();

    await verifyAdminAccess(
      request,
      firestoreDb,
      "Only authorized administrators can view campaign conversions."
    );

    const data = request.data || {};
    const rawStatus = String(data.status || "ALL").trim().toUpperCase();
    const allowedStatuses = new Set(["ALL", "PENDING", "APPROVED", "REJECTED", "REVERSED"]);
    if (!allowedStatuses.has(rawStatus)) {
      throw new HttpsError(
        "invalid-argument",
        "status filter must be one of ALL, PENDING, APPROVED, REJECTED, REVERSED."
      );
    }

    const campaignIdFilter = String(data.campaignId || "").trim();
    if (campaignIdFilter && !VALID_ID_REGEX.test(campaignIdFilter)) {
      throw new HttpsError("invalid-argument", "Invalid campaignId filter.");
    }

    const networkIdFilter = String(data.networkId || "").trim();
    if (networkIdFilter && !VALID_ID_REGEX.test(networkIdFilter)) {
      throw new HttpsError("invalid-argument", "Invalid networkId filter.");
    }

    const pageSize = clampPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();
    const maxScanLimit = Math.max(
      1,
      Number(deps.maxConversionsQueueScanLimit || MAX_CONVERSIONS_QUEUE_SCAN_LIMIT)
    );
    const scanBatchSize = Math.max(1, Number(deps.scanBatchSize || DEFAULT_SCAN_BATCH_SIZE));

    const colRef = firestoreDb.collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS);
    let queryRes;
    if (rawStatus !== "ALL" && typeof colRef.where === "function") {
      queryRes = await executePaginatedQuery(
        colRef.where("state", "==", rawStatus),
        maxScanLimit,
        scanBatchSize
      );
    } else if (campaignIdFilter && typeof colRef.where === "function") {
      queryRes = await executePaginatedQuery(
        colRef.where("campaignId", "==", campaignIdFilter),
        maxScanLimit,
        scanBatchSize
      );
    } else if (networkIdFilter && typeof colRef.where === "function") {
      queryRes = await executePaginatedQuery(
        colRef.where("networkId", "==", networkIdFilter),
        maxScanLimit,
        scanBatchSize
      );
    } else {
      queryRes = await readCollectionBounded(
        firestoreDb,
        CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS,
        maxScanLimit,
        scanBatchSize
      );
    }

    const filtered = [];
    for (const doc of queryRes.docs) {
      const state = String(doc.state || "").trim().toUpperCase();
      if (!isValidConversionState(state)) continue;
      if (rawStatus !== "ALL" && state !== rawStatus) continue;
      const cId = String(doc.campaignId || "").trim();
      if (campaignIdFilter && cId !== campaignIdFilter) continue;
      const nId = String(doc.networkId || "").trim();
      if (networkIdFilter && nId !== networkIdFilter) continue;

      const rawId = String(doc.conversionId || doc._docId || "").trim();
      const modId = String(doc.moderationId || "").trim() || buildConversionModerationId(rawId);
      const sortTimestampMillis = Math.max(
        toMillisValue(doc.updatedAt),
        toMillisValue(doc.reversedAt),
        toMillisValue(doc.rejectedAt),
        toMillisValue(doc.verifiedAt),
        toMillisValue(doc.createdAt)
      );
      filtered.push({ doc, modId, sortTimestampMillis });
    }

    filtered.sort((a, b) => {
      if (b.sortTimestampMillis !== a.sortTimestampMillis) {
        return b.sortTimestampMillis - a.sortTimestampMillis;
      }
      return a.modId.localeCompare(b.modId);
    });

    const totalCount = filtered.length;
    let startIndex = 0;
    if (cursor) {
      const cursorIdx = filtered.findIndex((entry) => entry.modId === cursor);
      if (cursorIdx >= 0) {
        startIndex = cursorIdx + 1;
      }
    }

    const pageEntries = filtered.slice(startIndex, startIndex + pageSize);
    const hasMore = startIndex + pageEntries.length < totalCount;
    const nextCursor =
      hasMore && pageEntries.length > 0 ? pageEntries[pageEntries.length - 1].modId : null;

    // Resolve titles, network names, and user wallet snapshots ONLY for the current page
    const uniqueCampaignIds = Array.from(
      new Set(pageEntries.map((e) => String(e.doc.campaignId || "").trim()).filter(Boolean))
    );
    const uniqueNetworkIds = Array.from(
      new Set(pageEntries.map((e) => String(e.doc.networkId || "").trim()).filter(Boolean))
    );
    const uniqueUserIds = Array.from(
      new Set(pageEntries.map((e) => String(e.doc.userId || "").trim()).filter(Boolean))
    );

    const campaignTitleMap = new Map();
    const networkNameMap = new Map();
    const userMap = new Map();

    await Promise.all([
      ...uniqueCampaignIds.map(async (cId) => {
        try {
          const [cfgSnap, pubSnap] = await Promise.all([
            firestoreDb
              .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS)
              .doc(cId)
              .get(),
            firestoreDb.collection("campaigns").doc(cId).get()
          ]);
          const title = String(
            (pubSnap?.exists ? pubSnap.data()?.title : "") ||
              (cfgSnap?.exists ? cfgSnap.data()?.title : "") ||
              cId
          ).trim();
          campaignTitleMap.set(cId, title || cId);
        } catch (_) {
          campaignTitleMap.set(cId, cId);
        }
      }),
      ...uniqueNetworkIds.map(async (nId) => {
        try {
          const netSnap = await firestoreDb
            .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
            .doc(nId)
            .get();
          const name = String(netSnap?.exists ? netSnap.data()?.name : nId).trim();
          networkNameMap.set(nId, name || nId);
        } catch (_) {
          networkNameMap.set(nId, nId);
        }
      }),
      ...uniqueUserIds.map(async (uId) => {
        try {
          const uSnap = await firestoreDb.collection("users").doc(uId).get();
          if (uSnap?.exists) {
            userMap.set(uId, uSnap.data() || {});
          }
        } catch (_) {
          // Ignore individual user fetch failures; fallback gracefully
        }
      })
    ]);

    const items = pageEntries.map((entry) =>
      toSafeAdminConversionDto(entry.doc, {
        campaignTitleMap,
        networkNameMap,
        userMap
      })
    );

    return {
      success: true,
      items,
      nextCursor,
      hasMore,
      totalCount,
      truncated: Boolean(queryRes.truncated),
      scanLimit: maxScanLimit
    };
  }

  return {
    handleAdminListCampaignNetworks,
    handleAdminUpsertCampaignNetwork,
    handleAdminSetCampaignNetworkStatus,
    handleAdminListCampaignTrackingConfigs,
    handleAdminUpsertCampaignTrackingConfig,
    handleAdminSetCampaignTrackingStatus,
    handleAdminGetCampaignTrackingDashboard,
    handleAdminListCampaignConversions
  };
}

/**
 * Strictly allowlisted Admin Conversion Moderation DTO.
 * Never returns `clickId`, raw `conversionId` (which embeds `clickId`), `externalTransactionId`,
 * `idempotencyKey`, `callbackPayoutRaw`, `signatureValue`, `trackingNetworkRef`, or any secret.
 */
function toSafeAdminConversionDto(
  convDoc = {},
  { campaignTitleMap = new Map(), networkNameMap = new Map(), userMap = new Map() } = {}
) {
  const rawConversionId = String(convDoc.conversionId || convDoc._docId || "").trim();
  const moderationId =
    String(convDoc.moderationId || "").trim() || buildConversionModerationId(rawConversionId);
  const displayRef = `MOD-${moderationId.replace(/^mod_/, "").slice(0, 8).toUpperCase()}`;

  const campaignId = String(convDoc.campaignId || "").trim();
  const networkId = String(convDoc.networkId || "").trim();
  const userId = String(convDoc.userId || "").trim();
  const state = String(convDoc.state || CAMPAIGN_CONVERSION_STATES.PENDING).trim().toUpperCase();
  const rewardTf = Math.max(0, Math.floor(Number(convDoc.rewardTf || 0)));
  const rewardCents = tfToBalanceCents(rewardTf);
  const rewardCredited = convDoc.rewardCredited === true;

  let recoveredTf = 0;
  let shortfallTf = 0;
  if (state === CAMPAIGN_CONVERSION_STATES.REVERSED && rewardCredited) {
    const split = resolveReversalRecoverySplit(convDoc, rewardTf);
    recoveredTf = split.recoveredTf;
    shortfallTf = split.shortfallTf;
  }

  const userData = userMap.get(userId) || null;
  let expectedRecoverableTf = 0;
  let estimatedShortfallTf = 0;
  if (state === CAMPAIGN_CONVERSION_STATES.APPROVED && rewardCredited && rewardTf > 0) {
    if (userData) {
      const preview = computeCampaignReversedWallet(userData, rewardTf);
      expectedRecoverableTf = Math.max(0, Math.floor(Number(preview.recoveredCoins || 0)));
      estimatedShortfallTf = Math.max(0, Math.floor(Number(preview.shortfallCoins || 0)));
    } else {
      expectedRecoverableTf = 0;
      estimatedShortfallTf = rewardTf;
    }
  }

  const userDisplayName = String(userData?.displayName || "TaskFlow Member").trim() || "TaskFlow Member";

  return {
    moderationId,
    displayRef,
    campaignId,
    campaignTitle: campaignTitleMap.get(campaignId) || campaignId,
    networkId,
    networkName: networkNameMap.get(networkId) || networkId,
    userId,
    userDisplayName,
    verificationMode: String(convDoc.verificationMode || "").trim(),
    state,
    rewardTf,
    rewardCents,
    rewardCredited,
    recoveredTf,
    recoveredCents: tfToBalanceCents(recoveredTf),
    shortfallTf,
    shortfallCents: tfToBalanceCents(shortfallTf),
    expectedRecoverableTf,
    expectedRecoverableCents: tfToBalanceCents(expectedRecoverableTf),
    estimatedShortfallTf,
    estimatedShortfallCents: tfToBalanceCents(estimatedShortfallTf),
    statusReason: String(convDoc.statusReason || "").trim(),
    sourceCallbackType: String(convDoc.sourceCallbackType || "").trim(),
    approvedByAdminId: String(convDoc.approvedByAdminId || "").trim(),
    rejectedByAdminId: String(convDoc.rejectedByAdminId || "").trim(),
    reversedByAdminId: String(convDoc.reversedByAdminId || "").trim(),
    createdAtMillis: toMillisValue(convDoc.createdAt),
    verifiedAtMillis: toMillisValue(convDoc.verifiedAt),
    rejectedAtMillis: toMillisValue(convDoc.rejectedAt),
    reversedAtMillis: toMillisValue(convDoc.reversedAt),
    updatedAtMillis: toMillisValue(convDoc.updatedAt)
  };
}

module.exports = {
  SUPPORTED_SIGNATURE_ALGORITHMS,
  SUPPORTED_HMAC_SIGNING_MODES,
  SUPPORTED_HMAC_ENCODINGS,
  ANALYTICS_TIMEZONE,
  ORPHANED_CAMPAIGN_SENTINEL,
  ORPHANED_NETWORK_SENTINEL,
  parseIsoDateToUtcMillis,
  validateCampaignActivationCompatibility,
  validateAndSanitizeNetworkInput,
  validateAndSanitizeCampaignConfigInput,
  toSafeAdminNetworkDto,
  toSafeAdminCampaignConfigDto,
  toSafeAdminConversionDto,
  resolveAnalyticsDateWindow,
  computeCampaignTrackingAnalytics,
  createCampaignAdminHandlers
};

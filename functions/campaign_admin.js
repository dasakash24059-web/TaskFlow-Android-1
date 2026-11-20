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
  toMillisValue,
  validateDestinationUrl
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

function validateAndSanitizeNetworkInput(data, HttpsErrorCtor) {
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

  const extraStaticParams = {};
  if (rawMap.extraStaticParams && typeof rawMap.extraStaticParams === "object" && !Array.isArray(rawMap.extraStaticParams)) {
    const entries = Object.entries(rawMap.extraStaticParams);
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

  const parameterMapping = {
    clickIdParam,
    userIdParam: sanitizeOptionalParam(rawMap.userIdParam, ""),
    campaignIdParam: sanitizeOptionalParam(rawMap.campaignIdParam, ""),
    externalTxnParam: sanitizeOptionalParam(rawMap.externalTxnParam, "txn_id") || "txn_id",
    statusParam: sanitizeOptionalParam(rawMap.statusParam, "status") || "status",
    payoutParam: sanitizeOptionalParam(rawMap.payoutParam, "payout") || "payout",
    signatureParam: sanitizeOptionalParam(rawMap.signatureParam, "sig") || "sig",
    extraStaticParams
  };

  // Postback status mapping validation
  const postbackStatusMapping = {};
  if (data.postbackStatusMapping && typeof data.postbackStatusMapping === "object" && !Array.isArray(data.postbackStatusMapping)) {
    const entries = Object.entries(data.postbackStatusMapping);
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
      signingMode: String(sig.signingMode || "SORTED_PARAMS"),
      encoding: String(sig.encoding || "hex"),
      signedFields: Array.isArray(sig.signedFields) ? sig.signedFields.map(String) : [],
      fieldSeparator: sig.fieldSeparator !== undefined ? String(sig.fieldSeparator) : "|"
    },
    createdAtMillis: toMillisValue(docData.createdAt),
    updatedAtMillis: toMillisValue(docData.updatedAt)
  };
}

function validateAndSanitizeCampaignConfigInput(data, networkData, existingConfig, HttpsErrorCtor) {
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
  const startAtMillis = Number(data.startAtMillis ?? 0);
  const expiresAtMillis = Number(data.expiresAtMillis ?? 0);
  if (!Number.isFinite(startAtMillis) || startAtMillis < 0 || !Number.isFinite(expiresAtMillis) || expiresAtMillis < 0) {
    throw new HttpsErrorCtor("invalid-argument", "startAtMillis and expiresAtMillis must be non-negative numbers.");
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

  const rawReq = data.conversionRequirements && typeof data.conversionRequirements === "object"
    ? data.conversionRequirements
    : {};
  const attributionWindowHours = Number(rawReq.attributionWindowHours ?? 72);
  if (!Number.isInteger(attributionWindowHours) || attributionWindowHours < 1 || attributionWindowHours > 2160) {
    throw new HttpsErrorCtor("invalid-argument", "attributionWindowHours must be an integer between 1 and 2160.");
  }

  const requireUniqueClickId =
    verificationMode === CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK
      ? true
      : rawReq.requireUniqueClickId !== undefined
        ? Boolean(rawReq.requireUniqueClickId)
        : true;

  const requireAdminApproval =
    verificationMode === CAMPAIGN_VERIFICATION_MODES.MANUAL_PROOF
      ? true
      : Boolean(rawReq.requireAdminApproval);

  const conversionRequirements = {
    requireUniqueClickId,
    requireInstallAttribution: Boolean(rawReq.requireInstallAttribution),
    autoApproveVerifiedPostback: !requireAdminApproval && Boolean(rawReq.autoApproveVerifiedPostback),
    requireAdminApproval,
    allowMultipleConversions: Boolean(rawReq.allowMultipleConversions),
    attributionWindowHours,
    requireVerifiedCountry: Boolean(rawReq.requireVerifiedCountry),
    requireAppCheck: Boolean(rawReq.requireAppCheck)
  };

  const clickTtlMinutes = Number(data.clickTtlMinutes ?? 60);
  if (!Number.isInteger(clickTtlMinutes) || clickTtlMinutes < 1 || clickTtlMinutes > 43200) {
    throw new HttpsErrorCtor("invalid-argument", "clickTtlMinutes must be an integer between 1 and 43200.");
  }

  return {
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
    clickTtlMinutes
  };
}

function toSafeAdminCampaignConfigDto(docData = {}, fallbackId = "") {
  const clean = stripPrivateTrackingFields(docData);
  const reqs = docData.conversionRequirements && typeof docData.conversionRequirements === "object"
    ? docData.conversionRequirements
    : {};
  const rewardTf = Math.max(0, Math.floor(Number(clean.rewardTf || 0)));

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
      autoApproveVerifiedPostback: Boolean(reqs.autoApproveVerifiedPostback),
      requireAdminApproval: Boolean(reqs.requireAdminApproval),
      allowMultipleConversions: Boolean(reqs.allowMultipleConversions),
      attributionWindowHours: Math.max(1, Math.floor(Number(reqs.attributionWindowHours || 72))),
      requireVerifiedCountry: Boolean(reqs.requireVerifiedCountry),
      requireAppCheck: Boolean(reqs.requireAppCheck)
    },
    clickTtlMinutes: Math.max(1, Math.floor(Number(clean.clickTtlMinutes || 60))),
    createdAtMillis: toMillisValue(docData.createdAt),
    updatedAtMillis: toMillisValue(docData.updatedAt)
  };
}

function resolveAnalyticsDateWindow(data = {}, nowMillis = Date.now(), HttpsErrorCtor = Error) {
  const dateRange = String(data.dateRange || "30D").trim().toUpperCase();
  const dayMs = 24 * 60 * 60 * 1000;

  if (dateRange === "ALL") {
    return { dateRange: "ALL", startAtMillis: 0, endAtMillis: nowMillis };
  }
  if (dateRange === "TODAY") {
    const startOfDayUtc = new Date(nowMillis);
    startOfDayUtc.setUTCHours(0, 0, 0, 0);
    return { dateRange: "TODAY", startAtMillis: startOfDayUtc.getTime(), endAtMillis: nowMillis };
  }
  if (dateRange === "7D") {
    return { dateRange: "7D", startAtMillis: nowMillis - 7 * dayMs, endAtMillis: nowMillis };
  }
  if (dateRange === "30D") {
    return { dateRange: "30D", startAtMillis: nowMillis - 30 * dayMs, endAtMillis: nowMillis };
  }
  if (dateRange === "CUSTOM") {
    const startAtMillis = Number(data.startAtMillis || 0);
    const endAtMillis = Number(data.endAtMillis || nowMillis);
    if (!Number.isFinite(startAtMillis) || !Number.isFinite(endAtMillis) || startAtMillis < 0 || endAtMillis < startAtMillis) {
      throw new HttpsErrorCtor("invalid-argument", "Invalid CUSTOM date range window.");
    }
    return { dateRange: "CUSTOM", startAtMillis: Math.floor(startAtMillis), endAtMillis: Math.floor(endAtMillis) };
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

/**
 * Pure server-side reversal-aware analytics aggregator.
 * Clearly distinguishes gross credited TF (`rewardCredited === true`) from reversed TF
 * and net TF after reversals (`grossCreditedTf - reversedTf`, clamped >= 0).
 * Never emits clickId, externalTransactionId, userId, or secret values.
 */
function computeCampaignTrackingAnalytics({
  campaigns = [],
  networks = [],
  clicks = [],
  conversions = [],
  window = { dateRange: "ALL", startAtMillis: 0, endAtMillis: Date.now() },
  pageSize = DEFAULT_PAGE_SIZE,
  campaignCursor = "",
  networkCursor = ""
}) {
  const networkById = new Map();
  for (const n of networks) {
    const id = String(n.networkId || "").trim();
    if (id) networkById.set(id, n);
  }

  const campaignMap = new Map();
  for (const c of campaigns) {
    const id = String(c.campaignId || c.configId || "").trim();
    if (!id) continue;
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
      rewardTf: Math.max(0, Math.floor(Number(c.rewardTf || 0))),
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
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
    networkMap.set(id, {
      networkId: id,
      name: String(n.name || id),
      adapterType: String(n.adapterType || ""),
      isActive: n.isActive === true,
      campaignsCount: 0,
      clicksCount: 0,
      totalConversions: 0,
      pendingConversions: 0,
      approvedConversions: 0,
      rejectedConversions: 0,
      reversedConversions: 0,
      grossCreditedTf: 0,
      reversedTf: 0,
      netCreditedTf: 0
    });
  }

  for (const c of campaignMap.values()) {
    if (c.networkId && networkMap.has(c.networkId)) {
      networkMap.get(c.networkId).campaignsCount += 1;
    }
  }

  let totalClicks = 0;
  for (const clk of clicks) {
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
    if (cId && campaignMap.has(cId)) {
      campaignMap.get(cId).clicksCount += 1;
    }
    if (nId && networkMap.has(nId)) {
      networkMap.get(nId).clicksCount += 1;
    }
  }

  let pendingConversions = 0;
  let approvedConversions = 0;
  let rejectedConversions = 0;
  let reversedConversions = 0;
  let grossCreditedTf = 0;
  let reversedTf = 0;

  // Deduplicate conversion records by conversionId to guarantee no double-counting or double-subtraction
  const seenConversionIds = new Set();
  for (const conv of conversions) {
    const convId = String(conv.conversionId || conv._docId || "").trim();
    if (convId && seenConversionIds.has(convId)) continue;
    if (convId) seenConversionIds.add(convId);

    const convMs = Math.max(
      toMillisValue(conv.createdAt),
      toMillisValue(conv.verifiedAt),
      toMillisValue(conv.updatedAt),
      Number(conv.createdAtMillis || 0)
    );
    if (!isTimestampInRange(convMs, window.startAtMillis, window.endAtMillis, window.dateRange)) {
      continue;
    }

    const state = String(conv.state || "").trim().toUpperCase();
    const rewardTf = Math.max(0, Math.floor(Number(conv.rewardTf || 0)));
    const wasCredited = conv.rewardCredited === true;
    const cId = String(conv.campaignId || "").trim();
    const nId = String(conv.networkId || "").trim();
    const campBucket = cId ? campaignMap.get(cId) : null;
    const netBucket = nId ? networkMap.get(nId) : null;

    if (campBucket) campBucket.totalConversions += 1;
    if (netBucket) netBucket.totalConversions += 1;

    if (state === CAMPAIGN_CONVERSION_STATES.PENDING) {
      pendingConversions += 1;
      if (campBucket) campBucket.pendingConversions += 1;
      if (netBucket) netBucket.pendingConversions += 1;
    } else if (state === CAMPAIGN_CONVERSION_STATES.APPROVED) {
      approvedConversions += 1;
      if (campBucket) campBucket.approvedConversions += 1;
      if (netBucket) netBucket.approvedConversions += 1;
      if (wasCredited) {
        grossCreditedTf += rewardTf;
        if (campBucket) campBucket.grossCreditedTf += rewardTf;
        if (netBucket) netBucket.grossCreditedTf += rewardTf;
      }
    } else if (state === CAMPAIGN_CONVERSION_STATES.REJECTED) {
      rejectedConversions += 1;
      if (campBucket) campBucket.rejectedConversions += 1;
      if (netBucket) netBucket.rejectedConversions += 1;
    } else if (state === CAMPAIGN_CONVERSION_STATES.REVERSED) {
      reversedConversions += 1;
      if (campBucket) campBucket.reversedConversions += 1;
      if (netBucket) netBucket.reversedConversions += 1;
      if (wasCredited) {
        // Reversed conversions had previously been credited; include in gross credited AND reversed so net = gross - reversed.
        grossCreditedTf += rewardTf;
        reversedTf += rewardTf;
        if (campBucket) {
          campBucket.grossCreditedTf += rewardTf;
          campBucket.reversedTf += rewardTf;
        }
        if (netBucket) {
          netBucket.grossCreditedTf += rewardTf;
          netBucket.reversedTf += rewardTf;
        }
      }
    }
  }

  const totalConversions =
    pendingConversions + approvedConversions + rejectedConversions + reversedConversions;
  const netCreditedTf = Math.max(0, grossCreditedTf - reversedTf);

  const campaignRows = Array.from(campaignMap.values())
    .map((row) => ({
      ...row,
      netCreditedTf: Math.max(0, row.grossCreditedTf - row.reversedTf),
      grossCreditedCents: tfToBalanceCents(row.grossCreditedTf),
      netCreditedCents: tfToBalanceCents(Math.max(0, row.grossCreditedTf - row.reversedTf)),
      approvalRatePercent: computeApprovalRatePercent(row.approvedConversions, row.totalConversions)
    }))
    .sort((a, b) => a.campaignId.localeCompare(b.campaignId));

  const networkRows = Array.from(networkMap.values())
    .map((row) => ({
      ...row,
      netCreditedTf: Math.max(0, row.grossCreditedTf - row.reversedTf),
      grossCreditedCents: tfToBalanceCents(row.grossCreditedTf),
      netCreditedCents: tfToBalanceCents(Math.max(0, row.grossCreditedTf - row.reversedTf)),
      approvalRatePercent: computeApprovalRatePercent(row.approvedConversions, row.totalConversions)
    }))
    .sort((a, b) => a.networkId.localeCompare(b.networkId));

  return {
    dateRange: window.dateRange,
    startAtMillis: window.startAtMillis,
    endAtMillis: window.endAtMillis,
    summary: {
      totalCampaigns: campaignMap.size,
      activeCampaigns: Array.from(campaignMap.values()).filter((c) => c.isActive).length,
      totalNetworks: networkMap.size,
      activeNetworks: Array.from(networkMap.values()).filter((n) => n.isActive).length,
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
      netCreditedTf,
      grossCreditedCents: tfToBalanceCents(grossCreditedTf),
      netCreditedCents: tfToBalanceCents(netCreditedTf)
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

async function readCollectionBounded(firestoreDb, collectionName, limitCount = MAX_ANALYTICS_SCAN_LIMIT) {
  const colRef = firestoreDb.collection(collectionName);
  const queryRef = typeof colRef.limit === "function" ? colRef.limit(limitCount) : colRef;
  const snap = await queryRef.get();
  const docs = snap?.docs || [];
  return docs.map((d) => ({
    _docId: d.id,
    ...(typeof d.data === "function" ? d.data() || {} : {})
  }));
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
    const pageSize = clampPageSize(data.pageSize);
    const cursor = String(data.cursor || "").trim();
    const filterAdapterType = String(data.adapterType || "").trim().toUpperCase();
    const filterActive = typeof data.isActive === "boolean" ? data.isActive : null;
    const search = String(data.search || "").trim().toLowerCase();

    if (filterAdapterType && !isValidVerificationMode(filterAdapterType)) {
      throw new HttpsError("invalid-argument", `Invalid adapterType filter '${filterAdapterType}'.`);
    }

    const rawDocs = await readCollectionBounded(
      firestoreDb,
      CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS,
      500
    );

    const filtered = rawDocs
      .map((doc) => toSafeAdminNetworkDto(doc, doc._docId))
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

    const sanitized = validateAndSanitizeNetworkInput(data, HttpsError);
    const networkRef = firestoreDb
      .collection(CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS)
      .doc(sanitized.networkId);
    const logRef = firestoreDb.collection("admin_logs").doc();

    const saved = await firestoreDb.runTransaction(async (transaction) => {
      const existingSnap = await transaction.get(networkRef);
      if (mode === "CREATE" && existingSnap.exists) {
        throw new HttpsError(
          "already-exists",
          `Campaign network '${sanitized.networkId}' already exists.`
        );
      }
      if (mode === "UPDATE" && !existingSnap.exists) {
        throw new HttpsError(
          "not-found",
          `Campaign network '${sanitized.networkId}' does not exist.`
        );
      }
      const existingData = existingSnap.exists ? existingSnap.data() || {} : null;

      const docToWrite = {
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

    const rawDocs = await readCollectionBounded(
      firestoreDb,
      CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS,
      1000
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
      ...page
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
        HttpsError
      );

      // Preserve server-authoritative runtime counters (never let client reset budgets/clicks)
      const usedSubmissions = Math.max(0, Math.floor(Number(existingData?.usedSubmissions || 0)));
      const spentBudgetTf = Math.max(0, Math.floor(Number(existingData?.spentBudgetTf || 0)));
      const reservedClickSlots = Math.max(0, Math.floor(Number(existingData?.reservedClickSlots || 0)));
      const issuedClicksCount = Math.max(0, Math.floor(Number(existingData?.issuedClicksCount || 0)));

      const docToWrite = {
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
        if (!networkSnap.exists || networkSnap.data()?.isActive !== true) {
          throw new HttpsError(
            "failed-precondition",
            `Cannot activate campaign while network '${networkId}' is missing or disabled.`
          );
        }
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

    const [campaigns, networks, clicks, conversions] = await Promise.all([
      readCollectionBounded(firestoreDb, CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_TRACKING_CONFIGS, 1000),
      readCollectionBounded(firestoreDb, CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_NETWORKS, 500),
      readCollectionBounded(firestoreDb, CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CLICKS, MAX_ANALYTICS_SCAN_LIMIT),
      readCollectionBounded(firestoreDb, CAMPAIGN_TRACKING_COLLECTIONS.CAMPAIGN_CONVERSIONS, MAX_ANALYTICS_SCAN_LIMIT)
    ]);

    const analytics = computeCampaignTrackingAnalytics({
      campaigns,
      networks,
      clicks,
      conversions,
      window,
      pageSize,
      campaignCursor,
      networkCursor
    });

    return {
      success: true,
      ...analytics,
      scanLimits: {
        clicksScanLimit: MAX_ANALYTICS_SCAN_LIMIT,
        conversionsScanLimit: MAX_ANALYTICS_SCAN_LIMIT,
        clicksTruncated: clicks.length >= MAX_ANALYTICS_SCAN_LIMIT,
        conversionsTruncated: conversions.length >= MAX_ANALYTICS_SCAN_LIMIT
      }
    };
  }

  return {
    handleAdminListCampaignNetworks,
    handleAdminUpsertCampaignNetwork,
    handleAdminSetCampaignNetworkStatus,
    handleAdminListCampaignTrackingConfigs,
    handleAdminUpsertCampaignTrackingConfig,
    handleAdminSetCampaignTrackingStatus,
    handleAdminGetCampaignTrackingDashboard
  };
}

module.exports = {
  SUPPORTED_SIGNATURE_ALGORITHMS,
  SUPPORTED_HMAC_SIGNING_MODES,
  SUPPORTED_HMAC_ENCODINGS,
  validateAndSanitizeNetworkInput,
  validateAndSanitizeCampaignConfigInput,
  toSafeAdminNetworkDto,
  toSafeAdminCampaignConfigDto,
  resolveAnalyticsDateWindow,
  computeCampaignTrackingAnalytics,
  createCampaignAdminHandlers
};

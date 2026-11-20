/**
 * Universal Campaign Tracking Engine — Phase 3A + 3B Foundation & Click Issuance Helpers.
 *
 * Implements Phase 3A schemas, verification modes, conversion states, eligibility checks,
 * secret stripping, extensible network adapter registry, plus Phase 3B cryptographically
 * secure click ID generation, destination URL anti-injection/anti-open-redirect validation,
 * and network-specific outbound tracking URL building.
 *
 * Does NOT process postbacks, approve conversions, or credit TF rewards (Phase 3C).
 */

const crypto = require("crypto");

const CAMPAIGN_TRACKING_COLLECTIONS = Object.freeze({
  CAMPAIGN_TRACKING_CONFIGS: "campaign_tracking_configs",
  CAMPAIGN_CLICKS: "campaign_clicks",
  CAMPAIGN_CONVERSIONS: "campaign_conversions",
  CAMPAIGN_NETWORKS: "campaign_networks",
  CAMPAIGN_CLICK_GUARDS: "campaign_click_guards"
});

const CAMPAIGN_VERIFICATION_MODES = Object.freeze({
  AFFILIATE_POSTBACK: "AFFILIATE_POSTBACK",
  ADVERTISER_S2S_API: "ADVERTISER_S2S_API",
  TELEGRAM_BOT_API: "TELEGRAM_BOT_API",
  MANUAL_PROOF: "MANUAL_PROOF",
  CUSTOM_APPROVED: "CUSTOM_APPROVED"
});

const CAMPAIGN_CONVERSION_STATES = Object.freeze({
  PENDING: "PENDING",
  APPROVED: "APPROVED",
  REJECTED: "REJECTED",
  REVERSED: "REVERSED"
});

const ALLOWED_PLATFORMS = new Set(["ANDROID", "IOS", "WEB", "ALL"]);
const CLIENT_DEVICE_PLATFORMS = new Set(["ANDROID", "IOS", "WEB"]);
const VALID_ID_REGEX = /^[a-zA-Z0-9_-]{1,128}$/;
const VALID_PARAM_KEY_REGEX = /^[a-zA-Z0-9_.-]{1,64}$/;
const VALID_COUNTRY_REGEX = /^(ALL|[A-Z]{2})$/;
const STRICT_COUNTRY_REGEX = /^[A-Z]{2}$/;

const SECRET_KEY_NAMES = new Set([
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
]);

function isValidVerificationMode(mode) {
  return Object.values(CAMPAIGN_VERIFICATION_MODES).includes(String(mode || "").trim());
}

function isValidConversionState(state) {
  return Object.values(CAMPAIGN_CONVERSION_STATES).includes(String(state || "").trim());
}

function canTransitionConversionState(currentState, nextState) {
  const from = String(currentState || "").trim();
  const to = String(nextState || "").trim();
  if (from === CAMPAIGN_CONVERSION_STATES.PENDING) {
    return to === CAMPAIGN_CONVERSION_STATES.APPROVED || to === CAMPAIGN_CONVERSION_STATES.REJECTED;
  }
  if (from === CAMPAIGN_CONVERSION_STATES.APPROVED) {
    return to === CAMPAIGN_CONVERSION_STATES.REVERSED;
  }
  return false;
}

function isSecretKeyName(key) {
  const clean = String(key || "").trim();
  const lower = clean.toLowerCase();
  return (
    SECRET_KEY_NAMES.has(clean) ||
    lower.includes("secret") ||
    lower.includes("apikey") ||
    lower.includes("api_key") ||
    lower.includes("token") ||
    lower.includes("private")
  );
}

/**
 * Strips any private postback secrets or internal secret references before
 * returning tracking metadata to Admin UI summaries or client projections.
 */
function stripPrivateTrackingFields(doc) {
  if (!doc || typeof doc !== "object" || Array.isArray(doc)) {
    return {};
  }
  const clean = {};
  for (const [key, value] of Object.entries(doc)) {
    if (isSecretKeyName(key)) {
      continue;
    }
    clean[key] = value;
  }
  return clean;
}

/**
 * Generates a cryptographically secure, unpredictable click ID (>= 128 bits entropy).
 */
function generateSecureClickId() {
  return `clk_${crypto.randomBytes(16).toString("hex")}`;
}

function toMillisValue(val) {
  if (val === null || val === undefined) return 0;
  if (typeof val === "number" && Number.isFinite(val)) return val;
  if (val instanceof Date) return val.getTime();
  if (typeof val.toMillis === "function") return val.toMillis();
  if (typeof val.toDate === "function") return val.toDate().getTime();
  if (typeof val._seconds === "number") {
    return val._seconds * 1000 + Math.floor((val._nanoseconds || 0) / 1e6);
  }
  if (typeof val.seconds === "number") {
    return val.seconds * 1000 + Math.floor((val.nanoseconds || 0) / 1e6);
  }
  return 0;
}

function isHostMatchesAllowlist(hostname, allowedHosts) {
  if (!Array.isArray(allowedHosts) || allowedHosts.length === 0) {
    return true;
  }
  const normalizedHost = String(hostname || "").trim().toLowerCase();
  return allowedHosts.some((rawAllowed) => {
    const allowed = String(rawAllowed || "").trim().toLowerCase().replace(/^\.+/, "");
    if (!allowed || allowed.length > 253) return false;
    return normalizedHost === allowed || normalizedHost.endsWith(`.${allowed}`);
  });
}

/**
 * Strictly validates an external campaign destination URL to block open redirects,
 * non-HTTPS schemes, embedded credentials, local/private network hosts, and template injections.
 */
function validateDestinationUrl(rawUrl, allowedHosts = []) {
  if (typeof rawUrl !== "string") {
    return { valid: false, reason: "Destination URL must be a string." };
  }
  const trimmed = rawUrl.trim();
  if (!trimmed || trimmed.length > 2000) {
    return { valid: false, reason: "Destination URL is missing or exceeds max length." };
  }
  if (/[\s\x00-\x1f\x7f<>]/.test(trimmed)) {
    return { valid: false, reason: "Destination URL contains invalid whitespace or control characters." };
  }

  let parsed;
  try {
    parsed = new URL(trimmed);
  } catch (_) {
    return { valid: false, reason: "Destination URL is malformed." };
  }

  if (parsed.protocol !== "https:") {
    return { valid: false, reason: "Destination URL must strictly use the HTTPS protocol." };
  }
  if (parsed.username || parsed.password) {
    return { valid: false, reason: "Destination URL must not contain embedded credentials." };
  }

  const host = (parsed.hostname || "").toLowerCase().replace(/\.+$/, "");
  if (!host || !host.includes(".")) {
    return { valid: false, reason: "Destination URL must contain a valid public domain." };
  }
  if (
    host === "localhost" ||
    host.endsWith(".localhost") ||
    host.endsWith(".local") ||
    host.endsWith(".internal") ||
    /[{}[\]]/.test(host) ||
    host.includes(":") ||
    /^\d{1,3}(\.\d{1,3}){3}$/.test(host)
  ) {
    return { valid: false, reason: "Destination URL host is unsafe or points to a local/IP address." };
  }

  if (!isHostMatchesAllowlist(host, allowedHosts)) {
    return { valid: false, reason: "Destination URL host is not on the approved allowlist." };
  }

  return { valid: true, url: parsed };
}

function validateCampaignEligibility(config) {
  const errors = [];
  if (!config || typeof config !== "object") {
    return ["Campaign tracking configuration object is required."];
  }
  if (typeof config.isActive !== "boolean") {
    errors.push("isActive must be a boolean.");
  }
  if (!Number.isInteger(config.rewardTf) || config.rewardTf <= 0) {
    errors.push("rewardTf must be a positive integer.");
  }
  if (!Number.isInteger(config.submissionBudget) || config.submissionBudget < 0) {
    errors.push("submissionBudget must be a non-negative integer.");
  }
  if (!Number.isInteger(config.usedSubmissions || 0) || (config.usedSubmissions || 0) < 0) {
    errors.push("usedSubmissions must be a non-negative integer.");
  }
  if (config.submissionBudget > 0 && (config.usedSubmissions || 0) > config.submissionBudget) {
    errors.push("usedSubmissions cannot exceed submissionBudget.");
  }
  const startAtMillis = Math.max(Number(config.startAtMillis || 0), toMillisValue(config.startAt));
  const expiresAtMillis = Math.max(Number(config.expiresAtMillis || 0), toMillisValue(config.expiresAt));
  if (startAtMillis < 0 || expiresAtMillis < 0) {
    errors.push("startAtMillis and expiresAtMillis must be non-negative.");
  }
  if (startAtMillis > 0 && expiresAtMillis > 0 && expiresAtMillis < startAtMillis) {
    errors.push("expiresAtMillis must be >= startAtMillis.");
  }
  if (!isValidVerificationMode(config.verificationMode)) {
    errors.push("verificationMode is invalid.");
  }
  if (!VALID_ID_REGEX.test(String(config.networkId || ""))) {
    errors.push("networkId must be a valid identifier (1-128 safe chars).");
  }
  if (!String(config.trackingNetworkRef || "").trim()) {
    errors.push("trackingNetworkRef is required.");
  }
  const countries = Array.isArray(config.allowedCountries) ? config.allowedCountries : [];
  if (
    countries.length === 0 ||
    countries.length > 50 ||
    countries.some((c) => !VALID_COUNTRY_REGEX.test(String(c || "").trim().toUpperCase()))
  ) {
    errors.push("allowedCountries must be 1-50 valid ISO alpha-2 codes or 'ALL'.");
  }
  const platforms = Array.isArray(config.allowedPlatforms) ? config.allowedPlatforms : [];
  if (
    platforms.length === 0 ||
    platforms.length > 10 ||
    platforms.some((p) => !ALLOWED_PLATFORMS.has(String(p || "").trim().toUpperCase()))
  ) {
    errors.push("allowedPlatforms must be 1-10 valid platforms.");
  }
  return errors;
}

function isCampaignUserEligible(config, { countryCode = "IN", platform = "ANDROID", nowMillis = Date.now() } = {}) {
  if (!config || config.isActive !== true) return false;
  if (validateCampaignEligibility(config).length > 0) return false;

  const startAtMillis = Math.max(Number(config.startAtMillis || 0), toMillisValue(config.startAt));
  const expiresAtMillis = Math.max(Number(config.expiresAtMillis || 0), toMillisValue(config.expiresAt));
  if (startAtMillis > 0 && nowMillis < startAtMillis) return false;
  if (expiresAtMillis > 0 && nowMillis > expiresAtMillis) return false;

  if (config.submissionBudget > 0 && (config.usedSubmissions || 0) >= config.submissionBudget) {
    return false;
  }
  if (
    Number.isInteger(config.rewardBudgetTf) &&
    config.rewardBudgetTf > 0 &&
    (config.spentBudgetTf || 0) + config.rewardTf > config.rewardBudgetTf
  ) {
    return false;
  }

  const countries = config.allowedCountries.map((c) => String(c).trim().toUpperCase());
  const normalizedCountry = String(countryCode || "").trim().toUpperCase();
  if (!countries.includes("ALL") && !countries.includes(normalizedCountry)) {
    return false;
  }

  const platforms = config.allowedPlatforms.map((p) => String(p).trim().toUpperCase());
  const normalizedPlatform = String(platform || "").trim().toUpperCase();
  if (!platforms.includes("ALL") && !platforms.includes(normalizedPlatform)) {
    return false;
  }

  return true;
}

/**
 * Resolves country solely from trusted infrastructure headers (`x-appengine-country`,
 * or `cf-ipcountry` ONLY when explicitly enabled for Cloudflare-fronted ingress).
 * Never trusts client JSON payload `countryCode` or arbitrary `x-country-code` headers,
 * and never defaults to a permissive country such as "IN".
 */
function resolveCountryTrustContext(rawHeaders = {}, options = {}) {
  const normalizedHeaders = {};
  for (const [k, v] of Object.entries(rawHeaders || {})) {
    normalizedHeaders[String(k || "").toLowerCase()] = v;
  }

  const appEngineCountry = String(normalizedHeaders["x-appengine-country"] || "")
    .trim()
    .toUpperCase();
  if (
    STRICT_COUNTRY_REGEX.test(appEngineCountry) &&
    appEngineCountry !== "XX" &&
    appEngineCountry !== "T1"
  ) {
    return {
      countryCode: appEngineCountry,
      source: "x-appengine-country",
      trustLevel: "PROXY_HEADER"
    };
  }

  const trustCf =
    options?.trustCloudflareHeaders === true ||
    process.env.TRUST_CLOUDFLARE_HEADERS === "true";
  if (trustCf) {
    const cfCountry = String(normalizedHeaders["cf-ipcountry"] || "")
      .trim()
      .toUpperCase();
    if (
      STRICT_COUNTRY_REGEX.test(cfCountry) &&
      cfCountry !== "XX" &&
      cfCountry !== "T1"
    ) {
      return {
        countryCode: cfCountry,
        source: "cf-ipcountry",
        trustLevel: "CLOUDFLARE_PROXY_HEADER"
      };
    }
  }

  return {
    countryCode: null,
    source: "none",
    trustLevel: "UNVERIFIED"
  };
}

function resolveCountryCodeFromRequest(requestData, rawHeaders = {}, options = {}) {
  return resolveCountryTrustContext(rawHeaders, options).countryCode;
}

/**
 * Resolves client-declared platform plus whether Firebase App Check attested the caller.
 */
function resolveDeviceTrustContext(requestData, appCheckToken = null) {
  const raw = requestData?.devicePlatform;
  const hasAppCheck = Boolean(appCheckToken && appCheckToken.appId);
  const trustLevel = hasAppCheck ? "APP_CHECK_VERIFIED" : "SELF_DECLARED_UNVERIFIED";
  if (raw === undefined || raw === null || String(raw).trim() === "") {
    return { platform: "ANDROID", trustLevel };
  }
  const normalized = String(raw).trim().toUpperCase();
  return {
    platform: CLIENT_DEVICE_PLATFORMS.has(normalized) ? normalized : null,
    trustLevel
  };
}

function resolveDevicePlatformFromRequest(requestData) {
  return resolveDeviceTrustContext(requestData, null).platform;
}

function normalizeCallbackStatus(rawStatus, statusMapping = {}) {
  const cleaned = String(rawStatus || "").trim();
  if (!cleaned) return CAMPAIGN_CONVERSION_STATES.PENDING;
  for (const [k, v] of Object.entries(statusMapping || {})) {
    if (k.toLowerCase() === cleaned.toLowerCase() && isValidConversionState(v)) {
      return v;
    }
  }
  switch (cleaned.toUpperCase()) {
    case "APPROVED":
    case "CONFIRMED":
    case "COMPLETED":
    case "SUCCESS":
    case "PAID":
    case "1":
      return CAMPAIGN_CONVERSION_STATES.APPROVED;
    case "REJECTED":
    case "DECLINED":
    case "FAILED":
    case "INVALID":
    case "FRAUD":
    case "0":
      return CAMPAIGN_CONVERSION_STATES.REJECTED;
    case "REVERSED":
    case "CHARGEBACK":
    case "CANCELLED":
    case "REFUNDED":
    case "-1":
      return CAMPAIGN_CONVERSION_STATES.REVERSED;
    default:
      return CAMPAIGN_CONVERSION_STATES.PENDING;
  }
}

class BaseNetworkAdapter {
  constructor(adapterId, verificationMode, capabilities) {
    this.adapterId = adapterId;
    this.verificationMode = verificationMode;
    this.capabilities = Object.freeze({ ...capabilities });
  }

  buildTrackingParameters(config, network, context) {
    const mapping = (network && network.parameterMapping) || {};
    const clickParam = String(mapping.clickIdParam || "click_id").trim() || "click_id";
    const params = {};

    const candidates = {
      ...(mapping.extraStaticParams || {}),
      ...(config?.parameterOverrides || {}),
      ...(mapping.userIdParam && context.userId ? { [mapping.userIdParam]: String(context.userId) } : {}),
      ...(mapping.campaignIdParam && context.campaignId ? { [mapping.campaignIdParam]: String(context.campaignId) } : {}),
      [clickParam]: String(context.clickId || "")
    };

    for (const [rawKey, rawVal] of Object.entries(candidates)) {
      const key = String(rawKey || "").trim();
      if (!key || !VALID_PARAM_KEY_REGEX.test(key) || isSecretKeyName(key)) {
        continue;
      }
      const val = String(rawVal ?? "").trim();
      if (val.length <= 256 && !/[\r\n\x00]/.test(val)) {
        params[key] = val;
      }
    }
    return params;
  }

  normalizeConversionPayload(rawInput, network) {
    const mapping = (network && network.parameterMapping) || {};
    const query = (rawInput && rawInput.query) || {};
    const body = (rawInput && rawInput.body) || {};
    const headers = (rawInput && rawInput.headers) || {};
    const merged = { ...query, ...body };
    const clickId = String(merged[mapping.clickIdParam || "click_id"] || "").trim();
    const externalTransactionId = String(merged[mapping.externalTxnParam || "txn_id"] || "").trim();
    const rawStatus = String(merged[mapping.statusParam || "status"] || "").trim();
    const payoutRaw = String(merged[mapping.payoutParam || "payout"] || "").trim();
    const sigHeader = network.signatureConfig?.headerName || "";
    const sigParam = network.signatureConfig?.queryParamName || mapping.signatureParam || "sig";
    const signatureValue = String(
      merged[sigParam] || (sigHeader ? headers[sigHeader] || headers[sigHeader.toLowerCase()] || "" : "")
    ).trim();

    return {
      clickId,
      externalTransactionId,
      rawStatus,
      normalizedState: normalizeCallbackStatus(rawStatus, network.postbackStatusMapping),
      payoutRaw,
      signatureValue,
      networkId: network.networkId,
      verificationMode: this.verificationMode
    };
  }
}

class CampaignNetworkAdapterRegistry {
  constructor() {
    this.byMode = new Map();
    this.byId = new Map();

    this.register(
      new BaseNetworkAdapter("configurable_affiliate_postback", CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK, {
        supportsIncentivizedTraffic: false,
        supportsPostback: true,
        supportsInstallAttribution: true,
        supportsServerToServerPolling: false,
        requiresManualReview: false
      })
    );
    this.register(
      new BaseNetworkAdapter("advertiser_s2s_api", CAMPAIGN_VERIFICATION_MODES.ADVERTISER_S2S_API, {
        supportsIncentivizedTraffic: true,
        supportsPostback: true,
        supportsInstallAttribution: true,
        supportsServerToServerPolling: true,
        requiresManualReview: false
      })
    );
    this.register(
      new BaseNetworkAdapter("telegram_bot_api", CAMPAIGN_VERIFICATION_MODES.TELEGRAM_BOT_API, {
        supportsIncentivizedTraffic: true,
        supportsPostback: false,
        supportsInstallAttribution: false,
        supportsServerToServerPolling: true,
        requiresManualReview: false
      })
    );
    this.register(
      new BaseNetworkAdapter("manual_proof", CAMPAIGN_VERIFICATION_MODES.MANUAL_PROOF, {
        supportsIncentivizedTraffic: true,
        supportsPostback: false,
        supportsInstallAttribution: false,
        supportsServerToServerPolling: false,
        requiresManualReview: true
      })
    );
    this.register(
      new BaseNetworkAdapter("custom_approved", CAMPAIGN_VERIFICATION_MODES.CUSTOM_APPROVED, {
        supportsIncentivizedTraffic: true,
        supportsPostback: true,
        supportsInstallAttribution: false,
        supportsServerToServerPolling: true,
        requiresManualReview: true
      })
    );
  }

  register(adapter, customKey = null) {
    if (!customKey && adapter.verificationMode && !this.byMode.has(adapter.verificationMode)) {
      this.byMode.set(adapter.verificationMode, adapter);
    }
    const key = String(customKey || adapter.adapterId || "").trim().toLowerCase();
    if (key) {
      this.byId.set(key, adapter);
    }
  }

  resolve(verificationMode, adapterOrNetworkId = "") {
    const specificKey = String(adapterOrNetworkId || "").trim().toLowerCase();
    if (specificKey && this.byId.has(specificKey)) {
      return this.byId.get(specificKey);
    }
    const adapter = this.byMode.get(verificationMode);
    if (!adapter) {
      throw new Error(`Unsupported verification mode: ${verificationMode}`);
    }
    return adapter;
  }
}

/**
 * Validates `rawBaseUrl` and applies adapter-resolved tracking parameters & safe query macros
 * while guaranteeing host/origin immutability and preventing open redirects or injection.
 */
function buildSafeTrackingUrl(rawBaseUrl, config, network, context, registry = new CampaignNetworkAdapterRegistry()) {
  const allowedHosts = [
    ...(Array.isArray(network?.allowedDestinationHosts) ? network.allowedDestinationHosts : []),
    ...(Array.isArray(config?.allowedDestinationHosts) ? config.allowedDestinationHosts : [])
  ].filter(Boolean);

  const initialValidation = validateDestinationUrl(rawBaseUrl, allowedHosts);
  if (!initialValidation.valid) {
    throw new Error(initialValidation.reason);
  }

  const originalUrl = initialValidation.url;
  const builtUrl = new URL(originalUrl.toString());
  const adapter = registry.resolve(config.verificationMode, network?.adapterId || network?.networkId);
  const resolvedParameters = adapter.buildTrackingParameters(config, network, context);

  const macroMap = {
    "{click_id}": String(context.clickId || ""),
    "{{click_id}}": String(context.clickId || ""),
    "{clickId}": String(context.clickId || ""),
    "{user_id}": String(context.userId || ""),
    "{userId}": String(context.userId || ""),
    "{campaign_id}": String(context.campaignId || ""),
    "{campaignId}": String(context.campaignId || ""),
    "{country}": String(context.countryCode || ""),
    "{platform}": String(context.devicePlatform || "")
  };

  for (const [paramKey, paramValue] of Array.from(builtUrl.searchParams.entries())) {
    if (isSecretKeyName(paramKey)) {
      builtUrl.searchParams.delete(paramKey);
      continue;
    }
    let nextVal = paramValue;
    for (const [token, replacement] of Object.entries(macroMap)) {
      if (nextVal.includes(token)) {
        nextVal = nextVal.split(token).join(replacement);
      }
    }
    builtUrl.searchParams.set(paramKey, nextVal);
  }

  for (const [paramKey, paramValue] of Object.entries(resolvedParameters)) {
    builtUrl.searchParams.set(paramKey, paramValue);
  }

  if (builtUrl.origin !== originalUrl.origin || builtUrl.pathname !== originalUrl.pathname) {
    throw new Error("Tracking parameter construction attempted to mutate destination origin or path.");
  }

  const finalString = builtUrl.toString();
  const finalValidation = validateDestinationUrl(finalString, allowedHosts);
  if (!finalValidation.valid) {
    throw new Error(finalValidation.reason);
  }

  return {
    trackingUrl: finalString,
    resolvedParameters
  };
}

module.exports = {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  isValidVerificationMode,
  isValidConversionState,
  canTransitionConversionState,
  isSecretKeyName,
  stripPrivateTrackingFields,
  generateSecureClickId,
  toMillisValue,
  validateDestinationUrl,
  validateCampaignEligibility,
  isCampaignUserEligible,
  resolveCountryTrustContext,
  resolveCountryCodeFromRequest,
  resolveDeviceTrustContext,
  resolveDevicePlatformFromRequest,
  normalizeCallbackStatus,
  buildSafeTrackingUrl,
  BaseNetworkAdapter,
  CampaignNetworkAdapterRegistry
};

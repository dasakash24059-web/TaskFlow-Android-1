const assert = require("assert");
const { test } = require("node:test");
const {
  CAMPAIGN_TRACKING_COLLECTIONS,
  CAMPAIGN_VERIFICATION_MODES,
  CAMPAIGN_CONVERSION_STATES,
  isValidVerificationMode,
  isValidConversionState,
  canTransitionConversionState,
  stripPrivateTrackingFields,
  validateCampaignEligibility,
  isCampaignUserEligible,
  CampaignNetworkAdapterRegistry
} = require("./campaign_tracking");

test("Phase 3A schema constants define all required collections, verification modes, and conversion states", () => {
  assert.deepStrictEqual(Object.values(CAMPAIGN_TRACKING_COLLECTIONS).sort(), [
    "campaign_click_guards",
    "campaign_clicks",
    "campaign_conversions",
    "campaign_networks",
    "campaign_tracking_configs"
  ]);

  assert.deepStrictEqual(Object.values(CAMPAIGN_VERIFICATION_MODES).sort(), [
    "ADVERTISER_S2S_API",
    "AFFILIATE_POSTBACK",
    "CUSTOM_APPROVED",
    "MANUAL_PROOF",
    "TELEGRAM_BOT_API"
  ]);

  assert.deepStrictEqual(Object.values(CAMPAIGN_CONVERSION_STATES).sort(), [
    "APPROVED",
    "PENDING",
    "REJECTED",
    "REVERSED"
  ]);

  assert.strictEqual(isValidVerificationMode("AFFILIATE_POSTBACK"), true);
  assert.strictEqual(isValidVerificationMode("UNKNOWN_MODE"), false);
  assert.strictEqual(isValidConversionState("REVERSED"), true);
  assert.strictEqual(isValidConversionState("CREDITED"), false);
});

test("Conversion state machine permits valid lifecycle transitions and blocks invalid shortcuts", () => {
  assert.strictEqual(canTransitionConversionState("PENDING", "APPROVED"), true);
  assert.strictEqual(canTransitionConversionState("PENDING", "REJECTED"), true);
  assert.strictEqual(canTransitionConversionState("PENDING", "REVERSED"), false);
  assert.strictEqual(canTransitionConversionState("APPROVED", "REVERSED"), true);
  assert.strictEqual(canTransitionConversionState("REJECTED", "APPROVED"), false);
  assert.strictEqual(canTransitionConversionState("REVERSED", "APPROVED"), false);
});

test("Eligibility validator and user eligibility evaluation enforce schedule, budget, countries, platforms, and TF reward", () => {
  const now = 1_750_000_000_000;
  const config = {
    isActive: true,
    startAtMillis: now - 10_000,
    expiresAtMillis: now + 60_000,
    submissionBudget: 50,
    usedSubmissions: 10,
    rewardBudgetTf: 25000,
    spentBudgetTf: 5000,
    allowedCountries: ["IN"],
    allowedPlatforms: ["ANDROID"],
    rewardTf: 500,
    verificationMode: CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK,
    networkId: "net_alpha",
    trackingNetworkRef: "campaign_networks/net_alpha"
  };

  assert.deepStrictEqual(validateCampaignEligibility(config), []);
  assert.strictEqual(
    isCampaignUserEligible(config, { countryCode: "IN", platform: "ANDROID", nowMillis: now }),
    true
  );
  assert.strictEqual(
    isCampaignUserEligible(config, { countryCode: "US", platform: "ANDROID", nowMillis: now }),
    false
  );
  assert.strictEqual(
    isCampaignUserEligible(config, { countryCode: "IN", platform: "IOS", nowMillis: now }),
    false
  );
  assert.strictEqual(
    isCampaignUserEligible({ ...config, isActive: false }, { countryCode: "IN", platform: "ANDROID", nowMillis: now }),
    false
  );
  assert.strictEqual(
    isCampaignUserEligible({ ...config, usedSubmissions: 50 }, { countryCode: "IN", platform: "ANDROID", nowMillis: now }),
    false
  );
});

test("stripPrivateTrackingFields removes all postback secrets and internal secret refs", () => {
  const raw = {
    configId: "camp_1",
    campaignId: "camp_1",
    isActive: true,
    rewardTf: 375,
    verificationMode: "AFFILIATE_POSTBACK",
    networkId: "net_1",
    postbackSecret: "super-secret-hmac",
    postbackSecretHash: "sha256-hash",
    hmacSecret: "hmac-123",
    apiKey: "key-123",
    apiSecret: "secret-456",
    webhookSecret: "wh-789",
    privateSecretRef: "ENV_SECRET_KEY",
    botToken: "bot:token"
  };

  const sanitized = stripPrivateTrackingFields(raw);
  assert.strictEqual(sanitized.configId, "camp_1");
  assert.strictEqual(sanitized.rewardTf, 375);
  assert.strictEqual("postbackSecret" in sanitized, false);
  assert.strictEqual("postbackSecretHash" in sanitized, false);
  assert.strictEqual("hmacSecret" in sanitized, false);
  assert.strictEqual("apiKey" in sanitized, false);
  assert.strictEqual("apiSecret" in sanitized, false);
  assert.strictEqual("webhookSecret" in sanitized, false);
  assert.strictEqual("privateSecretRef" in sanitized, false);
  assert.strictEqual("botToken" in sanitized, false);
});

test("Extensible adapter registry handles diverse affiliate network parameter & postback schemas", () => {
  const registry = new CampaignNetworkAdapterRegistry();

  const networkOne = {
    networkId: "trackier_in",
    parameterMapping: {
      clickIdParam: "click_id",
      externalTxnParam: "txn",
      statusParam: "status",
      payoutParam: "payout",
      signatureParam: "sig"
    },
    postbackStatusMapping: { approved_install: "APPROVED" },
    signatureConfig: { queryParamName: "sig" }
  };

  const adapter = registry.resolve(CAMPAIGN_VERIFICATION_MODES.AFFILIATE_POSTBACK);
  const params = adapter.buildTrackingParameters({ parameterOverrides: { source: "taskflow" } }, networkOne, {
    clickId: "clk_rand_999"
  });
  assert.deepStrictEqual(params, {
    click_id: "clk_rand_999",
    source: "taskflow"
  });

  const normalized = adapter.normalizeConversionPayload(
    {
      query: {
        click_id: "clk_rand_999",
        txn: "tx_77",
        status: "approved_install",
        sig: "hmac_hex"
      }
    },
    networkOne
  );
  assert.strictEqual(normalized.clickId, "clk_rand_999");
  assert.strictEqual(normalized.externalTransactionId, "tx_77");
  assert.strictEqual(normalized.normalizedState, "APPROVED");
  assert.strictEqual(normalized.signatureValue, "hmac_hex");
});

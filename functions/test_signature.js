const crypto = require("crypto");
const assert = require("assert");
const { _verifyUnityS2SSignature } = require("./index.js");

const secret = "test_unity_secret_hash_123";

// Official Unity S2S parameter format: sorted key=value pairs joined by commas, excluding hmac
const params = {
  adid: "800430673",
  oid: "tx_987654321",
  sid: "a1b2c3d4e5f60718293a4b5c6d7e8f90"
};

const sortedParamString = Object.keys(params)
  .sort()
  .map((k) => `${k}=${params[k]}`)
  .join(",");

const validHmac = crypto.createHmac("md5", secret).update(sortedParamString).digest("hex");

// Test Case 1: Valid Official HMAC-MD5 Signature
assert.strictEqual(
  _verifyUnityS2SSignature({ ...params, hmac: validHmac }, secret),
  true,
  "Expected valid Unity S2S HMAC-MD5 signature to pass verification"
);

// Test Case 2: Invalid Secret
assert.strictEqual(
  _verifyUnityS2SSignature({ ...params, hmac: validHmac }, "wrong_secret"),
  false,
  "Expected wrong secret to fail verification"
);

// Test Case 3: Tampered Parameter (sid modified)
assert.strictEqual(
  _verifyUnityS2SSignature({ ...params, sid: "tampered_session_id", hmac: validHmac }, secret),
  false,
  "Expected tampered sid parameter to fail verification"
);

// Test Case 4: Malformed non-hex HMAC
assert.strictEqual(
  _verifyUnityS2SSignature({ ...params, hmac: "not_a_valid_md5_hex" }, secret),
  false,
  "Expected malformed HMAC to fail verification"
);

console.log("All official Unity S2S HMAC-MD5 signature tests passed!");

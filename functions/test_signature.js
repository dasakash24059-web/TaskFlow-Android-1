const crypto = require("crypto");
const assert = require("assert");

function verifySignature(params, secret, providedSignature) {
  const keys = Object.keys(params).filter(k => k !== 'hmac').sort();
  const paramString = keys.map(k => params[k]).join(',');
  const hmacObj = crypto.createHmac("md5", secret);
  hmacObj.update(paramString);
  const calculated = hmacObj.digest("hex");
  
  const sigBuf = Buffer.from(providedSignature, 'hex');
  const calcBuf = Buffer.from(calculated, 'hex');
  return sigBuf.length === calcBuf.length && crypto.timingSafeEqual(sigBuf, calcBuf);
}

// Test Case 1: Valid Signature
const secret = "test_secret";
const params = { userId: "user1", rewardPoints: "2", oid: "tx123" };
const paramString = Object.keys(params).sort().map(k => params[k]).join(',');
const signature = crypto.createHmac("md5", secret).update(paramString).digest("hex");
params.hmac = signature;

const { hmac, ...rest } = params;
assert.strictEqual(verifySignature(rest, secret, hmac), true, "Signature should be valid");

// Test Case 2: Invalid Signature
assert.strictEqual(verifySignature(rest, "wrong_secret", hmac), false, "Signature should be invalid with wrong secret");

console.log("Tests passed!");

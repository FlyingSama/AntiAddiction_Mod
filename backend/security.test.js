/* eslint-disable no-console */
'use strict';

const assert = require('assert');
const crypto = require('crypto');

const {
  normalizeName,
  normalizeIdCard,
  hmacDigest,
  canonicalStringify,
  signPayloadBytes,
  verifyPayloadBytes,
  createCredentialPayload,
  createRulesPayload,
  createAdminUserView,
  validateRuntimeConfig,
  deriveStorageKey,
} = require('./security');

function testCredentialUsesDigestsOnly() {
  const payload = createCredentialPayload({
    issuer: 'antiaddiction-backend',
    audience: 'antiaddiction-mod',
    kid: 'prod-1',
    serverSecret: 'server-secret',
    name: ' 张 三 ',
    idCard: ' 11010520000101123x ',
    minecraftUuid: '00000000-0000-0000-0000-000000000001',
    minecraftName: 'PlayerOne',
    clientVersion: '1.0.0',
    minor: false,
    age: 26,
    policyVersion: 7,
    nowMillis: 1767225600000,
    ttlMillis: 86400000,
  });

  assert.strictEqual(payload.iss, 'antiaddiction-backend');
  assert.strictEqual(payload.aud, 'antiaddiction-mod');
  assert.strictEqual(payload.kid, 'prod-1');
  assert.strictEqual(payload.minecraftUuid, '00000000-0000-0000-0000-000000000001');
  assert.strictEqual(payload.minecraftName, 'PlayerOne');
  assert.strictEqual(payload.policyVersion, 7);
  assert.strictEqual(payload.issuedAt, 1767225600000);
  assert.strictEqual(payload.expiresAt, 1767312000000);
  assert.ok(payload.sub.startsWith('user:'));
  assert.ok(payload.userDigest.length >= 43);
  assert.ok(payload.idDigest.length >= 43);
  assert.strictEqual(payload.name, undefined);
  assert.strictEqual(payload.idCard, undefined);
  assert.strictEqual(payload.id_card, undefined);
  assert.strictEqual(payload.userDigest, hmacDigest('server-secret', normalizeName(' 张 三 ')));
  assert.strictEqual(payload.idDigest, hmacDigest('server-secret', normalizeIdCard(' 11010520000101123x ')));
  assert.notStrictEqual(
    payload.idDigest,
    crypto.createHash('sha256').update(normalizeIdCard(' 11010520000101123x '), 'utf8').digest('base64url')
  );
}

function testCanonicalBytesAreSignedExactly() {
  const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519');
  const bytes = Buffer.from(canonicalStringify({ z: 1, a: { d: true, b: [3, 2, 1] } }), 'utf8');
  const signature = signPayloadBytes(bytes, privateKey);

  assert.strictEqual(verifyPayloadBytes(bytes, signature, publicKey), true);

  const tampered = Buffer.from(bytes);
  tampered[tampered.length - 2] = '2'.charCodeAt(0);
  assert.strictEqual(verifyPayloadBytes(tampered, signature, publicKey), false);
}

function testRulesPayloadHasReplayAndTimeFields() {
  const payload = createRulesPayload({
    issuer: 'antiaddiction-backend',
    audience: 'antiaddiction-mod',
    kid: 'prod-1',
    policyId: 'cn-antiaddiction-default',
    policyVersion: 7,
    rulesVersion: 42,
    nowMillis: 1767225600000,
    ttlMillis: 120000,
    minClientVersion: '1.0.0',
    defaults: {
      default_start_hour: 20,
      default_start_minute: 30,
      default_end_hour: 21,
      default_end_minute: 0,
      default_playable_weekdays: [3, 4, 5, 6, 7],
      default_max_minutes: 60,
    },
    playedTodaySeconds: 1234,
    days: [{ date: '2026-01-01', playable: 1, start_hour: 20, start_minute: 0, end_hour: 21, end_minute: 0 }],
  });

  assert.strictEqual(payload.rulesVersion, 42);
  assert.strictEqual(payload.policyId, 'cn-antiaddiction-default');
  assert.strictEqual(payload.policyVersion, 7);
  assert.strictEqual(payload.issuedAt, 1767225600000);
  assert.strictEqual(payload.rulesExpiresAt, 1767225720000);
  assert.strictEqual(payload.serverTimeEpochMillis, 1767225600000);
  assert.strictEqual(payload.minClientVersion, '1.0.0');
  assert.strictEqual(payload.played_today_seconds, 1234);
  assert.deepStrictEqual(payload.default_playable_weekdays, [3, 4, 5, 6, 7]);
  assert.strictEqual(payload.default_max_minutes, 60);
  assert.strictEqual(payload.days.length, 1);
}

function testRulesPayloadNormalizesDefaultWeekdaysAndMaxMinutes() {
  const payload = createRulesPayload({
    issuer: 'antiaddiction-backend',
    audience: 'antiaddiction-mod',
    kid: 'prod-1',
    policyId: 'cn-antiaddiction-default',
    policyVersion: 7,
    rulesVersion: 42,
    nowMillis: 1767225600000,
    defaults: {
      default_start_hour: 20,
      default_end_hour: 21,
      default_playable_weekdays: [0, 8, 'abc'],
      default_max_minutes: 'abc',
    },
  });

  assert.deepStrictEqual(payload.default_playable_weekdays, [5, 6, 7]);
  assert.strictEqual(payload.default_max_minutes, 0);

  const negativePayload = createRulesPayload({
    issuer: 'antiaddiction-backend',
    audience: 'antiaddiction-mod',
    kid: 'prod-1',
    policyId: 'cn-antiaddiction-default',
    policyVersion: 7,
    rulesVersion: 42,
    nowMillis: 1767225600000,
    defaults: {
      default_start_hour: 20,
      default_end_hour: 21,
      default_playable_weekdays: '',
      default_max_minutes: -1,
    },
    playedTodaySeconds: -5,
  });

  assert.deepStrictEqual(negativePayload.default_playable_weekdays, [5, 6, 7]);
  assert.strictEqual(negativePayload.default_max_minutes, 0);
  assert.strictEqual(negativePayload.played_today_seconds, 0);
}

function testProductionRuntimeRejectsUnsafeModes() {
  assert.deepStrictEqual(validateRuntimeConfig({
    environment: 'development',
    backendUrl: 'http://localhost:3000',
    allowPublicKeyOverride: true,
    skipSignatureVerification: false,
    defaultAdminPassword: 'admin123',
    hasServerSecret: true,
    hasPrivateKey: true,
  }), []);

  const errors = validateRuntimeConfig({
    environment: 'production',
    backendUrl: 'http://example.com:3000',
    allowPublicKeyOverride: true,
    skipSignatureVerification: true,
    defaultAdminPassword: 'admin123',
    hasServerSecret: false,
    hasPrivateKey: false,
  });

  assert.ok(!errors.some(e => e.includes('HTTPS')));
  assert.ok(errors.some(e => e.includes('公钥覆盖')));
  assert.ok(errors.some(e => e.includes('跳过签名')));
  assert.ok(errors.some(e => e.includes('默认管理员')));
  assert.ok(errors.some(e => e.includes('server secret')));
  assert.ok(errors.some(e => e.includes('Ed25519')));
}

function testAdminUserViewMasksIdCardAndDerivesAge() {
  const view = createAdminUserView({
    id: 1,
    name: '张三',
    id_card: '110105201001011234',
    created: 1767225600,
  }, new Date('2026-06-07T12:00:00+08:00'));

  assert.strictEqual(view.id, 1);
  assert.strictEqual(view.name, '张三');
  assert.strictEqual(view.maskedIdCard, '110105********1234');
  assert.strictEqual(view.age, 16);
  assert.strictEqual(view.minor, true);
  assert.strictEqual(view.id_card, undefined);
}

function testDeriveStorageKeyDeterminismAndDifference() {
  const base = {
    serverSecret: 'test-server-secret',
    userDigest: crypto.randomBytes(32).toString('base64url'),
    salt: crypto.randomBytes(16),
    minecraftUuid: '00000000-0000-0000-0000-000000000001',
    saveName: 'TestWorld',
  };

  const k1 = deriveStorageKey(base);
  const k2 = deriveStorageKey(base);
  assert.strictEqual(k1.length, 32, 'key must be 32 bytes');
  assert.strictEqual(k1.toString('hex'), k2.toString('hex'), 'same inputs must produce same key');

  const kDiffSecret = deriveStorageKey({ ...base, serverSecret: 'other-secret' });
  assert.notStrictEqual(k1.toString('hex'), kDiffSecret.toString('hex'), 'different serverSecret must produce different key');

  const kDiffSalt = deriveStorageKey({ ...base, salt: crypto.randomBytes(16) });
  assert.notStrictEqual(k1.toString('hex'), kDiffSalt.toString('hex'), 'different salt must produce different key');

  const kDiffUuid = deriveStorageKey({ ...base, minecraftUuid: '00000000-0000-0000-0000-000000000002' });
  assert.notStrictEqual(k1.toString('hex'), kDiffUuid.toString('hex'), 'different uuid must produce different key');

  const kDiffName = deriveStorageKey({ ...base, saveName: 'OtherWorld' });
  assert.notStrictEqual(k1.toString('hex'), kDiffName.toString('hex'), 'different saveName must produce different key');
}

testCredentialUsesDigestsOnly();
testCanonicalBytesAreSignedExactly();
testRulesPayloadHasReplayAndTimeFields();
testRulesPayloadNormalizesDefaultWeekdaysAndMaxMinutes();
testProductionRuntimeRejectsUnsafeModes();
testAdminUserViewMasksIdCardAndDerivesAge();
testDeriveStorageKeyDeterminismAndDifference();

console.log('security tests passed');

'use strict';

const crypto = require('crypto');
const { DEFAULT_WEEKDAYS, normalizeIsoWeekdays, toNonNegativeInt } = require('./rules');

function normalizeName(value) {
  return String(value || '').trim().replace(/\s+/g, '');
}

function normalizeIdCard(value) {
  return String(value || '').trim().replace(/\s+/g, '').toUpperCase();
}

function hmacDigest(serverSecret, normalizedValue) {
  if (!serverSecret) throw new Error('server secret is required');
  return crypto.createHmac('sha256', serverSecret)
    .update(String(normalizedValue), 'utf8')
    .digest('base64url');
}

function canonicalStringify(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return '[' + value.map(canonicalStringify).join(',') + ']';
  const keys = Object.keys(value).sort();
  return '{' + keys
    .filter(k => value[k] !== undefined)
    .map(k => JSON.stringify(k) + ':' + canonicalStringify(value[k]))
    .join(',') + '}';
}

function signPayloadBytes(payloadBytes, privateKey) {
  return crypto.sign(null, Buffer.from(payloadBytes), privateKey).toString('base64url');
}

function verifyPayloadBytes(payloadBytes, signature, publicKey) {
  try {
    return crypto.verify(null, Buffer.from(payloadBytes), publicKey, Buffer.from(String(signature), 'base64url'));
  } catch (e) {
    return false;
  }
}

function createCredentialPayload(options) {
  const normalizedName = normalizeName(options.name);
  const normalizedIdCard = normalizeIdCard(options.idCard);
  const userDigest = hmacDigest(options.serverSecret, normalizedName);
  const idDigest = hmacDigest(options.serverSecret, normalizedIdCard);
  const nowMillis = Number(options.nowMillis);
  const ttlMillis = Number(options.ttlMillis || 86_400_000);

  return {
    age: Number(options.age),
    aud: options.audience,
    clientVersion: options.clientVersion,
    expiresAt: nowMillis + ttlMillis,
    idDigest,
    iss: options.issuer,
    issuedAt: nowMillis,
    kid: options.kid,
    minecraftName: String(options.minecraftName || ''),
    minecraftUuid: String(options.minecraftUuid || ''),
    minor: !!options.minor,
    policyVersion: Number(options.policyVersion),
    sub: 'user:' + userDigest.slice(0, 32),
    userDigest,
  };
}

function createRulesPayload(options) {
  const defaults = options.defaults || {};
  const nowMillis = Number(options.nowMillis);
  const defaultPlayableWeekdays = normalizeIsoWeekdays(defaults.default_playable_weekdays);
  return {
    aud: options.audience,
    days: Array.isArray(options.days) ? options.days : [],
    default_end_hour: Number(defaults.default_end_hour),
    default_end_minute: Number(defaults.default_end_minute || 0),
    default_max_minutes: toNonNegativeInt(defaults.default_max_minutes),
    default_playable_weekdays: defaultPlayableWeekdays.length ? defaultPlayableWeekdays : DEFAULT_WEEKDAYS.slice(),
    default_start_hour: Number(defaults.default_start_hour),
    default_start_minute: Number(defaults.default_start_minute || 0),
    iss: options.issuer,
    issuedAt: nowMillis,
    kid: options.kid,
    minClientVersion: String(options.minClientVersion || '1.0.0'),
    policyId: String(options.policyId),
    policyVersion: Number(options.policyVersion),
    played_today_seconds: toNonNegativeInt(options.playedTodaySeconds),
    rulesExpiresAt: nowMillis + Number(options.ttlMillis || 120_000),
    rulesVersion: Number(options.rulesVersion),
    serverTimeEpochMillis: nowMillis,
  };
}

function calculateAgeFromIdCard(idCard, now = new Date()) {
  const normalized = normalizeIdCard(idCard);
  if (!/^\d{17}[\dX]$/.test(normalized)) return null;
  const birth = normalized.slice(6, 14);
  const year = Number(birth.slice(0, 4));
  const month = Number(birth.slice(4, 6));
  const day = Number(birth.slice(6, 8));
  if (!year || month < 1 || month > 12 || day < 1 || day > 31) return null;

  let age = now.getFullYear() - year;
  const currentMonth = now.getMonth() + 1;
  if (currentMonth < month || (currentMonth === month && now.getDate() < day)) age--;
  return age;
}

function maskIdCard(idCard) {
  const normalized = normalizeIdCard(idCard);
  if (normalized.length <= 10) return normalized.replace(/.(?=.{4})/g, '*');
  return normalized.replace(/^(.{6}).+(.{4})$/, '$1********$2');
}

function createAdminUserView(row, now = new Date()) {
  const age = calculateAgeFromIdCard(row.id_card, now);
  return {
    id: row.id,
    name: row.name,
    maskedIdCard: maskIdCard(row.id_card),
    age,
    minor: age == null ? null : age < 18,
    created: row.created,
  };
}

function deriveStorageKey({ serverSecret, userDigest, salt, minecraftUuid, saveName }) {
  const ikm = Buffer.concat([
    Buffer.from(String(serverSecret), 'utf8'),
    Buffer.from(String(userDigest), 'base64url'),
  ]);
  const info = Buffer.from('aa-storage-key|v1|' + String(minecraftUuid) + '|' + String(saveName), 'utf8');
  return Buffer.from(crypto.hkdfSync('sha256', ikm, salt, info, 32));
}

function validateRuntimeConfig(options) {
  const env = String(options.environment || 'production').toLowerCase();
  const isProd = env === 'production';
  const url = String(options.backendUrl || '').trim();
  const errors = [];

  if (!isProd) {
    if (url && !/^https:\/\//i.test(url) && !/^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?(\/.*)?$/i.test(url)) {
      errors.push('development 仅允许 HTTPS 或 http://localhost/http://127.0.0.1');
    }
    return errors;
  }

  if (!url) errors.push('production 必须配置 backend_url');
  if (url && !/^https?:\/\//i.test(url)) errors.push('production backend_url 必须使用 HTTP 或 HTTPS');
  if (options.allowPublicKeyOverride) errors.push('production 禁止公钥覆盖');
  if (options.skipSignatureVerification) errors.push('production 禁止跳过签名验证');
  if (options.defaultAdminPassword) errors.push('production 禁止默认管理员密码');
  if (!options.hasServerSecret) errors.push('production 必须配置 server secret');
  if (!options.hasPrivateKey) errors.push('production 必须配置 Ed25519 私钥');
  return errors;
}

module.exports = {
  normalizeName,
  normalizeIdCard,
  hmacDigest,
  canonicalStringify,
  signPayloadBytes,
  verifyPayloadBytes,
  createCredentialPayload,
  createRulesPayload,
  calculateAgeFromIdCard,
  maskIdCard,
  createAdminUserView,
  validateRuntimeConfig,
  deriveStorageKey,
};

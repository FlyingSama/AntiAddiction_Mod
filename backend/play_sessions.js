'use strict';

const VALID_EVENTS = new Set(['play_start', 'play_heartbeat', 'play_end']);
const VALID_TARGET_TYPES = new Set(['singleplayer', 'multiplayer', 'unknown']);
const SHANGHAI_OFFSET_SECONDS = 8 * 60 * 60;
const DAY_SECONDS = 24 * 60 * 60;

function safeText(value, maxLength = 255) {
  return String(value ?? '')
    .replace(/[\x00-\x1F\x7F]/g, '')
    .slice(0, maxLength);
}

function safeInt(value, fallback = 0) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric < 0) return fallback;
  return Math.trunc(numeric);
}

function eventTimeToSeconds(value) {
  const millis = Number(value);
  if (!Number.isFinite(millis) || millis < 0) return Math.floor(Date.now() / 1000);
  return Math.trunc(millis / 1000);
}

function shanghaiDayBoundsSeconds(nowSeconds) {
  const seconds = safeInt(nowSeconds, Math.floor(Date.now() / 1000));
  const dayNumber = Math.floor((seconds + SHANGHAI_OFFSET_SECONDS) / DAY_SECONDS);
  const start = dayNumber * DAY_SECONDS - SHANGHAI_OFFSET_SECONDS;
  return { start, end: start + DAY_SECONDS };
}

function shanghaiDateBoundsSeconds(dateText) {
  const match = String(dateText || '').match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!match) return shanghaiDayBoundsSeconds();
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const start = Math.floor(Date.UTC(year, month - 1, day, -8, 0, 0) / 1000);
  return { start, end: start + DAY_SECONDS };
}

function shanghaiDateFromSeconds(seconds) {
  return new Date((safeInt(seconds) + SHANGHAI_OFFSET_SECONDS) * 1000).toISOString().slice(0, 10);
}

function sessionEffectiveEnd(row) {
  const endedAt = safeInt(row.ended_at);
  if (endedAt > 0) return endedAt;
  return safeInt(row.last_event_at, safeInt(row.started_at));
}

function sessionOverlapSeconds(row, bounds) {
  const startedAt = safeInt(row.started_at);
  const effectiveEnd = sessionEffectiveEnd(row);
  return Math.max(0, Math.min(effectiveEnd, bounds.end) - Math.max(startedAt, bounds.start));
}

function sumSessionOverlapSeconds(rows, bounds) {
  return rows.reduce((total, row) => total + sessionOverlapSeconds(row, bounds), 0);
}

function installPlaySessionSchema(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS play_sessions (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      session_id TEXT NOT NULL,
      user_sub TEXT NOT NULL,
      minecraft_uuid TEXT NOT NULL,
      minecraft_name TEXT NOT NULL,
      minor INTEGER NOT NULL DEFAULT 0,
      target_type TEXT NOT NULL,
      target_name TEXT NOT NULL,
      started_at INTEGER NOT NULL,
      last_event_at INTEGER NOT NULL,
      ended_at INTEGER,
      duration_seconds INTEGER NOT NULL DEFAULT 0,
      remaining_seconds INTEGER NOT NULL DEFAULT 0,
      ip TEXT NOT NULL DEFAULT '',
      location TEXT NOT NULL DEFAULT '',
      UNIQUE(user_sub, session_id)
    );

    CREATE TABLE IF NOT EXISTS play_events (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      event_id TEXT NOT NULL UNIQUE,
      session_id TEXT NOT NULL,
      user_sub TEXT NOT NULL,
      minecraft_uuid TEXT NOT NULL,
      minecraft_name TEXT NOT NULL,
      minor INTEGER NOT NULL DEFAULT 0,
      event TEXT NOT NULL,
      target_type TEXT NOT NULL,
      target_name TEXT NOT NULL,
      event_at INTEGER NOT NULL,
      duration_seconds INTEGER NOT NULL DEFAULT 0,
      remaining_seconds INTEGER NOT NULL DEFAULT 0,
      ip TEXT NOT NULL DEFAULT '',
      location TEXT NOT NULL DEFAULT ''
    );

    CREATE INDEX IF NOT EXISTS idx_play_sessions_user_time
      ON play_sessions (user_sub, started_at DESC);
    CREATE INDEX IF NOT EXISTS idx_play_sessions_last_event
      ON play_sessions (last_event_at DESC);
    CREATE INDEX IF NOT EXISTS idx_play_sessions_target
      ON play_sessions (target_type, target_name);
    CREATE INDEX IF NOT EXISTS idx_play_events_user_time
      ON play_events (user_sub, event_at DESC);
    CREATE INDEX IF NOT EXISTS idx_play_events_session
      ON play_events (session_id, event_at);
  `);
}

function normalizeEventInput(credential, body, ip, location) {
  const event = safeText(body.event, 32);
  if (!VALID_EVENTS.has(event)) throw new Error('invalid play session event');

  const eventId = safeText(body.eventId, 128);
  const sessionId = safeText(body.sessionId, 128);
  if (!eventId) throw new Error('eventId is required');
  if (!sessionId) throw new Error('sessionId is required');

  const targetType = safeText(body.targetType, 32);
  if (!VALID_TARGET_TYPES.has(targetType)) throw new Error('invalid targetType');

  const eventAt = eventTimeToSeconds(body.eventTimeMillis);
  return {
    eventId,
    sessionId,
    userSub: safeText(credential.sub, 128),
    minecraftUuid: safeText(credential.minecraftUuid, 64),
    minecraftName: safeText(credential.minecraftName, 64),
    minor: credential.minor ? 1 : 0,
    event,
    targetType,
    targetName: safeText(body.targetName, 255),
    eventAt,
    durationSeconds: safeInt(body.durationSeconds),
    remainingSeconds: safeInt(body.remainingSeconds),
    ip: safeText(ip, 64),
    location: safeText(location, 255),
  };
}

function recordPlaySessionEvent(db, { credential, body, ip = '', location = '' }) {
  const event = normalizeEventInput(credential || {}, body || {}, ip, location);

  const insertEvent = db.prepare(`
    INSERT OR IGNORE INTO play_events (
      event_id, session_id, user_sub, minecraft_uuid, minecraft_name, minor,
      event, target_type, target_name, event_at, duration_seconds, remaining_seconds,
      ip, location
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);

  const write = db.transaction((normalized) => {
    const info = insertEvent.run(
      normalized.eventId,
      normalized.sessionId,
      normalized.userSub,
      normalized.minecraftUuid,
      normalized.minecraftName,
      normalized.minor,
      normalized.event,
      normalized.targetType,
      normalized.targetName,
      normalized.eventAt,
      normalized.durationSeconds,
      normalized.remainingSeconds,
      normalized.ip,
      normalized.location
    );
    if (info.changes === 0) return { ok: true, duplicate: true };

    db.prepare(`
      INSERT INTO play_sessions (
        session_id, user_sub, minecraft_uuid, minecraft_name, minor,
        target_type, target_name, started_at, last_event_at, ended_at,
        duration_seconds, remaining_seconds, ip, location
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_sub, session_id) DO UPDATE SET
        user_sub = excluded.user_sub,
        minecraft_uuid = excluded.minecraft_uuid,
        minecraft_name = excluded.minecraft_name,
        minor = excluded.minor,
        target_type = excluded.target_type,
        target_name = excluded.target_name,
        started_at = MIN(play_sessions.started_at, excluded.started_at),
        last_event_at = MAX(play_sessions.last_event_at, excluded.last_event_at),
        ended_at = CASE
          WHEN excluded.ended_at IS NOT NULL
            AND excluded.last_event_at >= play_sessions.last_event_at
            AND play_sessions.ended_at IS NOT NULL
            THEN MAX(play_sessions.ended_at, excluded.ended_at)
          WHEN excluded.ended_at IS NOT NULL
            AND excluded.last_event_at >= play_sessions.last_event_at
            THEN excluded.ended_at
          ELSE play_sessions.ended_at
        END,
        duration_seconds = MAX(play_sessions.duration_seconds, excluded.duration_seconds),
        remaining_seconds = CASE
          WHEN excluded.last_event_at >= play_sessions.last_event_at
            THEN excluded.remaining_seconds
          ELSE play_sessions.remaining_seconds
        END,
        ip = CASE
          WHEN excluded.last_event_at >= play_sessions.last_event_at
            THEN excluded.ip
          ELSE play_sessions.ip
        END,
        location = CASE
          WHEN excluded.last_event_at >= play_sessions.last_event_at
            THEN excluded.location
          ELSE play_sessions.location
        END
    `).run(
      normalized.sessionId,
      normalized.userSub,
      normalized.minecraftUuid,
      normalized.minecraftName,
      normalized.minor,
      normalized.targetType,
      normalized.targetName,
      normalized.eventAt,
      normalized.eventAt,
      normalized.event === 'play_end' ? normalized.eventAt : null,
      normalized.durationSeconds,
      normalized.remainingSeconds,
      normalized.ip,
      normalized.location
    );

    return { ok: true, duplicate: false };
  });

  return write(event);
}

function getUserPlaySessions(db, userSub, options = {}) {
  const limit = Math.min(safeInt(options.limit, 50), 200);
  const offset = safeInt(options.offset);
  const total = db.prepare('SELECT COUNT(*) AS total FROM play_sessions WHERE user_sub = ?')
    .get(userSub).total;
  const items = db.prepare(`
    SELECT *
    FROM play_sessions
    WHERE user_sub = ?
    ORDER BY started_at DESC
    LIMIT ? OFFSET ?
  `).all(userSub, limit, offset);
  return { total, items };
}

function getUserPlayEvents(db, userSub, options = {}) {
  const limit = Math.min(safeInt(options.limit, 100), 500);
  const offset = safeInt(options.offset);
  const total = db.prepare('SELECT COUNT(*) AS total FROM play_events WHERE user_sub = ?')
    .get(userSub).total;
  const items = db.prepare(`
    SELECT *
    FROM play_events
    WHERE user_sub = ?
    ORDER BY event_at DESC, id DESC
    LIMIT ? OFFSET ?
  `).all(userSub, limit, offset);
  return { total, items };
}

function getUserPlayDays(db, userSub, options = {}) {
  const limit = Math.min(safeInt(options.limit, 30), 90);
  const rows = db.prepare(`
    SELECT started_at, last_event_at, ended_at, duration_seconds, remaining_seconds
    FROM play_sessions
    WHERE user_sub = ?
    ORDER BY started_at DESC
    LIMIT 500
  `).all(userSub);
  const days = new Map();
  for (const row of rows) {
    const day = shanghaiDateFromSeconds(row.started_at);
    const current = days.get(day) || {
      date: day,
      duration_seconds: 0,
      session_count: 0,
      last_play_at: 0,
      remaining_seconds: null,
    };
    current.duration_seconds += safeInt(row.duration_seconds);
    current.session_count++;
    current.last_play_at = Math.max(current.last_play_at, sessionEffectiveEnd(row));
    const remaining = safeInt(row.remaining_seconds, -1);
    if (remaining >= 0) {
      current.remaining_seconds = current.remaining_seconds == null
        ? remaining
        : Math.min(current.remaining_seconds, remaining);
    }
    days.set(day, current);
  }
  return Array.from(days.values())
    .sort((a, b) => b.date.localeCompare(a.date))
    .slice(0, limit);
}

function getUserPlayDayDetails(db, userSub, date) {
  const bounds = shanghaiDateBoundsSeconds(date);
  const sessions = db.prepare(`
    SELECT *
    FROM play_sessions
    WHERE user_sub = ?
      AND started_at < ?
      AND COALESCE(NULLIF(ended_at, 0), last_event_at) > ?
    ORDER BY started_at DESC
  `).all(userSub, bounds.end, bounds.start);
  const events = db.prepare(`
    SELECT *
    FROM play_events
    WHERE user_sub = ? AND event_at >= ? AND event_at < ?
    ORDER BY event_at DESC, id DESC
    LIMIT 200
  `).all(userSub, bounds.start, bounds.end);
  return {
    date,
    total_seconds: sumSessionOverlapSeconds(sessions, bounds),
    sessions,
    events,
  };
}

function getUserPlaySummary(db, userSub, options = {}) {
  const bounds = shanghaiDayBoundsSeconds(options.nowSeconds);
  const todayRows = db.prepare(`
    SELECT started_at, last_event_at, ended_at
    FROM play_sessions
    WHERE user_sub = ?
      AND started_at < ?
      AND COALESCE(NULLIF(ended_at, 0), last_event_at) > ?
  `).all(userSub, bounds.end, bounds.start);
  const today = sumSessionOverlapSeconds(todayRows, bounds);
  const logout = db.prepare(`
    SELECT MAX(ended_at) AS last_logout_at
    FROM play_sessions
    WHERE user_sub = ? AND ended_at IS NOT NULL
  `).get(userSub).last_logout_at;
  const topByTarget = db.prepare(`
    SELECT target_name, SUM(duration_seconds) AS duration_seconds
    FROM play_sessions
    WHERE user_sub = ? AND target_type = ? AND started_at >= ?
    GROUP BY target_name
    ORDER BY duration_seconds DESC, target_name ASC
    LIMIT 10
  `);

  return {
    today_seconds: today,
    last_logout_at: logout,
    singleplayer: topByTarget.all(userSub, 'singleplayer', bounds.start),
    multiplayer: topByTarget.all(userSub, 'multiplayer', bounds.start),
  };
}

function getDashboardPlaySummary(db, options = {}) {
  const nowSeconds = safeInt(options.nowSeconds, Math.floor(Date.now() / 1000));
  const bounds = shanghaiDayBoundsSeconds(nowSeconds);
  const activeSince = nowSeconds - safeInt(options.activeWindowSeconds, 120);
  const activeUsers = db.prepare(`
    SELECT COUNT(DISTINCT user_sub) AS total
    FROM play_sessions
    WHERE ended_at IS NULL AND last_event_at >= ?
  `).get(activeSince).total;
  const todayRows = db.prepare(`
    SELECT started_at, last_event_at, ended_at
    FROM play_sessions
    WHERE started_at < ?
      AND COALESCE(NULLIF(ended_at, 0), last_event_at) > ?
  `).all(bounds.end, bounds.start);
  const todayTotal = sumSessionOverlapSeconds(todayRows, bounds);
  const topByTarget = db.prepare(`
    SELECT target_name, SUM(duration_seconds) AS duration_seconds
    FROM play_sessions
    WHERE target_type = ? AND started_at >= ?
    GROUP BY target_name
    ORDER BY duration_seconds DESC, target_name ASC
    LIMIT 10
  `);

  return {
    active_users: activeUsers,
    today_total_seconds: todayTotal,
    top_singleplayer: topByTarget.all('singleplayer', bounds.start),
    top_multiplayer: topByTarget.all('multiplayer', bounds.start),
  };
}

module.exports = {
  installPlaySessionSchema,
  recordPlaySessionEvent,
  getUserPlaySummary,
  getUserPlaySessions,
  getUserPlayEvents,
  getUserPlayDays,
  getUserPlayDayDetails,
  getDashboardPlaySummary,
};

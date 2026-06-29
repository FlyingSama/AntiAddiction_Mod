const assert = require('node:assert/strict');
const Database = require('better-sqlite3');

const {
  installPlaySessionSchema,
  recordPlaySessionEvent,
  getUserPlaySummary,
  getUserPlaySessions,
  getUserPlayEvents,
  getDashboardPlaySummary,
} = require('./play_sessions');

const db = new Database(':memory:');
installPlaySessionSchema(db);

const credential = {
  sub: 'user:test-sub',
  minecraftUuid: 'uuid-1',
  minecraftName: 'Alex',
  minor: true,
};

const baseEvent = {
  sessionId: 'session-1',
  targetType: 'singleplayer',
  targetName: 'Survival',
  eventTimeMillis: 1780905600000,
  remainingSeconds: 3600,
};

recordPlaySessionEvent(db, {
  credential,
  body: {
    ...baseEvent,
    eventId: 'event-1',
    event: 'play_start',
    durationSeconds: 0,
  },
  ip: '127.0.0.1',
  location: 'test',
});

recordPlaySessionEvent(db, {
  credential,
  body: {
    ...baseEvent,
    eventId: 'event-2',
    event: 'play_heartbeat',
    eventTimeMillis: 1780905660000,
    durationSeconds: 60,
    remainingSeconds: 3540,
  },
  ip: '127.0.0.1',
  location: 'test',
});

recordPlaySessionEvent(db, {
  credential,
  body: {
    ...baseEvent,
    eventId: 'event-3',
    event: 'play_end',
    eventTimeMillis: 1780905720000,
    durationSeconds: 120,
    remainingSeconds: 3480,
  },
  ip: '127.0.0.1',
  location: 'test',
});

const duplicate = recordPlaySessionEvent(db, {
  credential,
  body: {
    ...baseEvent,
    eventId: 'event-3',
    event: 'play_end',
    eventTimeMillis: 1780905720000,
    durationSeconds: 999,
    remainingSeconds: 1,
  },
  ip: '127.0.0.1',
  location: 'test',
});

assert.deepEqual(duplicate, { ok: true, duplicate: true });

const sessions = getUserPlaySessions(db, credential.sub);
assert.equal(sessions.total, 1);
assert.equal(sessions.items[0].duration_seconds, 120);
assert.equal(sessions.items[0].remaining_seconds, 3480);
assert.equal(sessions.items[0].target_name, 'Survival');

const events = getUserPlayEvents(db, credential.sub);
assert.equal(events.total, 3);

const summary = getUserPlaySummary(db, credential.sub, { nowSeconds: 1780905900 });
assert.equal(summary.today_seconds, 120);
assert.equal(summary.last_logout_at, 1780905720);
assert.deepEqual(summary.singleplayer[0], {
  target_name: 'Survival',
  duration_seconds: 120,
});
assert.deepEqual(summary.multiplayer, []);

const dashboard = getDashboardPlaySummary(db, { nowSeconds: 1780905900 });
assert.equal(dashboard.active_users, 0);
assert.equal(dashboard.today_total_seconds, 120);
assert.equal(dashboard.top_singleplayer[0].target_name, 'Survival');

const outOfOrderCredential = {
  ...credential,
  sub: 'user:out-of-order',
};

recordPlaySessionEvent(db, {
  credential: outOfOrderCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-2',
    eventId: 'event-4',
    event: 'play_heartbeat',
    eventTimeMillis: 1780905660000,
    durationSeconds: 60,
    remainingSeconds: 3540,
  },
  ip: '127.0.0.1',
  location: 'newer',
});

recordPlaySessionEvent(db, {
  credential: outOfOrderCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-2',
    eventId: 'event-5',
    event: 'play_start',
    eventTimeMillis: 1780905600000,
    durationSeconds: 0,
    remainingSeconds: 3600,
  },
  ip: '127.0.0.2',
  location: 'older',
});

const outOfOrderSessions = getUserPlaySessions(db, outOfOrderCredential.sub);
assert.equal(outOfOrderSessions.total, 1);
assert.equal(outOfOrderSessions.items[0].remaining_seconds, 3540);
assert.equal(outOfOrderSessions.items[0].last_event_at, 1780905660);
assert.equal(outOfOrderSessions.items[0].ip, '127.0.0.1');
assert.equal(outOfOrderSessions.items[0].location, 'newer');

const staleEndCredential = {
  ...credential,
  sub: 'user:stale-end',
};

recordPlaySessionEvent(db, {
  credential: staleEndCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-3',
    eventId: 'event-6',
    event: 'play_heartbeat',
    eventTimeMillis: 1780905660000,
    durationSeconds: 60,
    remainingSeconds: 3540,
  },
  ip: '127.0.0.1',
  location: 'newer',
});

recordPlaySessionEvent(db, {
  credential: staleEndCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-3',
    eventId: 'event-7',
    event: 'play_end',
    eventTimeMillis: 1780905600000,
    durationSeconds: 0,
    remainingSeconds: 3600,
  },
  ip: '127.0.0.2',
  location: 'older-end',
});

const staleEndSessions = getUserPlaySessions(db, staleEndCredential.sub);
assert.equal(staleEndSessions.total, 1);
assert.equal(staleEndSessions.items[0].ended_at, null);
assert.equal(staleEndSessions.items[0].last_event_at, 1780905660);
assert.equal(staleEndSessions.items[0].remaining_seconds, 3540);

const staleEndDashboard = getDashboardPlaySummary(db, { nowSeconds: 1780905700 });
assert.equal(staleEndDashboard.active_users >= 1, true);

const sameSessionDb = new Database(':memory:');
installPlaySessionSchema(sameSessionDb);
const sameSessionA = {
  ...credential,
  sub: 'user:a',
  minecraftUuid: 'uuid-a',
  minecraftName: 'AlexA',
};
const sameSessionB = {
  ...credential,
  sub: 'user:b',
  minecraftUuid: 'uuid-b',
  minecraftName: 'AlexB',
};

recordPlaySessionEvent(sameSessionDb, {
  credential: sameSessionA,
  body: {
    ...baseEvent,
    sessionId: 'shared-session-id',
    eventId: 'event-8',
    event: 'play_start',
    durationSeconds: 0,
  },
});
recordPlaySessionEvent(sameSessionDb, {
  credential: sameSessionB,
  body: {
    ...baseEvent,
    sessionId: 'shared-session-id',
    eventId: 'event-9',
    event: 'play_start',
    durationSeconds: 0,
  },
});
assert.equal(getUserPlaySessions(sameSessionDb, 'user:a').total, 1);
assert.equal(getUserPlaySessions(sameSessionDb, 'user:b').total, 1);

const midnightDb = new Database(':memory:');
installPlaySessionSchema(midnightDb);
const midnightCredential = {
  ...credential,
  sub: 'user:midnight',
};
const bjtCrossMidnightStart = Date.UTC(2026, 5, 9, 15, 59, 0) / 1000; // 2026-06-09 23:59:00 Asia/Shanghai
const bjtCrossMidnightEnd = Date.UTC(2026, 5, 9, 16, 1, 0) / 1000; // 2026-06-10 00:01:00 Asia/Shanghai
const bjtNextDayNow = Date.UTC(2026, 5, 10, 4, 0, 0) / 1000; // 2026-06-10 12:00:00 Asia/Shanghai

recordPlaySessionEvent(midnightDb, {
  credential: midnightCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-midnight',
    eventId: 'event-10',
    event: 'play_start',
    eventTimeMillis: bjtCrossMidnightStart * 1000,
    durationSeconds: 0,
    remainingSeconds: 3600,
  },
});
recordPlaySessionEvent(midnightDb, {
  credential: midnightCredential,
  body: {
    ...baseEvent,
    sessionId: 'session-midnight',
    eventId: 'event-11',
    event: 'play_end',
    eventTimeMillis: bjtCrossMidnightEnd * 1000,
    durationSeconds: 120,
    remainingSeconds: 3480,
  },
});
assert.equal(
  getUserPlaySummary(midnightDb, midnightCredential.sub, { nowSeconds: bjtNextDayNow }).today_seconds,
  60
);
assert.equal(
  getDashboardPlaySummary(midnightDb, { nowSeconds: bjtNextDayNow }).today_total_seconds,
  60
);

const unknownTargetDb = new Database(':memory:');
installPlaySessionSchema(unknownTargetDb);
recordPlaySessionEvent(unknownTargetDb, {
  credential: {
    ...credential,
    sub: 'user:unknown-target',
  },
  body: {
    ...baseEvent,
    sessionId: 'session-unknown',
    eventId: 'event-12',
    event: 'play_start',
    targetType: 'unknown',
    targetName: 'unknown',
    targetId: 'unknown',
    durationSeconds: 0,
  },
});
assert.equal(getUserPlaySessions(unknownTargetDb, 'user:unknown-target').items[0].target_type, 'unknown');

console.log('play session tests passed');

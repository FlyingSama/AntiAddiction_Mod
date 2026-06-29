/* eslint-disable no-console */
'use strict';

const assert = require('assert');
const Database = require('better-sqlite3');

const {
  normalizeIsoWeekdays,
  isIsoDate,
  enumerateDateRange,
  validateTimeWindow,
  buildGameDayRowsForRange,
  readDefaultConfig,
  writeDefaultConfig,
  isDefaultPlayableDay,
} = require('./rules');

function testNormalizeIsoWeekdays() {
  assert.deepStrictEqual(normalizeIsoWeekdays([7, 3, '3', 0, 8, 5]), [3, 5, 7]);
  assert.deepStrictEqual(normalizeIsoWeekdays('3,4,5,6,7'), [3, 4, 5, 6, 7]);
  assert.deepStrictEqual(normalizeIsoWeekdays(''), []);
}

function testIsIsoDate() {
  assert.strictEqual(isIsoDate('2026-06-08'), true);
  assert.strictEqual(isIsoDate('2026-6-8'), false);
  assert.strictEqual(isIsoDate('2026-02-30'), false);
}

function testEnumerateDateRange() {
  assert.deepStrictEqual(enumerateDateRange('2026-06-08', '2026-06-10'), [
    '2026-06-08',
    '2026-06-09',
    '2026-06-10',
  ]);
  assert.throws(
    () => enumerateDateRange('2026-06-10', '2026-06-08'),
    /开始日期不能晚于结束日期/
  );
}

function testBuildGameDayRowsForRange() {
  const rows = buildGameDayRowsForRange({
    startDate: '2026-06-08',
    endDate: '2026-06-14',
    weekdays: [3, 4, 5, 6, 7],
    playable: true,
    startHour: 20,
    startMinute: 0,
    endHour: 21,
    endMinute: 30,
    maxMinutes: 45,
    label: '暑期可玩',
  });

  assert.deepStrictEqual(rows.map(row => row.date), [
    '2026-06-10',
    '2026-06-11',
    '2026-06-12',
    '2026-06-13',
    '2026-06-14',
  ]);
  for (const row of rows) {
    assert.strictEqual(row.playable, 1);
    assert.strictEqual(row.max_minutes, 45);
    assert.strictEqual(row.label, '暑期可玩');
  }
}

function testIsDefaultPlayableDay() {
  assert.strictEqual(isDefaultPlayableDay('2026-06-08', [1]), true);
  assert.strictEqual(isDefaultPlayableDay('2026-06-08', [3, 4, 5, 6, 7]), false);
  assert.strictEqual(isDefaultPlayableDay('2026-06-14', [3, 4, 5, 6, 7]), true);
}

function createFakeConfigDb() {
  const writes = [];
  return {
    writes,
    prepare(sql) {
      assert.strictEqual(sql, 'INSERT OR REPLACE INTO default_config (key, value) VALUES (?, ?)');
      return {
        run(key, value) {
          writes.push([key, value]);
        },
      };
    },
    transaction(fn) {
      return items => {
        writes.push(['transaction:start', String(items.length)]);
        fn(items);
        writes.push(['transaction:end', String(items.length)]);
      };
    },
  };
}

function testValidateTimeWindowMinuteBoundaries() {
  assert.deepStrictEqual(validateTimeWindow(20, 0, 21, 59), {
    start_hour: 20,
    start_minute: 0,
    end_hour: 21,
    end_minute: 59,
  });
  assert.throws(() => validateTimeWindow(20, 60, 21, 0), /时间格式错误/);
  assert.throws(() => validateTimeWindow(20, 0, 21, -1), /时间格式错误/);
  assert.throws(() => validateTimeWindow(20, 30, 20, 30), /开始时间必须小于结束时间/);
}

function testWriteDefaultConfigUsesTransactionAndNormalizesMaxMinutes() {
  const db = createFakeConfigDb();

  const defaults = writeDefaultConfig(db, {
    default_start_hour: 20,
    default_start_minute: 0,
    default_end_hour: 21,
    default_end_minute: 30,
    default_playable_weekdays: [7, '5', 5],
    default_max_minutes: '45.9',
  });

  assert.strictEqual(db.writes[0][0], 'transaction:start');
  assert.strictEqual(db.writes.at(-1)[0], 'transaction:end');
  assert.deepStrictEqual(defaults.default_playable_weekdays, [5, 7]);
  assert.strictEqual(defaults.default_max_minutes, 45);
  assert.deepStrictEqual(db.writes.slice(1, -1), [
    ['default_start_hour', '20'],
    ['default_start_minute', '0'],
    ['default_end_hour', '21'],
    ['default_end_minute', '30'],
    ['default_playable_weekdays', '5,7'],
    ['default_max_minutes', '45'],
  ]);
}

function testWriteDefaultConfigRejectsEmptyWeekdaysAndAvoidsNaNMaxMinutes() {
  assert.throws(() => writeDefaultConfig(createFakeConfigDb(), {
    default_start_hour: 20,
    default_end_hour: 21,
    default_playable_weekdays: '',
  }), /默认可玩星期不能为空/);

  const invalidMaxDb = createFakeConfigDb();
  const invalidMaxDefaults = writeDefaultConfig(invalidMaxDb, {
    default_start_hour: 20,
    default_end_hour: 21,
    default_playable_weekdays: [5],
    default_max_minutes: 'abc',
  });
  assert.strictEqual(invalidMaxDefaults.default_max_minutes, 0);
  assert.deepStrictEqual(invalidMaxDb.writes.find(row => row[0] === 'default_max_minutes'), ['default_max_minutes', '0']);

  const negativeMaxDefaults = writeDefaultConfig(createFakeConfigDb(), {
    default_start_hour: 20,
    default_end_hour: 21,
    default_playable_weekdays: [5],
    default_max_minutes: -1,
  });
  assert.strictEqual(negativeMaxDefaults.default_max_minutes, 0);
}

function testDefaultConfigHelpersReadAndWriteSqliteRows() {
  const db = new Database(':memory:');
  db.exec('CREATE TABLE default_config (key TEXT PRIMARY KEY, value TEXT NOT NULL)');

  const insert = db.prepare('INSERT INTO default_config (key, value) VALUES (?, ?)');
  insert.run('default_playable_weekdays', '5,6,7');
  insert.run('default_start_hour', '20');
  insert.run('default_start_minute', '0');
  insert.run('default_end_hour', '21');
  insert.run('default_end_minute', '0');
  insert.run('default_max_minutes', '0');

  assert.deepStrictEqual(readDefaultConfig(db).default_playable_weekdays, [5, 6, 7]);

  writeDefaultConfig(db, {
    default_playable_weekdays: [3, 4, 5, 6, 7],
    start_hour: 19,
    start_minute: 30,
    end_hour: 22,
    end_minute: 15,
    default_max_minutes: 75,
  });

  const defaults = readDefaultConfig(db);
  assert.deepStrictEqual(defaults.default_playable_weekdays, [3, 4, 5, 6, 7]);
  assert.strictEqual(defaults.default_start_hour, 19);
  assert.strictEqual(defaults.default_start_minute, 30);
  assert.strictEqual(defaults.default_end_hour, 22);
  assert.strictEqual(defaults.default_end_minute, 15);
  assert.strictEqual(defaults.default_max_minutes, 75);

  db.close();
}

testNormalizeIsoWeekdays();
testIsIsoDate();
testEnumerateDateRange();
testBuildGameDayRowsForRange();
testIsDefaultPlayableDay();
testValidateTimeWindowMinuteBoundaries();
testWriteDefaultConfigUsesTransactionAndNormalizesMaxMinutes();
testWriteDefaultConfigRejectsEmptyWeekdaysAndAvoidsNaNMaxMinutes();
testDefaultConfigHelpersReadAndWriteSqliteRows();

console.log('rules tests passed');

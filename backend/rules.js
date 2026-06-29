'use strict';

const DEFAULT_WEEKDAYS = [5, 6, 7];

function toNonNegativeInt(value, fallback = 0) {
  const numeric = Number(value);
  if (!Number.isFinite(numeric) || numeric < 0) return fallback;
  return Math.trunc(numeric);
}

function normalizeIsoWeekdays(value) {
  const raw = Array.isArray(value) ? value : String(value || '').split(',');
  const unique = new Set();

  for (const item of raw) {
    const weekday = Number(item);
    if (Number.isInteger(weekday) && weekday >= 1 && weekday <= 7) {
      unique.add(weekday);
    }
  }

  return Array.from(unique).sort((a, b) => a - b);
}

function isIsoDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value))) return false;
  const date = new Date(String(value) + 'T00:00:00.000Z');
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function isoWeekday(dateValue) {
  if (!isIsoDate(dateValue)) throw new Error('日期格式错误');
  const day = new Date(dateValue + 'T00:00:00.000Z').getUTCDay();
  return day === 0 ? 7 : day;
}

function enumerateDateRange(startDate, endDate) {
  if (!isIsoDate(startDate) || !isIsoDate(endDate)) throw new Error('日期格式错误');

  const start = new Date(startDate + 'T00:00:00.000Z');
  const end = new Date(endDate + 'T00:00:00.000Z');
  if (start > end) throw new Error('开始日期不能晚于结束日期');

  const dates = [];
  for (let time = start.getTime(); time <= end.getTime(); time += 86_400_000) {
    dates.push(new Date(time).toISOString().slice(0, 10));
  }
  return dates;
}

function validateTimeWindow(startHour, startMinute, endHour, endMinute) {
  const normalized = {
    start_hour: Number(startHour),
    start_minute: Number(startMinute || 0),
    end_hour: Number(endHour),
    end_minute: Number(endMinute || 0),
  };

  for (const value of Object.values(normalized)) {
    if (!Number.isInteger(value)) throw new Error('时间格式错误');
  }
  if (normalized.start_hour < 0 || normalized.start_hour > 23 ||
      normalized.end_hour < 0 || normalized.end_hour > 23 ||
      normalized.start_minute < 0 || normalized.start_minute > 59 ||
      normalized.end_minute < 0 || normalized.end_minute > 59) {
    throw new Error('时间格式错误');
  }

  const startTotal = normalized.start_hour * 60 + normalized.start_minute;
  const endTotal = normalized.end_hour * 60 + normalized.end_minute;
  if (startTotal >= endTotal) throw new Error('开始时间必须小于结束时间');

  return normalized;
}

function buildGameDayRowsForRange(options) {
  const weekdaySet = new Set(normalizeIsoWeekdays(options.weekdays));
  const time = validateTimeWindow(
    options.startHour,
    options.startMinute,
    options.endHour,
    options.endMinute
  );

  return enumerateDateRange(options.startDate, options.endDate)
    .filter(date => weekdaySet.has(isoWeekday(date)))
    .map(date => ({
      date,
      playable: options.playable ? 1 : 0,
      is_holiday: options.isHoliday ? 1 : 0,
      is_workday_override: options.isWorkdayOverride ? 1 : 0,
      start_hour: time.start_hour,
      start_minute: time.start_minute,
      end_hour: time.end_hour,
      end_minute: time.end_minute,
      max_minutes: toNonNegativeInt(options.maxMinutes),
      label: String(options.label || ''),
    }));
}

function defaultConfigFromMap(values, options = {}) {
  const weekdays = normalizeIsoWeekdays(values.default_playable_weekdays);
  if (!weekdays.length && options.requireWeekdays) {
    throw new Error('默认可玩星期不能为空');
  }
  return {
    default_start_hour: Number(values.default_start_hour ?? values.start_hour ?? 20),
    default_start_minute: Number(values.default_start_minute ?? values.start_minute ?? 0),
    default_end_hour: Number(values.default_end_hour ?? values.end_hour ?? 21),
    default_end_minute: Number(values.default_end_minute ?? values.end_minute ?? 0),
    default_playable_weekdays: weekdays.length ? weekdays : DEFAULT_WEEKDAYS.slice(),
    default_max_minutes: toNonNegativeInt(values.default_max_minutes),
  };
}

function readDefaultConfig(db) {
  const rows = db.prepare('SELECT key, value FROM default_config').all();
  const values = {};
  for (const row of rows) values[row.key] = row.value;
  return defaultConfigFromMap(values);
}

function writeDefaultConfig(db, config) {
  const defaults = defaultConfigFromMap(config || {}, { requireWeekdays: true });
  validateTimeWindow(
    defaults.default_start_hour,
    defaults.default_start_minute,
    defaults.default_end_hour,
    defaults.default_end_minute
  );

  const entries = [
    ['default_start_hour', String(defaults.default_start_hour)],
    ['default_start_minute', String(defaults.default_start_minute)],
    ['default_end_hour', String(defaults.default_end_hour)],
    ['default_end_minute', String(defaults.default_end_minute)],
    ['default_playable_weekdays', defaults.default_playable_weekdays.join(',')],
    ['default_max_minutes', String(defaults.default_max_minutes)],
  ];
  const stmt = db.prepare('INSERT OR REPLACE INTO default_config (key, value) VALUES (?, ?)');
  const write = db.transaction
    ? db.transaction(items => items.forEach(item => stmt.run(item[0], item[1])))
    : items => items.forEach(item => stmt.run(item[0], item[1]));
  write(entries);
  return defaults;
}

function isDefaultPlayableDay(date, weekdays = DEFAULT_WEEKDAYS) {
  return normalizeIsoWeekdays(weekdays).includes(isoWeekday(date));
}

module.exports = {
  DEFAULT_WEEKDAYS,
  toNonNegativeInt,
  normalizeIsoWeekdays,
  isIsoDate,
  isoWeekday,
  enumerateDateRange,
  validateTimeWindow,
  buildGameDayRowsForRange,
  readDefaultConfig,
  writeDefaultConfig,
  isDefaultPlayableDay,
};

// SPDX-License-Identifier: MIT
'use strict';
/**
 * One small interface over two drivers:
 *   run(sql, params) -> { insertId, changes }   get(sql, params) -> row | undefined
 *   all(sql, params) -> rows                    tx(async db => ...) -> result
 * SQL is kept to the subset both SQLite and MySQL accept; timestamps are
 * integer seconds so no date functions are needed.
 */
const fs = require('node:fs');
const path = require('node:path');

const TABLES = (pk) => [
  `CREATE TABLE IF NOT EXISTS users (
    id ${pk},
    username VARCHAR(32) NOT NULL,
    username_key VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    is_admin INTEGER NOT NULL DEFAULT 0,
    banned INTEGER NOT NULL DEFAULT 0,
    active_character_id BIGINT NULL,
    last_play_at BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    last_login_at BIGINT NOT NULL DEFAULT 0
  )`,
  `CREATE TABLE IF NOT EXISTS tokens (
    id ${pk},
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS characters (
    id ${pk},
    user_id BIGINT NOT NULL,
    name VARCHAR(32) NOT NULL,
    name_key VARCHAR(32) NOT NULL UNIQUE,
    model VARCHAR(64) NOT NULL,
    play_seconds BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS rooms (
    id ${pk},
    user_id BIGINT NOT NULL UNIQUE,
    host_name VARCHAR(32) NOT NULL,
    title VARCHAR(64) NOT NULL,
    host_address VARCHAR(64) NOT NULL,
    room_code CHAR(6) NOT NULL,
    virtual_lan INTEGER NOT NULL DEFAULT 0,
    max_players INTEGER NOT NULL,
    players INTEGER NOT NULL DEFAULT 1,
    game_version VARCHAR(32) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    heartbeat_at BIGINT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS races (
    id ${pk},
    reporter_user_id BIGINT NOT NULL,
    course_name VARCHAR(64) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    laps INTEGER NOT NULL DEFAULT 1,
    checkpoints INTEGER NOT NULL DEFAULT 0,
    entrants INTEGER NOT NULL,
    created_at BIGINT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS race_results (
    id ${pk},
    race_id BIGINT NOT NULL,
    character_id BIGINT NULL,
    character_name VARCHAR(32) NOT NULL,
    position INTEGER NULL,
    time_ms BIGINT NULL,
    finished INTEGER NOT NULL DEFAULT 0,
    reason VARCHAR(64) NOT NULL DEFAULT ''
  )`,
  `CREATE TABLE IF NOT EXISTS dm_matches (
    id ${pk},
    host_user_id BIGINT NOT NULL,
    host_name VARCHAR(32) NOT NULL,
    target INTEGER NOT NULL,
    minutes INTEGER NOT NULL,
    red_score INTEGER NOT NULL,
    blue_score INTEGER NOT NULL,
    winner VARCHAR(8) NOT NULL,
    reason VARCHAR(16) NOT NULL,
    started_at BIGINT NOT NULL,
    ended_at BIGINT NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS dm_players (
    id ${pk},
    match_id BIGINT NOT NULL,
    character_id BIGINT NULL,
    character_name VARCHAR(32) NOT NULL,
    team VARCHAR(8) NOT NULL,
    kills INTEGER NOT NULL DEFAULT 0,
    deaths INTEGER NOT NULL DEFAULT 0,
    won INTEGER NOT NULL DEFAULT 0
  )`,
  // One row per death in a public free-for-all room; the boards are sums over a time range.
  `CREATE TABLE IF NOT EXISTS ffa_kills (
    id ${pk},
    at BIGINT NOT NULL,
    room INTEGER NOT NULL DEFAULT 1,
    killer_character_id BIGINT NULL,
    killer_name VARCHAR(32) NOT NULL DEFAULT '',
    victim_character_id BIGINT NULL,
    victim_name VARCHAR(32) NOT NULL
  )`,
  `CREATE TABLE IF NOT EXISTS seasons (
    id ${pk},
    started_at BIGINT NOT NULL,
    ends_at BIGINT NOT NULL
  )`,
  // Rank points of one character in one season.
  `CREATE TABLE IF NOT EXISTS rank_points (
    season_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    name VARCHAR(32) NOT NULL,
    points INTEGER NOT NULL DEFAULT 0,
    kills INTEGER NOT NULL DEFAULT 0,
    wins INTEGER NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY (season_id, character_id)
  )`,
  `CREATE TABLE IF NOT EXISTS saves (
    character_id BIGINT PRIMARY KEY,
    size BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    updated_at BIGINT NOT NULL
  )`,
];
const INDEXES = [
  ['idx_tokens_user', 'tokens(user_id)'],
  ['idx_characters_user', 'characters(user_id)'],
  ['idx_rooms_heartbeat', 'rooms(heartbeat_at)'],
  ['idx_results_race', 'race_results(race_id)'],
  ['idx_results_character', 'race_results(character_id)'],
  ['idx_dm_players_match', 'dm_players(match_id)'],
  ['idx_dm_players_character', 'dm_players(character_id)'],
  ['idx_ffa_kills_at', 'ffa_kills(at)'],
];

async function openSqlite(config) {
  const { DatabaseSync } = require('node:sqlite');
  const file = config.sqliteFile || path.join(config.dataDir, 'xhynv.sqlite');
  if (file !== ':memory:') fs.mkdirSync(path.dirname(file), { recursive: true });
  const handle = new DatabaseSync(file);
  handle.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  const api = {
    driver: 'sqlite',
    async run(sql, params = []) {
      const result = handle.prepare(sql).run(...params);
      return { insertId: Number(result.lastInsertRowid), changes: Number(result.changes) };
    },
    async get(sql, params = []) { return handle.prepare(sql).get(...params); },
    async all(sql, params = []) { return handle.prepare(sql).all(...params); },
  };
  // A single connection: serialize transactions so awaits cannot interleave them.
  let queue = Promise.resolve();
  api.tx = (work) => {
    const next = queue.then(async () => {
      handle.exec('BEGIN IMMEDIATE');
      try {
        const result = await work(api);
        handle.exec('COMMIT');
        return result;
      } catch (error) {
        handle.exec('ROLLBACK');
        throw error;
      }
    });
    queue = next.catch(() => {});
    return next;
  };
  api.close = async () => handle.close();
  for (const ddl of TABLES('INTEGER PRIMARY KEY AUTOINCREMENT')) handle.exec(ddl);
  for (const [name, target] of INDEXES) handle.exec(`CREATE INDEX IF NOT EXISTS ${name} ON ${target}`);
  return api;
}

async function openMysql(config) {
  let mysql;
  try { mysql = require('mysql2/promise'); } catch { throw new Error('DB_DRIVER=mysql needs the mysql2 package: run "npm install mysql2"'); }
  const pool = mysql.createPool({ ...config.mysql, connectionLimit: 10, charset: 'utf8mb4', supportBigNumbers: true });
  const wrap = (executor) => ({
    driver: 'mysql',
    async run(sql, params = []) { const [r] = await executor.query(sql, params); return { insertId: Number(r.insertId), changes: Number(r.affectedRows) }; },
    async get(sql, params = []) { const [rows] = await executor.query(sql, params); return rows[0]; },
    async all(sql, params = []) { const [rows] = await executor.query(sql, params); return rows; },
  });
  const api = wrap(pool);
  api.tx = async (work) => {
    const connection = await pool.getConnection();
    try {
      await connection.beginTransaction();
      const result = await work(wrap(connection));
      await connection.commit();
      return result;
    } catch (error) {
      await connection.rollback();
      throw error;
    } finally {
      connection.release();
    }
  };
  api.close = () => pool.end();
  for (const ddl of TABLES('BIGINT PRIMARY KEY AUTO_INCREMENT')) await pool.query(ddl + ' ENGINE=InnoDB DEFAULT CHARSET=utf8mb4');
  for (const [name, target] of INDEXES) {
    try { await pool.query(`CREATE INDEX ${name} ON ${target}`); } catch (error) { if (error.code !== 'ER_DUP_KEYNAME') throw error; }
  }
  return api;
}

module.exports = { open: (config) => (config.dbDriver === 'mysql' ? openMysql(config) : openSqlite(config)) };

// SPDX-License-Identifier: MIT
'use strict';
const fs = require('node:fs');
const path = require('node:path');

/** Minimal .env reader: KEY=VALUE lines, # comments. Real environment wins. */
function loadEnvFile(file) {
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/.exec(line);
    if (!match || line.trim().startsWith('#')) continue;
    if (process.env[match[1]] === undefined) process.env[match[1]] = match[2].replace(/^(['"])(.*)\1$/, '$2');
  }
}

function integer(name, fallback, min, max) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number(raw);
  if (!Number.isInteger(value) || value < min || value > max) throw new Error(`${name} must be an integer ${min}..${max}`);
  return value;
}

function load(overrides = {}) {
  loadEnvFile(path.resolve(__dirname, '..', '.env'));
  const config = {
    host: process.env.HOST || '127.0.0.1',
    port: integer('PORT', 3080, 1, 65535),
    trustProxy: process.env.TRUST_PROXY === '1',
    allowEmbedding: process.env.ALLOW_EMBEDDING === '1',
    dbDriver: (process.env.DB_DRIVER || 'sqlite').toLowerCase(),
    dataDir: path.resolve(__dirname, '..', process.env.DATA_DIR || './data'),
    mysql: {
      host: process.env.MYSQL_HOST || '127.0.0.1',
      port: integer('MYSQL_PORT', 3306, 1, 65535),
      user: process.env.MYSQL_USER || 'xhynv',
      password: process.env.MYSQL_PASSWORD || '',
      database: process.env.MYSQL_DATABASE || 'xhynv',
    },
    inviteCode: process.env.INVITE_CODE || '',
    maxCharacters: integer('MAX_CHARACTERS', 3, 1, 20),
    maxSaveBytes: integer('MAX_SAVE_MB', 8, 1, 64) * 1024 * 1024,
    tokenDays: 30,
    roomTimeoutSeconds: 90,
    relayPort: integer('RELAY_PORT', 27950, 0, 65535),
    relayMaxKbps: integer('RELAY_MAX_KBPS', 6000, 100, 1000000),
    relayMaxMembers: 10,
    relayIdleSeconds: 600,
    ...overrides,
  };
  if (!['sqlite', 'mysql'].includes(config.dbDriver)) throw new Error('DB_DRIVER must be sqlite or mysql');
  return config;
}

module.exports = { load };

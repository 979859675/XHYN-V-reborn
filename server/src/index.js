// SPDX-License-Identifier: MIT
'use strict';
const { load } = require('./config');
const { open } = require('./db');
const { createApp } = require('./app');
const { createRelay } = require('./relay');

async function main() {
  const config = load();
  const db = await open(config);
  const relay = createRelay(config);
  if (config.relayPort) {
    // The relay must be reachable from the internet directly; it cannot go through the HTTP reverse proxy.
    await relay.start(config.relayPort, '0.0.0.0');
    console.log(`UDP relay listening on 0.0.0.0:${relay.port}`);
  }
  const server = createApp(config, db, relay).listen(config.port, config.host, () => {
    console.log(`星战GTA5Online listening on http://${config.host}:${config.port} (${config.dbDriver})`);
  });
  const stop = () => server.close(() => Promise.allSettled([relay.close(), db.close()]).finally(() => process.exit(0)));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((error) => { console.error(error.message); process.exit(1); });

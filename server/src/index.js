// SPDX-License-Identifier: MIT
'use strict';
const { load } = require('./config');
const { open } = require('./db');
const { createApp } = require('./app');

async function main() {
  const config = load();
  const db = await open(config);
  const server = createApp(config, db).listen(config.port, config.host, () => {
    console.log(`XHYN V Online listening on http://${config.host}:${config.port} (${config.dbDriver})`);
  });
  const stop = () => server.close(() => db.close().finally(() => process.exit(0)));
  process.on('SIGINT', stop);
  process.on('SIGTERM', stop);
}

main().catch((error) => { console.error(error.message); process.exit(1); });

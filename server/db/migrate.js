require('dotenv').config();
const { pool, runMigrations } = require('./store');

runMigrations()
    .then(() => {
        console.log('[DB] Migrations complete');
    })
    .catch(error => {
        console.error('[DB] Migration failed:', error);
        process.exitCode = 1;
    })
    .finally(() => pool.end());

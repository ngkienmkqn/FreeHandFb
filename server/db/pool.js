const { Pool } = require('pg');

const DEFAULT_DATABASE_URL = 'postgres://xmb:xmb@127.0.0.1:5432/xommuaban';

const pool = new Pool({
    connectionString: process.env.DATABASE_URL || DEFAULT_DATABASE_URL
});

module.exports = { pool, DEFAULT_DATABASE_URL };

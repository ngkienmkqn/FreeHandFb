'use strict';

/**
 * Prefer accounts already known to have joined the group when claiming.
 * Empty joinedAccounts → allow (bootstrap / cold start).
 * Otherwise only joinedAccounts members or membership.status === JOINED.
 *
 * @param {object} intel
 * @param {string} username
 * @returns {boolean}
 */
function preferJoinedGate(intel, username) {
  const joinedNames = Object.keys(intel?.joinedAccounts || {});
  if (joinedNames.length === 0) return true;
  if (joinedNames.includes(username)) return true;
  return intel.accountMembership?.[username]?.status === 'JOINED';
}

module.exports = { preferJoinedGate };

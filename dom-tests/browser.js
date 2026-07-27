'use strict';

/**
 * One browser launch owner for every Desk lane. CI uses Playwright's managed browser; isolated
 * review containers may point at their packaged Chromium without editing each suite or silently
 * changing launch flags in only one lane.
 */
function launchChromium(options = {}) {
  /*
   * Keep Playwright behind the launch boundary. `node lane.js … --list` intentionally runs before
   * `npm ci` in a clean CI checkout: inventorying committed tests and helpers must not depend on an
   * installed browser package. Actual test execution reaches this function only after the runtime
   * has been installed.
   */
  const { chromium } = require('playwright');
  const executablePath = process.env.CHROMIUM_EXECUTABLE_PATH || null;
  const args = executablePath && process.env.CHROMIUM_NO_SANDBOX === 'true'
    ? ['--no-sandbox', '--disable-setuid-sandbox'] : [];
  return chromium.launch({
    headless: true,
    ...options,
    ...(executablePath ? { executablePath } : {}),
    args: [...args, ...(options.args || [])]
  });
}

module.exports = { launchChromium };

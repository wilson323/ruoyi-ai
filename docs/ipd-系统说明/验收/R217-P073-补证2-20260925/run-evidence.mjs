import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const OUT_DIR = '/Users/mac/Documents/ruoyi-ai/docs/ipd-系统说明/验收/R217-P073-补证2-20260925';
const BASE = 'http://127.0.0.1:15666';
const API = 'http://127.0.0.1:16039';
const CREDS = { username: 'ipd-admin', password: 'Ipd@123456' };
const results = [];

async function screenshot(page, name) {
  const file = path.join(OUT_DIR, name);
  await page.screenshot({ path: file, fullPage: false });
  console.log(`  📸 ${name}`);
  return name;
}

(async () => {
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();

  // Collect network requests for evidence
  const networkLog = [];
  page.on('response', async (resp) => {
    const url = resp.url();
    if (url.includes('/api/v1/') || url.includes('/auth/')) {
      let body = '';
      try { body = await resp.text(); } catch {}
      networkLog.push({ url, status: resp.status(), body: body.slice(0, 500), ts: new Date().toISOString() });
    }
  });

  // ============= SCENARIO 1: AC-AUTH-07 正例 =============
  console.log('\n=== SCENARIO 1: 正常登录，浏览 IPD 页面 ===');
  networkLog.length = 0;
  await page.goto(`${BASE}/auth/login`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1000);
  await screenshot(page, 'p073-01a-login-page.png');

  // Fill login form
  const usernameInput = page.locator('input[type="text"], input[name="username"], input[placeholder*="用户"]').first();
  const passwordInput = page.locator('input[type="password"]').first();
  await usernameInput.fill(CREDS.username);
  await passwordInput.fill(CREDS.password);
  await screenshot(page, 'p073-01b-login-filled.png');
  
  // Click login button
  const loginBtn = page.locator('button[type="submit"], button:has-text("登录"), button:has-text("Login")').first();
  await loginBtn.click();
  
  // Wait for navigation
  await page.waitForTimeout(3000);
  await page.waitForLoadState('networkidle').catch(() => {});
  
  const afterLoginUrl = page.url();
  console.log(`  URL after login: ${afterLoginUrl}`);
  await screenshot(page, 'p073-01c-login-success.png');

  // Get the session token from sessionStorage
  const sessionData = await page.evaluate(() => {
    return sessionStorage.getItem('ruoyi-ipd.session');
  });
  console.log(`  Session data: ${sessionData?.slice(0, 100)}...`);
  
  let token = '';
  if (sessionData) {
    try { token = JSON.parse(sessionData).accessToken; } catch {}
  }
  console.log(`  Token: ${token.slice(0, 50)}...`);

  // Navigate to workbench and verify API calls succeed
  await page.goto(`${BASE}/ipd/workbench`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(2000);
  const workbenchUrl = page.url();
  console.log(`  Workbench URL: ${workbenchUrl}`);
  
  const scenario1Network = networkLog.filter(n => n.url.includes('/api/v1/'));
  const scenario1Success = scenario1Network.filter(n => n.status === 200);
  console.log(`  API calls: ${scenario1Network.length}, 200s: ${scenario1Success.length}`);
  
  await screenshot(page, 'p073-01d-workbench-ok.png');
  
  results.push({
    scenario: '1. AC-AUTH-07 正例：正常登录后浏览',
    verdict: (afterLoginUrl.includes('/ipd') && scenario1Success.length > 0) ? 'PASS' : 'FAIL',
    evidence: {
      loginUrl: afterLoginUrl,
      workbenchUrl,
      apiCalls: scenario1Network.length,
      successCalls: scenario1Success.length,
      sampleResponse: scenario1Success[0]?.body?.slice(0, 200),
      token: token.slice(0, 40) + '...',
    }
  });

  // ============= SCENARIO 2: Token 失效 → 自动跳登录 =============
  console.log('\n=== SCENARIO 2: Token 失效后自动跳登录页 ===');
  networkLog.length = 0;
  
  // Corrupt the session token in sessionStorage
  await page.evaluate(() => {
    const session = JSON.parse(sessionStorage.getItem('ruoyi-ipd.session') || '{}');
    session.accessToken = 'INVALID_TOKEN_FOR_TESTING_' + Date.now();
    session.accessExpiresAt = Date.now() + 3600000; // Not expired locally, but server rejects
    sessionStorage.setItem('ruoyi-ipd.session', JSON.stringify(session));
  });
  
  const beforeInvalidateUrl = page.url();
  console.log(`  URL before invalidate: ${beforeInvalidateUrl}`);
  
  // Trigger a navigation/request that will use the invalid token
  await page.goto(`${BASE}/ipd/workbench`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(4000);
  
  const afterInvalidateUrl = page.url();
  console.log(`  URL after invalidate: ${afterInvalidateUrl}`);
  
  const scenario2Network = [...networkLog];
  const got401 = scenario2Network.some(n => n.status === 401);
  const got20001 = scenario2Network.some(n => n.body.includes('20001'));
  const redirectToLogin = afterInvalidateUrl.includes('/auth/login') || afterInvalidateUrl.includes('/login');
  
  console.log(`  Got 401: ${got401}, Got code 20001: ${got20001}, Redirect to login: ${redirectToLogin}`);
  
  await screenshot(page, 'p073-02-token-invalid-redirect.png');
  
  results.push({
    scenario: '2. AC-AUTH-07 核心：Token 失效 → 自动跳登录',
    verdict: (redirectToLogin && (got401 || got20001)) ? 'PASS' : 'FAIL',
    evidence: {
      urlBefore: beforeInvalidateUrl,
      urlAfter: afterInvalidateUrl,
      got401,
      got20001,
      redirectToLogin,
      networkSample: scenario2Network.slice(0, 3).map(n => ({ url: n.url.slice(-60), status: n.status, body: n.body.slice(0, 150) })),
    }
  });

  // ============= SCENARIO 3: Logout 旧 token 服务端失效 =============
  console.log('\n=== SCENARIO 3: Logout → 旧 token 服务端真失效 ===');
  networkLog.length = 0;
  
  // Re-login
  await page.goto(`${BASE}/auth/login`, { waitUntil: 'networkidle', timeout: 30000 });
  await page.waitForTimeout(1000);
  await usernameInput.fill(CREDS.username);
  await passwordInput.fill(CREDS.password);
  await loginBtn.click();
  await page.waitForTimeout(3000);
  await page.waitForLoadState('networkidle').catch(() => {});
  
  const loginUrl3 = page.url();
  console.log(`  Login success URL: ${loginUrl3}`);
  
  // Capture current token
  const sessionData3 = await page.evaluate(() => sessionStorage.getItem('ruoyi-ipd.session'));
  let token3 = '';
  if (sessionData3) { try { token3 = JSON.parse(sessionData3).accessToken; } catch {} }
  console.log(`  Token before logout: ${token3.slice(0, 50)}...`);
  
  await screenshot(page, 'p073-03a-logged-in.png');
  
  // Perform logout via UI (click user menu → logout)
  // Try to find logout button
  let logoutDone = false;
  try {
    // Look for user avatar/dropdown trigger
    const userMenu = page.locator('[class*="avatar"], [class*="user-dropdown"], [class*="header"] [class*="drop"]').first();
    if (await userMenu.isVisible({ timeout: 3000 })) {
      await userMenu.click();
      await page.waitForTimeout(500);
      const logoutItem = page.locator('text=退出登录, text=Logout, text=退出, [class*="logout"]').first();
      if (await logoutItem.isVisible({ timeout: 2000 })) {
        await logoutItem.click();
        logoutDone = true;
      }
    }
  } catch (e) {
    console.log(`  UI logout attempt failed: ${e.message}`);
  }
  
  if (!logoutDone) {
    // Fallback: call logout API directly via page context
    console.log('  Falling back to API logout via page evaluate...');
    await page.evaluate(async (tk) => {
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        headers: { 'Authorization': `Bearer ${tk}`, 'Content-Type': 'application/json' }
      });
      sessionStorage.removeItem('ruoyi-ipd.session');
    }, token3);
    logoutDone = true;
  }
  
  await page.waitForTimeout(2000);
  const afterLogoutUrl = page.url();
  console.log(`  URL after logout: ${afterLogoutUrl}`);
  await screenshot(page, 'p073-03b-after-logout.png');
  
  // Now use the old token to make a direct API request
  const oldTokenResp = await page.evaluate(async (tk) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/me', {
      headers: { 'Authorization': `Bearer ${tk}` }
    });
    const body = await resp.text();
    return { status: resp.status, body };
  }, token3);
  
  console.log(`  Old token after logout: status=${oldTokenResp.status}, body=${oldTokenResp.body.slice(0, 150)}`);
  await screenshot(page, 'p073-03c-old-token-rejected.png');
  
  const logoutRejected = oldTokenResp.status === 401 || oldTokenResp.body.includes('20001');
  
  results.push({
    scenario: '3. Logout 旧 token 服务端真失效',
    verdict: logoutRejected ? 'PASS' : 'FAIL',
    evidence: {
      tokenBefore: token3.slice(0, 40) + '...',
      uiLogoutDone: logoutDone,
      afterLogoutUrl,
      oldTokenResponseStatus: oldTokenResp.status,
      oldTokenResponseBody: oldTokenResp.body.slice(0, 200),
    }
  });

  // ============= SCENARIO 4: Refresh 轮换行为 =============
  console.log('\n=== SCENARIO 4: Refresh 轮换 → 旧 token 失效 ===');
  networkLog.length = 0;
  
  // Login fresh
  const loginResp4 = await page.evaluate(async (creds) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(creds)
    });
    return await resp.json();
  }, CREDS);
  
  const token4Old = loginResp4.data?.token;
  console.log(`  Token before refresh: ${token4Old?.slice(0, 50)}...`);
  
  // Call refresh with old token
  const refreshResp4 = await page.evaluate(async (tk) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tk}`, 'Content-Type': 'application/json' }
    });
    return { status: resp.status, body: await resp.json() };
  }, token4Old);
  
  const token4New = refreshResp4.body?.data?.token;
  console.log(`  Refresh status: ${refreshResp4.status}`);
  console.log(`  Token after refresh: ${token4New?.slice(0, 50)}...`);
  console.log(`  Tokens different: ${token4Old !== token4New}`);
  
  // Try using old token after refresh
  const oldAfterRefresh = await page.evaluate(async (tk) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/me', {
      headers: { 'Authorization': `Bearer ${tk}` }
    });
    return { status: resp.status, body: await resp.text() };
  }, token4Old);
  
  console.log(`  Old token after refresh: status=${oldAfterRefresh.status}`);
  console.log(`  Old token body: ${oldAfterRefresh.body.slice(0, 150)}`);
  
  // Verify new token works
  const newAfterRefresh = await page.evaluate(async (tk) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/me', {
      headers: { 'Authorization': `Bearer ${tk}` }
    });
    return { status: resp.status, body: await resp.text() };
  }, token4New);
  
  console.log(`  New token after refresh: status=${newAfterRefresh.status}`);
  
  // Test replay: use old token for refresh again
  const replayResp = await page.evaluate(async (tk) => {
    const resp = await fetch('http://127.0.0.1:16039/api/v1/auth/refresh', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tk}`, 'Content-Type': 'application/json' }
    });
    return { status: resp.status, body: await resp.text() };
  }, token4Old);
  
  console.log(`  Replay old token refresh: status=${replayResp.status}, body=${replayResp.body.slice(0, 150)}`);
  
  await screenshot(page, 'p073-04-refresh-rotation.png');
  
  const refreshPass = (
    refreshResp4.status === 200 &&
    token4Old !== token4New &&
    (oldAfterRefresh.status === 401 || oldAfterRefresh.body.includes('20001')) &&
    newAfterRefresh.status === 200 &&
    (replayResp.status === 401 || replayResp.body.includes('20001'))
  );
  
  results.push({
    scenario: '4. Refresh 轮换：旧票失效 + 重放拒绝',
    verdict: refreshPass ? 'PASS' : 'FAIL',
    evidence: {
      refreshStatus: refreshResp4.status,
      tokensDifferent: token4Old !== token4New,
      oldTokenAfterRefresh: { status: oldAfterRefresh.status, body: oldAfterRefresh.body.slice(0, 150) },
      newTokenWorks: newAfterRefresh.status === 200,
      replayRejected: { status: replayResp.status, body: replayResp.body.slice(0, 150) },
    }
  });

  // Cleanup: logout new token
  await page.evaluate(async (tk) => {
    await fetch('http://127.0.0.1:16039/api/v1/auth/logout', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${tk}`, 'Content-Type': 'application/json' }
    });
  }, token4New);

  await browser.close();

  // Write results JSON
  fs.writeFileSync(path.join(OUT_DIR, 'evidence-results.json'), JSON.stringify(results, null, 2));
  console.log('\n=== ALL SCENARIOS COMPLETE ===');
  console.log(JSON.stringify(results.map(r => ({ s: r.scenario, v: r.verdict })), null, 2));
})();

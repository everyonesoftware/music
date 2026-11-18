import { chromium } from 'playwright';
import assert from 'node:assert/strict';

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const url = new URL('./index.html', import.meta.url).href;
try {
  for (const platform of ['windows', 'android']) {
    const originalPage = await browser.newPage({ viewport: { width: 1500, height: 1120 } });
    const originalErrors = [];
    originalPage.on('pageerror', error => originalErrors.push(error.message));
    await originalPage.goto(new URL('../update-changelog/index.html', import.meta.url).href);
    const button = originalPage.locator(`[data-platform=${platform}] .download`);
    assert.equal(await button.getAttribute('data-phase'), 'available', `${platform}: fresh original starts available`);
    assert.equal(await button.isDisabled(), false);
    for (const phase of ['downloading', 'ready']) {
      await button.click();
      assert.equal(await button.getAttribute('data-phase'), phase, `${platform}: original reaches ${phase}`);
      assert.equal(await button.isDisabled(), false);
    }
    await button.click();
    assert.equal(await button.isDisabled(), true, `${platform}: original completes after installation`);
    assert.equal(await button.textContent(), 'Installation preview complete');
    assert.deepEqual(originalErrors, []);
    await originalPage.close();
  }
  const page = await browser.newPage({ viewport: { width: 1500, height: 1120 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  assert.equal(await page.locator('[data-variant],.automatic,.installed,.version-range,.install-copy,.security').count(), 0);
  assert.equal(await page.locator('.grouped-disclosure[open]').count(), 0);
  for (const [platform, current, target, count, groups] of [
    ['windows', '0.1.37', '0.1.43', 2, ['Improvements']],
    ['android', '0.1.73', '0.1.81', 3, ['Improvements', 'Fixes']],
  ]) {
    const panel = page.locator(`[data-platform=${platform}]`);
    assert.equal(await panel.locator('.section-head h3').textContent(), `Current Version: ${current}`);
    assert.equal(await panel.locator('.offer-head .offer-title').textContent(), `Music ${target}`);
    await panel.locator('.compact-toggle').focus();
    await page.keyboard.press('Enter');
    assert.equal(await panel.locator('.list-content li:visible').count(), count);
    assert.deepEqual(await panel.locator('.list-content h4').allTextContents(), groups);
    await panel.locator('.source summary').click();
    assert.equal(await panel.locator('.source').getAttribute('open'), '');
    await panel.locator('.compact-toggle').click();
    assert.equal(await panel.locator('.list-content li:visible').count(), 0);
  }
  for (const mode of ['desktop', 'phone', 'both']) {
    await page.selectOption('#view', mode);
    assert.equal(await page.locator('.desktop').isVisible(), mode !== 'phone');
    assert.equal(await page.locator('.phone-column').isVisible(), mode !== 'desktop');
  }
  const dimensions = () => page.locator('.phone-screen').evaluate(el => [el.clientWidth, el.clientHeight]);
  const initial = await dimensions();
  for (const scale of ['25', '200', '50']) {
    await page.locator('#scale').evaluate((el, value) => { el.value = value; el.dispatchEvent(new Event('input')); }, scale);
    assert.deepEqual(await dimensions(), initial);
  }
  for (const state of ['loading', 'unavailable', 'current', 'available']) {
    await page.selectOption('#state', state);
    assert.equal(await page.locator('[data-platform=windows] .offer').isVisible(), state !== 'current');
    if (state === 'unavailable') await page.locator('[data-platform=windows] .notes-status button').click();
  }
  for (const platform of ['windows', 'android']) {
    const button = page.locator(`[data-platform=${platform}] .download`);
    await button.click();
    assert.equal(await button.getAttribute('data-phase'), 'downloading');
    await button.click();
    assert.equal(await button.getAttribute('data-phase'), 'ready');
    await button.click();
    assert.equal(await button.isDisabled(), true);
  }
  await page.click('#reset');
  assert.equal(await page.locator('.download:disabled,.grouped-disclosure[open]').count(), 0);
  await page.setViewportSize({ width: 580, height: 1000 });
  assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  const context = await browser.newContext({ javaScriptEnabled: false });
  const staticPage = await context.newPage();
  await staticPage.goto(url);
  await staticPage.locator('[data-platform=android] .compact-toggle').click();
  assert.equal(await staticPage.locator('[data-platform=android] .list-content li:visible').count(), 3);
  assert.deepEqual(errors, []);
  console.log('PASS: fresh original download lifecycle on both platforms, compact headings, grouped disclosures, viewer/calibration, preview states, simulated updates/reset, narrow layout, and script-free disclosure.');
} finally {
  await browser.close();
}

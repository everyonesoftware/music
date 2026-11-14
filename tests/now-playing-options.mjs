import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 1120 } });
const errors = [];
page.on('pageerror', e => errors.push(e.message));
try {
  await page.goto(new URL('../mockups/now-playing-queue-options.html', import.meta.url).href);
  assert.equal(await page.locator('.phone').count(), 3);
  for (const phone of await page.locator('.phone').all()) {
    assert.equal(await phone.locator('.top button[aria-label="Open queue"]').count(), 0);
    const queue = await phone.getByRole('button', { name: 'Open queue', exact: true }).boundingBox();
    const play = await phone.getByRole('button', { name: 'Pause', exact: true }).boundingBox();
    assert.ok(queue.y >= play.y + play.height);
    await phone.getByRole('button', { name: 'Open queue', exact: true }).click();
    assert.equal(await phone.locator('.sheet:not([hidden])').count(), 1);
    await phone.getByRole('button', { name: 'Close queue' }).click();
    await phone.getByRole('button', { name: 'Pause', exact: true }).click();
    assert.equal(await phone.getByRole('button', { name: 'Play', exact: true }).count(), 1);
    await phone.getByRole('button', { name: 'Play', exact: true }).click();
    const shuffle = phone.getByRole('button', { name: 'Shuffle', exact: true });
    await shuffle.click();
    assert.equal(await shuffle.getAttribute('aria-pressed'), 'false');
    await shuffle.click();
    await phone.getByRole('button', { name: 'Repeat', exact: true }).click();
    assert.equal(await phone.getByRole('button', { name: 'Repeat', exact: true }).getAttribute('aria-pressed'), 'true');
    await phone.getByRole('slider', { name: 'Playback position' }).fill('90');
    assert.equal(await phone.locator('.elapsed').innerText(), '1:30');
  }
  await page.getByRole('button', { name: 'Show Library preview' }).click();
  assert.equal(await page.locator('.playlist-card').count(), 3);
  assert.equal(await page.locator('.playlist-card button').count(), 0);
  await page.locator('.playlist-card').first().click();
  assert.match(await page.locator('#library-note').innerText(), /Playback has not started/);
  await page.getByRole('button', { name: 'Show Now Playing options' }).click();
  await mkdir(new URL('../test-results/', import.meta.url), { recursive: true });
  await page.screenshot({ path: new URL('../test-results/now-playing-queue-options.png', import.meta.url).pathname, fullPage: true });
  for (const width of [360, 390, 768, 1440]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `Page overflow at ${width}`);
    for (const phone of await page.locator('.phone').all()) {
      assert.equal(await phone.evaluate(el => el.scrollWidth > el.clientWidth), false, `Phone overflow at ${width}`);
      const q = await phone.getByRole('button', { name: 'Open queue', exact: true }).boundingBox();
      const bounds = await phone.boundingBox();
      assert.ok(q.y + q.height <= bounds.y + bounds.height);
    }
  }
  assert.deepEqual(errors, []);
  console.log('PASS: 3 options, queue placement/navigation, playback toggles, clean Library cards, 4 responsive widths, no JS errors.');
} finally { await browser.close(); }

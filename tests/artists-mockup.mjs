import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [];
page.on('pageerror', e => errors.push(e.message));
await mkdir(new URL('../test-results/artists/', import.meta.url), { recursive: true });
try {
  await page.goto(new URL('../mockups/artists/index.html', import.meta.url).href);
  await page.screenshot({ path: fileURLToPath(new URL('../test-results/artists/library.png', import.meta.url)), fullPage: true });
  for (const platform of ['desktop', 'phone']) {
    await page.locator('[data-scene="library"]').click();
    const app = page.locator(`[data-platform="${platform}"]`);
    assert.deepEqual(await app.locator('.section-head h3').allTextContents(), ['Playlists', 'Albums', 'Artists']);
    await app.getByRole('button', { name: 'Open Taylor Swift', exact: true }).click();
    assert.match(await app.locator('.artist-heading').innerText(), /4 albums · 12 songs in your library/);
    assert.equal(await app.locator('.grid .card').count(), 4);
    const alignment = await app.locator('.artist-heading').evaluate(heading => {
      const buttons = heading.querySelectorAll('.artist-transport button');
      const first = buttons[0].getBoundingClientRect(), last = buttons[1].getBoundingClientRect();
      const bounds = heading.getBoundingClientRect();
      return { text: getComputedStyle(heading.querySelector('h2')).textAlign,
        gap: Math.abs((first.left + last.right) / 2 - (bounds.left + bounds.right) / 2) };
    });
    assert.equal(alignment.text, platform === 'phone' ? 'center' : 'start');
    if (platform === 'phone') assert.ok(alignment.gap < 1, 'Android playback group is horizontally centered');
    assert.deepEqual(await app.locator('.artist-transport button').evaluateAll(buttons => buttons.map(b => ({
      label: b.getAttribute('aria-label'), text: b.textContent.trim(), width: b.offsetWidth, height: b.offsetHeight,
      radius: getComputedStyle(b).borderRadius, background: getComputedStyle(b).backgroundColor
    }))), [
      { label: 'Play all', text: '', width: 56, height: 56, radius: '50%', background: 'rgb(192, 215, 174)' },
      { label: 'Shuffle all', text: '', width: 48, height: 48, radius: '50%', background: platform === 'phone' ? 'rgba(0, 0, 0, 0)' : 'rgb(46, 52, 49)' }
    ]);
    await app.getByRole('button', { name: 'Play all', exact: true }).click();
    assert.deepEqual(await app.locator('.content .song').evaluateAll(rows => rows.map(r => Number(r.dataset.song))), Array.from({ length: 12 }, (_, i) => i));
    await app.getByRole('button', { name: 'View artist' }).click();
    await app.getByRole('button', { name: 'All songs 12' }).click();
    assert.equal(await app.locator('.content .song').count(), 12);
    await app.getByRole('button', { name: 'Shuffle all', exact: true }).click();
    const shuffled = await app.locator('.content .song').evaluateAll(rows => rows.map(r => Number(r.dataset.song)));
    assert.equal(shuffled.length, 12);
    assert.equal(new Set(shuffled).size, 12);
    assert.notDeepEqual(shuffled, Array.from({ length: 12 }, (_, i) => i));
    assert.match(await app.locator('.queue-scope').innerText(), /No playlist created/);
    await app.getByRole('button', { name: 'Pause', exact: true }).click();
    assert.equal(await app.getByRole('button', { name: 'Play', exact: true }).count(), 1);
    await app.getByRole('button', { name: 'View artist' }).click();
    await page.locator('[data-scene="library"]').click();
    await app.getByRole('textbox', { name: 'Search library' }).fill('Taylor Swift');
    assert.equal(await app.locator('.result-artist').count(), 1);
    assert.equal(await app.locator('.content .song').count(), 12);
    await app.locator('.result-artist').click();
    await app.getByRole('button', { name: 'Back', exact: true }).click();
    assert.equal(await app.locator('input').inputValue(), 'Taylor Swift');
    await app.getByRole('textbox', { name: 'Search library' }).fill('zz-no-match');
    assert.match(await app.locator('.empty').innerText(), /No matches/);
    await page.locator('[data-scene="library"]').click();
    await app.locator('[data-route="artists"]').last().click();
    await app.getByRole('textbox', { name: 'Search library' }).fill('Lena');
    assert.equal(await app.locator('.artist-card').count(), 1);
  }
  for (const scene of ['search', 'artist']) {
    await page.locator(`[data-scene="${scene}"]`).click();
    await page.screenshot({ path: fileURLToPath(new URL(`../test-results/artists/${scene}.png`, import.meta.url)), fullPage: true });
  }
  await page.locator('[data-view="phone"]').click();
  await page.setViewportSize({ width: 390, height: 1000 });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  assert.equal(await page.locator('.phone .primary').isVisible(), true);
  assert.deepEqual(errors, []);
  console.log('PASS: Windows + Android library shelves, artist search, detail, album/song tabs, temporary shuffle queue, pause, back, filtering, and narrow layout. Screenshots captured.');
} finally {
  await browser.close();
}

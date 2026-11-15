import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/opt/data/.local/bin/chromium' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [];
page.on('pageerror', e => errors.push(e.message));
const url = 'http://hermesdan.tail1a7c8f.ts.net:8766/windows-native.html';
try {
  const response = await page.goto(url);
  assert.equal(response.status(), 200);
  await page.locator('img').evaluateAll(images => Promise.all(images.map(i => i.decode())));
  assert.equal(await page.locator('.card').count(), 8);
  assert.equal(await page.locator('img').evaluateAll(imgs => imgs.every(i => i.naturalWidth > 0)), true);
  await mkdir('test-results', { recursive: true });
  for (const variant of ['sage', 'light', 'focus']) {
    await page.locator(`[data-variant="${variant}"]`).click();
    await page.screenshot({ path: `test-results/windows-${variant}.png`, fullPage: true });
    await page.locator('[data-page="library"]').first().click();
    await page.locator('#playlists .card').first().click();
    assert.equal(await page.locator('#detail-title').innerText(), 'Favorites (Unrated)');
    assert.equal(await page.locator('#play').getAttribute('aria-label'), 'Pause');
    await page.locator('#start-playback').click();
    assert.equal(await page.locator('#player-page').isVisible(), true);
    await page.locator('#output').click();
    await page.getByLabel('Wireless headphones', { exact: true }).check();
    await page.locator('#apply-output').click();
    assert.equal(await page.locator('#output-name').innerText(), 'Wireless headphones');
    assert.equal(await page.locator('#output').evaluate(e => e === document.activeElement), true);
    await page.locator('#output').click();
    assert.equal(await page.getByLabel('Wireless headphones', { exact: true }).isChecked(), true);
    await page.keyboard.press('Escape');
    await page.locator('#queue').click();
    assert.equal(await page.locator('#queue-pane').isVisible(), true);
    await page.screenshot({ path: `test-results/windows-${variant}-queue.png`, fullPage: true });
    await page.locator('#close-queue').click();
    await page.locator('#next').click();
    assert.equal(await page.locator('#player-page .song-title').innerText(), 'Maroon');
  }
  await page.locator('[data-variant="sage"]').click();
  await page.locator('#search').fill('no-such-album');
  assert.equal(await page.locator('#no-results').isVisible(), true);
  await page.locator('#search').fill('midnights');
  assert.equal(await page.locator('.card:visible').count(), 1);
  await page.locator('#search').fill('');
  for (const view of ['desktop', 'phone', 'both']) {
    await page.locator('#view').selectOption(view);
    assert.equal(await page.locator('.desktop').isVisible(), view !== 'phone');
    assert.equal(await page.locator('.companion').isVisible(), view !== 'desktop');
  }
  const logical = await page.locator('.phone').evaluate(e => [e.offsetWidth,e.offsetHeight,e.querySelector('h2').offsetHeight]);
  for (const scale of ['25','50','200']) {
    await page.locator('#scale').fill(scale);
    assert.deepEqual(await page.locator('.phone').evaluate(e => [e.offsetWidth,e.offsetHeight,e.querySelector('h2').offsetHeight]), logical);
    const box = await page.locator('#phone-wrap').boundingBox();
    assert.ok(Math.abs(box.width - 390 * Number(scale) / 100) < 1);
    assert.ok(Math.abs(box.height - 828 * Number(scale) / 100) < 1);
  }
  await page.reload();
  assert.equal(await page.locator('#scale').inputValue(), '200');
  await page.locator('#scale').fill('50');
  await page.locator('#view').selectOption('desktop');
  for (const width of [390,768,1440]) {
    await page.setViewportSize({ width, height: 1100 });
    for (const variant of ['sage','light','focus']) {
      await page.locator(`[data-variant="${variant}"]`).click();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `overflow ${width} ${variant}`);
    }
  }
  await page.setViewportSize({ width: 1440, height: 1100 });
  await page.locator('#view').selectOption('both');
  await page.locator('[data-variant="sage"]').click();
  await page.locator('#output').click();
  await page.screenshot({path:'test-results/windows-output.png',fullPage:true});
  await page.keyboard.press('Escape');
  assert.deepEqual(errors, []);
  const staticPage = await browser.newPage({ javaScriptEnabled:false });
  await staticPage.goto(url);
  assert.equal(await staticPage.locator('.card:visible').count(),8);
  await staticPage.close();
  console.log('PASS: HTTP 200; 3 directions; 8 cards; all images loaded; collection/playback navigation; output select/reopen/Escape/focus; queue; search; 3 view modes; uniform 25–200% phone scaling and persistence; 3 responsive widths; static HTML fallback; no JS errors.');
} finally { await browser.close(); }

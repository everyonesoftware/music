import { chromium } from 'playwright';
import assert from 'node:assert/strict';

const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
try {
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  const mockupURL = new URL('../mockups/windows-compact-transport.html', import.meta.url).href;
  await page.goto(mockupURL);
  const snapshot = () => page.evaluate(() => structuredClone(window.previewState));
  const original = await snapshot();
  assert.equal(await page.locator('#back').count(), 0, 'collection details omit the redundant Library back link');
  await page.locator('[data-collection="album"]').click();
  await page.goBack();
  await page.waitForFunction(() => document.querySelector('#detail-type').textContent === 'Playlist');
  assert.deepEqual(await snapshot(), original, 'Back navigation must not change playback');
  await page.locator('#detail-shuffle').click();
  assert.deepEqual(await snapshot(), original, 'choosing shuffle must not change active playback');
  assert.equal(await page.locator('#detail-shuffle').getAttribute('aria-pressed'), 'true');
  await page.locator('#detail-play').click();
  let state = await snapshot();
  assert.equal(state.playing, true); assert.equal(state.mode, 'shuffled');
  assert.deepEqual([...state.queue].sort(), [0,1,2,3,4,5]);
  const firstQueue = state.generation;
  await page.locator('#detail-play').click(); assert.equal((await snapshot()).generation, firstQueue + 1);
  await page.locator('#mini-play').click(); assert.equal((await snapshot()).playing, false);
  await page.locator('#mini-play').click(); assert.equal((await snapshot()).playing, true);
  await page.locator('#mini-play').click(); assert.equal((await snapshot()).playing, false);
  const paused = await snapshot();
  await page.locator('#detail-shuffle').click(); assert.deepEqual(await snapshot(), paused);
  await page.locator('#detail-play').click(); state = await snapshot();
  assert.equal(state.mode, 'ordered'); assert.deepEqual(state.queue, [0,1,2,3,4,5]);
  await page.locator('[data-collection="album"]').click();
  assert.equal(await page.locator('#detail-shuffle').getAttribute('aria-pressed'), 'false');
  await page.locator('#detail-shuffle').click(); await page.locator('[data-collection="playlist"]').click();
  assert.equal(await page.locator('#detail-shuffle').getAttribute('aria-pressed'), 'false');
  await page.locator('[data-collection="album"]').click();
  assert.equal(await page.locator('#detail-shuffle').getAttribute('aria-pressed'), 'true');
  await page.locator('[data-song="3"]').click(); assert.equal((await snapshot()).queue[(await snapshot()).index], 3);
  await page.locator('#reset').click(); assert.deepEqual(await snapshot(), original);
  for (const width of [1440, 1000]) {
    await page.setViewportSize({ width, height: 1000 });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, 'no page overflow');
    for (const collection of ['playlist', 'album']) {
      await page.locator(`[data-collection="${collection}"]`).click();
      const controls = page.locator('.actions button');
      assert.deepEqual(await controls.evaluateAll(nodes => nodes.map(n => n.id)), ['detail-play', 'detail-shuffle', 'download']);
      const boxes = await controls.evaluateAll(nodes => nodes.map(n => { const r=n.getBoundingClientRect(); return {x:r.x,right:r.right,y:r.y,w:r.width,h:r.height}; }));
      const left = await page.locator('.actions').evaluate(n => n.getBoundingClientRect().left);
      assert.ok(Math.abs(boxes[0].x - left) < 1, 'Play starts at the left edge');
      assert.ok(boxes[0].right < boxes[1].x && boxes[1].right < boxes[2].x);
      assert.ok(boxes[1].x - boxes[0].right <= 16 && boxes[2].x - boxes[1].right <= 16, 'controls stay grouped');
      assert.ok(boxes.every(b => b.w >= 44 && b.h >= 44));
      assert.equal((await page.locator('#download').innerText()).trim(), '', 'Download is icon-only');
      assert.equal(await page.locator('#download').getAttribute('aria-label'), 'Download');
    }
  }
  assert.deepEqual(errors, []);
  assert.equal(await page.evaluate(() => {
    const ids = [...document.querySelectorAll('[id]')].map(n => n.id);
    return new Set(ids).size === ids.length;
  }), true, 'all document identifiers are unique');
  await page.setViewportSize({ width:1440,height:1000 });
  if (process.env.MOCKUP_SCREENSHOT) await page.screenshot({ path: process.env.MOCKUP_SCREENSHOT, fullPage:true });
  const noJS = await browser.newPage({ javaScriptEnabled:false });
  await noJS.goto(mockupURL);
  assert.equal(await noJS.locator('#detail-play').isVisible(), true);
  assert.equal(await noJS.locator('[data-song]').count(), 6);
  console.log('PASS: compact Windows mockup interactions, independent mode, queue occurrences, repeat Play, pause/resume, collection scope, reset, geometry, static rendering, no JS errors');
} finally { await browser.close(); }

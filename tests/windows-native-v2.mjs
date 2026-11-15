import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/opt/data/.local/bin/chromium' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const url = 'http://hermesdan.tail1a7c8f.ts.net:8766/windows-native-v2.html';
try {
  assert.equal((await page.goto(url)).status(), 200);
  await page.locator('img').evaluateAll(images => Promise.all(images.map(image => image.decode())));
  assert.equal(await page.locator('img').evaluateAll(images => images.every(image => image.naturalWidth > 0)), true);
  assert.equal(await page.locator('[data-variant]').count(), 0);
  assert.deepEqual(await page.locator('.sidebar .nav').allTextContents(), ['Library','Playlists','Albums','Songs','Settings']);
  assert.equal(await page.locator('.sidebar').innerText().then(text => /Your Collection|Now Playing/i.test(text)), false);
  const ids = await page.locator('[id]').evaluateAll(elements => elements.map(element => element.id));
  assert.equal(new Set(ids).size, ids.length);
  const search = page.locator('#search');
  // Album-only, playlist-only, song-only, mixed, zero matches, and clearing.
  for (const [query, albums, playlists, songs] of [
    ['Attack on Titan',true,false,false], ['Night Drive',false,true,false],
    ['Lavender',false,false,true], ['Midnights',true,false,true],
    ['NO-MATCH-EXAMPLE',false,false,false], ['',true,true,true],
    ['  nIgHt DrIvE  ',false,true,false], ['',true,true,true],
  ]) {
    await search.fill(query);
    assert.equal(await page.locator('#album-section').isVisible(), albums, `albums ${query}`);
    assert.equal(await page.locator('#playlist-section').isVisible(), playlists, `playlists ${query}`);
    assert.equal(await page.locator('#song-section').isVisible(), songs, `songs ${query}`);
    assert.equal(await page.locator('#no-results').isVisible(), !(albums || playlists || songs), `empty ${query}`);
  }
  for (const collection of ['albums','playlists','songs']) {
    await page.locator(`.sidebar [data-page="${collection}"]`).click();
    assert.equal(await page.locator('#library-title').innerText(), collection[0].toUpperCase() + collection.slice(1));
    await search.fill('NO-MATCH-EXAMPLE');
    assert.equal(await page.locator('#no-results').innerText(), `No matching ${collection}.`);
    await search.fill('');
    assert.equal(await page.locator(`#${collection === 'songs' ? 'song' : collection === 'albums' ? 'album' : 'playlist'}-section`).isVisible(), true);
  }
  await page.locator('.sidebar [data-page="songs"]').click();
  assert.equal(await page.locator('.song-result:visible').count(), 5);
  await page.locator('#library-songs [data-track="1"]').click();
  assert.equal(await page.locator('#player-page .song-title').innerText(), 'Maroon');
  await page.locator('#seek').fill('90');
  await page.locator('#settings').click();
  assert.equal(await page.locator('#settings-page').isVisible(), true);
  assert.equal(await page.locator('#settings').getAttribute('aria-current'), 'page');
  assert.equal(await page.locator('dialog[open]').count(), 0);
  assert.equal(await page.locator('#settings-dialog').count(), 0);
  assert.equal(await page.locator('#settings-page').getByRole('heading', { name: 'Connection', exact:true }).count(), 1);
  assert.equal(await page.locator('#settings-page').getByRole('heading', { name: 'Updates', exact:true }).count(), 1);
  assert.equal(await page.getByText('https://music.example.com', { exact:true }).count(), 1);
  assert.equal(await page.locator('#settings-page input[type="checkbox"]').count(), 0);
  assert.equal(await page.getByText('Remember playback position', { exact:true }).count(), 0);
  assert.equal(await page.getByText('Launch at sign-in', { exact:true }).count(), 0);
  await page.locator('#test-connection').click();
  assert.match(await page.locator('#connection-status').innerText(), /Simulated test complete/);
  await page.locator('#check-updates').click();
  assert.match(await page.locator('#update-status').innerText(), /No live release service/);
  await mkdir('test-results', { recursive:true });
  await page.locator('#content').evaluate(element => { element.scrollTop = 0; });
  await page.screenshot({ path:'test-results/windows-v2-settings.png', fullPage:true });
  await page.locator('.current').click();
  assert.equal(await page.locator('#elapsed').innerText(), '1:30');
  assert.equal(await page.locator('#player-page .song-title').innerText(), 'Maroon');
  await page.locator('#queue').click();
  await page.locator('#settings').click();
  assert.equal(await page.locator('#queue-pane').isVisible(), false);
  await page.locator('.sidebar [data-page="library"]').click();
  await page.locator('#playlists .card').first().click();
  assert.equal(await page.locator('#detail-title').innerText(), 'Favorites (Unrated)');
  assert.equal(await page.locator('#elapsed').innerText(), '1:30');
  await page.locator('#start-playback').click();
  assert.equal(await page.locator('#player-page').isVisible(), true);
  await page.locator('#play').click();
  assert.equal(await page.locator('#play').getAttribute('aria-label'), 'Play');
  await page.locator('#play').click();
  await page.locator('#output').click();
  await page.getByLabel('Wireless headphones', { exact:true }).check();
  await page.locator('#apply-output').click();
  assert.equal(await page.locator('#output-name').innerText(), 'Wireless headphones');
  await page.locator('#output').click();
  assert.equal(await page.getByLabel('Wireless headphones', { exact:true }).isChecked(), true);
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#output').evaluate(element => element === document.activeElement), true);
  for (const view of ['desktop','phone','both']) {
    await page.locator('#view').selectOption(view);
    assert.equal(await page.locator('.desktop').isVisible(), view !== 'phone');
    assert.equal(await page.locator('.companion').isVisible(), view !== 'desktop');
  }
  const logical = await page.locator('.phone').evaluate(e => [e.offsetWidth,e.offsetHeight,e.querySelector('h2').offsetHeight]);
  for (const scale of ['25','50','200']) {
    await page.locator('#scale').fill(scale);
    assert.deepEqual(await page.locator('.phone').evaluate(e => [e.offsetWidth,e.offsetHeight,e.querySelector('h2').offsetHeight]), logical);
  }
  await page.reload();
  assert.equal(await page.locator('#scale').inputValue(), '200');
  await page.locator('#scale').fill('50');
  await page.locator('#view').selectOption('desktop');
  for (const width of [390,768,1440]) {
    await page.setViewportSize({ width, height:1100 });
    for (const name of ['library','playlists','albums','songs','settings']) {
      await page.locator(`.sidebar [data-page="${name}"]`).click();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${width} ${name} page overflow`);
      assert.equal(await page.locator('#content').evaluate(e => e.scrollWidth > e.clientWidth), false, `${width} ${name} content overflow`);
    }
  }
  await page.setViewportSize({ width:1440,height:1100 });
  await page.locator('#view').selectOption('both');
  await page.locator('.sidebar [data-page="library"]').click();
  await page.screenshot({ path:'test-results/windows-v2-library.png', fullPage:true });
  await search.fill('Midnights');
  await page.screenshot({ path:'test-results/windows-v2-search.png', fullPage:true });
  const staticPage = await browser.newPage({ javaScriptEnabled:false });
  await staticPage.goto(url);
  assert.equal(await staticPage.locator('.card:visible').count(), 8);
  await staticPage.close();
  assert.deepEqual(errors, []);
  console.log('PASS: Familiar sage only; exact navigation; 8 search cases including section hiding/restoration; Songs playback; main-view Settings with connection/updates and no removed options; simulated actions; playback position preserved across navigation; output/queue regressions; 3 viewer modes; calibration/persistence; 3 responsive widths × 5 panels; static HTML; unique IDs; loaded images; no JS errors.');
} finally { await browser.close(); }

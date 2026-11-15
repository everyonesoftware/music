import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || '/opt/data/.local/bin/chromium' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const url = 'http://hermesdan.tail1a7c8f.ts.net:8766/windows-native-v3.html';
try {
  assert.equal((await page.goto(url)).status(), 200);
  await page.locator('img').evaluateAll(images => Promise.all(images.map(image => image.decode())));
  assert.equal(await page.locator('img').evaluateAll(images => images.every(image => image.naturalWidth > 0)), true);
  assert.equal(await page.locator('[data-variant]').count(), 0);
  assert.deepEqual(await page.locator('.sidebar .nav').allTextContents(), ['Library','Playlists','Albums','Songs','Settings']);
  assert.equal(await page.locator('.sidebar').innerText().then(text => /Your Collection|Now Playing/i.test(text)), false);
  const ids = await page.locator('[id]').evaluateAll(elements => elements.map(element => element.id));
  assert.equal(new Set(ids).size, ids.length);
  assert.equal(await page.locator('.phone,.companion,#view,#scale').count(), 0);
  assert.equal(await page.locator('.sidebar [data-page="songs"] use').getAttribute('href'), '#icon-note');
  const search = page.locator('#search');
  async function setLayout(mode) {
    await page.locator('#layout-button').click();
    assert.equal(await page.locator('#layout-options').isVisible(), true);
    await page.locator(`#layout-options [data-layout="${mode}"]`).click();
    assert.equal(await page.locator('#library-page').getAttribute('data-layout'), mode);
    assert.equal(await page.locator('#layout-options').isVisible(), false);
    assert.equal(await page.locator('#layout-button').evaluate(e => e === document.activeElement), true);
  }
  for (const name of ['playlists','albums','songs']) {
    await page.locator(`.sidebar [data-page="${name}"]`).click();
    assert.equal(await page.locator('#library-page').getAttribute('data-layout'), name === 'songs' ? 'list' : 'grid');
    assert.equal(await page.getByRole('button', {name:/View all/}).count(), 0);
    const container = name === 'songs' ? '#library-songs' : '#'+name;
    if (name !== 'songs') {
      assert.equal(await page.locator(container).evaluate(e => getComputedStyle(e).display), 'grid');
      const boxes = await page.locator(container+' .card').evaluateAll(nodes => nodes.map(e => ({x:e.getBoundingClientRect().x,y:e.getBoundingClientRect().y})));
      assert.equal(boxes[0].y, boxes[1].y);
      assert.ok(boxes[1].x > boxes[0].x);
    }
    await setLayout('list');
    await setLayout('grid');
    assert.equal(await page.locator(container).evaluate(e => getComputedStyle(e).display), 'grid');
    await page.screenshot({path:`test-results/windows-v3-${name}-grid.png`,fullPage:true});
    await setLayout('list');
    await page.screenshot({path:`test-results/windows-v3-${name}-list.png`,fullPage:true});
    await search.fill(name === 'songs' ? 'Lavender' : name === 'albums' ? 'Titan' : 'Night');
    await setLayout('grid');
    assert.notEqual(await search.inputValue(), '');
    await search.fill('');
    await setLayout('list');
    await page.locator('.sidebar [data-page="library"]').click();
    assert.equal(await page.getByRole('button', {name:/View all/}).count(), 3);
    await page.locator(`.sidebar [data-page="${name}"]`).click();
    assert.equal(await page.locator('#library-page').getAttribute('data-layout'), 'list');
    await page.reload();
    await page.locator(`.sidebar [data-page="${name}"]`).click();
    assert.equal(await page.locator('#library-page').getAttribute('data-layout'), 'list');
    await setLayout(name === 'songs' ? 'list' : 'grid');
  }
  await page.locator('.sidebar [data-page="library"]').click();
  await page.locator('#layout-button').click();
  await page.keyboard.press('ArrowDown');
  assert.equal(await page.locator('#layout-options [data-layout="list"]').evaluate(e => e === document.activeElement), true);
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#layout-options').isVisible(), false);
  await page.locator('#layout-button').click();
  await page.locator('#library-title').click();
  assert.equal(await page.locator('#layout-options').isVisible(), false);
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
  await page.screenshot({ path:'test-results/windows-v3-settings.png', fullPage:true });
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
  for (const width of [390,768,1440]) {
    await page.setViewportSize({ width, height:1100 });
    for (const name of ['library','playlists','albums','songs','settings']) {
      await page.locator(`.sidebar [data-page="${name}"]`).click();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false, `${width} ${name} page overflow`);
      assert.equal(await page.locator('#content').evaluate(e => e.scrollWidth > e.clientWidth), false, `${width} ${name} content overflow`);
      if (name !== 'settings') {
        for (const mode of ['grid','list']) {
          await setLayout(mode);
          assert.equal(await page.locator('#content').evaluate(e => e.scrollWidth > e.clientWidth), false, `${width} ${name} ${mode} overflow`);
        }
      }
    }
  }
  await page.setViewportSize({ width:1440,height:1100 });
  await page.locator('.sidebar [data-page="library"]').click();
  await setLayout('grid');
  await page.screenshot({ path:'test-results/windows-v3-library.png', fullPage:true });
  await search.fill('Midnights');
  await page.screenshot({ path:'test-results/windows-v3-search.png', fullPage:true });
  const staticPage = await browser.newPage({ javaScriptEnabled:false });
  await staticPage.goto(url);
  assert.equal(await staticPage.locator('.card:visible').count(), 8);
  await staticPage.close();
  const blocked = await browser.newPage();
  await blocked.addInitScript(() => Object.defineProperty(window, 'localStorage', {get(){throw new Error('Blocked storage')}}));
  await blocked.goto(url);
  await blocked.locator('.sidebar [data-page="albums"]').click();
  await blocked.locator('#layout-button').click();
  await blocked.locator('#layout-options [data-layout="list"]').click();
  await blocked.locator('.sidebar [data-page="library"]').click();
  await blocked.locator('.sidebar [data-page="albums"]').click();
  assert.equal(await blocked.locator('#library-page').getAttribute('data-layout'), 'list');
  await blocked.close();
  assert.deepEqual(errors, []);
  console.log('PASS: desktop only; musical note; grid defaults; functional grid/list picker for all collections; no redundant View all links; per-view preferences across navigation/reload; Escape/arrows/outside dismissal/focus; blocked storage; Library filtering and Settings regressions; transport/output/queue; 3 widths × 5 panels with both layouts; no overflow or JS errors.');
} finally { await browser.close(); }

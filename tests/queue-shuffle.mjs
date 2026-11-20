import { chromium } from 'playwright';
import assert from 'node:assert/strict';
import { mkdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
const browser=await chromium.launch(process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{});
const errors=[];
const url=new URL('../mockups/queue-shuffle/index.html',import.meta.url).href;
const page=await browser.newPage({viewport:{width:1440,height:1100}});
page.on('pageerror',e=>errors.push(e.message));
const ids=scope=>scope.locator('.up-list .song-row').evaluateAll(els=>els.map(e=>e.dataset.id));
const snapshot=scope=>scope.evaluate(el=>({current:el.querySelector('.current-slot').innerHTML,history:el.querySelector('.history').innerHTML}));
try{
 await page.goto(url);
 const desktop=page.locator('[data-platform=desktop]'),phone=page.locator('[data-platform=phone]');
 const player=phone.locator('.android-player');
 assert.equal(await player.isVisible(),true,'Android opens on new Now Playing preview');
 assert.equal(await player.locator('.np-title').innerText(),'Compression of Time');
 assert.equal(await player.locator('.shuffle').count(),0,'No Shuffle in Now Playing');
 assert.equal(await player.getByRole('button',{name:'Repeat',exact:true}).count(),1);
 assert.equal(await player.getByRole('button',{name:'Media output: Phone',exact:true}).isVisible(),true);
 await player.getByRole('button',{name:'Pause',exact:true}).click();
 assert.equal(await player.getByRole('button',{name:'Resume',exact:true}).count(),1);
 await player.getByRole('button',{name:'Resume',exact:true}).click();
 await player.getByRole('button',{name:'Repeat',exact:true}).click();
 assert.equal(await player.locator('.repeat').getAttribute('aria-pressed'),'true');
 await player.getByRole('button',{name:'Repeat',exact:true}).click();
 await player.getByRole('slider',{name:'Playback position'}).fill('150');
 assert.equal(await player.locator('.np-elapsed').innerText(),'2:30');
 assert.equal(await phone.locator('.phone-screen').evaluate(el=>el.scrollHeight>el.clientHeight),false,'Now Playing fits phone');
 const playerBounds=await player.boundingBox(),queueBounds=await player.locator('.np-queue').boundingBox();
 assert.ok(queueBounds.y+queueBounds.height<=playerBounds.y+playerBounds.height,'Queue remains on screen');
 const playerLayout=await player.evaluate(el=>({width:el.clientWidth,height:el.clientHeight,title:el.querySelector('.np-title').clientHeight}));
 for(const value of ['25','200','50']){
  await page.locator('#scale').fill(value);
  assert.deepEqual(await player.evaluate(el=>({width:el.clientWidth,height:el.clientHeight,title:el.querySelector('.np-title').clientHeight})),playerLayout,'Now Playing calibration never reflows');
 }
 await page.getByRole('button',{name:'Queue',exact:true}).click();
 assert.equal(await phone.locator('.phone-queue').isVisible(),true);
 await phone.getByRole('button',{name:'Back to player',exact:true}).click();
 assert.equal(await player.isVisible(),true);
 await player.getByRole('button',{name:'Open Android queue',exact:true}).click();
 assert.equal(await phone.locator('.phone-queue').isVisible(),true);
 assert.equal(await desktop.locator('.transport .shuffle').count(),0);
 assert.equal(await desktop.locator('.volume').count(),0,'Windows volume control removed');
 assert.equal(await desktop.locator('.queue-link').isVisible(),true,'Queue shortcut retained');
 const queueGeometry=await desktop.evaluate(el=>{const bar=el.querySelector('.player-bar').getBoundingClientRect(),button=el.querySelector('.queue-link'),b=button.getBoundingClientRect(),play=el.querySelector('.transport .play').getBoundingClientRect(),scale=el.getBoundingClientRect().width/el.offsetWidth;return {centerGap:Math.abs((b.top+b.height/2)-(play.top+play.height/2))/scale,right:(bar.right-b.right)/scale,height:button.offsetHeight,font:getComputedStyle(button).fontSize,icon:getComputedStyle(button.querySelector('svg')).width}});
 assert.ok(queueGeometry.centerGap<0.5&&queueGeometry.right<=26,'Queue vertically centered with playback buttons, at right');
 assert.ok(queueGeometry.height>=48,'Queue has larger click target');
 assert.equal(queueGeometry.font,'18px');
 assert.equal(queueGeometry.icon,'26px');
 await desktop.getByRole('button',{name:'Close queue',exact:true}).click();
 await desktop.getByRole('button',{name:'Open queue',exact:true}).click();
 assert.equal(await desktop.locator('.queue-panel').isVisible(),true);
 assert.equal(await phone.locator('.mini-player .shuffle').count(),0);
 for(const scope of [desktop,phone]){
  assert.equal((await scope.locator('.shuffle').innerText()).trim(),'','Shuffle must be icon-only');
  assert.equal(await scope.locator('.shuffle svg').count(),1);
  assert.equal(await scope.locator('.played-label').innerText(),'Played');
  assert.equal(await scope.locator('.played-count').innerText(),'3 songs');
  const style=await scope.locator('.played').evaluate(el=>{const s=getComputedStyle(el);return {background:s.backgroundColor,radius:s.borderRadius,divider:s.borderBottomWidth}});
  assert.deepEqual(style,{background:'rgba(0, 0, 0, 0)',radius:'0px',divider:'1px'});
  const chevron=await scope.locator('summary svg').boundingBox(),label=await scope.locator('.played-label').boundingBox(),count=await scope.locator('.played-count').boundingBox();
  assert.ok(chevron.x<label.x&&label.x<count.x,'Played chevron left, count right');
 }
 const original=await ids(desktop),stable=await snapshot(desktop);
 for(let i=0;i<6;i++){
  const scope=i%2?phone:desktop,before=await ids(scope);
  await scope.getByRole('button',{name:'Shuffle Up next',exact:true}).click();
  const after=await ids(scope);
  assert.notDeepEqual(after,before,'Every action should change the remaining order');
  assert.deepEqual([...after].sort(),[...original].sort(),'No song occurrence lost or added');
  assert.deepEqual(await snapshot(desktop),stable,'Current song/history must not move');
  assert.deepEqual(await snapshot(phone),stable);
  assert.deepEqual(await ids(desktop),await ids(phone));
  assert.equal(await scope.locator('.shuffle').getAttribute('aria-pressed'),null);
 }
 await phone.locator('summary').click();assert.equal(await phone.locator('.history .song-row').count(),3);
 await desktop.locator('summary').click();assert.equal(await desktop.locator('.history .song-row').count(),3);
 for(const [mode,desktopVisible,phoneVisible] of [['desktop',true,false],['phone',false,true],['both',true,true]]){
  await page.locator('.viewer button[data-view='+mode+']').click();
  assert.equal(await page.locator('.desktop-study').isVisible(),desktopVisible);
  assert.equal(await page.locator('.phone-study').isVisible(),phoneVisible);
 }
 const logical=await phone.locator('.phone-screen').evaluate(el=>({w:el.clientWidth,h:el.clientHeight,title:el.querySelector('.song-title').clientHeight}));
 for(const value of ['25','200','50']){
  await page.locator('#scale').fill(value);
  const screen=await phone.locator('.phone-screen').evaluate(el=>({w:el.clientWidth,h:el.clientHeight,title:el.querySelector('.song-title').clientHeight}));
  assert.deepEqual(screen,logical,'Calibration must not reflow the phone');
  const bounds=await phone.boundingBox(),space=await page.locator('.phone-space').boundingBox();
  assert.ok(Math.abs(bounds.width-space.width)<1&&Math.abs(bounds.height-space.height)<1);
 }
 await page.reload();assert.equal(await page.locator('#scale').inputValue(),'50');
 await page.locator('#reset').click();assert.deepEqual(await ids(desktop),original);
 assert.equal(await desktop.locator('.current-slot .song-title').innerText(),'Compression of Time');
 assert.equal(await player.isVisible(),true,'Reset returns to Android Now Playing');
 await desktop.locator('.start-shuffle').click();
 const shuffledCurrent=await desktop.locator('.current-slot .song-title').innerText();
 await desktop.locator('.shuffle').click();
 assert.equal(await desktop.locator('.current-slot .song-title').innerText(),shuffledCurrent,'Queue action after Shuffle Play must not replace the current song');
 assert.equal(await desktop.locator('.history .song-row').count(),0);
 for(let i=0;i<6;i++)await desktop.locator('[data-step=next]').click();
 assert.equal(await desktop.locator('.shuffle').isDisabled(),true);
 await desktop.locator('[data-step=next]').click();assert.equal((await ids(desktop)).length,0);
 await page.locator('#reset').click();
 await page.getByRole('button',{name:'Desktop only',exact:true}).click();
 const wide=await page.locator('.desktop-space').boundingBox();
 await page.getByRole('button',{name:'Desktop + phone',exact:true}).click();
 assert.ok((await page.locator('.desktop-space').boundingBox()).width<wide.width,'Hidden phone must reclaim space');
 const evidenceDirectory=new URL('../test-results/queue-shuffle/',import.meta.url);
 await mkdir(evidenceDirectory,{recursive:true});
 await page.screenshot({path:fileURLToPath(new URL('both.png',evidenceDirectory)),fullPage:true});
 for(const width of [360,768,1440]){
  await page.setViewportSize({width,height:1100});
  await page.waitForFunction(()=>document.documentElement.scrollWidth<=innerWidth);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false,'Page overflow at '+width);
 }
 const nojs=await browser.newContext({javaScriptEnabled:false,viewport:{width:1440,height:1100}});
 const staticPage=await nojs.newPage();await staticPage.goto(url);
 assert.equal(await staticPage.locator('.up-list .song-row').count(),14);
 assert.equal(await staticPage.locator('.android-player').isVisible(),true,'Static preview includes Now Playing');
 assert.equal(await staticPage.locator('.np-title').innerText(),'Compression of Time');
 const staticPhone=await staticPage.locator('.phone').boundingBox(),staticSpace=await staticPage.locator('.phone-space').boundingBox();
 assert.equal(staticPhone.width,228);assert.ok(Math.abs(staticPhone.height-staticSpace.height)<1);
 await nojs.close();assert.deepEqual(errors,[]);
 console.log('PASS: Android Now Playing without Shuffle, playback/repeat/seeking, Queue navigation, player + queue calibration; desktop + Android queue shuffle, preserved current/history/song membership, Shuffle Play handoff, disabled short queues, view modes, persistence/reset, responsive widths and script-disabled rendering; no JS errors.');
}finally{await browser.close()}

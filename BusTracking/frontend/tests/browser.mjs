// Simulated API/GPS integration test. Set PLAYWRIGHT_MODULE_PATH or install playwright.
import { createRequire } from 'node:module';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
const require=createRequire(import.meta.url);
const {chromium}=require(process.env.PLAYWRIGHT_MODULE_PATH||'@playwright/test');
const server = process.env.TEST_BASE_URL ? null : spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1'], { stdio: 'ignore' });
for (let i=0;i<50;i++) { try { await fetch(process.env.TEST_BASE_URL || 'http://127.0.0.1:5173'); break; } catch { await new Promise(r=>setTimeout(r,100)); } }
const browser=await chromium.launch({headless:true});let passed=0;
try {
 const page=await browser.newPage({viewport:{width:1280,height:850}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
  window.activeGpsWatches=0;let callback;Object.defineProperty(navigator,'geolocation',{configurable:true,value:{watchPosition(success){window.activeGpsWatches++;callback=success;success({coords:{latitude:28.6,longitude:77.2,accuracy:10},timestamp:Date.now()});return 1;},clearWatch(){window.activeGpsWatches--;callback=null;}}});
  window.testGps=(lat,lng)=>callback?.({coords:{latitude:lat,longitude:lng,accuracy:10},timestamp:Date.now()});
 });
 const stops=[{id:'s',name:'Boarding',lat:28.601,lng:77.2},{id:'d',name:'Destination',lat:28.601,lng:77.202}];let polls=0,number,failRouting=false;
 await page.route('**/api/transit/**',async route=>{
  const url=new URL(route.request().url()),path=url.pathname;let body;
  if(path.endsWith('/buses/live')){polls++;number=url.searchParams.get('routeNumber');body={buses:['a','b'].map((id,i)=>({vehicleId:id,routeCode:'534',busLatitude:28.6+i*.001,busLongitude:77.2,lastUpdatedEpochSeconds:Date.now()/1000})),warnings:[]};body.buses.push({...body.buses[0],vehicleId:'wrong',routeCode:'221'});}
  else if(path.endsWith('/routes/search'))body=['534','534E'];
  else if(path.endsWith('/routes/map'))body={stops,shapes:[]};
  else if(path.endsWith('/stops/search'))body=stops;
  else if(path.endsWith('/arrivals'))body={arrivals:[{routeNumber:'534',vehicleId:'a',tripId:'t',etaMinutes:20,distanceMeters:900,lastGpsUpdate:Date.now()/1000,predictionAvailability:'TRIP_UPDATE'}],warnings:[]};
  else if(path.endsWith('/routes/alternatives'))body={message:url.searchParams.get('destinationStopId')?'Faster verified alternative':'Choose a destination stop',options:[]};
  else if(failRouting){await route.abort();return;}
  else body={stop:stops[1],available:true,distanceMeters:300,durationSeconds:240,coordinates:[[28.6,77.2],[28.601,77.2],[28.601,77.202]],maneuvers:[{instruction:'Walk north',fromIndex:0,toIndex:1},{instruction:'Turn right',fromIndex:1,toIndex:2}]};
  await route.fulfill({json:body,headers:{'access-control-allow-origin':'*'}});
 });
 await page.goto(process.env.TEST_BASE_URL||'http://127.0.0.1:5173');
 await page.getByText(/GPS accuracy/).waitFor();
 assert.equal(await page.getByRole('button',{name:'Center on My Location'}).isEnabled(),true);passed++;
 const marker=page.locator('.user-location-marker');await marker.waitFor();const initialPosition=await marker.getAttribute('style');
 await page.evaluate(()=>window.testGps(28.6002,77.2));await page.waitForTimeout(100);assert.notEqual(await marker.getAttribute('style'),initialPosition);passed++;
 await page.evaluate(()=>window.testGps(28.6,77.2));
 const input=page.getByRole('textbox',{name:'Public route number'});await input.fill('534');await input.press('Enter');
 await page.getByText('2 active buses',{exact:true}).waitFor();assert.equal(number,'534');passed++;
 const before=polls;await page.waitForTimeout(10500);assert.ok(polls>before);await page.getByText('2 active buses',{exact:true}).waitFor();passed++;
 await page.locator('select').nth(0).selectOption('s');await page.getByText('Choose a destination stop',{exact:true}).waitFor();
 await page.locator('select').nth(1).selectOption('d');await page.getByText('Faster verified alternative',{exact:true}).waitFor();passed++;
 await page.getByRole('button',{name:'Find Nearest Bus Stop'}).click();await page.getByText('Next: Walk north',{exact:true}).waitFor();
 await page.evaluate(()=>window.testGps(28.601,77.201));await page.getByText('Next: Turn right',{exact:true}).waitFor();passed++;
 await page.getByRole('button',{name:'Stop navigation'}).click();assert.equal(await page.getByRole('button',{name:'Stop navigation'}).count(),0);await page.evaluate(()=>window.testGps(28.602,77.202));assert.equal(await page.getByRole('button',{name:'Center on My Location'}).isEnabled(),true);passed++;
 await page.setViewportSize({width:390,height:844});assert.ok(await page.locator('.transit-map').isVisible());passed++;
 failRouting=true;await page.getByRole('button',{name:'Find Nearest Bus Stop'}).click();await page.getByText('Walking route unavailable. Please retry.',{exact:true}).waitFor();await page.getByRole('button',{name:'Stop navigation'}).click();passed++;
 assert.equal(await page.evaluate(()=>window.activeGpsWatches),1);
 await page.getByRole('link',{name:/Admin/}).click();await page.waitForTimeout(100);assert.equal(await page.evaluate(()=>window.activeGpsWatches),0);
 await page.getByRole('link',{name:/Live Map/}).click();await page.getByText(/GPS accuracy/).waitFor();assert.equal(await page.evaluate(()=>window.activeGpsWatches),1);passed++;
 await page.addInitScript(()=>Object.defineProperty(navigator,'geolocation',{configurable:true,value:{watchPosition(_s,error){error({code:1});return 2;},clearWatch(){}}}));
 await page.reload();await page.getByText('GPS permission denied.',{exact:false}).waitFor();
 assert.equal(await page.getByRole('button',{name:'Center on My Location'}).isDisabled(),true);passed++;
 assert.deepEqual(errors,[]);console.log(`${passed} browser scenarios passed (mock API/GPS).`);
} finally {await browser.close();server?.kill();}

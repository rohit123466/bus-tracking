import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
const browser = await chromium.launch();
try {
 const context = await browser.newContext({permissions:['geolocation'],geolocation:{latitude:28.6139,longitude:77.209,accuracy:10}});
 const page = await context.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto('http://localhost:5173');
 await page.getByText(/Location updated/).waitFor();
 await page.getByRole('button',{name:'Center on My Location'}).click();
 await page.waitForResponse(r=>r.url().includes('/buses/live') && r.status()===200);
 const live=await (await page.request.get('http://localhost:8080/api/transit/buses/live')).json();
 console.log(JSON.stringify({actualLiveVehicles:live.buses.length,warnings:live.warnings}));
 const input=page.getByRole('textbox',{name:'Public route number'});await input.fill('534');await input.press('Enter');
 const filtered=await page.waitForResponse(r=>r.url().includes('/buses/live?routeNumber=534')&&r.status()===200);
 assert.ok((await filtered.json()).buses.every(b=>b.routeCode==='534'));
 await page.getByRole('button',{name:'Find Nearest Bus Stop'}).click();
 await page.getByText(/straight-line distance only/).first().waitFor();
 await page.screenshot({path:process.env.SCREENSHOT_PATH,fullPage:true});
 assert.deepEqual(errors,[]);console.log('Actual backend browser flow passed; browser GPS simulated. Live feed availability is reported above, not assumed.');
} finally {await browser.close();}

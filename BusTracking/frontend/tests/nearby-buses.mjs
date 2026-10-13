import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
const browser = await chromium.launch();
try {
 const page = await browser.newPage();
 const errors=[]; page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>Object.defineProperty(navigator,'geolocation',{value:{watchPosition(callback){window.moveGps=lat=>callback({coords:{latitude:lat,longitude:77.2,accuracy:10},timestamp:Date.now()});window.moveGps(28.6);return 1;},clearWatch(){}}}));
 await page.route('**/api/transit/buses/live**',route=>route.fulfill({json:{buses:[{vehicleId:'close-bus',busLatitude:28.601},{vehicleId:'distant-bus',busLatitude:28.65}].map(b=>({...b,routeCode:'534',busLongitude:77.2,lastUpdatedEpochSeconds:Date.now()/1000})),warnings:[]}}));
 await page.goto('http://localhost:5173');
 const list=page.getByRole('region',{name:'Nearby live buses'});
 await list.getByText('Route 534 · close-bus',{exact:true}).waitFor();
 assert.equal(await list.getByText('Route 534 · distant-bus',{exact:true}).count(),0);
 await page.getByLabel('Nearby radius').selectOption('10000');
 await list.getByText('Route 534 · distant-bus',{exact:true}).waitFor();
 await page.getByLabel('Nearby radius').selectOption('500');
 await page.evaluate(()=>window.moveGps(28.65));
 await list.getByText('Route 534 · distant-bus',{exact:true}).waitFor();
 assert.equal(await list.getByText('Route 534 · close-bus',{exact:true}).count(),0);
 await page.getByLabel('Show buses near my location').uncheck();
 await page.getByText('2 recently reported buses',{exact:true}).waitFor();
 assert.deepEqual(errors,[]);
 console.log('PASS: simulated buses/GPS: radius, moving location, nearest list, all-bus toggle.');
} finally {await browser.close();}

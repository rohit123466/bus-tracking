// Actual local backend integration. Uses demo ticket data and simulated browser GPS.
// Never run ticket mutations against production.
import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
const api = 'http://localhost:8080';
const browser = await chromium.launch();
try {
  const context = await browser.newContext({permissions:['geolocation'],
    geolocation:{latitude:28.6139,longitude:77.209,accuracy:10}});
  const page = await context.newPage();
  const errors=[]; page.on('pageerror', e=>errors.push(e.message));
  const get = async path => {
    const response=await page.request.get(api+path);
    assert.equal(response.status(),200,path); return response.json();
  };
  assert.equal((await get('/actuator/health')).status,'UP');
  assert.equal((await page.request.get(api+'/api/admin/stats')).status(),403);
  assert.equal((await page.request.get(api+'/api/transit/stops/missing/arrivals')).status(),404);
  assert.equal((await page.request.get(api+'/api/transit/stops/nearest?latitude=NaN&longitude=77')).status(),400);
  const routes=await get('/api/transit/routes/search?query=534');
  for(const route of ['534','534A','534C','534E']) assert.ok(routes.includes(route));
  const live=await get('/api/transit/buses/live?routeNumber=534');
  assert.ok(live.buses.every(b=>b.routeCode==='534'));
  console.log(JSON.stringify({route534LiveCount:live.buses.length,warnings:live.warnings}));
  const cors=await page.request.get(api+'/api/transit/routes/search?query=534',{headers:{Origin:'http://localhost:5173'}});
  assert.equal(cors.headers()['access-control-allow-origin'],'http://localhost:5173');
  const rejected=await page.request.get(api+'/api/transit/routes/search',{headers:{Origin:'https://untrusted.invalid'}});
  assert.equal(rejected.status(),403);
  await page.goto('http://localhost:5173');
  await page.getByText(/Location updated/).waitFor();
  await page.getByRole('button',{name:'Center on My Location'}).click();
  const routeResponse=page.waitForResponse(r=>r.url().includes('/buses/live?routeNumber=534'));
  const input=page.getByRole('textbox',{name:'Public route number'});
  await input.fill('534'); await input.press('Enter'); await routeResponse;
  await page.getByRole('button',{name:'Find Nearest Bus Stop'}).click();
  await page.getByText(/straight-line distance only/).first().waitFor();
  const connected=await page.evaluate(()=>new Promise((resolve,reject)=>{
    const ws=new WebSocket('ws://localhost:8080/ws/websocket');
    const timeout=setTimeout(()=>{ws.close();reject(new Error('STOMP timeout'));},10000);
    ws.onopen=()=>ws.send('CONNECT\naccept-version:1.2\nhost:localhost\n\n\u0000');
    ws.onmessage=e=>{if(e.data.startsWith('CONNECTED')) {clearTimeout(timeout);ws.close();resolve(true);}};
    ws.onerror=()=>{clearTimeout(timeout);reject(new Error('WebSocket failed'));};
  }));
  assert.equal(connected,true);
  await page.goto('http://localhost:5173/seats');
  await page.getByText(/Demo ticket and seat data/).waitFor();
  const seatGroup=page.getByRole('group',{name:'Seat map of bus DTC-101'});
  await seatGroup.waitFor();
  assert.ok(await seatGroup.locator('.seat--wc').count()>0);
  await seatGroup.locator('button.seat:not([disabled])').first().click();
  assert.equal(await seatGroup.locator('[aria-pressed="true"]').count(),1);
  const categoryResponse=page.waitForResponse(r=>r.url().includes('/seats')&&r.url().includes('DIVYANGJAN'));
  await page.getByRole('radio',{name:/Divyangjan/}).click();
  await categoryResponse;
  await page.setViewportSize({width:390,height:844});
  const box=await seatGroup.boundingBox(); assert.ok(box.width<=390);
  if(process.env.SCREENSHOT_PATH) await seatGroup.screenshot({path:process.env.SCREENSHOT_PATH});
  await page.goto('http://localhost:5173/conductor');
  await page.getByPlaceholder('Username').fill(process.env.TEST_ADMIN_USERNAME||'admin');
  await page.getByPlaceholder('Password').fill(process.env.TEST_ADMIN_PASSWORD||'admin123');
  const loginResponse=page.waitForResponse(r=>r.url().endsWith('/auth/login'));
  await page.getByRole('button',{name:'Log in',exact:true}).click();
  assert.equal((await loginResponse).status(),200);
  await page.getByRole('heading',{name:'Sell tickets & monitor occupancy'}).waitFor();
  const token=await page.evaluate(()=>localStorage.getItem('dtc_admin_token'));
  assert.ok(token);
  const headers={Authorization:`Bearer ${token}`};
  assert.equal((await page.request.get(api+'/api/admin/stats',{headers})).status(),200);
  const seats=await get('/api/ticketing/buses/DTC-101/seats?category=ADULT');
  const seat=seats.seats.find(s=>s.selectable&&s.seatType==='REGULAR'); assert.ok(seat);
  const sale={busNumber:'DTC-101',passengerName:'Runtime verification',category:'ADULT',
    fromStopId:seats.fromStop.stopId,toStopId:seats.toStop.stopId,seatNumber:seat.seatNumber,
    requestId:`runtime-${Date.now()}`};
  const response=await page.request.post(api+'/api/conductor/tickets',{headers,data:sale});
  assert.equal(response.status(),201);const ticket=await response.json();
  const repeat=await page.request.post(api+'/api/conductor/tickets',{headers,data:sale});
  assert.equal(repeat.status(),409);
  assert.ok((await repeat.json()).message.includes(ticket.ticketNumber));
  const tickets=await (await page.request.get(api+'/api/conductor/buses/DTC-101/tickets',{headers})).json();
  assert.equal(tickets.filter(t=>t.ticketNumber===ticket.ticketNumber).length,1);
  const occupied=await get('/api/ticketing/buses/DTC-101/seats?category=ADULT');
  assert.equal(occupied.seats.find(s=>s.seatNumber===seat.seatNumber).status,'OCCUPIED');
  assert.equal((await page.request.post(api+`/api/conductor/tickets/${ticket.ticketNumber}/cancel`,{headers})).status(),200);
  const camera=await page.request.get(api+'/api/conductor/buses/DTC-101/camera',{headers});
  assert.equal(camera.status(),200);
  assert.deepEqual(errors,[]);
  console.log('Actual backend: route search, unavailable feeds, GPS, walking fallback, CORS, STOMP, mobile seats, wheelchair category, conductor login, admin API, ticket sale/idempotency/cancel and camera API passed.');
} finally {await browser.close();}

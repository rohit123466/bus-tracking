import { chromium } from '@playwright/test';
import { spawn } from 'node:child_process';
import assert from 'node:assert/strict';
const server = spawn(process.execPath, ['node_modules/vite/bin/vite.js', '--host', '127.0.0.1', '--port', '5174', '--strictPort'], {stdio:'ignore'});
let browser;
try {
 for(let i=0;i<100;i++){try{await fetch('http://127.0.0.1:5174');break;}catch{await new Promise(r=>setTimeout(r,100));}}
 browser=await chromium.launch();
 const page=await browser.newPage();const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>Object.defineProperty(navigator,'geolocation',{value:{watchPosition(){return 1;},clearWatch(){}}}));
 const stops=[{id:'1',name:'Kashmere Gate',lat:28.66,lng:77.23},{id:'2',name:'Kashmere Gate ISBT',lat:28.67,lng:77.23},{id:'3',name:'Rohini Sector 18',lat:28.74,lng:77.13}];
 await page.route('**/api/transit/**',async route=>{
  const url=new URL(route.request().url());let data=[];
  if(url.pathname.endsWith('/stops/search')) {
   const query=url.searchParams.get('query')||'';
   if(query==='failure'){await route.fulfill({status:503,json:{message:'unavailable'}});return;}
   data=stops.filter(s=>s.name.toLowerCase().includes(query.toLowerCase()));
  } else if(url.pathname.endsWith('/buses/live')) data={buses:[],warnings:[]};
  else if(url.pathname.endsWith('/arrivals')) data={arrivals:[],warnings:[]};
  await route.fulfill({json:data});
 });
 await page.goto('http://127.0.0.1:5174');
 const boarding=page.getByRole('combobox',{name:'Search Boarding stop',exact:true});
 await boarding.fill('Kash');
 const suggestions=page.getByRole('listbox',{name:'Boarding stop suggestions'});
 await suggestions.getByRole('option').first().waitFor();assert.equal(await suggestions.getByRole('option').count(),2);
 await suggestions.getByRole('option').filter({hasText:'Kashmere Gate ISBT'}).click();
 assert.equal(await boarding.inputValue(),'Kashmere Gate ISBT');
 assert.equal(await page.getByRole('combobox',{name:'Selected Boarding stop',exact:true}).inputValue(),'2');
 const destination=page.getByRole('combobox',{name:'Search Destination stop',exact:true});
 await destination.fill('Roh');await page.getByRole('listbox',{name:'Destination stop suggestions'}).getByRole('option').waitFor();
 await destination.press('ArrowDown');await destination.press('Enter');
 assert.equal(await destination.inputValue(),'Rohini Sector 18');
 assert.equal(await page.getByRole('combobox',{name:'Selected Destination stop',exact:true}).inputValue(),'3');
 await destination.fill('zzzz');await page.getByText('No matching bus stops found.').waitFor();
 await destination.fill('failure');await page.getByText('Stop search unavailable. Please try again.').waitFor();
 await destination.press('Escape');assert.equal(await destination.getAttribute('aria-expanded'),'false');
 assert.deepEqual(errors,[]);console.log('Stop autocomplete: click, keyboard, both fields, no matches, errors and Escape passed.');
} finally {await browser?.close();server.kill();}

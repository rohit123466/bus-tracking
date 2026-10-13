import { chromium } from '@playwright/test';
import assert from 'node:assert/strict';
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'geolocation', { value: {
      watchPosition(success) {
        window.gpsFix = () => success({ coords: { latitude: 28.6139, longitude: 77.209, accuracy: 150 }, timestamp: Date.now() });
        return 1;
      }, clearWatch() {}
    } });
  });
  await page.goto('http://localhost:5173');
  const find = page.getByRole('button', { name: 'Find Nearest Bus Stop' });
  await find.click();
  await page.getByText('Waiting for GPS.', { exact: false }).waitFor();
  await page.evaluate(() => window.gpsFix());
  await page.locator('strong').filter({ hasText: 'Udyog Bhawan' }).waitFor();
  await page.getByText('Location is approximate', { exact: false }).waitFor();
  await page.getByText('(straight-line distance; pedestrian access unverified)', { exact: false }).waitFor();
  assert.equal(await page.locator('path[stroke-dasharray="6 8"]').count(), 1);
  assert.equal(await find.isEnabled(), true);
  assert.equal(await page.getByText('Next:', { exact: false }).count(), 0);
  await find.click();
  await page.locator('strong').filter({ hasText: 'Udyog Bhawan' }).waitFor();
  assert.deepEqual(errors, []);
  console.log('PASS: real backend, simulated delayed/coarse GPS, fallback map line, retry, no invented guidance.');
} finally { await browser.close(); }


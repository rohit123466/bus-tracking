import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleBuses, progress } from '../src/utils/navigation.js';
const bus = (id, route = '534', stamp = 1000) => ({ vehicleId: id, routeCode: route, lastUpdatedEpochSeconds: stamp, busLatitude: 28.6, busLongitude: 77.2 });
test('534 matches exactly, keeps distinct vehicles and deduplicates repeated IDs', () => {
 assert.deepEqual(visibleBuses([bus('a'),bus('b'),bus('a'),bus('c','534E'),bus('d','221')],'534',1001).map(b=>b.vehicleId),['a','b']);
});
test('route filtering supports every public number, keeping variants distinct and no distance cutoff', () => {
 const rows = [
  bus('v106', '106'), bus('v106a', '106A'), bus('v73', '73'),
  bus('v419', '419'), bus('v534', '534'), bus('v-tms', 'TMS+')
 ];
 for (const [route, id] of [['106', 'v106'], ['106A', 'v106a'], ['73', 'v73'],
                             ['419', 'v419'], ['534', 'v534'], ['TMS+', 'v-tms']]) {
  assert.deepEqual(visibleBuses(rows, route, 1001).map(b => b.vehicleId), [id]);
 }
 // Search results come from official route identity, not browser GPS proximity.
 assert.deepEqual(visibleBuses(rows, '106', 1001).map(b => b.vehicleId), ['v106']);
 assert.deepEqual(visibleBuses(rows, '419', 1001).map(b => b.vehicleId), ['v419']);
});
test('route filtering survives replacement polling payloads', () => {
 for(const rows of [[bus('a'),bus('b','221')],[bus('c','221'),bus('d')]]) assert.equal(visibleBuses(rows,'534',1001)[0].routeCode,'534');
});
test('empty and stale positions are excluded', () => {
 assert.deepEqual(visibleBuses([bus('a','221'),bus('b','534',700)],'534',1001),[]);
});
test('remaining walking distance decreases along route and detects deviation', () => {
 const route=[[28.6,77.2],[28.601,77.2],[28.601,77.202]];
 const start=progress(route,{lat:28.6,lng:77.2});
 const middle=progress(route,{lat:28.601,lng:77.201});
 assert.ok(start.remaining>middle.remaining);assert.ok(middle.offset<1);
 assert.ok(progress(route,{lat:28.603,lng:77.205}).offset>100);
});

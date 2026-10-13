import test from 'node:test';
import assert from 'node:assert/strict';
import { visibleBuses, progress } from '../src/utils/navigation.js';
const bus = (id, route = '534', stamp = 1000) => ({ vehicleId: id, routeCode: route, lastUpdatedEpochSeconds: stamp, busLatitude: 28.6, busLongitude: 77.2 });
test('534 matches exactly, keeps distinct vehicles and deduplicates repeated IDs', () => {
 assert.deepEqual(visibleBuses([bus('a'),bus('b'),bus('a'),bus('c','534E'),bus('d','221')],'534',1001).map(b=>b.vehicleId),['a','b']);
});
test('route filtering survives replacement polling payloads', () => {
 for(const rows of [[bus('a'),bus('b','221')],[bus('c','221'),bus('d')]]) assert.equal(visibleBuses(rows,'534',1001)[0].routeCode,'534');
});
test('empty and stale positions are excluded', () => {
 assert.deepEqual(visibleBuses([bus('a','221'),bus('b','534',700)],'534',1001),[]);
});
test('invalid coordinate ranges and future fixes are excluded', () => {
 assert.deepEqual(visibleBuses([{...bus('a'),busLatitude:91},{...bus('b'),busLongitude:181},bus('c','534',1040)],'534',1000),[]);
});
test('vehicles expire even when the last polling payload has not changed', () => {
 const rows=[bus('a')];
 assert.equal(visibleBuses(rows,'534',1120).length,1);
 assert.equal(visibleBuses(rows,'534',1121).length,0);
});
test('remaining walking distance decreases along route and detects deviation', () => {
 const route=[[28.6,77.2],[28.601,77.2],[28.601,77.202]];
 const start=progress(route,{lat:28.6,lng:77.2});
 const middle=progress(route,{lat:28.601,lng:77.201});
 assert.ok(start.remaining>middle.remaining);assert.ok(middle.offset<1);
 assert.ok(progress(route,{lat:28.603,lng:77.205}).offset>100);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import {interpolateKeys,sceneAt,sampleFrame,durationFrames} from './timeline.mjs';
test('scene boundaries use cumulative frame rounding without leaking into the next scene',()=>{
 const scenes=[{durationMs:5001},{durationMs:4999},{durationMs:5000}];
 assert.equal(durationFrames(scenes),360);
 assert.equal(sceneAt(scenes,119).index,0);
 assert.equal(sceneAt(scenes,120).index,1);
 assert.equal(sampleFrame(scenes,0,5000),119);
 assert.equal(sampleFrame(scenes,2,5000),359);
});
test('sparse keys inherit prior values and explicit zero-time state overrides base',()=>{
 const keys=[{timeMs:0,x:500},{timeMs:1000,y:200,easing:'linear'},{timeMs:2000,x:700,easing:'easeOut'}];
 assert.deepEqual(interpolateKeys({x:600,y:100},keys,0),{x:500,y:100});
 assert.deepEqual(interpolateKeys({x:600,y:100},keys,500),{x:500,y:150});
 assert.deepEqual(interpolateKeys({x:600,y:100},keys,1500),{x:675,y:200});
 assert.deepEqual(interpolateKeys({x:600,y:100},keys,3000),{x:700,y:200});
});

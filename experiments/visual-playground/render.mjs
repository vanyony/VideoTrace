import fs from 'node:fs';
import path from 'node:path';
import {bundle} from '@remotion/bundler';
import {selectComposition,renderMedia,renderStill,openBrowser} from '@remotion/renderer';

const root=path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/,'$1'));
const inputProps={board:JSON.parse(fs.readFileSync(path.join(root,'resolved-storyboard.json'),'utf8'))};
// Reuse the repository's local music; no external assets or model calls.
fs.mkdirSync(path.join(root,'public'),{recursive:true});
fs.copyFileSync(path.resolve(root,'../../creator/assets/light_01.wav'),path.join(root,'public/light_01.wav'));
const started=Date.now();
const serveUrl=await bundle({entryPoint:path.join(root,'index.tsx'),publicDir:path.join(root,'public'),outDir:path.join(root,'bundle')});
const edge='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browserExecutable=process.env.REMOTION_BROWSER_EXECUTABLE || (fs.existsSync(edge)?edge:undefined);
const browser=await openBrowser('chrome',{browserExecutable});
try {
  const composition=await selectComposition({serveUrl,id:'WaterCycle',inputProps,puppeteerInstance:browser});
  for(const frame of [40,160,280]) {
    await renderStill({serveUrl,composition,inputProps,frame,output:path.join(root,`frame-${frame}.png`),puppeteerInstance:browser});
  }
  console.log('Preview frames ready');
  if(!process.argv.includes('--stills-only')) {
    let last=-1;
    await renderMedia({serveUrl,composition,inputProps,codec:'h264',outputLocation:path.join(root,'output.mp4'),
      puppeteerInstance:browser,concurrency:2,crf:19,pixelFormat:'yuv420p',onProgress:({progress})=>{
        const pct=Math.floor(progress*10)*10;if(pct!==last){console.log(`Render ${pct}%`);last=pct;}
      }});
    fs.writeFileSync(path.join(root,'timing.json'),JSON.stringify({elapsedSeconds:(Date.now()-started)/1000,remotion:JSON.parse(fs.readFileSync(path.join(root,'node_modules/remotion/package.json'),'utf8')).version,scope:'Hand-authored React/SVG rendering probe using existing Agent storyboard; not a generic canvas adapter or production integration'},null,2));
  }
} finally {await browser.close({silent:true});}

import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {bundle} from '@remotion/bundler';
import {selectComposition,renderMedia,renderStill,openBrowser} from '@remotion/renderer';

const root=path.dirname(fileURLToPath(import.meta.url));
const out=path.join(root,'journey');
fs.mkdirSync(out,{recursive:true});
// Reuse the repository's local music; no external assets or model calls.
fs.mkdirSync(path.join(root,'public'),{recursive:true});
fs.copyFileSync(path.resolve(root,'../../creator/assets/light_01.wav'),path.join(root,'public/light_01.wav'));
const started=Date.now();
const serveUrl=await bundle({entryPoint:path.join(root,'journey.tsx'),publicDir:path.join(root,'public'),outDir:path.join(out,'bundle')});
const edge='C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
const browserExecutable=process.env.REMOTION_BROWSER_EXECUTABLE || (fs.existsSync(edge)?edge:undefined);
const browser=await openBrowser('chrome',{browserExecutable});
try {
  const composition=await selectComposition({serveUrl,id:'WaterJourney',puppeteerInstance:browser});
  for(const frame of [48,156,255,302,340]) {
    await renderStill({serveUrl,composition,frame,output:path.join(out,`frame-${frame}.png`),puppeteerInstance:browser});
  }
  console.log('Journey preview frames ready');
  if(!process.argv.includes('--stills-only')) {
    let last=-1;
    await renderMedia({serveUrl,composition,codec:'h264',outputLocation:path.join(out,'output.mp4'),
      puppeteerInstance:browser,concurrency:2,crf:18,pixelFormat:'yuv420p',onProgress:({progress})=>{
        const pct=Math.floor(progress*10)*10;if(pct!==last){console.log(`Render ${pct}%`);last=pct;}
      }});
    fs.writeFileSync(path.join(out,'timing.json'),JSON.stringify({elapsedSeconds:(Date.now()-started)/1000,
      scope:'Hand-directed Remotion camera/scene design prototype; not Agent-generated and not integrated into VideoTrace'},null,2));
  }
} finally {await browser.close({silent:true});}

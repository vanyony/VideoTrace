import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import net from 'node:net';
import {fileURLToPath} from 'node:url';
import {bundle} from '@remotion/bundler';
import {selectComposition,renderMedia,renderStill,openBrowser,makeCancelSignal} from '@remotion/renderer';
import {sampleFrame} from './timeline.mjs';

const root=path.dirname(fileURLToPath(import.meta.url));
const job=JSON.parse(fs.readFileSync(process.argv[2],'utf8'));
// Reserve a free ephemeral port rather than competing with services on 3000/3001.
const port=await new Promise((resolve,reject)=>{
  const server=net.createServer();server.on('error',reject);
  server.listen(0,()=>{const selected=server.address().port;server.close(()=>resolve(selected));});
});
const {cancelSignal,cancel}=makeCancelSignal();
let browser;
process.on('SIGTERM',()=>{cancel();});
process.on('SIGINT',()=>{cancel();});
try {
  const destination=path.resolve(job.bundleDirectory);
  if(!fs.existsSync(path.join(destination,'index.html'))) {
    const temp=destination+`.tmp-${process.pid}`;
    const publicDir=temp+'-public';
    fs.mkdirSync(publicDir,{recursive:true});
    fs.copyFileSync(job.font,path.join(publicDir,'font.ttc'));
    fs.copyFileSync(path.resolve(root,'../assets/light_01.wav'),path.join(publicDir,'light_01.wav'));
    try {
      await bundle({entryPoint:path.join(root,'video.tsx'),publicDir,outDir:temp,onProgress:()=>{}});
      try {fs.renameSync(temp,destination);}catch(error){if(!fs.existsSync(path.join(destination,'index.html')))throw error;}
    } finally {fs.rmSync(temp,{recursive:true,force:true});fs.rmSync(publicDir,{recursive:true,force:true});}
  }
  const installed=process.platform==='win32'?[path.join(os.homedir(),'AppData/Local/Google/Chrome/Application/chrome.exe'),'C:/Program Files/Google/Chrome/Application/chrome.exe','C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe']:[];
  const browserExecutable=process.env.REMOTION_BROWSER_EXECUTABLE || installed.find(p=>fs.existsSync(p));
  browser=await openBrowser('chrome',{browserExecutable,chromeMode:browserExecutable?'chrome-for-testing':'headless-shell'});
  const inputProps={board:job.storyboard};
  const composition=await selectComposition({serveUrl:destination,id:'VideoTrace',inputProps,puppeteerInstance:browser,port});
  const common={serveUrl:destination,composition,inputProps,puppeteerInstance:browser,timeoutInMilliseconds:30000,port};
  if(job.mode==='stills') {
    for(const sample of job.samples) {
      await renderStill({...common,frame:sampleFrame(job.storyboard.scenes,sample.sceneIndex,sample.timestampMs),output:sample.path,imageFormat:'png'});
    }
  } else if(job.mode==='video') {
    await renderMedia({...common,codec:'h264',outputLocation:job.output,concurrency:2,crf:20,pixelFormat:'yuv420p',cancelSignal});
  } else throw new Error('Unknown trusted render job mode');
  console.log(JSON.stringify({renderer:'remotion',frames:composition.durationInFrames}));
} finally {if(browser)await browser.close({silent:true});}

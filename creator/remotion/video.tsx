import React, {useEffect, useState} from 'react';
import {AbsoluteFill, Audio, Composition, registerRoot, staticFile, useCurrentFrame,delayRender,continueRender,cancelRender} from 'remotion';
import {CanvasNode, Icon} from './components';
import {FPS, clamp, durationFrames, sceneAt, interpolateKeys} from './timeline.mjs';

type Data = Record<string, any>;
const palettes:Record<string,string[]> = {dark:['#0b1428','#172641','#f4f8ff','#64dfbf','#8ba5c7'],light:['#f3f6fb','#ffffff','#172641','#137bba','#516784'],warm:['#fff4e6','#ffe7cb','#422e27','#c45832','#856452']};
function positions(layout:string,n:number):number[][] {
  if(layout==='pipeline')return Array.from({length:n},(_,i)=>[280,445+i*150]);
  if(layout==='bars')return Array.from({length:n},(_,i)=>[130+i*460/Math.max(n-1,1),690]);
  if(layout==='compare')return Array.from({length:n},(_,i)=>[200+i%2*320,505+Math.floor(i/2)*240]);
  if(layout==='split')return [[360,440],...Array.from({length:n-1},(_,i)=>[130+i*460/Math.max(n-2,1),720])];
  if(layout==='merge')return [...Array.from({length:n-1},(_,i)=>[130+i*460/Math.max(n-2,1),440]),[360,720]];
  return [[360,590],...Array.from({length:n-1},(_,i)=>[360+Math.cos(-Math.PI/2+i*2*Math.PI/(n-1))*220,620+Math.sin(-Math.PI/2+i*2*Math.PI/(n-1))*155])];
}
const Text=({children,style={}}:any)=><div style={{position:'absolute',whiteSpace:'pre-wrap',overflowWrap:'anywhere',lineHeight:1.4,...style}}>{children}</div>;
function FontReady() {
  const [handle]=useState(()=>delayRender('Loading configured Chinese font'));
  useEffect(()=>{let active=true;document.fonts.load('24px VideoTrace','视频创作').then(fonts=>{
    if(!fonts.length)throw new Error('Configured Chinese font did not load');
    if(active)continueRender(handle);
  }).catch(cancelRender);return ()=>{active=false;};},[handle]);
  return null;
}
function Diagram({scene,time}:{scene:Data,time:number}) {
  const v=scene.visual,[bg,panel,fg,accent,muted]=palettes[v.theme]; const coords=positions(v.layout,v.items.length);
  const edges=v.layout==='pipeline'?coords.slice(1).map((_,i)=>[i,i+1]):['split','orbit'].includes(v.layout)?coords.slice(1).map((_,i)=>[0,i+1]):v.layout==='merge'?coords.slice(1).map((_,i)=>[i,coords.length-1]):[];
  return <AbsoluteFill style={{background:bg,color:fg}}>
    <div style={{position:'absolute',inset:'100px 32px',background:panel,borderRadius:28}}/>
    <Text style={{left:64,top:150,fontSize:23,color:accent}}>VIDEO TRACE / 图解</Text>
    <Text style={{left:64,top:205,width:584,fontSize:48}}>{scene.headline}</Text>
    <svg width={720} height={1280} style={{position:'absolute'}}><defs><marker id="diagram-arrow" markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto"><path d="M0 0 10 5 0 10" fill={muted}/></marker></defs>
      {edges.map(([a,b],i)=>{const [ax,ay]=coords[a],[bx,by]=coords[b],d=Math.hypot(bx-ax,by-ay),ux=(bx-ax)/d,uy=(by-ay)/d,p=v.motion==='draw'?clamp(time/1400):1;
        return <line key={i} x1={ax+ux*60} y1={ay+uy*60} x2={ax+ux*60+(bx-ax-ux*120)*p} y2={ay+uy*60+(by-ay-uy*120)*p} stroke={muted} strokeWidth={3} markerEnd={p===1?'url(#diagram-arrow)':undefined}/>;})}</svg>
    {v.items.map((item:Data,i:number)=>{let [x,y]=coords[i];const appear=clamp((time-i*250)/500);if(v.motion==='slide')x-=(1-appear)*65;const scale=v.motion==='pulse'?1+.04*Math.sin(time/333-i):1;
      return <React.Fragment key={i}><div style={{position:'absolute',left:x,top:y,opacity:['draw','reveal'].includes(v.motion)?appear:1,transform:`translate(-50%,-50%) scale(${scale})`}}>
        {v.layout==='bars'?<div style={{position:'absolute',left:-36,bottom:0,width:72,height:item.value*2.2*clamp(time/1400),background:accent,borderRadius:6}}><span style={{position:'absolute',top:-36,fontSize:23,color:fg}}>{item.value}%</span></div>:<div style={{width:106,height:98,display:'grid',placeItems:'center',border:`2px solid ${accent}`,borderRadius:18,background:bg}}><Icon kind={item.icon} color={accent}/></div>}
      </div><Text style={{left:v.layout==='pipeline'?x+76:x-92,top:v.layout==='pipeline'?y-20:y+58,width:184,textAlign:v.layout==='pipeline'?'left':'center',fontSize:25}}>{item.label}</Text></React.Fragment>;})}
    <Text style={{left:76,top:985,width:568,fontSize:30,color:muted}}>{scene.points.join('\n')}</Text>
  </AbsoluteFill>;
}
function Template({scene,time}:{scene:Data,time:number}) {
  const active=Math.min(scene.points.length-1,Math.floor(time/(scene.durationMs/Math.max(1,scene.points.length))));
  return <AbsoluteFill style={{background:'#0b1428',color:'#f4f8ff'}}>
    <div style={{position:'absolute',inset:'100px 32px',borderRadius:28,background:'#172641'}}/>
    <Text style={{left:64,top:150,fontSize:23,color:'#64dfbf'}}>{{title:'视频重点',points:'核心观点',flow:'理解路径'}[scene.template as 'title'|'points'|'flow']}</Text>
    <Text style={{left:64,top:230,width:584,fontSize:48,lineHeight:'68px'}}>{scene._textLayout.heading.join('\n')}</Text>
    {scene._textLayout.blocks.map((block:Data,i:number)=><React.Fragment key={i}>
      <div style={{position:'absolute',left:64,top:block.y+16,width:14,height:14,borderRadius:'50%',background:active===i||scene.template!=='flow'?'#64dfbf':'#7184a1',opacity:scene.animation==='reveal'?clamp((time-i*350)/500):1}}/>
      {scene.template==='flow'&&i<scene._textLayout.blocks.length-1&&<div style={{position:'absolute',left:70,top:block.y+36,width:3,height:scene._textLayout.blocks[i+1].y-block.y-31,background:'#425774'}}/>}
      <Text style={{left:96,top:block.y,width:528,fontSize:34,lineHeight:'50px',borderRadius:12,background:scene.animation==='highlight'&&active===i?'#234b50':'transparent',opacity:scene.animation==='reveal'?clamp((time-i*350)/500):1}}>{block.lines.join('\n')}</Text>
    </React.Fragment>)}
  </AbsoluteFill>;
}
function Canvas({scene,time}:{scene:Data,time:number}) {
  const c=scene.canvas, camera=interpolateKeys({x:360,y:640,zoom:1},c.camera,time);
  const elements=[...c.elements].sort((a,b)=>a.z-b.z);
  // Keep painter order across coordinate spaces; each element has its own projected layer.
  return <AbsoluteFill style={{background:c.background,overflow:'hidden'}}>
    {elements.map(node=><AbsoluteFill key={node.id} style={{transform:node.space==='world'?`translate(360px,640px) scale(${camera.zoom}) translate(${-camera.x}px,${-camera.y}px)`:'none',transformOrigin:'0 0'}}><CanvasNode node={node} nodes={elements} time={time}/></AbsoluteFill>)}
    {c.layout==='framed'&&<><Text style={{left:64,top:205,width:584,fontSize:48,color:'#f4f8ff'}}>{scene.headline}</Text><Text style={{left:76,top:1000,width:568,fontSize:30,color:'#8ba5c7'}}>{scene.points.join('\n')}</Text></>}
  </AbsoluteFill>;
}
function Motion({scene,time}:{scene:Data,time:number}) {
  const m=scene.motion,accent=({violet:'#9c8bff',cyan:'#54e5ef',lime:'#b6fb68'} as Data)[m.accent];
  return <AbsoluteFill style={{background:'radial-gradient(ellipse at 85% 25%,#252441,#080b14 70%)',color:'#f4f8ff'}}>
    <Text style={{top:100,left:56,fontSize:24,letterSpacing:5,color:accent}}>{m.eyebrow}</Text>
    <div style={{position:'absolute',top:m.style==='launch'?260:340,left:56,right:56}}>{m.words.map((word:string,i:number)=>{const p=clamp((time-i*220)/650);return <div key={i} style={{fontSize:Math.min(m.style==='kinetic'?100:86,600/Array.from(word).length),fontWeight:800,lineHeight:1.2,whiteSpace:'nowrap',letterSpacing:-2,opacity:p,transform:`translateY(${(1-p)*45}px) scale(${.94+.06*p})`,transformOrigin:'left',color:i%2?accent:'#f4f8ff'}}>{word}</div>;})}</div>
    <Text style={{left:56,top:900,width:608,fontSize:30,color:'#b9c4da'}}>{scene.points.join('\n\n')}</Text>
  </AbsoluteFill>;
}
function Scene({scene,time}:{scene:Data,time:number}) {
  return scene.canvas?<Canvas {...{scene,time}}/>:scene.motion?<Motion {...{scene,time}}/>:scene.visual?<Diagram {...{scene,time}}/>:<Template {...{scene,time}}/>;
}
export function Video({board}:{board:Data}) {
  const frame=useCurrentFrame(),{index,time}=sceneAt(board.scenes,frame),scene=board.scenes[index];
  const transition=scene.motion?.transition;
  const p=clamp(time/450),prior=index?board.scenes[index-1]:null;
  return <AbsoluteFill style={{fontFamily:'VideoTrace, sans-serif',overflow:'hidden',background:'#0b1428'}}>
    <style>{`@font-face {font-family: VideoTrace; src: url('${staticFile('font.ttc')}'); font-weight: 100 900;} * {box-sizing:border-box;}`}</style>
    <FontReady/>
    {prior&&transition&&<Scene scene={prior} time={prior.durationMs-1000/FPS}/>}
    <AbsoluteFill style={{opacity:transition==='fade'?p:scene.animation==='fade'&&!scene.motion?clamp(time/500):1,transform:transition==='push'?`translateX(${(1-p)*720}px)`:'none',clipPath:transition==='wipe'?`inset(0 ${(1-p)*100}% 0 0)`:'none'}}><Scene {...{scene,time}}/></AbsoluteFill>
    {board.musicId==='light_01'&&<Audio src={staticFile('light_01.wav')} loop volume={.18}/>}
  </AbsoluteFill>;
}
registerRoot(()=> <Composition id="VideoTrace" component={Video} width={720} height={1280} fps={FPS} durationInFrames={360} defaultProps={{board:{scenes:[],musicId:'none'}}} calculateMetadata={({props})=>({durationInFrames:durationFrames(props.board.scenes)})}/>);

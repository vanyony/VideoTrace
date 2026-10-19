import React from 'react';
import {AbsoluteFill, Audio, Composition, interpolate, registerRoot, staticFile, useCurrentFrame, useVideoConfig} from 'remotion';

type Node = any;
const ease=(p:number)=>1-Math.pow(1-Math.min(1,Math.max(0,p)),3);
const clamp=(p:number)=>Math.max(0,Math.min(1,p));

function position(curve:Node, p:number) {
  const [a,b,c,d]=curve.points;
  return {x:(1-p)**3*a.x+3*(1-p)**2*p*b.x+3*(1-p)*p*p*c.x+p**3*d.x,
          y:(1-p)**3*a.y+3*(1-p)**2*p*b.y+3*(1-p)*p*p*c.y+p**3*d.y};
}

function stateAt(node:Node,time:number) {
  let state={x:node.x,y:node.y,scale:node.scale??1,rotation:node.rotation??0,opacity:node.opacity};
  const timeline:any[]=[[0,{...state},'linear']];
  for(const k of node.keyframes) {
    for(const name of Object.keys(state)) if(k[name]!=null) state[name]=k[name];
    if(k.timeMs===0) timeline[0]=[0,{...state},k.easing];
    else timeline.push([k.timeMs,{...state},k.easing]);
  }
  let result={...timeline.at(-1)[1]};
  for(let i=1;i<timeline.length;i++) {
    const [start,a]=timeline[i-1], [end,b,easing]=timeline[i];
    if(time<=end) {
      let p=clamp((time-start)/(end-start));
      if(easing==='easeInOut') p=p*p*(3-2*p);
      if(easing==='easeOut') p=ease(p);
      for(const key of Object.keys(a)) result[key]=a[key]+(b[key]-a[key])*p;
      break;
    }
  }
  if(node.curve && node.kind!=='path') {
    let p=Math.max(0,(time-node.curve.startMs)/(node.curve.endMs-node.curve.startMs));
    p=node.curve.loop && time>=node.curve.startMs?p%1:Math.min(1,p);
    Object.assign(result,position(node.curve,p));
  }
  if(node.pulse) result.scale*=1+.06*Math.sin(Math.max(0,time)/1000*Math.PI*2);
  return result;
}

function Sprite({node}:{node:Node}) {
  const id=node.id.replace(/[^\w-]/g,'');
  const hi=node.color, lo=node.fill;
  return <svg width={node.width} height={node.height} viewBox="0 0 320 320" style={{overflow:'visible'}}>
    <defs>
      <radialGradient id={`body-${id}`} cx="30%" cy="22%" r="85%"><stop stopColor={hi}/><stop offset=".45" stopColor={lo}/><stop offset="1" stopColor="#10384f"/></radialGradient>
      <linearGradient id={`cloud-${id}`} x2=".3" y2="1"><stop stopColor="#f2fdff"/><stop offset=".6" stopColor={lo}/><stop offset="1" stopColor={hi}/></linearGradient>
      <radialGradient id={`sun-${id}`} cx="35%" cy="25%"><stop stopColor="#fff5c6"/><stop offset=".65" stopColor={hi}/><stop offset="1" stopColor={lo}/></radialGradient>
      <clipPath id={`clip-${id}`}><circle cx="160" cy="160" r="142"/></clipPath>
      <radialGradient id={`shade-${id}`} cx="25%" cy="25%" r="85%"><stop stopColor="#fff" stopOpacity=".15"/><stop offset=".6" stopOpacity="0"/><stop offset="1" stopColor="#071b34" stopOpacity=".7"/></radialGradient>
    </defs>
    {node.sprite==='earth' && <>
      <circle cx="160" cy="160" r="151" fill="none" stroke={hi} strokeOpacity=".1" strokeWidth="9"/>
      <circle cx="160" cy="160" r="142" fill={`url(#body-${id})`}/>
      <g clipPath={`url(#clip-${id})`} fill={hi}>
        <path d="M35 90 90 48 137 59 155 97 123 120 141 145 101 162 65 140Z"/>
        <path d="M130 163 166 174 178 210 153 268 139 238 141 204 118 180Z"/>
        <path d="M186 68 244 73 283 108 246 130 220 151 189 138 169 105Z"/>
        <path d="M188 145 224 159 233 196 207 241 185 202Z"/>
        <ellipse cx="249" cy="239" rx="19" ry="11"/>
        {[75,140,210].map((y,i)=><path key={y} d={`M${50+i*25} ${y} Q${110+i*30} ${y+24} ${180+i*25} ${y+8}`} fill="none" stroke="#ecfffb" strokeWidth="5" strokeLinecap="round" opacity=".5"/>)}
        <circle cx="160" cy="160" r="142" fill={`url(#shade-${id})`}/>
      </g>
      <circle cx="160" cy="160" r="142" fill="none" stroke="#b6fff3" strokeOpacity=".4" strokeWidth="1.5"/>
    </>}
    {node.sprite==='sun' && <>
      <circle cx="160" cy="160" r="137" fill={hi} opacity=".035"/>
      <circle cx="160" cy="160" r="119" fill={hi} opacity=".06"/>
      {Array.from({length:12},(_,i)=>{const a=i*Math.PI/6;return <line key={i} x1={160+112*Math.cos(a)} y1={160+112*Math.sin(a)} x2={160+139*Math.cos(a)} y2={160+139*Math.sin(a)} stroke={hi} strokeWidth="5" strokeLinecap="round"/>})}
      <circle cx="160" cy="160" r="91" fill={`url(#sun-${id})`}/>
      <ellipse cx="130" cy="121" rx="34" ry="21" fill="#fff9dc" opacity=".3" transform="rotate(-25 130 121)"/>
    </>}
    {node.sprite==='cloud' && <>
      <path d="M67 246C10 246 8 163 67 151C56 78 156 42 196 105C250 73 295 131 274 173C330 221 281 256 249 251H67Z" fill={`url(#cloud-${id})`} stroke="#e4fbff" strokeWidth="2" strokeOpacity=".6"/>
      <path d="M76 146C81 92 148 80 171 111" fill="none" stroke="white" strokeWidth="9" strokeLinecap="round" opacity=".45"/>
    </>}
    {node.sprite==='droplet' && <>
      <path d="M160 20C143 68 56 152 56 211A104 104 0 00264 211C264 151 178 66 160 20Z" fill={`url(#cloud-${id})`}/>
      <path d="M104 177Q76 237 140 263" fill="none" stroke="#e6ffff" strokeWidth="13" strokeLinecap="round" opacity=".65"/>
    </>}
  </svg>;
}

function CanvasNode({node,nodes,time}:{node:Node,nodes:Node[],time:number}) {
  const s=stateAt(node,time);
  if(node.kind==='path') {
    const c=node.curve;
    if(time<c.startMs) return null;
    const [a,b,cc,d]=c.points;
    const progress=clamp((time-c.startMs)/Math.min(600,c.endMs-c.startMs));
    return <svg width="720" height="1280" style={{position:'absolute',inset:0,opacity:s.opacity}}>
      <path d={`M${a.x} ${a.y} C${b.x} ${b.y} ${cc.x} ${cc.y} ${d.x} ${d.y}`} pathLength="1" strokeDasharray="1" strokeDashoffset={1-progress} fill="none" stroke={node.color} strokeWidth="2" opacity=".28"/>
      {(c.loop?[0,.33,.66]:[0]).map((offset,i)=>{
        const raw=Math.max(0,(time-c.startMs)/(c.endMs-c.startMs));
        const p=c.loop?(raw+offset)%1:Math.min(1,raw);
        if(p>progress) return null;
        const v=position(c,p);
        return <g key={i}><circle cx={v.x} cy={v.y} r="12" fill={node.color} opacity=".08"/><circle cx={v.x} cy={v.y} r="5" fill={node.color}/><circle cx={v.x-1} cy={v.y-1} r="2" fill="#fff" opacity=".8"/></g>;
      })}
    </svg>;
  }
  if(['arrow','line'].includes(node.kind)) {
    const from=nodes.find(e=>e.id===node.fromId), to=nodes.find(e=>e.id===node.toId);
    const a=stateAt(from,time),b=stateAt(to,time),distance=Math.hypot(b.x-a.x,b.y-a.y);
    const ux=(b.x-a.x)/distance,uy=(b.y-a.y)/distance;
    const radius=(n:Node,v:Node)=>Math.min(n.width/(2*Math.max(Math.abs(ux),.0001)),n.height/(2*Math.max(Math.abs(uy),.0001)))*v.scale+8;
    const start=radius(from,a),end=radius(to,b);
    if(start+end>=distance) return null;
    const x1=a.x+ux*start,y1=a.y+uy*start,x2=b.x-ux*end,y2=b.y-uy*end;
    return <svg width="720" height="1280" style={{position:'absolute',inset:0,opacity:s.opacity*Math.min(a.opacity,b.opacity)}}>
      <line {...{x1,y1,x2,y2}} stroke={node.color} strokeWidth="3" opacity=".75"/>
      {node.kind==='arrow' && <path d={`M${x2-ux*12-uy*6} ${y2-uy*12+ux*6} L${x2} ${y2} L${x2-ux*12+uy*6} ${y2-uy*12-ux*6}`} stroke={node.color} fill="none" strokeWidth="3"/>}
    </svg>;
  }
  return <div style={{position:'absolute',left:s.x,top:s.y,width:node.width,height:node.height,opacity:s.opacity,
    transform:`translate(-50%,-50%) rotate(${s.rotation}deg) scale(${s.scale})`,transformOrigin:'center',
    filter:node.kind==='sprite'?'drop-shadow(0 15px 23px rgba(0,0,0,.24))':undefined}}>
    {node.kind==='sprite'?<Sprite node={node}/>:node.kind==='text'?<div style={{width:'100%',height:'100%',display:'flex',alignItems:'center',justifyContent:'center',fontSize:node.fontSize,fontWeight:500,color:node.color,textAlign:'center',letterSpacing:1}}>{node.text}</div>:
      <div style={{width:'100%',height:'100%',background:node.fill,border:`1px solid ${node.color}`,borderRadius:node.kind==='circle'?'50%':18,boxShadow:node.kind==='circle'?`0 0 18px ${node.color}55`:undefined}}/>}
  </div>;
}

const WaterCycle=({board}:{board:Node})=>{
  const frame=useCurrentFrame(),{fps}=useVideoConfig();
  let elapsed=0,index=0;
  for(let i=0;i<board.scenes.length;i++) {if(frame/fps*1000<elapsed+board.scenes[i].durationMs){index=i;break;}elapsed+=board.scenes[i].durationMs;}
  const scene=board.scenes[index],time=frame/fps*1000-elapsed,seconds=frame/fps;
  const [phase,...rest]=scene.headline.split(/[：:]/),description=rest.join('：');
  const enter=ease(time/600);
  return <AbsoluteFill style={{background:'#081726',fontFamily:'"Microsoft YaHei",sans-serif',color:'#effcfb',overflow:'hidden'}}>
    <AbsoluteFill style={{background:`radial-gradient(ellipse at ${48+Math.sin(seconds*.2)*8}% 65%,#133f49 0%,#0b2637 35%,#081726 72%)`}}/>
    <AbsoluteFill style={{backgroundImage:'radial-gradient(#a9dfd01c .8px,transparent .8px)',backgroundSize:'28px 28px',maskImage:'linear-gradient(transparent,black 35%,black 70%,transparent)'}}/>
    <div style={{position:'absolute',top:74,left:52,fontSize:17,letterSpacing:5,color:'#8fc6ba'}}>THE WATER CYCLE</div>
    <div style={{position:'absolute',top:124,left:48,opacity:enter,transform:`translateY(${(1-enter)*18}px)`}}>
      <div style={{fontSize:86,fontWeight:800,letterSpacing:8,lineHeight:1.3,background:'linear-gradient(100deg,#f3fff7,#a9e8d6)',backgroundClip:'text',WebkitTextFillColor:'transparent'}}>{phase}</div>
      <div style={{fontSize:28,color:'#b0cdc9',marginTop:13,letterSpacing:2}}>{description}</div>
    </div>
    <div style={{position:'absolute',inset:0,transform:'translate(-36px,-80px) scale(1.1)',transformOrigin:'0 0'}}>
      {[...scene.canvas.elements].sort((a,b)=>a.z-b.z).map(node=><CanvasNode key={node.id} {...{node,time}} nodes={scene.canvas.elements}/>)}
    </div>
    <div style={{position:'absolute',left:52,right:52,top:1054,opacity:enter,transform:`translateY(${(1-enter)*12}px)`}}>
      <div style={{width:27,height:3,background:'#75d8ba',marginBottom:24,borderRadius:3}}/>
      <div style={{fontSize:27,lineHeight:1.75,color:'#c6dcd7',letterSpacing:1}}>{scene.points.join(' · ')}</div>
    </div>
    <div style={{position:'absolute',bottom:48,left:52,right:52,display:'flex',justifyContent:'space-between',fontSize:13,letterSpacing:3,color:'#588179'}}><span>VIDEOTRACE</span><span>自然 · 循环往复</span></div>
    <Audio src={staticFile('light_01.wav')} loop volume={f=>interpolate(f,[0,15,345,359],[0,.15,.15,0],{extrapolateLeft:'clamp',extrapolateRight:'clamp'})}/>
  </AbsoluteFill>;
};

registerRoot(()=> <Composition id="WaterCycle" component={WaterCycle} durationInFrames={360} fps={24} width={720} height={1280} defaultProps={{board:null}}/>);

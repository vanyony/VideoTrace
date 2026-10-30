import React from 'react';
import {interpolateKeys} from './timeline.mjs';

type Node = any;
const clamp=(p:number)=>Math.max(0,Math.min(1,p));

function position(curve:Node, p:number) {
  const [a,b,c,d]=curve.points;
  return {x:(1-p)**3*a.x+3*(1-p)**2*p*b.x+3*(1-p)*p*p*c.x+p**3*d.x,
          y:(1-p)**3*a.y+3*(1-p)**2*p*b.y+3*(1-p)*p*p*c.y+p**3*d.y};
}

function stateAt(node:Node,time:number) {
  let result=interpolateKeys({x:node.x,y:node.y,scale:node.scale,rotation:node.rotation,opacity:node.opacity},node.keyframes,time);
  if(node.curve && node.kind!=='path') {
    let p=Math.max(0,(time-node.curve.startMs)/(node.curve.endMs-node.curve.startMs));
    p=node.curve.loop && time>=node.curve.startMs?p%1:Math.min(1,p);
    Object.assign(result,position(node.curve,p));
  }
  if(node.pulse) result.scale*=1+.06*Math.sin(Math.max(0,time)/1000*Math.PI*2);
  return result;
}

function Sprite({node}:{node:Node}) {
  const id=React.useId().replace(/[^\w-]/g,'');
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
    {node.sprite==='factory' && <g fill={lo} stroke={hi} strokeWidth="7"><path d="M30 275V140L110 185V140L190 185V90H235V275Z"/><path d="M210 90V40H235V90"/>{[60,120,180].map(x=><rect key={x} x={x} y={215} width={25} height={30} fill={hi}/>)}</g>}
    {node.sprite==='truck' && <g fill={lo} stroke={hi} strokeWidth="7"><rect x="20" y="90" width="175" height="140" rx="15"/><path d="M195 140H260L300 190V230H195Z"/><path d="M225 155H253L278 186H225Z" fill={hi}/>{[75,245].map(x=><g key={x}><circle cx={x} cy={240} r={28} fill="#15283d"/><circle cx={x} cy={240} r={10} fill={hi}/></g>)}</g>}
    {node.sprite==='water' && <g fill={lo} stroke={hi} strokeWidth="5"><path d="M0 130Q40 100 80 130T160 130T240 130T320 130V320H0Z"/>{[180,230,280].map(y=><path key={y} d={`M0 ${y}Q40 ${y-25} 80 ${y}T160 ${y}T240 ${y}T320 ${y}`} fill="none" opacity=".5"/>)}</g>}
    {node.sprite==='ripple' && <g fill="none" stroke={hi} strokeWidth="5">{[40,80,120].map(r=><ellipse key={r} cx={160} cy={160} rx={r} ry={r*.3} opacity={1-r/180}/>)}</g>}
  </svg>;
}

export function CanvasNode({node,nodes,time}:{node:Node,nodes:Node[],time:number}) {
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
    if(distance<.001)return null;
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
    {node.kind==='sprite'?<Sprite node={node}/>:node.kind==='icon'?<div style={{width:'100%',height:'100%',display:'grid',placeItems:'center'}}><Icon kind={node.icon} color={node.color}/></div>:node.kind==='text'?<div style={{width:'100%',height:'100%',display:'flex',alignItems:'center',justifyContent:'center',fontSize:node.fontSize,fontWeight:500,color:node.color,textAlign:'center',overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>{node.text}</div>:
      <div style={{width:'100%',height:'100%',background:node.fill,border:`1px solid ${node.color}`,borderRadius:node.kind==='circle'?'50%':18,boxShadow:node.kind==='circle'?`0 0 18px ${node.color}55`:undefined,display:'flex',alignItems:'center',justifyContent:'center',fontSize:node.fontSize,color:node.color,textAlign:'center',overflowWrap:'anywhere',whiteSpace:'pre-wrap'}}>{node.text}</div>}
  </div>;
}

export function Icon({kind,color}:{kind:string,color:string}) {
  const paths:Record<string,React.ReactNode>={
    video:<><rect x="8" y="16" width="64" height="48" rx="6"/><path d="m32 28 20 12-20 12Z" fill={color}/></>,
    phone:<><rect x="20" y="4" width="40" height="72" rx="8"/><path d="M32 66H48"/></>,
    audio:<>{[18,30,42,54,66].map((x,i)=><path key={x} d={`M${x} ${40-[12,24,32,16,25][i]}V${40+[12,24,32,16,25][i]}`}/>)}</>,
    person:<><circle cx="40" cy="22" r="12"/><path d="M12 70C12 38 68 38 68 70"/></>,
    agent:<><rect x="10" y="20" width="60" height="45" rx="10"/><path d="M40 20V8M27 50H53"/><circle cx="28" cy="36" r="3" fill={color}/><circle cx="52" cy="36" r="3" fill={color}/></>,
    database:<><ellipse cx="40" cy="18" rx="28" ry="10"/><path d="M12 18V60C12 74 68 74 68 60V18M12 39C12 53 68 53 68 39"/></>,
    cards:<><rect x="8" y="12" width="52" height="48" rx="5"/><rect x="20" y="24" width="52" height="48" rx="5"/></>,
    server:<>{[8,32,56].map(y=><g key={y}><rect x="10" y={y} width="60" height="18" rx="3"/><path d={`M20 ${y+9}H45`}/></g>)}</>,
    text:<><path d="M12 14H68M40 14V68M28 68H52"/></>,
    file:<><path d="M18 6H50L66 22V74H18ZM50 6V22H66M28 36H56M28 48H56M28 60H48"/></>,
  };
  return <svg width="80%" height="80%" viewBox="0 0 80 80" fill="none" stroke={color} strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">{paths[kind]}</svg>;
}

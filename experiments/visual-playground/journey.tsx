import React from 'react';
import {AbsoluteFill, Audio, Composition, interpolate, registerRoot, staticFile, useCurrentFrame} from 'remotion';

const clamp=(n:number)=>Math.max(0,Math.min(1,n));
const ease=(n:number)=>{const p=clamp(n);return p*p*(3-2*p);};
const tween=(t:number,start:number,end:number,a:number,b:number)=>a+(b-a)*ease((t-start)/(end-start));
const INK='#143b52', BLUE='#287bc0', MINT='#77d2bd';

function Cloud({x,y,scale=1,opacity=1}:{x:number,y:number,scale?:number,opacity?:number}) {
  const outline='M-225 90C-315 83-306-41-211-48C-218-153-65-190-9-99C68-176 191-113 185-35C286-25 290 90 197 101Z';
  return <g transform={`translate(${x} ${y}) scale(${scale})`} opacity={opacity}>
    <path d={outline} transform="translate(3 16)" fill="#85b8bd" opacity=".16"/>
    <path d={outline} fill="url(#cloudFill)" stroke="#739eaa" strokeWidth="2"/>
    <path d="M-183-50C-183-108-105-138-65-101" fill="none" stroke="#fff" strokeWidth="13" strokeLinecap="round" opacity=".9"/>
    <path d="M10-74C69-113 134-80 144-35" fill="none" stroke="#fff" strokeWidth="10" strokeLinecap="round" opacity=".6"/>
  </g>;
}

function Drop({x,y,scale=1,opacity=1}:{x:number,y:number,scale?:number,opacity?:number}) {
  return <g transform={`translate(${x} ${y}) scale(${scale})`} opacity={opacity}>
    <path d="M0-90C-18-54-65-12-65 28A65 65 0 0065 28C65-12 18-54 0-90Z" fill="url(#dropFill)" stroke="#206fa3" strokeWidth="2"/>
    <path d="M-33 10C-44 38-24 61-4 62" fill="none" stroke="#e8ffff" strokeWidth="9" strokeLinecap="round"/>
    <ellipse cx="22" cy="19" rx="9" ry="17" fill="#9bf1eb" opacity=".65"/>
  </g>;
}

const Journey=()=>{
  const f=useCurrentFrame(),t=f/24;
  const intoCloud=ease((t-3.9)/2.1),rain=ease((t-8.8)/1.8),outro=ease((t-13)/1.8);
  const phase=t<4.5?0:t<9?1:t<13?2:3;
  const start=[0,4.5,9,13][phase],enter=ease((t-start)/.45);
  const titles=['升起','成云','落下','再出发'];
  const descriptions=['阳光让水面蒸发','水蒸气冷却，凝结成小水滴','水滴长大，形成降水','回到地表，继续下一次循环'];
  const waterY=850+intoCloud*600-rain*450-outro*145;
  const cloudY=t<13?-230+intoCloud*835-rain*850:470;
  const cloudX=t<13?378+Math.sin(t*.6)*7:tween(t,13,14.7,930,460);
  const cloudScale=t<13?1.05:.65;
  const sunOpacity=1-intoCloud+outro;
  const hit=12.15;
  const fall=tween(t,9.3,hit,560,930);
  const heroScale=1.05-outro*.4;
  const waterPath=(dy:number,amplitude:number)=>{
    const y=waterY+dy,s=Math.sin(t*1.2)*amplitude;
    return `M-50 ${y} C90 ${y-35+s} 150 ${y+35-s} 290 ${y+5} S520 ${y-28+s} 770 ${y+2} L770 1400 L-50 1400Z`;
  };
  return <AbsoluteFill style={{fontFamily:'"Microsoft YaHei",sans-serif',background:'#f4f5e9',color:INK,overflow:'hidden'}}>
    <svg width="720" height="1280" viewBox="0 0 720 1280" style={{position:'absolute',inset:0}}>
      <defs>
        <linearGradient id="sky" x2="0" y2="1"><stop stopColor="#f8f5e8"/><stop offset="1" stopColor="#d4ece8"/></linearGradient>
        <radialGradient id="sun"><stop stopColor="#fff1ad"/><stop offset=".75" stopColor="#ffd583"/><stop offset="1" stopColor="#f5b66e"/></radialGradient>
        <linearGradient id="cloudFill" x2=".3" y2="1"><stop stopColor="#fffef6"/><stop offset=".55" stopColor="#f7fffb"/><stop offset="1" stopColor="#c4e5e6"/></linearGradient>
        <linearGradient id="dropFill" x2=".7" y2="1"><stop stopColor="#a8f1e1"/><stop offset=".5" stopColor="#5dc5d5"/><stop offset="1" stopColor="#2d88bc"/></linearGradient>
        <linearGradient id="sea" x2=".1" y2="1"><stop stopColor="#4bacc5"/><stop offset="1" stopColor="#176599"/></linearGradient>
      </defs>
      <rect width="720" height="1280" fill="url(#sky)"/>
      <ellipse cx={160+t*3} cy="620" rx="420" ry="350" fill="#f8dfad" opacity={.12*(1-intoCloud)}/>
      <g opacity={sunOpacity} transform={`translate(${140+outro*15} ${525+intoCloud*100-outro*110}) scale(${1-outro*.25})`}>
        <circle r="114" fill="#f8d49a" opacity=".16"/>
        <circle r="91" fill="#f9d595" opacity=".28"/>
        {Array.from({length:12},(_,i)=>{const a=i*Math.PI/6+t*.06;return <line key={i} x1={103*Math.cos(a)} y1={103*Math.sin(a)} x2={123*Math.cos(a)} y2={123*Math.sin(a)} stroke="#e2a75d" strokeWidth="3" strokeLinecap="round"/>})}
        <circle r="74" fill="url(#sun)" stroke="#e8b46b" strokeWidth="2"/>
        <ellipse cx="-19" cy="-25" rx="28" ry="18" fill="#fff6c7" opacity=".6" transform="rotate(-25)"/>
      </g>
      {/* Water stays in the world. The camera leaves it, then meets it again. */}
      <path d={waterPath(-20,15)} fill="#85d1ce" opacity=".75"/>
      <path d={waterPath(0,18)} fill="url(#sea)"/>
      <path d={waterPath(35,12)} fill="#237fae" opacity=".3"/>
      {Array.from({length:7},(_,i)=>{
        const y=waterY+65+i*46,shift=Math.sin(t*.9+i)*22;
        return <path key={i} d={`M${30+shift+(i%2)*60} ${y} Q${130+shift} ${y+12} ${238+shift} ${y} M${410-shift} ${y+18} Q${500-shift} ${y+3} ${650-shift} ${y+18}`} fill="none" stroke="#b4eee2" strokeWidth={i===0?3:2} opacity={.4-i*.035}/>;
      })}
      {/* Vapor is light particles, not liquid drops. */}
      <g opacity={t<8.8?1:1-rain}>
        {[0,1,2,3,4,5,6,7,8].map(i=>{
          const q=((Math.max(0,t-.5)*.24+i/9)%1),base=840+intoCloud*200;
          const x=350+Math.sin(q*7+i*.4)*55+(i%3-1)*25;
          const y=base-q*(540+intoCloud*350);
          const opacity=Math.sin(q*Math.PI)*(.7-intoCloud*.2)*ease((t-.4)/.8);
          return <g key={i} opacity={opacity}><circle cx={x} cy={y} r={13+i%3*2} fill="#fff" opacity=".45"/><circle cx={x} cy={y} r={4+i%3} fill="#82c8bf"/></g>;
        })}
        {[0,1,2].map(i=><path key={i} d={`M${300+i*52} ${810+intoCloud*250} C${235+i*70} ${715+intoCloud*180} ${420+i*20} ${640+intoCloud*60} ${345+i*38} ${515}`} fill="none" stroke="#70aaa8" strokeWidth="2" strokeDasharray="7 15" opacity={.2*(1-intoCloud)}/>) }
      </g>
      <Cloud x={cloudX} y={cloudY} scale={cloudScale} opacity={t<13?intoCloud:outro}/>
      {/* A close view of droplets condensing inside the cloud. */}
      <g opacity={ease((t-6)/.6)*(1-rain)}>
        {[-100,-25,65,130].map((x,i)=><circle key={i} cx={378+x} cy={cloudY+30+Math.sin(t*1.5+i)*12} r={8+ease((t-6.2-i*.15)/1.2)*(i%2?8:13)} fill="#78c9d0" opacity=".65"/>)}
        {[0,1,2].map(i=><circle key={i} cx={378+Math.sin(t+i)*55} cy={cloudY-30+i*18} r="3" fill="#fff"/>)}
      </g>
      {/* Following one drop brings the water surface back into view. */}
      {t>=9 && t<hit && <>
        {[-110,120].map((x,i)=><g key={x} opacity={.25*ease((t-9)/.5)}>
          <path d={`M${360+x} ${380+((t*180+i*200)%530)} v-65`} stroke="#6faebd" strokeWidth="3" strokeLinecap="round"/>
        </g>)}
        <Drop x={360+Math.sin((t-9)*1.6)*14} y={fall} scale={heroScale*tween(t,9,9.4,.55,1)}/>
      </>}
      {t>=hit && <g>
        {[0,.22,.44].map((delay,i)=>{
          const p=clamp((t-hit-delay)/1.7);
          return <ellipse key={delay} cx="360" cy={waterY+8} rx={20+p*245} ry={5+p*42} fill="none" stroke={i===0?'#f3fff0':'#9ee5d4'} strokeWidth={3-p*1.5} opacity={Math.sin(p*Math.PI)*.8}/>;
        })}
        {[0,1,2,3,4].map(i=>{
          const p=clamp((t-hit)/.85),dx=(i-2)*47;
          return <circle key={i} cx={360+dx*p} cy={waterY-110*Math.sin(p*Math.PI)*(1-Math.abs(i-2)*.18)} r={5-p*3} fill="#b9f5e3" opacity={1-p}/>;
        })}
      </g>}
      <g opacity={outro*.5}>
        <path d="M255 800C145 650 170 530 285 462" fill="none" stroke="#78b7af" strokeWidth="2" strokeDasharray="6 12"/>
        {[0,1,2,3].map(i=>{
          const y=620+((t*110+i*80)%200);
          return <Drop key={i} x={435+i*25} y={y} scale={.12} opacity={.6}/>;
        })}
      </g>
    </svg>
    <div style={{position:'absolute',left:48,top:66,fontSize:17,letterSpacing:4,fontWeight:500}}>一滴水的旅程 <span style={{fontSize:12,opacity:.5,letterSpacing:2,marginLeft:12}}>WATER / IN MOTION</span></div>
    <div style={{position:'absolute',left:44,top:130,opacity:enter,transform:`translateY(${(1-enter)*20}px)`}}>
      <div style={{fontSize:108,fontWeight:900,lineHeight:1.3,letterSpacing:9}}>{titles[phase]}<span style={{fontSize:50,color:'#72a39c'}}>。</span></div>
      <div style={{fontSize:27,letterSpacing:2,marginTop:12,color:'#5b7a7e'}}>{descriptions[phase]}</div>
    </div>
    <div style={{position:'absolute',left:48,bottom:58,fontSize:14,letterSpacing:3,color:waterY<1180?'#d5f6f0':'#678c8a'}}>VIDEOTRACE <span style={{marginLeft:18,opacity:.7}}>自然的循环，从未停下</span></div>
    <Audio src={staticFile('light_01.wav')} loop volume={frame=>interpolate(frame,[0,12,340,359],[0,.15,.15,0],{extrapolateLeft:'clamp',extrapolateRight:'clamp'})}/>
  </AbsoluteFill>;
};

registerRoot(()=> <Composition id="WaterJourney" component={Journey} durationInFrames={360} fps={24} width={720} height={1280}/>);

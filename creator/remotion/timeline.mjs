export const FPS = 24;
export const clamp = p => Math.max(0, Math.min(1, p));
export function interpolateKeys(base, keys, time) {
  let previous = {...base}, start = 0;
  for (const key of keys) {
    const next = {...previous};
    for (const name of Object.keys(base)) if (key[name] != null) next[name] = key[name];
    if (key.timeMs === 0) {previous = next; continue;}
    if (time <= key.timeMs) {
      let p = clamp((time-start)/(key.timeMs-start));
      if (key.easing === 'easeInOut') p = p*p*(3-2*p);
      if (key.easing === 'easeOut') p = 1-(1-p)**3;
      return Object.fromEntries(Object.keys(base).map(name => [name, previous[name]+(next[name]-previous[name])*p]));
    }
    previous = next; start = key.timeMs;
  }
  return previous;
}
export function sceneAt(scenes, frame) {
  let elapsed = 0;
  for (let index=0; index<scenes.length; index++) {
    const end = Math.round((elapsed+scenes[index].durationMs)*FPS/1000);
    if (frame < end) return {index, time:Math.max(0,frame*1000/FPS-elapsed), start:elapsed};
    elapsed += scenes[index].durationMs;
  }
  throw new Error('Frame outside storyboard');
}
export const durationFrames = scenes => Math.round(scenes.reduce((total,s) => total+s.durationMs,0)*FPS/1000);
export const sampleFrame = (scenes, index, time) => Math.min(durationFrames(scenes)-1,
  Math.max(Math.round(scenes.slice(0,index).reduce((t,s)=>t+s.durationMs,0)*FPS/1000),
    Math.min(Math.round(scenes.slice(0,index+1).reduce((t,s)=>t+s.durationMs,0)*FPS/1000)-1,
      Math.round((scenes.slice(0,index).reduce((t,s)=>t+s.durationMs,0)+time)*FPS/1000))));

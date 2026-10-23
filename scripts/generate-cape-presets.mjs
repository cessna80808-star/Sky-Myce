import { deflateSync } from 'node:zlib';
import { mkdir, writeFile } from 'node:fs/promises';
const directory = new URL('../src/main/resources/assets/skymyce/textures/capes/', import.meta.url);
const crc = bytes => { let value=0xffffffff; for(const byte of bytes){value^=byte; for(let i=0;i<8;i++)value=(value>>>1)^((value&1)?0xedb88320:0);} return (value^0xffffffff)>>>0; };
const chunk=(type,data)=>{const name=Buffer.from(type),out=Buffer.alloc(data.length+12);out.writeUInt32BE(data.length);name.copy(out,4);data.copy(out,8);out.writeUInt32BE(crc(Buffer.concat([name,data])),data.length+8);return out;};
const rgb = hex => [hex >> 16 & 255, hex >> 8 & 255, hex & 255];
const mix = (a, b, t) => a.map((value, i) => Math.round(value + (b[i] - value) * t));
// Multi-stop vertical gradient, t in 0..1.
const gradient = (stops, t) => { const at = Math.min(stops.length - 1.000001, Math.max(0, t) * (stops.length - 1)); return mix(rgb(stops[Math.floor(at)]), rgb(stops[Math.floor(at) + 1]), at % 1); };

// Each front face is the 10x16 outward side seen behind the player; '.' shows the background gradient.
// Palettes follow the menu themes (Signature, Verdant, Emberforge, Dark glass). Designs are left-right symmetric
// except scattered stars, so mirrored faces read the same.
const presets = {
  'sky-myce': { sky: [0x101826, 0x1B2440, 0x3A2C63], trim: 0x71D4EA, colors: { a: 0xB59BE6, h: 0xE4D8FF, g: 0x6E5A9E, s: 0xDDE7EE, r: 0x4C8FA8, d: 0x71D4EA }, face: [
    'tttttttttt',
    't........t',
    't...aa...t',
    't..ahaa..t',
    't.aaaaha.t',
    't.ahaaaa.t',
    't.gggggg.t',
    't...ss...t',
    't...ss...t',
    't...ss...t',
    't..rrrr..t',
    't.rrrrrr.t',
    't........t',
    't........t',
    't........t',
    'tddddddddt'] },
  forest: { sky: [0x23463A, 0x18251F, 0x0F1813], trim: 0x92D1AE, colors: { m: 0xE9F1D6, a: 0x5FA57F, h: 0x92D1AE, k: 0x6B4A33, g: 0x3E7A57, l: 0xC4D5A0 }, face: [
    'tttttttttt',
    't......m.t',
    't...hh...t',
    't...aa...t',
    't..haah..t',
    't..aaaa..t',
    't.haaaah.t',
    't..aaaa..t',
    't.haaaah.t',
    't.aaaaaa.t',
    't...kk...t',
    't...kk...t',
    'tggggggggt',
    't.g.gg.g.t',
    't........t',
    'tllllllllt'] },
  sunset: { sky: [0x2A1638, 0x7A2E5C, 0xD99279, 0xE7B077], trim: 0xE7B077, colors: { s: 0xFFE3A3, w: 0x241A2E, r: 0xFFC27A, p: 0x5A2E52 }, face: [
    'tttttttttt',
    't........t',
    't........t',
    't........t',
    't...ss...t',
    't..ssss..t',
    't.ssssss.t',
    't........t',
    't.ssssss.t',
    't........t',
    'tsssssssst',
    'twwwwwwwwt',
    'twrrrrrrwt',
    'twwrrrrwwt',
    'twwwrrwwwt',
    'tppppppppt'] },
  void: { sky: [0x07060D, 0x12101B, 0x2B2050], trim: 0xBDA5EC, colors: { '*': 0xDED0F9, ',': 0x5A4B86, a: 0x8E74D6, h: 0xF3EDFF, c: 0xBDA5EC }, face: [
    'tttttttttt',
    't.......,t',
    't..*.....t',
    't,.......t',
    't..aaaa..t',
    't.a.cc.a.t',
    't.acchca.t',
    't.achcca.t',
    't.a.cc.a.t',
    't..aaaa..t',
    't........t',
    't.,......t',
    't......*.t',
    't........t',
    't...,....t',
    'tcccccccct'] },
};

await mkdir(directory,{recursive:true});
for(const [id,{sky,trim,colors,face}] of Object.entries(presets)){
  if(face.length!==16||face.some(row=>row.length!==10)) throw new Error(`${id} front face must be 10x16`);
  const rows=Buffer.alloc(32*(1+64*4));
  const pixel=(x,y,color)=>{const at=y*257+1+x*4;rows[at]=color[0];rows[at+1]=color[1];rows[at+2]=color[2];rows[at+3]=255;};
  const trimColor=mix(rgb(trim),[12,12,18],.45);
  // Standard 10x16x1 cape cube: edges at u 0/11 and v 0, outward face at (1,1), lining at (12,1).
  for(let y=0;y<16;y++)for(let u=0;u<10;u++){
    const key=face[y][u];
    const background=gradient(sky,y/15);
    pixel(u+1,y+1,key==='.'?background:key==='t'?trimColor:rgb(colors[key]??(()=>{throw new Error(`${id}: unknown key ${key}`);})()));
    pixel(u+12,y+1,mix(background,[0,0,0],.35));
  }
  for(let u=1;u<=20;u++)pixel(u,0,trimColor);
  for(let y=1;y<=16;y++){pixel(0,y,trimColor);pixel(11,y,trimColor);}
  // Elytra uses the cape texture when the skin has none: 10x20x2 wing cube at (22,0), faces at (24,2) and (36,2).
  for(let y=0;y<22;y++)for(let x=22;x<46;x++){
    const fx=x<36?x-24:x-36,fy=y-2,inside=fx>=0&&fx<10&&fy>=0&&fy<20;
    pixel(x,y,!inside||fx===0||fy===0||fy===19?trimColor:gradient(sky,fy/19));
  }
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(64);ihdr.writeUInt32BE(32,4);ihdr[8]=8;ihdr[9]=6;
  await writeFile(new URL(`${id}.png`,directory),Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]));
}
console.log('Generated four standard-layout 64x32 RGBA cape presets.');

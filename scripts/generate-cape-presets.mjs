import { deflateSync } from 'node:zlib';
import { mkdir, writeFile } from 'node:fs/promises';
const directory = new URL('../src/main/resources/assets/skymyce/textures/capes/', import.meta.url);
const crc = bytes => { let value=0xffffffff; for(const byte of bytes){value^=byte; for(let i=0;i<8;i++)value=(value>>>1)^((value&1)?0xedb88320:0);} return (value^0xffffffff)>>>0; };
const chunk=(type,data)=>{const name=Buffer.from(type),out=Buffer.alloc(data.length+12);out.writeUInt32BE(data.length);name.copy(out,4);data.copy(out,8);out.writeUInt32BE(crc(Buffer.concat([name,data])),data.length+8);return out;};
await mkdir(directory,{recursive:true});
const themes={ 'sky-myce':[[19,29,51],[78,220,255],[153,97,241]],forest:[[12,35,30],[98,226,160],[40,138,110]],sunset:[[47,22,45],[255,167,95],[230,88,140]],void:[[23,18,42],[176,143,255],[79,76,166]] };
for(const [id,[base,accent,secondary]] of Object.entries(themes)){
  const rows=Buffer.alloc(32*(1+64*4));
  const pixel=(x,y,color)=>{const at=y*257+1+x*4;rows[at]=color[0];rows[at+1]=color[1];rows[at+2]=color[2];rows[at+3]=255;};
  // Standard 10x16x1 cube UVs in a 64x32 Minecraft cape; unreferenced area stays transparent.
  for(let y=0;y<18;y++)for(let x=0;x<22;x++){
    const front=x>=1&&x<=10&&y>=1&&y<=16,back=x>=12&&x<=21&&y>=1&&y<=16;
    const edge=x===0||x===11||y===0||y===17;
    if(front||back||edge){const u=back?x-12:x-1; let color=base;
      if(edge||u===0||u===9||y===1||y===16)color=secondary;
      if((y===5&&u>=2&&u<=7)||(y>=6&&y<=11&&(u===2||u===7))||(y===12&&u>=2&&u<=7))color=accent;
      if(y===8&&u>=4&&u<=5)color=secondary;
      pixel(x,y,color);
    }
  }
  const ihdr=Buffer.alloc(13);ihdr.writeUInt32BE(64);ihdr.writeUInt32BE(32,4);ihdr[8]=8;ihdr[9]=6;
  await writeFile(new URL(`${id}.png`,directory),Buffer.concat([Buffer.from([137,80,78,71,13,10,26,10]),chunk('IHDR',ihdr),chunk('IDAT',deflateSync(rows)),chunk('IEND',Buffer.alloc(0))]));
}
console.log('Generated four standard-layout 64x32 RGBA cape presets.');

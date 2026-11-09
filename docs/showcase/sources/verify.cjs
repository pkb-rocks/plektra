#!/usr/bin/env node
const fs=require('fs'),path=require('path'),assert=require('assert');
const sharp=require('sharp');
const root=path.resolve(__dirname,'..');
const {createHash}=require('node:crypto');
(async()=>{
 const expected=JSON.parse(fs.readFileSync(path.join(root,'checksums.json')));
 for(const [file,hash] of Object.entries(expected)){
  assert.equal(createHash('sha256').update(fs.readFileSync(path.join(root,file))).digest('hex'),hash,'Changed canonical file: '+file);
 }
 const assets=JSON.parse(fs.readFileSync(path.join(root,'assets.json'))),checks=[];
 const compositionHash=createHash('sha256');
 for(const file of ['raw/titan-2-elite/hero-full.png','raw/titan-2-elite/r6-full-ime.png','raw/titan-2-elite/r6-full.png','sources/render.cjs'])compositionHash.update(fs.readFileSync(path.join(root,file)));
 const compositionStamp=compositionHash.digest('hex').slice(0,10);
 const eliteScreen=await sharp(path.join(root,'composed/titan-2-elite-hero-full-screen-'+compositionStamp+'.png')).raw().toBuffer();
 const clicksScreen=await sharp(path.join(root,'composed/clicks-communicator-screen.png')).raw().toBuffer();
 assert.deepEqual(clicksScreen,eliteScreen,'Communicator must use the identical original T2E screen composition');
 checks.push({capture:'clicks-communicator',identical_to_original_T2E_composition:true});
 for(const channel of ['stable','nightly'])for(const format of ['landscape','portrait']){
  const dir=path.join(root,'exports',channel,format),files=fs.readdirSync(dir).filter(n=>n.endsWith('.png'));assert.equal(files.length,21);
  for(const file of files){const p=path.join(dir,file),m=await sharp(p).metadata();assert.equal(m.width,format==='landscape'?1920:1080);assert.equal(m.height,format==='landscape'?1080:1920);const rgb=await sharp(p).extract({left:0,top:0,width:1,height:1}).removeAlpha().raw().toBuffer();assert.deepEqual([...rgb],channel==='nightly'?[207,221,231]:[244,241,233]);}
 }
 for(const channel of ['stable','nightly']){
  const files=fs.readdirSync(path.join(root,'exports',channel,'visuals')).filter(f=>f.endsWith('.png'));assert.equal(files.length,21);
  for(const file of files){const m=await sharp(path.join(root,'exports',channel,'visuals',file)).metadata();assert.equal(m.width,1100);assert.equal(m.height,file==='16-software-keyboard.png'?600:830);}
 }
 for(const name of ['r6-config-full','r5-config-suggestions','r5-config-leds','r5-slim']){
  const cropFile=assets.find(p=>p.includes('/titan-2-elite-'+name+'-bar-')),
   crop=await sharp(path.join(root,cropFile)).ensureAlpha().raw().toBuffer({resolveWithObject:true}),
   raw=await sharp(path.join(root,'raw/titan-2-elite',name+'.png')).extract({left:0,top:1200-crop.info.height,width:1076,height:crop.info.height}).ensureAlpha().raw().toBuffer(),
   reference=await sharp(path.join(root,'raw/titan-2-elite/r5-config-hidden.png')).extract({left:0,top:1200-crop.info.height,width:1076,height:crop.info.height}).ensureAlpha().raw().toBuffer();
  const nativeFile=path.join(root,'raw/titan-2-elite',name+'-ime.png');
  if(fs.existsSync(nativeFile)){
   const native=await sharp(nativeFile).ensureAlpha().raw().toBuffer({resolveWithObject:true});
   assert.equal(crop.info.height,native.info.height+6);
   assert.equal(crop.info.width,native.info.width);
   assert.deepEqual(crop.data.subarray(1076*6*4),native.data,'Native export pixels changed: '+name);
   let visible=0,transparent=0;
   for(let i=3;i<native.data.length;i+=4){if(native.data[i]>0)visible++;else transparent++;}
   assert(visible>100&&transparent>0);checks.push({capture:name,native_alpha_pixels_retained:visible,transparent_pixels:transparent});
   continue;
  }
  let foreground=0,hidden=0;
  const corners=[raw.length-4,raw.length-1076*4];
  for(let i=0;i<raw.length;i+=4){const difference=Math.max(...[0,1,2].map(c=>Math.abs(raw[i+c]-reference[i+c]))),background=corners.some(j=>[0,1,2].every(c=>Math.abs(raw[i+c]-raw[j+c])<=1));if(difference>1&&!background){assert.equal(crop.data[i+3],255);[0,1,2].forEach(c=>assert.equal(crop.data[i+c],raw[i+c]));foreground++;}else if(crop.data[i+3]===0)hidden++;}
  assert(hidden>0&&foreground>0);checks.push({capture:name,foreground_pixels_retained:foreground,background_pixels_transparent:hidden});
 }
 for(const channel of ['stable','nightly'])for(const format of ['landscape','portrait']){
  const svg=fs.readFileSync(path.join(root,'exports',channel,format,'01-devices.svg'),'utf8');
  const devices=[...svg.matchAll(/<g data-device="([^"]+)" data-width-mm="([^"]+)" data-visible-width="([^"]+)" data-bar-y="([^"]+)" data-label-y="([^"]+)">/g)].map(m=>({id:m[1],mm:+m[2],width:+m[3],bar:+m[4],label:+m[5]}));
  assert.equal(devices.length,4);
  const [titan,elite,clicks,pixel]=devices;
  assert.equal(clicks.id,'clicks-communicator');
  assert(Math.abs(clicks.width/elite.width-78.6/75)<1e-8);
  assert(svg.includes('>Upcoming</text>'));
  assert(titan.width>elite.width);
  assert(Math.abs(titan.width/elite.width-88.7/75)<1e-8);
  assert(Math.abs(pixel.width/elite.width-72.9/75)<1e-8);
  assert(devices.every(d=>Math.abs(d.bar-titan.bar)<1e-8&&Math.abs(d.label-titan.label)<1e-8));
 }
 for(const channel of ['stable','nightly']){
  const svg=fs.readFileSync(path.join(root,'exports',channel,'landscape','16-software-keyboard.svg'),'utf8');
  const phones=[...svg.matchAll(/<image [^>]* x="([^"]+)" y="([^"]+)" width="([^"]+)" height="([^"]+)"/g)]
   .map(m=>({x:+m[1],y:+m[2],width:+m[3],height:+m[4]})).filter(m=>m.x>=794&&m.width>100);
  assert.equal(phones.length,4,'Four theme devices must be present');
  assert(phones.every(p=>Math.abs(p.y-phones[0].y)<.01),'Theme devices must share one row');
  assert(phones.every((p,i)=>!i||p.x>=phones[i-1].x+phones[i-1].width),'Theme devices overlap');
  assert(phones.every(p=>p.x+p.width<=1842&&p.y+p.height<=938),'Theme devices exceed their visual area');
 }
 const mask=await sharp(path.join(root,'frames/titan-2-elite-screen-mask.png')).ensureAlpha().raw().toBuffer();
 for(const [name,colors] of [['r6-full',[[48,48,48]]],['r5-config-leds',[[48,48,48],[100,150,255]]]]){
  const raw=await sharp(path.join(root,'raw/titan-2-elite',name+'.png')).ensureAlpha().raw().toBuffer();let led=0,clipped=0;
  for(let y=1070;y<1200;y++)for(let x=0;x<1076;x++)if(x<155||x>920){const i=(y*1076+x)*4;if(colors.some(rgb=>rgb.every((c,j)=>raw[i+j]===c))){led++;const u=Math.round(100+x*1350/1075),v=Math.round(90+y*1500/1199);if(mask[(v*1550+u)*4+3]<240)clipped++;}}
  assert(led>100);assert.equal(clipped,0);checks.push({capture:name,native_LED_pixels_inside_photographic_opening:led,clipped});
 }
 fs.writeFileSync(path.join(root,'validation.json'),JSON.stringify({cards:84,dimensions_and_channel_backgrounds:true,protected_pixels:checks},null,2)+'\n');console.log(JSON.stringify(checks));
})().catch(e=>{console.error(e);process.exit(1)});

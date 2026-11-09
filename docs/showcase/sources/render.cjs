#!/usr/bin/env node
const fs=require('fs'), path=require('path'), {execFileSync}=require('child_process');
const sharp=require('sharp');
const root=path.resolve(__dirname,'..'), repo=path.resolve(root,'../..');
process.env.PLEKTRA_FRAMES_DIR=path.join(root,'frames');
const catalog=JSON.parse(fs.readFileSync(path.join(__dirname,'catalog.json')));
const deviceDimensions=JSON.parse(fs.readFileSync(path.join(__dirname,'device-dimensions.json')));
const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));
const uri=p=>'data:image/'+(p.endsWith('.svg')?'svg+xml':'png')+';base64,'+fs.readFileSync(p).toString('base64');
const text=(s,x,y,size=28,color='#555A60',weight=400,anchor='start')=>`<text x="${x}" y="${y}" font-family="Arial, sans-serif" font-size="${size}" font-weight="${weight}" fill="${color}" text-anchor="${anchor}">${esc(s)}</text>`;
const image=(p,x,y,w,h)=>`<image href="${uri(p)}" x="${x}" y="${y}" width="${w}" height="${h}" preserveAspectRatio="xMidYMid meet"/>`;
const metas={},assets=new Set();
const digest=(...paths)=>{const hash=require('crypto').createHash('sha256');paths.forEach(p=>hash.update(fs.readFileSync(p)));return hash.digest('hex').slice(0,10);};
async function wrapText(lines,size,maxWidth,bold=false){
 const out=[];
 for(const line of lines){
  let current='';
  for(const word of line.split(' ')){
   const candidate=current?current+' '+word:word;
   const m=await sharp({text:{text:esc(candidate),font:`Arial ${bold?'Bold ':''}${size}`,rgba:true,dpi:72}}).metadata();
   if(current&&m.width>maxWidth){out.push(current);current=word;}else current=candidate;
  }
  out.push(current);
 }
 return out;
}
async function dimensions(p){return metas[p]||(metas[p]=await sharp(p).metadata());}
async function fit(p,x,y,w,h){let m=await dimensions(p),scale=Math.min(w/m.width,h/m.height);let rw=m.width*scale,rh=m.height*scale;return image(p,x+(w-rw)/2,y+(h-rh)/2,rw,rh);}
function raw(item){return path.join(root,'raw',item[3]?.captureDevice||item[0],item[1]+'.png');}
async function presentedScreen(item){
 const nativeState=item[3]?.nativeKeyboard;
 if(!nativeState)return raw(item);
 const native=path.join(root,'raw',item[3]?.captureDevice||item[0],nativeState+'-ime.png');
 const reference=path.join(root,'raw',item[3]?.captureDevice||item[0],nativeState+'.png');
 const source=raw(item),stamp=digest(source,native,reference,__filename);
 const out=path.join(root,'composed',item[0]+'-'+item[1]+'-screen-'+stamp+'.png');
 if(!fs.existsSync(out)){
  const shot=await sharp(source).ensureAlpha().raw().toBuffer({resolveWithObject:true});
  const {width,height}=shot.info,m=await dimensions(native);
  const paperOffset=(Math.floor(height*.5)*width+Math.floor(width*.5))*4;
  const paper={r:shot.data[paperOffset],g:shot.data[paperOffset+1],b:shot.data[paperOffset+2],alpha:1};
  // Preserve the native editor above its blank lower half, then place the recorded device IME.
  const backdrop=await sharp(source).extract({left:0,top:0,width,height:Math.floor(height*.5)})
   .extend({top:0,bottom:height-Math.floor(height*.5),left:0,right:0,background:paper}).png().toBuffer();
  const status=await sharp(reference).extract({left:0,top:0,width,height:104}).png().toBuffer();
  await sharp(backdrop).composite([{input:status,left:0,top:0},{input:native,left:0,top:height-m.height}]).png().toFile(out);
 }
 return out;
}
async function frame(item){const source=await presentedScreen(item);const stamp=digest(source,path.join(process.env.PLEKTRA_FRAMES_DIR,item[0]+'-frame.png'),path.join(process.env.PLEKTRA_FRAMES_DIR,item[0]+'-screen-mask.png'),path.join(process.env.PLEKTRA_FRAMES_DIR,item[0]+'-geometry.json'));const out=path.join(root,'composed',item[0]+'-'+item[1]+'-'+stamp+'.png');assets.add(out);if(!fs.existsSync(out))execFileSync(process.execPath,[path.join(__dirname,'compose-screen.cjs'),item[0],source,out]);return out;}
// Titan 2 Elite bar crops. The IME top is measured per capture (bar, contour and LED strokes), not fixed.
// Background is removed by one rule: pixels that match the app background, and that are connected to the
// crop border, become transparent. Matching pixels enclosed by the bar (keys, panel gaps behind keys) stay.
// App background: pixel-identical Notes grid of r5-config-hidden.png when the capture shares it above the bar,
// otherwise the flat app colour of the same screenshot plus its corner colours (outside the rounded display).
const TITAN_REF=path.join(root,'raw/titan-2-elite/r5-config-hidden.png');
const BAR_SEARCH=420,BAR_MARGIN=6;
let titanReference;
async function rgba(p){const {data,info}=await sharp(p).ensureAlpha().raw().toBuffer({resolveWithObject:true});return {data,width:info.width,height:info.height};}
const sameRGB=(a,i,b,j)=>Math.abs(a[i]-b[j])<=1&&Math.abs(a[i+1]-b[j+1])<=1&&Math.abs(a[i+2]-b[j+2])<=1;
async function titanBarCrop(src,out){
 titanReference??=await rgba(TITAN_REF);
 const img=await rgba(src),ref=titanReference,{width:W,height:H}=img,d=img.data,r=ref.data,winTop=H-BAR_SEARCH;
 let gridMatches=true;
 for(let i=winTop*W*4;i<(winTop+200)*W*4&&gridMatches;i+=4)gridMatches=sameRGB(d,i,r,i);
 let flat=[];
 if(!gridMatches){
  const counts=new Map();
  for(let y=500;y<700;y+=2)for(let x=0;x<W;x+=2){const i=(y*W+x)*4,k=[d[i],d[i+1],d[i+2]].join(',');counts.set(k,(counts.get(k)||0)+1);}
  const app=[...counts.entries()].sort((a,b)=>b[1]-a[1])[0][0].split(',').map(Number);
  const corner=x=>{const i=((H-1)*W+x)*4;return [d[i],d[i+1],d[i+2]];};
  flat=[app,corner(0),corner(W-1)];
 }
 const cornerColors=[0,W-1].map(x=>{const i=((H-1)*W+x)*4;return [d[i],d[i+1],d[i+2]];});
 const isBg=i=>(gridMatches?sameRGB(d,i,r,i):flat.some(c=>Math.abs(d[i]-c[0])<=1&&Math.abs(d[i+1]-c[1])<=1&&Math.abs(d[i+2]-c[2])<=1))||
   cornerColors.some(c=>Math.abs(d[i]-c[0])<=1&&Math.abs(d[i+1]-c[1])<=1&&Math.abs(d[i+2]-c[2])<=1);
 let top=-1;
 for(let y=winTop;y<H&&top<0;y++)for(let x=0;x<W;x++)if(!isBg((y*W+x)*4)){top=y;break;}
 if(top<0)throw new Error('No IME content found in '+src);
 const y0=Math.max(0,top-BAR_MARGIN),h=H-y0,seen=new Uint8Array(W*h),stack=[];
 const seed=(x,y)=>{if(x<0||y<y0||x>=W||y>=H)return;const k=(y-y0)*W+x;if(!seen[k]&&isBg((y*W+x)*4)){seen[k]=1;stack.push(k);}};
 for(let x=0;x<W;x++){seed(x,y0);seed(x,H-1);}
 for(let y=y0;y<H;y++){seed(0,y);seed(W-1,y);}
 while(stack.length){const k=stack.pop(),x=k%W,y=(k-x)/W+y0;seed(x+1,y);seed(x-1,y);seed(x,y+1);seed(x,y-1);}
 const px=Buffer.alloc(W*h*4);
 for(let k=0;k<W*h;k++){const si=((k/W|0)+y0)*W*4+(k%W)*4;px[k*4]=d[si];px[k*4+1]=d[si+1];px[k*4+2]=d[si+2];px[k*4+3]=seen[k]?0:255;}
 await sharp(px,{raw:{width:W,height:h,channels:4}}).png().toFile(out);
 return out;
}
async function crop(item,mode='bar'){
 const nativeFile=path.join(root,'raw',item[0],item[1]+'-ime.png');
 const stamp=digest(raw(item),...(fs.existsSync(nativeFile)?[nativeFile]:[]),__filename,path.join(root,'raw/titan-2-elite/r5-config-hidden.png'));const out=path.join(root,'crops',item[0]+'-'+item[1]+'-'+mode+'-polished-'+stamp+'.png');assets.add(out);if(fs.existsSync(out))return out;
 const native=path.join(root,'raw',item[0],item[1]+'-ime.png');
 if(fs.existsSync(native)&&(mode==='bar'||mode==='modifier')){
   await sharp(native).extend({top:BAR_MARGIN,bottom:0,left:0,right:0,background:'#00000000'}).png().toFile(out);
   return out;
 }
 if(item[0]==='titan-2-elite'&&(mode==='bar'||mode==='modifier'))return titanBarCrop(raw(item),out);
 const src=raw(item),m=await dimensions(src),pixel=item[0]==='pixel-7a';let top,height;
 if(mode==='snippet-result'){
  top=340;height=item[1].endsWith('address')?130:78;await sharp(src).extract({left:45,top,width:m.width-90,height}).png().toFile(out);return out;
 }
 if(mode==='snippet'){
  top=996;height=81;await sharp(src).extract({left:Math.round(m.width/3),top,width:Math.floor(m.width/3),height}).png().toFile(out);return out;
 }
 if(mode==='context'){top=Math.round(m.height*(pixel?.10:.16));height=Math.round(m.height*(pixel?.22:.26));}
 else if(mode==='layout'){top=1672;height=448;}
 else if(mode==='modifier'){height=Math.round(m.height*.10);top=m.height-height;}
 else if(pixel && item[1].startsWith('clipboard')){top=1560;height=288;}
 else if(pixel){top=1372;height=m.height-top;}
 else{height=Math.round(m.height*(item[1].includes('symbols')?.35:.19));top=m.height-height;}
 await sharp(src).extract({left:0,top,width:m.width,height}).png().toFile(out);return out;
}
async function deviceComparison(items,box,portrait){
 const [x,y,w,h]=box,gap=portrait?28:32,labelSize=portrait?25:26,labelGap=16;
 const devices=[];
 for(const item of items){
  const id=item[0],p=await frame(item),m=await dimensions(p),geometry=JSON.parse(fs.readFileSync(path.join(root,'frames',id+'-geometry.json')));
  const trimmed=await sharp(path.join(root,'frames',id+'-frame.png')).trim({background:'#00000000'}).toBuffer({resolveWithObject:true});
  const bounds={x:-trimmed.info.trimOffsetLeft,y:-trimmed.info.trimOffsetTop,width:trimmed.info.width,height:trimmed.info.height};
  const capture=await rgba(await presentedScreen(item)),W=capture.width,H=capture.height,d=capture.data,center=(Math.floor(H*.5)*W+Math.floor(W*.5))*4;
  const bg=[d[center],d[center+1],d[center+2]];
  let barTop=-1;
  if(id==='titan-2-elite'||id==='clicks-communicator'){
   const bar=await dimensions(await crop(item[3]?.nativeKeyboard?[item[3].captureDevice||item[0],item[3].nativeKeyboard,item[2]]:item));
   barTop=H-bar.height+BAR_MARGIN;
  }
  for(let row=Math.floor(H*.5);barTop<0&&row<H;row++){
   let changed=0;
   for(let column=0;column<W;column++){const i=(row*W+column)*4;if(bg.some((v,j)=>Math.abs(d[i+j]-v)>12))changed++;}
   if(changed/W>.65){barTop=row;break;}
  }
  if(barTop<0)throw new Error('No keyboard bar edge found for '+id);
  const screen=geometry.screen.bounds,anchor=screen.y+barTop/(H-1)*screen.height;
  const mmPerPixel=deviceDimensions[id].width_mm/bounds.width;
  devices.push({item,p,m,bounds,anchor,mmPerPixel,width:deviceDimensions[id].width_mm,above:(anchor-bounds.y)*mmPerPixel,below:(bounds.y+bounds.height-anchor)*mmPerPixel});
 }
 const above=Math.max(...devices.map(d=>d.above)),below=Math.max(...devices.map(d=>d.below));
 const scale=Math.min((w-gap*(devices.length-1))/devices.reduce((sum,d)=>sum+d.width,0),(h-labelGap-labelSize)/(above+below));
 const groupWidth=devices.reduce((sum,d)=>sum+d.width*scale,0)+gap*(devices.length-1);
 const anchorY=y+(h-(above+below)*scale-labelGap-labelSize)/2+above*scale;
 const labelY=anchorY+below*scale+labelGap+labelSize*.8;
 let xx=x+(w-groupWidth)/2,out='';
 for(const device of devices){
  const {item,p,m,bounds,anchor,mmPerPixel,width}=device,s=scale*mmPerPixel;
  out+=`<g data-device="${item[0]}" data-width-mm="${width}" data-visible-width="${width*scale}" data-bar-y="${anchorY}" data-label-y="${labelY}">`;
  out+=image(p,xx-bounds.x*s,anchorY-anchor*s,m.width*s,m.height*s);
  out+=text(item[2],xx+width*scale/2,labelY,labelSize,'#565B60',400,'middle');
  if(item[3]?.upcoming)out+=text('Upcoming',xx+width*scale/2,labelY+labelSize*1.25,labelSize*.72,'#565B60',400,'middle');
  out+='</g>';
  xx+=width*scale+gap;
 }
 return out;
}
async function contents(card,box,portrait){
 let [x,y,w,h]=box, out='';
 if(card.layout==='devices'){
   out+=await deviceComparison(card.items,box,portrait);
 }else if(card.layout==='theme-gallery'){
   const gap=24,cell=(w-gap*(card.items.length-1))/card.items.length;
   const frames=await Promise.all(card.items.map(frame));
   const sizes=await Promise.all(frames.map(dimensions));
   const scale=Math.min(...sizes.map(m=>cell/m.width),...sizes.map(m=>(h-48)/m.height));
   const height=Math.max(...sizes.map(m=>m.height*scale));
   const top=y+(h-height-48)/2;
   for(let i=0;i<frames.length;i++){
     const m=sizes[i],xx=x+i*(cell+gap)+(cell-m.width*scale)/2;
     out+=image(frames[i],xx,top,m.width*scale,m.height*scale);
     out+=text(card.items[i][2],x+i*(cell+gap)+cell/2,top+height+34,23,'#565B60',400,'middle');
   }
 }else if(card.layout==='pair'){
   const n=card.items.length,gap=portrait?28:32,cell=(w-gap*(n-1))/n;
   for(let i=0;i<n;i++){const p=await frame(card.items[i]);out+=await fit(p,x+i*(cell+gap),y,cell,h-55);out+=text(card.items[i][2],x+i*(cell+gap)+cell/2,y+h-12,portrait?25:26,'#565B60',400,'middle');}
 }else if(card.layout==='frame-crop'){
   const item=card.items[0],label=card.title.join(' ').toLowerCase()===item[2].toLowerCase()?'':item[2],p=await frame(item),cp=await crop(item,card.id==='08-snippet-selection'?'snippet':card.id==='13-selection'?'context':'bar');
   if(portrait){out+=await fit(p,x+110,y,w-220,h*.64);out+=text(label,x,y+h*.71,27);out+=await fit(cp,x,y+h*.73,w,h*.27);}
   else{out+=await fit(p,x,y,w*.44,h);let cm=await dimensions(cp),ih=Math.min(h*.58,w*.51*cm.height/cm.width),iy=y+(h-ih)/2;out+=text(label,x+w*.49,iy-28,26);out+=await fit(cp,x+w*.49,iy,w*.51,ih);}
 }else if(card.layout==='bar-configs'){
   // Every row shares one width and scale, the screen bottom as bottom edge, and the same gap below each row.
   const shots=[];for(const item of card.items)shots.push(item[1].endsWith('hidden')?null:await crop(item));
   const total=(await Promise.all(shots.filter(Boolean).map(async p=>(await dimensions(p)).height))).reduce((s,v)=>s+v,0);
   const iw=Math.min(w,(h-4*74-30)*1076/total),scale=iw/1076;let yy=y;
   for(const [k,item] of card.items.entries()){out+=text(item[2],x,yy+27,27);yy+=44;if(!shots[k]){out+=text('Bar hidden',x,yy+29,30,'#565B60');yy+=58;}else{const cp=shots[k],cm=await dimensions(cp);out+=image(cp,x,yy,iw,cm.height*scale);yy+=cm.height*scale+25;}}
 }else if(card.layout==='snippets'){
   let yy=y+25;for(const item of card.items){out+=text(item[2],x,yy+27,27);yy+=50;const cp=await crop(item,'snippet-result'),cm=await dimensions(cp),iw=w,ih=iw*cm.height/cm.width;out+=image(cp,x,yy,iw,ih);yy+=ih+35;}
 }else if(card.layout==='compare-layouts'){
   const gap=60,ch=(h-gap)/2;for(let i=0;i<card.items.length;i++){const cp=await crop(card.items[i],'layout');out+=text(card.items[i][2],x,y+i*(ch+gap)+28,28);out+=await fit(cp,x,y+i*(ch+gap)+48,w,ch-48);}
 }else if(card.layout==='compare'){
   const files=await Promise.all(card.items.map(i=>crop(i,card.id==='14-modifiers'?'modifier':'bar')));const ratio=await Promise.all(files.map(async p=>{let m=await dimensions(p);return m.height/m.width;}));
   const total=ratio.reduce((s,r)=>s+w*r,0)+card.items.length*42+(card.items.length-1)*24;
   const scale=Math.min(1,h/total),iw=w*scale;let yy=y+(h-total*scale)/2;
   for(let i=0;i<files.length;i++){out+=text(card.items[i][2],x,yy+28*scale,portrait?28:27);yy+=42*scale;let ih=iw*ratio[i];out+=image(files[i],x+(w-iw)/2,yy,iw,ih);yy+=ih+24*scale;}
 }else if(card.layout==='gallery'){
   const n=card.items.length,cols=n>6?3:2,rows=Math.ceil(n/cols),gap=24,cw=(w-gap*(cols-1))/cols,ch=Math.min((h-gap*(rows-1))/rows,portrait?315:260);
   let yy=y+(h-(ch*rows+gap*(rows-1)))/2;
   for(let i=0;i<n;i++){const p=await crop(card.items[i],card.id==='17-layouts'?'layout':'bar'),xx=x+(i%cols)*(cw+gap),ty=yy+Math.floor(i/cols)*(ch+gap);out+=text(card.items[i][2],xx,ty+27,portrait?25:26);out+=await fit(p,xx,ty+44,cw,ch-44);}
 }
 return out;
}
(async()=>{
 for(const folder of ['composed','crops','exports','previews'])fs.mkdirSync(path.join(root,folder),{recursive:true});
 await require('./render-clicks-preview.cjs')();
 for(const channel of ['stable','nightly']){
   const accent=channel==='stable'?'#FC7A0F':'#2F80ED';const logo=path.join(repo,'docs/branding',channel==='stable'?'plektra-open-color.svg':'plektra-nightly-open-color.svg');
   for(const format of ['landscape','portrait']){
     const portrait=format==='portrait',W=portrait?1080:1920,H=portrait?1920:1080,dir=path.join(root,'exports',channel,format);fs.mkdirSync(dir,{recursive:true});
     for(const card of catalog){
       let body=`<rect width="${W}" height="${H}" fill="${channel==='nightly'?'#CFDDE7':'#F4F1E9'}"/>`;
       body+=image(logo,portrait?72:76,portrait?58:48,58,58)+text(channel==='stable'?'Plektra':'Plektra Nightly',portrait?148:152,portrait?99:89,31,'#292B2C',600);
       let titleY=portrait?211:296,titleSize=portrait?78:66;
       const titles=await wrapText(card.title,titleSize,portrait?936:668,true);
       const bodyLines=await wrapText(card.body,portrait?29:28,portrait?936:668);
       titles.forEach((s,i)=>body+=text(s,portrait?72:76,titleY+i*(portrait?88:78),titleSize,'#242729',700));
       let by=titleY+(titles.length-1)*(portrait?88:78)+(portrait?63:69);
       bodyLines.forEach((s,i)=>body+=text(s,portrait?72:76,by+i*43,portrait?29:28,'#535960'));
       const bodyEnd=by+(bodyLines.length-1)*43;
       body+=`<rect x="${portrait?72:76}" y="${bodyEnd+28}" width="64" height="5" fill="${accent}"/>`;
       const box=portrait?[72,bodyEnd+65,936,H-(bodyEnd+65)-175]:[794,148,1048,790];const artwork=await contents(card,box,portrait);body+=artwork;
       if(!portrait){
         const visualDir=path.join(root,'exports',channel,'visuals');fs.mkdirSync(visualDir,{recursive:true});
         const visualY=card.layout==='theme-gallery'?250:130,visualHeight=card.layout==='theme-gallery'?600:830;
         const visual=`<svg xmlns="http://www.w3.org/2000/svg" width="1100" height="${visualHeight}" viewBox="770 ${visualY} 1100 ${visualHeight}"><rect x="770" y="${visualY}" width="1100" height="${visualHeight}" fill="${channel==='nightly'?'#CFDDE7':'#F4F1E9'}"/>${artwork}</svg>`;
         const visualPath=path.join(visualDir,card.id+'.svg');fs.writeFileSync(visualPath,visual);
         execFileSync('rsvg-convert',['-o',path.join(visualDir,card.id+'.png'),visualPath]);
       }
       body+=text('Open source software under GPLv3',portrait?72:76,H-(portrait?94:55),portrait?25:25,'#535960');
       const gx=portrait?72:1460,gy=H-(portrait?65:82);
       body+=image(path.join(__dirname,'github.svg'),gx,gy,30,30)+text('pkb-rocks/plektra',gx+44,gy+25,portrait?27:27,'#292B2C',500);
       const svg=`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}">${body}</svg>`;
       const p=path.join(dir,card.id+'.svg');fs.writeFileSync(p,svg);execFileSync('rsvg-convert',['-o',path.join(dir,card.id+'.png'),p]);
     }
     console.log(channel,format,catalog.length+' cards');
   }
 }
 fs.writeFileSync(path.join(root,'assets.json'),JSON.stringify([...assets].map(p=>path.relative(root,p)),null,2)+'\n');
 // Contact sheets make all cards reviewable together; the full-size files remain separate.
 for(const channel of ['stable','nightly']){
   const tiles=[];for(let i=0;i<catalog.length;i++){let p=path.join(root,'exports',channel,'landscape',catalog[i].id+'.png');tiles.push({input:await sharp(p).resize(640,360).toBuffer(),left:(i%3)*640,top:Math.floor(i/3)*360});}
   await sharp({create:{width:1920,height:Math.ceil(catalog.length/3)*360,channels:3,background:'#F4F1E9'}}).composite(tiles).png().toFile(path.join(root,'previews',channel+'-overview.png'));
 }
})().catch(e=>{console.error(e);process.exit(1)});

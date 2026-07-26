'use strict';
const {serve,installWorld,bootHome,chromium,http}=require('./probe');
const CLIPPED=()=>{const c=[];document.querySelectorAll('body *').forEach(el=>{const s=getComputedStyle(el);
  if(s.display==='none'||s.visibility==='hidden')return;const b=el.getBoundingClientRect();if(b.width<1||b.height<1)return;
  if(/(auto|scroll)/.test(s.overflowX+s.overflowY))return;
  if(!(s.overflowX==='hidden'||s.overflowY==='hidden'||s.overflow==='hidden'))return;
  if(s.textOverflow==='ellipsis')return;
  if(el.scrollWidth-el.clientWidth<=2&&el.scrollHeight-el.clientHeight<=2)return;
  c.push((el.id?'#'+el.id:'')+'.'+(typeof el.className==='string'?el.className.split(' ')[0]:'')+' '+el.clientWidth+'x'+el.clientHeight+'/'+el.scrollWidth+'x'+el.scrollHeight);});return c;};
const CROSS=`(()=>{const clip=el=>{let r=el.getBoundingClientRect(),c=el.parentElement,x0=r.left,y0=r.top,x1=r.right,y1=r.bottom;
  while(c&&c!==document.documentElement){const s=getComputedStyle(c);const cx=/(hidden|auto|scroll|clip)/.test(s.overflowX),cy=/(hidden|auto|scroll|clip)/.test(s.overflowY);
   if(cx||cy){const cr=c.getBoundingClientRect();if(cx){x0=Math.max(x0,cr.left);x1=Math.min(x1,cr.right);}if(cy){y0=Math.max(y0,cr.top);y1=Math.min(y1,cr.bottom);}}c=c.parentElement;}
  return {left:x0,top:y0,right:x1,bottom:y1};};
 const boxes=[];document.querySelectorAll('body *').forEach(el=>{if(el.children.length)return;const t=(el.textContent||'').trim();if(!t)return;
  const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
  const raw=el.getBoundingClientRect();if(raw.width<2||raw.height<2)return;const b=clip(el);if(b.right-b.left<2||b.bottom-b.top<2)return;
  const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');boxes.push({b,t,band:band?(band.id||band.className.split(' ')[0]):null});});
 const seen=new Set();for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){const a=boxes[i],c=boxes[j];if(!a.band||!c.band||a.band===c.band)continue;
  const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left),oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
  if(ox>2&&oy>2)seen.add(a.band+':'+a.t.slice(0,18)+'|'+c.band+':'+c.t.slice(0,18));}return Array.from(seen);})()`;
async function sweep(page){const t=await page.evaluate(()=>{const b=document.getElementById('board');return {sh:b.scrollHeight,ch:b.clientHeight};});
  const all=new Set();for(let y=0;y<=Math.max(0,t.sh-t.ch);y+=Math.max(120,Math.floor(t.ch/3))){await page.evaluate(v=>{document.getElementById('board').scrollTop=v;},y);await page.waitForTimeout(110);(await page.evaluate(CROSS)).forEach(x=>all.add(x));}
  await page.evaluate(()=>{document.getElementById('board').scrollTop=0;});return all.size;}
const BANDS='#univBand .authbookpanel,#sectorBand .authbookpanel,#newsBand .authbookpanel,#chainBand .authbookpanel{min-height:0}';
const MOB='@media(max-width:900px){.lv-book .board{grid-template-rows:max-content;grid-auto-rows:max-content}}';
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const twelve={positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'};
  const four={positions:4,workingIdeas:5,scout:'complete'};
  console.log('--- band-scoped min-height:0 ---');
  for(const [w,h,st,sn] of [[1000,800,four,'four'],[1000,800,twelve,'twelve'],[2000,963,twelve,'twelve'],[1920,1080,twelve,'twelve'],[2560,1440,twelve,'twelve']]){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,st);await bootHome(page,url);
    const c0=await sweep(page),k0=(await page.evaluate(CLIPPED)).length;
    await page.addStyleTag({content:BANDS});await page.waitForTimeout(400);
    const c1=await sweep(page),k1=await page.evaluate(CLIPPED);
    console.log(`${w}x${h}/${sn}: cross ${c0}->${c1}  clipped ${k0}->${k1.length} ${k1.slice(0,2).join(' ; ')}`);
    await ctx.close();
  }
  console.log('--- mobile max-content alone ---');
  for(const [w,h,st,sn] of [[390,844,twelve,'twelve'],[390,844,four,'four'],[375,812,four,'four'],[320,700,four,'four']]){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,st);await bootHome(page,url);
    const c0=await sweep(page),k0=(await page.evaluate(CLIPPED)).length;
    await page.addStyleTag({content:MOB});await page.waitForTimeout(400);
    const c1=await sweep(page),k1=await page.evaluate(CLIPPED);
    const px=await page.evaluate(()=>({sw:document.documentElement.scrollWidth,cw:document.documentElement.clientWidth}));
    console.log(`${w}x${h}/${sn}: cross ${c0}->${c1}  clipped ${k0}->${k1.length} ${k1.slice(0,2).join(' ; ')} sideways=${px.sw>px.cw+1}`);
    await ctx.close();
  }
  await browser.close();server.close();
})();

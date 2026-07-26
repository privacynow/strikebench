'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
const CROSSFN=`(()=>{
  const clipRect=el=>{let r=el.getBoundingClientRect();let c=el.parentElement;
    let x0=r.left,y0=r.top,x1=r.right,y1=r.bottom;
    while(c&&c!==document.documentElement){const s=getComputedStyle(c);
      const cx=/(hidden|auto|scroll|clip)/.test(s.overflowX),cy=/(hidden|auto|scroll|clip)/.test(s.overflowY);
      if(cx||cy){const cr=c.getBoundingClientRect();
        if(cx){x0=Math.max(x0,cr.left);x1=Math.min(x1,cr.right);}
        if(cy){y0=Math.max(y0,cr.top);y1=Math.min(y1,cr.bottom);}}
      c=c.parentElement;}
    return {left:x0,top:y0,right:x1,bottom:y1};};
  const boxes=[];
  document.querySelectorAll('body *').forEach(el=>{
    if(el.children.length)return;const t=(el.textContent||'').trim();if(!t)return;
    const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
    const raw=el.getBoundingClientRect();if(raw.width<2||raw.height<2)return;
    const b=clipRect(el);if(b.right-b.left<2||b.bottom-b.top<2)return;
    const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');
    boxes.push({b,t,band:band?(band.id||band.className.split(' ')[0]):null});});
  const seen=new Set();
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){
    const a=boxes[i],c=boxes[j];if(!a.band||!c.band||a.band===c.band)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left);
    const oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2)seen.add(a.band+':'+a.t.slice(0,20)+' | '+c.band+':'+c.t.slice(0,20));}
  return Array.from(seen);})()`;
async function sweep(page){
  const total=await page.evaluate(()=>{const b=document.getElementById('board');return {sh:b.scrollHeight,ch:b.clientHeight};});
  const all=new Set();
  for(let y=0;y<=Math.max(0,total.sh-total.ch);y+=Math.max(120,Math.floor(total.ch/3))){
    await page.evaluate(t=>{document.getElementById('board').scrollTop=t;},y);
    await page.waitForTimeout(120);
    (await page.evaluate(CROSSFN)).forEach(x=>all.add(x));
  }
  await page.evaluate(()=>{document.getElementById('board').scrollTop=0;});
  return {n:all.size,ex:Array.from(all).slice(0,5),sh:total.sh,ch:total.ch};
}
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const cases=[
    ['390x844-twelve',390,844,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['390x844-four',390,844,{positions:4,workingIdeas:5,scout:'complete'}],
    ['390x844-empty',390,844,{positions:0,shares:0,workingIdeas:0,scout:'idle'}],
    ['2000x963-twelve',2000,963,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['2000x963-four',2000,963,{positions:4,workingIdeas:5,scout:'complete'}]
  ];
  for(const [name,w,h,st] of cases){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,st);await bootHome(page,url);
    const before=await sweep(page);
    const fix=w<=900?'@media(max-width:900px){.lv-book .board{grid-template-rows:max-content;grid-auto-rows:max-content}}':'.authbookpanel{min-height:0}';
    await page.addStyleTag({content:fix});await page.waitForTimeout(400);
    const after=await sweep(page);
    console.log(`\n${name}: before=${before.n} (board ${before.ch}/${before.sh})  after=${after.n} (board ${after.ch}/${after.sh})`);
    before.ex.forEach(e=>console.log('   B '+e));
    after.ex.forEach(e=>console.log('   A '+e));
    if(w<=900){await page.evaluate(t=>{document.getElementById('board').scrollTop=t;},1900);await page.waitForTimeout(200);
      await page.screenshot({path:path.join(OUT,`mobfix-${name}-1900.png`)});}
    await ctx.close();
  }
  await browser.close();server.close();
})();

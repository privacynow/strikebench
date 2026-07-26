'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
// clip-aware: intersect each leaf rect with every ancestor that clips (overflow hidden/auto/scroll)
const CROSS=()=>{
  const clipRect=el=>{
    let r=el.getBoundingClientRect();let c=el.parentElement;
    let x0=r.left,y0=r.top,x1=r.right,y1=r.bottom;
    while(c&&c!==document.documentElement){
      const s=getComputedStyle(c);
      if(/(hidden|auto|scroll|clip)/.test(s.overflowX)||/(hidden|auto|scroll|clip)/.test(s.overflowY)){
        const cr=c.getBoundingClientRect();
        if(/(hidden|auto|scroll|clip)/.test(s.overflowX)){x0=Math.max(x0,cr.left);x1=Math.min(x1,cr.right);}
        if(/(hidden|auto|scroll|clip)/.test(s.overflowY)){y0=Math.max(y0,cr.top);y1=Math.min(y1,cr.bottom);}
      }
      c=c.parentElement;}
    return {left:x0,top:y0,right:x1,bottom:y1};
  };
  const boxes=[];
  document.querySelectorAll('body *').forEach(el=>{
    if(el.children.length)return;const t=(el.textContent||'').trim();if(!t)return;
    const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
    const raw=el.getBoundingClientRect();if(raw.width<2||raw.height<2)return;
    const b=clipRect(el);if(b.right-b.left<2||b.bottom-b.top<2)return;
    const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');
    boxes.push({b,t,band:band?(band.id||band.className.split(' ')[0]):null});});
  let n=0;const ex=[];
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){
    const a=boxes[i],c=boxes[j];if(!a.band||!c.band||a.band===c.band)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left);
    const oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2){n++;if(ex.length<4)ex.push(`${a.band}:"${a.t.slice(0,22)}" over ${c.band}:"${c.t.slice(0,22)}" (${Math.round(ox)}x${Math.round(oy)}) at y=${Math.round(Math.max(a.b.top,c.b.top))}`);}}
  return {n,ex};
};
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const cases=[
    ['2000x963-twelve',2000,963,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['1920x1080-twelve',1920,1080,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['2560x1440-twelve',2560,1440,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['2000x963-one',2000,963,{positions:1,workingIdeas:5,scout:'idle'}],
    ['390x844-twelve',390,844,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['390x844-four',390,844,{positions:4,workingIdeas:5,scout:'complete'}],
    ['2000x963-four',2000,963,{positions:4,workingIdeas:5,scout:'complete'}]
  ];
  for(const [name,w,h,st] of cases){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,st);await bootHome(page,url);
    const before=await page.evaluate(CROSS);
    await page.screenshot({path:path.join(OUT,`clip-${name}-before.png`)});
    let after=null,afterMobile=null;
    if(w>900){await page.addStyleTag({content:'.authbookpanel{min-height:0}'});await page.waitForTimeout(400);
      after=await page.evaluate(CROSS);await page.screenshot({path:path.join(OUT,`clip-${name}-after.png`)});}
    else {await page.addStyleTag({content:'@media(max-width:900px){.lv-book .board{grid-template-rows:max-content;grid-auto-rows:max-content}}'});await page.waitForTimeout(400);
      afterMobile=await page.evaluate(CROSS);
      const g=await page.evaluate(()=>{const b=document.getElementById('board');return {rows:getComputedStyle(b).gridTemplateRows,sh:b.scrollHeight,ch:b.clientHeight};});
      console.log('   mobile geo after: '+JSON.stringify(g));
      await page.screenshot({path:path.join(OUT,`clip-${name}-after.png`)});}
    console.log(`${name}: before=${before.n} after=${after?after.n:afterMobile.n}`);
    before.ex.forEach(e=>console.log('   B '+e));
    ((after||afterMobile).ex).forEach(e=>console.log('   A '+e));
    await ctx.close();
  }
  await browser.close();server.close();
})();

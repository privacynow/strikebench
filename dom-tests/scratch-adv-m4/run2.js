'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
const CROSS=()=>{
  const boxes=[];
  document.querySelectorAll('body *').forEach(el=>{
    if(el.children.length)return;const t=(el.textContent||'').trim();if(!t)return;
    const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
    const b=el.getBoundingClientRect();if(b.width<2||b.height<2)return;
    const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');
    boxes.push({b,t,band:band?(band.id||band.className.split(' ')[0]):null});});
  let n=0;const ex=[];
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){
    const a=boxes[i],c=boxes[j];if(!a.band||!c.band||a.band===c.band)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left);
    const oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2){n++;if(ex.length<3)ex.push(`${a.band}:"${a.t.slice(0,20)}"/${c.band}:"${c.t.slice(0,20)}"`);}}
  return {n,ex};
};
const G=()=>{
  const bd=document.getElementById('board'),bk=document.querySelector('.book');
  const hd=document.querySelector('.book>.rosterhd'),c0=document.querySelector('.book .card');
  const o={rows:getComputedStyle(bd).gridTemplateRows,areas:getComputedStyle(bd).gridTemplateAreas.slice(0,80),
    gap:getComputedStyle(bd).rowGap,boardOverflow:bd.scrollHeight-bd.clientHeight};
  if(bk)o.book={st:bk.scrollTop,sh:bk.scrollHeight,ch:bk.clientHeight};
  if(hd&&c0)o.covered=Math.round(hd.getBoundingClientRect().bottom-c0.getBoundingClientRect().top);
  const sp=document.querySelector('#sectorBand .authbookpanel');
  if(sp)o.sectorPanel={h:Math.round(sp.getBoundingClientRect().height),band:Math.round(document.getElementById('sectorBand').getBoundingClientRect().height)};
  const w=document.getElementById('homeWatchList'),p=document.getElementById('homePlansList');
  o.watch=w?{h:Math.round(w.getBoundingClientRect().height),sh:w.scrollHeight,ch:w.clientHeight}:null;
  o.plans=p?{h:Math.round(p.getBoundingClientRect().height),sh:p.scrollHeight,ch:p.clientHeight}:null;
  o.reads=[...document.querySelectorAll('.overflowread')].map(e=>e.textContent.trim());
  return o;
};
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const cases=[
    ['2560x1440/twelve',2560,1440,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['2000x963/twelve',2000,963,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['1920x1080/twelve',1920,1080,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['2560x1440/one',2560,1440,{positions:1,workingIdeas:5,scout:'idle'}],
  ];
  for(const [name,w,h,st] of cases){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,st);await bootHome(page,url);
    console.log(`\n## ${name}`);
    console.log('  BEFORE cross='+JSON.stringify(await page.evaluate(CROSS)));
    console.log('  BEFORE geo='+JSON.stringify(await page.evaluate(G)));
    // recovery attempts for snap
    const rec=await page.evaluate(async()=>{const bk=document.querySelector('.book');if(!bk)return null;
      const a=bk.scrollTop;bk.scrollTo({top:0});await new Promise(r=>setTimeout(r,400));const b=bk.scrollTop;
      bk.scrollTop=0;await new Promise(r=>setTimeout(r,400));const c=bk.scrollTop;
      return {initial:a,afterScrollTo:b,afterAssign:c};});
    console.log('  recover='+JSON.stringify(rec));
    // FIX A: min-height:0 on panel
    await page.addStyleTag({content:'.authbookpanel{min-height:0}'});
    await page.waitForTimeout(300);
    console.log('  AFTER minheight0 cross='+JSON.stringify(await page.evaluate(CROSS)));
    console.log('  AFTER minheight0 geo='+JSON.stringify(await page.evaluate(G)));
    // FIX B: scroll-padding-top
    await page.addStyleTag({content:'.lv-book .book{scroll-padding-top:48px}'});
    await page.evaluate(()=>{const bk=document.querySelector('.book');if(bk)bk.scrollTop=0;});
    await page.waitForTimeout(400);
    console.log('  AFTER padtop geo='+JSON.stringify(await page.evaluate(G)));
    // FIX C: 475 -> 0 (no-op test)
    await page.addStyleTag({content:'.lv-book .board{grid-template-rows:minmax(0,auto) minmax(0,auto)}'});
    await page.waitForTimeout(200);
    console.log('  AFTER row0 rows='+(await page.evaluate(()=>getComputedStyle(document.getElementById("board")).gridTemplateRows)));
    await page.screenshot({path:path.join(OUT,`fixed-${name.replace(/[^a-z0-9]+/gi,'-')}.png`)});
    await ctx.close();
  }
  await browser.close();server.close();
})();

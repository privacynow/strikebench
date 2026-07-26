'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
const DETAIL=()=>{
  const chain=el=>{const p=[];let c=el;while(c&&c!==document.body){p.push(c.tagName.toLowerCase()+(c.id?'#'+c.id:'')+(typeof c.className==='string'&&c.className?'.'+c.className.trim().split(/\s+/).slice(0,2).join('.'):''));c=c.parentElement;}return p.join(' < ');};
  const boxes=[];
  document.querySelectorAll('body *').forEach(el=>{
    if(el.children.length)return;const t=(el.textContent||'').trim();if(!t)return;
    const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
    const b=el.getBoundingClientRect();if(b.width<2||b.height<2)return;
    const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');
    boxes.push({el,b,t,band:band?(band.id||band.className.split(' ')[0]):null});});
  const out=[];
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){
    const a=boxes[i],c=boxes[j];if(!a.band||!c.band||a.band===c.band)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left);
    const oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2&&out.length<5)out.push({a:a.t.slice(0,20),ap:chain(a.el),ar:[Math.round(a.b.left),Math.round(a.b.top),Math.round(a.b.right),Math.round(a.b.bottom)],
      b:c.t.slice(0,20),bp:chain(c.el),br:[Math.round(c.b.left),Math.round(c.b.top),Math.round(c.b.right),Math.round(c.b.bottom)]});}
  return out;
};
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const ctx=await browser.newContext({viewport:{width:2000,height:963}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
  await installWorld(page,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'});
  await bootHome(page,url);
  console.log('BEFORE:');console.log(JSON.stringify(await page.evaluate(DETAIL),null,1));
  await page.screenshot({path:path.join(OUT,'collide-2000x963-before.png')});
  await page.addStyleTag({content:'.authbookpanel{min-height:0}'});await page.waitForTimeout(400);
  console.log('AFTER minheight0:');console.log(JSON.stringify(await page.evaluate(DETAIL),null,1));
  await page.screenshot({path:path.join(OUT,'collide-2000x963-after.png')});
  // measure the sectorBand subtree overflow
  console.log(await page.evaluate(()=>{const b=document.getElementById('sectorBand');const o=[];
    b.querySelectorAll('*').forEach(e=>{const r=e.getBoundingClientRect();const br=b.getBoundingClientRect();
      if(r.bottom>br.bottom+2&&r.height>4)o.push({sel:e.tagName+(e.id?'#'+e.id:'')+'.'+(typeof e.className==='string'?e.className.split(' ')[0]:''),bottom:Math.round(r.bottom),bandBottom:Math.round(br.bottom),h:Math.round(r.height),ovy:getComputedStyle(e).overflowY});});
    return JSON.stringify(o.slice(0,12));}));
  await ctx.close();await browser.close();server.close();
})();

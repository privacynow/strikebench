'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');

// cross-panel leaf-text collisions (NOT siblings-only) + panel geometry
const CROSS=()=>{
  const boxes=[];
  document.querySelectorAll('body *').forEach(el=>{
    if(el.children.length)return;
    const t=(el.textContent||'').trim(); if(!t)return;
    const s=getComputedStyle(el);
    if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;
    const b=el.getBoundingClientRect(); if(b.width<2||b.height<2)return;
    const band=el.closest('#univBand,#sectorBand,#newsBand,#chainBand,#riskMain,#bookrisk,.book');
    boxes.push({el,b,t,band:band?(band.id||band.className.split(' ')[0]):null});
  });
  const out=[];
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){
    const a=boxes[i],c=boxes[j];
    if(!a.band||!c.band||a.band===c.band)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left);
    const oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2)out.push(`${a.band}:"${a.t.slice(0,24)}" over ${c.band}:"${c.t.slice(0,24)}" (${Math.round(ox)}x${Math.round(oy)})`);
  }
  return out;
};
const GEO=()=>{
  const g={};
  ['univBand','sectorBand','newsBand','chainBand','riskMain','bookrisk'].forEach(id=>{
    const e=document.getElementById(id); if(!e)return;
    const p=e.querySelector('.authbookpanel');
    g[id]={band:Math.round(e.getBoundingClientRect().height),scroll:e.scrollHeight,client:e.clientHeight,
      panel:p?Math.round(p.getBoundingClientRect().height):null,panelMinH:p?getComputedStyle(p).minHeight:null,ovy:getComputedStyle(e).overflowY};
  });
  const bd=document.getElementById('board');
  g.board={h:Math.round(bd.getBoundingClientRect().height),scrollH:bd.scrollHeight,clientH:bd.clientHeight,rows:getComputedStyle(bd).gridTemplateRows};
  const bk=document.querySelector('.book');
  if(bk)g.book={h:Math.round(bk.getBoundingClientRect().height),scrollH:bk.scrollHeight,clientH:bk.clientHeight,scrollTop:bk.scrollTop,snap:getComputedStyle(bk).scrollSnapType,padTop:getComputedStyle(bk).scrollPaddingTop};
  const hd=document.querySelector('.book>.rosterhd'),c0=document.querySelector('.book .card');
  if(hd&&c0)g.roster={hdBottom:Math.round(hd.getBoundingClientRect().bottom),cardTop:Math.round(c0.getBoundingClientRect().top),cardH:Math.round(c0.getBoundingClientRect().height)};
  const doc=document.documentElement;
  g.doc={scrollH:doc.scrollHeight,clientH:doc.clientHeight};
  const lists={};
  document.querySelectorAll('#homeWatchList,#homePlansList,.authhomenews').forEach(l=>{
    lists[l.id||l.className.split(' ')[0]]={h:Math.round(l.getBoundingClientRect().height),scrollH:l.scrollHeight,clientH:l.clientHeight,ovy:getComputedStyle(l).overflowY};
  });
  g.lists=lists;
  const ov=[];document.querySelectorAll('.overflowread,.overflowmeta').forEach(e=>ov.push((e.textContent||'').trim()));
  g.overflowreads=ov;
  return g;
};

(async()=>{
  const server=http.createServer(serve);
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const VPS=[[2560,1440],[2000,963],[1920,1080],[390,844]];
  const STATES=[
    ['one',{positions:1,shares:0,workingIdeas:5,scout:'idle'}],
    ['twelve',{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],
    ['four',{positions:4,workingIdeas:5,scout:'complete'}]
  ];
  for(const [w,h] of VPS){
    for(const [sn,st] of STATES){
      const ctx=await browser.newContext({viewport:{width:w,height:h}});
      const page=await ctx.newPage(); page.setDefaultTimeout(20000);
      const errs=[];page.on('pageerror',e=>errs.push(e.message));
      await installWorld(page,st); await bootHome(page,url);
      const cross=await page.evaluate(CROSS); const geo=await page.evaluate(GEO);
      console.log(`\n### ${w}x${h} / ${sn}  crossCollisions=${cross.length}`);
      cross.slice(0,6).forEach(c=>console.log('   '+c));
      console.log('   GEO '+JSON.stringify(geo));
      if(errs.length)console.log('   PAGEERR '+errs.join(' | '));
      await page.screenshot({path:path.join(OUT,`${w}x${h}-${sn}.png`),fullPage:false});
      await ctx.close();
    }
  }
  await browser.close(); server.close();
})();

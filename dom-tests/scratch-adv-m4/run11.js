'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
// the visual lane's OWN assertions, replayed with the candidate patches applied
const CLIPPED=()=>{const c=[];document.querySelectorAll('body *').forEach(el=>{const s=getComputedStyle(el);
  if(s.display==='none'||s.visibility==='hidden')return;const b=el.getBoundingClientRect();if(b.width<1||b.height<1)return;
  if(/(auto|scroll)/.test(s.overflowX+s.overflowY))return;
  if(!(s.overflowX==='hidden'||s.overflowY==='hidden'||s.overflow==='hidden'))return;
  if(s.textOverflow==='ellipsis')return;
  if(el.scrollWidth-el.clientWidth<=2&&el.scrollHeight-el.clientHeight<=2)return;
  c.push(el.tagName.toLowerCase()+(el.id?'#'+el.id:'')+(typeof el.className==='string'&&el.className?'.'+el.className.trim().split(/\s+/).slice(0,3).join('.'):'')+' '+el.clientWidth+'x'+el.clientHeight+' around '+el.scrollWidth+'x'+el.scrollHeight);});return c;};
const SIBOVER=()=>{const boxes=[],out=[];document.querySelectorAll('body *').forEach(el=>{if(el.children.length)return;
  const t=(el.textContent||'').trim();if(!t)return;const s=getComputedStyle(el);
  if(s.display==='none'||s.visibility==='hidden'||s.opacity==='0')return;if(s.position==='absolute'||s.position==='fixed')return;
  const b=el.getBoundingClientRect();if(b.width<2||b.height<2)return;boxes.push({b,t,p:el.parentElement});});
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){const a=boxes[i],c=boxes[j];if(a.p!==c.p)continue;
    const ox=Math.min(a.b.right,c.b.right)-Math.max(a.b.left,c.b.left),oy=Math.min(a.b.bottom,c.b.bottom)-Math.max(a.b.top,c.b.top);
    if(ox>2&&oy>2)out.push(`"${a.t.slice(0,24)}" over "${c.t.slice(0,24)}"`);}return out;};
const TARGETS=(floor)=>{const f=[];document.querySelectorAll('button,[role="button"],a[href],select,input,textarea').forEach(el=>{
  const s=getComputedStyle(el);if(s.display==='none'||s.visibility==='hidden')return;if(el.closest('[aria-hidden="true"]'))return;if(el.disabled)return;
  const b=el.getBoundingClientRect();if(b.width===0&&b.height===0)return;
  if(b.width<floor||b.height<floor)f.push(el.tagName.toLowerCase()+'.'+(typeof el.className==='string'?el.className.trim().split(/\s+/)[0]:'')+' '+Math.round(b.width)+'x'+Math.round(b.height));});return f;};
const PAGEX=()=>({sw:document.documentElement.scrollWidth,cw:document.documentElement.clientWidth});
const VPS=[[2560,1440],[2048,1152],[2000,963],[1920,1080],[1440,900],[1280,800],[1000,800],[390,844],[375,812],[320,700]];
const PATCH='.authbookpanel{min-height:0}\n@media(max-width:900px){.lv-book .board{grid-template-rows:max-content;grid-auto-rows:max-content}}\n.lv-book .book{scroll-padding-top:48px}';
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  for(const patched of [false,true]){
    console.log(`\n===== ${patched?'WITH PATCH':'BASELINE'} =====`);
    for(const [w,h] of VPS){
      const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
      const errs=[];page.on('pageerror',e=>errs.push(e.message));
      await installWorld(page,{positions:4,workingIdeas:5,scout:'complete'});await bootHome(page,url);
      if(patched){await page.addStyleTag({content:PATCH});await page.waitForTimeout(400);}
      const clip=await page.evaluate(CLIPPED),sib=await page.evaluate(SIBOVER),
        tg=await page.evaluate(TARGETS,w<=500?24:14),px=await page.evaluate(PAGEX);
      console.log(`${w}x${h}: clipped=${clip.length} sibOverlap=${sib.length} smallTargets=${tg.length} sideways=${px.sw>px.cw+1} err=${errs.length}`);
      clip.slice(0,3).forEach(x=>console.log('    CLIP '+x));
      sib.slice(0,3).forEach(x=>console.log('    SIB '+x));
      tg.slice(0,3).forEach(x=>console.log('    TGT '+x));
      await ctx.close();
    }
  }
  await browser.close();server.close();
})();

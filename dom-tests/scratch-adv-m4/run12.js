'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const CLIPPED=()=>{const c=[];document.querySelectorAll('body *').forEach(el=>{const s=getComputedStyle(el);
  if(s.display==='none'||s.visibility==='hidden')return;const b=el.getBoundingClientRect();if(b.width<1||b.height<1)return;
  if(/(auto|scroll)/.test(s.overflowX+s.overflowY))return;
  if(!(s.overflowX==='hidden'||s.overflowY==='hidden'||s.overflow==='hidden'))return;
  if(s.textOverflow==='ellipsis')return;
  if(el.scrollWidth-el.clientWidth<=2&&el.scrollHeight-el.clientHeight<=2)return;
  const anc=[];let p=el;while(p&&p!==document.body){anc.push(p.tagName.toLowerCase()+(p.id?'#'+p.id:'')+(typeof p.className==='string'&&p.className?'.'+p.className.trim().split(/\s+/)[0]:''));p=p.parentElement;}
  c.push(anc.join('<')+' | '+el.clientWidth+'x'+el.clientHeight+' around '+el.scrollWidth+'x'+el.scrollHeight);});return c;};
const PATCHES={
  none:'',
  minh:'.authbookpanel{min-height:0}',
  minhScoped:'@media(min-width:901px){.authbookpanel{min-height:0}}',
  pad:'.lv-book .book{scroll-padding-top:48px}',
  mob:'@media(max-width:900px){.lv-book .board{grid-template-rows:max-content;grid-auto-rows:max-content}}'
};
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const states=[['four',{positions:4,workingIdeas:5,scout:'complete'}],['twelve',{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'}],['empty',{positions:0,shares:0,workingIdeas:0,scout:'idle'}],['degraded',{positions:4,workingIdeas:5,quote:'stale',history:'missing',chain:'error',news:'error',scout:'error'}]];
  for(const [w,h] of [[1000,800],[1280,800],[1440,900],[2000,963]]){
   for(const [sn,st] of states){
    for(const [pn,p] of Object.entries(PATCHES)){
      if(pn==='pad'||pn==='mob')continue;
      const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
      await installWorld(page,st);await bootHome(page,url);
      if(p){await page.addStyleTag({content:p});await page.waitForTimeout(400);}
      const c=await page.evaluate(CLIPPED);
      if(c.length)console.log(`${w}x${h}/${sn}/${pn}: clipped=${c.length}  ${c[0]}`);
      else console.log(`${w}x${h}/${sn}/${pn}: clipped=0`);
      await ctx.close();
    }}}
  await browser.close();server.close();
})();

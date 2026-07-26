'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const ctx=await browser.newContext({viewport:{width:390,height:844}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
  await installWorld(page,{positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'});
  await bootHome(page,url);
  const info=await page.evaluate(()=>{const bk=document.querySelector('.book');const s=getComputedStyle(bk);
    return {bookOvY:s.overflowY,bookH:bk.clientHeight,bookSH:bk.scrollHeight,rect:JSON.stringify(bk.getBoundingClientRect()),
      bandOv:[...document.querySelectorAll('#univBand,#bookrisk,#sectorBand')].map(e=>e.id+':'+getComputedStyle(e).overflowY+':'+e.clientHeight+'/'+e.scrollHeight)};});
  console.log(JSON.stringify(info,null,1));
  for(const y of [400,900,1400,1900]){
    await page.evaluate(t=>{document.getElementById('board').scrollTop=t;},y);
    await page.waitForTimeout(250);
    await page.screenshot({path:path.join(OUT,`mob-scroll-${y}.png`)});
  }
  await ctx.close();await browser.close();server.close();
})();

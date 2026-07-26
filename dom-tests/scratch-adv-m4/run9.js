'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const ctx=await browser.newContext({viewport:{width:2560,height:1440}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
  const reqs=[];page.on('request',r=>{if(r.url().includes('/api/research'))reqs.push(r.url());});
  await installWorld(page,{positions:4,workingIdeas:5,scout:'complete'});
  await bootHome(page,url);
  console.log(await page.evaluate(()=>{
    const c=document.querySelector('.authchainslice');
    return c?c.innerHTML.replace(/\s+/g,' ').slice(0,1200):'NO .authchainslice';}));
  console.log('--- chainBand text ---');
  console.log(await page.evaluate(()=>{const b=document.getElementById('chainBand');return b?b.innerText.replace(/\n/g,' | ').slice(0,600):'none';}));
  console.log('--- research reqs ---');console.log(reqs.slice(0,6).join('\n'));
  await ctx.close();await browser.close();server.close();
})();

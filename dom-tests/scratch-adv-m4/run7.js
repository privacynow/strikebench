'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http,fixtures}=require('./probe');
const path=require('path');
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const twelve={positions:12,shares:2,workingIdeas:20,mixedIdeas:true,scout:'complete'};

  // A. isolated scroll-padding-top + real wheel recovery
  for(const [w,h] of [[2560,1440],[2000,963],[1920,1080]]){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,twelve);await bootHome(page,url);
    const m=()=>page.evaluate(()=>{const bk=document.querySelector('.book'),hd=document.querySelector('.book>.rosterhd'),c0=document.querySelector('.book .card');
      return {st:bk.scrollTop,covered:Math.round(hd.getBoundingClientRect().bottom-c0.getBoundingClientRect().top),cardH:Math.round(c0.getBoundingClientRect().height),hdH:Math.round(hd.getBoundingClientRect().height)};});
    const b0=await m();
    const bk=await page.$('.book');const bb=await bk.boundingBox();
    await page.mouse.move(bb.x+bb.width/2,bb.y+bb.height/2);
    await page.mouse.wheel(0,-600);await page.waitForTimeout(600);
    const afterWheel=await m();
    await page.addStyleTag({content:'.lv-book .book{scroll-padding-top:48px}'});await page.waitForTimeout(600);
    const afterPad=await m();
    console.log(`A ${w}x${h}: load=${JSON.stringify(b0)} wheelUp=${JSON.stringify(afterWheel)} padOnly=${JSON.stringify(afterPad)}`);
    if(w===2560)await page.screenshot({path:path.join(OUT,'roster-header-2560.png'),clip:{x:bb.x,y:bb.y-10,width:bb.width,height:200}});
    await ctx.close();
  }

  // B. deep 25-strike chain
  {
    const ctx=await browser.newContext({viewport:{width:2560,height:1440}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,{positions:4,workingIdeas:5,scout:'complete'},world=>{
      const ch=world.market.chain;const calls=[],puts=[];
      for(let i=0;i<25;i++){const k=220+i*2.5;
        calls.push({strike:k,bid:1+i*0.1,ask:1.2+i*0.1});puts.push({strike:k,bid:1+i*0.1,ask:1.2+i*0.1});}
      if(ch&&ch.chain){ch.chain.calls=calls;ch.chain.puts=puts;}
      else if(ch){ch.calls=calls;ch.puts=puts;}
      console.error('CHAIN KEYS '+Object.keys(ch||{}));
    });
    await bootHome(page,url);
    const r=await page.evaluate(()=>{const rows=document.querySelectorAll('.authchainrow');
      const col=document.querySelector('.authchainslice');const rec=document.querySelector('.authchainslice .authreceipt');
      return {rows:rows.length,strikes:[...rows].map(e=>e.getAttribute('data-chain-k')).join(','),
        col:col?Math.round(col.getBoundingClientRect().height):null,receipt:rec?rec.textContent.trim():null};});
    console.log('B deep chain: '+JSON.stringify(r));
    await ctx.close();
  }

  // C. mobile shell measurement + proposed release
  {
    const ctx=await browser.newContext({viewport:{width:390,height:844}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,twelve);await bootHome(page,url);
    const g=()=>page.evaluate(()=>{const b=document.getElementById('board');const r=b.getBoundingClientRect();
      const d=document.documentElement;
      return {boardTop:Math.round(r.top),boardH:Math.round(r.height),boardSH:b.scrollHeight,docSH:d.scrollHeight,docCH:d.clientHeight,
        bodySH:document.body.scrollHeight,ovy:getComputedStyle(b).overflowY,
        stage:(()=>{const s=document.getElementById('stage');const cs=getComputedStyle(s);return cs.height+'/'+cs.overflow+'/'+cs.display;})(),
        app:(()=>{const s=document.getElementById('app');const cs=getComputedStyle(s);return cs.height+'/'+cs.overflow+'/'+cs.display;})()};});
    console.log('C mobile before: '+JSON.stringify(await g()));
    await page.addStyleTag({content:'@media(max-width:900px){.board{overflow:visible;flex:none}}'});await page.waitForTimeout(400);
    console.log('C mobile afterRelease: '+JSON.stringify(await g()));
    await page.screenshot({path:path.join(OUT,'mobile-release.png')});
    await ctx.close();
  }
  await browser.close();server.close();
})();

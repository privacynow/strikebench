'use strict';
const fs=require('fs'),http=require('http'),path=require('path');
const {chromium}=require('playwright');
const fixtures=require('../fixtures');
const {serve,OUT}=require('./probe');
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  async function boot(page,deep,w,h){
    const world=fixtures.desk({positions:4,workingIdeas:5,scout:'complete'});
    if(deep){const b=world.market.chain.body,proto=b.calls[0],pp=b.puts[0];const c=[],p=[];
      for(let i=0;i<25;i++){const k=220+i*2.5;c.push(Object.assign({},proto,{strike:k,bid:1+i*.1,ask:1.2+i*.1}));p.push(Object.assign({},pp,{strike:k,bid:1+i*.1,ask:1.2+i*.1}));}
      b.calls=c;b.puts=p;}
    await page.route('**/api/**',async route=>{
      const at=new URL(route.request().url()).pathname;
      const research=at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
      let body,status=200;
      if(at==='/api/config')body={fixturesOnly:false,world:'observed',marketLane:'OBSERVED',scenarioMode:false};
      else if(at==='/api/status')body={ok:true,status:'READY',fixturesOnly:false};
      else if(at==='/api/world')body={world:'observed',revision:1,epoch:1};
      else if(at==='/api/workspace')body={rev:1,updatedAt:'2026-07-25T12:00:00Z',supportedVersion:1,world:'observed',marketLane:'OBSERVED',accountId:'acct-1',context:null,transition:null,unreadable:null};
      else if(at==='/api/account')body={account:{id:'acct-1',cashCents:5000000,buyingPowerCents:9700000},ledger:[]};
      else if(at==='/api/portfolio/summary')body=world.book.summary;
      else if(at==='/api/portfolio/heat')body=world.book.heat;
      else if(at==='/api/portfolio/greeks')body=world.book.greeks;
      else if(at==='/api/portfolio/book-risk')body=world.book.bookRisk;
      else if(at==='/api/portfolio/accounts')body=[{id:'acct-1',name:'Practice'}];
      else if(at==='/api/positions')body=world.book.positionBook;
      else if(at==='/api/trades')body=world.book.tradePage;
      else if(at==='/api/plans')body=world.plans;
      else if(at==='/api/plans/portfolio')body=world.planPortfolio;
      else if(at==='/api/universe')body=world.market.universe||{symbols:[],sectors:[]};
      else if(at==='/api/strategies')body={catalog:[]};
      else if(research){const lane=research[2]||'research';const doc=world.market[lane]!==undefined?world.market[lane]:world.market.research;
        if(doc&&typeof doc==='object'&&'status'in doc&&'body'in doc){status=doc.status;body=doc.body;}else body=doc;}
      else body={};
      await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});});
    await page.goto(url);
    await page.waitForFunction(()=>window.DeskBackend!=null&&window.WORKSPACE!=null);
    await page.waitForSelector('#board');
    await page.waitForTimeout(900);
  }
  for(const [name,deep,w,h] of [['5-strike',false,2560,1440],['25-strike',true,2560,1440],['25-strike',true,2000,963],['25-strike',true,1920,1080],['25-strike',true,390,844]]){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await boot(page,deep,w,h);
    const r=await page.evaluate(()=>{const rows=document.querySelectorAll('.authchainrow');
      const col=document.querySelector('.authchainslice');const rec=col&&col.querySelector('.authreceipt');
      let ink=0;if(col)col.querySelectorAll('*').forEach(e=>{if(e.children.length)return;const t=(e.textContent||'').trim();if(!t)return;const b=e.getBoundingClientRect();if(b.height>1)ink=Math.max(ink,b.bottom);});
      const r0=rows[0];
      return {n:rows.length,ks:[...rows].map(e=>e.getAttribute('data-chain-k')).join(','),
        colH:col?Math.round(col.getBoundingClientRect().height):null,colBottom:col?Math.round(col.getBoundingClientRect().bottom):null,
        ink:Math.round(ink),receipt:rec?rec.textContent.trim():null,
        tag:r0?r0.tagName:null,role:r0?r0.getAttribute('role'):null,cursor:r0?getComputedStyle(r0).cursor:null,
        h:r0?Math.round(r0.getBoundingClientRect().height):null,btns:r0?r0.querySelectorAll('button,a[href]').length:null,
        tabindex:r0?r0.getAttribute('tabindex'):null,title:r0?r0.getAttribute('title'):null};});
    console.log(`${name} ${w}x${h}: ${JSON.stringify(r)}`);
    if(deep&&w===2560)await page.screenshot({path:path.join(OUT,'deepchain2-2560.png')});
    await ctx.close();
  }
  await browser.close();server.close();
})();

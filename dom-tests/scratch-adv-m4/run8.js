'use strict';
const {serve,installWorld,bootHome,OUT,chromium,http}=require('./probe');
const path=require('path');
(async()=>{
  const server=http.createServer(serve);await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const url=`http://127.0.0.1:${server.address().port}/index.html`;
  const browser=await chromium.launch({headless:true});
  const mk=(deep)=>(world)=>{
    if(!deep)return;
    const b=world.market.chain.body;const proto=b.calls[0],pproto=b.puts[0];
    const calls=[],puts=[];
    for(let i=0;i<25;i++){const k=220+i*2.5;
      calls.push(Object.assign({},proto,{strike:k,bid:1+i*0.1,ask:1.2+i*0.1,last:1.1+i*0.1}));
      puts.push(Object.assign({},pproto,{strike:k,bid:1+i*0.1,ask:1.2+i*0.1,last:1.1+i*0.1}));}
    b.calls=calls;b.puts=puts;
  };
  for(const [name,deep,w,h] of [['default-5',false,2560,1440],['deep-25',true,2560,1440],['deep-25',true,2000,963],['deep-25',true,1920,1080]]){
    const ctx=await browser.newContext({viewport:{width:w,height:h}});const page=await ctx.newPage();page.setDefaultTimeout(20000);
    await installWorld(page,{positions:4,workingIdeas:5,scout:'complete'},mk(deep));
    await bootHome(page,url);
    const r=await page.evaluate(()=>{const rows=document.querySelectorAll('.authchainrow');
      const col=document.querySelector('.authchainslice');const rec=col&&col.querySelector('.authreceipt');
      let inkBottom=0;if(col){col.querySelectorAll('*').forEach(e=>{if(e.children.length)return;const t=(e.textContent||'').trim();if(!t)return;const b=e.getBoundingClientRect();if(b.height>1)inkBottom=Math.max(inkBottom,b.bottom);});}
      return {rows:rows.length,first:rows[0]&&rows[0].getAttribute('data-chain-k'),last:rows[rows.length-1]&&rows[rows.length-1].getAttribute('data-chain-k'),
        colH:col?Math.round(col.getBoundingClientRect().height):null,colTop:col?Math.round(col.getBoundingClientRect().top):null,
        inkBottom:Math.round(inkBottom),receipt:rec?rec.textContent.trim():null,
        rowTag:rows[0]?rows[0].tagName:null,rowRole:rows[0]?rows[0].getAttribute('role'):null,
        rowCursor:rows[0]?getComputedStyle(rows[0]).cursor:null,rowH:rows[0]?Math.round(rows[0].getBoundingClientRect().height):null,
        btns:rows[0]?rows[0].querySelectorAll('button,a[href]').length:null};});
    console.log(`${name} ${w}x${h}: ${JSON.stringify(r)}`);
    if(deep&&w===2560)await page.screenshot({path:path.join(OUT,'deepchain-2560.png')});
    await ctx.close();
  }
  await browser.close();server.close();
})();

'use strict';
const fs=require('fs'),http=require('http'),path=require('path');
const {chromium}=require('playwright');
const fixtures=require('../fixtures');
const PUBLIC=path.resolve(__dirname,'..','..','src','main','resources','public');
const OUT=path.join(__dirname,'shots'); fs.mkdirSync(OUT,{recursive:true});
function ct(f){if(f.endsWith('.html'))return 'text/html; charset=utf-8';if(f.endsWith('.js'))return 'text/javascript; charset=utf-8';if(f.endsWith('.css'))return 'text/css; charset=utf-8';if(f.endsWith('.svg'))return 'image/svg+xml';return 'application/octet-stream';}
function serve(req,res){const u=new URL(req.url,'http://127.0.0.1');const p=u.pathname==='/'?'/index.html':decodeURIComponent(u.pathname);const f=path.resolve(PUBLIC,`.${p}`);if(f!==PUBLIC&&!f.startsWith(PUBLIC+path.sep)){res.writeHead(403).end('no');return;}fs.readFile(f,(e,b)=>{if(e){res.writeHead(e.code==='ENOENT'?404:500).end(e.message);return;}res.writeHead(200,{'Content-Type':ct(f),'Cache-Control':'no-store'});res.end(b);});}
async function installWorld(page,state,mutate){
  const world=fixtures.desk(state); if(mutate)mutate(world);
  await page.route('**/api/**',async route=>{
    const url=new URL(route.request().url()); const at=url.pathname;
    const research=at.match(/^\/api\/research\/([^/]+)(?:\/(history|expirations|chain|news))?$/);
    let body;
    if(at==='/api/config')body={fixturesOnly:false,world:'observed',marketLane:'OBSERVED',scenarioMode:false};
    else if(at==='/api/status')body={ok:true,status:'READY',fixturesOnly:false};
    else if(at==='/api/world')body={world:'observed',revision:1,epoch:1};
    else if(at==='/api/workspace')body={rev:1,updatedAt:'2026-07-25T12:00:00Z',supportedVersion:1,world:'observed',marketLane:'OBSERVED',accountId:'acct-1',context:null,transition:null,unreadable:null};
    else if(at==='/api/account')body={account:{id:'acct-1',cashCents:5000000,buyingPowerCents:9700000},ledger:[]};
    else if(at==='/api/portfolio/summary')body=world.book.summary;
    else if(at==='/api/portfolio/heat')body=world.book.heat;
    else if(at==='/api/portfolio/greeks')body=world.book.greeks;
    else if(at==='/api/portfolio/book-risk')body=world.book.bookRisk;
    else if(at==='/api/portfolio/accounts')body=[{id:'acct-1',name:'Practice ••••0001'}];
    else if(at==='/api/positions')body=world.book.positionBook;
    else if(at==='/api/trades')body=world.book.tradePage;
    else if(at==='/api/plans')body=world.plans;
    else if(at==='/api/plans/portfolio')body=world.planPortfolio;
    else if(at==='/api/universe')body=world.market.universe||{symbols:[],sectors:[]};
    else if(at==='/api/strategies')body={catalog:[]};
    else if(research){const lane=research[2]||'research';body=world.market[lane]!==undefined?world.market[lane]:world.market.research;}
    else if(at.startsWith('/api/trades/')){const id=decodeURIComponent(at.slice('/api/trades/'.length));body=world.book.tradeDetails[id]||{trade:null};}
    else body={};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(body)});
  });
  return world;
}
async function bootHome(page,url){
  await page.goto(url);
  await page.waitForFunction(()=>window.DeskBackend!=null&&window.WORKSPACE!=null);
  await page.waitForSelector('#board');
  await page.waitForFunction(()=>{const b=document.getElementById('board');return b!=null&&b.getBoundingClientRect().height>40;});
  await page.waitForTimeout(250);
}
module.exports={serve,installWorld,bootHome,OUT,chromium,http,fixtures};

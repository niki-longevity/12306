const { execSync, spawnSync } = require('child_process');
const http = require('http');
const { randomInt } = require('crypto');

const TOKEN = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIyMDU4OTI3Nzk1NDQxNjIzMDQyIiwidXNlcm5hbWUiOiJiZW5jaDAxIiwiaWF0IjoxNzc5NzI1NjcwLCJleHAiOjE3Nzk3MzI4NzB9.igIzE-DQXHdJonKNogg6yzU5iXgYAzAu1shNawM8a60';

console.log('[1/3] Export Redis...');
const t0 = Date.now();
const keys = execSync('docker exec redis redis-cli --raw KEYS "TrainStop:*"', {encoding:'utf-8',timeout:30000}).trim().split('\n').filter(Boolean);
const pipe = keys.map(k=>`GET ${k}`).join('\n')+'\n';
const res = spawnSync('docker', ['exec','-i','redis','redis-cli','--raw'], {input:pipe,encoding:'utf-8',timeout:40000,maxBuffer:100*1024*1024});
const lines = (res.stdout||'').split('\n');
console.log(`  ${keys.length} keys, ${((Date.now()-t0)/1000).toFixed(1)}s`);

console.log('[2/3] Parse routes...');
const ROUTES = [];
let pc = 0;
for (const line of lines) {
  if (!line||line[0]==='$'||line[0]==='*'||line[0]==='+') continue;
  try {
    const d = JSON.parse(line), st = d.stopoverStations;
    if (!st||st.length<2) continue; pc++;
    for (let i=0;i<st.length-1;i++) for (let j=i+1;j<st.length;j++)
      for (const s of [0,1,2])
        ROUTES.push({date:d.date,code:d.code,startStation:st[i].stopoverStation,endStation:st[j].stopoverStation,seatType:s});
  }catch(e){}
}
console.log(`  ${ROUTES.length} routes from ${pc} trains, ${((Date.now()-t0)/1000).toFixed(1)}s`);

console.log('[3/3] Benchmark 30s...');
let total=0,ok=0,err=0,run=true;
const lats=[];

function send() {
  const r = ROUTES[randomInt(ROUTES.length)];
  const body = JSON.stringify({date:r.date,code:r.code,startStation:r.startStation,endStation:r.endStation,seatType:r.seatType,passengerList:[{realName:'Bench User',idCard:'110101199001011234'}]});
  return new Promise(resolve => {
    const start = process.hrtime.bigint();
    const req = http.request({hostname:'localhost',port:8092,path:'/ticket/buy',method:'PUT',timeout:3000,
      headers:{'Content-Type':'application/json; charset=UTF-8','Authorization':'Bearer '+TOKEN,'Connection':'keep-alive'}}, res => {
      let d=''; res.on('data',c=>d+=c); res.on('end',()=>{
        const ns=Number(process.hrtime.bigint()-start); total++;
        try { if(JSON.parse(d).code===1)ok++; else err++; } catch(e){err++;}
        if(lats.length<200000)lats.push(ns);
        resolve();
      });
    });
    req.on('timeout',()=>{req.destroy();total++;err++;resolve();});
    req.on('error',()=>{total++;err++;resolve();});
    req.write(body); req.end();
  });
}

async function worker() { while(run) { await send(); } }

(async()=>{
  const t1=Date.now();
  const workers=Array.from({length:100},()=>worker());
  setTimeout(()=>{run=false},30000);
  // Progress every 5s
  const iv=setInterval(()=>console.log(`  ${((Date.now()-t1)/1000).toFixed(0)}s elapsed, ${total} req, ${ok} ok`),5000);
  await Promise.allSettled(workers);
  clearInterval(iv);
  const elapsed=(Date.now()-t1)/1000;
  if(lats.length){
    const s=lats.map(v=>Number(v)).sort((a,b)=>a-b);
    const avg=s.reduce((a,b)=>a+b,0)/s.length/1e6;
    const p=n=>s[Math.min(Math.floor(s.length*n),s.length-1)]/1e6;
    console.log('');
    console.log('========================================');
    console.log(`  QPS:     ${(total/elapsed).toFixed(0)} req/s`);
    console.log(`  Success: ${ok}/${total} (${(ok/total*100).toFixed(1)}%)`);
    console.log(`  Avg:${avg.toFixed(1)}ms P50:${p(0.5).toFixed(1)}ms P99:${p(0.99).toFixed(1)}ms`);
    console.log(`  Min:${(s[0]/1e6).toFixed(1)}ms Max:${(s[s.length-1]/1e6).toFixed(1)}ms`);
    console.log('========================================');
  }
  process.exit(0);
})();

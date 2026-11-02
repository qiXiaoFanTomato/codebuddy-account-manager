import fs from 'node:fs';
import path from 'node:path';

const base = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\codebuddy-accounts`;
const idx = path.join(base, 'index.json');
if (!fs.existsSync(idx)) { console.log('index.json 不存在'); process.exit(0); }
const arr = JSON.parse(fs.readFileSync(idx, 'utf8'));
console.log('账号库条目数: ' + arr.length);
for (const a of arr) {
  console.log(`  uid=${(a.uid||'').slice(0,8)} domain=${a.domain} nick=${a.nickname} uin=${a.uin} alias=${a.alias}`);
}

console.log('\n快照文件:');
const snapDir = path.join(base, 'snapshots');
if (fs.existsSync(snapDir)) {
  for (const n of fs.readdirSync(snapDir)) console.log('  ' + n);
}

import fs from 'node:fs';
import path from 'node:path';

const AUTH_DIR = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data', 'Public', 'auth');

function mask(v, depth) {
  if (v === null || v === undefined) return v;
  if (Array.isArray(v)) {
    if (depth > 3) return '[Array(' + v.length + ')]';
    return v.map(x => mask(x, depth + 1));
  }
  if (typeof v === 'object') {
    const o = {};
    for (const [k, val] of Object.entries(v)) {
      if (/token|secret|password|credential/i.test(k) && typeof val === 'string') {
        o[k] = val.slice(0, 4) + '…(len=' + val.length + ')';
      } else if (depth > 4) {
        o[k] = typeof val === 'object' ? '[object]' : val;
      } else {
        o[k] = mask(val, depth + 1);
      }
    }
    return o;
  }
  return v;
}

for (const name of ['workbuddy-desktop.info', 'workbuddy-desktop.info.bak']) {
  const p = path.join(AUTH_DIR, name);
  if (!fs.existsSync(p)) { console.log('[缺失] ' + name); continue; }
  const j = JSON.parse(fs.readFileSync(p, 'utf8'));
  console.log('===== ' + name + ' =====');
  console.log('顶层键: ' + Object.keys(j).join(', '));
  console.log('accounts: ' + JSON.stringify(mask(j.accounts, 0), null, 2));
  console.log('allAccounts: ' + JSON.stringify(mask(j.allAccounts, 0), null, 2));
  console.log('');
}

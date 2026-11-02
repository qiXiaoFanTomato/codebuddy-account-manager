import fs from 'node:fs';
import path from 'node:path';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');
const AUTH = path.join(DATA, 'Public', 'auth');

console.log('===== auth 目录下所有 .info =====');
const files = fs.readdirSync(AUTH).filter(n => n.endsWith('.info') || n.endsWith('.info.bak'));
const seen = new Map();
for (const n of files) {
  try {
    const j = JSON.parse(fs.readFileSync(path.join(AUTH, n), 'utf8'));
    const uid = j.account?.uid || '?';
    const domain = j.auth?.domain || '?';
    const nick = j.account?.nickname || '?';
    const uin = j.account?.uin || '?';
    const exp = j.auth?.expiresAt || 0;
    const key = uid + '|' + domain;
    const prev = seen.get(key);
    console.log(`${n}\n   uid=${uid.slice(0,8)} domain=${domain} nick=${nick} uin=${uin} expiresAt=${exp}\n   key=${key} ${prev ? '(重复, 已存在)' : ''}`);
    if (!prev || exp > prev.exp) seen.set(key, { uid, domain, nick, uin, exp, file: n });
  } catch (e) {
    console.log(n + ' 解析失败: ' + e.message);
  }
}

console.log('\n===== 按 (uid|domain) 去重后 =====');
for (const [k, v] of seen) {
  console.log(`${k}  nick=${v.nick} uin=${v.uin}  <= ${v.file}`);
}

console.log('\n===== 按 uid 去重（旧逻辑）=====');
const byUid = new Map();
for (const [k, v] of seen) {
  if (!byUid.has(v.uid)) byUid.set(v.uid, []);
  byUid.get(v.uid).push(v);
}
for (const [uid, list] of byUid) {
  console.log(`uid=${uid.slice(0,8)} 有 ${list.length} 个 domain: ${list.map(x => x.domain).join(', ')}`);
}

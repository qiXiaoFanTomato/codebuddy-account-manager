import fs from 'node:fs';
import path from 'node:path';

const AUTH_DIR = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data', 'Public', 'auth');
const active = path.join(AUTH_DIR, 'workbuddy-desktop.info');
const j = JSON.parse(fs.readFileSync(active, 'utf8'));
const auth = j.auth || {};
const token = auth.accessToken;
const domain = auth.domain || 'www.codebuddy.cn';
const uid = auth.uid || j.uid || '';
const global = domain.includes('.workbuddy.ai');
const base = global ? 'https://www.workbuddy.ai' : 'https://copilot.tencent.com';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

console.log('auth keys = ' + Object.keys(auth).join(','));
console.log('domain=' + domain + ' uid=' + uid + ' base=' + base);

async function call(pathname) {
  const res = await fetch(base + pathname, {
    method: 'GET',
    headers: {
      'Accept': 'application/json, text/plain, */*',
      'Authorization': 'Bearer ' + token,
      'User-Agent': UA,
      'X-CodeBuddy-Request': '1',
      'X-User-Id': uid,
      'X-Domain': domain,
      'Accept-Language': global ? 'en-US' : 'zh-CN',
    },
  });
  const text = await res.text();
  console.log('--- ' + pathname + ' HTTP=' + res.status + ' ---');
  console.log(text.slice(0, 4000));
}

await call('/activity/growth/buddy/travel/status');
await call('/activity/growth/buddy/info');

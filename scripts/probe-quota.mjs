import fs from 'node:fs';
import path from 'node:path';

const AUTH_DIR = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data', 'Public', 'auth');
const active = path.join(AUTH_DIR, 'workbuddy-desktop.info');
const j = JSON.parse(fs.readFileSync(active, 'utf8'));
const token = j.auth.accessToken;
const domain = j.auth.domain || 'www.codebuddy.cn';
const url = 'https://' + domain + '/v2/billing/meter/get-user-resource';
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

async function call(body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Authorization': 'Bearer ' + token,
      'Content-Type': 'application/json',
      'User-Agent': UA,
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  return { status: res.status, text };
}

console.log('URL=' + url);
for (const body of [{}, { platform: 'ide' }]) {
  try {
    const r = await call(body);
    console.log('--- body=' + JSON.stringify(body) + ' HTTP=' + r.status + ' ---');
    console.log(r.text.slice(0, 2000));
  } catch (e) {
    console.log('--- body=' + JSON.stringify(body) + ' ERROR: ' + e.message);
  }
}

import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const files = [
  path.join(EXT, 'out', 'extension', 'index.js'),
  path.join(EXT, 'server.js'),
];

const kws = [
  'oauth/callback',
  'redirect_uri',
  'redirectUri',
  'client_id',
  'clientId',
  'code_challenge',
  'code_verifier',
  'localhost',
  '127.0.0.1',
  'authUrl',
  'auth/state',
  'nativeGetLoginUrl',
  'openExternal',
  'startCallbackServer',
];

for (const p of files) {
  if (!fs.existsSync(p)) continue;
  const txt = fs.readFileSync(p, 'utf8');
  console.log('===== ' + path.basename(p) + ' =====');
  for (const kw of kws) {
    let idx = 0, found = 0;
    while ((idx = txt.indexOf(kw, idx)) !== -1 && found < 2) {
      const start = Math.max(0, idx - 100);
      const end = Math.min(txt.length, idx + kw.length + 140);
      console.log('[' + kw + '] ' + txt.slice(start, end).replace(/\n/g, ' '));
      idx += kw.length; found++;
    }
    if (found === 0) console.log('[' + kw + '] —');
  }
  console.log('');
}

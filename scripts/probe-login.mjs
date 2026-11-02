import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const p = path.join(EXT, 'out', 'extension', 'index.js');
const txt = fs.readFileSync(p, 'utf8');

const kws = [
  'accessToken',
  'refreshToken',
  '.info',
  'writeFileSync',
  'auth/state',
  'plugin/auth',
  'oauth',
  'login',
  'codebuddy.cn',
  'workbuddy.ai',
];

for (const kw of kws) {
  let idx = 0, found = 0;
  const snippets = [];
  while ((idx = txt.indexOf(kw, idx)) !== -1 && found < 3) {
    const start = Math.max(0, idx - 150);
    const end = Math.min(txt.length, idx + kw.length + 200);
    snippets.push('@' + idx + ': ' + txt.slice(start, end).replace(/\n/g, ' '));
    idx += kw.length; found++;
  }
  console.log('===== [' + kw + '] ' + found + ' 处 =====');
  for (const s of snippets) console.log(s + '\n');
}

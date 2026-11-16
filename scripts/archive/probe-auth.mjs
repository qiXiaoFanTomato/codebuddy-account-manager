import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;

const targets = [
  'server.js',
  path.join('out', 'extension', 'index.js'),
];
const keywords = ['CodeBuddyExtension', 'workbuddy-desktop', 'get-user-resource', 'Public/auth', 'Public\\auth'];

for (const rel of targets) {
  const p = path.join(EXT, rel);
  if (!fs.existsSync(p)) { console.log('[缺失] ' + p); continue; }
  const txt = fs.readFileSync(p, 'utf8');
  console.log('===== ' + rel + ' (' + txt.length + ' chars) =====');
  for (const kw of keywords) {
    let idx = 0, found = 0;
    while ((idx = txt.indexOf(kw, idx)) !== -1 && found < 6) {
      const start = Math.max(0, idx - 120);
      const end = Math.min(txt.length, idx + kw.length + 160);
      console.log('--- [' + kw + '] @' + idx + ' ---');
      console.log(txt.slice(start, end).replace(/\n/g, ' '));
      idx += kw.length; found++;
    }
    if (found === 0) console.log('[' + kw + '] 未找到');
  }
  console.log('');
}

import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains`;
const targets = [
  path.join(EXT, 'bin', 'fusion-win-x64.exe'),
  path.join(EXT, 'lib', 'instrumented-code-icc-extensions-4.12.41911495.jar'),
];
const keywords = ['workbuddy-desktop', 'CodeBuddyExtension', 'get-user-resource', 'Public/auth', 'Public\\auth', 'workbuddy-desktop.info'];

for (const p of targets) {
  if (!fs.existsSync(p)) { console.log('[缺失] ' + p); continue; }
  const buf = fs.readFileSync(p);
  console.log('===== ' + path.basename(p) + ' (' + buf.length + ' bytes) =====');
  for (const kw of keywords) {
    const needle = Buffer.from(kw, 'utf8');
    let idx = 0, found = 0;
    while ((idx = buf.indexOf(needle, idx)) !== -1 && found < 5) {
      const start = Math.max(0, idx - 100);
      const end = Math.min(buf.length, idx + needle.length + 120);
      const chunk = buf.subarray(start, end).toString('latin1').replace(/[\x00-\x1f]/g, '.');
      console.log('--- [' + kw + '] @' + idx + ' ---');
      console.log(chunk);
      idx += needle.length; found++;
    }
    if (found === 0) console.log('[' + kw + '] 未找到');
  }
  console.log('');
}

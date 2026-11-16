import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const p = path.join(EXT, 'out', 'extension', 'index.js');
const txt = fs.readFileSync(p, 'utf8');

function show(kw, before, after, max) {
  let idx = 0, found = 0;
  console.log('##### ' + kw + ' #####');
  while ((idx = txt.indexOf(kw, idx)) !== -1 && found < (max || 5)) {
    const start = Math.max(0, idx - (before || 150));
    const end = Math.min(txt.length, idx + kw.length + (after || 500));
    console.log('--- @' + idx + ' ---');
    console.log(txt.slice(start, end));
    console.log('');
    idx += kw.length; found++;
  }
  if (!found) console.log('未找到');
}

show('"genie.auth.nativeGetSession"', 250, 700, 3);
show('registerCommand("genie.auth', 60, 300, 20);
show('genie.auth.', 80, 200, 40);

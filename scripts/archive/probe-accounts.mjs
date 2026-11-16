import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const p = path.join(EXT, 'out', 'extension', 'index.js');
const txt = fs.readFileSync(p, 'utf8');

function show(kw, before, after, max) {
  let idx = 0, found = 0;
  console.log('##### ' + kw + ' #####');
  while ((idx = txt.indexOf(kw, idx)) !== -1 && found < (max || 4)) {
    const start = Math.max(0, idx - (before || 200));
    const end = Math.min(txt.length, idx + kw.length + (after || 600));
    console.log('--- @' + idx + ' ---');
    console.log(txt.slice(start, end));
    console.log('');
    idx += kw.length; found++;
  }
  if (!found) console.log('未找到');
}

show('handleGetAccounts', 100, 1200, 2);
show('handleSwitchAccount', 100, 1200, 2);
show('genie.auth.native', 80, 400, 12);

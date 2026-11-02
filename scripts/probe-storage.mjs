import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const txt = fs.readFileSync(path.join(EXT, 'out', 'extension', 'index.js'), 'utf8');

function show(kw, before, after, max) {
  let idx = 0, found = 0;
  console.log('##### ' + kw + ' #####');
  while ((idx = txt.indexOf(kw, idx)) !== -1 && found < (max || 3)) {
    const start = Math.max(0, idx - (before || 200));
    const end = Math.min(txt.length, idx + kw.length + (after || 600));
    console.log('--- @' + idx + ' ---');
    console.log(txt.slice(start, end));
    console.log('');
    idx += kw.length; found++;
  }
  if (!found) console.log('未找到\n');
}

show('getAuthenticationStorage()', 150, 700, 4);
show('ide.host.authentication.init', 100, 500, 3);
show('authenticationStorage', 100, 400, 4);

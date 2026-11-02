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

// 找 storage 实现类
show('class IDEHost', 100, 900, 3);
show('class VSCodeAuth', 100, 700, 2);
show('class NativeAuthStorage', 100, 900, 2);
show('class FileAuthStorage', 100, 900, 2);
show('storePath', 100, 300, 6);
show('AUTH_STORAGE', 100, 300, 4);
show('.info', 100, 200, 8);

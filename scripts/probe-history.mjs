import fs from 'node:fs';
import path from 'node:path';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');

function walk(dir, depth, out) {
  if (depth > 6) return;
  let names;
  try { names = fs.readdirSync(dir); } catch { return; }
  for (const n of names) {
    const p = path.join(dir, n);
    let st;
    try { st = fs.statSync(p); } catch { continue; }
    if (st.isDirectory()) walk(p, depth + 1, out);
    else out.push({ path: p, size: st.size, mtime: st.mtimeMs });
  }
}

for (const uid of ['ab6f110b-0e24-451b-91c3-c077944f33d0', '13e50313-2703-4e53-a147-a39e76fc75eb']) {
  const histDir = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history');
  console.log('===== uid=' + uid.slice(0, 8) + ' =====');
  console.log('history 目录存在: ' + fs.existsSync(histDir));
  const out = [];
  walk(histDir, 0, out);
  console.log('文件数: ' + out.length + '，总大小: ' + out.reduce((s, f) => s + f.size, 0) + ' bytes');
  for (const f of out.slice(0, 15)) {
    console.log('  ' + path.relative(histDir, f.path) + '  (' + f.size + 'B)');
  }
  console.log('');
}

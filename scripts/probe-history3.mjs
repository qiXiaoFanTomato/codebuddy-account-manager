import fs from 'node:fs';
import path from 'node:path';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');
const uids = ['ab6f110b-0e24-451b-91c3-c077944f33d0', '13e50313-2703-4e53-a147-a39e76fc75eb'];

function allFiles(root) {
  const out = [];
  const stack = [root];
  while (stack.length) {
    const d = stack.pop();
    let names;
    try { names = fs.readdirSync(d); } catch { continue; }
    for (const n of names) {
      const p = path.join(d, n);
      let st;
      try { st = fs.statSync(p); } catch { continue; }
      if (st.isDirectory()) stack.push(p);
      else out.push({ rel: path.relative(root, p), size: st.size, mtime: st.mtimeMs });
    }
  }
  return out.sort((a, b) => a.rel.localeCompare(b.rel));
}

const maps = {};
for (const uid of uids) {
  const root = path.join(DATA, uid);
  const files = allFiles(root);
  maps[uid] = new Map(files.map(f => [f.rel, f]));
  console.log('===== ' + uid.slice(0, 8) + ' 共 ' + files.length + ' 文件 =====');
  for (const f of files.slice(0, 8)) {
    console.log('  ' + f.rel + '  ' + new Date(f.mtime).toISOString());
  }
  console.log('');
}

const a = maps[uids[0]], b = maps[uids[1]];
const onlyA = [...a.keys()].filter(k => !b.has(k));
const onlyB = [...b.keys()].filter(k => !a.has(k));
console.log('仅 A 有: ' + onlyA.length + '，仅 B 有: ' + onlyB.length);
console.log('A 独有示例: ' + onlyA.slice(0, 5).join(' | '));
console.log('B 独有示例: ' + onlyB.slice(0, 5).join(' | '));

// 抽查 mtime 差异
let diff = 0;
for (const [k, va] of a) {
  const vb = b.get(k);
  if (vb && va.mtime !== vb.mtime) diff++;
}
console.log('共有文件中 mtime 不同的: ' + diff);

// 看 Data 根下所有顶层目录的 mtime
console.log('\n--- Data 顶层 ---');
for (const n of fs.readdirSync(DATA)) {
  const p = path.join(DATA, n);
  try {
    const st = fs.statSync(p);
    console.log(n + '  ' + (st.isDirectory() ? 'DIR' : 'FILE') + '  ' + new Date(st.mtime).toISOString());
  } catch {}
}

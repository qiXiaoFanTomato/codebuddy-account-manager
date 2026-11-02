import fs from 'node:fs';
import path from 'node:path';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');

function countDir(dir, maxDepth = 3, depth = 0) {
  const out = { files: 0, dirs: 0, bytes: 0 };
  if (depth > maxDepth) return out;
  let names;
  try { names = fs.readdirSync(dir); } catch { return out; }
  for (const n of names) {
    const p = path.join(dir, n);
    let st;
    try { st = fs.statSync(p); } catch { continue; }
    if (st.isDirectory()) {
      out.dirs++;
      const sub = countDir(p, maxDepth, depth + 1);
      out.files += sub.files; out.dirs += sub.dirs; out.bytes += sub.bytes;
    } else {
      out.files++; out.bytes += st.size;
    }
  }
  return out;
}

// 1. 每个 uid 的 JetBrains 目录体积
console.log('===== Data/<uid>/JetBrains 体积 =====');
for (const uid of fs.readdirSync(DATA)) {
  const jb = path.join(DATA, uid, 'JetBrains');
  if (!fs.existsSync(jb)) continue;
  const s = countDir(jb, 2);
  console.log(`${uid.slice(0,8)}  JetBrains: ${s.dirs} 目录, ${s.files} 文件, ${(s.bytes/1024/1024).toFixed(1)} MB`);
}

// 2. 每个 uid 的 history 目录体积 + 会话数
console.log('\n===== history 目录 =====');
for (const uid of fs.readdirSync(DATA)) {
  const h = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history');
  if (!fs.existsSync(h)) continue;
  const s = countDir(h, 4);
  let wsCount = 0, convCount = 0;
  try {
    const wsList = fs.readdirSync(h).filter(n => {
      try { return fs.statSync(path.join(h, n)).isDirectory(); } catch { return false; }
    });
    wsCount = wsList.length;
    for (const ws of wsList) {
      const idx = path.join(h, ws, 'index.json');
      if (fs.existsSync(idx)) {
        try {
          const j = JSON.parse(fs.readFileSync(idx, 'utf8'));
          convCount += (j.conversations || []).length;
        } catch {}
      }
    }
  } catch {}
  console.log(`${uid.slice(0,8)}  history: ${wsCount} 工作区, ${convCount} 会话, ${s.files} 文件, ${(s.bytes/1024/1024).toFixed(1)} MB`);
}

// 3. 最大的单个 messages 目录
console.log('\n===== 最大的 messages 目录（Top 5）=====');
const msgDirs = [];
function findMsgDirs(root, depth = 0) {
  if (depth > 6) return;
  let names;
  try { names = fs.readdirSync(root); } catch { return; }
  for (const n of names) {
    const p = path.join(root, n);
    let st;
    try { st = fs.statSync(p); } catch { continue; }
    if (!st.isDirectory()) continue;
    if (n === 'messages') {
      const s = countDir(p, 1);
      msgDirs.push({ path: p, files: s.files, bytes: s.bytes });
    } else {
      findMsgDirs(p, depth + 1);
    }
  }
}
for (const uid of fs.readdirSync(DATA)) {
  const h = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history');
  if (fs.existsSync(h)) findMsgDirs(h);
}
msgDirs.sort((a, b) => b.bytes - a.bytes);
for (const m of msgDirs.slice(0, 5)) {
  console.log(`${m.files} 文件, ${(m.bytes/1024).toFixed(0)} KB  ${m.path.replace(DATA, '')}`);
}

// 4. 最大的单个 message 文件
console.log('\n===== 最大的 message 文件（Top 10）=====');
const files = [];
function findMsgFiles(root, depth = 0) {
  if (depth > 8) return;
  let names;
  try { names = fs.readdirSync(root); } catch { return; }
  for (const n of names) {
    const p = path.join(root, n);
    let st;
    try { st = fs.statSync(p); } catch { continue; }
    if (st.isDirectory()) findMsgFiles(p, depth + 1);
    else if (n.endsWith('.json') && p.includes('messages')) files.push({ path: p, bytes: st.size });
  }
}
for (const uid of fs.readdirSync(DATA)) {
  const h = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history');
  if (fs.existsSync(h)) findMsgFiles(h);
}
files.sort((a, b) => b.bytes - a.bytes);
for (const f of files.slice(0, 10)) {
  console.log(`${(f.bytes/1024).toFixed(0)} KB  ${f.path.replace(DATA, '')}`);
}

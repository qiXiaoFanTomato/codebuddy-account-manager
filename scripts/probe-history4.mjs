import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

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
      else out.push(p);
    }
  }
  return out;
}

function normalize(p, uid) { return p.split(uid).join('<UID>'); }

const maps = {};
for (const uid of uids) {
  const root = path.join(DATA, uid);
  const map = new Map();
  for (const p of allFiles(root)) map.set(normalize(path.relative(root, p), uid), p);
  maps[uid] = map;
  console.log(uid.slice(0, 8) + ' 归一化文件数: ' + map.size);
}

const a = maps[uids[0]], b = maps[uids[1]];
const onlyA = [...a.keys()].filter(k => !b.has(k));
const onlyB = [...b.keys()].filter(k => !a.has(k));
console.log('归一化后 仅A: ' + onlyA.length + '，仅B: ' + onlyB.length);
console.log('仅A示例: ' + onlyA.slice(0, 4).join(' | '));
console.log('仅B示例: ' + onlyB.slice(0, 4).join(' | '));

let same = 0, diff = 0;
const diffSamples = [];
let checked = 0;
for (const [k, pa] of a) {
  const pb = b.get(k);
  if (!pb) continue;
  checked++;
  if (checked % 50 === 0) console.error('checked ' + checked);
  const ha = crypto.createHash('sha1').update(fs.readFileSync(pa)).digest('hex');
  const hb = crypto.createHash('sha1').update(fs.readFileSync(pb)).digest('hex');
  if (ha === hb) same++; else { diff++; if (diffSamples.length < 5) diffSamples.push(k); }
}
console.log('共有文件: same=' + same + ', diff=' + diff);
console.log('内容不同示例: ' + diffSamples.join(' | '));

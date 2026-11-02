import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');
const uids = ['ab6f110b-0e24-451b-91c3-c077944f33d0', '13e50313-2703-4e53-a147-a39e76fc75eb'];

for (const uid of uids) {
  const d = path.join(DATA, uid);
  const histDir = path.join(d, 'CodeBuddyIDE', uid, 'history');
  console.log('===== ' + uid.slice(0, 8) + ' =====');
  console.log('账号数据目录 stat:');
  const st = fs.lstatSync(d);
  console.log('  isSymbolicLink=' + st.isSymbolicLink() + ' ino=' + st.ino + ' dev=' + st.dev);
  try {
    const real = fs.realpathSync(d);
    console.log('  realpath=' + real);
  } catch (e) { console.log('  realpath err'); }

  if (fs.existsSync(histDir)) {
    const st2 = fs.lstatSync(histDir);
    console.log('history stat: ino=' + st2.ino + ' dev=' + st2.dev + ' isSymlink=' + st2.isSymbolicLink());
  }
  console.log('');
}

// 对比一个具体文件的 hash 与 inode
const rel = '2eeab5120c5095a8b7d0975a337c65ec/current.json';
for (const uid of uids) {
  const p = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history', rel);
  const st = fs.statSync(p);
  const hash = crypto.createHash('sha1').update(fs.readFileSync(p)).digest('hex').slice(0, 16);
  console.log(uid.slice(0, 8) + ' current.json  ino=' + st.ino + ' size=' + st.size + ' sha1=' + hash);
}

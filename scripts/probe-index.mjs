import fs from 'node:fs';
import path from 'node:path';

const DATA = path.join(process.env.LOCALAPPDATA, 'CodeBuddyExtension', 'Data');
const uid = 'ab6f110b-0e24-451b-91c3-c077944f33d0';
const p = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history', '5a3bfb9222a61be65652da18772713f4', 'index.json');
const txt = fs.readFileSync(p, 'utf8');
console.log('size=' + txt.length);
console.log(txt.slice(0, 1500));

// 找根目录下是否有总索引
const rootIdx = path.join(DATA, uid, 'CodeBuddyIDE', uid, 'history', 'index.json');
console.log('\n根索引存在: ' + fs.existsSync(rootIdx));

import fs from 'node:fs';

const p = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\options\secret-storage.xml`;
const xml = fs.readFileSync(p, 'utf8');

// 提取所有 Entry 的 key
const re = /<Entry\s+key="([^"]+)"/g;
let m;
const keys = [];
while ((m = re.exec(xml)) !== null) keys.push(m[1]);

console.log('总条目: ' + keys.length);
console.log('\n=== 非 chatInputDraft 的条目 ===');
keys.filter(k => !k.startsWith('chatInputDraft:')).forEach(k => console.log('  ' + k));

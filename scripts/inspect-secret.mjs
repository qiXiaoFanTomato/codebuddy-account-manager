import fs from 'node:fs';

const p = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\options\secret-storage.xml`;
const xml = fs.readFileSync(p, 'utf8');

// 提取所有 Entry
const re = /<Entry\s+key="([^"]+)"\s+value="([^"]*)"\s*\/>/g;
let m, count = 0;
const keys = [];
while ((m = re.exec(xml)) !== null) {
  count++;
  const key = m[1];
  keys.push(key);
  if (/coding-copilot|codebuddy|Tencent/i.test(key)) {
    const rawVal = m[2]
      .replace(/&quot;/g, '"')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&apos;/g, "'");
    console.log('===== KEY: ' + key + ' =====');
    console.log('value 长度: ' + rawVal.length);
    try {
      const j = JSON.parse(rawVal);
      console.log('  顶层字段: ' + Object.keys(j).join(', '));
      if (j.account) console.log('  account.uid=' + j.account.uid + ' nickname=' + j.account.nickname + ' uin=' + j.account.uin);
      if (j.auth) console.log('  auth.accessToken 长度=' + (j.auth.accessToken || '').length + ' domain=' + j.auth.domain);
    } catch (e) {
      console.log('  非 JSON: ' + rawVal.slice(0, 100));
    }
    console.log('');
  }
}
console.log('总 Entry 数: ' + count);
console.log('CodeBuddy 相关 key:');
keys.filter(k => /coding-copilot|codebuddy|Tencent/i.test(k)).forEach(k => console.log('  ' + k));

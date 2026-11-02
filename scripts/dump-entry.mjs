import fs from 'node:fs';

const p = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\options\secret-storage.xml`;
const xml = fs.readFileSync(p, 'utf8');
const re = /<Entry\s+key="Tencent-Cloud\.coding-copilot\.new\.accessToken"\s+value="([^"]*)"\s*\/>/;
const m = re.exec(xml);
if (!m) { console.log('未找到条目'); process.exit(0); }
const rawVal = m[1].replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&apos;/g, "'");
const j = JSON.parse(rawVal);

function brief(o, depth = 0, key = '') {
  const pad = '  '.repeat(depth);
  if (o === null) return pad + key + ': null';
  if (Array.isArray(o)) return pad + key + ': Array(' + o.length + ')';
  if (typeof o === 'object') {
    const lines = [pad + key + ': {'];
    for (const [k, v] of Object.entries(o)) {
      if (/token|secret/i.test(k) && typeof v === 'string') {
        lines.push(pad + '  ' + k + ': "' + v.slice(0, 10) + '…"(len=' + v.length + ')');
      } else if (typeof v === 'object' && v !== null && depth < 2) {
        lines.push(brief(v, depth + 1, k));
      } else {
        lines.push(pad + '  ' + k + ': ' + (typeof v === 'string' ? '"' + v.slice(0, 40) + '"' : v));
      }
    }
    lines.push(pad + '}');
    return lines.join('\n');
  }
  return pad + key + ': ' + (typeof o === 'string' ? '"' + o.slice(0, 60) + '"' : o);
}

console.log(brief(j, 0, 'root'));

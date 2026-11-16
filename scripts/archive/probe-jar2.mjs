import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const PLUGIN = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\j${Date.now()}`;
fs.mkdirSync(tmp, { recursive: true });

const kws = ['nativeSwitchAccount', 'nativeGetSession', 'genie.auth.', 'IDE_HOST_AUTHENTICATION_INIT'];
const out = [];

const jars = ['instrumented-code-icc-extensions-4.12.41911495.jar', 'searchableOptions-4.12.41911495.jar'];
for (const name of jars) {
  const jar = path.join(PLUGIN, 'lib', name);
  if (!fs.existsSync(jar)) { out.push('[缺失] ' + name); continue; }
  const zip = path.join(tmp, name + '.zip');
  const dest = path.join(tmp, name + '.dir');
  fs.copyFileSync(jar, zip);
  try {
    execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force"`, { stdio: 'pipe' });
  } catch (e) {
    out.push('[解压失败] ' + name);
    continue;
  }
  function walk(dir) {
    for (const n of fs.readdirSync(dir)) {
      const p = path.join(dir, n);
      const st = fs.statSync(p);
      if (st.isDirectory()) walk(p);
      else if (n.endsWith('.class')) {
        const buf = fs.readFileSync(p);
        for (const kw of kws) {
          if (buf.includes(Buffer.from(kw, 'utf8'))) out.push(name + ' :: ' + path.relative(dest, p) + ' <= ' + kw);
        }
      }
    }
  }
  walk(dest);
}
console.log(out.join('\n') || '无命中');
console.log('\n总命中: ' + out.length);

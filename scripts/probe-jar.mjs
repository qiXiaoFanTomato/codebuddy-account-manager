import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const JAR = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\lib\instrumented-code-icc-extensions-4.12.41911495.jar`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\jar-extract`;
const zipCopy = String.raw`D:\Code\My Code\codeBuddy-User\tmp\plugin.zip`;

fs.rmSync(String.raw`D:\Code\My Code\codeBuddy-User\tmp`, { recursive: true, force: true });
fs.mkdirSync(path.dirname(zipCopy), { recursive: true });
fs.copyFileSync(JAR, zipCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${tmp}' -Force"`, { stdio: 'pipe' });

const kws = ['genie.auth', 'nativeLogin', 'nativeGetSession', 'nativeSwitchAccount', 'IDE_HOST_AUTHENTICATION_INIT', 'switchAccount', 'nativeGetLoginUrl', 'nativeLogout'];
const hits = new Map();

function walk(dir) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walk(p);
    else if (name.endsWith('.class')) {
      const buf = fs.readFileSync(p);
      const rel = path.relative(tmp, p);
      for (const kw of kws) {
        if (buf.includes(Buffer.from(kw, 'utf8'))) {
          if (!hits.has(rel)) hits.set(rel, new Set());
          hits.get(rel).add(kw);
        }
      }
    }
  }
}
walk(tmp);
for (const [f, ks] of hits) console.log(f + '  <=  ' + [...ks].join(', '));
console.log('\n匹配文件数: ' + hits.size);

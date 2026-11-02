import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const PLUGIN = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\all-extract`;
fs.rmSync(String.raw`D:\Code\My Code\codeBuddy-User\tmp`, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });

const kws = ['nativeSwitchAccount', 'nativeGetSession', 'genie.auth', 'IDE_HOST_AUTHENTICATION_INIT', 'switchAccount'];

function scanBuf(buf, label, out) {
  for (const kw of kws) {
    if (buf.includes(Buffer.from(kw, 'utf8'))) out.push(label + '  <=  ' + kw);
  }
}

function walkAndScan(dir, out) {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    const st = fs.statSync(p);
    if (st.isDirectory()) walkAndScan(p, out);
    else {
      try { scanBuf(fs.readFileSync(p), path.relative(PLUGIN, p), out); } catch {}
    }
  }
}

// 1) 扫描 vsix/extension 下纯文本/JS（跳过超大 map 以免慢）
// 2) 扫描 lib 下每个 jar 内 class
const libDir = path.join(PLUGIN, 'lib');
const out = [];
for (const name of fs.readdirSync(libDir)) {
  if (!name.endsWith('.jar')) continue;
  const jar = path.join(libDir, name);
  const zip = path.join(tmp, name + '.zip');
  fs.copyFileSync(jar, zip);
  const dest = path.join(tmp, name + '.dir');
  try {
    execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zip}' -DestinationPath '${dest}' -Force"`, { stdio: 'pipe' });
    walkAndScan(dest, out);
  } catch (e) {
    out.push('[解压失败] ' + name);
  }
}

console.log(out.join('\n'));
console.log('\n总命中: ' + out.length);

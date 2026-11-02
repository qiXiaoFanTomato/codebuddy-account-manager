import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const zip = String.raw`D:\Code\My Code\codeBuddy-User\build\distributions\codebuddy-account-manager-0.3.0.zip`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\zipcheck`;
fs.mkdirSync(tmp, { recursive: true });
const zipCopy = path.join(tmp, 'check.zip');
fs.copyFileSync(zip, zipCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${tmp}\ext' -Force"`, { stdio: 'pipe' });

// 找 lib 里的 jar
const ext = path.join(tmp, 'ext');
const libDir = path.join(ext, 'codebuddy-account-manager', 'lib');
if (!fs.existsSync(libDir)) {
  console.log('lib 目录不存在，列出 ext:', fs.readdirSync(ext));
  process.exit(0);
}
const jars = fs.readdirSync(libDir).filter(n => n.endsWith('.jar'));
console.log('jar:', jars.join(', '));

// 解压 jar 找 class
const jar = path.join(libDir, jars[0]);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${jar}' -DestinationPath '${tmp}\jar' -Force"`, { stdio: 'pipe' });

const classes = ['AccountCard.class', 'AccountCardList.class', 'QuotaAlertSettings.class'];
for (const c of classes) {
  const p = path.join(tmp, 'jar', 'dev', 'codebuddy', 'account', 'ui', c);
  const p2 = path.join(tmp, 'jar', 'dev', 'codebuddy', 'account', 'service', c);
  console.log(c + ' (ui): ' + fs.existsSync(p));
  if (c.includes('Settings')) console.log(c + ' (service): ' + fs.existsSync(p2));
}

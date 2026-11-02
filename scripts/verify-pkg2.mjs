import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const zip = String.raw`D:\Code\My Code\codeBuddy-User\build\distributions\codebuddy-account-manager-0.3.1.zip`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\verify3`;
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });

const zipCopy = path.join(tmp, 'pkg.zip');
fs.copyFileSync(zip, zipCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${tmp}\\outer' -Force"`, { stdio: 'pipe' });

const libDir = path.join(tmp, 'outer', 'codebuddy-account-manager', 'lib');
const jars = fs.readdirSync(libDir).filter(n => n.startsWith('codebuddy-account-manager') && n.endsWith('.jar') && !n.includes('searchable'));
const jarPath = path.join(libDir, jars[0]);
const jarCopy = path.join(tmp, 'main.zip');
fs.copyFileSync(jarPath, jarCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${jarCopy}' -DestinationPath '${tmp}\\inner' -Force"`, { stdio: 'pipe' });

const pluginXml = fs.readFileSync(path.join(tmp, 'inner', 'META-INF', 'plugin.xml'), 'utf8');
console.log('版本: ' + (pluginXml.match(/<version>(.*?)<\/version>/) || [])[1]);

const classPath = path.join(tmp, 'inner', 'dev', 'codebuddy', 'account', 'ui', 'AccountPanel.class');
const text = fs.readFileSync(classPath).toString('utf8');
console.log('=== AccountPanel.class ===');
for (const s of ['刷新额度', 'AccountCardList', 'JBTable']) {
  console.log((text.includes(s) ? '  包含: ' : '  不含: ') + s);
}

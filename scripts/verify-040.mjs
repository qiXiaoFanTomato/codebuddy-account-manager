import fs from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';

const zip = String.raw`D:\Code\My Code\codeBuddy-User\build\distributions\codebuddy-account-manager-0.4.0.zip`;
if (!fs.existsSync(zip)) { console.log('产物不存在'); process.exit(1); }
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\v040`;
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });
const zipCopy = path.join(tmp, 'pkg.zip');
fs.copyFileSync(zip, zipCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${tmp}\\outer' -Force"`, { stdio: 'pipe' });

const libDir = path.join(tmp, 'outer', 'codebuddy-account-manager', 'lib');
const jars = fs.readdirSync(libDir).filter(n => n.startsWith('codebuddy-account-manager') && n.endsWith('.jar') && !n.includes('searchable'));
const jarCopy = path.join(tmp, 'main.zip');
fs.copyFileSync(path.join(libDir, jars[0]), jarCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${jarCopy}' -DestinationPath '${tmp}\\inner' -Force"`, { stdio: 'pipe' });

const pluginXml = fs.readFileSync(path.join(tmp, 'inner', 'META-INF', 'plugin.xml'), 'utf8');
console.log('版本: ' + (pluginXml.match(/<version>(.*?)<\/version>/) || [])[1]);

const checks = [
  ['dev/codebuddy/account/credential/SecretStorage.class', 'SecretStorage'],
  ['dev/codebuddy/account/service/PendingSwitch.class', 'PendingSwitch'],
  ['dev/codebuddy/account/ui/AccountCard.class', 'AccountCard'],
];
for (const [rel, label] of checks) {
  console.log(label + ': ' + fs.existsSync(path.join(tmp, 'inner', rel)));
}

const cls = fs.readFileSync(path.join(tmp, 'inner', 'dev', 'codebuddy', 'account', 'credential', 'SecretStorage.class')).toString('utf8');
console.log('含 ENTRY_KEY 常量: ' + cls.includes('Tencent-Cloud.coding-copilot.new.accessToken'));

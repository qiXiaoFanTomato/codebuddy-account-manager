import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

// 不依赖外部库，用 PowerShell 解压两层
import { execSync } from 'node:child_process';

const zip = String.raw`D:\Code\My Code\codeBuddy-User\build\distributions\codebuddy-account-manager-0.3.0.zip`;
const tmp = String.raw`D:\Code\My Code\codeBuddy-User\tmp\verify2`;
fs.rmSync(tmp, { recursive: true, force: true });
fs.mkdirSync(tmp, { recursive: true });

const zipCopy = path.join(tmp, 'pkg.zip');
fs.copyFileSync(zip, zipCopy);

execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${zipCopy}' -DestinationPath '${tmp}\\outer' -Force"`, { stdio: 'pipe' });

const libDir = path.join(tmp, 'outer', 'codebuddy-account-manager', 'lib');
const jars = fs.readdirSync(libDir).filter(n => n.startsWith('codebuddy-account-manager') && n.endsWith('.jar') && !n.includes('searchable'));
console.log('主 jar: ' + jars[0]);

const jarPath = path.join(libDir, jars[0]);
const jarCopy = path.join(tmp, 'main.zip');
fs.copyFileSync(jarPath, jarCopy);
execSync(`powershell -NoProfile -Command "Expand-Archive -LiteralPath '${jarCopy}' -DestinationPath '${tmp}\\inner' -Force"`, { stdio: 'pipe' });

const classPath = path.join(tmp, 'inner', 'dev', 'codebuddy', 'account', 'ui', 'AccountPanel.class');
if (!fs.existsSync(classPath)) {
  console.log('AccountPanel.class 不存在！');
} else {
  const text = fs.readFileSync(classPath).toString('utf8');
  console.log('=== AccountPanel.class 字符串检查 ===');
  for (const s of ['刷新额度', '切换到选中账号', 'AccountCardList', 'JBTable', '回滚', '导出']) {
    console.log((text.includes(s) ? '  包含: ' : '  不含: ') + s);
  }
}

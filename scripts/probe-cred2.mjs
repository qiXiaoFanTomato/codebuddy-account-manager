import fs from 'node:fs';
import path from 'node:path';

const EXT = String.raw`C:\Users\qfang\AppData\Roaming\JetBrains\IntelliJIdea2026.2\plugins\coding-copilot-jetbrains\vsix\extension`;
const files = [
  path.join(EXT, 'out', 'extension', 'index.js'),
];

const kws = [
  'workbuddy-desktop',
  'desktop.info',
  'Public\\auth',
  'Public/auth',
  'authDir',
  'authDirPath',
  'credentialPath',
  'credentials',
  'PasswordSafe',
  'CredentialStore',
  'getPassword',
  'setPassword',
  'auth.json',
  'session.json',
  'IDE_HOST_AUTHENTICATION',
];

for (const p of files) {
  if (!fs.existsSync(p)) continue;
  const txt = fs.readFileSync(p, 'utf8');
  console.log('===== ' + path.basename(p) + ' =====');
  for (const kw of kws) {
    let idx = 0, found = 0;
    while ((idx = txt.indexOf(kw, idx)) !== -1 && found < 2) {
      const start = Math.max(0, idx - 120);
      const end = Math.min(txt.length, idx + kw.length + 180);
      console.log('[' + kw + '] ' + txt.slice(start, end).replace(/\n/g, ' '));
      idx += kw.length; found++;
    }
    if (found === 0) console.log('[' + kw + '] —');
  }
}

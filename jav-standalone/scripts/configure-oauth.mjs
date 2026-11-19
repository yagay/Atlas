// The Android project is generated in CI; register both callbacks on MainActivity.
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const dir = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.resolve(dir, '../android/app/src/main/AndroidManifest.xml');
let manifest = readFileSync(manifestPath, 'utf8');

if (!manifest.includes('android:name=".MainActivity"') || !manifest.includes('</activity>')) {
  throw new Error('Missing Android MainActivity in generated manifest');
}

for (const scheme of ['javsearch', 'atlas']) {
  if (manifest.includes('android:scheme="' + scheme + '"')) continue;
  const filter = '            <intent-filter>\n' +
    '                <action android:name="android.intent.action.VIEW" />\n' +
    '                <category android:name="android.intent.category.DEFAULT" />\n' +
    '                <category android:name="android.intent.category.BROWSABLE" />\n' +
    '                <data android:scheme="' + scheme + '" android:host="auth" android:path="/callback" />\n' +
    '            </intent-filter>\n';
  manifest = manifest.replace('</activity>', filter + '        </activity>');
}
writeFileSync(manifestPath, manifest);
for (const scheme of ['javsearch', 'atlas']) {
  if (!manifest.includes('android:scheme="' + scheme + '"')) throw new Error('Missing OAuth scheme ' + scheme);
}
console.log('[javsearch] registered javsearch://auth/callback and legacy atlas://auth/callback');

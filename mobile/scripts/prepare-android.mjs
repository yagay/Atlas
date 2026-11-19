// 在 `npx cap add android` 之后运行：注入版本号，并按环境变量启用 release 签名。
// android/ 不入库、每次由模板生成，因此版本与签名配置只能在这里补齐。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const mobileDir = path.resolve(scriptDir, '..');
const repoRoot = path.resolve(mobileDir, '..');
const gradlePath = path.join(mobileDir, 'android/app/build.gradle');

const { version } = JSON.parse(fs.readFileSync(path.join(repoRoot, 'package.json'), 'utf8'));
const match = /^(\d+)\.(\d+)\.(\d+)/.exec(version);
if (!match) throw new Error(`无法从根 package.json 解析版本号: ${version}`);

const major = Number(match[1]);
const minor = Number(match[2]);
const patch = Number(match[3]);
// Android 要求 versionCode 单调递增：主 * 10000 + 次 * 100 + 修订
const versionCode = major * 10000 + minor * 100 + patch;

let gradle = fs.readFileSync(gradlePath, 'utf8');

gradle = gradle.replace(/versionCode\s+\d+/, `versionCode ${versionCode}`);
gradle = gradle.replace(/versionName\s+"[^"]*"/, `versionName "${version}"`);

const hasSigning = Boolean(
  process.env.ANDROID_KEYSTORE_PATH &&
  process.env.ANDROID_KEYSTORE_PASSWORD &&
  process.env.ANDROID_KEY_ALIAS &&
  process.env.ANDROID_KEY_PASSWORD
);

// 幂等：重复执行时不再插入第二份签名配置
if (hasSigning && !gradle.includes('signingConfigs {')) {
  const signingConfigs = `    signingConfigs {
        release {
            storeFile file(System.getenv("ANDROID_KEYSTORE_PATH"))
            storePassword System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias System.getenv("ANDROID_KEY_ALIAS")
            keyPassword System.getenv("ANDROID_KEY_PASSWORD")
        }
    }
`;
  gradle = gradle.replace(/(\n\s*buildTypes\s*\{)/, `\n${signingConfigs}$1`);
  gradle = gradle.replace(
    /(release\s*\{\s*\n)(\s*)minifyEnabled false/,
    `$1$2signingConfig signingConfigs.release\n$2minifyEnabled false`
  );
}

fs.writeFileSync(gradlePath, gradle);
console.log(`[android] versionName=${version} versionCode=${versionCode} signed=${hasSigning}`);

// OAuth 深链回跳：系统浏览器授权完成后用 atlas://auth/callback 唤起 App。
// Android 工程不入库，这里在生成的 Manifest 中注入自定义 scheme（幂等）。
const manifestPath = path.join(mobileDir, 'android/app/src/main/AndroidManifest.xml');
let manifest = fs.readFileSync(manifestPath, 'utf8');
if (!manifest.includes('android:scheme="atlas"')) {
  const deepLinkFilter = `            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <category android:name="android.intent.category.BROWSABLE" />
                <data android:scheme="atlas" android:host="auth" />
            </intent-filter>
        </activity>`;
  manifest = manifest.replace('</activity>', deepLinkFilter);
  fs.writeFileSync(manifestPath, manifest);
  console.log('[android] injected atlas://auth/callback deep link');
}


/**
 * Android 项目在每次 CI 中动态生成，原生悬浮服务与 Capacitor 插件
 * 必须在 prepare-android 阶段注入，不能依赖被 gitignore 的 android/。
 */
const javaRoot = path.join(mobileDir, 'android/app/src/main/java/uk/wort/atlas');
const overlayJavaDir = path.join(javaRoot, 'overlay');
fs.mkdirSync(overlayJavaDir, { recursive: true });
for (const file of ['AtlasOverlayPlugin.java', 'AtlasOverlayService.java', 'OverlaySearchClient.java']) {
  fs.copyFileSync(path.join(mobileDir, 'native-overlay', file), path.join(overlayJavaDir, file));
}
const mainActivityFile = path.join(javaRoot, 'MainActivity.java');
let mainActivity = fs.readFileSync(mainActivityFile, 'utf8');
if (!mainActivity.includes('registerPlugin(uk.wort.atlas.overlay.AtlasOverlayPlugin.class)')) {
  mainActivity = mainActivity.replace(
    /public class MainActivity extends BridgeActivity\s*\{/,
    `public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(uk.wort.atlas.overlay.AtlasOverlayPlugin.class);
        super.onCreate(savedInstanceState);
    }`
  );
}
if (!mainActivity.includes('registerPlugin(uk.wort.atlas.overlay.AtlasOverlayPlugin.class)')) {
  throw new Error('Failed to register native AtlasOverlayPlugin in MainActivity');
}
fs.writeFileSync(mainActivityFile, mainActivity);

let overlayManifest = fs.readFileSync(manifestPath, 'utf8');
for (const permission of [
  'android.permission.SYSTEM_ALERT_WINDOW',
  'android.permission.FOREGROUND_SERVICE',
  'android.permission.FOREGROUND_SERVICE_SPECIAL_USE',
]) {
  if (!overlayManifest.includes(`android:name="${permission}"`)) {
    overlayManifest = overlayManifest.replace(
      '<application',
      `<uses-permission android:name="${permission}" />\n    <application`
    );
  }
}
if (!overlayManifest.includes('uk.wort.atlas.overlay.AtlasOverlayService')) {
  const service = `        <service
            android:name="uk.wort.atlas.overlay.AtlasOverlayService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="User initiated floating search bubble to show inline search results" />
        </service>
`;
  overlayManifest = overlayManifest.replace('</application>', service + '    </application>');
}
if (!overlayManifest.includes('android.permission.SYSTEM_ALERT_WINDOW') ||
    !overlayManifest.includes('android:foregroundServiceType="specialUse"')) {
  throw new Error('Failed to register overlay permissions and foreground service');
}
fs.writeFileSync(manifestPath, overlayManifest);
console.log('[android] Atlas native JAV overlay bridge, foreground service, and permissions installed');

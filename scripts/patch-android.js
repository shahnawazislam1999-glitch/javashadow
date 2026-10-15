// Patches android/app/build.gradle for CI (version code/name + optional release signing).
// Safe to run multiple times.
const fs = require('fs');
const path = require('path');

const file = path.join(process.cwd(), 'android', 'app', 'build.gradle');
if (!fs.existsSync(file)) {
  console.error('Not found: ' + file + ' (run "npx cap add android" / "npx cap sync android" first)');
  process.exit(1);
}
let src = fs.readFileSync(file, 'utf8');

// 1. Remove anything we added on a previous run, and the old versionCode/versionName lines.
src = src.replace(/^def ciVersionCode.*\r?\n/gm, '');
src = src.replace(/^def ciVersionName.*\r?\n/gm, '');
src = src.replace(/^[ \t]*versionCode\s.*\r?\n/gm, '');
src = src.replace(/^[ \t]*versionName\s.*\r?\n/gm, '');

// 2. Define the variables ABOVE the android { } block (plain Groovy, no parsing ambiguity).
const defs =
  "def ciVersionCode = Integer.parseInt(System.getenv('VERSION_CODE') ?: '1')\n" +
  "def ciVersionName = System.getenv('VERSION_NAME') ?: '1.0'\n\n";
const androidIdx = src.search(/^android\s*\{/m);
if (androidIdx === -1) {
  console.error('Could not find "android {" block in build.gradle');
  process.exit(1);
}
src = src.slice(0, androidIdx) + defs + src.slice(androidIdx);

// 3. Use the variables inside defaultConfig.
if (!/defaultConfig\s*\{/.test(src)) {
  console.error('Could not find defaultConfig { } in build.gradle');
  process.exit(1);
}
src = src.replace(
  /defaultConfig\s*\{/,
  'defaultConfig {\n        versionCode ciVersionCode\n        versionName ciVersionName'
);

// 4. Optional release signing from env (only if KEYSTORE_PATH is provided).
if (process.env.KEYSTORE_PATH && !src.includes('ciRelease')) {
  const signing =
    '    signingConfigs {\n' +
    '        ciRelease {\n' +
    "            storeFile file(System.getenv('KEYSTORE_PATH'))\n" +
    "            storePassword System.getenv('KEYSTORE_PASSWORD')\n" +
    "            keyAlias System.getenv('KEY_ALIAS')\n" +
    "            keyPassword System.getenv('KEY_PASSWORD')\n" +
    '        }\n' +
    '    }\n';
  src = src.replace(/android\s*\{/, (m) => m + '\n' + signing);
  src = src.replace(/buildTypes\s*\{\s*release\s*\{/, (m) => m + '\n            signingConfig signingConfigs.ciRelease');
}

fs.writeFileSync(file, src);
console.log('Patched ' + file + ' (VERSION_CODE=' + (process.env.VERSION_CODE || '1') + ')');

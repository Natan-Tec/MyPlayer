// Gera a pasta portátil do Windows (M3UFlow.exe) em dist/.
const m = require('@electron/packager');
const packager = typeof m === 'function' ? m : m.packager || m.default;

packager({
  dir: '.',
  name: 'M3UFlow',
  platform: 'win32',
  arch: 'x64',
  out: 'dist',
  overwrite: true,
  asar: true,
  icon: 'build/icon.ico',
  appVersion: '1.0.0',
  win32metadata: { ProductName: 'M3UFlow', FileDescription: 'M3UFlow' },
  ignore: [/^\/test($|\/)/, /^\/dist($|\/)/, /^\/scripts($|\/)/, /^\/\.gitignore$/, /^\/LEIA-ME\.txt$/, /^\/smoke\.txt$/, /^\/.*\.zip$/],
})
  .then((paths) => console.log('Gerado em:', paths.join(', ')))
  .catch((e) => { console.error(e && e.stack ? e.stack : e); process.exit(1); });

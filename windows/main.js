'use strict';
const { app, BrowserWindow, ipcMain, dialog, session, Menu, net, clipboard, powerSaveBlocker } = require('electron');
const path = require('path');
const fs = require('fs');
const Core = require('./core.js');

const LOGOS_URL = 'https://iptv-org.github.io/api/logos.json';
const LOGO_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;

app.setName('M3UFlow');
if (process.env.M3UFLOW_SMOKE) app.disableHardwareAcceleration(); // máquinas de teste não têm placa de vídeo

// Cabeçalhos e certificado da tentativa de reprodução em andamento (definidos pela interface).
let stream = { ua: null, headers: {}, insecure: false };
let win = null;
let keepAwakeId = null;

const dir = (...p) => path.join(app.getPath('userData'), ...p);
const ensureDir = (d) => fs.mkdirSync(d, { recursive: true });

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (win) {
      if (win.isMinimized()) win.restore();
      win.focus();
    }
  });
}

// ---------- Janela ----------
function createWindow() {
  Menu.setApplicationMenu(null);
  win = new BrowserWindow({
    width: 1280,
    height: 720,
    minWidth: 960,
    minHeight: 540,
    backgroundColor: '#0B5C9E',
    title: 'M3UFlow',
    icon: path.join(__dirname, 'build', 'icon.png'),
    autoHideMenuBar: true,
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      // A interface só abre arquivos do próprio app; sem isso o navegador bloquearia (CORS) os canais.
      webSecurity: false,
      autoplayPolicy: 'no-user-gesture-required',
      backgroundThrottling: false,
    },
  });
  win.once('ready-to-show', () => win.show());
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  win.on('closed', () => { win = null; });

  if (process.env.M3UFLOW_SMOKE) runSmokeTest(win);
}

// Cabeçalhos pedidos pela lista (User-Agent, Referer, Origin...) valem só para os pedidos de vídeo.
function setupRequestHooks() {
  const ses = session.defaultSession;
  ses.webRequest.onBeforeSendHeaders({ urls: ['http://*/*', 'https://*/*'] }, (details, cb) => {
    const h = details.requestHeaders;
    if (details.resourceType === 'xhr' || details.resourceType === 'media') {
      const set = (k, v) => {
        for (const key of Object.keys(h)) if (key.toLowerCase() === k.toLowerCase()) delete h[key];
        h[k] = v;
      };
      const drop = (k) => { for (const key of Object.keys(h)) if (key.toLowerCase() === k.toLowerCase()) delete h[key]; };
      if (stream.ua) set('User-Agent', stream.ua);
      drop('Origin'); // a página é um arquivo local ("null"); sem Origin, como no app do celular
      for (const [k, v] of Object.entries(stream.headers || {})) set(k, v);
    }
    cb({ requestHeaders: h });
  });
}

// Certificado inválido só é aceito na tentativa em que o usuário permitiu.
app.on('certificate-error', (event, _wc, _url, _error, _cert, callback) => {
  if (stream.insecure) {
    event.preventDefault();
    callback(true);
  } else {
    callback(false);
  }
});

// ---------- Arquivos ----------
const statePath = () => dir('state.json');

function readJson(file, fallback) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch (e) { return fallback; }
}

function writeAtomic(file, text) {
  ensureDir(path.dirname(file));
  const tmp = file + '.tmp';
  fs.writeFileSync(tmp, text);
  fs.renameSync(tmp, file);
}

function dirSize(p) {
  let total = 0;
  try {
    const st = fs.statSync(p);
    if (st.isFile()) return st.size;
    for (const f of fs.readdirSync(p)) total += dirSize(path.join(p, f));
  } catch (e) { /* não existe */ }
  return total;
}

const listCache = (id) => dir('lists', id + '.m3u');
const listLocal = (id) => dir('local_lists', id + '.m3u');
const logoIndexFile = () => dir('logo_index.json');

async function fetchText(url, timeoutMs, ua) {
  const res = await net.fetch(url, {
    headers: { 'User-Agent': ua || Core.USER_AGENT },
    redirect: 'follow',
    signal: AbortSignal.timeout(timeoutMs),
  });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  return res.text();
}

function errMsg(e) {
  const m = (e && e.message) || String(e);
  if (/abort|timeout/i.test(m)) return 'tempo esgotado';
  return m.replace(/^net::/, '');
}

// ---------- Comunicação com a interface ----------
function registerIpc() {
  ipcMain.handle('app:info', () => ({ version: app.getVersion(), data: app.getPath('userData') }));

  ipcMain.handle('state:get', () => readJson(statePath(), {}));
  ipcMain.handle('state:set', (_e, obj) => { writeAtomic(statePath(), JSON.stringify(obj)); return true; });

  ipcMain.handle('stream:set', (_e, s) => {
    stream = { ua: s && s.ua ? String(s.ua) : null, headers: (s && s.headers) || {}, insecure: !!(s && s.insecure) };
    return true;
  });

  ipcMain.handle('playlist:download', async (_e, { id, url }) => {
    try {
      const text = await fetchText(url, 30000);
      writeAtomic(listCache(id), text);
      return { ok: true, text };
    } catch (e) {
      return { ok: false, error: errMsg(e) };
    }
  });

  ipcMain.handle('playlist:cached', (_e, { id, local }) => {
    const f = local ? listLocal(id) : listCache(id);
    try {
      const st = fs.statSync(f);
      return { text: fs.readFileSync(f, 'utf8'), mtime: st.mtimeMs };
    } catch (e) {
      return null;
    }
  });

  ipcMain.handle('playlist:pickLocal', async () => {
    const r = await dialog.showOpenDialog(win, {
      title: 'Escolher lista M3U',
      properties: ['openFile'],
      filters: [{ name: 'Listas M3U', extensions: ['m3u', 'm3u8', 'txt'] }, { name: 'Todos os arquivos', extensions: ['*'] }],
    });
    if (r.canceled || !r.filePaths.length) return null;
    const file = r.filePaths[0];
    try {
      return { name: path.basename(file), text: fs.readFileSync(file, 'utf8') };
    } catch (e) {
      return { error: errMsg(e) };
    }
  });

  ipcMain.handle('playlist:saveLocal', (_e, { id, text }) => { writeAtomic(listLocal(id), text); return true; });

  ipcMain.handle('playlist:remove', (_e, id) => {
    for (const f of [listCache(id), listLocal(id)]) { try { fs.unlinkSync(f); } catch (e) { /* já não existe */ } }
    return true;
  });

  ipcMain.handle('playlist:dropCache', (_e, id) => { try { fs.unlinkSync(listCache(id)); } catch (e) { /* ok */ } return true; });

  // Índice de capas (logos) do iptv-org: baixa uma vez e renova a cada 30 dias.
  ipcMain.handle('logos:load', async () => {
    const f = logoIndexFile();
    let stale = true;
    try { stale = Date.now() - fs.statSync(f).mtimeMs > LOGO_MAX_AGE_MS; } catch (e) { /* não existe */ }
    if (stale) {
      try {
        const entries = JSON.parse(await fetchText(LOGOS_URL, 60000));
        writeAtomic(f, JSON.stringify(Core.buildLogoIndex(entries)));
      } catch (e) {
        if (!fs.existsSync(f)) return null;
      }
    }
    return readJson(f, null);
  });

  ipcMain.handle('storage:usage', () => {
    const lists = dirSize(dir('lists'));
    const local = dirSize(dir('local_lists'));
    const logos = dirSize(logoIndexFile());
    return { lists, local, logos, total: lists + local + logos };
  });

  ipcMain.handle('cache:clear', async () => {
    fs.rmSync(dir('lists'), { recursive: true, force: true });
    try { fs.unlinkSync(logoIndexFile()); } catch (e) { /* ok */ }
    try { await session.defaultSession.clearCache(); } catch (e) { /* ok */ }
    return true;
  });

  ipcMain.handle('clipboard:read', () => clipboard.readText());

  ipcMain.handle('win:toggleFullscreen', () => {
    if (win) win.setFullScreen(!win.isFullScreen());
    return win ? win.isFullScreen() : false;
  });
  ipcMain.handle('win:isFullscreen', () => (win ? win.isFullScreen() : false));
  ipcMain.handle('win:exitFullscreen', () => { if (win && win.isFullScreen()) win.setFullScreen(false); return true; });

  ipcMain.handle('power:keepAwake', (_e, on) => {
    if (on && keepAwakeId === null) keepAwakeId = powerSaveBlocker.start('prevent-display-sleep');
    if (!on && keepAwakeId !== null) { powerSaveBlocker.stop(keepAwakeId); keepAwakeId = null; }
    return true;
  });
}

// ---------- Teste rápido de abertura (usado só pelo build automático) ----------
function runSmokeTest(w) {
  const out = path.join(process.env.M3UFLOW_SMOKE, 'smoke.txt');
  const finish = (ok, text) => {
    try { fs.writeFileSync(out, (ok ? 'OK ' : 'FALHOU ') + text); } catch (e) { /* ignora */ }
    app.exit(ok ? 0 : 1);
  };
  setTimeout(() => finish(false, 'tempo esgotado'), 45000);
  w.webContents.on('did-fail-load', (_e, code, desc) => finish(false, 'did-fail-load ' + code + ' ' + desc));
  w.webContents.on('render-process-gone', (_e, d) => finish(false, 'render-process-gone ' + d.reason));
  w.webContents.once('did-finish-load', async () => {
    try {
      await new Promise((r) => setTimeout(r, 2500));
      const info = await w.webContents.executeJavaScript(
        'JSON.stringify({core: typeof Core, hls: typeof Hls, mpegts: typeof mpegts, view: window.__view && window.__view(), err: window.__errors || []})'
      );
      const parsed = JSON.parse(info);
      const good = parsed.core === 'object' && parsed.hls !== 'undefined' && parsed.mpegts !== 'undefined' && !parsed.err.length;
      finish(good, info);
    } catch (e) {
      finish(false, 'erro: ' + e.message);
    }
  });
}

app.whenReady().then(() => {
  registerIpc();
  setupRequestHooks();
  createWindow();
  app.on('activate', () => { if (!BrowserWindow.getAllWindows().length) createWindow(); });
});

app.on('window-all-closed', () => app.quit());

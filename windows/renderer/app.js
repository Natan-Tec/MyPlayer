/* M3UFlow para Windows — interface e player. */
(function () {
  'use strict';

  window.__errors = [];
  window.addEventListener('error', (e) => window.__errors.push(String(e.message)));
  window.addEventListener('unhandledrejection', (e) => window.__errors.push(String(e.reason)));

  const $ = (id) => document.getElementById(id);
  const api = window.api;
  const video = $('video');

  // ---------- Dados guardados ----------
  const S = { lists: [], activeId: null, lastUrl: null, res: {}, group: null, insecure: false, volume: 1 };
  let saveTimer = null;
  function save() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(() => api.stateSet(S), 400);
  }

  const activeList = () => S.lists.find((l) => l.id === S.activeId) || S.lists[0] || null;
  const isLocal = (p) => p.url.startsWith('local:');
  const whereOf = (p) => (isLocal(p) ? 'Arquivo local · ' + p.url.slice(6) : p.url);
  const newId = () => Math.random().toString(36).slice(2, 10);

  // ---------- Utilitários ----------
  function el(tag, cls, text) {
    const e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }
  const norm = (s) => s.normalize('NFD').replace(/\p{Mn}+/gu, '').toLowerCase();
  const fmtBytes = (b) => (b >= 1048576 ? (b / 1048576).toFixed(1).replace('.', ',') + ' MB' : b >= 1024 ? Math.round(b / 1024) + ' KB' : '0 KB');

  let toastTimer = null;
  function toast(text) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { t.hidden = true; }, 3500);
  }

  function updateClock() {
    const t = new Date().toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    $('clockTop').textContent = t;
    $('clockPlayer').textContent = t;
  }

  // ---------- Telas ----------
  let current = 'splash';
  let settingsFrom = 'home';
  window.__view = () => current;

  function showView(name) {
    current = name;
    for (const v of ['splash', 'home', 'channels', 'settings', 'player']) $('v-' + v).hidden = v !== name;
    const top = name === 'home' || name === 'channels' || name === 'settings';
    $('topbar').hidden = !top;
    $('navHome').hidden = name === 'home';
    $('navSettings').hidden = name === 'settings';
    document.body.classList.toggle('player-active', name === 'player');
  }

  function focusEl(e) {
    if (!e) return;
    e.focus({ preventScroll: true });
    e.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  function goHome() {
    showView('home');
    focusEl($('tLive'));
  }

  function goSettings() {
    if (current === 'channels' || current === 'home') settingsFrom = current;
    showView('settings');
    renderSettings();
  }

  function back() {
    if (current === 'channels') goHome();
    else if (current === 'settings') (settingsFrom === 'channels' && all.length ? enterChannels(false) : goHome());
  }

  // ---------- Navegação por teclado (setas) ----------
  const visible = (e) => e.getClientRects().length > 0;

  function spatial(dir, cur, root) {
    const cr = cur.getBoundingClientRect();
    const cx = cr.left + cr.width / 2;
    const cy = cr.top + cr.height / 2;
    let best = null;
    let bestScore = Infinity;
    for (const e of root.querySelectorAll('[data-nav]')) {
      if (e === cur || e.classList.contains('card') || e.disabled || !visible(e)) continue;
      const r = e.getBoundingClientRect();
      const dx = r.left + r.width / 2 - cx;
      const dy = r.top + r.height / 2 - cy;
      let main;
      let cross;
      if (dir === 'right') { if (dx <= 4) continue; main = dx; cross = Math.abs(dy); }
      else if (dir === 'left') { if (dx >= -4) continue; main = -dx; cross = Math.abs(dy); }
      else if (dir === 'up') { if (dy >= -4) continue; main = -dy; cross = Math.abs(dx); }
      else { if (dy <= 4) continue; main = dy; cross = Math.abs(dx); }
      const score = main + cross * 2;
      if (score < bestScore) { bestScore = score; best = e; }
    }
    return best;
  }

  function viewRoot() {
    return document.body;
  }

  function onArrow(dir, e) {
    const cur = document.activeElement;
    const root = dialogEl ? dialogEl : viewRoot();
    if (!cur || cur === document.body || !cur.matches('[data-nav]')) {
      e.preventDefault();
      const first = [...root.querySelectorAll('[data-nav]')].find((x) => visible(x));
      focusEl(first);
      return;
    }
    e.preventDefault();
    if (!dialogEl && cur.classList.contains('card')) return gridArrow(dir, cur);
    if (!dialogEl && cur.classList.contains('cat') && dir === 'right') return focusGrid();
    if (!dialogEl && (cur.id === 'search' || cur.id === 'lastBtn') && dir === 'down') return focusGrid();
    if (!dialogEl && cur.id === 'search' && dir === 'left') return focusEl(catsEl.querySelector('.cat.selected') || catsEl.querySelector('.cat'));
    const next = spatial(dir, cur, root);
    if (next) focusEl(next);
  }

  // ---------- Diálogos ----------
  let dialogEl = null;
  let dialogPrevFocus = null;

  function closeDialog() {
    if (!dialogEl) return;
    const host = $('dialogHost');
    host.textContent = '';
    dialogEl = null;
    if (dialogPrevFocus && document.contains(dialogPrevFocus)) focusEl(dialogPrevFocus);
    dialogPrevFocus = null;
  }

  /** spec: { title, message, fields:[{id,label,hint,value}], options:[{label,sub,onClick}], buttons:[{label,primary,keep,onClick}] } */
  function openDialog(spec) {
    closeDialog();
    dialogPrevFocus = document.activeElement;
    const back = el('div', 'dlg-back');
    const box = el('div', 'dlg glass');
    box.appendChild(el('h3', null, spec.title));
    if (spec.message) box.appendChild(el('div', 'm', spec.message));
    const inputs = {};
    for (const f of spec.fields || []) {
      if (f.label) box.appendChild(el('label', null, f.label));
      const inp = el('input');
      inp.type = 'text';
      inp.placeholder = f.hint || '';
      inp.value = f.value || '';
      inp.spellcheck = false;
      inp.setAttribute('data-nav', '');
      inputs[f.id] = inp;
      box.appendChild(inp);
    }
    for (const o of spec.options || []) {
      const b = el('button', 'opt');
      b.setAttribute('data-nav', '');
      b.appendChild(el('b', null, o.label));
      if (o.sub) b.appendChild(el('small', null, o.sub));
      b.addEventListener('click', () => { closeDialog(); o.onClick(); });
      box.appendChild(b);
    }
    const btns = el('div', 'btns');
    const values = () => Object.fromEntries(Object.entries(inputs).map(([k, v]) => [k, v.value]));
    let primaryBtn = null;
    for (const bd of spec.buttons || []) {
      const b = el('button', 'btn' + (bd.primary ? ' primary' : ''), bd.label);
      b.setAttribute('data-nav', '');
      b.addEventListener('click', async () => {
        const r = bd.onClick ? await bd.onClick(values()) : undefined;
        if (r !== false && !bd.keep) closeDialog();
      });
      if (bd.primary) primaryBtn = b;
      btns.appendChild(b);
    }
    box.appendChild(btns);
    for (const inp of Object.values(inputs)) {
      inp.addEventListener('keydown', (e) => { if (e.key === 'Enter' && primaryBtn) { e.preventDefault(); primaryBtn.click(); } });
    }
    back.appendChild(box);
    back.addEventListener('mousedown', (e) => { if (e.target === back) closeDialog(); });
    $('dialogHost').appendChild(back);
    dialogEl = back;
    const first = box.querySelector('input, .opt, .btn');
    focusEl(first);
    if (first && first.tagName === 'INPUT') first.select();
    return { inputs };
  }

  // ---------- Listas ----------
  let all = [];
  let filtered = [];
  let loadedFor = null;
  let loadToken = 0;
  let query = '';
  let focusUrl = null;
  let firstFocusDone = false;
  let logoMap = null;
  const logoMemo = new Map();

  const catsEl = $('cats');
  const gridEl = $('grid');

  function logoFor(ch) {
    if (!logoMap) return null;
    const k = ch.displayName;
    if (logoMemo.has(k)) return logoMemo.get(k);
    const v = Core.lookupLogo(logoMap, ch.displayName) || Core.lookupLogo(logoMap, ch.name) || null;
    logoMemo.set(k, v);
    return v;
  }

  function showMsg(text) {
    const m = $('msg');
    m.hidden = text == null;
    if (text != null) { m.textContent = text; $('spin').hidden = true; }
  }

  async function addListFlow() {
    openDialog({
      title: 'Adicionar Lista:',
      options: [
        { label: 'URL', sub: 'Colar o link da lista (http:// ou https://)', onClick: addUrlDialog },
        { label: 'Local', sub: 'Escolher um arquivo .m3u guardado neste computador', onClick: addLocalFlow },
      ],
      buttons: [{ label: 'Cancelar' }],
    });
  }

  const isUrl = (u) => /^https?:\/\//i.test(u);

  async function addUrlDialog() {
    let clip = '';
    try { clip = (await api.readClipboard() || '').trim(); } catch (e) { /* sem área de transferência */ }
    openDialog({
      title: 'Adicionar Lista:',
      fields: [{ id: 'url', hint: 'Cole o link aqui', value: isUrl(clip) ? clip : '' }],
      buttons: [
        { label: 'Cancelar' },
        {
          label: 'Salvar', primary: true,
          onClick: ({ url }) => {
            url = url.trim();
            if (!isUrl(url)) { toast('Digite um link que comece com http:// ou https://'); return false; }
            const p = { id: newId(), name: Core.suggestName(url), url };
            S.lists.push(p);
            S.activeId = p.id;
            loadedFor = null;
            save();
            toast('Lista adicionada: ' + p.name);
            enterChannels(true);
          },
        },
      ],
    });
  }

  async function addLocalFlow() {
    const r = await api.pickLocal();
    if (!r) return;
    if (r.error) return toast('Não foi possível usar o arquivo: ' + r.error);
    let count = 0;
    try { count = Core.parseM3u(r.text).length; } catch (e) { count = 0; }
    if (!count) return toast('Nenhum canal encontrado no arquivo');
    const id = newId();
    await api.saveLocal({ id, text: r.text });
    const p = { id, name: r.name.replace(/\.[^.]*$/, '').trim() || 'Minha lista', url: 'local:' + r.name };
    S.lists.push(p);
    S.activeId = p.id;
    loadedFor = null;
    save();
    toast('Lista adicionada: ' + p.name + ' (' + count + ' canais)');
    enterChannels(true);
  }

  function listActions(p) {
    const isActive = activeList() && activeList().id === p.id;
    const options = [];
    if (!isActive) {
      options.push({ label: 'Usar esta lista', sub: 'É a lista que abre em TV AO VIVO', onClick: () => { S.activeId = p.id; loadedFor = null; save(); toast('Lista ativa: ' + p.name); renderSettings(); } });
    }
    options.push({ label: 'Atualizar agora', sub: isLocal(p) ? 'Lê o arquivo guardado de novo' : 'Baixa a lista de novo', onClick: () => refreshList(p) });
    options.push({ label: 'Editar', sub: isLocal(p) ? 'Mudar o nome' : 'Mudar o nome ou o link', onClick: () => editList(p) });
    options.push({ label: 'Apagar', sub: 'Remove a lista do app', onClick: () => confirmDelete(p) });
    openDialog({ title: p.name, message: whereOf(p), options, buttons: [{ label: 'Fechar' }] });
  }

  function editList(p) {
    const fields = [{ id: 'name', label: 'Nome', hint: 'Nome da lista', value: p.name }];
    if (!isLocal(p)) fields.push({ id: 'url', label: 'Link', hint: 'http://.../lista.m3u', value: p.url });
    openDialog({
      title: 'Editar lista:',
      fields,
      buttons: [
        { label: 'Cancelar' },
        {
          label: 'Salvar', primary: true,
          onClick: ({ name, url }) => {
            if (!isLocal(p)) {
              url = (url || '').trim();
              if (!isUrl(url)) { toast('Digite um link que comece com http:// ou https://'); return false; }
              if (url !== p.url) { p.url = url; api.dropCache(p.id); }
              p.name = name.trim() || Core.suggestName(url);
            } else {
              p.name = name.trim() || p.name;
            }
            loadedFor = null;
            save();
            renderSettings();
          },
        },
      ],
    });
  }

  function confirmDelete(p) {
    openDialog({
      title: 'Apagar lista?',
      message: '"' + p.name + '" será removida do app. Isso não apaga nada na internet.',
      buttons: [
        { label: 'Cancelar' },
        {
          label: 'Apagar', primary: true,
          onClick: () => {
            S.lists = S.lists.filter((l) => l.id !== p.id);
            if (S.activeId === p.id) S.activeId = S.lists.length ? S.lists[0].id : null;
            api.removeList(p.id);
            loadedFor = null;
            all = [];
            save();
            renderSettings();
          },
        },
      ],
    });
  }

  async function refreshList(p) {
    toast('Atualizando a lista…');
    const cached = isLocal(p) ? await api.cached({ id: p.id, local: true }) : null;
    if (isLocal(p)) {
      if (!cached) return toast('O arquivo da lista não está mais no app. Adicione de novo.');
      toast('Lista atualizada: ' + Core.parseM3u(cached.text).length + ' canais');
    } else {
      const r = await api.download({ id: p.id, url: p.url });
      if (!r.ok) return toast('Não foi possível atualizar: ' + r.error);
      toast('Lista atualizada: ' + Core.parseM3u(r.text).length + ' canais');
    }
    loadedFor = null;
    refreshStorage();
  }

  // ---------- Canais ----------
  async function enterChannels(force) {
    showView('channels');
    const pl = activeList();
    if (!pl) {
      all = [];
      renderCats([]);
      gridEl.textContent = '';
      $('status').textContent = '';
      $('lastBtn').hidden = true;
      showMsg('Nenhuma lista adicionada.\nVolte à tela inicial e escolha ADICIONAR LISTA.');
      return;
    }
    if (loadedFor === pl.id && all.length && !force) {
      showMsg(null);
      updateSuggestion();
      refreshBadges();
      restoreFocus();
      return;
    }
    const my = ++loadToken;
    all = [];
    gridEl.textContent = '';
    catsEl.textContent = '';
    $('lastBtn').hidden = true;
    $('status').textContent = pl.name;
    showMsg(null);
    $('spin').hidden = false;
    firstFocusDone = false;

    const cached = await api.cached({ id: pl.id, local: isLocal(pl) });
    if (my !== loadToken) return;
    let shown = false;
    if (cached) { showChannels(Core.parseM3u(cached.text), pl); shown = true; }
    if (isLocal(pl) && !cached) {
      showMsg('O arquivo da lista não está mais no app.\nApague e adicione de novo.');
      return;
    }
    const stale = !isLocal(pl) && (!cached || Date.now() - cached.mtime > 12 * 3600 * 1000);
    if (stale) {
      const r = await api.download({ id: pl.id, url: pl.url });
      if (my !== loadToken) return;
      if (r.ok) {
        const fresh = Core.parseM3u(r.text);
        if (!shown || fresh.length !== all.length) showChannels(fresh, pl);
      } else if (!shown) {
        showMsg('Não foi possível baixar a lista:\n' + r.error + '\nConfira a internet e o link em Configurações.');
      }
    }
    $('spin').hidden = true;
  }

  function showChannels(channels, pl) {
    $('spin').hidden = true;
    all = channels;
    loadedFor = pl.id;
    if (!channels.length) { showMsg('Nenhum canal encontrado nesta lista.\nConfira o link em Configurações.'); renderCats([]); gridEl.textContent = ''; return; }
    showMsg(null);
    const collator = new Intl.Collator('pt-BR');
    const counts = new Map();
    for (const ch of channels) for (const g of Core.GroupNames.split(ch.group)) counts.set(g, (counts.get(g) || 0) + 1);
    const names = [...counts.keys()].sort((a, b) => collator.compare(a, b));
    groups = names.filter((n) => n !== Core.GroupNames.NONE).concat(names.filter((n) => n === Core.GroupNames.NONE));
    groupCounts = counts;
    if (S.group && !groups.includes(S.group)) S.group = null;
    renderCats();
    applyFilter();
    updateSuggestion();
    if (!firstFocusDone) {
      firstFocusDone = true;
      if (!$('lastBtn').hidden) focusEl($('lastBtn')); else focusGrid();
    }
  }

  let groups = [];
  let groupCounts = new Map();

  function renderCats() {
    catsEl.textContent = '';
    if (!all.length) return;
    const add = (key, label, count) => {
      const b = el('button', 'cat', label + ' (' + count + ')');
      b.setAttribute('data-nav', '');
      b.dataset.key = key == null ? '' : key;
      if ((S.group || null) === key) b.classList.add('selected');
      catsEl.appendChild(b);
    };
    add(null, 'Todas', all.length);
    for (const g of groups) add(g, g, groupCounts.get(g) || 0);
  }

  catsEl.addEventListener('click', (e) => {
    const b = e.target.closest('.cat');
    if (!b) return;
    S.group = b.dataset.key || null;
    save();
    for (const c of catsEl.children) c.classList.toggle('selected', c === b);
    applyFilter();
  });

  function applyFilter() {
    const q = norm(query.trim());
    filtered = all.filter((ch) => {
      if (S.group && !Core.GroupNames.split(ch.group).includes(S.group)) return false;
      if (!q) return true;
      if (ch._n === undefined) ch._n = norm(ch.name);
      return ch._n.includes(q);
    });
    renderGrid();
    gridEl.scrollTop = 0;
    $('status').textContent = q ? filtered.length + ' canais • busca: ' + query.trim() : S.group ? S.group + ' • ' + filtered.length + ' canais' : filtered.length + ' canais';
    if (!filtered.length && all.length) showMsg('Nenhum canal encontrado.'); else if (all.length) showMsg(null);
  }

  const initialsOf = (name) => {
    const parts = name.trim().split(/[^\p{L}\p{N}]+/u).filter(Boolean);
    if (!parts.length) return '?';
    return (parts.length === 1 ? parts[0].slice(0, 2) : parts[0].slice(0, 1) + parts[1].slice(0, 1)).toUpperCase();
  };

  function badgeFor(ch) {
    const h = S.res[Core.hashKey(ch.url)] || ch.nameResolution;
    return h ? Core.resolutionLabel(h) : null;
  }

  function bindLogo(card, ch) {
    const area = card.querySelector('.logo-area');
    const old = area.querySelector('img');
    if (old) old.remove();
    const initials = area.querySelector('.initials');
    initials.hidden = false;
    const primary = /^https?:/i.test(ch.logo || '') ? ch.logo : null;
    const fallback = logoFor(ch);
    const first = primary || fallback;
    card.dataset.hasLogo = '';
    if (!first) return;
    const img = new Image();
    img.decoding = 'async';
    img.loading = 'lazy';
    img.alt = '';
    img.addEventListener('load', () => { initials.hidden = true; card.dataset.hasLogo = '1'; });
    img.addEventListener('error', () => {
      if (img.dataset.tried) { img.remove(); return; }
      img.dataset.tried = '1';
      if (primary && fallback && fallback !== primary) img.src = fallback; else img.remove();
    });
    img.src = first;
    area.insertBefore(img, initials);
  }

  function renderGrid() {
    const frag = document.createDocumentFragment();
    filtered.forEach((ch, i) => {
      const card = el('button', 'card');
      card.setAttribute('data-nav', '');
      card.dataset.i = i;
      const area = el('div', 'logo-area');
      area.appendChild(el('div', 'initials', initialsOf(ch.displayName)));
      const badge = el('div', 'badge');
      badge.hidden = true;
      area.appendChild(badge);
      card.appendChild(area);
      card.appendChild(el('div', 'name', ch.displayName));
      card.title = ch.displayName;
      frag.appendChild(card);
      bindLogo(card, ch);
      const lb = badgeFor(ch);
      if (lb) { badge.textContent = lb; badge.hidden = false; }
    });
    gridEl.textContent = '';
    gridEl.appendChild(frag);
  }

  function refreshBadges() {
    const cards = gridEl.children;
    for (let i = 0; i < cards.length && i < filtered.length; i++) {
      const b = cards[i].querySelector('.badge');
      const lb = badgeFor(filtered[i]);
      b.hidden = !lb;
      if (lb) b.textContent = lb;
    }
  }

  function refreshLogos() {
    const cards = gridEl.children;
    for (let i = 0; i < cards.length && i < filtered.length; i++) {
      if (cards[i].dataset.hasLogo !== '1' && !cards[i].querySelector('img')) bindLogo(cards[i], filtered[i]);
    }
  }

  gridEl.addEventListener('click', (e) => {
    const card = e.target.closest('.card');
    if (card) openPlayer(filtered[Number(card.dataset.i)]);
  });

  function gridColumns() {
    return Math.max(1, getComputedStyle(gridEl).gridTemplateColumns.split(' ').length);
  }

  function focusGrid() {
    const cards = gridEl.children;
    if (!cards.length) { focusEl($('search')); return; }
    const pos = focusUrl ? filtered.findIndex((c) => c.url === focusUrl) : -1;
    focusEl(cards[pos >= 0 ? pos : 0]);
  }

  function restoreFocus() {
    if (!focusUrl) return;
    const pos = filtered.findIndex((c) => c.url === focusUrl);
    if (pos >= 0 && gridEl.children[pos]) focusEl(gridEl.children[pos]);
  }

  function gridArrow(dir, cur) {
    const i = Number(cur.dataset.i);
    const cols = gridColumns();
    const n = gridEl.children.length;
    let target = null;
    if (dir === 'right') target = i + 1 < n ? i + 1 : null;
    else if (dir === 'left') {
      if (i % cols === 0) return focusEl(catsEl.querySelector('.cat.selected') || catsEl.querySelector('.cat'));
      target = i - 1;
    } else if (dir === 'down') target = i + cols < n ? i + cols : null;
    else if (dir === 'up') {
      if (i - cols < 0) return focusEl($('lastBtn').hidden ? $('search') : $('lastBtn'));
      target = i - cols;
    }
    if (target != null) focusEl(gridEl.children[target]);
  }

  $('search').addEventListener('input', (e) => { query = e.target.value; applyFilter(); });
  $('search').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); focusGrid(); }
    if (e.key === 'Escape') { e.stopPropagation(); if ($('search').value) { $('search').value = ''; query = ''; applyFilter(); } else { $('search').blur(); focusGrid(); } e.preventDefault(); }
  });

  function updateSuggestion() {
    const ch = S.lastUrl ? all.find((c) => c.url === S.lastUrl) : null;
    const b = $('lastBtn');
    if (!ch) { b.hidden = true; return; }
    b.textContent = '▶  Continuar assistindo: ' + ch.displayName;
    b.hidden = false;
  }
  $('lastBtn').addEventListener('click', () => {
    const ch = all.find((c) => c.url === S.lastUrl);
    if (ch) openPlayer(ch, true);
  });

  // ---------- Configurações ----------
  let storageSub = null;

  function section(box, title) { box.appendChild(el('h2', null, title)); }
  function note(box, text) { box.appendChild(el('div', 'note', text)); }
  function row(box, { t, s, v, onClick }) {
    const r = el(onClick ? 'button' : 'div', 'row' + (onClick ? ' act' : ''));
    const l = el('div', 'l');
    l.appendChild(el('div', 't', t));
    const sub = el('div', 's', s || '');
    sub.hidden = !s;
    l.appendChild(sub);
    r.appendChild(l);
    const val = el('div', 'v', v || '');
    val.hidden = !v;
    r.appendChild(val);
    if (onClick) { r.setAttribute('data-nav', ''); r.addEventListener('click', onClick); }
    box.appendChild(r);
    return { r, sub, val };
  }

  async function renderSettings() {
    const box = $('settingsBox');
    box.textContent = '';
    box.appendChild(el('h1', null, 'Configurações'));

    section(box, 'LISTAS');
    const active = activeList();
    if (!S.lists.length) note(box, 'Nenhuma lista adicionada.');
    for (const p of S.lists) row(box, { t: p.name, s: (active && active.id === p.id ? 'Lista ativa  •  ' : '') + whereOf(p), onClick: () => listActions(p) });
    row(box, { t: 'Adicionar lista', s: 'Por link (URL) ou arquivo .m3u deste computador', onClick: addListFlow });

    section(box, 'ARMAZENAMENTO');
    storageSub = row(box, { t: 'Uso do app', s: 'Calculando…' }).sub;
    row(box, { t: 'Limpar cache', s: 'Apaga as listas baixadas e o índice de capas (listas de arquivo ficam)', onClick: clearCache });
    refreshStorage();

    section(box, 'REPRODUÇÃO');
    const ssl = row(box, {
      t: 'Aceitar certificados inválidos',
      s: 'Como última tentativa, o canal abre mesmo com certificado HTTPS vencido ou inválido. Menos seguro: ligue só se canais https falham.',
      v: S.insecure ? 'Ligado' : 'Desligado',
      onClick: () => { S.insecure = !S.insecure; save(); ssl.val.textContent = S.insecure ? 'Ligado' : 'Desligado'; },
    });
    note(box, 'Teclas no player: ↑ ↓ trocam de canal • ← → volume • M mudo • F ou F11 tela cheia • Esc volta.');

    section(box, 'SOBRE');
    const info = await api.info();
    row(box, { t: 'Versão instalada', s: 'M3UFlow para Windows ' + info.version });
    note(box, 'Versão única: não se atualiza sozinha. Seus dados ficam em ' + info.data);

    focusEl(box.querySelector('[data-nav]'));
  }

  async function refreshStorage() {
    const u = await api.storageUsage();
    if (storageSub) storageSub.textContent = 'Total ' + fmtBytes(u.total) + '  •  Listas ' + fmtBytes(u.lists + u.local) + '  •  Capas ' + fmtBytes(u.logos);
  }

  async function clearCache() {
    await api.clearCache();
    logoMap = null;
    logoMemo.clear();
    loadedFor = null;
    toast('Cache limpo');
    refreshStorage();
  }

  // ---------- Player ----------
  const P = { list: [], index: 0, attempts: [], ai: 0, netRetries: 0, failed: false, token: 0, hls: null, ts: null, dead: new Set(), stall: null, restart: null, uiTimer: null, height: 0, mode: '', mediaRecovered: false };

  function openPlayer(ch, fromSuggestion) {
    if (!ch) return;
    let list = filtered;
    let index = list.findIndex((c) => c.url === ch.url);
    if (index < 0) { list = all; index = Math.max(0, all.findIndex((c) => c.url === ch.url)); }
    focusUrl = fromSuggestion && list === all ? null : ch.url;
    P.list = list;
    P.index = index;
    showView('player');
    api.keepAwake(true);
    video.volume = S.volume == null ? 1 : S.volume;
    playCurrent();
  }

  function leavePlayer() {
    P.token++;
    clearTimeout(P.stall); clearTimeout(P.restart); clearTimeout(P.uiTimer);
    destroyEngines();
    api.keepAwake(false);
    showView('channels');
    updateSuggestion();
    refreshBadges();
    restoreFocus();
  }

  function playCurrent() {
    const ch = P.list[P.index];
    S.lastUrl = ch.url;
    save();
    clearTimeout(P.restart); clearTimeout(P.stall);
    P.attempts = Core.buildAttempts(ch, { allowInsecure: S.insecure });
    P.ai = 0; P.netRetries = 0; P.failed = false; P.height = 0; P.mediaRecovered = false; P.dead = new Set();
    $('pTitle').textContent = ch.displayName;
    updateSub();
    setInfo('');
    showUI();
    startAttempt();
  }

  function updateSub() {
    const ch = P.list[P.index];
    const res = P.height > 0 ? Core.resolutionLabel(P.height) : null;
    $('pSub').textContent = [Core.GroupNames.display(ch.group), res].filter(Boolean).join('  •  ');
  }

  function setInfo(t) { const e = $('pInfo'); e.textContent = t; e.hidden = !t; }
  function loading(on) { $('loading').hidden = !on; }

  function showUI(persist) {
    const v = $('v-player');
    v.classList.add('show-ui');
    clearTimeout(P.uiTimer);
    if (!persist && !P.failed) P.uiTimer = setTimeout(() => v.classList.remove('show-ui'), 3500);
  }

  function destroyEngines() {
    if (P.hls) { try { P.hls.destroy(); } catch (e) { /* já fechado */ } P.hls = null; }
    if (P.ts) { try { P.ts.pause(); P.ts.unload(); P.ts.detachMediaElement(); P.ts.destroy(); } catch (e) { /* já fechado */ } P.ts = null; }
    P.mode = '';
    try { video.pause(); video.removeAttribute('src'); video.load(); } catch (e) { /* ok */ }
  }

  /** Descobre o formato olhando o começo da resposta (sem baixar o vídeo todo). */
  async function sniff(url) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), 12000);
    try {
      const res = await fetch(url, { signal: ctrl.signal, redirect: 'follow', cache: 'no-store' });
      if (!res.ok) { const e = new Error('HTTP ' + res.status); e.status = res.status; throw e; }
      const ct = (res.headers.get('content-type') || '').toLowerCase();
      let first = -1;
      let head = '';
      const reader = res.body.getReader();
      const { value } = await reader.read();
      if (value && value.length) { first = value[0]; head = new TextDecoder().decode(value.subarray(0, 16)); }
      ctrl.abort();
      let mode = 'ts';
      if (head.startsWith('#EXTM3U') || ct.includes('mpegurl')) mode = 'hls';
      else if (first === 0x47 || ct.includes('mp2t')) mode = 'ts';
      else if (ct.startsWith('video/mp4') || ct.includes('webm') || ct.includes('ogg')) mode = 'native';
      return { mode, url: res.url || url };
    } finally {
      clearTimeout(timer);
    }
  }

  async function startAttempt() {
    const my = ++P.token;
    clearTimeout(P.stall);
    destroyEngines();
    loading(true);
    const a = P.attempts[P.ai];
    if (!a) return;
    await api.setStream({ ua: a.ua, headers: a.headers, insecure: a.insecure });
    if (my !== P.token) return;
    P.stall = setTimeout(() => onStalled(my), 25000);
    let mode = a.mode;
    let url = a.url;
    if (mode === 'auto') {
      try {
        const r = await sniff(url);
        if (my !== P.token) return;
        mode = r.mode;
        url = r.url;
      } catch (e) {
        if (my !== P.token) return;
        return handleError(my, { status: e.status || null, network: !e.status, detail: e.message });
      }
    }
    if (mode === 'hls') startHls(my, url);
    else if (mode === 'ts') startTs(my, url);
    else startNative(my, url);
  }

  function startHls(my, url) {
    if (!window.Hls || !Hls.isSupported()) return handleError(my, { detail: 'HLS indisponível' });
    P.mode = 'hls';
    const hls = new Hls({
      lowLatencyMode: false, enableWorker: true, maxBufferLength: 30, liveSyncDurationCount: 3,
      manifestLoadingTimeOut: 12000, levelLoadingTimeOut: 12000, fragLoadingTimeOut: 20000,
      manifestLoadingMaxRetry: 1, levelLoadingMaxRetry: 2, fragLoadingMaxRetry: 3,
    });
    P.hls = hls;
    hls.on(Hls.Events.ERROR, (ev, d) => {
      if (my !== P.token || !d.fatal) return;
      const status = d.response && d.response.code ? d.response.code : null;
      if (d.type === Hls.ErrorTypes.MEDIA_ERROR && !P.mediaRecovered) { P.mediaRecovered = true; hls.recoverMediaError(); return; }
      const glitch = !status && /LoadError|LoadTimeOut/i.test(d.details || '');
      handleError(my, { status, network: glitch, decode: d.type === Hls.ErrorTypes.MEDIA_ERROR, detail: d.details });
    });
    hls.on(Hls.Events.MANIFEST_PARSED, () => { video.play().catch(() => {}); });
    hls.loadSource(url);
    hls.attachMedia(video);
  }

  function startTs(my, url) {
    if (!window.mpegts || !mpegts.isSupported()) return handleError(my, { detail: 'MPEG-TS indisponível' });
    P.mode = 'ts';
    const p = mpegts.createPlayer({ type: 'mpegts', isLive: true, url }, {
      enableWorker: true, lazyLoad: false, autoCleanupSourceBuffer: true,
      liveBufferLatencyChasing: true, liveBufferLatencyMaxLatency: 10, liveBufferLatencyMinRemain: 2,
    });
    P.ts = p;
    p.attachMediaElement(video);
    p.on(mpegts.Events.ERROR, (type, detail, info) => {
      if (my !== P.token) return;
      const status = info && info.code >= 100 ? info.code : null;
      handleError(my, { status, network: type === mpegts.ErrorTypes.NETWORK_ERROR && !status, decode: type === mpegts.ErrorTypes.MEDIA_ERROR, detail });
    });
    p.load();
    const pr = p.play();
    if (pr && pr.catch) pr.catch(() => {});
  }

  function startNative(my, url) {
    P.mode = 'native';
    video.src = url;
    video.play().catch(() => {});
  }

  function handleError(my, e) {
    if (my !== P.token) return;
    clearTimeout(P.stall);
    // Rede instável: repete a mesma tentativa algumas vezes antes de trocar de método.
    if (e.network && !e.status && P.netRetries < 2) {
      P.netRetries++;
      return scheduleRestart(1500, false);
    }
    // Servidor que nem conecta: repetir o mesmo endereço com outra identidade não adianta.
    if (e.network && !e.status && P.attempts[P.ai]) P.dead.add(P.attempts[P.ai].url);
    if (!e.decode && nextAttempt() >= 0) {
      return advance(e.status === 429 || e.status === 509 ? 1500 : 0);
    }
    showFailure(Core.describeError(e));
  }

  function onStalled(my) {
    if (my !== P.token) return;
    if (!video.paused && video.readyState >= 3) return;
    if (nextAttempt() >= 0) advance(0);
    else showFailure('O canal não respondeu a tempo');
  }

  /** Índice da próxima tentativa que ainda vale a pena (ou -1). A de certificado inválido nunca é pulada. */
  function nextAttempt() {
    for (let i = P.ai + 1; i < P.attempts.length; i++) {
      const a = P.attempts[i];
      if (a.insecure || !P.dead.has(a.url)) return i;
    }
    return -1;
  }

  function advance(delay) {
    P.ai = nextAttempt();
    P.netRetries = 0;
    P.mediaRecovered = false;
    scheduleRestart(delay, true);
  }

  function scheduleRestart(delay, newMethod) {
    P.token++;
    destroyEngines();
    loading(true);
    setInfo(newMethod ? 'Tentando outro método (' + (P.ai + 1) + '/' + P.attempts.length + ')…' : 'Reconectando…');
    showUI(true);
    clearTimeout(P.restart);
    P.restart = setTimeout(startAttempt, delay);
  }

  function showFailure(msg) {
    P.failed = true;
    P.token++;
    destroyEngines();
    loading(false);
    setInfo(msg + '\nPressione Enter ou clique no vídeo para tentar de novo, ou troque de canal.');
    showUI(true);
  }

  video.addEventListener('playing', () => {
    clearTimeout(P.stall);
    P.netRetries = 0;
    P.failed = false;
    loading(false);
    setInfo('');
    showUI();
  });
  video.addEventListener('waiting', () => { if (!P.failed && current === 'player') loading(true); });
  const onSize = () => {
    const h = video.videoHeight;
    if (h > 0 && current === 'player') {
      P.height = h;
      S.res[Core.hashKey(P.list[P.index].url)] = h;
      save();
      updateSub();
    }
  };
  video.addEventListener('loadedmetadata', onSize);
  video.addEventListener('resize', onSize);
  video.addEventListener('error', () => {
    if (P.mode !== 'native' || current !== 'player') return;
    const c = video.error ? video.error.code : 0;
    handleError(P.token, { decode: c === 3, detail: 'código ' + c });
  });
  video.addEventListener('volumechange', () => { S.volume = video.volume; save(); });

  function change(delta) {
    const n = P.list.length;
    P.index = (P.index + delta + n) % n;
    focusUrl = P.list[P.index].url;
    playCurrent();
  }

  function retryOrShow() { if (P.failed) playCurrent(); else showUI(); }

  async function toggleFull() {
    const fs = await api.toggleFullscreen();
    $('pFull').textContent = fs ? 'Sair da tela cheia' : 'Tela cheia';
  }

  async function leaveOrExitFull() {
    if (await api.isFullscreen()) { await api.exitFullscreen(); $('pFull').textContent = 'Tela cheia'; } else leavePlayer();
  }

  function playerKey(e) {
    const k = e.key;
    if (k === 'ArrowUp' || k === 'PageUp' || k === 'ChannelUp') { if (!e.repeat) change(1); }
    else if (k === 'ArrowDown' || k === 'PageDown' || k === 'ChannelDown') { if (!e.repeat) change(-1); }
    else if (k === 'ArrowLeft') { video.volume = Math.max(0, video.volume - 0.05); toast('Volume ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'ArrowRight') { video.volume = Math.min(1, video.volume + 0.05); toast('Volume ' + Math.round(video.volume * 100) + '%'); }
    else if (k === 'm' || k === 'M') { video.muted = !video.muted; toast(video.muted ? 'Mudo' : 'Som ligado'); }
    else if (k === 'f' || k === 'F') toggleFull();
    else if (k === 'Escape') leaveOrExitFull();
    else if (k === 'Backspace') leavePlayer();
    else if (k === 'Enter' || k === ' ') retryOrShow();
    else return;
    e.preventDefault();
  }

  $('v-player').addEventListener('mousemove', () => showUI());
  video.addEventListener('click', retryOrShow);
  video.addEventListener('dblclick', toggleFull);
  $('pBack').addEventListener('click', leavePlayer);
  $('pPrev').addEventListener('click', () => change(-1));
  $('pNext').addEventListener('click', () => change(1));
  $('pFull').addEventListener('click', toggleFull);

  // ---------- Teclado geral ----------
  document.addEventListener('keydown', (e) => {
    if (e.key === 'F11') { e.preventDefault(); api.toggleFullscreen(); return; }
    if (e.ctrlKey && (e.key === 'f' || e.key === 'F') && current === 'channels' && !dialogEl) { e.preventDefault(); focusEl($('search')); return; }
    if (e.ctrlKey || e.altKey || e.metaKey) return;
    const inInput = e.target && e.target.tagName === 'INPUT';
    if (dialogEl) {
      if (e.key === 'Escape') { e.preventDefault(); closeDialog(); return; }
      if (e.key.startsWith('Arrow') && !(inInput && (e.key === 'ArrowLeft' || e.key === 'ArrowRight'))) onArrow(e.key.slice(5).toLowerCase(), e);
      return;
    }
    if (current === 'splash') { e.preventDefault(); endSplash(); return; }
    if (current === 'player') return playerKey(e);
    if (e.key === 'Escape' && inInput) return; // dentro de um campo, o próprio campo trata o Esc
    if (e.key === 'Escape' || (e.key === 'Backspace' && !inInput)) { e.preventDefault(); back(); return; }
    if (e.key.startsWith('Arrow') && !(inInput && (e.key === 'ArrowLeft' || e.key === 'ArrowRight'))) onArrow(e.key.slice(5).toLowerCase(), e);
  }, true);

  // Digitar em qualquer lugar da lista de canais começa a busca.
  document.addEventListener('keypress', (e) => {
    if (current !== 'channels' || dialogEl || e.ctrlKey || e.altKey || e.metaKey) return;
    if (e.target && e.target.tagName === 'INPUT') return;
    if (e.key.length === 1 && e.key !== ' ') { const s = $('search'); s.focus(); }
  });

  // ---------- Início ----------
  $('navHome').addEventListener('click', goHome);
  $('navSettings').addEventListener('click', goSettings);
  $('tLive').addEventListener('click', () => {
    if (!S.lists.length) { toast('Primeiro adicione uma lista (link ou arquivo .m3u).'); addListFlow(); } else enterChannels(false);
  });
  $('tAdd').addEventListener('click', addListFlow);
  $('tSettings').addEventListener('click', goSettings);

  let splashDone = false;
  function endSplash() {
    if (splashDone) return;
    splashDone = true;
    goHome();
  }
  $('v-splash').addEventListener('click', endSplash);

  async function init() {
    showView('splash');
    updateClock();
    setInterval(updateClock, 10000);
    try {
      const st = await api.stateGet();
      Object.assign(S, st || {});
      if (!Array.isArray(S.lists)) S.lists = [];
      if (!S.res || typeof S.res !== 'object') S.res = {};
    } catch (e) { /* começa do zero */ }
    setTimeout(endSplash, 1800);
    api.loadLogos().then((map) => {
      if (map) { logoMap = map; logoMemo.clear(); refreshLogos(); }
    }).catch(() => {});
  }

  init();
})();

/*
 * M3UFlow (Windows) — lógica compartilhada, portada do app Android.
 * Funciona no processo principal (require) e na interface (<script>, global "Core").
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.Core = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const USER_AGENT = 'VLC/3.0.20 LibVLC/3.0.20';
  const ALT_USER_AGENTS = [
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'IPTVSmartersPro',
    'Lavf/60.16.100',
  ];

  // ---------- Categorias em português ----------
  const NONE = 'Sem categoria';
  const SEP = ';';
  const GROUP_MAP = {
    animation: 'Animação', auto: 'Automotivo', business: 'Negócios', classic: 'Clássicos',
    comedy: 'Comédia', cooking: 'Culinária', culture: 'Cultura', documentary: 'Documentários',
    education: 'Educação', entertainment: 'Entretenimento', family: 'Família', general: 'Geral',
    interactive: 'Interativo', kids: 'Infantil', legislative: 'Legislativo', lifestyle: 'Estilo de vida',
    movies: 'Filmes', music: 'Música', news: 'Notícias', outdoor: 'Ao ar livre', public: 'Públicos',
    relax: 'Relax', religious: 'Religião', science: 'Ciência', series: 'Séries', shop: 'Compras',
    sports: 'Esportes', travel: 'Viagens', weather: 'Clima', undefined: NONE, 'sem grupo': NONE,
  };

  const GroupNames = {
    NONE,
    SEP,
    translate(raw) {
      const seen = new Set();
      const parts = [];
      for (const p of String(raw || '').split(SEP)) {
        const t = p.trim();
        if (!t) continue;
        const name = Object.prototype.hasOwnProperty.call(GROUP_MAP, t.toLowerCase()) ? GROUP_MAP[t.toLowerCase()] : t;
        if (!seen.has(name)) { seen.add(name); parts.push(name); }
      }
      const out = parts.length > 1 ? parts.filter((x) => x !== NONE) : parts;
      return out.length ? out.join(SEP) : NONE;
    },
    split(group) { return String(group || NONE).split(SEP); },
    display(group) { return String(group || NONE).split(SEP).join(' • '); },
  };

  // ---------- Parser M3U ----------
  const groupRegex = /group-title="([^"]*)"/;
  const logoRegex = /tvg-logo="([^"]*)"/;
  const jsonPairRegex = /"([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"/g;

  function normalizeHeader(key) {
    switch (key.toLowerCase()) {
      case 'user-agent': case 'useragent': case 'http-user-agent': return 'User-Agent';
      case 'referer': case 'referrer': case 'http-referrer': return 'Referer';
      case 'origin': return 'Origin';
      case 'cookie': return 'Cookie';
      case 'authorization': return 'Authorization';
      default: return key;
    }
  }

  function safeDecode(s) { try { return decodeURIComponent(s.replace(/\+/g, ' ')); } catch (e) { return s; } }

  function parseVlcOpt(opt, out) {
    const eq = opt.indexOf('=');
    if (eq <= 0) return;
    const key = opt.substring(0, eq).trim().toLowerCase();
    const value = opt.substring(eq + 1).trim().replace(/^"+|"+$/g, '');
    if (!value) return;
    if (key === 'http-user-agent') out['User-Agent'] = value;
    else if (key === 'http-referrer' || key === 'http-referer') out['Referer'] = value;
    else if (key === 'http-origin') out['Origin'] = value;
    else if (key === 'http-cookie') out['Cookie'] = value;
  }

  function parseJsonHeaders(json, out) {
    jsonPairRegex.lastIndex = 0;
    let m;
    while ((m = jsonPairRegex.exec(json)) !== null) {
      const key = m[1].trim();
      const value = m[2].replace(/\\"/g, '"').replace(/\\\\/g, '\\');
      if (key && value) out[normalizeHeader(key)] = value;
    }
  }

  /** Estilo Kodi: "http://host/stream.m3u8|User-Agent=X&Referer=Y". */
  function splitPipeHeaders(line) {
    const bar = line.indexOf('|');
    if (bar < 0 || !/^http/i.test(line)) return [line, {}];
    const url = line.substring(0, bar).trim();
    const map = {};
    for (const part of line.substring(bar + 1).split('&')) {
      const eq = part.indexOf('=');
      if (eq <= 0) continue;
      const key = safeDecode(part.substring(0, eq).trim());
      const value = safeDecode(part.substring(eq + 1).trim());
      if (key && value) map[normalizeHeader(key)] = value;
    }
    return [url, map];
  }

  function parseM3u(text) {
    const result = [];
    let name = null;
    let group = '';
    let logo = '';
    let headers = {};
    const lines = String(text).split(/\r?\n/);
    for (const raw of lines) {
      const line = raw.replace(/^﻿/, '').trim();
      if (!line) continue;
      const up = line.substring(0, 8).toUpperCase();
      if (up === '#EXTINF:' || line.substring(0, 7).toUpperCase() === '#EXTINF') {
        const g = groupRegex.exec(line);
        group = g ? g[1].trim() : '';
        const l = logoRegex.exec(line);
        logo = l ? l[1].trim() : '';
        const comma = line.lastIndexOf(',');
        name = comma >= 0 ? line.substring(comma + 1).trim() : '';
      } else if (line.substring(0, 11).toUpperCase() === '#EXTVLCOPT:') {
        parseVlcOpt(line.substring(11), headers);
      } else if (line.substring(0, 9).toUpperCase() === '#EXTHTTP:') {
        parseJsonHeaders(line.substring(9), headers);
      } else if (line.startsWith('#')) {
        continue;
      } else {
        const [cleanUrl, pipeHeaders] = splitPipeHeaders(line);
        Object.assign(headers, pipeHeaders);
        result.push(makeChannel({
          name: name && name.length ? name : cleanUrl,
          url: cleanUrl,
          group: GroupNames.translate(group),
          logo,
          headers,
        }));
        name = null; group = ''; logo = ''; headers = {};
      }
    }
    return result;
  }

  // ---------- Canal ----------
  const RES_TAG = /\s*[(\[]\s*(\d{3,4}\s*[pPiI]|4K)\s*[)\]]/i;

  function makeChannel(c) {
    const m = RES_TAG.exec(c.name);
    let nameResolution = null;
    if (m) {
      const raw = m[1].trim();
      nameResolution = /^4k$/i.test(raw) ? 2160 : parseInt(raw.replace(/\D/g, ''), 10) || null;
    }
    const displayName = c.name.replace(new RegExp(RES_TAG.source, 'gi'), '').replace(/\s{2,}/g, ' ').trim() || c.name;
    return Object.assign({}, c, { displayName, nameResolution });
  }

  function resolutionLabel(h) {
    if (h >= 2000) return '4K';
    if (h >= 1300) return '1440p';
    if (h >= 1000) return '1080p';
    if (h >= 680) return '720p';
    if (h >= 520) return '576p';
    if (h >= 420) return '480p';
    if (h >= 300) return '360p';
    return '240p';
  }

  function hashKey(url) {
    let h = 5381;
    for (let i = 0; i < url.length; i++) h = ((h * 33) ^ url.charCodeAt(i)) >>> 0;
    return h.toString(16);
  }

  function suggestName(url) {
    try {
      const u = new URL(url.trim());
      const file = u.pathname.split('/').pop().replace(/\.[^.]*$/, '');
      const host = u.hostname.replace(/^www\./, '');
      return file && file !== 'index' ? host + ' · ' + file : host;
    } catch (e) {
      return 'Minha lista';
    }
  }

  // ---------- Capas (logos) por nome ----------
  const NOISE = new Set(['hd', 'fhd', 'uhd', 'sd', '4k', '8k', 'hdtv', 'h264', 'h265', 'hevc', '1080p', '720p', '1080', '720', 'fps', '50fps', '60fps', 'raw']);
  const GENERIC = new Set(['canal', 'rede', 'tv', 'tele', 'filmes', 'series', 'esportes', 'sports', 'news', 'kids', 'cine', 'radio', 'brasil', 'noticias', 'cinema']);
  const COUNTRY_ORDER = ['br', 'pt', 'us', 'ar', 'mx', 'es'];

  function stripMarks(s) { return s.normalize('NFD').replace(/\p{Mn}+/gu, ''); }
  function simplify(text) { return stripMarks(text).toLowerCase().replace(/[^\p{L}\p{N}]/gu, ''); }

  function tokensOf(name) {
    let s = stripMarks(name).toLowerCase();
    s = s.replace(/\[[^\]]*\]|\([^)]*\)/g, ' ');
    s = s.replace(/^\s*[a-z]{2,3}\s*[|:]\s*/, '');
    return s.split(/[^a-z0-9]+/).filter((t) => t && !NOISE.has(t));
  }

  function lookupLogo(map, name) {
    const tokens = tokensOf(name);
    if (!tokens.length) return null;
    for (let n = tokens.length; n >= 1; n--) {
      const key = tokens.slice(0, n).join('');
      const partial = n < tokens.length;
      if (partial && (key.length < 4 || GENERIC.has(key))) continue;
      if (map[key]) return map[key];
      if (map['tv' + key]) return map['tv' + key];
    }
    return null;
  }

  /** Recebe o logos.json do iptv-org e devolve { nomeSimplificado: urlDoLogo }. */
  function buildLogoIndex(entries) {
    const bestRank = {};
    const bestScore = {};
    const bestUrl = {};
    for (const e of entries) {
      const channel = e && e.channel;
      const url = e && e.url;
      if (!channel || !url) continue;
      if (String(e.format || '').toUpperCase() === 'SVG') continue;
      const dot = channel.lastIndexOf('.');
      const country = dot >= 0 ? channel.substring(dot + 1).toLowerCase() : '';
      const rank = COUNTRY_ORDER.indexOf(country);
      if (rank < 0) continue;
      const key = simplify(channel.substring(0, dot));
      if (!key) continue;
      const score = (e.feed != null ? 0 : 2) + (e.in_use ? 1 : 0);
      const prev = bestRank[key];
      if (prev === undefined || rank < prev || (rank === prev && score > (bestScore[key] === undefined ? -1 : bestScore[key]))) {
        bestRank[key] = rank;
        bestScore[key] = score;
        bestUrl[key] = url;
      }
    }
    return bestUrl;
  }

  // ---------- Tentativas de reprodução ----------
  function originOf(url) {
    try {
      const u = new URL(url);
      if (!u.protocol || !u.host) return null;
      return u.protocol + '//' + u.host + '/';
    } catch (e) { return null; }
  }

  function swapScheme(url) {
    if (/^https:\/\//i.test(url)) return 'http://' + url.substring(8);
    if (/^http:\/\//i.test(url)) return 'https://' + url.substring(7);
    return null;
  }

  /**
   * Monta as formas de abrir o canal, da mais provável para a menos provável.
   * mode: "auto" (descobre pelo conteúdo), "hls" ou "ts".
   */
  function buildAttempts(ch, opts) {
    const allowInsecure = !!(opts && opts.allowInsecure);
    const list = [];
    const keys = new Set();
    const add = (a) => {
      const k = JSON.stringify([a.url, a.ua, a.headers, a.mode, a.insecure]);
      if (!keys.has(k)) { keys.add(k); list.push(a); }
    };
    const mk = (url, ua, headers, mode, insecure) => ({ url, ua, headers: Object.assign({}, headers), mode, insecure: !!insecure });

    const headers = ch.headers || {};
    const playlistUa = headers['User-Agent'];
    const baseHeaders = {};
    for (const k of Object.keys(headers)) if (k.toLowerCase() !== 'user-agent') baseHeaders[k] = headers[k];
    const primaryUa = playlistUa || USER_AGENT;
    const path = ch.url.split('?')[0].split('#')[0];
    const hasM3u8 = /\.m3u8?$/i.test(path) || /\.m3u8/i.test(ch.url);
    const isTs = /\.ts$/i.test(path);
    const firstMode = hasM3u8 ? 'hls' : isTs ? 'ts' : 'auto';
    const extraModes = hasM3u8 ? [] : isTs ? ['hls'] : ['hls', 'ts'];

    const origin = originOf(ch.url);
    const auto = origin ? { Referer: origin, Origin: origin.replace(/\/$/, '') } : {};
    const withReferer = Object.assign({}, auto, baseHeaders); // Referer/Origin da lista têm prioridade

    // 1) Do jeito que a lista pede (ou como o VLC faria).
    add(mk(ch.url, primaryUa, baseHeaders, firstMode));
    // 2) Mesmo endereço, forçando o outro formato.
    for (const m of extraModes) add(mk(ch.url, primaryUa, baseHeaders, m));
    // 3) Outras identidades, com Referer e Origin do próprio servidor.
    ALT_USER_AGENTS.forEach((ua, i) => {
      add(mk(ch.url, ua, withReferer, firstMode));
      if (i === 0) for (const m of extraModes.slice(0, 1)) add(mk(ch.url, ua, withReferer, m));
    });
    // 4) Trocar http <-> https.
    const alt = swapScheme(ch.url);
    if (alt) {
      add(mk(alt, primaryUa, baseHeaders, firstMode));
      for (const m of extraModes.slice(0, 1)) add(mk(alt, primaryUa, baseHeaders, m));
    }
    // 5) Só se o usuário permitiu: ignora certificado HTTPS inválido ou vencido.
    if (allowInsecure) {
      add(mk(ch.url, primaryUa, baseHeaders, firstMode, true));
      for (const m of extraModes.slice(0, 1)) add(mk(ch.url, primaryUa, baseHeaders, m, true));
    }
    return list;
  }

  function describeError(e) {
    const s = e && e.status;
    if (s === 401 || s === 403) return 'Servidor recusou o acesso (HTTP ' + s + ')';
    if (s === 404 || s === 410) return 'Este link não existe mais (HTTP ' + s + ')';
    if (s === 429 || s === 509) return 'Limite de conexões do servidor atingido (HTTP ' + s + ')';
    if (s && s >= 500) return 'Servidor do canal com problema (HTTP ' + s + ')';
    if (s) return 'Servidor respondeu com erro (HTTP ' + s + ')';
    if (e && e.decode) return 'Este canal usa um formato de vídeo que o app não consegue reproduzir';
    if (e && e.network) return 'Não foi possível conectar ao servidor do canal';
    return 'Não foi possível reproduzir este canal' + (e && e.detail ? ' (' + e.detail + ')' : '');
  }

  return {
    USER_AGENT, ALT_USER_AGENTS, GroupNames, parseM3u, makeChannel, resolutionLabel, hashKey,
    suggestName, simplify, tokensOf, lookupLogo, buildLogoIndex, buildAttempts, describeError,
    originOf, swapScheme,
  };
});

const test = require('node:test');
const assert = require('node:assert');
const Core = require('../core.js');

const SAMPLE = `﻿#EXTM3U
#EXTINF:-1 tvg-logo="https://x/logo.png" group-title="News;Entertainment",Globo News (1080p)
http://srv.example.com/live/globonews.m3u8
#EXTINF:-1 group-title="Undefined",Canal Sem Grupo
#EXTVLCOPT:http-user-agent=MyUA/1.0
#EXTVLCOPT:http-referrer=http://ref.example.com/
https://a.example.com:8080/stream/123
#EXTINF:-1 group-title="Sports",ESPN 4K [4K]
#EXTHTTP:{"cookie":"a=b","User-Agent":"JsonUA"}
http://s.example.com/espn.ts
#EXTINF:-1,Kodi Style
http://k.example.com/s.m3u8|User-Agent=Foo%20Bar&Referer=http%3A%2F%2Fr.example.com%2F
#EXTINF:-1 group-title="Kids",Fim
http://z.example.com/z`;

test('parser: canais, grupos, nomes e logos', () => {
  const ch = Core.parseM3u(SAMPLE);
  assert.strictEqual(ch.length, 5);
  assert.strictEqual(ch[0].name, 'Globo News (1080p)');
  assert.strictEqual(ch[0].displayName, 'Globo News');
  assert.strictEqual(ch[0].nameResolution, 1080);
  assert.strictEqual(ch[0].group, 'Notícias;Entretenimento');
  assert.strictEqual(ch[0].logo, 'https://x/logo.png');
  assert.strictEqual(ch[1].group, 'Sem categoria');
  assert.strictEqual(ch[4].group, 'Infantil');
});

test('parser: cabeçalhos EXTVLCOPT, EXTHTTP e estilo Kodi', () => {
  const ch = Core.parseM3u(SAMPLE);
  assert.deepStrictEqual(ch[1].headers, { 'User-Agent': 'MyUA/1.0', Referer: 'http://ref.example.com/' });
  assert.deepStrictEqual(ch[2].headers, { Cookie: 'a=b', 'User-Agent': 'JsonUA' });
  assert.strictEqual(ch[2].nameResolution, 2160);
  assert.strictEqual(ch[3].url, 'http://k.example.com/s.m3u8'); // o "|" nunca vai para a URL
  assert.deepStrictEqual(ch[3].headers, { 'User-Agent': 'Foo Bar', Referer: 'http://r.example.com/' });
  assert.strictEqual(ch[3].name, 'Kodi Style');
});

test('parser: cabeçalhos de um canal não vazam para o próximo', () => {
  const ch = Core.parseM3u(SAMPLE);
  assert.deepStrictEqual(ch[4].headers, {});
});

test('categorias: tradução, duplicatas e sem categoria', () => {
  assert.strictEqual(Core.GroupNames.translate('News;news'), 'Notícias');
  assert.strictEqual(Core.GroupNames.translate(''), 'Sem categoria');
  assert.strictEqual(Core.GroupNames.translate('Undefined;Movies'), 'Filmes');
  assert.strictEqual(Core.GroupNames.translate('Algo Novo'), 'Algo Novo');
  assert.strictEqual(Core.GroupNames.display('Notícias;Filmes'), 'Notícias • Filmes');
  assert.strictEqual(Core.GroupNames.translate('constructor'), 'constructor');
});

test('resolução: rótulos e chave', () => {
  assert.strictEqual(Core.resolutionLabel(1080), '1080p');
  assert.strictEqual(Core.resolutionLabel(720), '720p');
  assert.strictEqual(Core.resolutionLabel(2160), '4K');
  assert.strictEqual(Core.resolutionLabel(240), '240p');
  assert.strictEqual(Core.hashKey('abc'), Core.hashKey('abc'));
  assert.notStrictEqual(Core.hashKey('abc'), Core.hashKey('abd'));
  assert.strictEqual(Core.makeChannel({ name: 'Filme (2024)', url: 'x', group: '', logo: '', headers: {} }).nameResolution, null);
});

test('capas: índice do iptv-org e busca por nome', () => {
  const idx = Core.buildLogoIndex([
    { channel: 'GloboNews.br', url: 'https://l/globonews.png', format: 'PNG', feed: null, in_use: true },
    { channel: 'CNN.us', url: 'https://l/cnn-us.png', format: 'PNG', feed: null },
    { channel: 'CNN.br', url: 'https://l/cnn-br.png', format: 'PNG', feed: null },
    { channel: 'SVGOnly.br', url: 'https://l/x.svg', format: 'SVG' },
    { channel: 'Outro.jp', url: 'https://l/jp.png', format: 'PNG' },
    { channel: 'TVCultura.br', url: 'https://l/cultura.png', format: 'PNG' },
  ]);
  assert.strictEqual(idx.globonews, 'https://l/globonews.png');
  assert.strictEqual(idx.cnn, 'https://l/cnn-br.png'); // Brasil tem prioridade
  assert.strictEqual(idx.svgonly, undefined);
  assert.strictEqual(idx.outro, undefined);
  assert.strictEqual(Core.lookupLogo(idx, 'BR | Globo News HD [FHD]'), 'https://l/globonews.png');
  assert.strictEqual(Core.lookupLogo(idx, 'CNN HD'), 'https://l/cnn-br.png');
  assert.strictEqual(Core.lookupLogo(idx, 'CNN Brasil'), null); // parcial com menos de 4 letras é ignorado (igual ao app Android)
  assert.strictEqual(Core.lookupLogo(idx, 'Cultura'), 'https://l/cultura.png'); // tenta "tv" + nome
  assert.strictEqual(Core.lookupLogo(idx, 'Canal Qualquer'), null);
});

test('tentativas: m3u8 usa HLS; sem extensão detecta pelo conteúdo', () => {
  const hls = Core.parseM3u('#EXTINF:-1,A\nhttps://srv.com/a.m3u8')[0];
  const a1 = Core.buildAttempts(hls, {});
  assert.strictEqual(a1[0].mode, 'hls');
  assert.strictEqual(a1[0].ua, Core.USER_AGENT);
  assert.ok(a1.every((a) => a.mode === 'hls'));
  assert.ok(a1.some((a) => a.url.startsWith('http://'))); // troca https -> http

  const plain = Core.parseM3u('#EXTINF:-1,B\nhttp://srv.com/live/123')[0];
  const a2 = Core.buildAttempts(plain, {});
  assert.deepStrictEqual(a2.slice(0, 3).map((a) => a.mode), ['auto', 'hls', 'ts']);
  assert.ok(a2.length >= 6);
});

test('tentativas: Origin/Referer automáticos só nas identidades alternativas', () => {
  const ch = Core.parseM3u('#EXTINF:-1,A\nhttp://srv.com:8080/x/1.m3u8')[0];
  const list = Core.buildAttempts(ch, {});
  assert.deepStrictEqual(list[0].headers, {});
  const alt = list.find((a) => a.ua === Core.ALT_USER_AGENTS[0]);
  assert.strictEqual(alt.headers.Origin, 'http://srv.com:8080');
  assert.strictEqual(alt.headers.Referer, 'http://srv.com:8080/');
});

test('tentativas: Origin e Referer da própria lista têm prioridade', () => {
  const ch = Core.parseM3u('#EXTINF:-1,A\n#EXTVLCOPT:http-origin=http://meu.origem\n#EXTVLCOPT:http-referrer=http://meu.ref/\nhttp://srv.com/x/1.m3u8')[0];
  const alt = Core.buildAttempts(ch, {}).find((a) => a.ua === Core.ALT_USER_AGENTS[0]);
  assert.strictEqual(alt.headers.Origin, 'http://meu.origem');
  assert.strictEqual(alt.headers.Referer, 'http://meu.ref/');
});

test('tentativas: certificado inválido só entra se o usuário permitiu, e fica por último', () => {
  const ch = Core.parseM3u('#EXTINF:-1,A\nhttps://srv.com/x/1.m3u8')[0];
  assert.ok(Core.buildAttempts(ch, {}).every((a) => !a.insecure));
  const on = Core.buildAttempts(ch, { allowInsecure: true });
  assert.strictEqual(on[on.length - 1].insecure, true);
  assert.ok(on.slice(0, -1).every((a) => !a.insecure));
});

test('tentativas: User-Agent da lista é respeitado', () => {
  const ch = Core.parseM3u('#EXTINF:-1,A\n#EXTVLCOPT:http-user-agent=MeuUA\nhttp://srv.com/1.m3u8')[0];
  assert.strictEqual(Core.buildAttempts(ch, {})[0].ua, 'MeuUA');
});

test('mensagens de erro', () => {
  assert.match(Core.describeError({ status: 403 }), /recusou/);
  assert.match(Core.describeError({ status: 404 }), /não existe/);
  assert.match(Core.describeError({ status: 429 }), /Limite/);
  assert.match(Core.describeError({ status: 502 }), /problema/);
  assert.match(Core.describeError({ network: true }), /conectar/);
  assert.match(Core.describeError({ decode: true }), /formato/);
});

test('suggestName', () => {
  assert.strictEqual(Core.suggestName('https://iptv-org.github.io/iptv/countries/br.m3u'), 'iptv-org.github.io · br');
  assert.strictEqual(Core.suggestName('http://x.com/index.m3u'), 'x.com');
  assert.strictEqual(Core.suggestName('lixo'), 'Minha lista');
});

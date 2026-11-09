import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { cleanTitle, createLocalProvider, languageOf, needsVideoTranscode, parseEpisodeName, planSegments, scanFolders, skipRanges } from '../library.mjs';

const hasFfmpeg = spawnSync('ffmpeg', ['-version'], { stdio: 'ignore' }).status === 0 && spawnSync('ffprobe', ['-version'], { stdio: 'ignore' }).status === 0;

test('Episode numbers are read from the usual file name styles', () => {
  const episode = (name) => parseEpisodeName(name).episode;
  assert.equal(episode('[Group] Some Show - 07 [1080p][ABCD1234].mkv'), 7);
  assert.equal(episode('Some.Show.S02E11.1080p.WEB-DL.x265.mkv'), 11);
  assert.equal(episode('Some Show Episode 3.mp4'), 3);
  assert.equal(episode('Some Show 1x05.mkv'), 5);
  assert.equal(episode('Some Show - 12v2.mkv'), 12);
  assert.equal(episode('Some Show 24.mkv'), 24);
  assert.equal(episode('Some Movie (2019) 1080p x264.mkv'), null);
  assert.equal(parseEpisodeName('Some.Show.S02E11.mkv').season, 2);
});

test('Titles drop release tags and keep the name', () => {
  assert.equal(cleanTitle('[Group] Some Show (2019) [1080p]'), 'Some Show');
  assert.equal(cleanTitle('Some.Show.1080p.BluRay.x264'), 'Some Show');
  assert.equal(languageOf('jpn').language, 'ja');
  assert.equal(languageOf('spa').name, 'Spanish');
  assert.equal(languageOf('zzz').language, 'und');
});

test('Segments follow the keyframes when copying and a fixed grid when transcoding', () => {
  const copy = planSegments({ transcode: false, keyframes: [0, 2, 4, 6, 8, 10, 12, 14], duration: 15 });
  assert.deepEqual(copy, [{ start: 0, end: 6 }, { start: 6, end: 12 }, { start: 12, end: 15 }]);
  const grid = planSegments({ transcode: true, keyframes: [], duration: 14 });
  assert.deepEqual(grid, [{ start: 0, end: 6 }, { start: 6, end: 12 }, { start: 12, end: 14 }]);
  assert.equal(planSegments({ transcode: false, keyframes: [], duration: 0 }).length, 1);
});

test('Only 8-bit progressive H.264 is copied', () => {
  assert.equal(needsVideoTranscode({ codec: 'h264', pixFmt: 'yuv420p', interlaced: false }), false);
  assert.equal(needsVideoTranscode({ codec: 'h264', pixFmt: 'yuv420p10le' }), true);
  assert.equal(needsVideoTranscode({ codec: 'hevc', pixFmt: 'yuv420p' }), true);
  assert.equal(needsVideoTranscode({ codec: 'h264', pixFmt: 'yuv420p' }, true), true);
});

test('Intro and outro come from chapter names', () => {
  const ranges = skipRanges([
    { title: 'Opening', start: 90, end: 180 }, { title: 'Part A', start: 180, end: 700 }, { title: 'Ending', start: 1300, end: 1390 },
  ]);
  assert.deepEqual(ranges, { intro: { start: 90, end: 180 }, outro: { start: 1300, end: 1390 } });
  assert.deepEqual(skipRanges([{ title: 'Chapter 1', start: 0, end: 10 }]), { intro: null, outro: null });
});

async function libraryTree() {
  const root = await mkdtemp(join(tmpdir(), 'relaycast-library-'));
  const video = Buffer.alloc(4096, 1);
  const put = async (path, content = video) => { await mkdir(join(root, path, '..'), { recursive: true }); await writeFile(join(root, path), content); };
  await put('Alpha/Season 1/Alpha - 01.mkv'); await put('Alpha/Season 1/Alpha - 02.mkv'); await put('Alpha/Season 2/Alpha S02E01.mkv');
  await put('Alpha/metadata.json', JSON.stringify({ synopsis: 'About Alpha', genres: ['Comedy', 'Drama'], year: 2021, score: 8.1 }));
  await put('Beta (2020)/Beta - 01.mp4'); await put('Beta (2020)/Beta - 03.mp4');
  await put('Gamma Movie (2019).mkv');
  await put('Tiny/tiny.mkv', Buffer.alloc(10));
  await put('notes.txt');
  return root;
}

test('Folders become series, seasons and episodes', async (context) => {
  const root = await libraryTree();
  context.after(() => rm(root, { recursive: true, force: true }));
  const { series, files } = await scanFolders([root]);
  assert.equal(files.size, 6);
  const byTitle = Object.fromEntries([...series.values()].map((item) => [item.title, item]));
  assert.deepEqual(Object.keys(byTitle).sort(), ['Alpha', 'Alpha Season 2', 'Beta', 'Gamma Movie']);
  assert.deepEqual(byTitle.Alpha.episodes.map((episode) => episode.number), [1, 2]);
  assert.deepEqual(byTitle.Beta.episodes.map((episode) => episode.number), [1, 3]);
  assert.equal(byTitle['Gamma Movie'].mediaType, 'Movie');
  assert.equal(byTitle.Alpha.synopsis, 'About Alpha');
  assert.equal(byTitle.Alpha.relatedIds.length, 2);
  assert.equal(byTitle['Alpha Season 2'].seasonNumber, 2);
  assert.match(byTitle.Alpha.id, /^alpha-[0-9a-f]{8}$/);
});

test('The provider answers the catalog protocol from the scanned library', async (context) => {
  const root = await libraryTree();
  const data = await mkdtemp(join(tmpdir(), 'relaycast-data-'));
  context.after(() => Promise.all([rm(root, { recursive: true, force: true }), rm(data, { recursive: true, force: true })]));
  const provider = createLocalProvider({ folders: [root], dataDir: data });
  await provider.rescan();
  assert.equal(provider.status().series, 4);

  const all = provider.latest(new URLSearchParams());
  assert.equal(all.results.length, 4); assert.equal(all.hasNext, false);
  assert.ok(all.results.every((item) => item.watchUrl.startsWith('https://library.relaycast.local/watch/') && item.imageUrl.startsWith('/art/')));
  assert.deepEqual(provider.search('alp').map((item) => item.title).sort(), ['Alpha', 'Alpha Season 2']);
  assert.deepEqual(provider.latest(new URLSearchParams({ mediaType: 'Movie' })).results.map((item) => item.title), ['Gamma Movie']);
  assert.deepEqual(provider.latest(new URLSearchParams({ minEpisodes: '2', sort: 'name-az' })).results.map((item) => item.title), ['Alpha', 'Beta']);
  const genre = provider.filters.genres.find((option) => option.label === 'Comedy').id;
  assert.deepEqual(provider.latest(new URLSearchParams({ genre })).results.map((item) => item.title).sort(), ['Alpha', 'Alpha Season 2']);
  assert.deepEqual(provider.latest(new URLSearchParams({ language: 'dub' })).results, []);
  assert.throws(() => provider.latest(new URLSearchParams({ season: 'monsoon' })), (error) => error.status === 400);
  assert.throws(() => provider.latest(new URLSearchParams({ minEpisodes: '0' })), (error) => error.status === 400);

  const alpha = provider.series(provider.search('alpha')[0].watchUrl);
  assert.equal(alpha.totalEpisodes, 2);
  assert.deepEqual(alpha.episodes.map(({ number, sub, dub }) => [number, sub, dub]), [[1, true, false], [2, true, false]]);
  assert.equal(alpha.seasons.length, 2);
  assert.equal(provider.watchUrl('/watch/alpha-1'), 'https://library.relaycast.local/watch/alpha-1');
  assert.equal(provider.watchUrl('https://old.example/watch/alpha-1?ep=2'), 'https://library.relaycast.local/watch/alpha-1');
  assert.throws(() => provider.watchUrl('https://library.relaycast.local/user'), (error) => error.status === 400);
  assert.throws(() => provider.series('https://library.relaycast.local/watch/missing-1'), (error) => error.status === 404);
});

test('A real file plays as HLS: copy, audio tracks, subtitles and transcoding', { skip: !hasFfmpeg }, async (context) => {
  const root = await mkdtemp(join(tmpdir(), 'relaycast-e2e-'));
  const data = await mkdtemp(join(tmpdir(), 'relaycast-e2e-data-'));
  context.after(() => Promise.all([rm(root, { recursive: true, force: true }), rm(data, { recursive: true, force: true })]));
  await mkdir(join(root, 'Demo'), { recursive: true });
  const make = (name, extra) => {
    const result = spawnSync('ffmpeg', ['-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=24:duration=14', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=14',
      '-f', 'lavfi', '-i', 'sine=frequency=660:duration=14', '-map', '0', '-map', '1', '-map', '2', '-c:a', 'aac', '-metadata:s:a:0', 'language=jpn', '-metadata:s:a:1', 'language=eng',
      ...extra, join(root, 'Demo', name)]);
    assert.equal(result.status, 0, String(result.stderr));
  };
  make('Demo - 01.mkv', ['-c:v', 'libx264', '-g', '48', '-pix_fmt', 'yuv420p']);
  make('Demo - 02.mkv', ['-c:v', 'libx264', '-g', '48', '-pix_fmt', 'yuv420p10le']);
  await writeFile(join(root, 'Demo', 'Demo - 01.en.srt'), '1\n00:00:01,000 --> 00:00:03,000\nHello\n');

  const provider = createLocalProvider({ folders: [root], dataDir: data, videoEncoder: 'libx264' });
  await provider.rescan();
  const series = provider.series(provider.search('demo')[0].watchUrl);
  for (const [index, transcoded] of [[0, false], [1, true]]) {
    const servers = await provider.servers(series.episodes[index].ref);
    assert.deepEqual(servers.map(({ type, name }) => [type, name.split(' ')[0]]), [['sub', 'Japanese'], ['dub', 'English']]);
    const media = await provider.source(servers[1].ref);
    assert.equal(media.audio.language, 'en');
    const master = (await provider.media.read(media.hlsUrl)).body.toString('utf8');
    assert.match(master, /#EXT-X-STREAM-INF:BANDWIDTH=\d+,RESOLUTION=320x180/);
    const playlist = (await provider.media.read(new URL('media.m3u8', media.hlsUrl).toString())).body.toString('utf8');
    const names = playlist.split('\n').filter((line) => line.endsWith('.ts'));
    assert.ok(names.length >= 3, `${names.length} segments`);
    const total = [...playlist.matchAll(/#EXTINF:([\d.]+)/g)].reduce((sum, match) => sum + Number(match[1]), 0);
    assert.ok(Math.abs(total - 14) < 0.5, `duration ${total}`);
    const chunks = [];
    for (const name of names) {
      const segment = await provider.media.read(new URL(name, media.hlsUrl).toString());
      assert.equal(segment.type, 'video/mp2t'); assert.equal(segment.body[0], 0x47);
      chunks.push(segment.body);
    }
    const probe = spawnSync('ffprobe', ['-v', 'error', '-show_entries', 'stream=codec_name,pix_fmt', '-of', 'compact', '-'], { input: Buffer.concat(chunks) }).stdout.toString();
    assert.match(probe, /codec_name=h264\|pix_fmt=yuv420p\b/, `${transcoded ? 'transcoded' : 'copied'}: ${probe}`);
    assert.match(probe, /codec_name=aac/);
  }
  const first = await provider.source((await provider.servers(series.episodes[0].ref))[0].ref);
  assert.deepEqual(first.rawSubtitles.map((track) => [track.label, track.srclang]), [['en', 'en']]);
  const vtt = (await provider.media.read(first.rawSubtitles[0].file)).body.toString('utf8');
  assert.match(vtt, /^WEBVTT/); assert.match(vtt, /00:01\.000 --> 00:03\.000\s+Hello/);
  const art = await provider.art(provider.search('demo')[0].watchUrl.split('/').pop());
  assert.equal(art.type, 'image/jpeg'); assert.ok(art.body.length > 500);
  provider.stop();
});

test('The companion can edit metadata, seasons, relations, episodes, intro/outro and the poster', async (context) => {
  const root = await libraryTree();
  const data = await mkdtemp(join(tmpdir(), 'relaycast-editor-'));
  context.after(() => Promise.all([rm(root, { recursive: true, force: true }), rm(data, { recursive: true, force: true })]));
  const provider = createLocalProvider({ folders: [root], dataDir: data });
  await provider.rescan();
  const { editor } = provider;
  const find = (title) => editor.catalog().find((item) => item.title === title);
  const beta = find('Beta'); const gamma = find('Gamma Movie');
  const before = editor.detail(beta.id);
  const [first, second] = before.episodes;

  // Fields, relation to another folder (franchise), season and per-episode edits.
  const saved = await editor.saveSeries(beta.id, {
    title: 'Beta Reborn', synopsis: 'New synopsis', genres: ['Action', 'Action', 'Mystery'], status: 'airing', rating: 'pg-13', season: 'Fall', year: 2024, score: 7.5,
    franchise: 'Beta saga', seasonNumber: 2, nextEpisodeAt: 1900000000,
    episodes: { [first.fileId]: { title: 'Pilot', intro: { start: 5, end: 65 }, outro: { start: 1300, end: 1390 }, dub: true }, [second.fileId]: { number: 9, intro: false } },
  });
  assert.equal(saved.id, beta.id);
  assert.equal(saved.effective.title, 'Beta Reborn');
  assert.deepEqual(saved.effective.genres, ['Action', 'Mystery']);
  assert.equal(saved.effective.rating, 'PG-13'); assert.equal(saved.effective.season, 'fall');
  assert.deepEqual(saved.episodes.map((episode) => [episode.number, episode.title, episode.dub]), [[1, 'Pilot', true], [9, 'Episode 9', false]]);
  await editor.saveSeries(gamma.id, { franchise: 'beta saga', seasonNumber: 1, seasonLabel: 'The movie' });
  const watchUrl = provider.search('Beta Reborn')[0].watchUrl;
  const protocol = provider.series(watchUrl);
  assert.equal(protocol.synopsis, 'New synopsis'); assert.equal(protocol.status, 'airing'); assert.equal(protocol.nextEpisodeAt, 1900000000);
  assert.deepEqual(protocol.seasons.map((season) => [season.title, season.seasonNumber, season.label]), [['Gamma Movie', 1, 'The movie'], ['Beta Reborn', 2, 'Season 2']]);
  assert.deepEqual(provider.latest(new URLSearchParams({ genre: 'action', status: 'currently-airing', season: 'fall', year: '2024' })).results.map((item) => item.title), ['Beta Reborn']);

  // Intro/outro override reaches the playback source (without needing a probe).
  assert.deepEqual(saved.episodes[0].intro, { start: 5, end: 65 });
  assert.equal(saved.episodes[1].intro, false); // false = switched off

  // Validation.
  await assert.rejects(editor.saveSeries(beta.id, { status: 'someday' }), (error) => error.status === 400);
  await assert.rejects(editor.saveSeries(beta.id, { episodes: { [first.fileId]: { intro: { start: 50, end: 10 } } } }), (error) => error.status === 400);
  await assert.rejects(editor.saveSeries(beta.id, { episodes: { '0123456789abcdef': { title: 'x' } } }), (error) => error.status === 400);

  // Poster upload, served in place of the folder's artwork or a frame.
  const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(32, 7)]);
  await assert.rejects(editor.setPoster(beta.id, Buffer.from('not an image'), 'image/png'), (error) => error.status === 415);
  const withPoster = await editor.setPoster(beta.id, png, 'image/png');
  assert.equal(withPoster.customPoster, true);
  assert.deepEqual((await provider.art(beta.id)).body, png);
  assert.equal(editor.detail(beta.id).effective.title, 'Beta Reborn'); // the poster does not drop the other edits
  assert.equal((await editor.clearPoster(beta.id)).customPoster, false);

  // Clearing every field returns the series to what the folders say.
  const cleared = await editor.saveSeries(beta.id, {});
  assert.equal(cleared.effective.title, 'Beta'); assert.deepEqual(cleared.override, {});
  assert.deepEqual(cleared.episodes.map((episode) => episode.number), [1, 3]);
});

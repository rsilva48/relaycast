import assert from 'node:assert/strict';
import { test } from 'node:test';

process.env.SERVER_NO_LISTEN = '1';
// Identities created by the tests go to a throwaway store.
process.env.SERVER_USER_STORE_PATH = (await import('node:path')).join((await import('node:os')).tmpdir(), `relaycast-users-${process.pid}-${Date.now()}.json`);
process.env.SERVER_ADMIN_TOKEN = 'admin-secret';
process.env.PROFILES_STORE_PATH = (await import('node:path')).join((await import('node:os')).tmpdir(), `relaycast-profiles-${process.pid}-${Date.now()}.json`);
const { approvePairing, completeSetup, createIdentity, sameNetwork, setupForm, listDevices, reportDevice, sendCommand, waitForCommands, injectRenditions, pairingStatus, startPairing, normalizePreferences, normalizeHistoryEntry, subtitleTracks, transportStreamStartPts, withTimestampMap } = await import('../server.mjs');

// One 188-byte MPEG-TS packet starting a PES with only a PTS.
function pesPacket(streamId, pts) {
  const packet = Buffer.alloc(188, 0xff);
  packet.set([0x47, 0x40, 0x100 & 0xff, 0x10, 0x00, 0x00, 0x01, streamId, 0x00, 0x00, 0x80, 0x80, 0x05]);
  packet.set([0x21 | ((pts >>> 29) & 0x0e), (pts >>> 22) & 0xff, ((pts >>> 14) & 0xfe) | 1, (pts >>> 7) & 0xff, ((pts << 1) & 0xfe) | 1], 13);
  return packet;
}

const master = [
  '#EXTM3U',
  '#EXT-X-VERSION:3',
  '#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720',
  'index-720.m3u8',
  '#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080',
  'index-1080.m3u8',
  '',
].join('\n');
const subtitles = [
  { name: 'English', language: 'en', default: true },
  { name: 'Spanish', language: 'es', default: false },
];

test('Sub sources declare Japanese muxed audio and the subtitle group', () => {
  const playlist = injectRenditions(master, { kind: 'sub', subtitles, accessToken: '' }, 'abc');
  assert.match(playlist, /^#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="server-audio",NAME="Japanese",LANGUAGE="ja",DEFAULT=YES,AUTOSELECT=YES\n/);
  assert.doesNotMatch(playlist, /TYPE=AUDIO[^\n]*URI=/);
  assert.match(playlist, /NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI="\/subtitle\/abc\/0\.m3u8"/);
  assert.match(playlist, /NAME="Spanish",LANGUAGE="es",DEFAULT=NO/);
  for (const line of playlist.split('\n').filter((value) => value.startsWith('#EXT-X-STREAM-INF:'))) {
    assert.match(line, /,AUDIO="server-audio",SUBTITLES="server-subs"$/);
  }
});

test('Dub sources declare English audio', () => {
  const playlist = injectRenditions(master, { kind: 'dub', subtitles: [], accessToken: '' }, 'abc');
  assert.match(playlist, /NAME="English",LANGUAGE="en",DEFAULT=YES/);
  assert.doesNotMatch(playlist, /TYPE=SUBTITLES/);
});

test('Forced tracks are flagged and never the default; repeated names get a number', () => {
  const vtt = (file) => ({ kind: 'captions', label: '', file: `https://cdn.example/subs/${file}` });
  const tracks = subtitleTracks([
    vtt('track_0_Forced_eng.vtt'), vtt('track_1_eng.vtt'), vtt('track_2_Dubtitle_eng.vtt'),
    vtt('track_13_Latin_American__Forced__spa.vtt'), vtt('track_14_Latin_American_spa.vtt'),
    vtt('spa-1.vtt'), vtt('spa-2.vtt'),
  ], 'https://cdn.example/');
  assert.deepEqual(tracks.map(({ name, language, forced, default: isDefault }) => [name, language, forced, isDefault]), [
    ['English (Forced)', 'en', true, false],
    ['English', 'en', false, true],
    ['English (Dubtitle)', 'en', false, false],
    ['Spanish (Latin American, Forced)', 'es-419', true, false],
    ['Spanish (Latin American)', 'es-419', false, false],
    ['Spanish', 'es', false, false],
    ['Spanish 2', 'es', false, false],
  ]);
  const playlist = injectRenditions(master, { kind: 'sub', subtitles: tracks, accessToken: '' }, 'abc');
  assert.match(playlist, /NAME="English \(Forced\)",LANGUAGE="en",DEFAULT=NO,AUTOSELECT=YES,FORCED=YES,URI=/);
  assert.match(playlist, /NAME="English",LANGUAGE="en",DEFAULT=YES,AUTOSELECT=YES,URI=/);
  assert.doesNotMatch(playlist, /NAME="English",[^\n]*FORCED/);
});

test('The audio declaration can be switched off', () => {
  const playlist = injectRenditions(master, { kind: 'sub', declareAudio: false, subtitles, accessToken: '' }, 'abc');
  assert.doesNotMatch(playlist, /TYPE=AUDIO|AUDIO="/);
  assert.match(playlist, /TYPE=SUBTITLES/);
});

test('Stream start is the earliest audio/video PTS of the first segment', () => {
  const segment = Buffer.concat([pesPacket(0xe0, 16290), pesPacket(0xc0, 9090), pesPacket(0xe0, 19890)]);
  assert.equal(transportStreamStartPts(segment), 9090);
  assert.equal(transportStreamStartPts(Buffer.from('not a transport stream')), null);
});

test('WebVTT gets a timestamp map anchored to the stream start', () => {
  const vtt = 'WEBVTT\n\n00:20.240 --> 00:29.210\nHello\n';
  assert.equal(withTimestampMap(vtt, 9090), 'WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:9090,LOCAL:00:00:00.000\n\n00:20.240 --> 00:29.210\nHello\n');
  assert.equal(withTimestampMap(vtt, null), vtt);
  const mapped = 'WEBVTT\nX-TIMESTAMP-MAP=MPEGTS:900000,LOCAL:00:00:00.000\n\n';
  assert.equal(withTimestampMap(mapped, 9090), mapped);
});

test('Existing audio renditions only receive a missing language', () => {
  const withAudio = master.replace('#EXT-X-VERSION:3', '#EXT-X-VERSION:3\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="Main",DEFAULT=YES,URI="audio.m3u8"');
  const playlist = injectRenditions(withAudio, { kind: 'sub', subtitles: [], accessToken: '' }, 'abc');
  assert.match(playlist, /GROUP-ID="aac",NAME="Main",DEFAULT=YES,URI="audio\.m3u8",LANGUAGE="ja"/);
  assert.doesNotMatch(playlist, /server-audio/);
});

test('Media playlists are returned unchanged', () => {
  const media = '#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nsegment0.ts\n#EXT-X-ENDLIST\n';
  assert.equal(injectRenditions(media, { kind: 'sub', subtitles, accessToken: '' }, 'abc'), media);
});

test('Preferences are coerced to the supported values', () => {
  const swiftEpoch = 978307200;
  const value = normalizePreferences({ titleLanguage: 'jp', defaultTrackType: 'dub', subtitleLanguage: 'es-419', autoNext: false, autoSkipIntro: 'yes', updatedAt: 1000 });
  assert.deepEqual(value, {
    titleLanguage: 'jp', defaultTrackType: 'dub', subtitleLanguage: 'es-419', autoNext: false, autoSkipIntro: false, autoSkipOutro: false, hideAdult: false,
    updatedAt: new Date((1000 + swiftEpoch) * 1000).toISOString(),
  });
  assert.equal(normalizePreferences({ titleLanguage: 'fr' }).titleLanguage, 'en');
  assert.equal(normalizePreferences({ subtitleLanguage: 'off' }).subtitleLanguage, 'off');
  assert.equal(normalizePreferences({ subtitleLanguage: '<script>' }).subtitleLanguage, '');
  assert.throws(() => normalizePreferences(null));
});

test('History saved before a domain move keeps resuming', () => {
  const entry = normalizeHistoryEntry({ watchUrl: 'https://old-domain.example/watch/series-1', episode: 2, positionSeconds: 90 });
  assert.equal(entry.watchUrl, 'https://library.relaycast.local/watch/series-1');
});

test('History rows keep a valid server kind only', () => {
  const base = { watchUrl: '/watch/example-1', episode: 3, positionSeconds: 12 };
  assert.equal(normalizeHistoryEntry({ ...base, serverKind: 'dub' }).serverKind, 'dub');
  assert.equal(normalizeHistoryEntry({ ...base, serverKind: 'raw' }).serverKind, null);
});

test('History rows keep completion details for the Continue screen', () => {
  const base = { watchUrl: '/watch/example-1', episode: 12, positionSeconds: 1400 };
  const entry = normalizeHistoryEntry({ ...base, durationSeconds: 1420, completed: true, totalEpisodes: 12 });
  assert.deepEqual([entry.durationSeconds, entry.completed, entry.totalEpisodes], [1420, true, 12]);
  const legacy = normalizeHistoryEntry(base);
  assert.deepEqual([legacy.durationSeconds, legacy.completed, legacy.totalEpisodes], [null, false, null]);
  assert.equal(normalizeHistoryEntry({ ...base, completed: 'yes', totalEpisodes: -1 }).completed, false);
});

test('Preferences keep the hide-adult setting', () => {
  assert.equal(normalizePreferences({ hideAdult: true }).hideAdult, true);
  assert.equal(normalizePreferences({}).hideAdult, false);
});

test('QR pairing hands the approving identity to the TV exactly once', () => {
  const { pairingId, code } = startPairing();
  assert.equal(pairingStatus(pairingId).status, 'pending');
  assert.throws(() => approvePairing('wrong-code', 'Sala', { token: 'abc' }), (error) => error.status === 404);
  assert.deepEqual(approvePairing(code, 'Sala', { token: 'abc' }), { ok: true });
  assert.throws(() => approvePairing(code, 'Otra', { token: 'xyz' }), (error) => error.status === 404 || error.status === 409);
  assert.deepEqual(pairingStatus(pairingId), { status: 'approved', token: 'abc', label: 'Sala' });
  assert.equal(pairingStatus(pairingId).status, 'expired');
  assert.equal(pairingStatus('unknown').status, 'expired');
});

test('Remote control: a TV reports itself and receives commands from the same identity', async () => {
  const { EventEmitter } = await import('node:events');
  const tv = { userId: 'remote-user', token: 't' };
  const other = { userId: 'someone-else', token: 'o' };
  const deviceId = 'tv-0123456789';
  reportDevice(tv, deviceId, { name: 'Sala', canOpen: true, state: { status: 'playing', seriesTitle: 'Delta', episode: 3, position: 61.5, duration: 1440, kind: 'sub' } });
  // A real TV also keeps a command poll going.
  await waitForCommands(tv, deviceId, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), 1);
  assert.deepEqual(listDevices(tv).devices.map(({ id, name, state, canOpen }) => [id, name, state.status, state.episode, canOpen]), [[deviceId, 'Sala', 'playing', 3, true]]);
  assert.ok(listDevices(tv).devices[0].stateAge >= 0);
  assert.equal(listDevices(other).devices.length, 0);
  assert.throws(() => sendCommand(other, deviceId, { type: 'pause' }), (error) => error.status === 404);
  assert.throws(() => sendCommand(tv, deviceId, { type: 'explode' }), (error) => error.status === 400);
  assert.throws(() => sendCommand(tv, deviceId, { type: 'seek' }), (error) => error.status === 400);

  // A waiting long poll is answered as soon as a command arrives.
  const response = Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false });
  const pending = waitForCommands(tv, deviceId, response, 5000);
  sendCommand(tv, deviceId, { type: 'seek', seconds: -10 });
  const reply = await pending;
  assert.deepEqual(reply.commands.map(({ type, seconds }) => [type, seconds]), [['seek', -10]]);

  // Scrubbing sends an absolute position.
  sendCommand(tv, deviceId, { type: 'seek', position: 754.2 });
  const scrub = await waitForCommands(tv, deviceId, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), 50);
  assert.deepEqual(scrub.commands.map(({ type, position, seconds }) => [type, position, seconds]), [['seek', 754.2, undefined]]);

  // The phone's volume buttons send volume steps.
  assert.throws(() => sendCommand(tv, deviceId, { type: 'volume', steps: 0 }), (error) => error.status === 400);
  sendCommand(tv, deviceId, { type: 'volume', steps: -2 });
  const volume = await waitForCommands(tv, deviceId, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), 50);
  assert.deepEqual(volume.commands.map(({ type, steps }) => [type, steps]), [['volume', -2]]);

  // Subtitle tracks: the TV lists them, the phone picks one (or "off").
  reportDevice(tv, deviceId, { name: 'Sala', state: { status: 'playing', subtitles: [{ id: '2:0', label: 'English' }, { id: 'x', label: 'bad' }], subtitle: '2:0' } });
  assert.deepEqual(listDevices(tv).devices[0].state.subtitles, [{ id: '2:0', label: 'English' }]);
  assert.equal(listDevices(tv).devices[0].state.subtitle, '2:0');
  assert.throws(() => sendCommand(tv, deviceId, { type: 'subtitles', track: '../x' }), (error) => error.status === 400);
  sendCommand(tv, deviceId, { type: 'subtitles', track: '3:1' });
  sendCommand(tv, deviceId, { type: 'subtitles' });
  const subtitles = await waitForCommands(tv, deviceId, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), 50);
  assert.deepEqual(subtitles.commands.map(({ track }) => track), ['3:1', 'off']);

  // Acknowledged delivery: a command answered to a poll stays queued until a
  // later poll acknowledges it (a TV restarted behind a proxy loses nothing).
  const fresh = () => Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false });
  sendCommand(tv, deviceId, { type: 'pause' });
  const first = await waitForCommands(tv, deviceId, fresh(), 50, 0);
  assert.deepEqual(first.commands.map(({ type }) => type), ['pause']);
  const again = await waitForCommands(tv, deviceId, fresh(), 50, 0);
  assert.deepEqual(again.commands.map(({ seq }) => seq), first.commands.map(({ seq }) => seq));
  const acknowledged = await waitForCommands(tv, deviceId, fresh(), 50, first.commands.at(-1).seq);
  assert.deepEqual(acknowledged.commands, []);

  // Commands queued while the TV is away are delivered on its next poll.
  sendCommand(tv, deviceId, { type: 'play', watchUrl: '/watch/delta-4', episode: 5, kind: 'dub' });
  const next = await waitForCommands(tv, deviceId, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), 50);
  assert.deepEqual(next.commands.map(({ type, watchUrl, episode, kind }) => [type, watchUrl, episode, kind]), [['play', 'https://library.relaycast.local/watch/delta-4', 5, 'dub']]);
});

test('A profile creates profiles, and a TV is set up from the browser page', async () => {
  const parent = { userId: 'parent-user', token: 'a'.repeat(64) };
  const created = await createIdentity(parent, 'Sala');
  assert.match(created.token, /^[a-f0-9]{64}$/);
  await assert.rejects(createIdentity({ userId: 'local', token: '' }, 'Sala'), (error) => error.status === 409);

  // First setup with the administrator token creates the TV's identity.
  const first = startPairing();
  await assert.rejects(completeSetup({ code: first.code, mode: 'create', adminToken: 'wrong', label: 'Ana' }), (error) => error.status === 401);
  await completeSetup({ code: first.code, mode: 'create', adminToken: 'admin-secret', label: 'Ana' });
  const approved = pairingStatus(first.pairingId);
  assert.equal(approved.label, 'Ana');
  assert.match(approved.token, /^[a-f0-9]{64}$/);

  // An existing identity's token links the TV to that identity.
  const second = startPairing();
  await assert.rejects(completeSetup({ code: second.code, mode: 'existing', token: 'b'.repeat(64) }), (error) => error.status === 401);
  await completeSetup({ code: second.code, mode: 'existing', token: created.token, label: 'Sala' });
  assert.equal(pairingStatus(second.pairingId).token, created.token);

  // A code allows a few attempts.
  const third = startPairing();
  for (let attempt = 0; attempt < 5; attempt += 1) await assert.rejects(completeSetup({ code: third.code, mode: 'existing', token: 'c'.repeat(64) }), (error) => error.status === 401);
  await assert.rejects(completeSetup({ code: third.code, mode: 'existing', token: created.token }), (error) => error.status === 429);
  await assert.rejects(completeSetup({ code: third.code, mode: 'existing', token: created.token }), (error) => error.status === 404);

  // Each identity creates at most five profiles an hour.
  for (let count = 1; count < 5; count += 1) await createIdentity(parent, `Perfil ${count}`);
  await assert.rejects(createIdentity(parent, 'Uno más'), (error) => error.status === 429);
});

test('The setup page opens the app', () => {
  const { code } = startPairing();
  const request = { headers: { host: 'server.example', 'x-forwarded-proto': 'https' }, socket: {} };
  const page = setupForm(request, code);
  assert.ok(page.includes(`href="relaycast://pair?bridge=`));
});

test('A profile keeps its name, email and picture per identity, and rejects what is not valid', async () => {
  const { storedProfile, updateProfile } = await import('../server.mjs');
  const ana = { userId: 'profile-ana', token: '1'.repeat(64) };
  const leo = { userId: 'profile-leo', token: '2'.repeat(64) };
  assert.deepEqual((await storedProfile(ana)).profile, { name: null, email: null, avatar: null, label: null, updatedAt: null });

  const jpeg = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(64, 1)]).toString('base64');
  let profile = (await updateProfile(ana, { name: '  Ana  ', email: 'Ana@Example.com', avatar: { color: '#e5484d', emoji: '🦊', photo: jpeg } })).profile;
  assert.equal(profile.name, 'Ana');
  assert.equal(profile.email, 'ana@example.com');
  assert.deepEqual(profile.avatar, { color: '#E5484D', emoji: '🦊', photo: jpeg });

  // Fields not sent stay; null clears them.
  profile = (await updateProfile(ana, { name: 'Ana María' })).profile;
  assert.equal(profile.email, 'ana@example.com');
  assert.equal(profile.avatar.photo, jpeg);
  profile = (await updateProfile(ana, { avatar: { photo: null }, email: '' })).profile;
  assert.equal(profile.avatar.photo, null);
  assert.equal(profile.avatar.emoji, '🦊');
  assert.equal(profile.email, null);

  // Another identity has its own profile.
  assert.equal((await storedProfile(leo)).profile.name, null);

  await assert.rejects(updateProfile(ana, { email: 'not-an-email' }), (error) => error.status === 400);
  await assert.rejects(updateProfile(ana, { avatar: { photo: Buffer.from('GIF89a....').toString('base64') } }), (error) => error.status === 400);
  await assert.rejects(updateProfile(ana, { avatar: { photo: Buffer.concat([Buffer.from([0xff, 0xd8, 0xff]), Buffer.alloc(70 * 1024)]).toString('base64') } }), (error) => error.status === 400);
  assert.equal((await storedProfile(ana)).profile.name, 'Ana María');
});

test('A TV is listed only while a phone of its identity can really control it', async () => {
  const { EventEmitter } = await import('node:events');
  const poll = (user, id, waitMs = 1) => waitForCommands(user, id, Object.assign(new EventEmitter(), { destroyed: false, writableEnded: false }), waitMs);
  const owner = { userId: 'availability-user', token: 'a' };
  const stranger = { userId: 'availability-other', token: 'b' };
  const listed = (user, ...rest) => listDevices(user, ...rest).devices.map(({ id }) => id);

  // Reporting state is not enough: without a command poll, commands would not arrive.
  reportDevice(owner, 'tv-reports-only', { name: 'Reports', state: { status: 'idle' } });
  assert.deepEqual(listed(owner), []);
  assert.throws(() => sendCommand(owner, 'tv-reports-only', { type: 'pause' }), (error) => error.status === 404);

  // Reporting and polling: listed for its identity only.
  reportDevice(owner, 'tv-living-room', { name: 'Sala', state: { status: 'idle' }, awake: true }, '203.0.113.7');
  await poll(owner, 'tv-living-room');
  assert.deepEqual(listed(owner), ['tv-living-room']);
  assert.deepEqual(listed(stranger), []);
  assert.throws(() => sendCommand(stranger, 'tv-living-room', { type: 'pause' }), (error) => error.status === 404);

  // While a poll is open the channel is alive however long it lasts.
  const open = poll(owner, 'tv-living-room', 200);
  assert.deepEqual(listed(owner, null, Date.now() + 40_000), ['tv-living-room']);
  await open;
  // 35 s after the last poll ended (the TV app was killed), it is gone.
  assert.deepEqual(listed(owner, null, Date.now() + 36_000), []);

  // In standby it keeps running but is not listed, and commands are refused.
  reportDevice(owner, 'tv-living-room', { name: 'Sala', state: { status: 'idle' }, awake: false }, '203.0.113.7');
  assert.deepEqual(listed(owner), []);
  assert.throws(() => sendCommand(owner, 'tv-living-room', { type: 'play', watchUrl: '/watch/x', episode: 1 }), (error) => error.status === 409);

  // Awake again; the phone learns whether it shares the TV's network.
  reportDevice(owner, 'tv-living-room', { name: 'Sala', state: { status: 'idle' }, awake: true }, '203.0.113.7');
  assert.equal(listDevices(owner, '203.0.113.7').devices[0].sameNetwork, true);
  assert.equal(listDevices(owner, '198.51.100.20').devices[0].sameNetwork, false);
  assert.equal(listDevices(owner, null).devices[0].sameNetwork, null);
  // Another network is still controllable: commands go through the server.
  assert.equal(sendCommand(owner, 'tv-living-room', { type: 'pause' }).ok, true);
});

test('Same network: one public IPv4, one private /24 or one IPv6 /64', () => {
  assert.equal(sameNetwork('203.0.113.7', '203.0.113.7'), true);
  assert.equal(sameNetwork('203.0.113.7', '203.0.113.8'), false);
  assert.equal(sameNetwork('192.168.50.3', '192.168.50.20'), true);
  assert.equal(sameNetwork('192.168.50.3', '192.168.1.20'), false);
  assert.equal(sameNetwork('10.0.0.5', '10.0.0.9'), true);
  assert.equal(sameNetwork('2001:db8:1:2::10', '2001:db8:1:2:abcd::1'), true);
  assert.equal(sameNetwork('2001:db8:1:2::10', '2001:db8:1:3::10'), false);
  assert.equal(sameNetwork('203.0.113.7', '2001:db8::1'), null);
  assert.equal(sameNetwork(null, '203.0.113.7'), null);
});

// The library serves media in-process through `local://` URLs; these tests
// stand in for it with a map of URL → body.
async function withLocalMedia(context, files) {
  const { provider } = await import('../server.mjs');
  context.mock.method(provider.media, 'read', async (value) => {
    const file = files[value];
    if (!file) throw new Error(`Unexpected media read ${value}`);
    return { url: value, type: file.type, body: Buffer.from(file.body) };
  });
}

test('Cast links open one playback without the identity token and can be revoked', async () => {
  const { castSource, revokeCast, rewriteUriLines, shareForCast, sources } = await import('../server.mjs');
  const owner = { userId: 'cast-owner', token: 'd'.repeat(64) };
  sources.set('cast-source-1', {
    hlsUrl: 'local://f1/a0/master.m3u8', userId: owner.userId, accessToken: owner.token, expiresAt: Date.now() + 60_000,
    subtitles: [{ name: 'English', language: 'en', default: true, forced: false, vttUrl: 'local://f1/sub/0.vtt' }, { name: 'Signs', language: 'en', default: false, forced: true, vttUrl: 'local://f1/sub/1.vtt' }],
  });
  const playbackUrl = `https://server.example/watch/cast-source-1/master.m3u8?access_token=${owner.token}`;
  assert.throws(() => shareForCast({ userId: 'someone-else', token: 'e'.repeat(64) }, playbackUrl, 'https://server.example'), (error) => error.status === 403);
  assert.throws(() => shareForCast(owner, 'https://server.example/watch/missing/master.m3u8', 'https://server.example'), (error) => error.status === 404);

  const share = shareForCast(owner, playbackUrl, 'https://server.example');
  assert.match(share.url, /^https:\/\/server\.example\/cast\/[\w-]{32}\/master\.m3u8$/);
  assert.ok(!JSON.stringify(share).includes(owner.token));
  assert.deepEqual(share.subtitles.map((track) => [track.id, track.language, track.forced, track.default]), [[1, 'en', false, true], [2, 'en', true, false]]);
  assert.equal(share.subtitles[0].url, `https://server.example/cast/${share.id}/subtitle/0.vtt`);
  assert.equal(castSource(share.id).id, 'cast-source-1');

  // Segments and nested playlists become token-free cast links.
  const castLink = (upstream) => `/cast/${share.id}/media?url=${encodeURIComponent(upstream)}`;
  const rewritten = rewriteUriLines('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI="audio.m3u8"\nseg-1.ts\n', 'local://f1/a0/index.m3u8', 'cast-source-1', sources.get('cast-source-1'), castLink);
  assert.ok(!rewritten.includes('access_token'));
  assert.ok(rewritten.includes(`URI="/cast/${share.id}/media?url=${encodeURIComponent('local://f1/a0/audio.m3u8')}"`));
  assert.ok(rewritten.includes(`/cast/${share.id}/media?url=${encodeURIComponent('local://f1/a0/seg-1.ts')}`));
  // Without a link builder the app's own playlists keep the token.
  assert.ok(rewriteUriLines('seg-1.ts', 'local://f1/a0/index.m3u8', 'cast-source-1', sources.get('cast-source-1')).includes('access_token='));

  // Another identity cannot revoke it; its owner can.
  revokeCast({ userId: 'someone-else' }, share.id);
  assert.ok(castSource(share.id));
  revokeCast(owner, share.id);
  assert.equal(castSource(share.id), null);

  // A link stops working when its playback expires.
  const second = shareForCast(owner, playbackUrl, 'https://server.example');
  sources.delete('cast-source-1');
  assert.equal(castSource(second.id), null);
});

test('Cast routes answer the receiver with CORS, without the identity token', async (context) => {
  const { server, shareForCast, sources } = await import('../server.mjs');
  const vtt = 'WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n';
  await withLocalMedia(context, {
    'local://f2/a0/master.m3u8': { type: 'application/vnd.apple.mpegurl', body: '#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia.m3u8\n' },
    'local://f2/sub/0.vtt': { type: 'text/vtt', body: vtt },
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  context.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const owner = { userId: 'cast-http', token: 'f'.repeat(64) };
  sources.set('cast-source-2', {
    hlsUrl: 'local://f2/a0/master.m3u8', userId: owner.userId, accessToken: owner.token, expiresAt: Date.now() + 60_000,
    subtitles: [{ name: 'English', language: 'en', default: true, forced: false, vttUrl: 'local://f2/sub/0.vtt' }],
  });
  const share = shareForCast(owner, `${base}/watch/cast-source-2/master.m3u8`, base);

  const preflight = await fetch(`${base}/cast/${share.id}/master.m3u8`, { method: 'OPTIONS' });
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers.get('access-control-allow-origin'), '*');
  assert.match(preflight.headers.get('access-control-allow-headers'), /Range/);

  const subtitle = await fetch(share.subtitles[0].url);
  assert.equal(subtitle.status, 200);
  assert.equal(subtitle.headers.get('access-control-allow-origin'), '*');
  assert.match(subtitle.headers.get('content-type'), /text\/vtt/);
  // Side-loaded tracks keep the file's own timing (no HLS timestamp map).
  assert.equal(await subtitle.text(), vtt);

  const forged = await fetch(`${base}/cast/${share.id}/media?url=${encodeURIComponent('local://f2/a0/seg0.ts')}&sig=nope`);
  assert.equal(forged.status, 403);
  const unknown = await fetch(`${base}/cast/${'x'.repeat(32)}/master.m3u8`);
  assert.equal(unknown.status, 404);
  assert.equal(unknown.headers.get('access-control-allow-origin'), '*');
  sources.delete('cast-source-2');
});

test('Cast segments reach the receiver as MPEG-TS behind a .ts link', async (context) => {
  const { mediaType, server, shareForCast, sources } = await import('../server.mjs');
  const packet = Buffer.alloc(188 * 2, 0xff); packet[0] = 0x47; packet[188] = 0x47;
  const ts = { type: 'video/mp2t', body: packet };
  await withLocalMedia(context, {
    'local://f3/a0/master.m3u8': { type: 'application/vnd.apple.mpegurl', body: '#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nmedia.m3u8\n' },
    'local://f3/a0/media.m3u8': { type: 'application/vnd.apple.mpegurl', body: '#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\nseg1.ts\n#EXT-X-ENDLIST\n' },
    'local://f3/a0/seg0.ts': ts, 'local://f3/a0/seg1.ts': ts,
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  context.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;
  const owner = { userId: 'cast-ts', token: 'c'.repeat(64) };
  sources.set('cast-source-3', { hlsUrl: 'local://f3/a0/master.m3u8', userId: owner.userId, accessToken: owner.token, expiresAt: Date.now() + 60_000, subtitles: [] });
  const share = shareForCast(owner, `${base}/watch/cast-source-3/master.m3u8`, base);

  const master = await (await fetch(share.url)).text();
  const variantPath = master.split('\n').find((line) => line.startsWith('/cast/'));
  assert.match(variantPath, new RegExp(`^/cast/${share.id}/media\\?url=`));
  const variant = await fetch(`${base}${variantPath}`);
  assert.equal(variant.headers.get('access-control-allow-origin'), '*');
  const segments = (await variant.text()).split('\n').filter((line) => line.startsWith('/cast/'));
  assert.equal(segments.length, 2);
  for (const path of segments) {
    assert.match(path, new RegExp(`^/cast/${share.id}/media\\.ts\\?url=`));
    const segment = await fetch(`${base}${path}`);
    assert.equal(segment.status, 200);
    assert.equal(segment.headers.get('content-type'), 'video/mp2t');
    assert.equal(segment.headers.get('access-control-allow-origin'), '*');
  }

  // Only real container signatures change the type.
  assert.equal(mediaType(Buffer.from('00000018667479706d703432', 'hex'), 'image/png'), 'video/mp4');
  assert.equal(mediaType(Buffer.from([0xff, 0xf1, 0x50, 0x80]), ''), 'audio/aac');
  assert.equal(mediaType(Buffer.alloc(16, 7), 'application/octet-stream'), 'application/octet-stream');
  sources.delete('cast-source-3');
});

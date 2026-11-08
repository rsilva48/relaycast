import http from 'node:http';
import { createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { delimiter, dirname, join, resolve } from 'node:path';
import { HttpError } from './http-error.mjs';
import { createLocalProvider } from './library.mjs';

// Settings come from SERVER_* environment variables.
const setting = (name) => process.env[`SERVER_${name}`];
const host = setting('HOST') ?? '0.0.0.0';
const port = Number(setting('PORT') ?? 8787);
// Kept in sync with version.txt by scripts/version.mjs; reported by /health.
const serverVersion = (() => {
  try { return JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version ?? null; } catch { return null; }
})();
// DECLARE_AUDIO_LANGUAGE=false stops announcing the audio language in the
// master playlist (useful to compare subtitle timing with and without it).
const declareAudioLanguage = process.env.DECLARE_AUDIO_LANGUAGE !== 'false';
const sources = new Map();
const seriesCache = new Map();
// Episode counts per series for the Continue screen, refreshed every 15 minutes.
const seriesSummaries = new Map();
const serverUsers = configuredServerUsers();
const serverAdminToken = cleanText(setting('ADMIN_TOKEN'));
const historyStorePath = resolve(process.env.HISTORY_STORE_PATH ?? `${process.cwd()}/data/history.json`);
const userStorePath = resolve(setting('USER_STORE_PATH') ?? `${process.cwd()}/data/users.json`);
// Stored next to the history file by default so an existing data volume keeps it.
const preferencesStorePath = resolve(process.env.PREFERENCES_STORE_PATH ?? join(dirname(historyStorePath), 'preferences.json'));
const profilesStorePath = resolve(process.env.PROFILES_STORE_PATH ?? join(dirname(historyStorePath), 'profiles.json'));
const mediaUrlSigningKey = randomUUID();
let historyUsers;
let historyMutation = Promise.resolve();
let persistedUsers;
let userMutation = Promise.resolve();
let preferenceUsers;
let preferencesMutation = Promise.resolve();
let profileUsers;
let profilesMutation = Promise.resolve();

// Serialises writes per store. A failed write rejects only its own caller; the
// queue keeps accepting later writes instead of staying rejected forever.
function enqueue(queue, task) {
  const run = queue.then(task);
  return { run, queue: run.catch(() => {}) };
}
async function writeJsonAtomically(path, value) {
  await mkdir(dirname(path), { recursive: true });
  const temporaryPath = `${path}.${process.pid}.${randomUUID()}.tmp`;
  await writeFile(temporaryPath, JSON.stringify(value), 'utf8');
  await rename(temporaryPath, path);
}

// The catalog is the local library (library.mjs): folders of video files on
// this computer, served through the same API the apps always used. A provider
// implements:
//   watchUrl(value)        canonical `${origin}/watch/…` URL; rebases other origins
//   search(keyword)        → [catalog item]
//   latest(searchParams)   → { results, page, hasNext }
//   series(watchUrl)       → { title, titleJp, watchUrl, episodes: [{ number, title, ref }], seasons }
//   servers(episodeRef)    → [{ index, name, type: 'sub' | 'dub', ref }]
//   source(serverRef)      → { hlsUrl, intro, outro, rawSubtitles, audio }
//   media.read(url)        → { url, type, body } for the `local://` URLs of a source
// Catalog items: { title, titleJp, watchUrl, imageUrl, meta, rating, score, mediaType, year, episode? }.
const dataDirectory = dirname(historyStorePath);
const libraryConfigPath = resolve(setting('LIBRARY_CONFIG_PATH') ?? join(dataDirectory, 'library.json'));
const provider = createLocalProvider({
  folders: [],
  dataDir: dataDirectory,
  ffmpegPath: process.env.FFMPEG_PATH || 'ffmpeg',
  ffprobePath: process.env.FFPROBE_PATH || 'ffprobe',
  concurrency: Number(process.env.FFMPEG_CONCURRENCY ?? 3),
  forceTranscode: process.env.FORCE_TRANSCODE === 'true',
  videoEncoder: process.env.VIDEO_ENCODER ?? '',
  log: (message) => console.info(`[server] ${message}`),
});
// Folders come from LIBRARY_PATHS (separated like PATH) and the saved
// library.json, which the companion app edits through /api/admin/library.
async function savedLibraryFolders() {
  try { return (JSON.parse(await readFile(libraryConfigPath, 'utf8')).folders ?? []).map(cleanText).filter(Boolean); } catch { return []; }
}
const environmentFolders = cleanText(process.env.LIBRARY_PATHS).split(delimiter).map(cleanText).filter(Boolean);
async function configuredFolders() { return [...new Set([...environmentFolders, ...await savedLibraryFolders()])]; }
async function saveLibraryFolders(folders) {
  const clean = [...new Set((Array.isArray(folders) ? folders : []).map((folder) => cleanText(folder)).filter(Boolean))];
  if (clean.length > 50) throw new HttpError(400, 'Too many library folders');
  await writeJsonAtomically(libraryConfigPath, { folders: clean });
  return await provider.setFolders([...new Set([...environmentFolders, ...clean])]);
}
const libraryRescanMinutes = Number(process.env.LIBRARY_RESCAN_MINUTES ?? 10);
if (!setting('NO_LISTEN') && !setting('NO_SCAN')) {
  provider.setFolders(await configuredFolders());
  if (libraryRescanMinutes > 0) setInterval(() => provider.rescan(), libraryRescanMinutes * 60 * 1000).unref();
}
// Catalog images are paths on this server (`/art/<id>.jpg`); apps need them
// absolute, on the address they used to reach the server.
function withOrigin(value, origin) {
  if (Array.isArray(value)) return value.map((item) => withOrigin(item, origin));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, key === 'imageUrl' && typeof item === 'string' && item.startsWith('/') ? `${origin}${item}` : withOrigin(item, origin)]));
  }
  return value;
}

function json(response, status, body) {
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  response.end(JSON.stringify(body));
}
function text(response, status, body, type = 'text/plain; charset=utf-8') {
  response.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' }); response.end(body);
}
function absoluteUrl(value, parent) { return new URL(value, parent).toString(); }
function protectedPath(path, accessToken) {
  if (!accessToken) return path;
  return `${path}${path.includes('?') ? '&' : '?'}access_token=${encodeURIComponent(accessToken)}`;
}
function mediaUrlSignature(id, upstream) { return createHmac('sha256', mediaUrlSigningKey).update(`${id}\n${upstream}`).digest('base64url'); }
function mediaUrl(id, upstream, accessToken) { return protectedPath(`/media/${id}?url=${encodeURIComponent(upstream)}&sig=${mediaUrlSignature(id, upstream)}`, accessToken); }
function isPlaylist(url, contentType) { return new URL(url).pathname.toLowerCase().endsWith('.m3u8') || contentType.includes('mpegurl'); }
function cleanText(value) { return String(value ?? '').replace(/\s+/g, ' ').trim(); }
function normalizedUserId(value) {
  const userId = cleanText(value).toLowerCase();
  if (!/^[a-z0-9][a-z0-9_-]{0,39}$/.test(userId)) throw new Error('Server user IDs must use 1-40 lowercase letters, numbers, hyphens, or underscores');
  return userId;
}
function configuredServerUsers() {
  const configured = cleanText(setting('USER_TOKENS'));
  if (configured) {
    let entries;
    try { entries = Object.entries(JSON.parse(configured)); } catch { throw new Error('SERVER_USER_TOKENS must be a JSON object of user IDs to tokens'); }
    if (!entries.length) throw new Error('SERVER_USER_TOKENS cannot be empty');
    const users = entries.map(([userId, token]) => ({ userId: normalizedUserId(userId), token: cleanText(token) }));
    if (users.some((user) => !user.token) || new Set(users.map((user) => user.userId)).size !== users.length) throw new Error('SERVER_USER_TOKENS contains an invalid user or token');
    return users;
  }
  const token = cleanText(setting('ACCESS_TOKEN'));
  return token ? [{ userId: 'default', token }] : [];
}
function sameSecret(left, right) {
  const leftBuffer = Buffer.from(String(left)); const rightBuffer = Buffer.from(String(right));
  return leftBuffer.length === rightBuffer.length && timingSafeEqual(leftBuffer, rightBuffer);
}
function requestedAccessToken(request, requestUrl) {
  const authorization = cleanText(request.headers.authorization);
  const bearer = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
  return cleanText(request.headers['x-bridge-token'] ?? bearer ?? requestUrl.searchParams.get('access_token'));
}
function tokenHash(token, salt) { return createHash('sha256').update(`${salt}:${token}`).digest('hex'); }
function requestedAdminToken(request) { return cleanText(request.headers['x-bridge-admin-token']); }
function isAdministrator(request) { return Boolean(serverAdminToken) && sameSecret(requestedAdminToken(request), serverAdminToken); }
async function loadPersistedUsers() {
  if (persistedUsers) return persistedUsers;
  persistedUsers = [];
  try {
    const parsed = JSON.parse(await readFile(userStorePath, 'utf8'));
    const users = Array.isArray(parsed?.users) ? parsed.users : [];
    persistedUsers = users.flatMap((user) => {
      try {
        const userId = normalizedUserId(user?.userId);
        const salt = cleanText(user?.salt); const hash = cleanText(user?.tokenHash);
        if (!/^[a-f0-9]{32}$/i.test(salt) || !/^[a-f0-9]{64}$/i.test(hash)) return [];
        const parent = cleanText(user?.parent) || undefined;
        return [{ userId, salt, tokenHash: hash, label: historyText(user?.label, 'Private user', 80), createdAt: cleanText(user?.createdAt), parent }];
      } catch { return []; }
    });
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`[server] User store could not be read: ${error.message}`);
  }
  return persistedUsers;
}
function validUserToken(value) { return /^[a-f0-9]{64}$/i.test(value); }
async function registerUser(token, label, parent = undefined) {
  if (!validUserToken(token)) throw new Error('User token must be a 64-character hexadecimal value');
  await loadPersistedUsers();
  const { run, queue } = enqueue(userMutation, async () => {
    const users = await loadPersistedUsers();
    if (serverUsers.some((user) => sameSecret(token, user.token))) throw new Error('This token is reserved by server configuration');
    const existing = users.find((user) => sameSecret(user.tokenHash, tokenHash(token, user.salt)));
    if (existing) return existing;
    if (users.length >= 1000) throw new Error('User limit reached');
    const user = { userId: randomUUID(), salt: randomBytes(16).toString('hex'), tokenHash: '', label: historyText(label, 'Private user', 80), createdAt: new Date().toISOString(), parent };
    user.tokenHash = tokenHash(token, user.salt);
    users.push(user);
    await writeJsonAtomically(userStorePath, { version: 1, users });
    return user;
  });
  userMutation = queue;
  return run;
}
async function userForToken(token) {
  const configuredUser = serverUsers.find((user) => sameSecret(token, user.token));
  if (configuredUser) return configuredUser;
  const users = await loadPersistedUsers();
  const storedUser = users.find((user) => sameSecret(user.tokenHash, tokenHash(token, user.salt)));
  return storedUser ? { userId: storedUser.userId, token } : null;
}

// An identity creates more profiles (a TV adding a person) without the
// administrator token. Each creator is limited so a leaked token cannot fill
// the user store; a server without identities (open mode) has none to create.
const identityCreations = new Map();
async function createIdentity(user, label) {
  if (!user?.token) throw new HttpError(409, 'This server does not use identities');
  const users = await loadPersistedUsers();
  if (users.filter((candidate) => candidate.parent === user.userId).length >= 10) throw new HttpError(429, 'This identity already created 10 profiles');
  const now = Date.now();
  const recent = (identityCreations.get(user.userId) ?? []).filter((at) => now - at < 60 * 60 * 1000);
  if (recent.length >= 5) throw new HttpError(429, 'Too many profiles created in the last hour; try again later');
  const token = randomBytes(32).toString('hex');
  const registered = await registerUser(token, label, user.userId);
  identityCreations.set(user.userId, [...recent, now]);
  return { token, userId: registered.userId, label: registered.label };
}

async function authenticatedUser(request, requestUrl) {
  const user = await userForToken(requestedAccessToken(request, requestUrl));
  if (user) return user;
  const users = await loadPersistedUsers();
  return !serverUsers.length && !users.length && !serverAdminToken ? { userId: 'local', token: '' } : null;
}
function forwardedValue(request, name) { return cleanText(request.headers[name]).split(',')[0].trim(); }
function requestOrigin(request) {
  const forwardedProtocol = forwardedValue(request, 'x-forwarded-proto');
  const protocol = forwardedProtocol === 'https' ? 'https' : 'http';
  const authority = forwardedValue(request, 'x-forwarded-host') || cleanText(request.headers.host);
  if (!authority) throw new Error('Request host was not provided');
  return `${protocol}://${authority}`;
}
function base64Url(value) { return Buffer.from(value).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, ''); }
function playlistDescriptor(value) {
  const url = new URL(value); const token = url.searchParams.get('token'); let expiresIn = null;
  try {
    const payload = Buffer.from(String(token ?? '').split('.')[0], 'base64url').toString('utf8');
    const expiry = Number(payload.split('|')[0]); if (Number.isFinite(expiry)) expiresIn = expiry - Math.floor(Date.now() / 1000);
  } catch { /* Diagnostics deliberately omit token values. */ }
  return `${url.hostname} token=${token ? 'yes' : 'no'}${expiresIn === null ? '' : ` expiresIn=${expiresIn}s`}`;
}
function paddedKey(value, length) {
  const result = Buffer.alloc(length); Buffer.from(value, 'utf8').copy(result); return result;
}
function resolveStreamFile(sources) {
  if (typeof sources === 'string') return sources;
  if (Array.isArray(sources)) return resolveStreamFile(sources[0]);
  return typeof sources?.file === 'string' ? sources.file : null;
}
function subtitleDetails(track, index) {
  // The provider often labels tracks as “Unknown language”.  Derive both the
  // user-facing name and BCP-47 tag from the label, srclang, and VTT filename.
  // Apple otherwise folds all `und` tracks into a single Unknown entry.
  const suppliedLabel = cleanText(track?.label);
  const value = `${suppliedLabel} ${track?.srclang ?? ''} ${track?.file ?? ''}`.toLowerCase();
  const hasText = (pattern) => pattern.test(value);
  const hasToken = (...tokens) => tokens.some((token) => new RegExp(`(?:^|[\\s_./-])${token}(?=$|[\\s_./-])`, 'i').test(value));
  const qualifiers = [];
  const addQualifier = (name, pattern) => { if (hasText(pattern) && !qualifiers.includes(name)) qualifiers.push(name); };
  addQualifier('Latin American', /latin[\s_-]*american/);
  addQualifier('European', /(?:^|[\s_./-])european(?=$|[\s_./-])/);
  addQualifier('Brazilian', /(?:^|[\s_./-])brazilian(?=$|[\s_./-])/);
  if (!qualifiers.includes('Latin American')) addQualifier('Latin', /(?:^|[\s_./-])latin(?=$|[\s_./-])/);
  addQualifier('Forced', /(?:^|[\s_./-])forced(?=$|[\s_./-])/);
  addQualifier('Dubtitle', /(?:^|[\s_./-])dubtitle(?=$|[\s_./-])/);
  addQualifier('SDH', /(?:^|[\s_./-])sdh(?=$|[\s_./-])/);
  addQualifier('CC', /(?:^|[\s_./-])cc(?=$|[\s_./-])|closed[\s_-]*captions?/);
  // Forced tracks only carry signs and songs; Dubtitle tracks follow the dub script.
  const kind = { forced: qualifiers.includes('Forced'), dubtitle: qualifiers.includes('Dubtitle') };
  const named = (name, language, extra = []) => {
    const descriptors = [...extra, ...qualifiers];
    return { name: descriptors.length ? `${name} (${descriptors.join(', ')})` : name, language, ...kind };
  };

  const chinese = hasText(/chinese|中文/) || hasToken('chi', 'zho', 'cmn', 'yue', 'zht', 'zhs', 'cht');
  if (chinese) {
    const traditional = hasText(/traditional|hant/) || hasToken('zht', 'cht');
    return named('Chinese', traditional ? 'zh-Hant' : 'zh-Hans', [traditional ? 'Traditional' : 'Simplified']);
  }

  const languages = [
    ['English', 'en', ['english', 'eng', 'en']], ['Spanish', 'es', ['spanish', 'español', 'espanol', 'spa', 'es']],
    ['French', 'fr', ['french', 'français', 'francais', 'fre', 'fra', 'fr']], ['German', 'de', ['german', 'deutsch', 'ger', 'deu', 'de']],
    ['Italian', 'it', ['italian', 'ita', 'it']], ['Portuguese', 'pt', ['portuguese', 'português', 'portugues', 'por', 'pt']],
    ['Polish', 'pl', ['polish', 'pol', 'pl']], ['Russian', 'ru', ['russian', 'русский', 'rus', 'ru']],
    ['Ukrainian', 'uk', ['ukrainian', 'ukr', 'uk']], ['Arabic', 'ar', ['arabic', 'ara', 'ar']],
    ['Indonesian', 'id', ['indonesian', 'ind', 'id']], ['Japanese', 'ja', ['japanese', 'jpn', 'ja']],
    ['Korean', 'ko', ['korean', 'kor', 'ko']], ['Malay', 'ms', ['malay', 'may', 'msa', 'ms']],
    ['Thai', 'th', ['thai', 'tha', 'th']], ['Vietnamese', 'vi', ['vietnamese', 'vie', 'vi']],
    ['Chinese', 'zh-Hans', ['simplified', 'chi', 'zho']], ['Turkish', 'tr', ['turkish', 'tur', 'tr']],
    ['Hindi', 'hi', ['hindi', 'hin', 'hi']], ['Bengali', 'bn', ['bengali', 'ben', 'bn']],
    ['Dutch', 'nl', ['dutch', 'nederlands', 'dut', 'nld', 'nl']], ['Swedish', 'sv', ['swedish', 'swe', 'sv']],
    ['Danish', 'da', ['danish', 'dan', 'da']], ['Finnish', 'fi', ['finnish', 'fin', 'fi']],
    ['Norwegian', 'no', ['norwegian', 'nor', 'no']], ['Norwegian (Bokmål)', 'nb', ['nob', 'nb']], ['Czech', 'cs', ['czech', 'ces', 'cze', 'cs']],
    ['Romanian', 'ro', ['romanian', 'română', 'romana', 'ron', 'rum', 'ro']], ['Hungarian', 'hu', ['hungarian', 'hun', 'hu']],
    ['Greek', 'el', ['greek', 'ell', 'gre', 'el']], ['Hebrew', 'he', ['hebrew', 'heb', 'he']],
    ['Persian', 'fa', ['persian', 'farsi', 'fas', 'per', 'fa']], ['Filipino', 'fil', ['filipino', 'tagalog', 'fil', 'tgl']],
    ['Tamil', 'ta', ['tamil', 'tam', 'ta']], ['Telugu', 'te', ['telugu', 'tel', 'te']], ['Urdu', 'ur', ['urdu', 'urd', 'ur']],
    ['Catalan', 'ca', ['catalan', 'cat', 'ca']], ['Basque', 'eu', ['basque', 'euskara', 'baq', 'eus', 'eu']],
    ['Galician', 'gl', ['galician', 'glg', 'gl']], ['Croatian', 'hr', ['croatian', 'hrv', 'hr']],
    ['Bulgarian', 'bg', ['bulgarian', 'bul', 'bg']], ['Serbian', 'sr', ['serbian', 'srp', 'sr']],
    ['Slovak', 'sk', ['slovak', 'slk', 'slo', 'sk']], ['Slovenian', 'sl', ['slovenian', 'slv', 'sl']],
    ['Lithuanian', 'lt', ['lithuanian', 'lit', 'lt']], ['Latvian', 'lv', ['latvian', 'lav', 'lv']],
    ['Estonian', 'et', ['estonian', 'est', 'et']], ['Icelandic', 'is', ['icelandic', 'isl', 'ice', 'is']],
    ['Welsh', 'cy', ['welsh', 'cym', 'wel', 'cy']],
  ];
  for (const [name, language, aliases] of languages) {
    if (aliases.some((alias) => hasText(new RegExp(`\\b${alias}\\b`, 'i')) || hasToken(alias))) {
      const latinAmerican = qualifiers.includes('Latin American'); const european = qualifiers.includes('European'); const brazilian = qualifiers.includes('Brazilian');
      const regionalLanguage = name === 'Spanish' && latinAmerican ? 'es-419'
        : name === 'Spanish' && european ? 'es-ES'
          : name === 'French' && european ? 'fr-FR'
            : name === 'Portuguese' && brazilian ? 'pt-BR'
              : name === 'Portuguese' && european ? 'pt-PT' : language;
      return named(name, regionalLanguage);
    }
  }

  const usableLabel = /^(?:unknown(?: language)?|subtitles?(?:\s+\d+)?)$/i.test(suppliedLabel) ? '' : suppliedLabel;
  return { name: usableLabel || `Subtitles ${index + 1}`, language: 'und', ...kind };
}
// Subtitle tracks from a player: readable, unique names, BCP-47 tags and a
// full English track as default (never a Forced one, which is nearly empty).
function subtitleTracks(tracks, baseUrl) {
  const subtitles = (Array.isArray(tracks) ? tracks : [])
    .filter((track) => /^(captions|subtitles)$/i.test(cleanText(track?.kind)))
    .map((track, index) => ({ ...subtitleDetails(track, index), vttUrl: absoluteUrl(track?.file ?? '', baseUrl) }))
    .filter((track) => track.vttUrl && new URL(track.vttUrl).pathname.toLowerCase().endsWith('.vtt'));
  const seen = new Map();
  for (const track of subtitles) {
    const count = (seen.get(track.name) ?? 0) + 1;
    seen.set(track.name, count);
    if (count > 1) track.name = `${track.name} ${count}`;
  }
  const preferences = [
    (track) => track.language === 'en' && !track.forced && !track.dubtitle,
    (track) => track.language === 'en' && !track.forced,
    (track) => !track.forced,
  ];
  const defaultTrack = preferences.map((matches) => subtitles.find(matches)).find(Boolean) ?? subtitles[0];
  subtitles.forEach((track) => { track.default = track === defaultTrack; });
  return subtitles;
}
function validSkipRange(value) {
  const start = Number(value?.start), end = Number(value?.end);
  return value && Number.isFinite(start) && Number.isFinite(end) && start >= 0 && end > start ? { start, end } : null;
}
function readBody(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    request.on('data', (part) => { body += part; if (body.length > 100000) reject(new Error('Request too large')); });
    request.on('end', () => resolve(body)); request.on('error', reject);
  });
}
// A binary upload (the poster the companion sends), capped at 8 MB.
function readBinary(request, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const parts = []; let size = 0;
    request.on('data', (part) => { size += part.length; if (size > limit) { reject(new HttpError(413, 'The file is too large')); request.destroy(); } else parts.push(part); });
    request.on('end', () => resolve(Buffer.concat(parts))); request.on('error', reject);
  });
}
function historyDate(value) {
  // Swift's default JSONEncoder encodes Date as seconds since 2001-01-01.
  if (typeof value === 'number' && Number.isFinite(value)) return new Date((value + 978307200) * 1000);
  const parsed = new Date(value);
  return Number.isNaN(parsed.valueOf()) ? new Date() : parsed;
}
function historyTimestamp(date) {
  return (new Date(date).valueOf() / 1000) - 978307200;
}
function historyText(value, fallback = '', maximum = 500) {
  return cleanText(value).slice(0, maximum) || fallback;
}
// History keeps the provider's watch URL rebased onto the library origin, so
// entries saved under another origin (the earlier catalog) keep the same path.
function normalizeHistoryEntry(value) {
  const watchUrl = provider.watchUrl(historyText(value?.watchUrl, '', 2000));
  const episode = Number(value?.episode);
  if (!Number.isInteger(episode) || episode < 1 || episode > 100000) throw new Error('History episode is invalid');
  const position = Number(value?.positionSeconds);
  const duration = Number(value?.durationSeconds ?? NaN);
  const totalEpisodes = Number(value?.totalEpisodes ?? NaN);
  return {
    seriesTitle: historyText(value?.seriesTitle, 'Untitled'),
    titleJp: value?.titleJp ? historyText(value.titleJp, '', 500) : null,
    watchUrl,
    imageUrl: value?.imageUrl ? historyText(value.imageUrl, '', 2000) : null,
    episode,
    episodeTitle: historyText(value?.episodeTitle, `Episode ${episode}`),
    serverName: historyText(value?.serverName, ''),
    serverKind: ['sub', 'dub'].includes(value?.serverKind) ? value.serverKind : null,
    positionSeconds: Number.isFinite(position) ? Math.max(0, position) : 0,
    durationSeconds: Number.isFinite(duration) && duration > 0 ? duration : null,
    completed: value?.completed === true,
    totalEpisodes: Number.isInteger(totalEpisodes) && totalEpisodes > 0 ? totalEpisodes : null,
    updatedAt: historyDate(value?.updatedAt).toISOString(),
  };
}
function normalizeHistoryDeletion(value) {
  return {
    watchUrl: provider.watchUrl(historyText(value?.watchUrl, '', 2000)),
    updatedAt: historyDate(value?.updatedAt).toISOString(),
  };
}
function historyKey(entry) { return entry.watchUrl; }
function serializeHistoryEntry(entry) {
  return { ...entry, updatedAt: historyTimestamp(entry.updatedAt) };
}
function serializeHistoryDeletion(deletion) {
  return { ...deletion, updatedAt: historyTimestamp(deletion.updatedAt) };
}
function historyState(entries = [], deletions = []) {
  const latestEntries = new Map(); const latestDeletions = new Map();
  for (const entry of entries) {
    const previous = latestEntries.get(historyKey(entry));
    if (!previous || new Date(entry.updatedAt) >= new Date(previous.updatedAt)) latestEntries.set(historyKey(entry), entry);
  }
  for (const deletion of deletions) {
    const previous = latestDeletions.get(deletion.watchUrl);
    if (!previous || new Date(deletion.updatedAt) >= new Date(previous.updatedAt)) latestDeletions.set(deletion.watchUrl, deletion);
  }
  for (const [watchUrl, deletion] of latestDeletions) {
    const entry = latestEntries.get(watchUrl);
    if (entry && new Date(entry.updatedAt) > new Date(deletion.updatedAt)) latestDeletions.delete(watchUrl);
    else latestEntries.delete(watchUrl);
  }
  return {
    entries: [...latestEntries.values()].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt)).slice(0, 100),
    deletions: [...latestDeletions.values()].sort((left, right) => new Date(right.updatedAt) - new Date(left.updatedAt)).slice(0, 500),
  };
}
function serializeHistoryState(state) {
  return { entries: state.entries.map(serializeHistoryEntry), deletions: state.deletions.map(serializeHistoryDeletion) };
}
async function loadHistory() {
  if (historyUsers) return historyUsers;
  historyUsers = new Map();
  try {
    const parsed = JSON.parse(await readFile(historyStorePath, 'utf8'));
    const rawUsers = parsed?.users && typeof parsed.users === 'object'
      ? parsed.users
      : parsed?.profiles && typeof parsed.profiles === 'object'
        ? parsed.profiles
      : { default: Array.isArray(parsed?.entries) ? parsed.entries : [] };
    for (const [userId, value] of Object.entries(rawUsers)) {
      const normalizedUser = normalizedUserId(userId);
      const rawEntries = Array.isArray(value) ? value : value?.entries;
      const rawDeletions = Array.isArray(value) ? [] : value?.deletions;
      if (!Array.isArray(rawEntries)) continue;
      const normalizedEntries = []; const normalizedDeletions = [];
      for (const entry of rawEntries) {
        try { normalizedEntries.push(normalizeHistoryEntry(entry)); } catch { /* Ignore corrupted history rows. */ }
      }
      for (const deletion of Array.isArray(rawDeletions) ? rawDeletions : []) {
        try { normalizedDeletions.push(normalizeHistoryDeletion(deletion)); } catch { /* Ignore corrupted deletion rows. */ }
      }
      historyUsers.set(normalizedUser, historyState(normalizedEntries, normalizedDeletions));
    }
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`[server] History store could not be read: ${error.message}`);
  }
  return historyUsers;
}
async function storedHistory(userId) {
  await historyMutation; const users = await loadHistory();
  return serializeHistoryState(users.get(userId) ?? historyState());
}
async function mergeHistory(userId, payload) {
  if (!Array.isArray(payload?.entries) || payload.entries.length > 100 || (payload.deletions && (!Array.isArray(payload.deletions) || payload.deletions.length > 500))) {
    throw new Error('History payload is invalid');
  }
  const incomingEntries = payload.entries.map(normalizeHistoryEntry);
  const incomingDeletions = (payload.deletions ?? []).map(normalizeHistoryDeletion);
  const { run, queue } = enqueue(historyMutation, async () => {
    const users = await loadHistory(); const existing = users.get(userId) ?? historyState();
    const merged = historyState([...existing.entries, ...incomingEntries], [...existing.deletions, ...incomingDeletions]);
    users.set(userId, merged);
    await writeJsonAtomically(historyStorePath, { version: 4, users: Object.fromEntries(users) });
    return merged;
  });
  historyMutation = queue;
  return serializeHistoryState(await run);
}

// Catalog and playback preferences follow the user token, like the history.
// The newest `updatedAt` wins so every device converges on the same values.
function normalizePreferences(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HttpError(400, 'Preferences payload is invalid');
  const subtitleLanguage = cleanText(value.subtitleLanguage).slice(0, 35);
  return {
    titleLanguage: value.titleLanguage === 'jp' ? 'jp' : 'en',
    defaultTrackType: value.defaultTrackType === 'dub' ? 'dub' : 'sub',
    // Empty follows the device languages, `off` disables, otherwise a BCP-47 tag.
    subtitleLanguage: /^(?:off|[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*)$/.test(subtitleLanguage) ? subtitleLanguage : '',
    autoNext: value.autoNext !== false,
    autoSkipIntro: value.autoSkipIntro === true,
    autoSkipOutro: value.autoSkipOutro === true,
    hideAdult: value.hideAdult === true,
    updatedAt: historyDate(value.updatedAt).toISOString(),
  };
}
function serializePreferences(preferences) {
  return preferences ? { ...preferences, updatedAt: historyTimestamp(preferences.updatedAt) } : null;
}
async function loadPreferences() {
  if (preferenceUsers) return preferenceUsers;
  preferenceUsers = new Map();
  try {
    const parsed = JSON.parse(await readFile(preferencesStorePath, 'utf8'));
    for (const [userId, value] of Object.entries(parsed?.users ?? {})) {
      try { preferenceUsers.set(normalizedUserId(userId), normalizePreferences(value)); } catch { /* Ignore corrupted preference rows. */ }
    }
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`[server] Preferences store could not be read: ${error.message}`);
  }
  return preferenceUsers;
}
async function storedPreferences(userId) {
  await preferencesMutation; const users = await loadPreferences();
  return { preferences: serializePreferences(users.get(userId)) };
}
async function mergePreferences(userId, payload) {
  const incoming = normalizePreferences(payload?.preferences);
  const { run, queue } = enqueue(preferencesMutation, async () => {
    const users = await loadPreferences(); const existing = users.get(userId);
    if (!existing || new Date(incoming.updatedAt) > new Date(existing.updatedAt)) {
      users.set(userId, incoming);
      await writeJsonAtomically(preferencesStorePath, { version: 1, users: Object.fromEntries(users) });
    }
    return users.get(userId);
  });
  preferencesMutation = queue;
  return { preferences: serializePreferences(await run) };
}

// The profile of an identity: the name and picture phones and TVs show, and
// an email kept for recovery (only stored for now; sending recovery mail would
// need an SMTP server). A picture is a preset avatar (a color with an emoji,
// or the name's initial) or a small photo the app has already cropped and
// reduced. A write replaces the fields it sends and keeps the others.
const avatarColors = ['#4B49D6', '#E5484D', '#F76B15', '#E2A336', '#46A758', '#12A594', '#0090FF', '#8E4EC6', '#D6409F', '#6F6E77'];
const maxPhotoBytes = 64 * 1024;
function profileAvatar(value, previous) {
  if (value === null) return null;
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HttpError(400, 'Avatar is invalid');
  const color = typeof value.color === 'string' && avatarColors.includes(value.color.toUpperCase()) ? value.color.toUpperCase() : previous?.color ?? avatarColors[0];
  const emoji = value.emoji === undefined ? previous?.emoji ?? null : cleanText(value.emoji).slice(0, 16) || null;
  let photo = previous?.photo ?? null;
  if (value.photo === null || value.photo === '') photo = null;
  else if (value.photo !== undefined) {
    const bytes = Buffer.from(String(value.photo), 'base64');
    if (!bytes.length || bytes.length > maxPhotoBytes) throw new HttpError(400, `The photo must be smaller than ${maxPhotoBytes / 1024} KB`);
    const jpeg = bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
    const png = bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
    if (!jpeg && !png) throw new HttpError(400, 'The photo must be a JPEG or PNG image');
    photo = bytes.toString('base64');
  }
  return { color, emoji, photo };
}
function normalizeProfile(value, previous = {}) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new HttpError(400, 'Profile payload is invalid');
  const profile = { name: previous.name ?? null, email: previous.email ?? null, avatar: previous.avatar ?? null };
  if (value.name !== undefined) profile.name = cleanText(value.name).slice(0, 40) || null;
  if (value.email !== undefined) {
    const email = cleanText(value.email).toLowerCase();
    if (email && (email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email))) throw new HttpError(400, 'Email is invalid');
    profile.email = email || null;
  }
  if (value.avatar !== undefined) profile.avatar = profileAvatar(value.avatar, previous.avatar);
  profile.updatedAt = new Date().toISOString();
  return profile;
}
async function loadProfiles() {
  if (profileUsers) return profileUsers;
  profileUsers = new Map();
  try {
    const parsed = JSON.parse(await readFile(profilesStorePath, 'utf8'));
    for (const [userId, value] of Object.entries(parsed?.users ?? {})) {
      try { profileUsers.set(normalizedUserId(userId), { ...normalizeProfile(value), updatedAt: cleanText(value?.updatedAt) || null }); } catch { /* Ignore corrupted profile rows. */ }
    }
  } catch (error) {
    if (error?.code !== 'ENOENT') console.warn(`[server] Profiles store could not be read: ${error.message}`);
  }
  return profileUsers;
}
// `label` is the name the identity was created with (a TV profile's name, or
// the label typed on the phone): apps show it until a name is set.
async function serializeProfile(user, profile) {
  const label = (await loadPersistedUsers()).find((candidate) => candidate.userId === user.userId)?.label ?? null;
  return { profile: { name: profile?.name ?? null, email: profile?.email ?? null, avatar: profile?.avatar ?? null, label, updatedAt: profile?.updatedAt ?? null } };
}
async function storedProfile(user) {
  await profilesMutation; const users = await loadProfiles();
  return serializeProfile(user, users.get(user.userId));
}
async function updateProfile(user, payload) {
  const { run, queue } = enqueue(profilesMutation, async () => {
    const users = await loadProfiles();
    const profile = normalizeProfile(payload, users.get(user.userId));
    users.set(user.userId, profile);
    await writeJsonAtomically(profilesStorePath, { version: 1, users: Object.fromEntries(users) });
    return profile;
  });
  profilesMutation = queue;
  return serializeProfile(user, await run);
}

function normalizedTitle(value) {
  return cleanText(value)
    .normalize('NFD').replace(/\p{Diacritic}/gu, '')
    .toLowerCase().replace(/[^\p{Letter}\p{Number}]+/gu, ' ')
    .replace(/\s+/g, ' ').trim();
}
function seasonIdentity(title) {
  let base = cleanText(title); let seasonNumber = null;
  const patterns = [
    /\bseason\s*(\d+)\b/i,
    /\bs\s*(\d+)\b/i,
    /\b(\d+)(?:st|nd|rd|th)\s+season\b/i,
  ];
  for (const pattern of patterns) {
    const match = base.match(pattern);
    if (!match) continue;
    seasonNumber = Number(match[1]);
    base = cleanText(base.replace(pattern, ''));
    break;
  }
  // "Title II" style sequels.
  const roman = seasonNumber === null ? base.match(/\s+(II|III|IV|V|VI|VII|VIII|IX|X)$/) : null;
  if (roman) {
    seasonNumber = ['II', 'III', 'IV', 'V', 'VI', 'VII', 'VIII', 'IX', 'X'].indexOf(roman[1]) + 2;
    base = cleanText(base.slice(0, roman.index));
  }
  return { base, baseKey: normalizedTitle(base), seasonNumber };
}
function seasonRecord(anime) {
  return { ...anime, seasonNumber: seasonIdentity(anime.title).seasonNumber ?? 1 };
}

// QR pairing for devices without a keyboard (TV apps). The TV starts a
// pairing and shows its one-time code in a QR; an app that already has an
// identity approves it, and the TV collects that identity's token once.
// Pending pairings live in memory for 10 minutes.
const pairings = new Map();
function startPairing() {
  for (const [id, pairing] of pairings) if (pairing.expiresAt < Date.now()) pairings.delete(id);
  if (pairings.size >= 50) throw new HttpError(429, 'Too many pending pairings; try again in a few minutes');
  const pairingId = randomBytes(16).toString('hex');
  const code = randomBytes(12).toString('base64url');
  const expiresAt = Date.now() + 10 * 60 * 1000;
  pairings.set(pairingId, { code, expiresAt, approved: null });
  return { pairingId, code, expiresAt: new Date(expiresAt).toISOString() };
}
function pairingStatus(pairingId) {
  const id = cleanText(pairingId); const pairing = pairings.get(id);
  if (!pairing || pairing.expiresAt < Date.now()) { pairings.delete(id); return { status: 'expired' }; }
  if (!pairing.approved) return { status: 'pending' };
  pairings.delete(id);
  return { status: 'approved', token: pairing.approved.token, label: pairing.approved.label };
}
function pendingPairing(code) {
  const value = cleanText(code);
  return [...pairings.values()].find((candidate) => candidate.expiresAt > Date.now() && !candidate.approved && sameSecret(candidate.code, value)) ?? null;
}

// First setup of a TV from any phone browser (no app needed): the TV's QR
// opens /setup?code=…, where the person pastes an existing token or, the
// first time, the administrator token to create an identity. The
// administrator token is only checked, never stored; each code allows a few
// attempts. The page is in Spanish or English, after the browser's language.
const setupStrings = {
  es: {
    lang: 'es', profile: 'Perfil',
    expired: 'El código caducó o ya se usó. Abre de nuevo el código QR en la TV.',
    tooMany: 'Demasiados intentos con este código. Abre un código nuevo en la TV.',
    noAdmin: 'Este servidor no tiene token administrador configurado.',
    badAdmin: 'El token administrador no es válido.',
    badToken: 'Ese token no corresponde a ninguna identidad de este servidor.',
    expiredTitle: 'Código caducado', linkTitle: 'Vincular la TV', doneTitle: 'Listo', failedTitle: 'No se pudo vincular',
    done: (label) => `La TV ya tiene el perfil «${label}». Puedes cerrar esta página.`,
    doneNote: 'Para guardar este perfil en otro dispositivo, usa «Llevar al teléfono» en los perfiles de la TV.',
    nameLabel: 'Nombre del perfil en la TV', namePlaceholder: 'Por ejemplo, tu nombre',
    haveApp: '¿Tienes Relaycast en este teléfono?',
    haveAppNote: 'La TV usará la identidad de la app: el mismo historial y las mismas preferencias.',
    openApp: 'Abrir en la app',
    existing: 'Usar una identidad existente', tokenLabel: 'Token de 64 caracteres', link: 'Vincular la TV',
    create: 'Crear una identidad nueva',
    createNote: 'La primera vez, con el token administrador del servidor. Sólo se usa para crear la identidad: no se guarda. Después, la TV puede crear más perfiles sin él.',
    adminLabel: 'Token administrador', createAndLink: 'Crear y vincular la TV',
    expiresNote: 'El código caduca en 10 minutos.',
  },
  en: {
    lang: 'en', profile: 'Profile',
    expired: 'The code expired or was already used. Open the QR code on the TV again.',
    tooMany: 'Too many attempts with this code. Open a new code on the TV.',
    noAdmin: 'This server has no administrator token configured.',
    badAdmin: 'The administrator token is not valid.',
    badToken: 'That token does not belong to any identity on this server.',
    expiredTitle: 'Code expired', linkTitle: 'Link the TV', doneTitle: 'Done', failedTitle: 'Could not link',
    done: (label) => `The TV now has the profile “${label}”. You can close this page.`,
    doneNote: 'To keep this profile on another device, use “Take to phone” in the TV’s profiles.',
    nameLabel: 'Profile name on the TV', namePlaceholder: 'For example, your name',
    haveApp: 'Do you have Relaycast on this phone?',
    haveAppNote: 'The TV will use the app’s identity: the same history and the same preferences.',
    openApp: 'Open in the app',
    existing: 'Use an existing identity', tokenLabel: '64-character token', link: 'Link the TV',
    create: 'Create a new identity',
    createNote: 'The first time, with the server’s administrator token. It is only used to create the identity and is not stored. Afterwards, the TV can create more profiles without it.',
    adminLabel: 'Administrator token', createAndLink: 'Create and link the TV',
    expiresNote: 'The code expires in 10 minutes.',
  },
};
// The first of the browser's languages that the page has; English otherwise.
function setupLanguage(request) {
  const languages = String(request?.headers?.['accept-language'] ?? '').split(',')
    .map((part) => part.trim().split(';')[0].slice(0, 2).toLowerCase());
  return setupStrings[languages.find((language) => setupStrings[language]) ?? 'en'];
}
async function completeSetup(fields, text = setupStrings.es) {
  const pairing = pendingPairing(fields.code);
  if (!pairing) throw new HttpError(404, text.expired);
  pairing.attempts = (pairing.attempts ?? 0) + 1;
  if (pairing.attempts > 5) {
    pairing.expiresAt = 0;
    throw new HttpError(429, text.tooMany);
  }
  const label = historyText(fields.label, text.profile, 40);
  let token;
  if (fields.mode === 'create') {
    if (!serverAdminToken) throw new HttpError(409, text.noAdmin);
    if (!sameSecret(cleanText(fields.adminToken), serverAdminToken)) throw new HttpError(401, text.badAdmin);
    token = randomBytes(32).toString('hex');
    await registerUser(token, label);
  } else {
    token = cleanText(fields.token).toLowerCase();
    if (!validUserToken(token) || !(await userForToken(token))) throw new HttpError(401, text.badToken);
  }
  approvePairing(fields.code, label, { token });
  return { label };
}

function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, (character) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}
function setupPage(response, status, title, content, text = setupStrings.es) {
  response.writeHead(status, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
    'Referrer-Policy': 'no-referrer',
    'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'",
  });
  response.end(`<!doctype html><html lang="${text.lang}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title>
<style>body{font:16px/1.5 system-ui,sans-serif;margin:0;padding:24px 16px;background:#0b0b12;color:#eee}main{max-width:520px;margin:auto}h1{font-size:1.5rem}section{background:#1a1a26;border-radius:16px;padding:16px;margin:16px 0}label{display:block;margin:10px 0 4px}input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #444;background:#0b0b12;color:#eee;font:inherit}button,a.button{display:block;width:100%;box-sizing:border-box;margin-top:14px;padding:12px;border:0;border-radius:10px;background:#6366f1;color:#fff;font:inherit;text-align:center;text-decoration:none}a.button.secondary{background:#2a2a3e}a{color:#a5b4fc}p.note{color:#aaa;font-size:.9rem}p.error{color:#fca5a5}</style>
</head><body><main><h1>${escapeHtml(title)}</h1>${content}</main></body></html>`);
}
function setupForm(request, code, error = '', text = setupStrings.es) {
  const app = `relaycast://pair?bridge=${encodeURIComponent(requestOrigin(request))}&code=${encodeURIComponent(code)}`;
  const hidden = `<input type="hidden" name="code" value="${escapeHtml(code)}">`;
  const nameField = `<label for="label-MODE">${text.nameLabel}</label><input id="label-MODE" name="label" maxlength="40" placeholder="${text.namePlaceholder}" autocomplete="off">`;
  return `${error ? `<p class="error">${escapeHtml(error)}</p>` : ''}
<section><strong>${text.haveApp}</strong><p class="note">${text.haveAppNote}</p><a class="button" href="${escapeHtml(app)}">${text.openApp}</a></section>
<section><form method="post" action="/setup">${hidden}<input type="hidden" name="mode" value="existing"><strong>${text.existing}</strong>${nameField.replaceAll('MODE', 'existing')}<label for="token">${text.tokenLabel}</label><input id="token" name="token" autocomplete="off" autocapitalize="off" spellcheck="false" required minlength="64" maxlength="64"><button type="submit">${text.link}</button></form></section>
${serverAdminToken ? `<section><form method="post" action="/setup">${hidden}<input type="hidden" name="mode" value="create"><strong>${text.create}</strong><p class="note">${text.createNote}</p>${nameField.replaceAll('MODE', 'create')}<label for="admin">${text.adminLabel}</label><input id="admin" name="adminToken" type="password" autocomplete="off" required><button type="submit">${text.createAndLink}</button></form></section>` : ''}
<p class="note">${text.expiresNote}</p>`;
}

function approvePairing(code, label, user) {
  const value = cleanText(code);
  const pairing = [...pairings.values()].find((candidate) => candidate.expiresAt > Date.now() && sameSecret(candidate.code, value));
  if (!pairing) throw new HttpError(404, 'The pairing code was not found or has expired');
  if (pairing.approved) throw new HttpError(409, 'This pairing was already approved');
  pairing.approved = { token: user.token, label: historyText(label, 'Perfil', 40) };
  return { ok: true };
}

// Remote control. TV apps register as devices of their identity, report what
// they are playing and wait for commands with a long poll; apps of the same
// identity list those TVs and send them commands. Kept in memory.
//
// A TV is listed (and accepts commands) only while a phone can really
// control it:
// - same identity: devices are keyed by the identity's user id;
// - it reported in the last 45 s;
// - its command channel is alive: a long poll is open now, or one ended less
//   than 35 s ago (polls last 25 s). State reports alone do not prove that
//   commands arrive (a poll can die behind a proxy while reports go on);
// - it is awake: TVs report whether their screen is on, and a TV in standby
//   keeps its service running but nobody would see what a phone sends. TV
//   apps that do not report it count as awake.
// Being on the same network is not required: commands travel through the
// server, so a TV at home is controllable from anywhere the server is. The
// list says, per TV, whether the phone and the TV reached the server from the
// same network (`sameNetwork`), or null when the addresses cannot tell.
const devices = new Map();
const deviceTimeout = 45 * 1000;
const commandChannelTimeout = 35 * 1000;
const commandTypes = ['play', 'pause', 'resume', 'toggle', 'seek', 'skip', 'next', 'previous', 'stop', 'volume', 'subtitles'];
const subtitleTrackId = /^(?:off|\d{1,3}:\d{1,3})$/;
function deviceFor(user, deviceId, create = true) {
  const id = cleanText(deviceId);
  if (!/^[\w-]{8,64}$/.test(id)) throw new HttpError(400, 'Invalid device id');
  const key = `${user.userId}:${id}`;
  let device = devices.get(key);
  if (!device && create) {
    device = { id, userId: user.userId, name: 'TV', state: null, stateAt: 0, canOpen: false, awake: null, address: null, lastSeen: 0, lastPoll: 0, queue: [], seq: 0, waiters: new Set() };
    devices.set(key, device);
  }
  return device;
}
function deviceState(value) {
  if (!value || typeof value !== 'object') return null;
  const seconds = (number) => (Number.isFinite(Number(number)) && Number(number) >= 0 ? Number(number) : null);
  const text = (field, maximum) => (value[field] ? historyText(value[field], '', maximum) : null);
  return {
    status: ['idle', 'loading', 'playing', 'paused'].includes(value.status) ? value.status : 'idle',
    watchUrl: text('watchUrl', 2000), seriesTitle: text('seriesTitle', 300), titleJp: text('titleJp', 300), imageUrl: text('imageUrl', 2000),
    episode: Number.isInteger(value.episode) ? value.episode : null, episodeTitle: text('episodeTitle', 300),
    kind: ['sub', 'dub'].includes(value.kind) ? value.kind : null,
    position: seconds(value.position), duration: seconds(value.duration),
    skip: text('skip', 40),
    // Subtitle tracks the TV can show ("group:track" ids) and the one shown.
    subtitles: Array.isArray(value.subtitles)
      ? value.subtitles.slice(0, 30)
        .filter((track) => subtitleTrackId.test(String(track?.id)) && track.id !== 'off')
        .map((track) => ({ id: track.id, label: historyText(track.label, track.id, 80) }))
      : [],
    subtitle: subtitleTrackId.test(String(value.subtitle)) ? value.subtitle : null,
  };
}
function reportDevice(user, deviceId, body, address = null) {
  const device = deviceFor(user, deviceId);
  device.name = historyText(body?.name, device.name, 60);
  device.state = deviceState(body?.state);
  // Whether the TV app can open itself for "play" while it is closed.
  device.canOpen = body?.canOpen === true;
  // Screen on (true) or in standby (false); null when the TV app is older.
  device.awake = typeof body?.awake === 'boolean' ? body.awake : null;
  device.address = address;
  device.stateAt = device.lastSeen = Date.now();
  return { ok: true };
}
function deviceAvailability(device, now = Date.now()) {
  if (device.lastSeen <= now - deviceTimeout) return 'offline';
  if (!device.waiters.size && device.lastPoll <= now - commandChannelTimeout) return 'offline';
  if (device.awake === false) return 'asleep';
  return 'available';
}
// The address a request came from: the first X-Forwarded-For hop behind the
// reverse proxy, else the socket. IPv4-mapped IPv6 is reduced to IPv4.
function clientAddress(request) {
  const value = forwardedValue(request, 'x-forwarded-for') || cleanText(request?.socket?.remoteAddress);
  return value ? value.replace(/^::ffff:/i, '').toLowerCase() : null;
}
function expandedIpv6(value) {
  const [head, tail = ''] = value.split('%')[0].split('::');
  const left = head ? head.split(':') : [];
  const right = value.includes('::') && tail ? tail.split(':') : [];
  const groups = value.includes('::') ? [...left, ...Array(8 - left.length - right.length).fill('0'), ...right] : left;
  return groups.map((group) => group.padStart(4, '0'));
}
// Whether two clients reached the server from the same network: the same
// public IPv4 (one NAT), the same private /24 (a server inside the home
// network) or the same IPv6 /64. Null when they cannot be compared (unknown,
// or one IPv4 and one IPv6). A VPN on either side shows as another network.
function sameNetwork(left, right) {
  if (!left || !right) return null;
  const ipv4 = /^\d{1,3}(?:\.\d{1,3}){3}$/;
  if (ipv4.test(left) && ipv4.test(right)) {
    if (left === right) return true;
    const isPrivate = (address) => /^(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)/.test(address);
    const prefix = (address) => address.split('.').slice(0, 3).join('.');
    return isPrivate(left) && isPrivate(right) && prefix(left) === prefix(right);
  }
  if (left.includes(':') && right.includes(':')) return expandedIpv6(left).slice(0, 4).join(':') === expandedIpv6(right).slice(0, 4).join(':');
  return null;
}
function listDevices(user, address = null, now = Date.now()) {
  const available = [...devices.values()].filter((device) => device.userId === user.userId && deviceAvailability(device, now) === 'available');
  // stateAge (ms) lets the phone advance the position between reports
  // without depending on its clock matching the server's.
  return {
    devices: available.map(({ id, name, state, stateAt, canOpen, lastSeen, address: tvAddress }) => ({
      id, name, state, stateAge: now - stateAt, canOpen, lastSeen: new Date(lastSeen).toISOString(),
      sameNetwork: sameNetwork(address, tvAddress),
    })),
  };
}
function sendCommand(user, deviceId, body) {
  const device = deviceFor(user, deviceId, false);
  const availability = device ? deviceAvailability(device) : 'offline';
  if (availability === 'offline') throw new HttpError(404, 'The TV is not connected');
  if (availability === 'asleep') throw new HttpError(409, 'The TV is in standby');
  const type = cleanText(body?.type);
  if (!commandTypes.includes(type)) throw new HttpError(400, 'Invalid command');
  // `seq` orders the commands of a TV; it acknowledges them with `after`.
  const command = { id: randomUUID(), seq: ++device.seq, at: Date.now(), type };
  if (type === 'play') {
    command.watchUrl = provider.watchUrl(historyText(body.watchUrl, '', 2000));
    command.episode = Number.isInteger(body.episode) && body.episode > 0 ? body.episode : null;
    command.kind = ['sub', 'dub'].includes(body.kind) ? body.kind : null;
  }
  if (type === 'seek') {
    // An absolute position, or a relative jump in seconds (negative goes back).
    const valid = (value) => value !== undefined && value !== null && Number.isFinite(Number(value)) && Math.abs(Number(value)) <= 6 * 3600;
    if (valid(body.position) && Number(body.position) >= 0) command.position = Number(body.position);
    else if (valid(body.seconds)) command.seconds = Number(body.seconds);
    else throw new HttpError(400, 'Invalid seek');
  }
  if (type === 'volume') {
    // Volume steps of the TV (the phone's volume buttons): up when positive.
    const steps = Number(body.steps);
    if (!Number.isInteger(steps) || steps === 0 || Math.abs(steps) > 20) throw new HttpError(400, 'Invalid volume');
    command.steps = steps;
  }
  if (type === 'subtitles') {
    const track = cleanText(body.track) || 'off';
    if (!subtitleTrackId.test(track)) throw new HttpError(400, 'Invalid subtitle track');
    command.track = track;
  }
  device.queue.push(command);
  if (device.queue.length > 20) device.queue.shift();
  for (const wake of [...device.waiters]) wake();
  return { ok: true, id: command.id };
}
// Long poll: answers as soon as a command is queued, or empty after 25 s.
// With `after` (the last seq the TV handled) commands stay queued until a
// later poll acknowledges them: behind a proxy a TV that restarted may leave
// an old poll open, and a command answered there would be lost. Commands
// older than two minutes are dropped so a stale "play" never replays. TVs
// without `after` (older apps) take the commands when they are answered.
const commandLifetime = 2 * 60 * 1000;
async function waitForCommands(user, deviceId, response, waitMs = 25 * 1000, after = null) {
  const device = deviceFor(user, deviceId);
  device.lastSeen = device.lastPoll = Date.now();
  const acknowledged = Number.isSafeInteger(after) && after >= 0 ? after : null;
  const pending = () => {
    const now = Date.now();
    device.queue = device.queue.filter((command) => now - command.at < commandLifetime && (acknowledged === null || command.seq > acknowledged));
    return device.queue;
  };
  if (!pending().length) {
    await new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        device.waiters.delete(done);
        response.off('close', done);
        resolve();
      };
      const timer = setTimeout(done, waitMs);
      device.waiters.add(done);
      response.on('close', done);
    });
  }
  if (response.destroyed || response.writableEnded) return null;
  device.lastSeen = device.lastPoll = Date.now();
  if (acknowledged === null) return { commands: device.queue.splice(0) };
  return { commands: pending().map(({ at, ...command }) => command) };
}

async function getSeries(watchUrl) {
  const series = await provider.series(provider.watchUrl(watchUrl));
  if (!series.episodes.length) throw new Error('No episodes were returned');
  const key = randomUUID(); seriesCache.set(key, { ...series, expiresAt: Date.now() + 20 * 60 * 1000 });
  rememberSummary(series);
  console.info(`[server] Loaded ${series.title}: ${series.episodes.length} episode(s), ${series.seasons.length} related season(s)`);
  const { synopsis, rating, adult, status, mediaType, score, totalEpisodes, imageUrl, nextEpisodeAt } = series;
  return {
    seriesKey: key, title: series.title, titleJp: series.titleJp, synopsis, rating, adult, status, mediaType, score, totalEpisodes, imageUrl, nextEpisodeAt,
    episodes: series.episodes.map(({ number, title, sub, dub }) => ({ number, title, sub, dub })), seasons: series.seasons,
  };
}
// The newest Sub and Dub episode, the announced total and the season that
// follows in the watch order: enough for the app to tell a finished series
// from one waiting for its next episode.
function seriesSummary(series) {
  const latest = (kind) => series.episodes.reduce((newest, episode) => (episode[kind] ? Math.max(newest, episode.number) : newest), 0) || null;
  const index = series.seasons.findIndex((season) => season.watchUrl === series.watchUrl);
  const following = index >= 0 ? series.seasons.slice(index + 1) : [];
  const nextSeason = following.find((season) => !/\b(?:specials?|mini anime|recap|ova|oad|movie|film)\b/i.test(season.title)) ?? following[0] ?? null;
  return { watchUrl: series.watchUrl, totalEpisodes: series.totalEpisodes, status: series.status, latestSub: latest('sub'), latestDub: latest('dub'), nextSeason, nextEpisodeAt: series.nextEpisodeAt ?? null };
}
function rememberSummary(series) {
  const summary = seriesSummary(series);
  seriesSummaries.set(series.watchUrl, { summary, expiresAt: Date.now() + 15 * 60 * 1000 });
  return summary;
}
async function getSeriesStatuses(watchUrls) {
  if (!Array.isArray(watchUrls)) throw new HttpError(400, 'watchUrls must be an array');
  const urls = [...new Set(watchUrls.slice(0, 50).map((value) => { try { return provider.watchUrl(String(value)); } catch { return null; } }).filter(Boolean))];
  const statuses = []; let next = 0;
  // A few at a time so a long Continue list does not flood the catalog.
  async function worker() {
    while (next < urls.length) {
      const url = urls[next++]; const cached = seriesSummaries.get(url);
      if (cached && cached.expiresAt > Date.now()) { statuses.push(cached.summary); continue; }
      try { statuses.push(rememberSummary(await provider.series(url))); } catch (error) { console.warn(`[server] Status lookup failed: ${error.message}`); }
    }
  }
  await Promise.all(Array.from({ length: Math.min(4, urls.length) }, worker));
  return { statuses };
}
function cachedSeries(key) {
  const series = seriesCache.get(key);
  // 410 lets the app reload the series transparently and retry once.
  if (!series || series.expiresAt < Date.now()) { seriesCache.delete(key); throw new HttpError(410, 'Series session expired; search again'); }
  return series;
}
async function getServers(seriesKey, episodeNumber) {
  const series = cachedSeries(seriesKey); const episode = series.episodes.find((candidate) => candidate.number === Number(episodeNumber));
  if (!episode) throw new Error('Episode was not found');
  const servers = await provider.servers(episode.ref);
  if (!servers.length) throw new Error('No servers were returned');
  const subCount = servers.filter((server) => server.type === 'sub').length;
  console.info(`[server] Episode ${episode.number}: ${subCount} Sub, ${servers.length - subCount} Dub servers`);
  series.lastServers = { episodeNumber: episode.number, servers };
  return { episode: episode.number, servers: servers.map(({ index, name, type }) => ({ index, name, type })) };
}
async function createPlayback(seriesKey, episodeNumber, serverIndex, playbackOrigin, user) {
  const series = cachedSeries(seriesKey);
  if (!series.lastServers || series.lastServers.episodeNumber !== Number(episodeNumber)) await getServers(seriesKey, episodeNumber);
  const server = series.lastServers.servers.find((candidate) => candidate.index === Number(serverIndex)); if (!server) throw new Error('Server was not found');
  console.info(`[server] Resolving episode ${episodeNumber}, ${server.type.toUpperCase()} server ${server.name}`);
  const media = await provider.source(server.ref);
  media.subtitles = subtitleTracks(media.rawSubtitles, media.hlsUrl);
  console.info(`[server] Public source metadata returned ${media.subtitles.length} subtitle track(s)`);
  console.info(`[server] HLS candidate: ${playlistDescriptor(media.hlsUrl)}`);
  // Dub audio is English, so no subtitle is forced on; Sub keeps its default track.
  if (server.type === 'dub') media.subtitles.forEach((track) => { track.default = false; });
  const lifetime = 2 * 60 * 60 * 1000;
  const id = randomUUID(); sources.set(id, { ...media, kind: server.type, declareAudio: declareAudioLanguage, userId: user.userId, accessToken: user.token, expiresAt: Date.now() + lifetime });
  const expiry = setTimeout(() => { sources.delete(id); }, lifetime);
  expiry.unref();
  console.info(`[server] Playback ready with ${media.subtitles.length} subtitle track(s)`);
  return { playbackUrl: `${playbackOrigin}${protectedPath(`/watch/${id}/master.m3u8`, user.token)}`, subtitleTracks: media.subtitles.length, serverName: server.name, serverKind: server.type, intro: media.intro, outro: media.outro };
}

// Google Cast. A receiver (Chromecast, Google TV) loads media by URL and
// cannot send our headers, and what it loads is outside our control, so it
// never gets the identity token: the phone asks for a cast link to one of its
// playbacks, an unguessable id that opens only that playback (manifest,
// segments and subtitle files), expires with it and is revoked when the Cast
// session ends. Responses carry CORS headers: the receiver is a web page on
// another origin. Subtitles are offered as whole WebVTT files for the
// receiver's side-loaded text tracks, with the provider's own timing.
//
// The receiver (Shaka Player for HLS) tells a segment's container from its
// URL extension or, without one, from its Content-Type. The CDNs serve
// MPEG-TS segments as images or web pages (.jpg, .html, image/jpeg), which it
// rejects although AVPlayer plays them: cast segment links end in the real
// extension (`media.ts`), found by reading the stream's first segment, and
// every segment is served with the type of its actual bytes.
const castLinks = new Map();
const castCorsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
  'Access-Control-Allow-Headers': 'Content-Type, Accept-Encoding, Range',
  'Access-Control-Expose-Headers': 'Content-Length, Content-Range',
  'Access-Control-Max-Age': '600',
};
const segmentExtensions = { 'video/mp2t': 'ts', 'video/mp4': 'mp4', 'audio/aac': 'aac' };
function castMediaUrl(castId, id, upstream, extension = null) {
  const name = extension && !new URL(upstream).pathname.toLowerCase().endsWith('.m3u8') ? `media.${extension}` : 'media';
  return `/cast/${castId}/${name}?url=${encodeURIComponent(upstream)}&sig=${mediaUrlSignature(id, upstream)}`;
}
// The container of the stream's segments ('ts', 'mp4', 'aac'), or null.
// Read once per playback; started when the cast link is created.
function castSegmentExtension(source) {
  source.segmentType ??= firstSegment(source).then((body) => mediaType(body, ''), (error) => {
    console.warn(`[server] Cast segment format unavailable: ${error.message}`);
    return '';
  });
  return source.segmentType.then((type) => segmentExtensions[type] ?? null);
}
function shareForCast(user, playbackUrl, origin) {
  const id = String(playbackUrl ?? '').match(/\/watch\/([\w-]+)\/master\.m3u8/)?.[1];
  const source = id ? sources.get(id) : null;
  if (!source) throw new HttpError(404, 'Unknown or expired playback');
  if (source.userId !== user.userId) throw new HttpError(403, 'Playback belongs to another user');
  for (const [key, link] of castLinks) if (link.expiresAt < Date.now() || !sources.has(link.id)) castLinks.delete(key);
  if (castLinks.size >= 500) throw new HttpError(429, 'Too many cast links; try again later');
  const castId = randomBytes(24).toString('base64url');
  const expiresAt = source.expiresAt ?? Date.now() + 2 * 60 * 60 * 1000;
  castLinks.set(castId, { id, userId: user.userId, expiresAt });
  castSegmentExtension(source);
  return {
    id: castId,
    url: `${origin}/cast/${castId}/master.m3u8`,
    contentType: 'application/x-mpegURL',
    subtitles: (source.subtitles ?? []).map((track, index) => ({
      id: index + 1, name: track.name, language: track.language, forced: Boolean(track.forced), default: Boolean(track.default),
      url: `${origin}/cast/${castId}/subtitle/${index}.vtt`,
    })),
    expiresAt: new Date(expiresAt).toISOString(),
  };
}
function revokeCast(user, castId) {
  const key = cleanText(castId); const link = castLinks.get(key);
  if (link && link.userId === user.userId) castLinks.delete(key);
  return { ok: true };
}
function castSource(castId) {
  const link = castLinks.get(castId);
  const source = link && link.expiresAt > Date.now() ? sources.get(link.id) : null;
  if (!source) { castLinks.delete(castId); return null; }
  return { id: link.id, source };
}
async function sendCast(request, response, requestUrl, route) {
  for (const [name, value] of Object.entries(castCorsHeaders)) response.setHeader(name, value);
  if (request.method === 'OPTIONS') { response.writeHead(204); response.end(); return; }
  if (request.method !== 'GET' && request.method !== 'HEAD') return json(response, 405, { error: 'Method not allowed' });
  const castId = route[1]; const shared = castSource(castId);
  if (!shared) return json(response, 404, { error: 'Unknown or expired cast link' });
  // Segments are not logged: one every few seconds.
  if (!route[2].startsWith('media.')) console.info(`[server] Cast receiver ${request.method} ${route[2] === 'media' ? 'playlist' : route[2]}`);
  const { id, source } = shared;
  const extension = await castSegmentExtension(source);
  const linkFor = (upstream) => castMediaUrl(castId, id, upstream, extension);
  if (route[2] === 'master.m3u8') return await sendUpstream(response, source.hlsUrl, id, source, false, request.headers.range, linkFor);
  if (route[3] !== undefined) {
    const track = source.subtitles?.[Number(route[3])];
    if (!track) return json(response, 404, { error: 'Unknown subtitle track' });
    const vtt = (await fetchUpstream(track.vttUrl, source)).body.toString('utf8');
    if (!/^(?:﻿)?WEBVTT(?:\s|$)/.test(vtt)) throw new Error('Subtitle did not return WebVTT');
    return text(response, 200, vtt, 'text/vtt; charset=utf-8');
  }
  const upstream = requestUrl.searchParams.get('url');
  if (!upstream || !sameSecret(requestUrl.searchParams.get('sig'), mediaUrlSignature(id, upstream))) return json(response, 403, { error: 'Invalid media request' });
  return await sendUpstream(response, new URL(upstream).toString(), id, source, false, request.headers.range, linkFor);
}

function rewriteUriLines(textValue, baseUrl, id, source, linkFor = (upstream) => mediaUrl(id, upstream, source.accessToken)) {
  return textValue.split(/\r?\n/).map((line) => {
    const value = line.trim(); if (!value) return line;
    if (!value.startsWith('#')) return linkFor(absoluteUrl(value, baseUrl));
    return line.replace(/URI="([^"]+)"/g, (match, uri) => uri.startsWith('/subtitle/') ? match : `URI="${linkFor(absoluteUrl(uri, baseUrl))}"`);
  }).join('\n');
}
function withStreamAttribute(playlist, name, value) {
  return playlist.replace(/#EXT-X-STREAM-INF:([^\r\n]*)/g, (line, attrs) => new RegExp(`(?:^|,)${name}=`).test(attrs) ? line : `#EXT-X-STREAM-INF:${attrs},${name}="${value}"`);
}
// Declares the audio language and the VTT subtitle renditions in the master
// playlist. AVPlayer only auto-enables subtitles when it knows the audio is in
// another language, so Sub sources announce Japanese and Dub sources English.
// An AUDIO rendition without URI describes the audio muxed into each variant.
function injectRenditions(master, source, id) {
  if (!/#EXT-X-STREAM-INF:/.test(master)) return master;
  const audio = source.audio ?? (source.kind === 'dub' ? { name: 'English', language: 'en' } : { name: 'Japanese', language: 'ja' });
  const declarations = [];
  let playlist = master;
  if (source.declareAudio === false) {
    // DECLARE_AUDIO_LANGUAGE=false: leave the audio as the provider sent it.
  } else if (/TYPE=AUDIO/.test(playlist)) {
    playlist = playlist.replace(/^#EXT-X-MEDIA:([^\r\n]*)$/gm, (line, attrs) => /(?:^|,)TYPE=AUDIO(?:,|$)/.test(attrs) && !/(?:^|,)LANGUAGE=/.test(attrs) ? `${line},LANGUAGE="${audio.language}"` : line);
  } else {
    declarations.push(`#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="server-audio",NAME="${audio.name}",LANGUAGE="${audio.language}",DEFAULT=YES,AUTOSELECT=YES`);
    playlist = withStreamAttribute(playlist, 'AUDIO', 'server-audio');
  }
  const tracks = source.subtitles ?? [];
  if (tracks.length && !/TYPE=SUBTITLES/.test(playlist)) {
    // FORCED=YES makes Apple players treat signs/songs tracks as forced
    // subtitles: shown automatically when needed, not listed as a choice.
    declarations.push(...tracks.map((track, index) => `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="server-subs",NAME="${track.name.replaceAll('"', '')}",LANGUAGE="${track.language.replaceAll('"', '')}",DEFAULT=${track.default && !track.forced ? 'YES' : 'NO'},AUTOSELECT=YES,${track.forced ? 'FORCED=YES,' : ''}URI="${protectedPath(`/subtitle/${id}/${index}.m3u8`, source.accessToken)}"`));
    playlist = withStreamAttribute(playlist, 'SUBTITLES', 'server-subs');
  }
  return declarations.length ? playlist.replace(/^#EXTM3U\s*$/m, '#EXTM3U\n' + declarations.join('\n')) : playlist;
}
function secondsInVttTimestamp(match) { return Number(match[1] ?? 0) * 3600 + Number(match[2]) * 60 + Number(match[3]); }
function durationFromVtt(vtt) {
  const pattern = /^(?:(\d+):)?(\d{2}):(\d{2}\.\d{3})\s+-->\s+(?:(\d+):)?(\d{2}):(\d{2}\.\d{3})/gm; let duration = 1;
  for (const match of vtt.matchAll(pattern)) duration = Math.max(duration, secondsInVttTimestamp([, match[4], match[5], match[6]]));
  return Math.ceil(duration);
}
// Media is read from the local library in this process; there is no network
// upstream. The shape of a response is { url, type, body }.
async function fetchUpstream(value, source) {
  if (!String(value).startsWith('local://')) throw new HttpError(400, 'Only library media can be served');
  return await provider.media.read(value);
}
// First presentation timestamp (90 kHz ticks) among the audio/video PES
// packets of an MPEG-TS segment, or null for other containers.
function transportStreamStartPts(buffer) {
  let start = null;
  for (let offset = 0; offset + 188 <= buffer.length && offset < 188 * 2000; offset += 188) {
    if (buffer[offset] !== 0x47) return start;
    if (!(buffer[offset + 1] & 0x40)) continue;
    const adaptation = (buffer[offset + 3] >> 4) & 0x3;
    const payload = offset + 4 + (adaptation >= 2 ? 1 + buffer[offset + 4] : 0);
    if (adaptation === 2 || payload + 14 > offset + 188) continue;
    const isPes = buffer[payload] === 0 && buffer[payload + 1] === 0 && buffer[payload + 2] === 1;
    const streamId = buffer[payload + 3];
    if (!isPes || streamId < 0xc0 || streamId > 0xef || !(buffer[payload + 7] & 0x80)) continue;
    const b = buffer.subarray(payload + 9, payload + 14);
    const pts = ((b[0] >> 1) & 0x07) * 2 ** 30 + (((b[1] << 8) | b[2]) >> 1) * 2 ** 15 + (((b[3] << 8) | b[4]) >> 1);
    start = start === null ? pts : Math.min(start, pts);
  }
  return start;
}
// Apple players place WebVTT cues by timestamp: without X-TIMESTAMP-MAP a cue
// at 0 is pinned to PTS 0, while the provider's web player measures cues from
// the first frame. Mapping LOCAL 0 to the stream's first PTS matches it.
function withTimestampMap(vtt, startPts) {
  if (startPts === null || /X-TIMESTAMP-MAP/.test(vtt) || !/^﻿?WEBVTT/.test(vtt)) return vtt;
  return vtt.replace(/^(﻿?WEBVTT[^\r\n]*)/, `$1\nX-TIMESTAMP-MAP=MPEGTS:${startPts},LOCAL:00:00:00.000`);
}
// What a segment really is, from its first bytes: the CDNs label MPEG-TS as
// images or web pages. Anything else (an AES-128 encrypted segment, a key)
// keeps the upstream type.
function mediaType(body, upstreamType) {
  if (body.length >= 188 && body[0] === 0x47 && (body.length < 189 || body[188] === 0x47)) return 'video/mp2t';
  if (body.length >= 8 && /^(?:ftyp|styp|moof|sidx|moov)$/.test(body.toString('latin1', 4, 8))) return 'video/mp4';
  if (body.length >= 2 && body[0] === 0xff && (body[1] & 0xf6) === 0xf0) return 'audio/aac';
  return upstreamType || 'application/octet-stream';
}
// The start of the stream's first segment (its first 256 KiB, after the
// provider's transport fix-up), through the first variant.
async function firstSegment(source, masterUrl, masterText) {
  const firstUri = (text) => text.split(/\r?\n/).map((line) => line.trim()).find((line) => line && !line.startsWith('#'));
  const master = masterText === undefined ? await fetchUpstream(source.hlsUrl, source, null, 15000) : null;
  let playlistUrl = master ? master.url : masterUrl;
  let playlist = master ? master.body.toString('utf8') : masterText;
  if (/#EXT-X-STREAM-INF:/.test(playlist)) {
    playlistUrl = absoluteUrl(firstUri(playlist), playlistUrl);
    const variant = await fetchUpstream(playlistUrl, source, null, 15000);
    playlistUrl = variant.url; playlist = variant.body.toString('utf8');
  }
  const segment = await fetchUpstream(absoluteUrl(firstUri(playlist), playlistUrl), source, 'bytes=0-262143', 15000);
  return segment.body;
}
// Started while serving the master playlist, whose CDN token is still fresh.
async function detectStartPts(source, masterUrl, masterText) {
  try {
    return transportStreamStartPts(await firstSegment(source, masterUrl, masterText));
  } catch (error) {
    console.warn(`[server] Stream start time unavailable: ${error.message}`);
    return null;
  }
}
async function sendUpstream(response, upstreamUrl, id, source, inject, range, linkFor = undefined) {
  const upstream = await fetchUpstream(upstreamUrl, source, range); const type = upstream.type;
  const isVtt = upstream.url.toLowerCase().includes('.vtt') || /text\/vtt/i.test(type);
  if (isVtt) {
    source.startPts ??= detectStartPts(source);
    text(response, 200, withTimestampMap(upstream.body.toString('utf8'), await source.startPts), 'text/vtt; charset=utf-8');
    return;
  }
  if (!isPlaylist(upstream.url, type)) { response.writeHead(200, { 'content-type': mediaType(upstream.body, type) }); response.end(upstream.body); return; }
  let playlist = upstream.body.toString('utf8'); const manifestStart = playlist.indexOf('#EXTM3U'); if (manifestStart > 0) playlist = playlist.slice(manifestStart);
  if (inject) {
    source.startPts ??= detectStartPts(source, upstream.url, playlist);
    playlist = injectRenditions(playlist, source, id);
  }
  playlist = rewriteUriLines(playlist, upstream.url, id, source, linkFor);
  text(response, 200, playlist, 'application/vnd.apple.mpegurl; charset=utf-8');
}
async function sendSingleVttPlaylist(response, track, id, source) {
  if (!track.duration) { const vtt = await fetchUpstream(track.vttUrl, source); const body = vtt.body.toString('utf8'); if (!/^WEBVTT(?:\r?\n|$)/.test(body)) throw new Error('Subtitle did not return WebVTT'); track.duration = durationFromVtt(body); }
  const duration = track.duration; const playlist = ['#EXTM3U', '#EXT-X-VERSION:3', `#EXT-X-TARGETDURATION:${duration}`, '#EXT-X-PLAYLIST-TYPE:VOD', `#EXTINF:${duration}.000,`, mediaUrl(id, track.vttUrl, source.accessToken), '#EXT-X-ENDLIST', ''].join('\n');
  text(response, 200, playlist, 'application/vnd.apple.mpegurl; charset=utf-8');
}

let toolsCheck = null;
function libraryTools() {
  toolsCheck ??= provider.tools();
  return toolsCheck;
}

const server = http.createServer(async (request, response) => {
  const requestUrl = new URL(request.url, `http://${request.headers.host}`);
  try {
    if (requestUrl.pathname === '/health' || requestUrl.pathname.startsWith('/api/')) {
      console.info(`[server] ${request.method} ${requestUrl.pathname}`);
    }
    if (request.method === 'GET' && requestUrl.pathname === '/health') {
      const { series, files, scanning } = provider.status();
      return json(response, 200, { ok: true, provider: provider.type, version: serverVersion, library: { series, files, scanning } });
    }
    // Posters are public and unguessable per series; image loaders send no token.
    const artRoute = requestUrl.pathname.match(/^\/art\/([\w-]+)\.jpg$/);
    if (request.method === 'GET' && artRoute) {
      const image = await provider.art(artRoute[1]);
      if (!image) return json(response, 404, { error: 'No artwork' });
      response.writeHead(200, { 'content-type': image.type, 'cache-control': 'public, max-age=3600' });
      response.end(image.body);
      return;
    }
    // The library the companion manages: folders, status and rescans.
    // The catalog the companion edits: titles, synopsis, genres, seasons and
    // franchise (what relates series), poster, next air date, and each episode's
    // number, title, language and intro/outro ranges.
    const editorRoute = requestUrl.pathname.match(/^\/api\/admin\/library\/(series|episodes)(?:\/([\w-]+))?(?:\/(poster|marks))?$/);
    if (editorRoute) {
      if (!serverAdminToken) return json(response, 503, { error: 'SERVER_ADMIN_TOKEN is not configured' });
      if (!isAdministrator(request)) return json(response, 401, { error: 'A valid server administrator token is required' });
      const [, kind, id, action] = editorRoute; const { editor } = provider;
      if (kind === 'series' && !id && request.method === 'GET') return json(response, 200, { series: editor.catalog() });
      if (kind === 'series' && id && !action && request.method === 'GET') return json(response, 200, editor.detail(id));
      if (kind === 'series' && id && !action && request.method === 'PUT') return json(response, 200, await editor.saveSeries(id, JSON.parse(await readBody(request))));
      if (kind === 'series' && id && action === 'poster' && request.method === 'PUT') return json(response, 200, await editor.setPoster(id, await readBinary(request), request.headers['content-type']));
      if (kind === 'series' && id && action === 'poster' && request.method === 'DELETE') return json(response, 200, await editor.clearPoster(id));
      if (kind === 'episodes' && id && action === 'marks' && request.method === 'GET') return json(response, 200, await editor.episodeMarks(id));
      return json(response, 405, { error: 'Method not allowed' });
    }
    if (requestUrl.pathname === '/api/admin/library' || requestUrl.pathname === '/api/admin/library/rescan') {
      if (!serverAdminToken) return json(response, 503, { error: 'SERVER_ADMIN_TOKEN is not configured' });
      if (!isAdministrator(request)) return json(response, 401, { error: 'A valid server administrator token is required' });
      if (requestUrl.pathname.endsWith('/rescan') && request.method === 'POST') { provider.rescan(); return json(response, 202, provider.status()); }
      if (request.method === 'GET') return json(response, 200, { ...provider.status(), tools: await libraryTools() });
      if (request.method === 'PUT') {
        const body = JSON.parse(await readBody(request));
        const status = await saveLibraryFolders(body.folders);
        return json(response, 200, { ...status, tools: await libraryTools() });
      }
      return json(response, 405, { error: 'Method not allowed' });
    }
    if (request.method === 'POST' && requestUrl.pathname === '/api/admin/users') {
      if (!serverAdminToken) return json(response, 503, { error: 'SERVER_ADMIN_TOKEN is not configured' });
      if (!isAdministrator(request)) return json(response, 401, { error: 'A valid server administrator token is required' });
      const body = JSON.parse(await readBody(request));
      try {
        const registered = await registerUser(cleanText(body.token), body.label);
        return json(response, 201, { userId: registered.userId, createdAt: registered.createdAt });
      } catch (error) {
        return json(response, /already registered/.test(error.message) ? 409 : 400, { error: error.message });
      }
    }
    // First setup page for a TV, from any phone browser.
    if (requestUrl.pathname === '/setup' && (request.method === 'GET' || request.method === 'POST')) {
      const text = setupLanguage(request);
      if (request.method === 'GET') {
        const code = cleanText(requestUrl.searchParams.get('code'));
        if (!pendingPairing(code)) return setupPage(response, 404, text.expiredTitle, `<p>${text.expired}</p>`, text);
        return setupPage(response, 200, text.linkTitle, setupForm(request, code, '', text), text);
      }
      const fields = Object.fromEntries(new URLSearchParams(await readBody(request)));
      try {
        const { label } = await completeSetup(fields, text);
        return setupPage(response, 200, text.doneTitle, `<p>${text.done(escapeHtml(label))}</p><p class="note">${text.doneNote}</p>`, text);
      } catch (error) {
        const status = error instanceof HttpError ? error.status : 500;
        if (status === 404 || status === 429) return setupPage(response, status, text.failedTitle, `<p class="error">${escapeHtml(error.message)}</p>`, text);
        return setupPage(response, status, text.linkTitle, setupForm(request, cleanText(fields.code), error.message, text), text);
      }
    }
    // Pairing start and status are open: the TV has no identity yet.
    if (request.method === 'POST' && requestUrl.pathname === '/api/pair/start') return json(response, 201, startPairing());
    if (request.method === 'GET' && requestUrl.pathname === '/api/pair/status') return json(response, 200, pairingStatus(requestUrl.searchParams.get('pairingId')));
    // Cast links authorize by their own id, never by the identity token.
    const castRoute = requestUrl.pathname.match(/^\/cast\/([\w-]{16,64})\/(master\.m3u8|media(?:\.(?:ts|mp4|aac))?|subtitle\/(\d{1,3})\.vtt)$/);
    if (castRoute) return await sendCast(request, response, requestUrl, castRoute);
    const user = await authenticatedUser(request, requestUrl);
    if (!user) return json(response, 401, { error: 'A valid server access token is required' });
    if (request.method === 'GET' && requestUrl.pathname === '/api/search') {
      const results = await provider.search(requestUrl.searchParams.get('q') ?? '');
      console.info(`[server] Search returned ${results.length} result(s)`);
      return json(response, 200, withOrigin({ results }, requestOrigin(request)));
    }
    if (request.method === 'GET' && requestUrl.pathname === '/api/latest') return json(response, 200, withOrigin(await provider.latest(requestUrl.searchParams), requestOrigin(request)));
    if (request.method === 'GET' && requestUrl.pathname === '/api/catalog/filters') return json(response, 200, provider.filters ?? {});
    if (request.method === 'GET' && requestUrl.pathname === '/api/devices') return json(response, 200, listDevices(user, clientAddress(request)));
    const deviceRoute = requestUrl.pathname.match(/^\/api\/devices\/([\w-]+)(\/commands)?$/);
    if (deviceRoute && request.method === 'PUT' && !deviceRoute[2]) return json(response, 200, reportDevice(user, deviceRoute[1], JSON.parse(await readBody(request)), clientAddress(request)));
    if (deviceRoute && request.method === 'POST' && deviceRoute[2]) return json(response, 201, sendCommand(user, deviceRoute[1], JSON.parse(await readBody(request))));
    if (deviceRoute && request.method === 'GET' && deviceRoute[2]) {
      const after = requestUrl.searchParams.has('after') ? Number(requestUrl.searchParams.get('after')) : null;
      const reply = await waitForCommands(user, deviceRoute[1], response, undefined, after);
      return reply ? json(response, 200, reply) : undefined;
    }
    if (request.method === 'POST' && requestUrl.pathname === '/api/identities') return json(response, 201, await createIdentity(user, JSON.parse(await readBody(request)).label));
    if (request.method === 'POST' && requestUrl.pathname === '/api/pair/approve') { const body = JSON.parse(await readBody(request)); return json(response, 200, approvePairing(body.code, body.label, user)); }
    if (request.method === 'GET' && requestUrl.pathname === '/api/schedule') return json(response, 200, withOrigin({ entries: await provider.schedule() }, requestOrigin(request)));
    if (request.method === 'GET' && requestUrl.pathname === '/api/history') {
      return json(response, 200, await storedHistory(user.userId));
    }
    if (request.method === 'PUT' && requestUrl.pathname === '/api/history') {
      const body = JSON.parse(await readBody(request));
      return json(response, 200, await mergeHistory(user.userId, body));
    }
    if (request.method === 'GET' && requestUrl.pathname === '/api/preferences') return json(response, 200, await storedPreferences(user.userId));
    if (request.method === 'GET' && requestUrl.pathname === '/api/profile') return json(response, 200, await storedProfile(user));
    if (request.method === 'PUT' && requestUrl.pathname === '/api/profile') return json(response, 200, await updateProfile(user, JSON.parse(await readBody(request))));
    if (request.method === 'PUT' && requestUrl.pathname === '/api/preferences') return json(response, 200, await mergePreferences(user.userId, JSON.parse(await readBody(request))));
    if (request.method === 'POST' && requestUrl.pathname === '/api/series') return json(response, 200, withOrigin(await getSeries(JSON.parse(await readBody(request)).watchUrl), requestOrigin(request)));
    if (request.method === 'POST' && requestUrl.pathname === '/api/series/status') return json(response, 200, withOrigin(await getSeriesStatuses(JSON.parse(await readBody(request)).watchUrls), requestOrigin(request)));
    const serversRoute = requestUrl.pathname.match(/^\/api\/series\/([\w-]+)\/servers$/);
    if (request.method === 'GET' && serversRoute) return json(response, 200, await getServers(serversRoute[1], requestUrl.searchParams.get('episode')));
    if (request.method === 'POST' && requestUrl.pathname === '/api/cast') return json(response, 201, shareForCast(user, JSON.parse(await readBody(request)).playbackUrl, requestOrigin(request)));
    const castLink = requestUrl.pathname.match(/^\/api\/cast\/([\w-]+)$/);
    if (request.method === 'DELETE' && castLink) return json(response, 200, revokeCast(user, castLink[1]));
    if (request.method === 'POST' && requestUrl.pathname === '/api/play') { const body = JSON.parse(await readBody(request)); return json(response, 201, await createPlayback(body.seriesKey, body.episode, body.serverIndex, requestOrigin(request), user)); }
    const watch = requestUrl.pathname.match(/^\/watch\/([\w-]+)\/master\.m3u8$/);
    if (request.method === 'GET' && watch) { const source = sources.get(watch[1]); if (!source) return json(response, 404, { error: 'Unknown or expired source' }); if (source.userId !== user.userId) return json(response, 403, { error: 'Playback belongs to another user' }); return await sendUpstream(response, source.hlsUrl, watch[1], source, true, request.headers.range); }
    const subtitle = requestUrl.pathname.match(/^\/subtitle\/([\w-]+)\/(\d+)\.m3u8$/);
    if (request.method === 'GET' && subtitle) { const source = sources.get(subtitle[1]); const track = source?.subtitles[Number(subtitle[2])]; if (!track) return json(response, 404, { error: 'Unknown subtitle track' }); if (source.userId !== user.userId) return json(response, 403, { error: 'Playback belongs to another user' }); return track.playlistUrl ? await sendUpstream(response, track.playlistUrl, subtitle[1], source, false, request.headers.range) : await sendSingleVttPlaylist(response, track, subtitle[1], source); }
    const media = requestUrl.pathname.match(/^\/media\/([\w-]+)$/);
    if (request.method === 'GET' && media) {
      const source = sources.get(media[1]); const upstream = requestUrl.searchParams.get('url'); const signature = requestUrl.searchParams.get('sig');
      if (!source || !upstream) return json(response, 404, { error: 'Unknown media request' });
      if (source.userId !== user.userId) return json(response, 403, { error: 'Playback belongs to another user' });
      if (!sameSecret(signature, mediaUrlSignature(media[1], upstream))) return json(response, 403, { error: 'Invalid media request' });
      return await sendUpstream(response, new URL(upstream).toString(), media[1], source, false, request.headers.range);
    }
    json(response, 404, { error: 'Not found' });
  } catch (error) {
    console.error(`[server] ${request.method} ${requestUrl.pathname} failed: ${error.message}`);
    json(response, error instanceof HttpError ? error.status : 502, { error: error.message });
  }
});
// Tests import the pure helpers without opening a port.
if (!setting('NO_LISTEN')) server.listen(port, host, () => console.log(`Relay Server: http://${host}:${port}`));

export { approvePairing, castMediaUrl, storedProfile, updateProfile, castSource, mediaType, sameNetwork, server, completeSetup, revokeCast, rewriteUriLines, setupForm, shareForCast, sources, createIdentity, provider, listDevices, reportDevice, sendCommand, waitForCommands, fetchUpstream, injectRenditions, pairingStatus, startPairing, normalizePreferences, normalizeHistoryEntry, subtitleDetails, subtitleTracks, transportStreamStartPts, withTimestampMap };

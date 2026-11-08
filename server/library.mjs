// Local library: the self-hosted catalog. It turns folders of video files on
// this computer into the same catalog/series/servers/source interface the
// server always exposed to the apps, and serves the media itself as HLS.
//
//   folders → scan → series (a folder of episodes) → episodes (files)
//   playback → `local://<fileId>/a<audio>/master.m3u8`, read in-process by the
//   server (see `media.read`); segments are cut from the original file with
//   ffmpeg, copying the streams the Apple/Android players accept and
//   transcoding the ones they do not.
//
// Every provider implements (see server.mjs):
//   watchUrl(value)        canonical `${origin}/watch/<seriesId>` URL
//   search(keyword)        → [catalog item]
//   latest(searchParams)   → { results, page, hasNext }
//   series(watchUrl)       → { title, titleJp, watchUrl, episodes: [{ number, title, ref, sub, dub }], seasons, … }
//   servers(episodeRef)    → [{ index, name, type: 'sub' | 'dub', ref }]
//   source(serverRef)      → { hlsUrl, intro, outro, subtitles, audio }
import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import { basename, dirname, extname, join, relative, resolve, sep } from 'node:path';
import { HttpError } from './http-error.mjs';

export const libraryOrigin = 'https://library.relaycast.local';
const videoExtensions = new Set(['.mkv', '.mp4', '.m4v', '.mov', '.avi', '.webm', '.ts', '.m2ts', '.wmv', '.flv', '.mpg', '.mpeg']);
const sidecarSubtitleExtensions = new Set(['.srt', '.ass', '.ssa', '.vtt']);
const artworkNames = ['poster', 'cover', 'folder', 'thumb', 'front'];
const artworkExtensions = ['.jpg', '.jpeg', '.png', '.webp'];
const textSubtitleCodecs = new Set(['subrip', 'srt', 'ass', 'ssa', 'webvtt', 'mov_text', 'text']);
const segmentSeconds = 6;
const pageSize = 24;

const cleanText = (value) => String(value ?? '').replace(/\s+/g, ' ').trim();
const sha1 = (value) => createHash('sha1').update(value).digest('hex');
function normalizedTitle(value) {
  return cleanText(value).normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().replace(/[^\p{Letter}\p{Number}]+/gu, ' ').replace(/\s+/g, ' ').trim();
}
function slug(value) {
  return normalizedTitle(value).replace(/ /g, '-').slice(0, 48) || 'series';
}

// ---------------------------------------------------------------------------
// File names → titles and episode numbers
// ---------------------------------------------------------------------------

const noiseTokens = /\b(?:480|576|720|1080|2160)[pi]?\b|\b[xh]\.?26[45]\b|\bhevc\b|\bavc\b|\baac\d?(?:\.\d)?\b|\bflac\b|\b\d{1,2}[ -]?bits?\b|\b\d+\s?kbps\b|\b(?:bd|blu-?ray|bdrip|brrip|web-?dl|web-?rip|webrip|hdtv|dvdrip|remux)\b|\b(?:dual|multi)[ ._-]?audio\b|\b(?:19|20)\d{2}\b/gi;
const seasonFolder = /^(?:season|temporada|saga|s)[\s._-]*(\d{1,2})$/i;
const extraFolder = /^(?:specials?|extras?|ovas?|oads?|especiales)$/i;

function stripBrackets(value) {
  return value.replace(/\[[^\]]*\]|\([^)]*\)|\{[^}]*\}/g, ' ');
}
function spaced(value) {
  const base = value.replace(/\.(?=\S)/g, (match, offset, all) => (/\s/.test(all) ? match : ' ')).replace(/_/g, ' ');
  return cleanText(base);
}
export function cleanTitle(name) {
  const withoutExtension = name.replace(/\.[a-z0-9]{2,4}$/i, '');
  const bracketless = stripBrackets(withoutExtension);
  const quiet = spaced(bracketless).replace(noiseTokens, ' ').replace(/[\s._-]+$/g, '').replace(/^[\s._-]+/g, '');
  return cleanText(quiet) || cleanText(withoutExtension);
}
export function releaseYear(name) {
  const match = String(name).match(/[([ ._-]((?:19|20)\d{2})[)\] ._-]/);
  return match ? Number(match[1]) : null;
}
// { season, episode, title } of a file name; episode is null when it has none.
export function parseEpisodeName(fileName) {
  const base = fileName.replace(/\.[a-z0-9]{2,4}$/i, '');
  const plain = spaced(stripBrackets(base));
  const cleaned = ` ${plain.replace(noiseTokens, ' ')} `;
  let match = cleaned.match(/\bS(\d{1,2})[ ._-]*E(\d{1,4})(?:v\d)?\b/i);
  if (match) return { season: Number(match[1]), episode: Number(match[2]), before: cleaned.slice(0, match.index), after: cleaned.slice(match.index + match[0].length) };
  match = cleaned.match(/\b(?:\d{1,2}x)(\d{2,3})\b/i);
  if (match) return { season: null, episode: Number(match[1]), before: cleaned.slice(0, match.index), after: cleaned.slice(match.index + match[0].length) };
  match = cleaned.match(/\b(?:EP?|Episode|Episodio|Cap(?:itulo)?)[ ._-]*(\d{1,4})(?:v\d)?\b/i);
  if (match) return { season: null, episode: Number(match[1]), before: cleaned.slice(0, match.index), after: cleaned.slice(match.index + match[0].length) };
  const bracketed = base.match(/\[(\d{1,4})(?:v\d)?\]/);
  match = cleaned.match(/ - (\d{1,4})(?:v\d)?(?=\s|$)/) ?? (bracketed ? null : cleaned.match(/(?:^|\s)(\d{1,3})(?:v\d)?(?=\s*$)/));
  if (match) return { season: null, episode: Number(match[1]), before: cleaned.slice(0, match.index), after: cleaned.slice(match.index + match[0].length) };
  if (bracketed) return { season: null, episode: Number(bracketed[1]), before: plain, after: '' };
  return { season: null, episode: null, before: plain, after: '' };
}
function episodeTitle(parsed, number) {
  const after = cleanText(parsed.after.replace(/^[\s._-]+|[\s._-]+$/g, ''));
  return after && !/^v\d$/i.test(after) ? after : `Episode ${number}`;
}

// ---------------------------------------------------------------------------
// Scanning
// ---------------------------------------------------------------------------

async function* walk(directory, depth = 0) {
  let entries;
  try { entries = await readdir(directory, { withFileTypes: true }); } catch { return; }
  entries.sort((left, right) => left.name.localeCompare(right.name, undefined, { numeric: true }));
  for (const entry of entries) {
    if (entry.name.startsWith('.') || entry.name.startsWith('$')) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) { if (depth < 6) yield* walk(path, depth + 1); } else if (entry.isFile()) yield path;
  }
}
async function readJson(path) {
  try { return JSON.parse(await readFile(path, 'utf8')); } catch { return null; }
}
// What the series folder says about itself: `metadata.json`, a poster file.
async function folderDetails(directory, cache) {
  if (cache.has(directory)) return cache.get(directory);
  const metadata = (await readJson(join(directory, 'metadata.json'))) ?? {};
  let artwork = null; let names = [];
  try { names = await readdir(directory); } catch { /* unreadable folders just have no artwork */ }
  const lower = new Map(names.map((name) => [name.toLowerCase(), name]));
  for (const name of artworkNames) {
    const found = artworkExtensions.map((extension) => lower.get(name + extension)).find(Boolean);
    if (found) { artwork = join(directory, found); break; }
  }
  const details = { metadata, artwork };
  cache.set(directory, details);
  return details;
}
function languageGuess(fileName) {
  return /\b(?:dual[ ._-]?audio|dubbed?|multi)\b/i.test(fileName);
}

// Folders → { series: Map(id → series), files: Map(fileId → file) }.
export async function scanFolders(folders, { overrides = {}, artDir = '' } = {}) {
  const groups = new Map(); const files = new Map(); const detailsCache = new Map();
  for (const folder of folders) {
    const root = resolve(folder);
    for await (const path of walk(root)) {
      if (!videoExtensions.has(extname(path).toLowerCase())) continue;
      let info;
      try { info = await stat(path); } catch { continue; }
      if (info.size < 1024) continue;
      const parts = relative(root, path).split(sep);
      const fileName = parts.pop();
      const parsed = parseEpisodeName(fileName);
      let groupParts = parts; let seasonLabel = null; let seasonNumber = null;
      const last = parts.at(-1) ?? '';
      if (parts.length >= 2 && (seasonFolder.test(last) || extraFolder.test(last))) {
        groupParts = parts.slice(0, -1);
        seasonNumber = extraFolder.test(last) ? 0 : Number(last.match(seasonFolder)[1]);
        seasonLabel = extraFolder.test(last) ? cleanTitle(last) : `Season ${seasonNumber}`;
      } else if (parsed.season && parsed.season > 1) {
        seasonNumber = parsed.season; seasonLabel = `Season ${seasonNumber}`;
      }
      let baseTitle; let directory; let looseKey = null;
      if (groupParts.length) {
        baseTitle = cleanTitle(groupParts.at(-1)); directory = join(root, ...groupParts);
      } else {
        // Loose files in the library root: grouped by the title in the name.
        const title = cleanTitle(parsed.before) || cleanTitle(fileName);
        baseTitle = title; directory = root; looseKey = parsed.episode === null ? `file:${path}` : `loose:${normalizedTitle(title)}`;
      }
      const key = `${root}|${groupParts.join('/')}|${looseKey ?? ''}|${seasonNumber ?? ''}`;
      let group = groups.get(key);
      if (!group) {
        group = {
          key, root, directory, baseTitle, seasonNumber, seasonLabel, groupKey: `${root}|${groupParts.join('/')}|${looseKey ?? ''}`,
          loose: groupParts.length === 0, files: [],
        };
        groups.set(key, group);
      }
      const id = sha1(resolve(path).toLowerCase()).slice(0, 16);
      const file = {
        id, path: resolve(path), name: fileName, size: info.size, mtimeMs: info.mtimeMs, birthMs: info.birthtimeMs || info.mtimeMs,
        number: parsed.episode, title: parsed.episode === null ? null : episodeTitle(parsed, parsed.episode),
        dubHint: languageGuess(fileName),
      };
      group.files.push(file); files.set(id, file);
    }
  }
  const series = new Map();
  for (const group of groups.values()) {
    const record = await buildSeries(group, detailsCache, overrides, artDir);
    if (record) series.set(record.id, record);
  }
  // Seasons of one show (same parent folder or same loose title), in order.
  // A `franchise` set in the editor relates series from different folders.
  const byShow = new Map();
  for (const item of series.values()) {
    const show = item.franchise ? `franchise:${normalizedTitle(item.franchise)}` : item.groupKey;
    if (!byShow.has(show)) byShow.set(show, []);
    byShow.get(show).push(item);
  }
  for (const items of byShow.values()) {
    items.sort((left, right) => (left.seasonNumber ?? 1) - (right.seasonNumber ?? 1) || left.title.localeCompare(right.title));
    for (const item of items) item.relatedIds = items.map((related) => related.id);
  }
  return { series, files };
}

// What the editor can change, on top of what the folder says. Same names as
// metadata.json, plus the season, franchise, poster, next air date and the
// per-episode number, title, language and intro/outro ranges.
const statusValues = ['finished', 'airing', 'upcoming'];
const seasonValues = ['winter', 'spring', 'summer', 'fall'];
const ratingValues = ['G', 'PG', 'PG-13', 'R', 'R+', 'Rx'];
const fileIdPattern = /^[0-9a-f]{16}$/;
function skipOverride(value, name) {
  if (value === undefined || value === null) return undefined;
  if (value === false) return false;
  const start = Number(value?.start); const end = Number(value?.end);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start < 0 || end <= start || end > 86400) throw new HttpError(400, `Invalid ${name} range`);
  return { start, end };
}
// Validates what the companion sends; empty values clear a field.
export function cleanOverride(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new HttpError(400, 'Expected an object');
  const out = {};
  const text = (name, maximum) => {
    if (input[name] === undefined || input[name] === null) return;
    const value = cleanText(input[name]).slice(0, maximum); if (value) out[name] = value;
  };
  const choice = (name, allowed) => {
    const value = cleanText(input[name]); if (!value) return;
    const found = allowed.find((option) => option.toLowerCase() === value.toLowerCase());
    if (!found) throw new HttpError(400, `Invalid ${name}`);
    out[name] = found;
  };
  const integer = (name, minimum, maximum) => {
    if (input[name] === undefined || input[name] === null || input[name] === '') return;
    const value = Number(input[name]);
    if (!Number.isInteger(value) || value < minimum || value > maximum) throw new HttpError(400, `Invalid ${name}`);
    out[name] = value;
  };
  text('title', 200); text('titleJp', 200); text('synopsis', 4000); text('seasonLabel', 60); text('franchise', 80); text('mediaType', 20);
  choice('status', statusValues); choice('season', seasonValues); choice('rating', ratingValues);
  integer('year', 1900, 2200); integer('totalEpisodes', 1, 10000); integer('seasonNumber', 0, 99);
  if (input.score !== undefined && input.score !== null && input.score !== '') {
    const score = Number(input.score);
    if (!Number.isFinite(score) || score < 0 || score > 10) throw new HttpError(400, 'Invalid score');
    if (score > 0) out.score = score;
  }
  if (input.nextEpisodeAt !== undefined && input.nextEpisodeAt !== null && input.nextEpisodeAt !== '') {
    const at = Number(input.nextEpisodeAt);
    if (!Number.isFinite(at) || at < 0) throw new HttpError(400, 'Invalid nextEpisodeAt');
    if (at > 0) out.nextEpisodeAt = at;
  }
  for (const flag of ['dub', 'adult']) if (typeof input[flag] === 'boolean') out[flag] = input[flag];
  if (input.genres !== undefined && input.genres !== null) {
    if (!Array.isArray(input.genres)) throw new HttpError(400, 'Invalid genres');
    const genres = [...new Set(input.genres.map((genre) => cleanText(genre).slice(0, 40)).filter(Boolean))].slice(0, 20);
    if (genres.length) out.genres = genres;
  }
  if (input.episodes !== undefined && input.episodes !== null) {
    if (typeof input.episodes !== 'object' || Array.isArray(input.episodes)) throw new HttpError(400, 'Invalid episodes');
    const episodes = {};
    for (const [fileId, value] of Object.entries(input.episodes)) {
      if (!fileIdPattern.test(fileId) || !value || typeof value !== 'object') throw new HttpError(400, 'Invalid episode');
      const episode = {};
      const title = cleanText(value.title).slice(0, 200); if (title) episode.title = title;
      if (value.number !== undefined && value.number !== null && value.number !== '') {
        const number = Number(value.number);
        if (!Number.isInteger(number) || number < 1 || number > 10000) throw new HttpError(400, 'Invalid episode number');
        episode.number = number;
      }
      if (typeof value.dub === 'boolean') episode.dub = value.dub;
      const intro = skipOverride(value.intro, 'intro'); if (intro !== undefined) episode.intro = intro;
      const outro = skipOverride(value.outro, 'outro'); if (outro !== undefined) episode.outro = outro;
      if (Object.keys(episode).length) episodes[fileId] = episode;
    }
    if (Object.keys(episodes).length) out.episodes = episodes;
  }
  return out;
}

export const overrideKeyOf = (groupKey) => sha1(groupKey).slice(0, 16);

async function buildSeries(group, detailsCache, overrides = {}, artDir = '') {
  const { metadata, artwork } = await folderDetails(group.directory, detailsCache);
  const usable = group.loose ? { metadata: {}, artwork: null } : { metadata, artwork };
  const overrideKey = overrideKeyOf(group.key);
  const edit = overrides[overrideKey] ?? {};
  const fields = { ...usable.metadata, ...Object.fromEntries(Object.entries(edit).filter(([name]) => name !== 'episodes' && name !== 'poster')) };
  const files = [...group.files];
  for (const file of files) {
    const change = edit.episodes?.[file.id];
    if (!change) continue;
    if (change.number) { file.number = change.number; if (/^Episode \d+$/.test(file.title ?? '')) file.title = `Episode ${change.number}`; }
    if (change.title) file.title = change.title;
    file.edit = change;
  }
  // Episode numbers: the ones in the names; files without one follow in order.
  const numbered = files.filter((file) => file.number !== null);
  const duplicates = new Map();
  for (const file of numbered) {
    const previous = duplicates.get(file.number);
    if (!previous || file.size > previous.size) duplicates.set(file.number, file);
  }
  const unnumbered = files.filter((file) => file.number === null);
  let next = Math.max(0, ...duplicates.keys());
  const episodes = [...duplicates.values()];
  for (const file of unnumbered) {
    next += 1; file.number = next; file.title = file.edit?.title ?? null;
    episodes.push(file);
  }
  episodes.sort((left, right) => left.number - right.number);
  if (!episodes.length) return null;
  const single = episodes.length === 1 && unnumbered.length === 1;
  const seasonNumber = edit.seasonNumber ?? group.seasonNumber;
  const seasonLabel = edit.seasonLabel ?? (edit.seasonNumber === undefined ? group.seasonLabel : edit.seasonNumber === 0 ? 'Specials' : `Season ${edit.seasonNumber}`);
  const suffixed = (shown, number, label) => (number && number > 1 ? `${shown} ${label}` : shown);
  // The id stays the same while the title is edited: apps keep it in their history.
  const id = `${slug(suffixed(cleanText(usable.metadata.title) || group.baseTitle, group.seasonNumber, group.seasonLabel))}-${sha1(group.key).slice(0, 8)}`;
  const title = edit.title ?? suffixed(cleanText(usable.metadata.title) || group.baseTitle, seasonNumber, seasonLabel);
  const mediaType = cleanText(fields.mediaType) || (single ? 'Movie' : seasonNumber === 0 ? 'Special' : 'TV');
  const genres = Array.isArray(fields.genres) ? fields.genres.map(cleanText).filter(Boolean) : [];
  const rating = cleanText(fields.rating) || null;
  const score = Number(fields.score);
  const newest = Math.max(...episodes.map((file) => file.mtimeMs));
  const poster = edit.poster && artDir ? join(artDir, edit.poster) : null;
  return {
    id, overrideKey, groupKey: group.groupKey, key: group.key, directory: group.directory, title, titleJp: cleanText(fields.titleJp) || null,
    seasonNumber, seasonLabel, franchise: edit.franchise ?? null, edit,
    synopsis: cleanText(fields.synopsis) || null, rating, adult: ['R+', 'Rx'].includes(rating ?? '') || fields.adult === true,
    status: statusValues.includes(fields.status) ? fields.status : 'finished',
    mediaType, score: score > 0 ? score : null, genres, season: cleanText(fields.season).toLowerCase() || null,
    year: Number(fields.year) || releaseYear(group.directory.split(sep).at(-1)) || null,
    totalEpisodes: Number(fields.totalEpisodes) > 0 ? Number(fields.totalEpisodes) : episodes.length,
    nextEpisodeAt: Number(edit.nextEpisodeAt) > 0 ? Number(edit.nextEpisodeAt) : null,
    artwork: poster ?? usable.artwork, customPoster: Boolean(poster), firstFileId: episodes[0].id, updatedMs: newest, addedMs: Math.max(...episodes.map((file) => file.birthMs)),
    dubDefault: fields.dub === true,
    episodes: episodes.map((file) => ({
      number: file.number, title: single ? file.edit?.title ?? title : file.title ?? `Episode ${file.number}`, ref: file.id, fileId: file.id, fileName: file.name,
      sub: true, dub: file.edit?.dub ?? (fields.dub === true || file.dubHint), skip: { intro: file.edit?.intro, outro: file.edit?.outro },
    })),
  };
}

// ---------------------------------------------------------------------------
// ffmpeg / ffprobe
// ---------------------------------------------------------------------------

function createGate(limit) {
  let active = 0; const waiting = [];
  return async (task) => {
    if (active >= limit) await new Promise((resolve) => waiting.push(resolve));
    active += 1;
    try { return await task(); } finally { active -= 1; waiting.shift()?.(); }
  };
}
function run(command, args, { timeoutMs = 120000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
    const out = []; const err = [];
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error(`${basename(command)} timed out`)); }, timeoutMs);
    child.stdout.on('data', (chunk) => out.push(chunk));
    child.stderr.on('data', (chunk) => { err.push(chunk); if (err.length > 50) err.shift(); });
    child.on('error', (error) => { clearTimeout(timer); reject(error.code === 'ENOENT' ? new Error(`${command} was not found; install ffmpeg or set FFMPEG_PATH`) : error); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(Buffer.concat(out));
      else reject(new Error(`${basename(command)} exited with ${code}: ${Buffer.concat(err).toString('utf8').trim().split(/\r?\n/).slice(-2).join(' ')}`));
    });
  });
}

const languageNames = {
  ja: ['Japanese', ['jpn', 'ja', 'jap']], en: ['English', ['eng', 'en']], es: ['Spanish', ['spa', 'es', 'esp']], fr: ['French', ['fre', 'fra', 'fr']],
  de: ['German', ['ger', 'deu', 'de']], it: ['Italian', ['ita', 'it']], pt: ['Portuguese', ['por', 'pt']], ru: ['Russian', ['rus', 'ru']],
  ko: ['Korean', ['kor', 'ko']], zh: ['Chinese', ['chi', 'zho', 'zh', 'cmn']], ar: ['Arabic', ['ara', 'ar']], pl: ['Polish', ['pol', 'pl']],
  tr: ['Turkish', ['tur', 'tr']], id: ['Indonesian', ['ind', 'id']], th: ['Thai', ['tha', 'th']], vi: ['Vietnamese', ['vie', 'vi']],
};
export function languageOf(code) {
  const value = cleanText(code).toLowerCase();
  for (const [tag, [name, aliases]] of Object.entries(languageNames)) if (aliases.includes(value)) return { language: tag, name };
  return { language: 'und', name: value && value !== 'und' ? value : '' };
}

function numberOr(value, fallback = null) { const number = Number(value); return Number.isFinite(number) ? number : fallback; }
function parseProbe(json) {
  const streams = json.streams ?? [];
  const video = streams.find((stream) => stream.codec_type === 'video' && !stream.disposition?.attached_pic) ?? null;
  const audio = streams.filter((stream) => stream.codec_type === 'audio').map((stream, ordinal) => ({
    ordinal, codec: stream.codec_name, channels: numberOr(stream.channels, 2), ...languageOf(stream.tags?.language), title: cleanText(stream.tags?.title),
    default: Boolean(stream.disposition?.default),
  }));
  const subs = streams.filter((stream) => stream.codec_type === 'subtitle').map((stream, ordinal) => ({
    ordinal, codec: stream.codec_name, ...languageOf(stream.tags?.language), title: cleanText(stream.tags?.title),
    forced: Boolean(stream.disposition?.forced), default: Boolean(stream.disposition?.default),
  })).filter((stream) => textSubtitleCodecs.has(stream.codec));
  const chapters = (json.chapters ?? []).map((chapter) => ({ start: numberOr(chapter.start_time, 0), end: numberOr(chapter.end_time, 0), title: cleanText(chapter.tags?.title) }));
  return {
    duration: numberOr(json.format?.duration, 0), bitrate: numberOr(json.format?.bit_rate, 0),
    video: video && { index: video.index, codec: video.codec_name, pixFmt: video.pix_fmt, width: numberOr(video.width, 0), height: numberOr(video.height, 0), interlaced: video.field_order && video.field_order !== 'progressive' && video.field_order !== 'unknown' },
    audio, subs, chapters,
  };
}
// Intro/outro ranges from chapter names ("Opening", "Ending", "Credits"…).
export function skipRanges(chapters) {
  const find = (pattern) => chapters.find((chapter) => pattern.test(chapter.title) && chapter.end > chapter.start);
  const intro = find(/^(?:intro(?:duction)?|opening|op)(?:\b|\d|$)/i);
  const outro = find(/^(?:outro|ending|ed|credits?)(?:\b|\d|$)/i);
  return { intro: intro ? { start: intro.start, end: intro.end } : null, outro: outro ? { start: outro.start, end: outro.end } : null };
}
// Copying is possible when every player accepts the stream as it is.
export function needsVideoTranscode(video, forced = false) {
  if (forced || !video) return Boolean(forced);
  return video.codec !== 'h264' || !/^(?:yuvj?420p)$/.test(video.pixFmt ?? '') || Boolean(video.interlaced);
}
export function planSegments({ transcode, keyframes, duration }) {
  const total = duration > 0 ? duration : (keyframes.at(-1) ?? 0) + 1;
  const segments = [];
  if (transcode || !keyframes.length) {
    for (let start = 0; start < total; start += segmentSeconds) segments.push({ start, end: Math.min(total, start + segmentSeconds) });
    return segments;
  }
  let current = 0;
  for (const keyframe of keyframes) {
    if (keyframe - current >= segmentSeconds) { segments.push({ start: current, end: keyframe }); current = keyframe; }
  }
  if (total > current) segments.push({ start: current, end: total });
  return segments;
}
function audioCopyable(codec) { return ['aac', 'ac3', 'eac3', 'mp3'].includes(codec); }

// ---------------------------------------------------------------------------
// The provider
// ---------------------------------------------------------------------------

export function createLocalProvider({
  folders = [], dataDir, ffmpegPath = 'ffmpeg', ffprobePath = 'ffprobe', concurrency = 3, forceTranscode = false, videoEncoder = '', log = () => {},
} = {}) {
  let configuredFolders = [...folders]; let library = { series: new Map(), files: new Map() };
  let scanning = null; let lastScan = null; let scanError = null;
  const gate = createGate(Math.max(1, concurrency));
  const probes = new Map(); const subtitleCache = new Map(); const segmentCache = new Map(); const inflight = new Map();
  const artDir = join(dataDir, 'art'); const cacheDir = join(dataDir, 'cache');

  // What the companion edited (titles, seasons, posters, intro/outro ranges…).
  const overridesPath = join(dataDir, 'catalog.json');
  async function readOverrides() {
    const data = await readJson(overridesPath);
    return data?.series && typeof data.series === 'object' && !Array.isArray(data.series) ? data.series : {};
  }
  async function writeOverrides(next) {
    await mkdir(dataDir, { recursive: true });
    await writeFile(`${overridesPath}.tmp`, JSON.stringify({ version: 1, series: next }, null, 2));
    await rename(`${overridesPath}.tmp`, overridesPath);
  }

  async function rescan() {
    if (scanning) return scanning;
    scanning = (async () => {
      const started = Date.now();
      try {
        library = await scanFolders(configuredFolders, { overrides: await readOverrides(), artDir }); scanError = null; lastScan = Date.now();
        log(`Library scanned: ${library.series.size} series, ${library.files.size} file(s) in ${((Date.now() - started) / 1000).toFixed(1)}s`);
      } catch (error) { scanError = error.message; log(`Library scan failed: ${error.message}`); }
      scanning = null;
      return status();
    })();
    return scanning;
  }
  // A scan that started before an edit does not include it: wait, then scan again.
  async function rescanFresh() {
    if (scanning) await scanning;
    return rescan();
  }
  function setFolders(next) { configuredFolders = next.map((folder) => resolve(String(folder))); return rescan(); }
  function status() {
    return { folders: configuredFolders, series: library.series.size, files: library.files.size, scanning: Boolean(scanning), lastScan, error: scanError };
  }

  // ---- catalog -----------------------------------------------------------
  const watchUrlFor = (item) => `${libraryOrigin}/watch/${item.id}`;
  const artUrl = (item) => `/art/${item.id}.jpg`;
  function watchUrl(value) {
    const url = new URL(String(value), libraryOrigin);
    if (!/^\/watch\/[\w-]+\/?$/.test(url.pathname)) throw new HttpError(400, 'Only library watch URLs are accepted');
    return new URL(url.pathname.replace(/\/$/, ''), libraryOrigin).toString();
  }
  function seriesFor(url) {
    const found = library.series.get(new URL(url).pathname.split('/')[2]);
    if (!found) throw new HttpError(404, 'Series was not found in the library');
    return found;
  }
  function card(item, language = 'all') {
    const sub = item.episodes.length; const dub = item.episodes.filter((episode) => episode.dub).length;
    const newest = language === 'dub' ? Math.max(0, ...item.episodes.filter((episode) => episode.dub).map((episode) => episode.number)) : item.episodes.at(-1).number;
    return {
      title: item.title, titleJp: item.titleJp, watchUrl: watchUrlFor(item), imageUrl: artUrl(item),
      meta: [item.mediaType, `${item.episodes.length} ep`].filter(Boolean).join(' · '), rating: item.rating, score: item.score,
      mediaType: item.mediaType, year: item.year, adult: item.adult, episode: newest || null, subEpisodes: sub, dubEpisodes: dub || null, totalEpisodes: item.totalEpisodes,
    };
  }
  function search(keyword) {
    const needle = normalizedTitle(String(keyword ?? '').slice(0, 200));
    return [...library.series.values()]
      .filter((item) => !needle || normalizedTitle(`${item.title} ${item.titleJp ?? ''}`).includes(needle))
      .sort((left, right) => left.title.localeCompare(right.title)).slice(0, 60).map((item) => card(item));
  }
  const genreId = (name) => slug(name);
  const sortOptions = [['latest-updated', 'Actualizados recientemente', 'Recently updated'], ['latest-added', 'Añadidos recientemente', 'Recently added'],
    ['score', 'Puntuación', 'Score'], ['release-date', 'Fecha de estreno', 'Release date'], ['name-az', 'Nombre (A-Z)', 'Name (A-Z)'],
    ['number_of_episodes', 'Número de episodios', 'Number of episodes'], ['default', 'Relevancia', 'Relevance']];
  const mediaTypeOptions = ['TV', 'Movie', 'ONA', 'OVA', 'Special'].map((id) => ({ id, label: { Movie: 'Película', Special: 'Especial' }[id] ?? id, labelEn: id }));
  function filters() {
    const genres = new Map();
    for (const item of library.series.values()) for (const genre of item.genres) genres.set(genreId(genre), genre);
    return {
      genres: [...genres].sort((left, right) => left[1].localeCompare(right[1])).map(([id, name]) => ({ id, label: name, labelEn: name })),
      mediaTypes: mediaTypeOptions, sources: [],
      sorts: sortOptions.map(([id, label, labelEn]) => ({ id, label, labelEn })),
    };
  }
  function values(params, name, allowed) {
    const found = [...new Set(params.getAll(name).map(cleanText).filter(Boolean))];
    const invalid = found.find((value) => !allowed(value));
    if (invalid !== undefined) throw new HttpError(400, `Invalid ${name}`);
    return found;
  }
  function latest(params) {
    const page = Math.max(1, Math.min(10000, Number.parseInt(params.get('page') || '1', 10) || 1));
    const keyword = normalizedTitle(cleanText(params.get('q')).slice(0, 200));
    const available = filters();
    const seasons = values(params, 'season', (value) => ['winter', 'spring', 'summer', 'fall'].includes(value));
    const statuses = values(params, 'status', (value) => ['finished-airing', 'currently-airing', 'not-yet-aired'].includes(value));
    const ratings = values(params, 'rating', (value) => ['G', 'PG', 'PG-13', 'R', 'R+', 'Rx'].includes(value));
    const mediaTypes = values(params, 'mediaType', (value) => available.mediaTypes.some((option) => option.id === value));
    const years = values(params, 'year', (value) => /^\d{4}$/.test(value));
    const genres = values(params, 'genre', (value) => available.genres.some((option) => option.id === value));
    values(params, 'source', () => true);
    const sorts = values(params, 'sort', (value) => available.sorts.some((option) => option.id === value));
    const bound = (name) => {
      const value = cleanText(params.get(name));
      if (!value) return null;
      const number = Number(value);
      if (!Number.isInteger(number) || number < 1 || number > 10000) throw new HttpError(400, `Invalid ${name}`);
      return number;
    };
    const minEpisodes = bound('minEpisodes'); const maxEpisodes = bound('maxEpisodes');
    const languages = values(params, 'language', (value) => ['sub', 'dub', 'all'].includes(value));
    const language = languages.length === 1 ? languages[0] : 'all';
    const statusOf = { 'finished-airing': 'finished', 'currently-airing': 'airing', 'not-yet-aired': 'upcoming' };
    let items = [...library.series.values()].filter((item) => {
      if (keyword && !normalizedTitle(`${item.title} ${item.titleJp ?? ''}`).includes(keyword)) return false;
      if (seasons.length && !seasons.includes(item.season)) return false;
      if (statuses.length && !statuses.some((status) => statusOf[status] === item.status)) return false;
      if (ratings.length && !ratings.includes(item.rating)) return false;
      if (mediaTypes.length && !mediaTypes.some((type) => type.toLowerCase() === item.mediaType.toLowerCase())) return false;
      if (years.length && !years.includes(String(item.year))) return false;
      if (genres.length && !genres.every((genre) => item.genres.some((name) => genreId(name) === genre))) return false;
      if (minEpisodes && item.episodes.length < minEpisodes) return false;
      if (maxEpisodes && item.episodes.length > maxEpisodes) return false;
      if (language === 'dub' && !item.episodes.some((episode) => episode.dub)) return false;
      return true;
    });
    const sort = sorts[0] ?? (keyword ? 'default' : 'latest-updated');
    const byName = (left, right) => left.title.localeCompare(right.title);
    const order = {
      'latest-updated': (left, right) => right.updatedMs - left.updatedMs, 'latest-added': (left, right) => right.addedMs - left.addedMs,
      score: (left, right) => (right.score ?? 0) - (left.score ?? 0) || byName(left, right), 'release-date': (left, right) => (right.year ?? 0) - (left.year ?? 0) || byName(left, right),
      'name-az': byName, number_of_episodes: (left, right) => right.episodes.length - left.episodes.length || byName(left, right), default: byName,
    }[sort];
    items = items.sort(order);
    const slice = items.slice((page - 1) * pageSize, page * pageSize);
    return { results: slice.map((item) => card(item, language)), page, hasNext: items.length > page * pageSize };
  }
  function seasonRecord(item) {
    return {
      title: item.title, titleJp: item.titleJp, watchUrl: watchUrlFor(item), imageUrl: artUrl(item), meta: `${item.mediaType} · ${item.episodes.length} ep`,
      label: item.seasonLabel, seasonNumber: item.seasonNumber ?? 1, mediaType: item.mediaType,
    };
  }
  function series(url) {
    const item = seriesFor(url);
    const related = (item.relatedIds ?? [item.id]).map((id) => library.series.get(id)).filter(Boolean);
    return {
      title: item.title, titleJp: item.titleJp, watchUrl: watchUrlFor(item), synopsis: item.synopsis, rating: item.rating, adult: item.adult,
      status: item.status, mediaType: item.mediaType, score: item.score, totalEpisodes: item.totalEpisodes, imageUrl: artUrl(item), nextEpisodeAt: item.nextEpisodeAt,
      episodes: item.episodes.map(({ number, title, ref, sub, dub }) => ({ number, title, ref, sub, dub })),
      seasons: related.map(seasonRecord),
    };
  }
  function schedule() { return []; }

  // ---- probing -----------------------------------------------------------
  function fileFor(fileId) {
    const file = library.files.get(fileId);
    if (!file) throw new HttpError(404, 'File was not found in the library');
    return file;
  }
  async function probe(fileId) {
    const file = fileFor(fileId); const cached = probes.get(fileId);
    if (cached && cached.mtimeMs === file.mtimeMs) return cached.promise;
    const promise = gate(async () => {
      const output = await run(ffprobePath, ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', '-show_chapters', file.path]);
      return parseProbe(JSON.parse(output.toString('utf8')));
    });
    probes.set(fileId, { mtimeMs: file.mtimeMs, promise });
    promise.catch(() => probes.delete(fileId));
    return promise;
  }
  async function keyframes(fileId) {
    const file = fileFor(fileId);
    const cachePath = join(cacheDir, `kf-${fileId}-${file.size}-${Math.round(file.mtimeMs)}.json`);
    const cached = await readJson(cachePath);
    if (Array.isArray(cached)) return cached;
    const output = await gate(() => run(ffprobePath, ['-v', 'error', '-select_streams', 'v:0', '-show_entries', 'packet=pts_time,flags', '-of', 'csv=p=0', file.path], { timeoutMs: 600000 }));
    const times = output.toString('utf8').split(/\r?\n/).map((line) => line.split(',')).filter(([, flags]) => flags?.startsWith('K')).map(([time]) => Number(time)).filter(Number.isFinite);
    const sorted = [...new Set(times)].sort((left, right) => left - right);
    mkdir(cacheDir, { recursive: true }).then(() => writeFile(cachePath, JSON.stringify(sorted))).catch(() => {});
    return sorted;
  }
  async function planFor(fileId) {
    const info = await probe(fileId); const transcode = needsVideoTranscode(info.video, forceTranscode);
    const marks = transcode ? [] : await keyframes(fileId);
    return { info, transcode, segments: planSegments({ transcode, keyframes: marks, duration: info.duration }) };
  }

  // ---- servers and sources ----------------------------------------------
  // One server per audio track. The original audio (Japanese when the file
  // has it, else the first track) is "Sub"; every other language is "Dub".
  async function servers(fileId) {
    const info = await probe(fileId);
    if (!info.audio.length) throw new Error('The file has no audio track');
    const original = info.audio.find((track) => track.language === 'ja') ?? info.audio.find((track) => track.default) ?? info.audio[0];
    return info.audio.map((track, index) => {
      const channels = track.channels > 2 ? ` ${track.channels - 1}.1` : '';
      const label = [track.name || track.title || `Audio ${index + 1}`, `(${String(track.codec).toUpperCase()}${channels})`].join(' ');
      return { index, name: label, type: track === original ? 'sub' : 'dub', ref: `${fileId}:${track.ordinal}` };
    });
  }
  async function source(serverRef) {
    const [fileId, audioText] = String(serverRef).split(':'); const audioIndex = Number(audioText);
    const info = await probe(fileId); const file = fileFor(fileId);
    const audio = info.audio.find((track) => track.ordinal === audioIndex);
    if (!audio) throw new Error('Audio track was not found');
    const raw = [];
    info.subs.forEach((track, index) => raw.push({ kind: 'subtitles', label: [track.title || track.name || `Subtitles ${index + 1}`, track.forced && !/forced/i.test(track.title) ? 'Forced' : ''].filter(Boolean).join(' '), srclang: track.language === 'und' ? '' : track.language, file: `local://${fileId}/sub/${raw.length}.vtt` }));
    const sidecars = await sidecarSubtitles(file);
    sidecars.forEach((sidecar) => raw.push({ kind: 'subtitles', label: sidecar.label, srclang: sidecar.language, file: `local://${fileId}/sub/${raw.length}.vtt` }));
    // Ranges set in the companion win over the ones found in the chapters (false turns one off).
    const ranges = skipRanges(info.chapters);
    for (const name of ['intro', 'outro']) {
      const set = file.edit?.[name];
      if (set === false) ranges[name] = null; else if (set) ranges[name] = set;
    }
    return {
      hlsUrl: `local://${fileId}/a${audioIndex}/master.m3u8`, ...ranges, rawSubtitles: raw,
      audio: { name: audio.name || 'Original', language: audio.language === 'und' ? 'ja' : audio.language },
    };
  }
  async function sidecarSubtitles(file) {
    const directory = dirname(file.path); const stem = basename(file.path, extname(file.path)).toLowerCase();
    let names = [];
    try { names = await readdir(directory); } catch { return []; }
    const found = [];
    for (const name of names.sort()) {
      const lower = name.toLowerCase(); const extension = extname(lower);
      if (!sidecarSubtitleExtensions.has(extension) || !lower.startsWith(stem)) continue;
      const suffix = lower.slice(stem.length, lower.length - extension.length).replace(/^[\s._-]+/, '');
      const language = languageOf(suffix.split(/[\s._-]+/).find((part) => languageOf(part).language !== 'und') ?? '');
      found.push({ path: join(directory, name), label: suffix ? cleanText(suffix.replace(/[._-]+/g, ' ')) : language.name || 'Subtitles', language: language.language === 'und' ? '' : language.language });
    }
    return found;
  }
  async function subtitleVtt(fileId, index) {
    const key = `${fileId}:${index}`;
    if (subtitleCache.has(key)) return subtitleCache.get(key);
    const file = fileFor(fileId); const info = await probe(fileId);
    let args;
    if (index < info.subs.length) args = ['-v', 'error', '-nostdin', '-i', file.path, '-map', `0:s:${info.subs[index].ordinal}`, '-f', 'webvtt', 'pipe:1'];
    else {
      const sidecar = (await sidecarSubtitles(file))[index - info.subs.length];
      if (!sidecar) throw new HttpError(404, 'Unknown subtitle track');
      args = ['-v', 'error', '-nostdin', '-i', sidecar.path, '-f', 'webvtt', 'pipe:1'];
    }
    const vtt = (await gate(() => run(ffmpegPath, args))).toString('utf8');
    if (!/^(?:﻿)?WEBVTT/.test(vtt)) throw new Error('Subtitle conversion did not return WebVTT');
    if (subtitleCache.size > 200) subtitleCache.delete(subtitleCache.keys().next().value);
    subtitleCache.set(key, vtt);
    return vtt;
  }

  // ---- media (read by the server in place of an HTTP upstream) -----------
  let encoderChoice = null;
  async function chooseEncoder() {
    encoderChoice ??= (async () => {
      const candidates = videoEncoder ? [videoEncoder] : ['h264_nvenc', 'h264_qsv', 'h264_amf'];
      for (const encoder of candidates) {
        try {
          await run(ffmpegPath, ['-v', 'error', '-nostdin', '-f', 'lavfi', '-i', 'color=c=black:s=256x256:d=0.2', '-c:v', encoder, '-f', 'null', '-'], { timeoutMs: 15000 });
          log(`Video encoder: ${encoder}`); return encoder;
        } catch { /* not available on this computer */ }
      }
      log('Video encoder: libx264');
      return 'libx264';
    })();
    return encoderChoice;
  }
  function audioArgs(plan, audioOrdinal) {
    const audio = plan.info.audio.find((track) => track.ordinal === audioOrdinal);
    if (!audio) throw new HttpError(404, 'Audio track was not found');
    return audioCopyable(audio.codec) ? ['-c:a', 'copy'] : ['-c:a', 'aac', '-b:a', '192k', '-ac', String(Math.min(2, audio.channels || 2))];
  }
  // Transcoding: one segment per ffmpeg run, cut at an exact time (the seek
  // decodes from the previous keyframe, so it is accurate wherever it lands).
  async function transcodeSegment(file, plan, audioOrdinal, number) {
    const { start, end } = plan.segments[number]; const encoder = await chooseEncoder();
    const args = ['-v', 'error', '-nostdin', '-ss', start.toFixed(3), '-i', file.path, '-t', (end - start).toFixed(3),
      '-map', `0:${plan.info.video.index}`, '-map', `0:a:${audioOrdinal}`, '-sn', '-dn', '-c:v', encoder];
    if (encoder === 'libx264') args.push('-preset', 'veryfast', '-crf', '21'); else args.push('-b:v', '6M');
    args.push('-pix_fmt', 'yuv420p', '-profile:v', 'high', '-force_key_frames', '0', '-vf', 'yadif=deint=interlaced', ...audioArgs(plan, audioOrdinal),
      '-max_muxing_queue_size', '2048', '-output_ts_offset', start.toFixed(3), '-f', 'mpegts', 'pipe:1');
    return await gate(() => run(ffmpegPath, args, { timeoutMs: 180000 }));
  }
  // Copying: the whole file is remuxed once, in the background, cutting at the
  // keyframes of the plan with ffmpeg's segment muxer. Seeking to a keyframe
  // inside the container is not reliable (a coarse index lands earlier and
  // the segments would overlap); a continuous remux is, and runs far faster
  // than playback. A segment is served once its successor has started.
  const remuxes = new Map(); const remuxRoot = join(cacheDir, 'hls');
  async function pruneRemuxes() {
    let names = [];
    try { names = await readdir(remuxRoot); } catch { return; }
    const running = new Set([...remuxes.values()].filter((job) => !job.done && !job.error).map((job) => job.key));
    const entries = [];
    for (const name of names) { try { entries.push({ name, mtime: (await stat(join(remuxRoot, name))).mtimeMs }); } catch { /* gone */ } }
    entries.sort((left, right) => right.mtime - left.mtime);
    for (const entry of entries.slice(6)) if (!running.has(entry.name)) await rm(join(remuxRoot, entry.name), { recursive: true, force: true }).catch(() => {});
  }
  function startRemux(file, plan, audioOrdinal) {
    const key = `${file.id}-a${audioOrdinal}-${file.size}-${Math.round(file.mtimeMs)}`;
    const known = remuxes.get(key);
    if (known && !known.error) return known;
    const job = { key, dir: join(remuxRoot, key), done: false, error: null };
    remuxes.set(key, job);
    job.ready = (async () => {
      await pruneRemuxes();
      if (existsSync(join(job.dir, 'complete'))) { job.done = true; return; }
      await rm(job.dir, { recursive: true, force: true }); await mkdir(job.dir, { recursive: true });
      const cuts = plan.segments.slice(1).map((item) => (item.start - 0.0005).toFixed(4)).join(',');
      const args = ['-v', 'error', '-nostdin', '-i', file.path, '-map', `0:${plan.info.video.index}`, '-map', `0:a:${audioOrdinal}`, '-sn', '-dn', '-c:v', 'copy',
        ...audioArgs(plan, audioOrdinal), '-max_muxing_queue_size', '2048', '-f', 'segment', ...(cuts ? ['-segment_times', cuts] : []), '-segment_format', 'mpegts', join(job.dir, 'seg%d.ts')];
      const child = spawn(ffmpegPath, args, { windowsHide: true, stdio: ['ignore', 'ignore', 'pipe'] });
      job.child = child; const err = [];
      child.stderr.on('data', (chunk) => { err.push(chunk); if (err.length > 20) err.shift(); });
      child.on('error', (error) => { job.error = error.message; });
      child.on('close', (code) => {
        if (code === 0) { job.done = true; writeFile(join(job.dir, 'complete'), '').catch(() => {}); } else job.error ??= `ffmpeg exited with ${code}: ${Buffer.concat(err).toString('utf8').trim().split(/\r?\n/).slice(-2).join(' ')}`;
      });
    })().catch((error) => { job.error = error.message; });
    return job;
  }
  async function copiedSegment(file, plan, audioOrdinal, number) {
    const job = startRemux(file, plan, audioOrdinal); await job.ready;
    const path = join(job.dir, `seg${number}.ts`); const successor = join(job.dir, `seg${number + 1}.ts`);
    const deadline = Date.now() + 240000;
    for (;;) {
      if (job.error) throw new Error(job.error);
      if (existsSync(path) && (job.done || existsSync(successor))) return await readFile(path);
      if (job.done) throw new HttpError(404, 'Unknown segment');
      if (Date.now() > deadline) throw new Error('Timed out waiting for the remux');
      await new Promise((resolve) => setTimeout(resolve, 80));
    }
  }
  async function segment(fileId, audioOrdinal, number) {
    const key = `${fileId}:${audioOrdinal}:${number}`;
    if (segmentCache.has(key)) return segmentCache.get(key);
    if (inflight.has(key)) return inflight.get(key);
    const promise = (async () => {
      const file = fileFor(fileId); const plan = await planFor(fileId);
      if (!plan.segments[number]) throw new HttpError(404, 'Unknown segment');
      const body = plan.transcode ? await transcodeSegment(file, plan, audioOrdinal, number) : await copiedSegment(file, plan, audioOrdinal, number);
      segmentCache.set(key, body);
      if (segmentCache.size > 24) segmentCache.delete(segmentCache.keys().next().value);
      // Playback is sequential: warm the next segment while this one plays.
      if (plan.transcode && plan.segments[number + 1]) segment(fileId, audioOrdinal, number + 1).catch(() => {});
      return body;
    })();
    inflight.set(key, promise);
    try { return await promise; } finally { inflight.delete(key); }
  }
  async function mediaPlaylist(fileId) {
    const plan = await planFor(fileId);
    const target = Math.ceil(Math.max(...plan.segments.map((item) => item.end - item.start), segmentSeconds));
    const lines = ['#EXTM3U', '#EXT-X-VERSION:3', `#EXT-X-TARGETDURATION:${target}`, '#EXT-X-MEDIA-SEQUENCE:0', '#EXT-X-PLAYLIST-TYPE:VOD'];
    plan.segments.forEach((item, index) => { lines.push(`#EXTINF:${(item.end - item.start).toFixed(3)},`, `seg${index}.ts`); });
    lines.push('#EXT-X-ENDLIST', '');
    return lines.join('\n');
  }
  async function masterPlaylist(fileId) {
    const info = await probe(fileId);
    const bandwidth = Math.max(500000, Math.round(info.bitrate || 5000000));
    const resolution = info.video?.width ? `,RESOLUTION=${info.video.width}x${info.video.height}` : '';
    return ['#EXTM3U', '#EXT-X-VERSION:3', `#EXT-X-STREAM-INF:BANDWIDTH=${bandwidth}${resolution}`, 'media.m3u8', ''].join('\n');
  }
  // Same shape as an upstream response: { url, type, body }.
  async function read(value) {
    const url = new URL(value); const fileId = url.hostname; const parts = url.pathname.split('/').filter(Boolean);
    const audioIndex = Number((parts[0] ?? '').replace(/^a/, '')) || 0;
    const respond = (type, body) => ({ url: url.toString(), type, body: Buffer.isBuffer(body) ? body : Buffer.from(body, 'utf8') });
    if (parts[0]?.startsWith('a') && parts[1] === 'master.m3u8') return respond('application/vnd.apple.mpegurl', await masterPlaylist(fileId));
    if (parts[0]?.startsWith('a') && parts[1] === 'media.m3u8') return respond('application/vnd.apple.mpegurl', await mediaPlaylist(fileId));
    const segmentMatch = parts[1]?.match(/^seg(\d+)\.ts$/);
    if (parts[0]?.startsWith('a') && segmentMatch) return respond('video/mp2t', await segment(fileId, audioIndex, Number(segmentMatch[1])));
    if (parts[0] === 'sub' && /^\d+\.vtt$/.test(parts[1] ?? '')) return respond('text/vtt; charset=utf-8', await subtitleVtt(fileId, Number.parseInt(parts[1], 10)));
    throw new HttpError(404, 'Unknown media request');
  }

  // ---- artwork -----------------------------------------------------------
  async function art(seriesId) {
    const item = library.series.get(seriesId);
    if (!item) return null;
    if (item.artwork) {
      try { return { type: { '.png': 'image/png', '.webp': 'image/webp' }[extname(item.artwork).toLowerCase()] ?? 'image/jpeg', body: await readFile(item.artwork) }; } catch { /* fall through to a frame */ }
    }
    const cached = join(artDir, `${seriesId}.jpg`);
    try { return { type: 'image/jpeg', body: await readFile(cached) }; } catch { /* generate below */ }
    try {
      const file = fileFor(item.firstFileId); const info = await probe(item.firstFileId);
      const at = Math.max(1, Math.min(info.duration * 0.12, 120));
      const body = await gate(() => run(ffmpegPath, ['-v', 'error', '-nostdin', '-ss', at.toFixed(1), '-i', file.path, '-frames:v', '1', '-vf', 'scale=-2:480', '-q:v', '4', '-f', 'image2pipe', '-vcodec', 'mjpeg', 'pipe:1'], { timeoutMs: 30000 }));
      mkdir(artDir, { recursive: true }).then(() => writeFile(cached, body)).catch(() => {});
      return { type: 'image/jpeg', body };
    } catch (error) {
      log(`Artwork unavailable for ${item.title}: ${error.message}`);
      return null;
    }
  }

  // ---- editor (the companion) --------------------------------------------
  const posterTypes = { 'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp' };
  const itemFor = (id) => {
    const item = library.series.get(id);
    if (!item) throw new HttpError(404, 'Series was not found in the library');
    return item;
  };
  function catalog() {
    return [...library.series.values()].map((item) => ({
      id: item.id, title: item.title, folder: item.directory, seasonNumber: item.seasonNumber ?? 1, seasonLabel: item.seasonLabel, franchise: item.franchise,
      episodes: item.episodes.length, imageUrl: artUrl(item), customPoster: item.customPoster, edited: Object.keys(item.edit).length > 0,
    })).sort((left, right) => left.title.localeCompare(right.title));
  }
  function detail(id) {
    const item = itemFor(id);
    return {
      id: item.id, folder: item.directory, imageUrl: artUrl(item), customPoster: item.customPoster, override: item.edit,
      effective: {
        title: item.title, titleJp: item.titleJp, synopsis: item.synopsis, rating: item.rating, adult: item.adult, status: item.status, mediaType: item.mediaType,
        score: item.score, genres: item.genres, season: item.season, year: item.year, totalEpisodes: item.totalEpisodes, seasonNumber: item.seasonNumber ?? 1,
        seasonLabel: item.seasonLabel, franchise: item.franchise, nextEpisodeAt: item.nextEpisodeAt, dub: item.dubDefault,
      },
      related: (item.relatedIds ?? [item.id]).map((relatedId) => library.series.get(relatedId)).filter(Boolean)
        .map((related) => ({ id: related.id, title: related.title, seasonNumber: related.seasonNumber ?? 1 })),
      episodes: item.episodes.map((episode) => ({
        fileId: episode.fileId, number: episode.number, title: episode.title, fileName: episode.fileName, dub: episode.dub,
        intro: episode.skip.intro ?? null, outro: episode.skip.outro ?? null,
      })),
    };
  }
  async function saveSeries(id, input) {
    const item = itemFor(id); const clean = cleanOverride(input);
    const stored = await readOverrides();
    for (const fileId of Object.keys(clean.episodes ?? {})) if (!item.episodes.some((episode) => episode.fileId === fileId)) throw new HttpError(400, 'Episode does not belong to this series');
    if (stored[item.overrideKey]?.poster) clean.poster = stored[item.overrideKey].poster;
    if (Object.keys(clean).length) stored[item.overrideKey] = clean; else delete stored[item.overrideKey];
    await writeOverrides(stored); await rescanFresh();
    return detail(id);
  }
  function posterExtension(body, type) {
    const extension = posterTypes[String(type).split(';')[0].trim().toLowerCase()];
    const isJpeg = body[0] === 0xff && body[1] === 0xd8; const isPng = body.subarray(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47]));
    const isWebp = body.subarray(0, 4).toString('latin1') === 'RIFF' && body.subarray(8, 12).toString('latin1') === 'WEBP';
    if (!extension || !((extension === '.jpg' && isJpeg) || (extension === '.png' && isPng) || (extension === '.webp' && isWebp))) throw new HttpError(415, 'The poster must be a JPEG, PNG or WebP image');
    return extension;
  }
  async function removePoster(name) {
    if (name) await rm(join(artDir, name), { force: true }).catch(() => {});
  }
  async function setPoster(id, body, type) {
    const item = itemFor(id); const extension = posterExtension(body, type);
    const stored = await readOverrides(); const name = `custom-${item.overrideKey}${extension}`;
    await mkdir(artDir, { recursive: true }); await writeFile(join(artDir, name), body);
    if (stored[item.overrideKey]?.poster && stored[item.overrideKey].poster !== name) await removePoster(stored[item.overrideKey].poster);
    stored[item.overrideKey] = { ...stored[item.overrideKey], poster: name };
    await writeOverrides(stored); await rescanFresh();
    return detail(id);
  }
  async function clearPoster(id) {
    const item = itemFor(id); const stored = await readOverrides(); const entry = stored[item.overrideKey];
    if (entry?.poster) {
      await removePoster(entry.poster); delete entry.poster;
      if (Object.keys(entry).length) stored[item.overrideKey] = entry; else delete stored[item.overrideKey];
      await writeOverrides(stored); await rescanFresh();
    }
    return detail(id);
  }
  // The chapters of an episode and the intro/outro found in them, for the editor.
  async function episodeMarks(fileId) {
    const info = await probe(fileId); const file = fileFor(fileId);
    return { fileId, duration: info.duration, chapters: info.chapters, detected: skipRanges(info.chapters), override: { intro: file.edit?.intro ?? null, outro: file.edit?.outro ?? null } };
  }

  async function tools() {
    try {
      const version = (await run(ffmpegPath, ['-version'], { timeoutMs: 10000 })).toString('utf8').split(/\r?\n/)[0];
      return { ffmpeg: true, version };
    } catch (error) { return { ffmpeg: false, error: error.message }; }
  }

  return {
    type: 'LOCAL', origin: libraryOrigin, watchUrl, search, latest, series, schedule, servers, source, get filters() { return filters(); },
    media: { read }, stop() { for (const job of remuxes.values()) job.child?.kill(); }, rescan, setFolders, status, art, tools, subtitleVtt,
    editor: { catalog, detail, saveSeries, setPoster, clearPoster, episodeMarks },
  };
}

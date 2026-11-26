import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request

from yt_dlp.postprocessor.common import PostProcessor
from yt_dlp.utils import PostProcessingError

API = 'https://lrclib.net/api'
USER_AGENT = 'DFast (https://github.com/MenuM7D/dfast)'
APPLICATION_ID = 'DzQSE74gVl7mtIo7csJ2ZkJhZXvr-8v3ilv7k9SB6SM'
DURATION_TOLERANCE = 5
MAX_ATTEMPTS = 4
MAX_RETRY_DELAY = 10
RETRY_CODES = (429, 500, 502, 503, 504)
TIME_BUDGET = 25  # seconds for the whole lyrics step, so it can never hold up a download
_deadline = [0.0]

_LRC_STAMP = re.compile(r'\[(\d+):(\d+(?:\.\d+)?)\]')
_JUNK_BRACKETS = re.compile(
    r'\s*[\(\[][^\)\]]*(?:official|video|audio|lyric|visuali[sz]er|\bhd\b|\b4k\b|\bmv\b|remaster)[^\)\]]*[\)\]]',
    re.IGNORECASE)


def _get_json(path, params=None):
    url = f'{API}/{path}'
    if params:
        url += '?' + urllib.parse.urlencode({k: v for k, v in params.items() if v not in (None, '')})
    req = urllib.request.Request(url, headers={'User-Agent': USER_AGENT, 'Lrclib-App-Id': APPLICATION_ID})
    for attempt in range(MAX_ATTEMPTS):
        if _deadline[0] and time.time() > _deadline[0]:
            raise TimeoutError('lyrics time budget used up')
        last = attempt == MAX_ATTEMPTS - 1
        try:
            with urllib.request.urlopen(req, timeout=10) as res:
                return json.loads(res.read().decode('utf-8'))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
            if e.code not in RETRY_CODES or last:
                raise
            delay = _retry_delay(e.headers.get('Retry-After'), attempt)
        except (urllib.error.URLError, TimeoutError):
            if last:
                raise
            delay = _retry_delay(None, attempt)
        time.sleep(delay)


def _retry_delay(retry_after, attempt):
    """Seconds to wait, honoring Retry-After (seconds) when lrclib sends it."""
    try:
        return min(max(float(retry_after), 0), MAX_RETRY_DELAY)
    except (TypeError, ValueError):
        return min(2 ** attempt, MAX_RETRY_DELAY)


def parse_lrc(text):
    """Returns a sorted list of (milliseconds, line)."""
    lines = []
    for raw in (text or '').splitlines():
        stamps = list(_LRC_STAMP.finditer(raw))
        if not stamps:
            continue
        content = raw[stamps[-1].end():].strip()
        for s in stamps:
            ms = int((int(s.group(1)) * 60 + float(s.group(2))) * 1000)
            lines.append((ms, content))
    lines.sort(key=lambda l: l[0])
    return lines


def format_lrc(lines, offset=0):
    out = []
    for ms, content in lines:
        ms = max(0, ms + offset)
        out.append(f'[{ms // 60000:02d}:{(ms % 60000) / 1000:05.2f}]{content}')
    return '\n'.join(out)


def _pick(results, duration):
    best = None
    for r in results or []:
        if r.get('instrumental'):
            continue
        if not (r.get('syncedLyrics') or r.get('plainLyrics')):
            continue
        diff = abs((r.get('duration') or 0) - duration) if duration else 0
        if duration and diff > DURATION_TOLERANCE:
            continue
        # synced lyrics first, then closest duration
        key = (0 if r.get('syncedLyrics') else 1, diff)
        if best is None or key < best[0]:
            best = (key, r)
    return best[1] if best else None


_ANY_BRACKETS = re.compile(r'[\(\[\{\u3010\u300c\u300e\uff08][^\)\]\}\u3011\u300d\u300f\uff09]*[\)\]\}\u3011\u300d\u300f\uff09]')
_NOISE = re.compile(
    r'\b(official|music video|video|lyrics?|lyric video|video clip|audio|visuali[sz]er|hd|4k|mv|remaster(?:ed)?|karaoke)\b'
    r'|\u0643\u0644\u0645\u0627\u062a|\u0641\u064a\u062f\u064a\u0648 \u0643\u0644\u064a\u0628|\u0627\u0644\u0645\u0648\u0633\u064a\u0642\u064a|\u0627\u0644\u0631\u0633\u0645\u064a'
    r'|#\S+', re.IGNORECASE)
_FEAT = re.compile(r'\s*[\(\[]?\s*(?:feat\.?|ft\.?|featuring)\s+[^\)\]]*[\)\]]?', re.IGNORECASE)
_SEPARATORS = (' - ', ' \u2013 ', ' \u2014 ', ' | ', ' : ', ' \u2022 ')
DURATION_STEPS = (3, 6, 12, 25)


def _clean(text):
    text = _FEAT.sub('', text or '')
    text = _JUNK_BRACKETS.sub('', text)
    text = re.sub(r'[\u3010\u3011\u300c\u300d\u300e\u300f]', ' ', text)
    text = _NOISE.sub(' ', text)
    text = re.sub(r'[\(\[\{]\s*[\)\]\}]', ' ', text)
    return re.sub(r'\s+', ' ', text).strip(' -\u2013\u2014|:\u2022"\'')


def _variants(track, artist):
    """Candidate (track, artist) pairs, most specific first. Works for any script."""
    out = []

    def add(t, a):
        t, a = _clean(t), (a or '').strip()
        if t and (t, a) not in out:
            out.append((t, a))

    base = track or ''
    add(base, artist)
    for sep in _SEPARATORS:
        if sep in base:
            left, right = base.split(sep, 1)
            add(right, left)
            add(left, right)
            add(right, artist)
            break
    no_brackets = _ANY_BRACKETS.sub(' ', base)
    add(no_brackets, artist)
    for sep in _SEPARATORS:
        if sep in no_brackets:
            left, right = no_brackets.split(sep, 1)
            add(right, left)
            break
    add(base, '')
    return out


def _pick_loose(results, duration):
    """Widen the duration window step by step instead of failing at 5 s."""
    for tol in DURATION_STEPS:
        best = None
        for r in results or []:
            if r.get('instrumental') or not (r.get('syncedLyrics') or r.get('plainLyrics')):
                continue
            diff = abs((r.get('duration') or 0) - duration) if duration else 0
            if duration and diff > tol:
                continue
            key = (0 if r.get('syncedLyrics') else 1, diff)
            if best is None or key < best[0]:
                best = (key, r)
        if best:
            return best[1]
    return None


def find_lyrics(track, artist, album, duration, lrclib_id=None):
    if lrclib_id:
        return _get_json(f'get/{lrclib_id}')

    variants = _variants(track, artist)
    # exact signature lookup first (cheap and precise)
    for t, a in variants[:3]:
        if a:
            res = _get_json('get', {'track_name': t, 'artist_name': a,
                                    'album_name': album, 'duration': duration or None})
            if res and (res.get('syncedLyrics') or res.get('plainLyrics')) and not res.get('instrumental'):
                return res
    # then searches, with fuzzy duration
    for t, a in variants:
        for params in ({'track_name': t, 'artist_name': a} if a else {'track_name': t},
                       {'q': f'{a} {t}'.strip()}):
            res = _pick_loose(_get_json('search', params), duration)
            if res:
                return res
    return None


def embed(path, text, synced):
    ext = os.path.splitext(path)[1].lower().lstrip('.')

    if ext == 'mp3':
        from mutagen.id3 import ID3, ID3NoHeaderError, SYLT, USLT
        try:
            tags = ID3(path)
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall('USLT')
        tags.delall('SYLT')
        tags.add(USLT(encoding=3, lang='eng', desc='', text=text))
        if synced:
            tags.add(SYLT(encoding=3, lang='eng', format=2, type=1, desc='',
                          text=[(content, ms) for ms, content in synced]))
        tags.save(path)
    elif ext in ('m4a', 'mp4'):
        from mutagen.mp4 import MP4
        audio = MP4(path)
        if audio.tags is None:
            audio.add_tags()
        audio.tags['\xa9lyr'] = [text]
        audio.save()
    elif ext in ('opus', 'ogg', 'oga', 'flac'):
        import mutagen
        audio = mutagen.File(path)
        if audio is None:
            raise ValueError('unsupported file')
        audio['lyrics'] = [text]
        audio.save()
    else:
        raise ValueError(f'embedding lyrics is not supported for .{ext} files')


class LyricsPP(PostProcessor):
    """Fetches lyrics from lrclib.net and embeds them into the audio file.

    Raises PostProcessingError when the lyrics can't be fetched or aren't found,
    which fails the download.

    Must run with when=after_move: post_process plugins run before audio
    extraction, so the file would still be the original webm/m4a stream.

    Args (all optional):
      id       lrclib track id, skips matching when set
      offset   milliseconds added to every synced timestamp
      embed    1/0 write the lyrics into the file tags
      lrc      1/0 save a .lrc file next to the audio file when synced lyrics exist
    """

    def __init__(self, downloader=None, id=None, offset=0, embed=1, lrc=0, **kwargs):
        super().__init__(downloader)
        self._id = int(id) if id and str(id).isdigit() else None
        self._offset = int(offset or 0)
        self._embed = str(embed) != '0'
        self._lrc = str(lrc) == '1'

    @staticmethod
    def _artist(info):
        artist = info.get('artist') or ''
        if not artist and info.get('artists'):
            artist = info['artists'][0]
        artist = artist or info.get('uploader') or info.get('channel') or ''
        artist = re.sub(r'\s*-\s*Topic$', '', artist)
        return artist.split(',')[0].strip()

    def run(self, info):
        path = info.get('filepath')
        if not path or not os.path.exists(path):
            return [], info

        track = info.get('track') or info.get('title') or ''
        _deadline[0] = time.time() + TIME_BUDGET
        try:
            result = find_lyrics(track, self._artist(info), info.get('album') or '',
                                 int(info.get('duration') or 0), self._id)
        except Exception as e:
            # lyrics are optional: never fail a finished download because of them
            self.report_warning(f'Could not fetch lyrics: {e}')
            result = None

        if not result or result.get('instrumental'):
            self.to_screen('No lyrics found')
            return [], info

        synced = parse_lrc(result.get('syncedLyrics'))
        shifted = [(max(0, ms + self._offset), c) for ms, c in synced]
        plain = result.get('plainLyrics') or '\n'.join(c for _, c in synced)
        # Only mp3 has a real synced tag (SYLT). Everywhere else players expect
        # LRC text, with timestamps, inside the regular lyrics tag.
        text = format_lrc(synced, self._offset) if synced else plain

        if self._embed:
            try:
                embed(path, text, shifted)
                self.to_screen('Embedded lyrics' + (' (synced, LRC)' if shifted else ' (plain)'))
            except Exception as e:
                self.report_warning(f'Could not embed lyrics: {e}')

        if self._lrc and shifted:
            lrc_path = os.path.splitext(path)[0] + '.lrc'
            try:
                with open(lrc_path, 'w', encoding='utf-8') as f:
                    f.write(format_lrc(synced, self._offset))
                self.to_screen(f'Saved {os.path.basename(lrc_path)}')
            except OSError as e:
                self.report_warning(f'Could not save lyrics file: {e}')

        return [], info

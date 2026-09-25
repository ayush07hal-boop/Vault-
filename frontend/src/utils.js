import { useCallback, useEffect, useRef, useState } from 'react';

export function formatBytes(n) {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${units[i]}`;
}

export function timeAgo(iso) {
  if (!iso) return '—';
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 5) return 'just now';
  if (s < 60) return `${Math.floor(s)}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return new Date(iso).toLocaleDateString();
}

export const shortId = (id) => (id ? id.replace(/^obj-/, '').slice(0, 8) : '');

/** Runs `fn` now and every `ms`; returns {data, error, loading, reload}. Pauses while the tab is hidden. */
export function usePolling(fn, ms = 5000, deps = []) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const fnRef = useRef(fn);
  fnRef.current = fn;

  const load = useCallback(async () => {
    try {
      const data = await fnRef.current();
      setState({ data, error: null, loading: false });
    } catch (error) {
      setState((s) => ({ ...s, error, loading: false }));
    }
  }, []);

  useEffect(() => {
    setState((s) => ({ ...s, loading: true }));
    load();
    if (!ms) return undefined;
    const t = setInterval(() => !document.hidden && load(), ms);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, load, ...deps]);

  return { ...state, reload: load };
}

export function useDebounced(value, ms = 300) {
  const [v, setV] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t);
  }, [value, ms]);
  return v;
}

/** Drive-style size text: "1.2 GB", "398.9 MB", "89.16 GB" (two decimals from GB up). */
export function formatSize(n) {
  if (n == null) return '—';
  if (n < 1024) return `${n} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = n / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  return `${v.toFixed(i >= 2 ? 2 : 1)} ${units[i]}`;
}

/** "Jan 5, 2026" for older dates, "10:42 AM" for today. */
export function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' });
  return d.toLocaleDateString([], { month: 'short', day: 'numeric', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' });
}

/** "Modified" filter options -> {modifiedAfter, modifiedBefore} in the user's local time zone. */
export const MODIFIED_OPTIONS = [
  ['today', 'Today'],
  ['7d', 'Last 7 days'],
  ['30d', 'Last 30 days'],
  ['year', `This year (${new Date().getFullYear()})`],
  ['lastyear', `Last year (${new Date().getFullYear() - 1})`],
];

export function modifiedRange(key) {
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const daysAgo = (n) => new Date(startOfToday.getTime() - n * 86400000);
  switch (key) {
    case 'today': return { modifiedAfter: startOfToday.toISOString() };
    case '7d': return { modifiedAfter: daysAgo(7).toISOString() };
    case '30d': return { modifiedAfter: daysAgo(30).toISOString() };
    case 'year': return { modifiedAfter: new Date(now.getFullYear(), 0, 1).toISOString() };
    case 'lastyear':
      return { modifiedAfter: new Date(now.getFullYear() - 1, 0, 1).toISOString(), modifiedBefore: new Date(now.getFullYear(), 0, 1).toISOString() };
    default: return {};
  }
}

/** "Type" filter options (keys match the backend's file categories). */
export const TYPE_OPTIONS = [
  ['pdf', 'PDFs'],
  ['document', 'Documents'],
  ['spreadsheet', 'Spreadsheets'],
  ['presentation', 'Presentations'],
  ['image', 'Photos & images'],
  ['video', 'Videos'],
  ['audio', 'Audio'],
  ['archive', 'Archives (zips)'],
  ['other', 'Other'],
];

/** Storage-bar groups, like Drive's Drive/Photos/Gmail/Other split, but by kind of file. */
export const STORAGE_GROUPS = [
  { key: 'documents', label: 'Documents', color: 'var(--type-document)', types: ['pdf', 'document', 'spreadsheet', 'presentation'] },
  { key: 'images', label: 'Photos & images', color: 'var(--type-image)', types: ['image'] },
  { key: 'videos', label: 'Videos', color: 'var(--type-video)', types: ['video'] },
  { key: 'audio', label: 'Audio', color: 'var(--type-audio)', types: ['audio'] },
  { key: 'other', label: 'Other', color: 'var(--type-other)', types: ['archive', 'other'] },
];

const EXT_CATEGORY = {
  pdf: 'pdf', doc: 'document', docx: 'document', txt: 'document', rtf: 'document', odt: 'document', md: 'document',
  xls: 'spreadsheet', xlsx: 'spreadsheet', csv: 'spreadsheet', ods: 'spreadsheet',
  ppt: 'presentation', pptx: 'presentation', odp: 'presentation', key: 'presentation',
  jpg: 'image', jpeg: 'image', png: 'image', gif: 'image', webp: 'image', bmp: 'image', svg: 'image', heic: 'image', tif: 'image', tiff: 'image',
  mp4: 'video', mov: 'video', avi: 'video', mkv: 'video', webm: 'video', wmv: 'video', flv: 'video', m4v: 'video',
  mp3: 'audio', wav: 'audio', flac: 'audio', aac: 'audio', ogg: 'audio', m4a: 'audio',
  zip: 'archive', rar: 'archive', '7z': 'archive', tar: 'archive', gz: 'archive',
};

/** Same categories as the backend's FileTypes, for files that are not uploaded yet. */
export function categoryOfName(name) {
  const dot = (name || '').lastIndexOf('.');
  return EXT_CATEGORY[dot < 0 ? '' : name.slice(dot + 1).toLowerCase()] || 'other';
}

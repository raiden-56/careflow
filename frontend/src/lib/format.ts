import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';
import relativeTime from 'dayjs/plugin/relativeTime';
import customParseFormat from 'dayjs/plugin/customParseFormat';

dayjs.extend(utc);
dayjs.extend(timezone);
dayjs.extend(relativeTime);
dayjs.extend(customParseFormat);

/** Hospital timezone (set from the signed-in user's organization). Timestamps are stored in UTC. */
let hospitalTz = 'Asia/Kolkata';

export function setHospitalTimeZone(tz: string | undefined) {
  if (tz) hospitalTz = tz;
}

export function hospitalTimeZone() {
  return hospitalTz;
}

/** dayjs instance in hospital time for an ISO instant. */
export function inHospitalTz(iso: string | Date) {
  return dayjs(iso).tz(hospitalTz);
}

export function nowInHospitalTz() {
  return dayjs().tz(hospitalTz);
}

const inrFormatter = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2 });
const inrCompact = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 1 });

/**
 * Formats a decimal string from the API as INR. Display only: never do arithmetic on money in
 * the browser - totals and balances are calculated by the backend.
 */
export function formatINR(value: string | number | null | undefined, opts?: { compact?: boolean }): string {
  if (value === null || value === undefined || value === '') return '—';
  const n = typeof value === 'number' ? value : Number(value);
  if (Number.isNaN(n)) return String(value);
  return opts?.compact ? inrCompact.format(n) : inrFormatter.format(n);
}

export function isNegative(value: string | null | undefined) {
  return !!value && value.trim().startsWith('-');
}

export function formatDate(iso?: string | null, fmt = 'DD MMM YYYY'): string {
  if (!iso) return '—';
  // Plain dates (yyyy-MM-dd) are calendar dates, not instants: format without timezone shift.
  if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) return dayjs(iso, 'YYYY-MM-DD').format(fmt);
  return inHospitalTz(iso).format(fmt);
}

export function formatDateTime(iso?: string | null, fmt = 'DD MMM YYYY, HH:mm'): string {
  if (!iso) return '—';
  return inHospitalTz(iso).format(fmt);
}

export function formatTime(iso?: string | null): string {
  if (!iso) return '—';
  return inHospitalTz(iso).format('HH:mm');
}

export function fromNow(iso?: string | null): string {
  if (!iso) return '—';
  return dayjs(iso).fromNow();
}

/** Converts a hospital-local "YYYY-MM-DDTHH:mm" (datetime-local input) to a UTC ISO string. */
export function localInputToIso(value: string): string {
  return dayjs.tz(value, 'YYYY-MM-DDTHH:mm', hospitalTz).toISOString();
}

/** UTC ISO -> hospital-local "YYYY-MM-DDTHH:mm" for datetime-local inputs. */
export function isoToLocalInput(iso?: string | null): string {
  return (iso ? inHospitalTz(iso) : nowInHospitalTz()).format('YYYY-MM-DDTHH:mm');
}

export function todayLocalDate(): string {
  return nowInHospitalTz().format('YYYY-MM-DD');
}

export function initials(name?: string | null): string {
  if (!name) return '?';
  const parts = name.replace(/^Dr\.?\s+/i, '').split(/\s+/).filter(Boolean);
  return (parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '');
}

const ACRONYMS = new Set(['icu', 'hdu', 'mrn', 'iv', 'im', 'sc', 'ecg', 'cbc', 'lft', 'rft', 'crp', 'upi', 'prn', 'stat', 'od', 'bd', 'tds', 'qid', 'hs', 'lama', 'abdm', 'fhir', 'lis', 'tpa', 'sms', 'opd', 'ipd', 'or']);

export function humanize(value?: string | null): string {
  if (!value) return '—';
  return value
    .toLowerCase()
    .split('_')
    .map((w, i) => (ACRONYMS.has(w) ? w.toUpperCase() : i === 0 ? w.charAt(0).toUpperCase() + w.slice(1) : w))
    .join(' ');
}

export function pluralize(n: number, word: string, plural?: string) {
  return `${n} ${n === 1 ? word : plural ?? word + 's'}`;
}

export function genderShort(g?: string | null) {
  return g === 'MALE' ? 'M' : g === 'FEMALE' ? 'F' : g === 'OTHER' ? 'O' : '—';
}

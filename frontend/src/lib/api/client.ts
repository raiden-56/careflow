/**
 * Typed fetch wrapper for the CareFlow API.
 * - Same-origin requests (Next.js rewrites /api to the backend), httpOnly cookies carry the session.
 * - Sends the CSRF client header on every request.
 * - On 401 it transparently refreshes the session once (single-flight) and retries.
 * - Errors are thrown as ApiError with the backend's consistent error body.
 */

export interface FieldError {
  field: string;
  message: string;
}

export interface ApiErrorBody {
  timestamp?: string;
  status: number;
  code: string;
  message: string;
  path?: string;
  traceId?: string;
  fieldErrors?: FieldError[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: FieldError[];
  readonly traceId?: string;

  constructor(body: ApiErrorBody) {
    super(body.message || 'Request failed');
    this.name = 'ApiError';
    this.status = body.status;
    this.code = body.code;
    this.fieldErrors = body.fieldErrors ?? [];
    this.traceId = body.traceId;
  }
}

export type QueryValue = string | number | boolean | null | undefined | Array<string | number>;
export type Query = Record<string, QueryValue>;

export function buildQuery(query?: Query): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      value.forEach((v) => params.append(key, String(v)));
    } else {
      params.append(key, String(value));
    }
  }
  const s = params.toString();
  return s ? `?${s}` : '';
}

const NO_REFRESH_PATHS = ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'];
let refreshing: Promise<boolean> | null = null;

async function refreshSession(): Promise<boolean> {
  if (!refreshing) {
    refreshing = fetch('/api/auth/refresh', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'X-CareFlow-Client': 'web' },
    })
      .then((r) => r.ok)
      .catch(() => false)
      .finally(() => {
        setTimeout(() => {
          refreshing = null;
        }, 0);
      });
  }
  return refreshing;
}

/** Pages that work without a session (no redirect on 401). Keep in sync with src/middleware.ts. */
export const PUBLIC_PATHS = [
  '/login',
  '/forgot-password',
  '/reset-password',
  '/account-recovery',
  '/unauthorized',
  '/session-expired',
  '/dev-mailbox',
];

export function isPublicPath(pathname: string) {
  return PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(p + '/'));
}

function onSessionExpired() {
  if (typeof window === 'undefined') return;
  const here = window.location.pathname + window.location.search;
  if (!isPublicPath(window.location.pathname)) {
    window.location.assign(`/session-expired?next=${encodeURIComponent(here)}`);
  }
}

interface RequestOptions {
  body?: unknown;
  query?: Query;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  /** Return the raw Response (e.g. for file downloads). */
  raw?: boolean;
}

async function request<T>(method: string, path: string, opts: RequestOptions = {}, retried = false): Promise<T> {
  const url = path + buildQuery(opts.query);
  const isForm = typeof FormData !== 'undefined' && opts.body instanceof FormData;
  const headers: Record<string, string> = {
    'X-CareFlow-Client': 'web',
    Accept: 'application/json',
    ...(opts.body !== undefined && !isForm ? { 'Content-Type': 'application/json' } : {}),
    ...opts.headers,
  };
  let res: Response;
  try {
    res = await fetch(url, {
      method,
      credentials: 'same-origin',
      headers,
      signal: opts.signal,
      body: opts.body === undefined ? undefined : isForm ? (opts.body as FormData) : JSON.stringify(opts.body),
    });
  } catch (e) {
    if ((e as Error).name === 'AbortError') throw e;
    throw new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'Unable to reach the server. Check your connection.' });
  }

  if (res.status === 401 && !retried && !NO_REFRESH_PATHS.some((p) => path.startsWith(p))) {
    const ok = await refreshSession();
    if (ok) return request<T>(method, path, opts, true);
    onSessionExpired();
  }

  if (!res.ok) {
    let body: ApiErrorBody;
    try {
      body = (await res.json()) as ApiErrorBody;
    } catch {
      body = { status: res.status, code: 'HTTP_' + res.status, message: res.statusText || 'Request failed' };
    }
    throw new ApiError({ ...body, status: body.status ?? res.status });
  }
  if (opts.raw) return res as unknown as T;
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal }),
  post: <T>(path: string, body?: unknown, query?: Query) => request<T>('POST', path, { body: body ?? {}, query }),
  put: <T>(path: string, body?: unknown) => request<T>('PUT', path, { body: body ?? {} }),
  patch: <T>(path: string, body?: unknown) => request<T>('PATCH', path, { body: body ?? {} }),
  del: <T>(path: string, body?: unknown) => request<T>('DELETE', path, { body }),
  upload: <T>(path: string, form: FormData) => request<T>('POST', path, { body: form }),
  /** Downloads a file (PDF/CSV) through the authenticated session and saves it. */
  async download(path: string, fallbackName: string, query?: Query): Promise<void> {
    const res = await request<Response>('GET', path, { query, raw: true, headers: { Accept: '*/*' } });
    const blob = await res.blob();
    const disposition = res.headers.get('Content-Disposition') || '';
    const match = /filename="?([^";]+)"?/i.exec(disposition);
    const name = match ? match[1] : fallbackName;
    const href = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = href;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(href), 1000);
  },
};

/** A unique key for retry-safe (idempotent) operations such as payments. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** Human-readable message for any thrown error. */
export function errorMessage(e: unknown): string {
  if (e instanceof ApiError) return e.message;
  if (e instanceof Error) return e.message;
  return 'Something went wrong.';
}

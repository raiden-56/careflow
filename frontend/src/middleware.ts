import { NextRequest, NextResponse } from 'next/server';

/**
 * Redirects signed-out visitors to /login before rendering protected pages. This is a UX
 * convenience only - every API call is authorized by the backend regardless of routing.
 */
const PUBLIC_PREFIXES = [
  '/login',
  '/forgot-password',
  '/reset-password',
  '/account-recovery',
  '/unauthorized',
  '/session-expired',
  '/dev-mailbox',
];

export function middleware(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  if (PUBLIC_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + '/'))) {
    return NextResponse.next();
  }
  const hasSession = req.cookies.has('cf_sess') || req.cookies.has('cf_at');
  if (!hasSession) {
    const url = req.nextUrl.clone();
    url.pathname = '/login';
    url.search = pathname === '/' ? '' : `?next=${encodeURIComponent(pathname + search)}`;
    return NextResponse.redirect(url);
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/((?!api|_next/static|_next/image|favicon.ico|icon.svg|robots.txt).*)'],
};

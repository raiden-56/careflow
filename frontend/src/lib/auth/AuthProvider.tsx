'use client';

import { createContext, ReactNode, useCallback, useContext, useEffect, useMemo } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { usePathname, useRouter } from 'next/navigation';
import { authApi, qk } from '@/lib/api/core';
import type { Me, Role } from '@/lib/api/types';
import { ApiError, isPublicPath } from '@/lib/api/client';
import { setHospitalTimeZone } from '@/lib/format';

interface AuthContextValue {
  me: Me | undefined;
  isLoading: boolean;
  isAuthenticated: boolean;
  error: unknown;
  hasPermission: (permission: string) => boolean;
  hasAnyPermission: (...permissions: string[]) => boolean;
  hasRole: (...roles: Role[]) => boolean;
  refresh: () => Promise<unknown>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const pathname = usePathname();
  const meQuery = useQuery({
    queryKey: qk.me,
    queryFn: authApi.me,
    retry: false,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
    enabled: !isPublicPath(pathname ?? '/'),
  });

  useEffect(() => {
    if (meQuery.data) setHospitalTimeZone(meQuery.data.organization.timezone);
  }, [meQuery.data]);

  const logout = useCallback(async () => {
    try {
      await authApi.logout();
    } catch {
      // Signing out locally is still correct even if the server call fails.
    }
    queryClient.clear();
    router.replace('/login');
  }, [queryClient, router]);

  const value = useMemo<AuthContextValue>(() => {
    const perms = new Set(meQuery.data?.permissions ?? []);
    const unauthenticated = meQuery.error instanceof ApiError && meQuery.error.status === 401;
    return {
      me: meQuery.data,
      isLoading: meQuery.isLoading,
      isAuthenticated: !!meQuery.data && !unauthenticated,
      error: meQuery.error,
      hasPermission: (p) => perms.has(p),
      hasAnyPermission: (...ps) => ps.some((p) => perms.has(p)),
      hasRole: (...roles) => !!meQuery.data && roles.includes(meQuery.data.role),
      refresh: () => meQuery.refetch(),
      logout,
    };
  }, [meQuery, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

/** The signed-in user; only use inside the authenticated app shell. */
export function useMe(): Me {
  const { me } = useAuth();
  if (!me) throw new Error('useMe used outside an authenticated page');
  return me;
}

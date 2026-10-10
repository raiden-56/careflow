import { AppShell } from '@/components/layout/AppShell';

/** All authenticated pages share the shell (sidebar, top bar, notifications, role guard). */
export default function AuthenticatedLayout({ children }: { children: React.ReactNode }) {
  return <AppShell>{children}</AppShell>;
}

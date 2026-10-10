'use client';

import { PortalPatientProvider } from '@/lib/portal';

export default function PatientPortalLayout({ children }: { children: React.ReactNode }) {
  return <PortalPatientProvider>{children}</PortalPatientProvider>;
}

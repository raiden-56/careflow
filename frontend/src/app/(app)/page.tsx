'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/AuthProvider';
import { ROLE_HOME } from '@/lib/nav';
import { PageSkeleton } from '@/components/ui/States';

/** "/" sends each role to its own dashboard. */
export default function Home() {
  const { me } = useAuth();
  const router = useRouter();
  useEffect(() => {
    if (me) router.replace(ROLE_HOME[me.role]);
  }, [me, router]);
  return <PageSkeleton />;
}

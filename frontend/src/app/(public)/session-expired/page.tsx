'use client';

import { Suspense, useEffect } from 'react';
import Link from 'next/link';
import { useSearchParams } from 'next/navigation';
import { useQueryClient } from '@tanstack/react-query';
import { Box, Button, Stack, Typography } from '@mui/material';
import { TimerOff } from 'lucide-react';
import { colors } from '@/theme/tokens';

function SessionExpired() {
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const next = params.get('next');
  useEffect(() => {
    // Drop any cached patient data from the expired session.
    queryClient.clear();
  }, [queryClient]);
  return (
    <Stack spacing={4} alignItems="flex-start">
      <Box sx={{ width: 48, height: 48, borderRadius: 999, bgcolor: colors.warningSoft, color: colors.warning, display: 'grid', placeItems: 'center' }}>
        <TimerOff size={24} />
      </Box>
      <Typography variant="h2" component="h1">
        Your session has expired
      </Typography>
      <Typography variant="body2" color="text.secondary">
        For patient privacy, sessions end after a period of inactivity. Sign in again to continue where you left off.
      </Typography>
      <Button component={Link} href={next ? `/login?next=${encodeURIComponent(next)}` : '/login'} variant="contained">
        Sign in again
      </Button>
    </Stack>
  );
}

export default function SessionExpiredPage() {
  return (
    <Suspense>
      <SessionExpired />
    </Suspense>
  );
}

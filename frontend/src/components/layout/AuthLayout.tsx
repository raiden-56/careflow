'use client';

import { ReactNode } from 'react';
import { Box, Card, Stack, Typography } from '@mui/material';
import { HeartPulse, ShieldCheck } from 'lucide-react';
import { colors } from '@/theme/tokens';

/** Split layout for sign-in and account pages. */
export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <Box sx={{ minHeight: '100vh', display: 'grid', gridTemplateColumns: { xs: '1fr', md: '1.05fr 1fr' } }}>
      <Box
        sx={{
          display: { xs: 'none', md: 'flex' },
          flexDirection: 'column',
          justifyContent: 'space-between',
          p: 12,
          color: '#fff',
          background: `linear-gradient(160deg, ${colors.primaryDark} 0%, #0d4f4a 55%, ${colors.ink} 100%)`,
        }}
      >
        <Stack direction="row" spacing={3} alignItems="center">
          <Box sx={{ width: 40, height: 40, borderRadius: 3, bgcolor: 'rgba(255,255,255,0.14)', display: 'grid', placeItems: 'center' }}>
            <HeartPulse size={22} />
          </Box>
          <Typography sx={{ fontWeight: 700, fontSize: '1.25rem' }}>CareFlow HMS</Typography>
        </Stack>
        <Box sx={{ maxWidth: 460 }}>
          <Typography variant="h1" component="p" sx={{ color: '#fff', mb: 4 }}>
            Every admission, coordinated from bedside to discharge.
          </Typography>
          <Typography sx={{ color: 'rgba(255,255,255,0.78)' }}>
            Medication rounds, clinical review, transparent billing and discharge readiness — one shared, role-aware record for
            the whole care team and the patient.
          </Typography>
        </Box>
        <Stack direction="row" spacing={2} alignItems="center" sx={{ color: 'rgba(255,255,255,0.7)' }}>
          <ShieldCheck size={16} />
          <Typography variant="caption">Access is role-based and every sensitive action is audited.</Typography>
        </Stack>
      </Box>
      <Box sx={{ display: 'grid', placeItems: 'center', p: { xs: 4, sm: 8 }, bgcolor: 'background.default' }}>
        <Card sx={{ width: '100%', maxWidth: 440, p: { xs: 6, sm: 8 } }}>{children}</Card>
      </Box>
    </Box>
  );
}

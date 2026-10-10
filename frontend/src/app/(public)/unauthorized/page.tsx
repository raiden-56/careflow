'use client';

import Link from 'next/link';
import { Box, Button, Stack, Typography } from '@mui/material';
import { ShieldX } from 'lucide-react';
import { colors } from '@/theme/tokens';

export default function UnauthorizedPage() {
  return (
    <Stack spacing={4} alignItems="flex-start">
      <Box sx={{ width: 48, height: 48, borderRadius: 999, bgcolor: colors.dangerSoft, color: colors.danger, display: 'grid', placeItems: 'center' }}>
        <ShieldX size={24} />
      </Box>
      <Typography variant="h2" component="h1">
        Access not permitted
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Your role does not have access to that page or record. Access to patient information is limited to the people involved in
        their care, and attempts are recorded in the audit log.
      </Typography>
      <Stack direction="row" spacing={2}>
        <Button component={Link} href="/" variant="contained">
          Go to my dashboard
        </Button>
        <Button component={Link} href="/login" color="inherit">
          Switch account
        </Button>
      </Stack>
    </Stack>
  );
}

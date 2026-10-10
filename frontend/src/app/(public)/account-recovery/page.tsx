'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Stack, Typography } from '@mui/material';
import { authApi } from '@/lib/api/core';
import { errorMessage } from '@/lib/api/client';
import { RHFTextField } from '@/components/forms/Fields';

const schema = z.object({
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address'),
  reason: z.string().max(500, 'Keep it under 500 characters').optional(),
});
type Values = z.infer<typeof schema>;

export default function AccountRecoveryPage() {
  const [done, setDone] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, formState } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '', reason: '' } });
  return (
    <Stack spacing={4}>
      <Typography variant="h2" component="h1">
        Secure account recovery
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Locked out after too many attempts, or lost access? We&apos;ll send a one-time recovery link (valid for 60 minutes) to the
        email address registered on your account. Using it unlocks the account and lets you set a new password. All other sessions
        are signed out.
      </Typography>
      <Box sx={{ bgcolor: 'background.default', p: 3, borderRadius: 2 }}>
        <Typography variant="caption" color="text.secondary">
          If you no longer have access to your registered email, contact the hospital help desk with photo ID. Staff accounts can
          also be unlocked by your hospital administrator.
        </Typography>
      </Box>
      {done ? (
        <Alert severity="success">{done}</Alert>
      ) : (
        <form
          noValidate
          onSubmit={handleSubmit(async (v) => {
            setError(null);
            try {
              const r = await authApi.accountRecovery(v.email, v.reason);
              setDone(r.message);
            } catch (e) {
              setError(errorMessage(e));
            }
          })}
        >
          <Stack spacing={4}>
            {error && <Alert severity="error">{error}</Alert>}
            <RHFTextField control={control} name="email" label="Registered email" type="email" autoFocus />
            <RHFTextField control={control} name="reason" label="What happened? (optional)" multiline minRows={2} />
            <Button type="submit" variant="contained" size="large" disabled={formState.isSubmitting}>
              Send recovery link
            </Button>
          </Stack>
        </form>
      )}
      <Typography component={Link} href="/login" variant="body2" color="primary" sx={{ textDecoration: 'none' }}>
        ← Back to sign in
      </Typography>
    </Stack>
  );
}

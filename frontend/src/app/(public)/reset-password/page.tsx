'use client';

import { Suspense, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, LinearProgress, Stack, Typography } from '@mui/material';
import { authApi } from '@/lib/api/core';
import { errorMessage } from '@/lib/api/client';
import { RHFTextField } from '@/components/forms/Fields';
import { passwordSchema } from '@/lib/validation';

const schema = z
  .object({ password: passwordSchema, confirm: z.string() })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], message: 'Passwords do not match' });

function ResetForm() {
  const params = useSearchParams();
  const router = useRouter();
  const token = params.get('token') ?? '';
  const [error, setError] = useState<string | null>(null);
  const valid = useQuery({
    queryKey: ['reset-token', token],
    queryFn: () => authApi.validateResetToken(token),
    enabled: !!token,
    retry: false,
  });
  const { control, handleSubmit, formState } = useForm<{ password: string; confirm: string }>({
    resolver: zodResolver(schema),
    defaultValues: { password: '', confirm: '' },
  });

  if (!token || valid.data?.valid === false) {
    return (
      <Stack spacing={4}>
        <Typography variant="h2" component="h1">
          Link expired
        </Typography>
        <Alert severity="warning">This password link is invalid, already used or expired.</Alert>
        <Button component={Link} href="/forgot-password" variant="contained">
          Request a new link
        </Button>
      </Stack>
    );
  }
  if (valid.isLoading) return <LinearProgress aria-label="Checking link" />;

  return (
    <Stack spacing={4}>
      <Typography variant="h2" component="h1">
        Set a new password
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Use at least 10 characters with upper- and lower-case letters and a digit. Other active sessions will be signed out.
      </Typography>
      {error && <Alert severity="error">{error}</Alert>}
      <form
        noValidate
        onSubmit={handleSubmit(async ({ password }) => {
          setError(null);
          try {
            await authApi.resetPassword(token, password);
            router.replace('/login?reset=1');
          } catch (e) {
            setError(errorMessage(e));
          }
        })}
      >
        <Stack spacing={4}>
          <RHFTextField control={control} name="password" label="New password" type="password" autoComplete="new-password" autoFocus />
          <RHFTextField control={control} name="confirm" label="Confirm new password" type="password" autoComplete="new-password" />
          <Button type="submit" variant="contained" size="large" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? 'Saving…' : 'Save password'}
          </Button>
        </Stack>
      </form>
    </Stack>
  );
}

export default function ResetPasswordPage() {
  return (
    <Suspense>
      <ResetForm />
    </Suspense>
  );
}

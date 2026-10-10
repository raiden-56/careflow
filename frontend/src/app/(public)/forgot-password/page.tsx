'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Stack, Typography } from '@mui/material';
import { authApi, qk } from '@/lib/api/core';
import { errorMessage } from '@/lib/api/client';
import { RHFTextField } from '@/components/forms/Fields';

const schema = z.object({ email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address') });

export default function ForgotPasswordPage() {
  const [sent, setSent] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const config = useQuery({ queryKey: qk.publicConfig, queryFn: authApi.publicConfig, staleTime: Infinity });
  const { control, handleSubmit, formState } = useForm<{ email: string }>({ resolver: zodResolver(schema), defaultValues: { email: '' } });

  return (
    <Stack spacing={4}>
      <Typography variant="h2" component="h1">
        Reset your password
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Enter the email address on your account. If it matches an account, we&apos;ll send a link that is valid for 30 minutes.
      </Typography>
      {sent ? (
        <Alert severity="success">{sent}</Alert>
      ) : (
        <form
          noValidate
          onSubmit={handleSubmit(async ({ email }) => {
            setError(null);
            try {
              const r = await authApi.forgotPassword(email);
              setSent(r.message);
            } catch (e) {
              setError(errorMessage(e));
            }
          })}
        >
          <Stack spacing={4}>
            {error && <Alert severity="error">{error}</Alert>}
            <RHFTextField control={control} name="email" label="Email" type="email" autoComplete="email" autoFocus />
            <Button type="submit" variant="contained" size="large" disabled={formState.isSubmitting}>
              {formState.isSubmitting ? 'Sending…' : 'Send reset link'}
            </Button>
          </Stack>
        </form>
      )}
      {config.data?.devOutboxEnabled && (
        <Alert severity="info">
          Development mode: emails are not sent. Open the <Link href="/dev-mailbox">development mailbox</Link> to view the reset link.
        </Alert>
      )}
      <Typography component={Link} href="/login" variant="body2" color="primary" sx={{ textDecoration: 'none' }}>
        ← Back to sign in
      </Typography>
    </Stack>
  );
}

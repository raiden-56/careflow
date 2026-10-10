'use client';

import { Suspense, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Divider, IconButton, InputAdornment, Stack, Typography } from '@mui/material';
import { Eye, EyeOff, FlaskConical } from 'lucide-react';
import { authApi, qk } from '@/lib/api/core';
import { ApiError } from '@/lib/api/client';
import { RHFTextField } from '@/components/forms/Fields';
import { ROLE_HOME } from '@/lib/nav';

const schema = z.object({
  email: z.string().trim().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
});
type FormValues = z.infer<typeof schema>;

const DEMO_PASSWORD = 'CareFlow#Demo2026';
const DEMO_ACCOUNTS = [
  { role: 'Hospital admin', email: 'admin@careflow.local' },
  { role: 'Doctor', email: 'dr.mehta@careflow.local' },
  { role: 'Nurse', email: 'nurse.priya@careflow.local' },
  { role: 'Billing', email: 'billing@careflow.local' },
  { role: 'Pharmacist', email: 'pharmacy@careflow.local' },
  { role: 'Patient', email: 'ravi.sharma@demo.careflow.local' },
  { role: 'Caregiver', email: 'sunita.sharma@demo.careflow.local' },
  { role: 'Super admin', email: 'superadmin@careflow.local' },
];

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const queryClient = useQueryClient();
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const config = useQuery({ queryKey: qk.publicConfig, queryFn: authApi.publicConfig, staleTime: Infinity });
  const { control, handleSubmit, setValue, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { email: '', password: '' },
  });

  const onSubmit = async (values: FormValues) => {
    setError(null);
    try {
      await authApi.login(values.email, values.password);
      const me = await queryClient.fetchQuery({ queryKey: qk.me, queryFn: authApi.me, staleTime: 0 });
      const next = params.get('next');
      router.replace(next && next.startsWith('/') && !next.startsWith('//') ? next : ROLE_HOME[me.role]);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError({ status: 0, code: 'UNKNOWN', message: 'Sign-in failed.' }));
    }
  };

  return (
    <Box>
      <Typography variant="h2" component="h1">
        Sign in
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1, mb: 6 }}>
        Use your hospital account. Patients and caregivers use the email registered with the hospital.
      </Typography>
      {params.get('reset') === '1' && (
        <Alert severity="success" sx={{ mb: 4 }}>
          Your password was updated. Sign in with your new password.
        </Alert>
      )}
      {error && (
        <Alert severity="error" sx={{ mb: 4 }}>
          {error.message}
          {error.code === 'ACCOUNT_LOCKED' && (
            <Box sx={{ mt: 1 }}>
              <Link href="/account-recovery">Start secure account recovery</Link>
            </Box>
          )}
        </Alert>
      )}
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <Stack spacing={4}>
          <RHFTextField control={control} name="email" label="Email" type="email" autoComplete="username" autoFocus />
          <RHFTextField
            control={control}
            name="password"
            label="Password"
            type={showPassword ? 'text' : 'password'}
            autoComplete="current-password"
            slotProps={{
              input: {
                endAdornment: (
                  <InputAdornment position="end">
                    <IconButton
                      onClick={() => setShowPassword((s) => !s)}
                      edge="end"
                      aria-label={showPassword ? 'Hide password' : 'Show password'}
                    >
                      {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                    </IconButton>
                  </InputAdornment>
                ),
              },
            }}
          />
          <Stack direction="row" justifyContent="space-between">
            <Typography component={Link} href="/forgot-password" variant="body2" color="primary" sx={{ textDecoration: 'none' }}>
              Forgot password?
            </Typography>
            <Typography component={Link} href="/account-recovery" variant="body2" color="text.secondary" sx={{ textDecoration: 'none' }}>
              Can&apos;t access your account?
            </Typography>
          </Stack>
          <Button type="submit" variant="contained" size="large" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? 'Signing in…' : 'Sign in'}
          </Button>
        </Stack>
      </form>

      {config.data?.demoMode && (
        <Box sx={{ mt: 6 }}>
          <Divider sx={{ mb: 4 }}>
            <Chip size="small" icon={<FlaskConical size={14} />} label="Demo accounts (development only)" />
          </Divider>
          <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 2 }}>
            Fictional data. All demo accounts use the password <strong>{DEMO_PASSWORD}</strong>.
          </Typography>
          <Box sx={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 2 }}>
            {DEMO_ACCOUNTS.map((a) => (
              <Button
                key={a.email}
                size="small"
                variant="outlined"
                color="inherit"
                sx={{ justifyContent: 'flex-start', borderColor: 'divider' }}
                onClick={() => {
                  setValue('email', a.email, { shouldValidate: true });
                  setValue('password', DEMO_PASSWORD, { shouldValidate: true });
                }}
              >
                {a.role}
              </Button>
            ))}
          </Box>
        </Box>
      )}
    </Box>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}

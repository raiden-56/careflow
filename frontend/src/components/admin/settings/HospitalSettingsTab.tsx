'use client';

import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Divider,
  FormControlLabel,
  FormGroup,
  Grid,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Typography,
} from '@mui/material';
import { RotateCcw, Save } from 'lucide-react';
import { adminApi, OrganizationProfile, SettingView } from '@/lib/api/admin';
import { ApiError, errorMessage } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { fromNow, humanize } from '@/lib/format';
import { emailOptional, optionalText, phoneSchema } from '@/lib/validation';
import { SectionCard } from '@/components/ui/SectionCard';
import { CardsSkeleton, ErrorState } from '@/components/ui/States';
import { RHFTextField } from '@/components/forms/Fields';

export function HospitalSettingsTab() {
  return (
    <Stack spacing={5}>
      <ProfileCard />
      <SettingsCard />
    </Stack>
  );
}

const profileSchema = z.object({
  name: z.string().trim().min(2, 'Hospital name is required').max(200),
  legalName: optionalText(200, 'Legal name'),
  address: optionalText(500, 'Address'),
  city: optionalText(100, 'City'),
  state: optionalText(100, 'State'),
  phone: phoneSchema,
  email: emailOptional,
  registrationNumber: optionalText(100, 'Registration number'),
});
type ProfileValues = z.infer<typeof profileSchema>;

function ProfileCard() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'organization'], queryFn: adminApi.organizationProfile });
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, formState, setError: setFieldError } = useForm<ProfileValues>({ resolver: zodResolver(profileSchema) });
  useEffect(() => {
    if (q.data) reset(toProfile(q.data));
  }, [q.data, reset]);
  const m = useMutation({
    mutationFn: (v: ProfileValues) =>
      adminApi.updateOrganizationProfile({
        name: v.name,
        legalName: v.legalName || undefined,
        address: v.address || undefined,
        city: v.city || undefined,
        state: v.state || undefined,
        phone: v.phone || undefined,
        email: v.email || undefined,
        registrationNumber: v.registrationNumber || undefined,
        version: q.data!.version,
      }),
    onSuccess: (p) => {
      toast.success('Hospital profile saved');
      setError(null);
      qc.setQueryData(['settings', 'organization'], p);
      qc.invalidateQueries({ queryKey: ['me'] });
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  if (q.isLoading) return <CardsSkeleton count={1} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return (
    <SectionCard title="Hospital profile" subtitle={`Code ${q.data!.code} · time zone ${q.data!.timezone} · currency ${q.data!.currency} (set by the platform team)`}>
      <form onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
        <Grid container spacing={3}>
          {error && (
            <Grid size={12}>
              <Alert severity="error">{error}</Alert>
            </Grid>
          )}
          <Grid size={{ xs: 12, md: 6 }}>
            <RHFTextField control={control} name="name" label="Hospital name" required fullWidth />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <RHFTextField control={control} name="legalName" label="Legal name" fullWidth />
          </Grid>
          <Grid size={12}>
            <RHFTextField control={control} name="address" label="Address" fullWidth />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <RHFTextField control={control} name="city" label="City" fullWidth />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <RHFTextField control={control} name="state" label="State" fullWidth />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <RHFTextField control={control} name="phone" label="Phone" fullWidth />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, md: 3 }}>
            <RHFTextField control={control} name="email" label="Email" fullWidth />
          </Grid>
          <Grid size={{ xs: 12, md: 6 }}>
            <RHFTextField control={control} name="registrationNumber" label="Registration number" fullWidth />
          </Grid>
          <Grid size={12}>
            <Stack direction="row" justifyContent="flex-end" spacing={2}>
              <Button color="inherit" disabled={!formState.isDirty || m.isPending} onClick={() => q.data && reset(toProfile(q.data))}>
                Discard
              </Button>
              <Button type="submit" variant="contained" disabled={!formState.isDirty || m.isPending} startIcon={<Save size={16} />}>
                Save profile
              </Button>
            </Stack>
          </Grid>
        </Grid>
      </form>
    </SectionCard>
  );
}

function toProfile(p: OrganizationProfile): ProfileValues {
  return {
    name: p.name,
    legalName: p.legalName ?? '',
    address: p.address ?? '',
    city: p.city ?? '',
    state: p.state ?? '',
    phone: p.phone ?? '',
    email: p.email ?? '',
    registrationNumber: p.registrationNumber ?? '',
  };
}

function SettingsCard() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'hospital'], queryFn: adminApi.settings });
  const [values, setValues] = useState<Record<string, string>>({});
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (q.data) setValues(Object.fromEntries(q.data.map((s) => [s.key, s.value])));
  }, [q.data]);
  const dirty = useMemo(() => (q.data ?? []).filter((s) => values[s.key] !== undefined && values[s.key] !== s.value), [q.data, values]);
  const groups = useMemo(() => {
    const m = new Map<string, SettingView[]>();
    (q.data ?? []).forEach((s) => {
      if (!m.has(s.group)) m.set(s.group, []);
      m.get(s.group)!.push(s);
    });
    return [...m.entries()];
  }, [q.data]);

  const m = useMutation({
    mutationFn: () => adminApi.updateSettings(Object.fromEntries(dirty.map((s) => [s.key, values[s.key]]))),
    onSuccess: (data) => {
      toast.success(`${dirty.length} setting${dirty.length === 1 ? '' : 's'} saved`);
      setErrors({});
      setError(null);
      qc.setQueryData(['settings', 'hospital'], data);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.fieldErrors.length) {
        setErrors(Object.fromEntries(e.fieldErrors.map((f) => [f.field, f.message])));
        setError('Some settings are invalid. Review the highlighted fields.');
      } else setError(errorMessage(e));
    },
  });

  if (q.isLoading) return <CardsSkeleton count={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const set = (key: string, v: string) => {
    setValues((x) => ({ ...x, [key]: v }));
    setErrors((x) => ({ ...x, [key]: '' }));
  };

  return (
    <SectionCard
      title="Workflow settings"
      subtitle="Validated on the server. Clinical modules read these values at run time; changes are audited with old and new values."
      action={
        <Stack direction="row" spacing={2}>
          <Button color="inherit" disabled={!dirty.length || m.isPending} onClick={() => q.data && setValues(Object.fromEntries(q.data.map((s) => [s.key, s.value])))}>
            Discard
          </Button>
          <Button variant="contained" startIcon={<Save size={16} />} disabled={!dirty.length || m.isPending} onClick={() => m.mutate()}>
            Save {dirty.length ? `(${dirty.length})` : ''}
          </Button>
        </Stack>
      }
    >
      {error && (
        <Alert severity="error" sx={{ mb: 4 }}>
          {error}
        </Alert>
      )}
      <Stack spacing={5} divider={<Divider flexItem />}>
        {groups.map(([group, items]) => (
          <Box key={group}>
            <Typography variant="overline" color="text.secondary">
              {group}
            </Typography>
            <Stack spacing={4} sx={{ mt: 2 }}>
              {items.map((s) => (
                <SettingField key={s.key} s={s} value={values[s.key] ?? s.value} error={errors[s.key]} onChange={(v) => set(s.key, v)} />
              ))}
            </Stack>
          </Box>
        ))}
      </Stack>
    </SectionCard>
  );
}

function SettingField({ s, value, error, onChange }: { s: SettingView; value: string; error?: string; onChange: (v: string) => void }) {
  const id = `setting-${s.key}`;
  const meta = (
    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
      {s.description}
      {s.overridden ? ` · changed ${s.updatedAt ? fromNow(s.updatedAt) : ''}${s.updatedBy ? ' by ' + s.updatedBy : ''}` : ' · default'}
    </Typography>
  );
  const reset =
    value !== s.defaultValue ? (
      <Button size="small" color="inherit" startIcon={<RotateCcw size={13} />} onClick={() => onChange(s.defaultValue)} sx={{ flexShrink: 0 }}>
        Default
      </Button>
    ) : null;
  let control;
  switch (s.type) {
    case 'BOOLEAN':
      control = <FormControlLabel control={<Switch id={id} checked={value === 'true'} onChange={(e) => onChange(e.target.checked ? 'true' : 'false')} />} label={s.label} />;
      break;
    case 'INTEGER':
      control = (
        <TextField
          id={id}
          label={s.label}
          type="number"
          value={value}
          onChange={(e) => onChange(e.target.value)}
          error={!!error}
          helperText={error || `Between ${s.min} and ${s.max}`}
          slotProps={{ htmlInput: { min: s.min, max: s.max, step: 1 } }}
          sx={{ maxWidth: 320 }}
          fullWidth
        />
      );
      break;
    case 'ENUM':
      control = (
        <TextField id={id} select label={s.label} value={value} onChange={(e) => onChange(e.target.value)} error={!!error} helperText={error} sx={{ maxWidth: 320 }} fullWidth>
          {(s.options ?? []).map((o) => (
            <MenuItem key={o} value={o}>
              {humanize(o)}
            </MenuItem>
          ))}
        </TextField>
      );
      break;
    case 'MULTI_ENUM': {
      const selected = new Set(value.split(',').map((x) => x.trim()).filter(Boolean));
      control = (
        <Box>
          <Typography variant="body2" fontWeight={500}>
            {s.label}
          </Typography>
          <FormGroup row>
            {(s.options ?? []).map((o) => (
              <FormControlLabel
                key={o}
                control={
                  <Checkbox
                    checked={selected.has(o)}
                    onChange={(e) => {
                      const next = new Set(selected);
                      if (e.target.checked) next.add(o);
                      else next.delete(o);
                      onChange((s.options ?? []).filter((x) => next.has(x)).join(','));
                    }}
                  />
                }
                label={humanize(o)}
              />
            ))}
          </FormGroup>
          {error && (
            <Typography variant="caption" color="error">
              {error}
            </Typography>
          )}
        </Box>
      );
      break;
    }
    default:
      control = (
        <TextField id={id} label={s.label} value={value} onChange={(e) => onChange(e.target.value)} error={!!error} helperText={error} multiline minRows={2} fullWidth />
      );
  }
  return (
    <Stack direction="row" spacing={2} alignItems="flex-start">
      <Box sx={{ flex: 1, minWidth: 0 }}>
        {control}
        {meta}
      </Box>
      {reset}
    </Stack>
  );
}

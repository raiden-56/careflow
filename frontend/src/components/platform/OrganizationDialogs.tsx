'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation } from '@tanstack/react-query';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { AdminAccount, OrganizationDetail, platformApi } from '@/lib/api/platform';
import { applyServerErrors } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { emailOptional, optionalText, phoneSchema } from '@/lib/validation';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

export const TIMEZONES = ['Asia/Kolkata', 'Asia/Dubai', 'Asia/Singapore', 'Asia/Kathmandu', 'Asia/Dhaka', 'Asia/Colombo', 'Europe/London', 'UTC'];

const orgSchema = z.object({
  code: z.string().trim().regex(/^[A-Z][A-Z0-9_-]{2,31}$/, 'Use 3-32 upper-case letters, digits, - or _ starting with a letter'),
  name: z.string().trim().min(2, 'Name is required').max(200),
  legalName: optionalText(200, 'Legal name'),
  timezone: z.string().min(1, 'Select a time zone'),
  address: optionalText(500, 'Address'),
  city: optionalText(100, 'City'),
  state: optionalText(100, 'State'),
  phone: phoneSchema,
  email: emailOptional,
  registrationNumber: optionalText(100, 'Registration number'),
});
type OrgValues = z.infer<typeof orgSchema>;

export function OrganizationDialog({ open, org, onClose, onSaved }: { open: boolean; org?: OrganizationDetail; onClose: () => void; onSaved: (o: OrganizationDetail) => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<OrgValues>({ resolver: zodResolver(orgSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: org?.code ?? '',
        name: org?.name ?? '',
        legalName: org?.legalName ?? '',
        timezone: org?.timezone ?? 'Asia/Kolkata',
        address: org?.address ?? '',
        city: org?.city ?? '',
        state: org?.state ?? '',
        phone: org?.phone ?? '',
        email: org?.email ?? '',
        registrationNumber: org?.registrationNumber ?? '',
      });
    }
  }, [open, org, reset]);
  const m = useMutation({
    mutationFn: (v: OrgValues) => {
      const body = {
        name: v.name,
        legalName: v.legalName || null,
        timezone: v.timezone,
        address: v.address || null,
        city: v.city || null,
        state: v.state || null,
        phone: v.phone || null,
        email: v.email || null,
        registrationNumber: v.registrationNumber || null,
      };
      return org ? platformApi.updateOrganization(org.id, { ...body, version: org.version }) : platformApi.createOrganization({ ...body, code: v.code });
    },
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  const tzOptions = (org && !TIMEZONES.includes(org.timezone) ? [org.timezone, ...TIMEZONES] : TIMEZONES).map((t) => ({ value: t, label: t }));
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="org-title">
      <DialogTitle id="org-title">{org ? `Edit ${org.name}` : 'Register a hospital'}</DialogTitle>
      <DialogContent>
        <form id="org-form" onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          <Grid container spacing={3} sx={{ pt: 1 }}>
            {error && (
              <Grid size={12}>
                <Alert severity="error">{error}</Alert>
              </Grid>
            )}
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="code" label="Code" required fullWidth disabled={!!org} helperText={org ? 'Codes are permanent' : 'e.g. CFNORTH'} />
            </Grid>
            <Grid size={{ xs: 12, sm: 8 }}>
              <RHFTextField control={control} name="name" label="Hospital name" required fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 8 }}>
              <RHFTextField control={control} name="legalName" label="Legal name" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFSelect control={control} name="timezone" label="Time zone" required fullWidth options={tzOptions} />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="address" label="Address" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="city" label="City" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="state" label="State" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="phone" label="Phone" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="email" label="Contact email" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="registrationNumber" label="Registration number" fullWidth />
            </Grid>
            {!org && (
              <Grid size={12}>
                <Typography variant="caption" color="text.secondary">
                  Registration applies the default least-privilege role templates, discharge checklist, example observation thresholds,
                  escalation rules, notification templates and integration boundaries. Provision a hospital administrator next.
                </Typography>
              </Grid>
            )}
          </Grid>
        </form>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="org-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : org ? 'Save' : 'Register hospital'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const adminSchema = z.object({
  fullName: z.string().trim().min(2, 'Full name is required').max(200),
  email: z.string().trim().email('Enter a valid email address').max(200),
  phone: phoneSchema,
  designation: optionalText(100, 'Designation'),
});
type AdminValues = z.infer<typeof adminSchema>;

export function ProvisionAdminDialog({
  open,
  organizations,
  organizationId,
  onClose,
  onSaved,
}: {
  open: boolean;
  organizations: Array<{ id: string; name: string; status: string }>;
  organizationId?: string;
  onClose: () => void;
  onSaved: (a: AdminAccount) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const [orgId, setOrgId] = useState(organizationId ?? '');
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<AdminValues>({ resolver: zodResolver(adminSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      setOrgId(organizationId ?? '');
      reset({ fullName: '', email: '', phone: '', designation: 'Hospital Administrator' });
    }
  }, [open, organizationId, reset]);
  const m = useMutation({
    mutationFn: (v: AdminValues) => platformApi.createAdmin(orgId, { ...v, phone: v.phone || null, designation: v.designation || null }),
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="admin-title">
      <DialogTitle id="admin-title">Provision hospital administrator</DialogTitle>
      <DialogContent>
        <Stack
          component="form"
          id="admin-form"
          spacing={3}
          sx={{ pt: 1 }}
          onSubmit={handleSubmit((v) => (orgId ? m.mutate(v) : setError('Select the hospital.')))}
          noValidate
        >
          {error && <Alert severity="error">{error}</Alert>}
          {!organizationId && (
            <RHFSelectStandalone
              label="Hospital"
              value={orgId}
              onChange={setOrgId}
              options={organizations.filter((o) => o.status === 'ACTIVE').map((o) => ({ value: o.id, label: o.name }))}
            />
          )}
          <RHFTextField control={control} name="fullName" label="Full name" required fullWidth />
          <RHFTextField control={control} name="email" label="Work email" required fullWidth type="email" />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="phone" label="Phone" sx={{ flex: 1 }} />
            <RHFTextField control={control} name="designation" label="Designation" sx={{ flex: 1 }} />
          </Stack>
          <Typography variant="caption" color="text.secondary">
            The administrator receives a set-password invitation valid for 3 days (development outbox in this build). They manage their
            hospital&apos;s staff, roles and configuration; the platform team keeps no access to the hospital&apos;s records.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="admin-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Provisioning…' : 'Create & invite'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}


function RHFSelectStandalone({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: Array<{ value: string; label: string }> }) {
  return (
    <TextField select label={label} value={value} onChange={(e) => onChange(e.target.value)} required fullWidth>
      {options.map((o) => (
        <MenuItem key={o.value} value={o.value}>
          {o.label}
        </MenuItem>
      ))}
    </TextField>
  );
}

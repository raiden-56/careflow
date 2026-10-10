'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  Stack,
  Typography,
} from '@mui/material';
import { UserPlus } from 'lucide-react';
import { authApi, patientApi, qk } from '@/lib/api/core';
import type { CaregiverGrant } from '@/lib/api/types';
import { useMe } from '@/lib/auth/AuthProvider';
import { ROLE_LABEL } from '@/lib/nav';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { passwordSchema } from '@/lib/validation';
import { formatDate, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const pwSchema = z
  .object({ currentPassword: z.string().min(1, 'Enter your current password'), newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], message: 'Passwords do not match' })
  .refine((v) => v.newPassword !== v.currentPassword, { path: ['newPassword'], message: 'Choose a different password' });
type PwValues = z.infer<typeof pwSchema>;

const caregiverSchema = z.object({
  email: z.string().trim().email('Enter the caregiver’s portal email'),
  relationship: z.string().trim().min(2, 'Relationship is required').max(50),
  scope: z.enum(['FULL', 'BILLING_ONLY', 'CLINICAL_ONLY']),
});
type CaregiverValues = z.infer<typeof caregiverSchema>;

function ChangePassword() {
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<PwValues>({
    resolver: zodResolver(pwSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirm: '' },
  });
  return (
    <SectionCard title="Change password" subtitle="Other signed-in devices will be signed out.">
      <form
        noValidate
        onSubmit={handleSubmit(async (v) => {
          setError(null);
          try {
            await authApi.changePassword(v.currentPassword, v.newPassword);
            reset();
            toast.success('Password updated');
          } catch (e) {
            if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
          }
        })}
      >
        <Stack spacing={4} sx={{ maxWidth: 420 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <RHFTextField control={control} name="currentPassword" label="Current password" type="password" autoComplete="current-password" />
          <RHFTextField control={control} name="newPassword" label="New password" type="password" autoComplete="new-password" />
          <RHFTextField control={control} name="confirm" label="Confirm new password" type="password" autoComplete="new-password" />
          <Box>
            <Button type="submit" variant="contained" disabled={formState.isSubmitting}>
              Update password
            </Button>
          </Box>
        </Stack>
      </form>
    </SectionCard>
  );
}

function Caregivers({ patientId }: { patientId: string }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [revoke, setRevoke] = useState<CaregiverGrant | null>(null);
  const q = useQuery({ queryKey: qk.caregivers(patientId), queryFn: () => patientApi.caregivers(patientId) });
  const { control, handleSubmit, reset, formState } = useForm<CaregiverValues>({
    resolver: zodResolver(caregiverSchema),
    defaultValues: { email: '', relationship: '', scope: 'FULL' },
  });
  const [formError, setFormError] = useState<string | null>(null);
  const revokeMutation = useMutation({
    mutationFn: (g: CaregiverGrant) => patientApi.revokeCaregiver(patientId, g.id),
    onSuccess: () => {
      toast.success('Access revoked');
      setRevoke(null);
      queryClient.invalidateQueries({ queryKey: qk.caregivers(patientId) });
    },
    onError: toast.error,
  });

  return (
    <SectionCard
      title="Authorized caregivers"
      subtitle="People you allow to view your records in the portal. You can revoke access at any time."
      action={
        <Button startIcon={<UserPlus size={16} />} variant="outlined" onClick={() => setOpen(true)}>
          Add caregiver
        </Button>
      }
      flush
    >
      <DataTable<CaregiverGrant>
        rows={q.data}
        loading={q.isLoading}
        error={q.error}
        rowKey={(g) => g.id}
        emptyTitle="No caregivers authorized"
        emptyDescription="Only you can see your records."
        columns={[
          { key: 'name', header: 'Caregiver', primary: true, render: (g) => <b>{g.granteeName}</b> },
          { key: 'email', header: 'Email', render: (g) => g.granteeEmail },
          { key: 'rel', header: 'Relationship', render: (g) => g.relationship },
          { key: 'scope', header: 'Access', render: (g) => humanize(g.scope) },
          { key: 'since', header: 'Since', render: (g) => formatDate(g.grantedAt) },
          { key: 'status', header: 'Status', render: (g) => <StatusChip value={g.active ? 'ACTIVE' : 'WITHDRAWN'} label={g.active ? 'Active' : 'Revoked'} /> },
          {
            key: 'act',
            header: '',
            align: 'right',
            render: (g) =>
              g.active ? (
                <Button size="small" color="error" onClick={() => setRevoke(g)}>
                  Revoke
                </Button>
              ) : null,
          },
        ]}
      />
      <Dialog open={open} onClose={() => setOpen(false)} maxWidth="xs" fullWidth>
        <form
          noValidate
          onSubmit={handleSubmit(async (v) => {
            setFormError(null);
            try {
              await patientApi.addCaregiver(patientId, v);
              toast.success('Caregiver access granted');
              reset();
              setOpen(false);
              queryClient.invalidateQueries({ queryKey: qk.caregivers(patientId) });
            } catch (e) {
              setFormError((e as Error).message);
            }
          })}
        >
          <DialogTitle>Authorize a caregiver</DialogTitle>
          <DialogContent>
            <Stack spacing={4} sx={{ pt: 1 }}>
              <Typography variant="body2" color="text.secondary">
                The caregiver must already have a CareFlow portal account. Ask the registration desk to create one if needed.
              </Typography>
              {formError && <Alert severity="error">{formError}</Alert>}
              <RHFTextField control={control} name="email" label="Caregiver portal email" type="email" />
              <RHFTextField control={control} name="relationship" label="Relationship (e.g. Spouse, Son)" />
              <RHFSelect
                control={control}
                name="scope"
                label="What can they see?"
                options={[
                  { value: 'FULL', label: 'Everything I can see' },
                  { value: 'CLINICAL_ONLY', label: 'Clinical information only' },
                  { value: 'BILLING_ONLY', label: 'Bills and payments only' },
                ]}
              />
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 6, pb: 4 }}>
            <Button onClick={() => setOpen(false)} color="inherit">
              Cancel
            </Button>
            <Button type="submit" variant="contained" disabled={formState.isSubmitting}>
              Grant access
            </Button>
          </DialogActions>
        </form>
      </Dialog>
      <ConfirmDialog
        open={!!revoke}
        title="Revoke caregiver access?"
        description={`${revoke?.granteeName} will no longer be able to view your records.`}
        confirmLabel="Revoke access"
        tone="error"
        loading={revokeMutation.isPending}
        onConfirm={() => revoke && revokeMutation.mutate(revoke)}
        onClose={() => setRevoke(null)}
      />
    </SectionCard>
  );
}

export default function ProfilePage() {
  const me = useMe();
  return (
    <>
      <PageHeader title="Profile & security" breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Profile' }]} />
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="Account">
            <DefinitionList
              items={[
                { label: 'Name', value: me.fullName },
                { label: 'Email', value: me.email },
                { label: 'Phone', value: me.phone },
                { label: 'Role', value: ROLE_LABEL[me.role] },
                { label: 'Organization', value: me.organization.name },
                { label: 'Timezone', value: me.organization.timezone },
                ...(me.staff
                  ? [
                      { label: 'Designation', value: me.staff.designation },
                      { label: 'Department', value: me.staff.departmentName },
                      { label: 'Specialty', value: me.staff.specialty },
                      { label: 'Assigned wards', value: me.staff.wards.map((w) => w.name).join(', ') || '—' },
                    ]
                  : []),
              ]}
            />
            {me.role === 'PATIENT' && me.accessiblePatients.length > 1 && (
              <Alert severity="info" sx={{ mt: 4 }}>
                You can also view records for:{' '}
                {me.accessiblePatients
                  .filter((p) => p.relationship !== 'SELF')
                  .map((p) => `${p.name} (${p.relationship})`)
                  .join(', ')}
              </Alert>
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 6 }}>
          <ChangePassword />
        </Grid>
        {me.role === 'PATIENT' && me.patientId && (
          <Grid size={12}>
            <Caregivers patientId={me.patientId} />
          </Grid>
        )}
      </Grid>
    </>
  );
}

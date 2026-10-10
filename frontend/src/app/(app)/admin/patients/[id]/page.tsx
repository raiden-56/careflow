'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
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
  TextField,
  Typography,
} from '@mui/material';
import { BedDouble, KeyRound, Pencil, UserPlus } from 'lucide-react';
import { patientApi, qk } from '@/lib/api/core';
import type { AdmissionSummary, CaregiverGrant } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDate, genderShort, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { PatientForm } from '@/components/patient/PatientForm';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { phoneSchema } from '@/lib/validation';

const caregiverSchema = z.object({
  email: z.string().trim().email('Enter a valid email'),
  fullName: z.string().trim().max(200).optional(),
  phone: phoneSchema,
  relationship: z.string().trim().min(2, 'Relationship is required').max(50),
  scope: z.enum(['FULL', 'BILLING_ONLY', 'CLINICAL_ONLY']),
});
type CaregiverValues = z.infer<typeof caregiverSchema>;

export default function PatientDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const [editing, setEditing] = useState(false);
  const [portalOpen, setPortalOpen] = useState(false);
  const [caregiverOpen, setCaregiverOpen] = useState(false);
  const [revoke, setRevoke] = useState<CaregiverGrant | null>(null);
  const [portalEmail, setPortalEmail] = useState('');
  const patient = useQuery({ queryKey: qk.patient(id), queryFn: () => patientApi.get(id) });
  const admissions = useQuery({ queryKey: qk.patientAdmissions(id), queryFn: () => patientApi.admissions(id) });
  const caregivers = useQuery({ queryKey: qk.caregivers(id), queryFn: () => patientApi.caregivers(id), enabled: hasPermission('PATIENT_REGISTER') });
  const cgForm = useForm<CaregiverValues>({ resolver: zodResolver(caregiverSchema), defaultValues: { email: '', fullName: '', phone: '', relationship: '', scope: 'FULL' } });
  const [cgError, setCgError] = useState<string | null>(null);

  const portal = useMutation({
    mutationFn: () => patientApi.createPortalAccount(id, portalEmail),
    onSuccess: () => {
      toast.success('Portal invitation sent');
      setPortalOpen(false);
      queryClient.invalidateQueries({ queryKey: qk.patient(id) });
    },
    onError: toast.error,
  });
  const revokeMutation = useMutation({
    mutationFn: (g: CaregiverGrant) => patientApi.revokeCaregiver(id, g.id),
    onSuccess: () => {
      toast.success('Caregiver access revoked');
      setRevoke(null);
      queryClient.invalidateQueries({ queryKey: qk.caregivers(id) });
    },
    onError: toast.error,
  });

  if (patient.isLoading) return <PageSkeleton />;
  if (patient.error || !patient.data) return <ErrorState error={patient.error} onRetry={() => patient.refetch()} />;
  const p = patient.data;
  const open = admissions.data?.find((a) => ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'].includes(a.status));

  return (
    <>
      <PageHeader
        title={p.fullName}
        subtitle={`${p.mrn} · ${p.age}y ${genderShort(p.gender)} · Registered ${formatDate(p.createdAt)}`}
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Patients', href: '/admin/patients' }, { label: p.fullName }]}
        actions={
          <>
            {hasPermission('PATIENT_REGISTER') && (
              <Button startIcon={<Pencil size={16} />} variant="outlined" onClick={() => setEditing(true)}>
                Edit details
              </Button>
            )}
            {hasPermission('ADMISSION_MANAGE') &&
              (open ? (
                <Button component={Link} href={`/admin/admissions/${open.id}`} variant="contained" startIcon={<BedDouble size={16} />}>
                  View current admission
                </Button>
              ) : (
                <Button component={Link} href={`/admin/admissions/new?patientId=${p.id}`} variant="contained" startIcon={<BedDouble size={16} />}>
                  Admit patient
                </Button>
              ))}
          </>
        }
      />
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 8 }}>
          <SectionCard title="Demographics">
            <DefinitionList
              items={[
                { label: 'Date of birth', value: formatDate(p.dateOfBirth) },
                { label: 'Blood group', value: p.bloodGroup },
                { label: 'Phone', value: p.phone },
                { label: 'Email', value: p.email },
                { label: 'Address', value: [p.addressLine, p.city, p.state, p.pincode].filter(Boolean).join(', '), span: 2 },
                { label: 'Emergency contact', value: p.emergencyContactName ? `${p.emergencyContactName} (${p.emergencyContactRelation ?? '—'}) · ${p.emergencyContactPhone ?? ''}` : null, span: 2 },
                { label: 'ABHA number', value: p.abhaNumber },
                { label: 'Preferred language', value: p.preferredLanguage },
                { label: 'Status', value: <StatusChip value={p.status} /> },
              ]}
            />
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 4 }}>
          <SectionCard title="Patient portal">
            {p.hasPortalAccount ? (
              <Alert severity="success">Portal account active. The patient can view released records and bills.</Alert>
            ) : (
              <Stack spacing={3}>
                <Typography variant="body2" color="text.secondary">
                  No portal account yet. Create one to let the patient view their admission, released reports and bills.
                </Typography>
                {hasPermission('PATIENT_REGISTER') && (
                  <Button
                    variant="outlined"
                    startIcon={<KeyRound size={16} />}
                    onClick={() => {
                      setPortalEmail(p.email ?? '');
                      setPortalOpen(true);
                    }}
                  >
                    Create portal account
                  </Button>
                )}
              </Stack>
            )}
          </SectionCard>
        </Grid>
        <Grid size={12}>
          <SectionCard title="Admission history" flush>
            <DataTable<AdmissionSummary>
              rows={admissions.data}
              loading={admissions.isLoading}
              error={admissions.error}
              rowKey={(a) => a.id}
              onRowClick={(a) => router.push(`/admin/admissions/${a.id}`)}
              emptyTitle="No admissions"
              columns={[
                { key: 'no', header: 'Admission', primary: true, render: (a) => <b>{a.admissionNumber}</b> },
                { key: 'status', header: 'Status', render: (a) => <StatusChip value={a.status} /> },
                { key: 'dept', header: 'Department', render: (a) => a.departmentName },
                { key: 'doc', header: 'Doctor', render: (a) => a.attendingDoctorName },
                { key: 'in', header: 'Admitted', render: (a) => formatDate(a.admittedAt) },
                { key: 'out', header: 'Discharged', render: (a) => formatDate(a.dischargedAt) },
                { key: 'los', header: 'LOS', align: 'right', render: (a) => `${a.lengthOfStayDays}d` },
              ]}
            />
          </SectionCard>
        </Grid>
        {hasPermission('PATIENT_REGISTER') && (
          <Grid size={12}>
            <SectionCard
              title="Authorized caregivers"
              subtitle="Explicit, revocable portal access for family members or guardians."
              action={
                <Button startIcon={<UserPlus size={16} />} variant="outlined" onClick={() => setCaregiverOpen(true)}>
                  Add caregiver
                </Button>
              }
              flush
            >
              <DataTable<CaregiverGrant>
                rows={caregivers.data}
                loading={caregivers.isLoading}
                error={caregivers.error}
                rowKey={(g) => g.id}
                emptyTitle="No caregivers"
                columns={[
                  { key: 'n', header: 'Caregiver', primary: true, render: (g) => <b>{g.granteeName}</b> },
                  { key: 'e', header: 'Email', render: (g) => g.granteeEmail },
                  { key: 'r', header: 'Relationship', render: (g) => g.relationship },
                  { key: 's', header: 'Scope', render: (g) => humanize(g.scope) },
                  { key: 'st', header: 'Status', render: (g) => <StatusChip value={g.active ? 'ACTIVE' : 'WITHDRAWN'} label={g.active ? 'Active' : 'Revoked'} /> },
                  {
                    key: 'a',
                    header: '',
                    align: 'right',
                    render: (g) =>
                      g.active && (
                        <Button size="small" color="error" onClick={() => setRevoke(g)}>
                          Revoke
                        </Button>
                      ),
                  },
                ]}
              />
            </SectionCard>
          </Grid>
        )}
      </Grid>

      <Dialog open={editing} onClose={() => setEditing(false)} maxWidth="md" fullWidth>
        <DialogTitle>Edit patient details</DialogTitle>
        <DialogContent>
          <Box sx={{ pt: 2 }}>
            <PatientForm
              initial={p}
              submitLabel="Save changes"
              onCancel={() => setEditing(false)}
              onSubmit={async (body) => {
                await patientApi.update(id, body);
                toast.success('Patient details updated');
                setEditing(false);
                queryClient.invalidateQueries({ queryKey: qk.patient(id) });
                queryClient.invalidateQueries({ queryKey: ['patients'] });
              }}
            />
          </Box>
        </DialogContent>
      </Dialog>

      <ConfirmDialog
        open={portalOpen}
        title="Create portal account"
        description="An invitation with a set-password link (valid 7 days) will be emailed to this address."
        confirmLabel="Send invitation"
        loading={portal.isPending}
        onConfirm={() => portal.mutate()}
        onClose={() => setPortalOpen(false)}
      >
        <TextField
          label="Portal email"
          type="email"
          value={portalEmail}
          onChange={(e) => setPortalEmail(e.target.value)}
          error={!!portalEmail && !/^\S+@\S+\.\S+$/.test(portalEmail)}
          helperText={!!portalEmail && !/^\S+@\S+\.\S+$/.test(portalEmail) ? 'Enter a valid email' : ' '}
          sx={{ mt: 2 }}
        />
      </ConfirmDialog>

      <Dialog open={caregiverOpen} onClose={() => setCaregiverOpen(false)} maxWidth="sm" fullWidth>
        <form
          noValidate
          onSubmit={cgForm.handleSubmit(async (v) => {
            setCgError(null);
            try {
              await patientApi.addCaregiver(id, { ...v, fullName: v.fullName || undefined, phone: v.phone || undefined });
              toast.success('Caregiver access granted');
              cgForm.reset();
              setCaregiverOpen(false);
              queryClient.invalidateQueries({ queryKey: qk.caregivers(id) });
            } catch (e) {
              setCgError((e as Error).message);
            }
          })}
        >
          <DialogTitle>Add caregiver</DialogTitle>
          <DialogContent>
            <Stack spacing={4} sx={{ pt: 1 }}>
              <Typography variant="body2" color="text.secondary">
                If the caregiver has no portal account, enter their name to create one; they receive a set-password invitation. Record
                the patient&apos;s consent for this access under Consent &amp; requests.
              </Typography>
              {cgError && <Alert severity="error">{cgError}</Alert>}
              <RHFTextField control={cgForm.control} name="email" label="Caregiver email" type="email" required />
              <RHFTextField control={cgForm.control} name="fullName" label="Full name (for a new account)" />
              <RHFTextField control={cgForm.control} name="phone" label="Phone" />
              <RHFTextField control={cgForm.control} name="relationship" label="Relationship" required />
              <RHFSelect
                control={cgForm.control}
                name="scope"
                label="Access scope"
                options={[
                  { value: 'FULL', label: 'Full portal access' },
                  { value: 'CLINICAL_ONLY', label: 'Clinical only' },
                  { value: 'BILLING_ONLY', label: 'Billing only' },
                ]}
              />
            </Stack>
          </DialogContent>
          <DialogActions sx={{ px: 6, pb: 4 }}>
            <Button onClick={() => setCaregiverOpen(false)} color="inherit">
              Cancel
            </Button>
            <Button type="submit" variant="contained" disabled={cgForm.formState.isSubmitting}>
              Grant access
            </Button>
          </DialogActions>
        </form>
      </Dialog>

      <ConfirmDialog
        open={!!revoke}
        title="Revoke caregiver access?"
        description={`${revoke?.granteeName} will immediately lose access to this patient's portal records.`}
        tone="error"
        confirmLabel="Revoke"
        loading={revokeMutation.isPending}
        onConfirm={() => revoke && revokeMutation.mutate(revoke)}
        onClose={() => setRevoke(null)}
      />
    </>
  );
}

'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Grid, Stack, Typography } from '@mui/material';
import { BedDouble, CalendarClock, Stethoscope } from 'lucide-react';
import { notificationApi, patientApi, qk } from '@/lib/api/core';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { useMe } from '@/lib/auth/AuthProvider';
import { formatDate, formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { DefinitionList } from '@/components/ui/Display';
import { EmptyState, PageSkeleton } from '@/components/ui/States';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';

export default function PatientDashboard() {
  const me = useMe();
  const { patientId, patient } = usePortal();
  const profile = useQuery({ queryKey: qk.patient(patientId ?? ''), queryFn: () => patientApi.get(patientId!), enabled: !!patientId });
  const admissions = useQuery({ queryKey: qk.patientAdmissions(patientId ?? ''), queryFn: () => patientApi.admissions(patientId!), enabled: !!patientId });
  const notes = useQuery({ queryKey: qk.notifications({ size: 5 }), queryFn: () => notificationApi.list({ size: 5 }) });

  if (!patientId || profile.isLoading || admissions.isLoading) return <PageSkeleton />;
  if (!patient) {
    return <EmptyState title="No patient record linked" description="Ask the hospital registration desk to link your portal account to your patient record." />;
  }
  const current = admissions.data?.find((a) => ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'].includes(a.status));
  const last = admissions.data?.find((a) => a.status === 'DISCHARGED');
  const p = profile.data;

  return (
    <>
      <PageHeader
        title={patient.relationship === 'SELF' ? `Hello, ${me.fullName.split(' ')[0]}` : `${patient.name}'s records`}
        subtitle={p ? `${p.mrn} · ${p.age} years` : undefined}
        actions={<PortalPatientSwitcher />}
      />
      {current?.status === 'DISCHARGE_IN_PROGRESS' && (
        <Alert severity="info" sx={{ mb: 5 }} action={<Button component={Link} href="/patient/discharge" color="inherit" size="small">View progress</Button>}>
          Your discharge is being prepared. You can follow each step on the discharge page.
        </Alert>
      )}
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard
            label="Admission status"
            value={current ? <StatusChip value={current.status} size="medium" /> : 'Not admitted'}
            hint={current ? `Since ${formatDate(current.admittedAt)}` : last ? `Last discharged ${formatDate(last.dischargedAt)}` : undefined}
            icon={BedDouble}
            href={current ? `/patient/admissions/${current.id}` : '/patient/admissions'}
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Treating doctor" value={<Typography variant="h4" component="span">{(current ?? last)?.attendingDoctorName ?? '—'}</Typography>} hint={(current ?? last)?.departmentName} icon={Stethoscope} tone="info" />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard
            label="Expected discharge"
            value={<Typography variant="h4" component="span">{current?.expectedDischargeDate ? formatDate(current.expectedDischargeDate) : current ? 'To be decided' : '—'}</Typography>}
            hint={current ? 'Set by your care team; may change' : undefined}
            icon={CalendarClock}
            tone="secondary"
          />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          {current ? (
            <SectionCard title="Your current stay" action={<Button component={Link} href={`/patient/admissions/${current.id}`} size="small">Details</Button>}>
              <DefinitionList
                items={[
                  { label: 'Hospital', value: me.organization.name },
                  { label: 'Admission number', value: current.admissionNumber },
                  { label: 'Ward', value: current.location?.wardName ?? 'Bed being arranged' },
                  { label: 'Room / bed', value: current.location ? `Room ${current.location.roomNumber} · Bed ${current.location.bedNumber}` : '—' },
                  { label: 'Admitted', value: formatDateTime(current.admittedAt) },
                  { label: 'Department', value: current.departmentName },
                ]}
              />
              <Box sx={{ mt: 5 }}>
                <Typography variant="subtitle2" sx={{ mb: 3 }}>
                  Recent updates
                </Typography>
                <AdmissionTimeline admissionId={current.id} categories={['ADMISSION', 'CLINICAL', 'INVESTIGATION', 'DISCHARGE', 'BILLING']} />
              </Box>
            </SectionCard>
          ) : (
            <SectionCard title="Your stays">
              <EmptyState
                icon={BedDouble}
                title="You are not currently admitted"
                description="Past admissions, reports, bills and follow-up instructions remain available here."
                action={<Button component={Link} href="/patient/admissions" variant="outlined">View past admissions</Button>}
              />
            </SectionCard>
          )}
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Notifications" action={<Button component={Link} href="/notifications" size="small">All</Button>}>
            {notes.data?.content.length ? (
              <Stack spacing={3}>
                {notes.data.content.map((n) => (
                  <Box key={n.id} component={n.link ? Link : 'div'} href={n.link ?? ''} sx={{ textDecoration: 'none', color: 'inherit' }}>
                    <Typography variant="body2" fontWeight={n.readAt ? 500 : 700}>
                      {n.title}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {n.message} · {fromNow(n.createdAt)}
                    </Typography>
                  </Box>
                ))}
              </Stack>
            ) : (
              <EmptyState title="No notifications" compact />
            )}
          </SectionCard>
        </Grid>
      </Grid>
    </>
  );
}

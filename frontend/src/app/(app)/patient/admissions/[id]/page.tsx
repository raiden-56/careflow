'use client';

import { use } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Grid, Stack, Typography } from '@mui/material';
import { admissionApi, qk } from '@/lib/api/core';
import { formatDate, formatDateTime, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { AdmissionTimeline } from '@/components/timeline/AdmissionTimeline';

/** Patient-facing admission detail. The server only returns patient-released timeline events. */
export default function PatientAdmissionDetail({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const q = useQuery({ queryKey: qk.admission(id), queryFn: () => admissionApi.get(id) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const a = d.summary;
  return (
    <>
      <PageHeader
        title={`${a.departmentName} admission`}
        subtitle={<Stack direction="row" spacing={2} alignItems="center"><StatusChip value={a.status} /><span>{a.admissionNumber}</span></Stack>}
        breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Admissions', href: '/patient/admissions' }, { label: a.admissionNumber }]}
      />
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            <SectionCard title="Your stay">
              <DefinitionList
                columns={1}
                items={[
                  { label: 'Ward, room and bed', value: a.location ? `${a.location.wardName} · Room ${a.location.roomNumber} · Bed ${a.location.bedNumber}` : a.status === 'PENDING_BED' ? 'A bed is being arranged' : '—' },
                  { label: 'Treating doctor', value: a.attendingDoctorName },
                  { label: 'Admitted on', value: formatDateTime(a.admittedAt) },
                  { label: 'Expected discharge', value: a.expectedDischargeDate ? formatDate(a.expectedDischargeDate) : 'Not yet decided by your care team' },
                  ...(a.dischargedAt ? [{ label: 'Discharged on', value: `${formatDateTime(a.dischargedAt)}${d.dischargeDisposition ? ` (${humanize(d.dischargeDisposition)})` : ''}` }] : []),
                  { label: 'Reason for admission', value: d.admissionReason },
                ]}
              />
            </SectionCard>
            <SectionCard title="Your care team">
              <Stack spacing={2}>
                {d.careTeam.map((m) => (
                  <div key={m.id}>
                    <Typography variant="body2" fontWeight={600}>
                      {m.fullName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {humanize(m.careRole)}
                    </Typography>
                  </div>
                ))}
              </Stack>
            </SectionCard>
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Progress timeline" subtitle="Updates your care team has released to you.">
            <AdmissionTimeline admissionId={id} categories={['ADMISSION', 'CLINICAL', 'MEDICATION', 'INVESTIGATION', 'TRANSFER', 'BILLING', 'DISCHARGE']} />
          </SectionCard>
        </Grid>
      </Grid>
    </>
  );
}

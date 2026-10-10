'use client';

import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Box, Card, Typography } from '@mui/material';
import { patientApi, qk } from '@/lib/api/core';
import type { AdmissionSummary } from '@/lib/api/types';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { formatDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';

export default function MyAdmissionsPage() {
  const router = useRouter();
  const { patientId, patient } = usePortal();
  const q = useQuery({ queryKey: qk.patientAdmissions(patientId ?? ''), queryFn: () => patientApi.admissions(patientId!), enabled: !!patientId });
  return (
    <>
      <PageHeader
        title="My admissions"
        subtitle={patient && patient.relationship !== 'SELF' ? `Showing admissions for ${patient.name}` : 'Your current and past hospital stays.'}
        breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Admissions' }]}
        actions={<PortalPatientSwitcher />}
      />
      <Card>
        <DataTable<AdmissionSummary>
          rows={q.data}
          loading={q.isLoading || !patientId}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(a) => a.id}
          onRowClick={(a) => router.push(`/patient/admissions/${a.id}`)}
          emptyTitle="No hospital admissions on record"
          columns={[
            {
              key: 'no',
              header: 'Admission',
              primary: true,
              render: (a) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {a.departmentName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {a.admissionNumber}
                  </Typography>
                </Box>
              ),
            },
            { key: 'status', header: 'Status', render: (a) => <StatusChip value={a.status} /> },
            { key: 'doctor', header: 'Treating doctor', render: (a) => a.attendingDoctorName },
            { key: 'in', header: 'Admitted', render: (a) => formatDate(a.admittedAt) },
            { key: 'out', header: 'Discharged', render: (a) => (a.dischargedAt ? formatDate(a.dischargedAt) : a.expectedDischargeDate ? `Expected ${formatDate(a.expectedDischargeDate)}` : '—') },
            { key: 'where', header: 'Ward / bed', render: (a) => (a.location ? `${a.location.wardName} · Room ${a.location.roomNumber}, Bed ${a.location.bedNumber}` : '—') },
          ]}
        />
      </Card>
    </>
  );
}

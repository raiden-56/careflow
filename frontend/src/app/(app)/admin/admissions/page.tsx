'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Plus, Search } from 'lucide-react';
import { admissionApi, facilityApi, qk } from '@/lib/api/core';
import type { AdmissionStatus, AdmissionSummary } from '@/lib/api/types';
import { useTableState } from '@/lib/hooks';
import { formatDate, formatDateTime, genderShort } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';

const STATUS_FILTERS: { label: string; value: AdmissionStatus[] }[] = [
  { label: 'Active', value: ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'] },
  { label: 'Awaiting bed', value: ['PENDING_BED'] },
  { label: 'Discharging', value: ['DISCHARGE_IN_PROGRESS'] },
  { label: 'Discharged', value: ['DISCHARGED'] },
  { label: 'Cancelled', value: ['CANCELLED'] },
  { label: 'All', value: [] },
];

export default function AdmissionsPage() {
  const router = useRouter();
  const t = useTableState({ sort: { field: 'admittedAt', direction: 'desc' } });
  const [filter, setFilter] = useState(0);
  const [departmentId, setDepartmentId] = useState('');
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const params = {
    status: STATUS_FILTERS[filter].value,
    departmentId: departmentId || undefined,
    q: t.q,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const q = useQuery({ queryKey: qk.admissions(params), queryFn: () => admissionApi.search(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Admissions"
        subtitle="Create admissions, assign beds and track each inpatient stay."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Admissions' }]}
        actions={
          <Button component={Link} href="/admin/admissions/new" variant="contained" startIcon={<Plus size={16} />}>
            New admission
          </Button>
        }
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {STATUS_FILTERS.map((f, i) => (
              <Chip
                key={f.label}
                label={f.label}
                clickable
                color={filter === i ? 'primary' : 'default'}
                variant={filter === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setFilter(i);
                  t.setPage(0);
                }}
              />
            ))}
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <TextField
              placeholder="Search patient, MRN or admission no."
              value={t.search}
              onChange={(e) => t.setSearch(e.target.value)}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
              sx={{ maxWidth: { sm: 360 } }}
            />
            <TextField select label="Department" value={departmentId} onChange={(e) => { setDepartmentId(e.target.value); t.setPage(0); }} sx={{ maxWidth: { sm: 240 } }}>
              <MenuItem value="">All departments</MenuItem>
              {departments.data?.map((d) => (
                <MenuItem key={d.id} value={d.id}>
                  {d.name}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Stack>
        <DataTable<AdmissionSummary>
          ariaLabel="Admissions"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(a) => a.id}
          onRowClick={(a) => router.push(`/admin/admissions/${a.id}`)}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No admissions match these filters"
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              render: (a) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {a.patient.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {a.patient.mrn} · {a.patient.age}y {genderShort(a.patient.gender)}
                  </Typography>
                </Box>
              ),
            },
            { key: 'no', header: 'Admission', sortKey: 'admissionNumber', render: (a) => a.admissionNumber },
            { key: 'status', header: 'Status', sortKey: 'status', render: (a) => <StatusChip value={a.status} /> },
            {
              key: 'loc',
              header: 'Location',
              render: (a) =>
                a.location ? `${a.location.wardName} · ${a.location.roomNumber}-${a.location.bedNumber}` : a.status === 'PENDING_BED' ? <Typography variant="caption" color="warning.main" fontWeight={600}>Needs bed</Typography> : '—',
            },
            { key: 'dept', header: 'Department / doctor', hideOnMobile: true, render: (a) => <Box><Typography variant="body2">{a.departmentName}</Typography><Typography variant="caption" color="text.secondary">{a.attendingDoctorName}</Typography></Box> },
            { key: 'admitted', header: 'Admitted', sortKey: 'admittedAt', render: (a) => formatDateTime(a.admittedAt) },
            { key: 'edd', header: 'Expected discharge', sortKey: 'expectedDischargeDate', hideOnMobile: true, render: (a) => formatDate(a.expectedDischargeDate) },
          ]}
        />
      </Card>
    </>
  );
}

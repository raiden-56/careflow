'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useQuery, keepPreviousData } from '@tanstack/react-query';
import { Box, Button, Card, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Search, UserPlus } from 'lucide-react';
import { patientApi, qk } from '@/lib/api/core';
import type { PatientSummary } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDate, genderShort } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { useState } from 'react';

export default function PatientsPage() {
  const router = useRouter();
  const { hasPermission } = useAuth();
  const t = useTableState({ sort: { field: 'createdAt', direction: 'desc' } });
  const [status, setStatus] = useState('');
  const params = { q: t.q, status: status || undefined, page: t.page, size: t.size, sort: sortParam(t.sort) };
  const q = useQuery({ queryKey: qk.patients(params), queryFn: () => patientApi.search(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Patients"
        subtitle="Register patients and manage demographics. Clinical records are only visible to the care team."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Patients' }]}
        actions={
          hasPermission('PATIENT_REGISTER') && (
            <Button component={Link} href="/admin/patients/new" variant="contained" startIcon={<UserPlus size={16} />}>
              Register patient
            </Button>
          )
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField
            placeholder="Search name, MRN or phone"
            value={t.search}
            onChange={(e) => t.setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { sm: 360 } }}
          />
          <TextField select label="Status" value={status} onChange={(e) => { setStatus(e.target.value); t.setPage(0); }} sx={{ maxWidth: { sm: 180 } }}>
            <MenuItem value="">All</MenuItem>
            <MenuItem value="ACTIVE">Active</MenuItem>
            <MenuItem value="ARCHIVED">Archived</MenuItem>
            <MenuItem value="DECEASED">Deceased</MenuItem>
          </TextField>
        </Stack>
        <DataTable<PatientSummary>
          ariaLabel="Patients"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(p) => p.id}
          onRowClick={(p) => router.push(`/admin/patients/${p.id}`)}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle={t.q ? 'No patients match your search' : 'No patients registered yet'}
          emptyAction={
            hasPermission('PATIENT_REGISTER') && (
              <Button component={Link} href="/admin/patients/new" variant="outlined">
                Register a patient
              </Button>
            )
          }
          columns={[
            {
              key: 'name',
              header: 'Patient',
              sortKey: 'name',
              primary: true,
              render: (p) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {p.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {p.mrn}
                  </Typography>
                </Box>
              ),
            },
            { key: 'age', header: 'Age / sex', sortKey: 'dateOfBirth', render: (p) => `${p.age}y ${genderShort(p.gender)}` },
            { key: 'phone', header: 'Phone', render: (p) => p.phone ?? '—' },
            {
              key: 'adm',
              header: 'Current admission',
              render: (p) =>
                p.currentAdmission ? (
                  <Stack direction="row" spacing={1} alignItems="center">
                    <StatusChip value={p.currentAdmission.status} />
                    <Typography variant="caption" color="text.secondary">
                      {p.currentAdmission.wardName ? `${p.currentAdmission.wardName} · ${p.currentAdmission.bedNumber}` : p.currentAdmission.admissionNumber}
                    </Typography>
                  </Stack>
                ) : (
                  <Typography variant="caption" color="text.secondary">
                    Not admitted
                  </Typography>
                ),
            },
            { key: 'portal', header: 'Portal', hideOnMobile: true, render: (p) => (p.hasPortalAccount ? 'Yes' : '—') },
            { key: 'created', header: 'Registered', sortKey: 'createdAt', render: (p) => formatDate(p.createdAt) },
          ]}
        />
      </Card>
    </>
  );
}

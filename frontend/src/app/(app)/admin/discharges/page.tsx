'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Card, Chip, FormControlLabel, Grid, InputAdornment, MenuItem, Stack, Switch, TextField, Typography } from '@mui/material';
import { AlertOctagon, ClipboardCheck, DoorOpen, Hourglass, Search } from 'lucide-react';
import { facilityApi, qk } from '@/lib/api/core';
import { dischargeApi, dqk, DISPOSITION_LABEL, type WorkflowListItem } from '@/lib/api/discharge';
import { useDebouncedValue } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { BlockersCell, ProgressCell } from '@/components/discharge/Worklist';
import { DelayReportCard } from '@/components/discharge/DelayReportCard';
import { ageLabel } from '@/components/discharge/hooks';

type Scope = 'ACTIVE' | 'COMPLETED' | 'CANCELLED';

export default function DischargeBoardPage() {
  const router = useRouter();
  const [scope, setScope] = useState<Scope>('ACTIVE');
  const [wardId, setWardId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [blockedOnly, setBlockedOnly] = useState(false);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 300);
  const params = { scope, wardId: wardId || undefined, departmentId: departmentId || undefined, q: q || undefined, blockedOnly, days: 30 };
  const board = useQuery({ queryKey: dqk.board(params), queryFn: () => dischargeApi.board(params), placeholderData: keepPreviousData, refetchInterval: 60_000 });
  const active = useQuery({ queryKey: dqk.board({ scope: 'ACTIVE' }), queryFn: () => dischargeApi.board({ scope: 'ACTIVE' }), refetchInterval: 60_000 });
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });

  const all = active.data ?? [];
  const ready = all.filter((w) => w.status === 'READY').length;
  const blocked = all.filter((w) => w.blockedSteps > 0).length;
  const oldest = all.reduce((m, w) => Math.max(m, w.ageHours), 0);

  return (
    <>
      <PageHeader
        title="Discharge coordination"
        subtitle="Every discharge in progress with its blockers, owners, age and stage. Refreshes every minute."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Discharge coordination' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="In progress" value={all.length} icon={ClipboardCheck} tone="info" loading={active.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="Ready for departure" value={ready} icon={DoorOpen} tone="success" loading={active.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="With blocked steps" value={blocked} icon={AlertOctagon} tone={blocked ? 'error' : 'neutral'} loading={active.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="Oldest open workflow" value={all.length ? ageLabel(oldest) : '—'} icon={Hourglass} tone="warning" loading={active.isLoading} />
        </Grid>
      </Grid>

      <Card sx={{ mb: 5 }}>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="group" aria-label="Workflow status">
            {(
              [
                ['ACTIVE', 'In progress'],
                ['COMPLETED', 'Completed (30 days)'],
                ['CANCELLED', 'Cancelled (30 days)'],
              ] as const
            ).map(([k, label]) => (
              <Chip key={k} label={label} clickable color={scope === k ? 'primary' : 'default'} variant={scope === k ? 'filled' : 'outlined'} onClick={() => setScope(k)} />
            ))}
          </Stack>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} alignItems={{ md: 'center' }}>
            <TextField
              placeholder="Search patient, MRN or admission no."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
              sx={{ minWidth: { md: 300 } }}
            />
            <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} sx={{ minWidth: { md: 200 } }} disabled={scope !== 'ACTIVE'}>
              <MenuItem value="">All wards</MenuItem>
              {wards.data?.map((w) => (
                <MenuItem key={w.id} value={w.id}>
                  {w.name}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Department" value={departmentId} onChange={(e) => setDepartmentId(e.target.value)} sx={{ minWidth: { md: 200 } }}>
              <MenuItem value="">All departments</MenuItem>
              {departments.data?.map((d) => (
                <MenuItem key={d.id} value={d.id}>
                  {d.name}
                </MenuItem>
              ))}
            </TextField>
            {scope === 'ACTIVE' && (
              <FormControlLabel sx={{ whiteSpace: 'nowrap', flexShrink: 0 }} control={<Switch checked={blockedOnly} onChange={(e) => setBlockedOnly(e.target.checked)} />} label="Blocked only" />
            )}
          </Stack>
        </Stack>
        <DataTable<WorkflowListItem>
          ariaLabel="Discharge workflows"
          rows={board.data}
          loading={board.isFetching}
          error={board.error}
          onRetry={() => board.refetch()}
          rowKey={(w) => w.workflowId}
          onRowClick={(w) => router.push(`/admin/discharges/${w.admissionId}`)}
          emptyTitle={scope === 'ACTIVE' ? 'No discharges in progress' : 'No workflows in this period'}
          emptyDescription={scope === 'ACTIVE' ? 'Discharges started by the care team appear here with their blockers.' : undefined}
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              render: (w) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {w.patient.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {w.patient.mrn} · {w.admissionNumber}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'where',
              header: 'Ward / department',
              render: (w) => (
                <Box>
                  <Typography variant="body2">{w.wardName ? `${w.wardName} · ${w.bedLabel}` : '—'}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {w.departmentName} · {w.attendingDoctorName}
                  </Typography>
                </Box>
              ),
            },
            { key: 'stage', header: 'Stage', render: (w) => (scope === 'ACTIVE' ? <ProgressCell w={w} /> : <StatusChip value={w.status} />) },
            {
              key: 'blockers',
              header: scope === 'ACTIVE' ? 'Blockers' : 'Outcome',
              render: (w) => (scope === 'ACTIVE' ? <BlockersCell w={w} /> : w.disposition ? DISPOSITION_LABEL[w.disposition] : '—'),
            },
            {
              key: 'decisions',
              header: 'Clinical / financial',
              hideOnMobile: true,
              render: (w) => (
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                  {w.clinicalDecision ? <StatusChip value={w.clinicalDecision} /> : <StatusChip value="PENDING" label="Clinical pending" tone="neutral" />}
                  {w.financialClearance ? <StatusChip value={w.financialClearance} /> : <StatusChip value="PENDING" label="Billing pending" tone="neutral" />}
                </Stack>
              ),
            },
            { key: 'age', header: 'Age', render: (w) => ageLabel(w.ageHours) },
            {
              key: 'planned',
              header: scope === 'COMPLETED' ? 'Discharged' : 'Planned',
              hideOnMobile: true,
              render: (w) => formatDateTime(scope === 'COMPLETED' ? w.completedAt : w.plannedDischargeAt),
            },
          ]}
        />
      </Card>
      <DelayReportCard />
    </>
  );
}

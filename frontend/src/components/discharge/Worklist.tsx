'use client';

import { useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, Grid, LinearProgress, Stack, Typography } from '@mui/material';
import { AlertOctagon, CalendarClock, ClipboardCheck, DoorOpen } from 'lucide-react';
import { dischargeApi, dqk, DISPOSITION_LABEL, type WorkflowListItem, type Worklist } from '@/lib/api/discharge';
import type { AdmissionSummary } from '@/lib/api/types';
import { formatDate, formatDateTime, genderShort, todayLocalDate } from '@/lib/format';
import { DataTable } from '@/components/ui/DataTable';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { ageLabel } from './hooks';

type Row = Worklist['active'][number];

export function ProgressCell({ w }: { w: WorkflowListItem }) {
  return (
    <Box sx={{ minWidth: 140 }}>
      <Stack direction="row" justifyContent="space-between">
        <Typography variant="caption" fontWeight={600}>
          {w.stageLabel}
        </Typography>
        <Typography variant="caption" color="text.secondary">
          {w.progress.percent}%
        </Typography>
      </Stack>
      <LinearProgress
        variant="determinate"
        value={w.progress.percent}
        color={w.status === 'READY' ? 'success' : 'primary'}
        sx={{ height: 6, borderRadius: 999, mt: 0.5 }}
        aria-label={`${w.progress.percent}% complete`}
      />
    </Box>
  );
}

export function BlockersCell({ w }: { w: WorkflowListItem }) {
  if (w.status === 'READY') return <StatusChip value="READY" label="Ready for departure" />;
  if (w.blockerCount === 0) return <Typography variant="body2">—</Typography>;
  return (
    <Box sx={{ minWidth: 0 }}>
      <Stack direction="row" spacing={1} alignItems="center">
        {w.blockedSteps > 0 && <StatusChip value="BLOCKED" label={`${w.blockedSteps} blocked`} />}
        <Typography variant="body2" fontWeight={600}>
          {w.blockerCount} open
        </Typography>
      </Stack>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ maxWidth: 220 }} noWrap title={w.topBlockers.join(' · ')}>
        {w.topBlockers.join(' · ')}
      </Typography>
    </Box>
  );
}

/** Clinician discharge worklist (doctor: my patients; nurse: my wards). */
export function DischargeWorklist({ basePath, audience }: { basePath: string; audience: 'doctor' | 'nurse' }) {
  const router = useRouter();
  const q = useQuery({ queryKey: dqk.worklist, queryFn: dischargeApi.worklist });
  const [filter, setFilter] = useState<'all' | 'discharging' | 'ready' | 'notStarted'>('all');
  const today = todayLocalDate();

  const rows = useMemo(() => {
    const all = q.data?.active ?? [];
    return all.filter((r) =>
      filter === 'all' ? true : filter === 'discharging' ? !!r.workflow : filter === 'ready' ? r.workflow?.status === 'READY' : !r.workflow,
    );
  }, [q.data, filter]);

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const active = q.data.active;
  const discharging = active.filter((r) => r.workflow).length;
  const ready = active.filter((r) => r.workflow?.status === 'READY').length;
  const blocked = active.filter((r) => (r.workflow?.blockedSteps ?? 0) > 0).length;
  const dueSoon = active.filter((r) => !r.workflow && r.admission.expectedDischargeDate && r.admission.expectedDischargeDate <= today).length;

  const columns = [
    {
      key: 'patient',
      header: 'Patient',
      primary: true,
      render: (r: Row) => (
        <Box sx={{ minWidth: { md: 170 } }}>
          <Typography variant="body2" fontWeight={600}>
            {r.admission.patient.fullName}
          </Typography>
          <Typography variant="caption" color="text.secondary" component="div">
            {r.admission.patient.mrn} · {r.admission.patient.age}y {genderShort(r.admission.patient.gender)}
          </Typography>
          <Typography variant="caption" color="text.secondary" component="div">
            {r.admission.admissionNumber}
          </Typography>
        </Box>
      ),
    },
    {
      key: 'loc',
      header: 'Location',
      render: (r: Row) =>
        r.admission.location ? (
          <Box>
            <Typography variant="body2">{r.admission.location.wardName}</Typography>
            <Typography variant="caption" color="text.secondary">
              Bed {r.admission.location.roomNumber}-{r.admission.location.bedNumber}
            </Typography>
          </Box>
        ) : (
          '—'
        ),
    },
    {
      key: 'status',
      header: 'Discharge',
      render: (r: Row) => (r.workflow ? <ProgressCell w={r.workflow} /> : <Chip size="small" variant="outlined" label="Not started" />),
    },
    { key: 'blockers', header: 'Blockers', hideOnMobile: true, render: (r: Row) => (r.workflow ? <BlockersCell w={r.workflow} /> : '—') },
    {
      key: 'plan',
      header: 'Planned / decision',
      render: (r: Row) => (
        <Stack spacing={0.5} alignItems={{ xs: 'flex-end', md: 'flex-start' }}>
          <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
            {r.workflow?.plannedDischargeAt
              ? formatDateTime(r.workflow.plannedDischargeAt)
              : r.admission.expectedDischargeDate
                ? `EDD ${formatDate(r.admission.expectedDischargeDate)}`
                : '—'}
          </Typography>
          {r.workflow && (r.workflow.clinicalDecision ? <StatusChip value={r.workflow.clinicalDecision} /> : <Typography variant="caption" color="text.secondary">Clinical decision pending</Typography>)}
        </Stack>
      ),
    },
    {
      key: 'action',
      header: '',
      align: 'right' as const,
      hideOnMobile: true,
      render: (r: Row) => (
        <Button size="small" variant={r.workflow ? 'text' : 'outlined'} sx={{ whiteSpace: 'nowrap' }} onClick={(e) => { e.stopPropagation(); router.push(`${basePath}/${r.admission.id}`); }}>
          {r.workflow ? 'Open' : 'Plan'}
        </Button>
      ),
    },
  ];

  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="In discharge planning" value={discharging} icon={ClipboardCheck} tone="info" />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="Ready for departure" value={ready} icon={DoorOpen} tone="success" />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="With blocked steps" value={blocked} icon={AlertOctagon} tone={blocked ? 'error' : 'neutral'} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 3 }}>
          <KpiCard label="Expected today, not started" value={dueSoon} icon={CalendarClock} tone={dueSoon ? 'warning' : 'neutral'} />
        </Grid>
      </Grid>
      <Card>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ p: 4, pb: 2 }} role="group" aria-label="Filter">
          {(
            [
              ['all', `All (${active.length})`],
              ['discharging', `Discharging (${discharging})`],
              ['ready', `Ready (${ready})`],
              ['notStarted', `Not started (${active.length - discharging})`],
            ] as const
          ).map(([k, label]) => (
            <Chip key={k} label={label} clickable color={filter === k ? 'primary' : 'default'} variant={filter === k ? 'filled' : 'outlined'} onClick={() => setFilter(k)} />
          ))}
        </Stack>
        <DataTable<Row>
          ariaLabel="Discharge worklist"
          rows={rows}
          rowKey={(r) => r.admission.id}
          onRowClick={(r) => router.push(`${basePath}/${r.admission.id}`)}
          emptyTitle={audience === 'doctor' ? 'No current inpatients under your care' : 'No current inpatients on your wards'}
          columns={columns}
        />
      </Card>
      <SectionCard title="Discharged in the last 7 days" flush>
        <DataTable<WorkflowListItem>
          ariaLabel="Recent discharges"
          rows={q.data.recentlyCompleted}
          rowKey={(w) => w.workflowId}
          onRowClick={(w) => router.push(`${basePath}/${w.admissionId}`)}
          emptyTitle="No recent discharges"
          columns={[
            { key: 'patient', header: 'Patient', primary: true, render: (w) => <Box><Typography variant="body2" fontWeight={600}>{w.patient.fullName}</Typography><Typography variant="caption" color="text.secondary">{w.patient.mrn} · {w.admissionNumber}</Typography></Box> },
            { key: 'dept', header: 'Department', render: (w) => w.departmentName ?? '—' },
            { key: 'done', header: 'Discharged', render: (w) => formatDateTime(w.completedAt) },
            { key: 'disp', header: 'Disposition', render: (w) => (w.disposition ? DISPOSITION_LABEL[w.disposition] : '—') },
            { key: 'dur', header: 'Workflow duration', hideOnMobile: true, render: (w) => ageLabel(w.ageHours) },
          ]}
        />
      </SectionCard>
    </Stack>
  );
}

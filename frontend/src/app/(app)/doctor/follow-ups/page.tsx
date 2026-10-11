'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, FormControlLabel, Grid, Stack, Switch, Tab, Tabs, Typography } from '@mui/material';
import { CalendarClock, FlaskConical, Timer } from 'lucide-react';
import { dischargeApi, dqk, type FollowUpView, type PendingResultView } from '@/lib/api/discharge';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { FollowUpCard } from '@/components/discharge/FollowUpPlan';
import { FollowUpDialog, FollowUpOutcomeDialog, type FollowUpPreset } from '@/components/discharge/FollowUpDialogs';

export default function DoctorFollowUpsPage() {
  const [tab, setTab] = useState<'mine' | 'results'>('mine');
  const [includeClosed, setIncludeClosed] = useState(false);
  const mine = useQuery({ queryKey: dqk.myFollowUps(includeClosed), queryFn: () => dischargeApi.myFollowUps(includeClosed) });
  const open = useQuery({ queryKey: dqk.myFollowUps(false), queryFn: () => dischargeApi.myFollowUps(false) });
  const pending = useQuery({ queryKey: dqk.pendingResults, queryFn: dischargeApi.pendingResults });
  const [editing, setEditing] = useState<{ admissionId: string; followUp?: FollowUpView; preset?: FollowUpPreset } | null>(null);
  const [outcome, setOutcome] = useState<{ f: FollowUpView; o: 'COMPLETED' | 'MISSED' | 'CANCELLED' } | null>(null);

  const due = (open.data ?? []).filter((f) => f.outcomeDue).length;
  const unhandled = (pending.data ?? []).filter((p) => !p.followUpId).length;

  return (
    <>
      <PageHeader
        title="Follow-up plans"
        subtitle="Follow-ups you are responsible for or named on, and investigations still awaiting review for patients who are leaving or have left."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Follow-up plans' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Open follow-ups" value={open.data?.length ?? 0} icon={CalendarClock} tone="info" loading={open.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Outcome due" value={due} hint="Scheduled time has passed" icon={Timer} tone={due ? 'warning' : 'neutral'} loading={open.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Results awaiting review" value={pending.data?.length ?? 0} hint={`${unhandled} not yet handed to a follow-up`} icon={FlaskConical} tone={unhandled ? 'error' : 'neutral'} loading={pending.isLoading} />
        </Grid>
      </Grid>
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 4 }} variant="scrollable" allowScrollButtonsMobile>
          <Tab value="mine" label="My follow-ups" />
          <Tab value="results" label="Pending result reviews" />
        </Tabs>
      </Card>

      {tab === 'mine' && (
        <Card>
          <Stack direction="row" justifyContent="flex-end" sx={{ px: 4, pt: 3 }}>
            <FormControlLabel control={<Switch checked={includeClosed} onChange={(e) => setIncludeClosed(e.target.checked)} />} label="Include completed, missed and cancelled" />
          </Stack>
          <Box sx={{ p: 4, pt: 2 }}>
            {mine.isLoading ? (
              <TableSkeleton rows={4} columns={3} />
            ) : mine.error ? (
              <ErrorState error={mine.error} onRetry={() => mine.refetch()} />
            ) : !mine.data?.length ? (
              <EmptyState icon={CalendarClock} title="No follow-up responsibilities" description="Follow-ups where you are the responsible clinician or the named doctor appear here." compact />
            ) : (
              <Stack spacing={3}>
                {mine.data.map((f) => (
                  <FollowUpCard
                    key={f.id}
                    f={f}
                    canManage
                    showPatient
                    patientHref={f.admissionId ? `/doctor/discharge/${f.admissionId}` : undefined}
                    onEdit={f.admissionId ? (x) => setEditing({ admissionId: x.admissionId!, followUp: x }) : undefined}
                    onOutcome={(x, o) => setOutcome({ f: x, o })}
                  />
                ))}
              </Stack>
            )}
          </Box>
        </Card>
      )}

      {tab === 'results' && (
        <Card>
          <DataTable<PendingResultView>
            ariaLabel="Pending result reviews"
            rows={pending.data}
            loading={pending.isFetching}
            error={pending.error}
            onRetry={() => pending.refetch()}
            rowKey={(r) => r.labOrderId}
            emptyTitle="No results awaiting review"
            emptyDescription="Investigations of your discharging and recently discharged patients that are not yet reviewed appear here."
            columns={[
              {
                key: 'test',
                header: 'Investigation',
                primary: true,
                render: (r) => (
                  <Box>
                    <Stack direction="row" spacing={1} alignItems="center">
                      <Typography variant="body2" fontWeight={600}>
                        {r.testName}
                      </Typography>
                      {r.abnormal && <StatusChip value="ABNORMAL" />}
                    </Stack>
                    <Typography variant="caption" color="text.secondary">
                      {r.orderNumber} · ordered {formatDateTime(r.orderedAt)}
                    </Typography>
                  </Box>
                ),
              },
              {
                key: 'patient',
                header: 'Patient',
                render: (r) => (
                  <Box>
                    <Box component={Link} href={`/doctor/discharge/${r.admissionId}`} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none', fontSize: '0.875rem' }}>
                      {r.patientName}
                    </Box>
                    <Typography variant="caption" color="text.secondary" component="div">
                      {r.patientMrn} · {r.admissionStatus === 'DISCHARGED' ? `discharged ${formatDateTime(r.dischargedAt)}` : 'discharge in progress'}
                    </Typography>
                  </Box>
                ),
              },
              { key: 'status', header: 'Result status', render: (r) => <StatusChip value={r.status} /> },
              {
                key: 'handover',
                header: 'Handled by',
                render: (r) =>
                  r.followUpId ? (
                    <Box>
                      <Typography variant="body2">{r.followUpResponsibleName}</Typography>
                      <Typography variant="caption" color="text.secondary">
                        Result-review follow-up · {r.followUpStatus?.toLowerCase()}
                      </Typography>
                    </Box>
                  ) : (
                    <Button
                      size="small"
                      variant="outlined"
                      onClick={() => setEditing({ admissionId: r.admissionId, preset: { type: 'RESULT_REVIEW', title: `Review ${r.testName}`, labOrderId: r.labOrderId } })}
                    >
                      Hand to follow-up
                    </Button>
                  ),
              },
            ]}
          />
        </Card>
      )}

      <FollowUpDialog
        open={!!editing}
        admissionId={editing?.admissionId ?? ''}
        followUp={editing?.followUp}
        preset={editing?.preset}
        onClose={() => setEditing(null)}
      />
      <FollowUpOutcomeDialog followUp={outcome?.f ?? null} outcome={outcome?.o ?? null} onClose={() => setOutcome(null)} />
    </>
  );
}

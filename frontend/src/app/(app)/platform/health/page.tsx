'use client';

import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Grid, LinearProgress, Stack, Typography } from '@mui/material';
import { Activity, Cpu, Database, RefreshCw, Users } from 'lucide-react';
import { platformApi } from '@/lib/api/platform';
import { formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { DefinitionList } from '@/components/ui/Display';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { humanDuration } from '@/components/platform/format';

export default function HealthPage() {
  const q = useQuery({ queryKey: ['platform', 'health'], queryFn: platformApi.health, refetchInterval: 30_000 });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const h = q.data!;
  const heapPct = h.heapMaxMb > 0 ? Math.round((h.heapUsedMb / h.heapMaxMb) * 100) : 0;
  return (
    <>
      <PageHeader
        title="System health"
        subtitle={`Checked ${formatDateTime(h.checkedAt, 'HH:mm:ss')} · refreshes every 30 seconds`}
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'System health' }]}
        actions={
          <Button variant="outlined" startIcon={<RefreshCw size={16} />} onClick={() => q.refetch()} disabled={q.isFetching}>
            Refresh
          </Button>
        }
      />
      {h.status !== 'UP' && (
        <Alert severity="error" sx={{ mb: 5 }}>
          The application reports status {h.status}.
        </Alert>
      )}
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Application" value={<StatusChip value={h.status} size="medium" />} icon={Activity} tone={h.status === 'UP' ? 'success' : 'error'} hint={`Up ${humanDuration(h.uptimeSeconds)}`} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Database" value={h.databaseUp ? `${h.databaseLatencyMs} ms` : 'Down'} icon={Database} tone={h.databaseUp ? 'success' : 'error'} hint={h.databaseUp ? 'Round-trip latency' : 'Unreachable'} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Schema" value={`V${h.flywayCurrentVersion ?? '?'}`} icon={Database} tone={h.flywayAllSucceeded ? 'info' : 'error'} hint={`${h.flywayApplied} migrations${h.flywayAllSucceeded ? ', all succeeded' : ', some failed'}`} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Active sessions" value={h.activeSessions} icon={Users} tone="neutral" hint={`${h.users} accounts · ${h.organizations} hospitals`} />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Runtime">
            <Stack spacing={4}>
              <Box>
                <Stack direction="row" justifyContent="space-between">
                  <Typography variant="body2">Heap memory</Typography>
                  <Typography variant="body2" fontWeight={600}>
                    {h.heapUsedMb} / {h.heapMaxMb} MB
                  </Typography>
                </Stack>
                <LinearProgress variant="determinate" value={heapPct} color={heapPct > 85 ? 'error' : 'primary'} sx={{ mt: 1, height: 8, borderRadius: 4 }} aria-label={`Heap ${heapPct}% used`} />
              </Box>
              <DefinitionList
                items={[
                  { label: 'Java', value: h.javaVersion },
                  { label: 'Processors', value: h.availableProcessors },
                  { label: 'Live threads', value: h.liveThreads },
                  { label: 'Uptime', value: humanDuration(h.uptimeSeconds) },
                  { label: 'Database', value: h.databaseVersion?.split(' on ')[0] ?? '—', span: 2 },
                  { label: 'Demo data', value: h.demoMode ? 'Enabled (fictional data)' : 'Disabled' },
                  { label: 'Dev outbox', value: h.devOutboxEnabled ? 'Enabled — no real email/SMS' : 'Disabled' },
                ]}
              />
            </Stack>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Scheduled jobs" subtitle="Last run of each background job in this application instance (resets on restart)" flush>
            {h.jobs.length === 0 ? (
              <Box sx={{ p: 4 }}>
                <EmptyState icon={Cpu} title="No scheduled job has run yet" compact />
              </Box>
            ) : (
              <DataTable
                ariaLabel="Scheduled jobs"
                rows={h.jobs}
                rowKey={(j) => j.job}
                columns={[
                  { key: 'job', header: 'Job', primary: true, render: (j) => <Typography variant="body2" sx={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>{j.job}</Typography> },
                  { key: 'last', header: 'Last run', render: (j) => fromNow(j.lastStartedAt) },
                  { key: 'dur', header: 'Duration', align: 'right', render: (j) => `${j.lastDurationMs} ms` },
                  { key: 'runs', header: 'Runs / failures', align: 'right', render: (j) => `${j.runs} / ${j.failures}` },
                  { key: 'ok', header: 'Last result', render: (j) => <StatusChip value={j.lastSucceeded ? 'SUCCESS' : 'FAILURE'} label={j.lastSucceeded ? 'Succeeded' : j.lastError ?? 'Failed'} /> },
                ]}
              />
            )}
          </SectionCard>
        </Grid>
        <Grid size={12}>
          <SectionCard title="Database migrations" subtitle="Flyway schema history" flush>
            <DataTable
              ariaLabel="Migrations"
              dense
              rows={h.migrations}
              rowKey={(m) => String(m.rank)}
              columns={[
                { key: 'v', header: 'Version', primary: true, render: (m) => `V${m.version ?? '-'}` },
                { key: 'd', header: 'Description', render: (m) => m.description },
                { key: 'i', header: 'Installed', render: (m) => formatDateTime(m.installedOn) },
                { key: 't', header: 'Time', align: 'right', render: (m) => `${m.executionTimeMs} ms` },
                { key: 's', header: 'Result', render: (m) => <StatusChip value={m.success ? 'SUCCESS' : 'FAILURE'} /> },
              ]}
            />
          </SectionCard>
        </Grid>
      </Grid>
    </>
  );
}

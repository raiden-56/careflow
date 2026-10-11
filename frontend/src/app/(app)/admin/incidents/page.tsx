'use client';

import { Suspense, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter, useSearchParams } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Button, Card, Chip, Grid, InputAdornment, MenuItem, Stack, TextField } from '@mui/material';
import { AlertOctagon, ClipboardCheck, Search, ShieldAlert, ShieldPlus, Timer } from 'lucide-react';
import { incidentApi, IncidentStatus } from '@/lib/api/admin';
import { useTableState } from '@/lib/hooks';
import { humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { sortParam } from '@/components/ui/DataTable';
import { IncidentDrawer } from '@/components/admin/IncidentDrawer';
import { INCIDENT_TYPES, IncidentTable, SEVERITIES } from '@/components/admin/IncidentTable';

const STATUS_FILTERS: { label: string; value: IncidentStatus | '' }[] = [
  { label: 'All', value: '' },
  { label: 'Reported', value: 'REPORTED' },
  { label: 'Under review', value: 'UNDER_REVIEW' },
  { label: 'Action required', value: 'ACTION_REQUIRED' },
  { label: 'Closed', value: 'CLOSED' },
];

export default function AdminIncidentsPage() {
  return (
    <Suspense>
      <Content />
    </Suspense>
  );
}

function Content() {
  const router = useRouter();
  const search = useSearchParams();
  const t = useTableState({ sort: { field: 'reportedAt', direction: 'desc' } });
  const [status, setStatus] = useState<IncidentStatus | ''>('');
  const [severity, setSeverity] = useState('');
  const [type, setType] = useState('');
  const [open, setOpen] = useState<string | null>(search.get('id'));
  useEffect(() => {
    const id = search.get('id');
    if (id) setOpen(id);
  }, [search]);
  const params = { q: t.q, status: status || undefined, severity: severity || undefined, type: type || undefined, page: t.page, size: t.size, sort: sortParam(t.sort) };
  const q = useQuery({ queryKey: ['incidents', 'all', params], queryFn: () => incidentApi.all(params), placeholderData: keepPreviousData });
  const stats = useQuery({ queryKey: ['incidents', 'stats'], queryFn: incidentApi.stats });

  return (
    <>
      <PageHeader
        title="Safety incidents"
        subtitle="Review reported incidents, assign reviewers and record findings and corrective actions. Reports are confidential to reporters and reviewers."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Safety incidents' }]}
        actions={
          <Button component={Link} href="/incidents" variant="outlined" startIcon={<ShieldPlus size={16} />}>
            Report an incident
          </Button>
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Awaiting review" value={stats.data?.reported ?? 0} icon={ShieldAlert} tone="warning" loading={stats.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Under review" value={stats.data?.underReview ?? 0} icon={Timer} tone="info" loading={stats.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Action required" value={stats.data?.actionRequired ?? 0} icon={AlertOctagon} tone="error" loading={stats.isLoading} hint={stats.data ? `${stats.data.severeOpen} severe still open` : undefined} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Closed" value={stats.data?.closed ?? 0} icon={ClipboardCheck} tone="success" loading={stats.isLoading} hint={stats.data ? `${stats.data.reportedLast30Days} reported in 30 days` : undefined} />
        </Grid>
      </Grid>
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {STATUS_FILTERS.map((f) => (
              <Chip
                key={f.label}
                label={f.label}
                clickable
                color={status === f.value ? 'primary' : 'default'}
                variant={status === f.value ? 'filled' : 'outlined'}
                onClick={() => { setStatus(f.value); t.setPage(0); }}
              />
            ))}
          </Stack>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
            <TextField
              placeholder="Search number, description or location"
              value={t.search}
              onChange={(e) => t.setSearch(e.target.value)}
              aria-label="Search incidents"
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
              sx={{ flex: 1, maxWidth: { md: 380 } }}
            />
            <TextField select label="Severity" value={severity} onChange={(e) => { setSeverity(e.target.value); t.setPage(0); }} sx={{ minWidth: 170 }}>
              <MenuItem value="">Any severity</MenuItem>
              {SEVERITIES.map((s) => (
                <MenuItem key={s} value={s}>
                  {humanize(s)}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Type" value={type} onChange={(e) => { setType(e.target.value); t.setPage(0); }} sx={{ minWidth: 200 }}>
              <MenuItem value="">Any type</MenuItem>
              {INCIDENT_TYPES.map((s) => (
                <MenuItem key={s} value={s}>
                  {humanize(s)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Stack>
        <IncidentTable rows={q.data?.content} loading={q.isFetching} error={q.error} onRetry={() => q.refetch()} onOpen={setOpen} table={t} total={q.data?.totalElements} showReporter />
      </Card>
      <IncidentDrawer
        id={open}
        onClose={() => {
          setOpen(null);
          if (search.get('id')) router.replace('/admin/incidents', { scroll: false });
        }}
      />
    </>
  );
}

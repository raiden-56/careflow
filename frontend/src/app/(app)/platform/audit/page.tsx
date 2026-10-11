'use client';

import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Alert, Box, Card, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Activity, KeyRound, Lock, ShieldAlert } from 'lucide-react';
import dayjs from 'dayjs';
import { platformApi, SECURITY_CATEGORIES, SecurityEvent } from '@/lib/api/platform';
import { useDebouncedValue } from '@/lib/hooks';
import { formatDateTime, humanize, localInputToIso, nowInHospitalTz } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';

export default function PlatformAuditPage() {
  return (
    <Suspense>
      <Content />
    </Suspense>
  );
}

function Content() {
  const params0 = useSearchParams();
  const [category, setCategory] = useState(params0.get('category') ?? '');
  const [org, setOrg] = useState('');
  const [outcome, setOutcome] = useState('');
  const [from, setFrom] = useState(nowInHospitalTz().subtract(7, 'day').format('YYYY-MM-DDTHH:mm'));
  const [to, setTo] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(25);
  const q = useDebouncedValue(search, 300);
  const invalid = !!to && !!from && dayjs(to).isBefore(dayjs(from));
  const params = {
    category: category || undefined,
    organizationId: org || undefined,
    outcome: outcome || undefined,
    from: from ? localInputToIso(from) : undefined,
    to: to ? localInputToIso(to) : undefined,
    q: q || undefined,
    page,
    size,
  };
  const summary = useQuery({ queryKey: ['platform', 'security-summary'], queryFn: platformApi.securitySummary, refetchInterval: 60_000 });
  const orgs = useQuery({ queryKey: ['platform', 'orgs', { size: 100 }], queryFn: () => platformApi.organizations({ size: 100 }) });
  const list = useQuery({ queryKey: ['platform', 'security', params], queryFn: () => platformApi.securityEvents(params), placeholderData: keepPreviousData, enabled: !invalid });
  const s = summary.data;
  return (
    <>
      <PageHeader
        title="Security & audit"
        subtitle="Platform-wide security signals: failed and blocked sign-ins, denied access, token reuse and privileged changes across all hospitals."
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'Security & audit' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Failed sign-ins (24 h)" value={s?.failedLogins24h ?? 0} icon={KeyRound} tone={s?.failedLogins24h ? 'warning' : 'neutral'} hint={s ? `${s.failedLogins7d} in 7 days` : undefined} loading={summary.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Blocked sign-ins (24 h)" value={s?.lockouts24h ?? 0} icon={Lock} tone={s?.lockouts24h ? 'warning' : 'neutral'} loading={summary.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Denied access (24 h)" value={s?.deniedAccess24h ?? 0} icon={ShieldAlert} tone={s?.deniedAccess24h ? 'error' : 'neutral'} hint={s ? `${s.deniedAccess7d} in 7 days` : undefined} loading={summary.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, lg: 3 }}>
          <KpiCard label="Token reuse (7 days)" value={s?.sessionReuse7d ?? 0} icon={Activity} tone={s?.sessionReuse7d ? 'error' : 'neutral'} loading={summary.isLoading} />
        </Grid>
      </Grid>
      <Alert severity="info" sx={{ mb: 4 }}>
        Events show who, where and what kind of action — never patient identities or record contents.
      </Alert>
      <Card>
        <Box sx={{ p: 4, display: 'grid', gap: 3, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: 'repeat(6, minmax(0, 1fr))' } }}>
          <TextField select label="Signal" value={category} onChange={(e) => { setCategory(e.target.value); setPage(0); }}>
            <MenuItem value="">All security signals</MenuItem>
            {SECURITY_CATEGORIES.map((c) => (
              <MenuItem key={c.value} value={c.value}>
                {c.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Hospital" value={org} onChange={(e) => { setOrg(e.target.value); setPage(0); }}>
            <MenuItem value="">All hospitals</MenuItem>
            {(orgs.data?.content ?? []).map((o) => (
              <MenuItem key={o.id} value={o.id}>
                {o.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Outcome" value={outcome} onChange={(e) => { setOutcome(e.target.value); setPage(0); }}>
            <MenuItem value="">Any</MenuItem>
            <MenuItem value="SUCCESS">Success</MenuItem>
            <MenuItem value="DENIED">Denied</MenuItem>
            <MenuItem value="FAILURE">Failed</MenuItem>
          </TextField>
          <TextField label="From" type="datetime-local" value={from} onChange={(e) => { setFrom(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
          <TextField label="To" type="datetime-local" value={to} onChange={(e) => { setTo(e.target.value); setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} error={invalid} helperText={invalid ? 'Before start' : undefined} />
          <TextField label="Actor or IP" value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
        </Box>
        <DataTable<SecurityEvent>
          ariaLabel="Security events"
          dense
          rows={list.data?.content}
          loading={list.isFetching}
          error={list.error}
          onRetry={() => list.refetch()}
          rowKey={(e) => e.id}
          page={page}
          pageSize={size}
          total={list.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={(v) => { setSize(v); setPage(0); }}
          pageSizeOptions={[25, 50, 100]}
          emptyTitle="No security events in this window"
          columns={[
            { key: 'when', header: 'When', render: (e) => formatDateTime(e.occurredAt, 'DD MMM YYYY, HH:mm:ss') },
            {
              key: 'event',
              header: 'Event',
              primary: true,
              render: (e) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {humanize(e.action)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {SECURITY_CATEGORIES.find((c) => c.value === e.category)?.label ?? humanize(e.category)}
                  </Typography>
                </Box>
              ),
            },
            { key: 'org', header: 'Hospital', render: (e) => e.organizationName ?? 'Unknown / pre-authentication' },
            { key: 'actor', header: 'Actor', render: (e) => <Box sx={{ wordBreak: 'break-all' }}>{e.actorName ?? '—'}{e.actorRole ? ` (${humanize(e.actorRole)})` : ''}</Box> },
            { key: 'outcome', header: 'Outcome', render: (e) => <StatusChip value={e.outcome} /> },
            { key: 'summary', header: 'Summary', hideOnMobile: true, render: (e) => e.summary ?? '—' },
            { key: 'ip', header: 'IP', hideOnMobile: true, render: (e) => e.ipAddress ?? '—' },
          ]}
        />
      </Card>
      <Stack sx={{ mt: 2 }}>
        <Typography variant="caption" color="text.secondary">
          Times are shown in the platform time zone. Sign-in failures for unknown accounts are not linked to a hospital.
        </Typography>
      </Stack>
    </>
  );
}

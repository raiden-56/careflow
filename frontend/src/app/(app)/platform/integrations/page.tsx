'use client';

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Card, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { KeyRound, PlugZap } from 'lucide-react';
import { IntegrationRow, platformApi } from '@/lib/api/platform';
import { fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';

export default function PlatformIntegrationsPage() {
  const [org, setOrg] = useState('');
  const [type, setType] = useState('');
  const orgs = useQuery({ queryKey: ['platform', 'orgs', { size: 100 }], queryFn: () => platformApi.organizations({ size: 100 }) });
  const q = useQuery({ queryKey: ['platform', 'integrations', org], queryFn: () => platformApi.integrations(org || undefined) });
  const rows = useMemo(() => (q.data ?? []).filter((r) => !type || r.integrationType === type), [q.data, type]);
  const counts = useMemo(() => {
    const c: Record<string, number> = { DISABLED: 0, DEV_ADAPTER: 0, SANDBOX: 0, LIVE: 0 };
    (q.data ?? []).forEach((r) => (c[r.mode] = (c[r.mode] ?? 0) + 1));
    return c;
  }, [q.data]);
  const types = useMemo(() => [...new Set((q.data ?? []).map((r) => r.integrationType))], [q.data]);
  return (
    <>
      <PageHeader
        title="Integrations"
        subtitle="Integration boundaries configured by each hospital (ABDM, FHIR, LIS, payments, SMS, email, insurance/TPA). Read-only overview."
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'Integrations' }]}
      />
      <Alert severity="warning" icon={<PlugZap size={18} />} sx={{ mb: 5 }}>
        No live external integration is implemented in this build. Development adapters keep messages in the dev outbox; sandbox and live modes only record configuration.
        Secrets are never stored — hospitals reference secret names only.
      </Alert>
      <Grid container spacing={4} sx={{ mb: 5 }}>
        {(['DISABLED', 'DEV_ADAPTER', 'SANDBOX', 'LIVE'] as const).map((m) => (
          <Grid key={m} size={{ xs: 6, lg: 3 }}>
            <KpiCard label={humanize(m)} value={counts[m] ?? 0} icon={PlugZap} tone={m === 'LIVE' ? 'error' : m === 'SANDBOX' ? 'warning' : m === 'DEV_ADAPTER' ? 'info' : 'neutral'} loading={q.isLoading} />
          </Grid>
        ))}
      </Grid>
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField select label="Hospital" value={org} onChange={(e) => setOrg(e.target.value)} sx={{ minWidth: 260 }}>
            <MenuItem value="">All hospitals</MenuItem>
            {(orgs.data?.content ?? []).map((o) => (
              <MenuItem key={o.id} value={o.id}>
                {o.name}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Boundary" value={type} onChange={(e) => setType(e.target.value)} sx={{ minWidth: 220 }}>
            <MenuItem value="">All boundaries</MenuItem>
            {types.map((t) => (
              <MenuItem key={t} value={t}>
                {humanize(t)}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <DataTable<IntegrationRow>
          ariaLabel="Integration boundaries"
          rows={q.data ? rows : undefined}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(r) => r.organizationId + r.integrationType}
          emptyTitle="No integration boundaries match"
          columns={[
            {
              key: 'boundary',
              header: 'Boundary',
              primary: true,
              render: (r) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {r.label}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {r.adapterStatus}
                  </Typography>
                </Box>
              ),
            },
            { key: 'org', header: 'Hospital', render: (r) => `${r.organizationName} (${r.organizationCode})` },
            { key: 'mode', header: 'Mode', render: (r) => <StatusChip value={r.mode} /> },
            { key: 'endpoint', header: 'Endpoint', hideOnMobile: true, render: (r) => <Box sx={{ wordBreak: 'break-all' }}>{r.endpointUrl ?? '—'}</Box> },
            {
              key: 'secret',
              header: 'Secret',
              render: (r) =>
                r.secretReferenced ? (
                  <Stack direction="row" spacing={1} alignItems="center">
                    <KeyRound size={14} aria-hidden />
                    <Typography variant="body2">Referenced</Typography>
                  </Stack>
                ) : (
                  '—'
                ),
            },
            { key: 'upd', header: 'Updated', render: (r) => fromNow(r.updatedAt) },
          ]}
        />
      </Card>
    </>
  );
}

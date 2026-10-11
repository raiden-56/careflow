'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Divider, Grid, Stack, Typography } from '@mui/material';
import { Activity, Building2, KeyRound, Lock, LogIn, PlugZap, ShieldAlert, UserCog, Users } from 'lucide-react';
import { platformApi } from '@/lib/api/platform';
import { fromNow } from '@/lib/format';
import { humanDuration } from '@/components/platform/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';

export default function PlatformOverviewPage() {
  const q = useQuery({ queryKey: ['platform', 'overview'], queryFn: platformApi.overview, refetchInterval: 60_000 });
  const health = useQuery({ queryKey: ['platform', 'health'], queryFn: platformApi.health });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const o = q.data!;
  return (
    <>
      <PageHeader
        title="Platform overview"
        subtitle="Organizations, administrator accounts, security signals and system health. The platform team never sees patient, clinical or financial records."
        breadcrumbs={[{ label: 'Platform' }]}
        actions={
          <Button component={Link} href="/platform/organizations" variant="contained" startIcon={<Building2 size={16} />}>
            Organizations
          </Button>
        }
      />
      {health.data && health.data.status !== 'UP' && (
        <Alert severity="error" sx={{ mb: 5 }} action={<Button component={Link} href="/platform/health" color="inherit" size="small">Details</Button>}>
          System health is {health.data.status}.
        </Alert>
      )}
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Hospitals" value={o.organizations} icon={Building2} hint={`${o.activeOrganizations} active · ${o.suspendedOrganizations} suspended`} href="/platform/organizations" />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Hospital administrators" value={o.hospitalAdmins} icon={UserCog} tone="info" href="/platform/users" />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Staff accounts" value={o.staffUsers} icon={Users} tone="secondary" hint="Across all hospitals" />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 3 }}>
          <KpiCard label="Active sessions" value={o.activeSessions} icon={LogIn} tone="neutral" hint="Unrevoked, unexpired refresh sessions" />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="Security signals" subtitle="Last 24 hours unless noted" action={<Button component={Link} href="/platform/audit" size="small">Investigate</Button>}>
            <Stack spacing={3}>
              <Signal icon={KeyRound} label="Failed sign-ins (24 h)" value={o.security.failedLogins24h} detail={`${o.security.failedLogins7d} in 7 days`} href="/platform/audit?category=FAILED_LOGIN" />
              <Signal icon={Lock} label="Blocked / locked sign-ins (24 h)" value={o.security.lockouts24h} href="/platform/audit?category=LOCKOUT" />
              <Signal icon={ShieldAlert} label="Denied access attempts (24 h)" value={o.security.deniedAccess24h} detail={`${o.security.deniedAccess7d} in 7 days`} href="/platform/audit?category=ACCESS_DENIED" />
              <Signal icon={Activity} label="Session token reuse (7 days)" value={o.security.sessionReuse7d} critical href="/platform/audit?category=SESSION_REUSE" />
            </Stack>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="Recently registered hospitals" action={<Button component={Link} href="/platform/organizations" size="small">All</Button>}>
            {o.recentOrganizations.length === 0 ? (
              <EmptyState icon={Building2} title="No hospitals registered" compact />
            ) : (
              <Stack divider={<Divider flexItem />}>
                {o.recentOrganizations.map((org) => (
                  <Stack key={org.id} direction="row" alignItems="center" spacing={2} sx={{ py: 2 }}>
                    <Box sx={{ flex: 1, minWidth: 0 }}>
                      <Typography variant="body2" fontWeight={600}>
                        {org.name}
                      </Typography>
                      <Typography variant="caption" color="text.secondary">
                        {org.code} · {org.city ?? '—'} · {org.staffUsers} staff · {org.beds} beds · last sign-in {org.lastLoginAt ? fromNow(org.lastLoginAt) : 'never'}
                      </Typography>
                    </Box>
                    <StatusChip value={org.status} />
                  </Stack>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="Integration boundaries" subtitle="Configured modes across all hospitals" action={<Button component={Link} href="/platform/integrations" size="small">Details</Button>}>
            <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
              {Object.entries(o.integrationModes).map(([mode, n]) => (
                <Stack key={mode} direction="row" spacing={1} alignItems="center">
                  <StatusChip value={mode} />
                  <Typography variant="body2" fontWeight={600}>
                    {n}
                  </Typography>
                </Stack>
              ))}
            </Stack>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 3 }}>
              <PlugZap size={12} style={{ verticalAlign: 'middle', marginRight: 4 }} />
              No live connector is implemented in this build; LIVE only records an administrator&apos;s declared intent.
            </Typography>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="System" action={<Button component={Link} href="/platform/health" size="small">Health</Button>}>
            {health.data ? (
              <Stack spacing={2}>
                <Row label="Application" value={<StatusChip value={health.data.status} />} />
                <Row label="Database" value={health.data.databaseUp ? `Connected · ${health.data.databaseLatencyMs} ms` : 'Unreachable'} />
                <Row label="Schema version" value={`V${health.data.flywayCurrentVersion ?? '?'} · ${health.data.flywayApplied} migrations`} />
                <Row label="Uptime" value={humanDuration(health.data.uptimeSeconds)} />
                <Row label="Mode" value={health.data.demoMode ? 'Demonstration data enabled' : 'Production data'} />
              </Stack>
            ) : health.error ? (
              <ErrorState error={health.error} />
            ) : (
              <Typography variant="body2" color="text.secondary">
                Checking…
              </Typography>
            )}
          </SectionCard>
        </Grid>
      </Grid>
    </>
  );
}

function Signal({ icon: Icon, label, value, detail, href, critical }: { icon: typeof Lock; label: string; value: number; detail?: string; href: string; critical?: boolean }) {
  const hot = value > 0;
  return (
    <Stack direction="row" spacing={3} alignItems="center" component={Link} href={href} sx={{ textDecoration: 'none', color: 'inherit', borderRadius: 2, '&:hover': { bgcolor: 'action.hover' }, p: 1 }}>
      <Box sx={{ color: hot ? (critical ? 'error.main' : 'warning.main') : 'text.disabled', display: 'grid' }} aria-hidden>
        <Icon size={18} />
      </Box>
      <Box sx={{ flex: 1 }}>
        <Typography variant="body2">{label}</Typography>
        {detail && (
          <Typography variant="caption" color="text.secondary">
            {detail}
          </Typography>
        )}
      </Box>
      <Typography variant="h4" component="span" color={hot && critical ? 'error.main' : 'text.primary'} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {value}
      </Typography>
    </Stack>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Stack direction="row" justifyContent="space-between" spacing={2} alignItems="center">
      <Typography variant="body2" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="body2" component="div" sx={{ textAlign: 'right' }}>
        {value}
      </Typography>
    </Stack>
  );
}

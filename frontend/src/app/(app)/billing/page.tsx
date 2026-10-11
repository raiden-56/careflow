'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Grid, LinearProgress, Stack, Typography } from '@mui/material';
import { AlertTriangle, CreditCard, FileClock, FileSpreadsheet, MessageSquareWarning, Scale, Timer, Wallet } from 'lucide-react';
import { billingApi, billingKeys, METHOD_LABEL } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime, formatINR, pluralize } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { DailyBars } from '@/components/billing/Charts';

const BUCKET_LABEL: Record<string, string> = { '0-30': '0–30 days', '31-60': '31–60 days', '61-90': '61–90 days', '90+': 'Over 90 days' };

export default function BillingDashboard() {
  const { me } = useAuth();
  const q = useQuery({ queryKey: billingKeys.dashboard, queryFn: billingApi.dashboard, refetchInterval: 60_000 });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const d = q.data;
  const maxBucket = Math.max(...d.aging.map((b) => Number(b.amount)), 1);

  return (
    <>
      <PageHeader
        title="Billing dashboard"
        subtitle={`Good to see you, ${me?.fullName.split(' ')[0] ?? ''}. Figures are live from the ledger.`}
        actions={
          <>
            <Button component={Link} href="/billing/invoices" variant="outlined" startIcon={<FileSpreadsheet size={16} />}>
              Invoices
            </Button>
            <Button component={Link} href="/billing/payments" variant="contained" startIcon={<CreditCard size={16} />}>
              Collect payment
            </Button>
          </>
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Today's collections"
            value={formatINR(d.todaysCollections)}
            hint={`${pluralize(d.todaysPaymentCount, 'receipt')} today, net of reversals`}
            icon={Wallet}
            tone="success"
            href="/billing/payments"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Outstanding receivables"
            value={formatINR(d.outstandingTotal)}
            hint={`${pluralize(d.outstandingCount, 'invoice')} with a balance due`}
            icon={Timer}
            tone="warning"
            href="/billing/outstanding"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Unpaid discharged invoices"
            value={d.unpaidDischargedCount}
            hint={`${formatINR(d.unpaidDischargedAmount)} still due after discharge`}
            icon={AlertTriangle}
            tone={d.unpaidDischargedCount ? 'error' : 'neutral'}
            href="/billing/outstanding"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Adjustments awaiting approval"
            value={d.pendingApprovals}
            hint={d.pendingApprovals ? `${formatINR(d.pendingApprovalAmount)} requested` : 'Nothing waiting'}
            icon={Scale}
            tone={d.pendingApprovals ? 'warning' : 'neutral'}
            href="/billing/adjustments"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Open insurance claims"
            value={d.openClaims}
            hint={`${formatINR(d.claimsPendingSettlement)} approved, pending settlement`}
            icon={FileClock}
            tone="info"
            href="/billing/claims"
          />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, lg: 4 }}>
          <KpiCard
            label="Patient queries"
            value={d.openRequests}
            hint={d.openRequests ? 'Open or in review' : 'All answered'}
            icon={MessageSquareWarning}
            tone={d.openRequests ? 'secondary' : 'neutral'}
            href="/billing/requests"
          />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <Stack spacing={5}>
            <SectionCard
              title="Collections, last 14 days"
              subtitle="Payments received less payment reversals, by day"
              action={
                <Button component={Link} href="/billing/reports" size="small">
                  Reports
                </Button>
              }
            >
              <DailyBars data={d.collectionsTrend} name="Net collections" />
            </SectionCard>
            <SectionCard title="Recent payments" action={<Button component={Link} href="/billing/payments" size="small">All payments</Button>}>
              {d.recentPayments.length === 0 ? (
                <EmptyState title="No payments recorded yet" compact />
              ) : (
                <Stack divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}>
                  {d.recentPayments.map((p) => (
                    <Stack
                      key={p.id}
                      component={Link}
                      href={`/billing/invoices/${p.invoiceId}`}
                      direction="row"
                      justifyContent="space-between"
                      alignItems="center"
                      spacing={2}
                      sx={{ py: 2, textDecoration: 'none', color: 'inherit', '&:hover': { bgcolor: 'action.hover' }, px: 1, borderRadius: 1 }}
                    >
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={600} noWrap>
                          {p.patientName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" noWrap component="div">
                          {p.receiptNumber} · {METHOD_LABEL[p.method]} · {formatDateTime(p.receivedAt)}
                        </Typography>
                      </Box>
                      <Stack alignItems="flex-end" spacing={0.5} sx={{ flexShrink: 0 }}>
                        <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                          {formatINR(p.amount)}
                        </Typography>
                        {p.status !== 'COMPLETED' && <StatusChip value={p.status} />}
                      </Stack>
                    </Stack>
                  ))}
                </Stack>
              )}
            </SectionCard>
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            <SectionCard title="Receivables aging" subtitle="By days since discharge (or invoice date while admitted)" action={<Button component={Link} href="/billing/outstanding" size="small">Details</Button>}>
              <Stack spacing={3}>
                {d.aging.map((b) => (
                  <Box key={b.bucket}>
                    <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
                      <Typography variant="body2">
                        {BUCKET_LABEL[b.bucket]} <Typography component="span" variant="caption" color="text.secondary">· {pluralize(b.count, 'invoice')}</Typography>
                      </Typography>
                      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {formatINR(b.amount)}
                      </Typography>
                    </Stack>
                    <LinearProgress
                      variant="determinate"
                      value={(Number(b.amount) / maxBucket) * 100}
                      aria-label={`${BUCKET_LABEL[b.bucket]} share`}
                      sx={{ height: 8, borderRadius: 4, bgcolor: colors.neutralSoft, '& .MuiLinearProgress-bar': { bgcolor: b.bucket === '0-30' ? colors.primary : colors.warning, borderRadius: 4 } }}
                    />
                  </Box>
                ))}
              </Stack>
            </SectionCard>
            <SectionCard title="Work queues">
              <Stack spacing={2}>
                {[
                  { href: '/billing/adjustments', label: 'Approve adjustments', count: d.pendingApprovals },
                  { href: '/billing/claims', label: 'Follow up insurance claims', count: d.openClaims },
                  { href: '/billing/requests', label: 'Answer patient queries', count: d.openRequests },
                  { href: '/billing/invoices', label: 'Open invoices', count: d.openInvoices },
                ].map((w) => (
                  <Stack
                    key={w.href}
                    component={Link}
                    href={w.href}
                    direction="row"
                    justifyContent="space-between"
                    alignItems="center"
                    sx={{ p: 3, borderRadius: 2, border: 1, borderColor: 'divider', textDecoration: 'none', color: 'inherit', '&:hover': { borderColor: 'primary.main' } }}
                  >
                    <Typography variant="body2" fontWeight={500}>
                      {w.label}
                    </Typography>
                    <Typography variant="body2" fontWeight={700} color={w.count ? 'primary.main' : 'text.secondary'}>
                      {w.count}
                    </Typography>
                  </Stack>
                ))}
              </Stack>
            </SectionCard>
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}

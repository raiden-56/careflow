'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Grid, List, ListItemButton, ListItemText, Stack, Typography } from '@mui/material';
import { AlarmClock, ArrowRight, ClipboardList, IndianRupee, PackageX, Pill } from 'lucide-react';
import { pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDate, formatINR, pluralize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState } from '@/components/ui/States';
import { Skeleton } from '@mui/material';
import { DispensingChart } from '@/components/pharmacy/DispensingChart';
import { QueueTable } from '@/components/pharmacy/QueueTable';
import { OrderReviewDialog } from '@/components/pharmacy/OrderReviewDialog';
import { DispenseDialog } from '@/components/pharmacy/DispenseDialog';

export default function PharmacyDashboardPage() {
  const { hasPermission, me } = useAuth();
  const canDispense = hasPermission('DISPENSE');
  const [review, setReview] = useState<UUID | null>(null);
  const [dispense, setDispense] = useState<UUID | null>(null);
  const dash = useQuery({ queryKey: pharmacyKeys.dashboard, queryFn: () => pharmacyApi.dashboard(), refetchInterval: 60_000 });
  const alerts = useQuery({ queryKey: pharmacyKeys.alerts(90), queryFn: () => pharmacyApi.alerts(90) });
  const queueParams = { filter: 'OUTSTANDING' as const, size: 6 };
  const queue = useQuery({
    queryKey: pharmacyKeys.queue(queueParams),
    queryFn: () => pharmacyApi.queue(queueParams),
    enabled: canDispense,
  });
  const k = dash.data;

  const alertRows = alerts.data
    ? [
        ...alerts.data.expiredOnHand.map((b) => ({
          key: 'e' + b.id,
          primary: `${b.medicationName} · batch ${b.batchNumber}`,
          secondary: `Expired ${formatDate(b.expiryDate)} · ${b.quantityOnHand} still on hand`,
          status: 'EXPIRED',
          href: '/pharmacy/alerts',
        })),
        ...alerts.data.lowStock.map((s) => ({
          key: 'l' + s.medicationId,
          primary: s.name,
          secondary: `${s.usable} ${s.dispenseUnit} usable · reorder level ${s.reorderLevel}`,
          status: s.availability,
          href: '/pharmacy/inventory',
        })),
        ...alerts.data.nearExpiry.map((b) => ({
          key: 'n' + b.id,
          primary: `${b.medicationName} · batch ${b.batchNumber}`,
          secondary: `Expires ${formatDate(b.expiryDate)} (${pluralize(b.daysToExpiry, 'day')}) · ${b.quantityOnHand} on hand`,
          status: 'WARNING',
          href: '/pharmacy/batches',
        })),
        ...alerts.data.quarantined.map((b) => ({
          key: 'q' + b.id,
          primary: `${b.medicationName} · batch ${b.batchNumber}`,
          secondary: `${b.quantityOnHand} units held in quarantine`,
          status: 'QUARANTINED',
          href: '/pharmacy/batches',
        })),
      ]
    : [];

  return (
    <>
      <PageHeader
        title="Pharmacy"
        subtitle={`Good to see you, ${me?.fullName?.split(' ')[0] ?? ''}. Verify new prescriptions, supply the wards and keep stock safe.`}
        breadcrumbs={[{ label: 'Pharmacy' }]}
        actions={
          canDispense && (
            <Button component={Link} href="/pharmacy/prescriptions" variant="contained" startIcon={<ClipboardList size={16} />}>
              Prescription queue
            </Button>
          )
        }
      />
      {dash.error ? (
        <Box sx={{ mb: 5 }}>
          <ErrorState error={dash.error} onRetry={() => dash.refetch()} />
        </Box>
      ) : (
        <Grid container spacing={4} sx={{ mb: 5 }}>
          <Grid size={{ xs: 12, sm: 6, lg: 2.4 }}>
            <KpiCard
              label="Prescription queue"
              value={k?.queueSize ?? '—'}
              icon={ClipboardList}
              tone="info"
              loading={dash.isLoading}
              href="/pharmacy/prescriptions"
              hint={k ? `${k.awaitingVerification} to verify · ${k.readyToDispense} to supply · ${k.openQueries} queried` : undefined}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 2.4 }}>
            <KpiCard
              label="Dispensed today"
              value={k?.dispensedToday ?? '—'}
              icon={Pill}
              tone="primary"
              loading={dash.isLoading}
              href="/pharmacy/dispensing"
              hint={k ? `${k.unitsDispensedToday} units net of returns` : undefined}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 2.4 }}>
            <KpiCard
              label="Low / out of stock"
              value={k ? k.lowStockCount + k.outOfStockCount : '—'}
              icon={PackageX}
              tone={k && k.outOfStockCount > 0 ? 'error' : 'warning'}
              loading={dash.isLoading}
              href="/pharmacy/alerts"
              hint={k ? `${k.lowStockCount} low · ${k.outOfStockCount} out of stock` : undefined}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 6, lg: 2.4 }}>
            <KpiCard
              label={`Expiring ≤ ${k?.nearExpiryDays ?? 90} days`}
              value={k?.nearExpiryCount ?? '—'}
              icon={AlarmClock}
              tone="warning"
              loading={dash.isLoading}
              href="/pharmacy/alerts"
              hint={k ? `${pluralize(k.expiredOnHandCount, 'expired batch', 'expired batches')} on hand · ${k.quarantinedCount} quarantined` : undefined}
            />
          </Grid>
          <Grid size={{ xs: 12, sm: 12, lg: 2.4 }}>
            <KpiCard
              label="Usable stock value"
              value={k ? formatINR(k.stockValue, { compact: true }) : '—'}
              icon={IndianRupee}
              tone="secondary"
              loading={dash.isLoading}
              href="/pharmacy/inventory"
              hint={k ? `${formatINR(k.stockValue)} at cost` : undefined}
            />
          </Grid>
        </Grid>
      )}

      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title="Dispensations, last 7 days" subtitle="Number of supplies to the wards per day">
            {dash.isLoading ? <Skeleton variant="rounded" height={220} /> : k ? <DispensingChart days={k.dispensedLast7Days} /> : null}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard
            title="Stock alerts"
            subtitle="Expired, low, expiring and quarantined stock"
            action={
              <Button component={Link} href="/pharmacy/alerts" size="small" endIcon={<ArrowRight size={14} />}>
                All alerts
              </Button>
            }
            flush
          >
            {alerts.isLoading ? (
              <Box sx={{ p: 5 }}>
                <Skeleton height={180} />
              </Box>
            ) : alerts.error ? (
              <Box sx={{ p: 5 }}>
                <ErrorState error={alerts.error} onRetry={() => alerts.refetch()} />
              </Box>
            ) : alertRows.length === 0 ? (
              <EmptyState title="No stock alerts" description="Stock levels and expiry dates are within limits." compact />
            ) : (
              <List dense disablePadding sx={{ maxHeight: 268, overflowY: 'auto' }}>
                {alertRows.slice(0, 8).map((a) => (
                  <ListItemButton key={a.key} component={Link} href={a.href} sx={{ px: 5, borderTop: 1, borderColor: 'divider' }}>
                    <ListItemText
                      primary={a.primary}
                      secondary={a.secondary}
                      slotProps={{ primary: { variant: 'body2', fontWeight: 500, noWrap: true }, secondary: { variant: 'caption' } }}
                      sx={{ minWidth: 0, mr: 2 }}
                    />
                    <StatusChip value={a.status} label={a.status === 'WARNING' ? 'Near expiry' : undefined} />
                  </ListItemButton>
                ))}
              </List>
            )}
          </SectionCard>
        </Grid>
      </Grid>

      {canDispense && (
        <SectionCard
          title="Next in the queue"
          subtitle="Oldest unverified prescriptions first, then supplies due"
          action={
            <Button component={Link} href="/pharmacy/prescriptions" size="small" endIcon={<ArrowRight size={14} />}>
              Full queue
            </Button>
          }
          flush
        >
          <QueueTable
            rows={queue.data?.content}
            loading={queue.isFetching}
            error={queue.error}
            onRetry={() => queue.refetch()}
            emptyTitle="The queue is clear"
            emptyDescription="Every active prescription has been verified and supplied for this cycle."
            onReview={(i) => setReview(i.orderId)}
            onDispense={(i) => setDispense(i.orderId)}
            canAct={canDispense}
            compact
          />
          {queue.data && queue.data.totalElements > queue.data.content.length && (
            <Typography variant="caption" color="text.secondary" component="div" sx={{ px: 5, py: 3, borderTop: 1, borderColor: 'divider' }}>
              Showing {queue.data.content.length} of {queue.data.totalElements} outstanding prescriptions.
            </Typography>
          )}
        </SectionCard>
      )}

      <OrderReviewDialog
        orderId={review}
        onClose={() => setReview(null)}
        onDispense={(id) => {
          setReview(null);
          setDispense(id);
        }}
      />
      <DispenseDialog orderId={dispense} onClose={() => setDispense(null)} />
    </>
  );
}

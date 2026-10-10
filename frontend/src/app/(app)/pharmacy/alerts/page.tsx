'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { AlarmClock, Ban, PackageX, ShieldAlert } from 'lucide-react';
import { BatchView, pharmacyApi, pharmacyKeys, StockLevel } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable, Column } from '@/components/ui/DataTable';
import { Money } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { AvailabilityChip, ExpiryText, MedicationFlags, QuantityText, useDesktopRowClick } from '@/components/pharmacy/PharmacyBits';
import { BatchActionsMenu } from '@/components/pharmacy/BatchActions';
import { BatchDrawer } from '@/components/pharmacy/Drawers';
import { ReceiveStockDialog } from '@/components/pharmacy/StockDialogs';

export default function StockAlertsPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('INVENTORY_MANAGE');
  const [days, setDays] = useState(90);
  const [history, setHistory] = useState<UUID | null>(null);
  const [receiveFor, setReceiveFor] = useState<string | null>(null);
  const q = useQuery({ queryKey: pharmacyKeys.alerts(days), queryFn: () => pharmacyApi.alerts(days), placeholderData: keepPreviousData });
  const a = q.data;
  const rowClick = useDesktopRowClick<BatchView>((b) => setHistory(b.id));

  const batchColumns = (extra?: Column<BatchView>[]): Column<BatchView>[] => [
    {
      key: 'batch',
      header: 'Medication / batch',
      primary: true,
      render: (b) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" fontWeight={600}>
            {b.medicationName}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            Batch {b.batchNumber} · {b.supplier ?? 'supplier not recorded'}
          </Typography>
        </Box>
      ),
    },
    { key: 'expiry', header: 'Expiry', render: (b) => <ExpiryText date={b.expiryDate} days={b.daysToExpiry} expired={b.expired} /> },
    { key: 'qty', header: 'On hand', align: 'right', render: (b) => <QuantityText value={b.quantityOnHand} unit={b.dispenseUnit} strong /> },
    { key: 'value', header: 'Value at cost', align: 'right', hideOnMobile: true, render: (b) => <Money value={b.stockValue} /> },
    ...(extra ?? []),
    { key: 'actions', header: '', align: 'right', render: (b) => <BatchActionsMenu batch={b} canManage={canManage} onHistory={(x) => setHistory(x.id)} /> },
  ];

  const lowColumns: Column<StockLevel>[] = [
    {
      key: 'name',
      header: 'Medication',
      primary: true,
      render: (s) => (
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="body2" fontWeight={600} component="div">
            {s.name} <MedicationFlags highAlert={s.isHighAlert} controlled={s.isControlled} />
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {s.code}
          </Typography>
        </Box>
      ),
    },
    { key: 'usable', header: 'Usable', render: (s) => <AvailabilityChip value={s.availability} stock={s.usable} unit={s.dispenseUnit} /> },
    { key: 'reorder', header: 'Reorder level', align: 'right', render: (s) => <QuantityText value={s.reorderLevel} /> },
    {
      key: 'held',
      header: 'Held / expired',
      hideOnMobile: true,
      render: (s) => (s.quarantined + s.expiredOnHand > 0 ? `${s.quarantined} quarantined · ${s.expiredOnHand} expired` : '—'),
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      render: (s) => (
        <Stack direction="row" spacing={1} justifyContent="flex-end">
          <Button size="small" component={Link} href={`/pharmacy/batches?medicationId=${s.medicationId}`}>
            Batches
          </Button>
          {canManage && (
            <Button size="small" variant="outlined" onClick={() => setReceiveFor(s.medicationId)}>
              Receive
            </Button>
          )}
        </Stack>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Stock alerts"
        subtitle="Items below their reorder level and batches that are expiring, expired or held in quarantine."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Stock alerts' }]}
        actions={
          <TextField select size="small" label="Near-expiry window" value={days} onChange={(e) => setDays(Number(e.target.value))} sx={{ minWidth: 180 }}>
            {[30, 60, 90, 180].map((d) => (
              <MenuItem key={d} value={d}>
                {d} days
              </MenuItem>
            ))}
          </TextField>
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 6, md: 3 }}>
          <KpiCard label="Low / out of stock" value={a?.lowStock.length ?? '—'} icon={PackageX} tone="warning" loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <KpiCard label="Expired on hand" value={a?.expiredOnHand.length ?? '—'} icon={Ban} tone="error" loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <KpiCard label={`Expiring ≤ ${days} days`} value={a?.nearExpiry.length ?? '—'} icon={AlarmClock} tone="warning" loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <KpiCard label="Quarantined" value={a?.quarantined.length ?? '—'} icon={ShieldAlert} tone="info" loading={q.isLoading} />
        </Grid>
      </Grid>
      <Stack spacing={5}>
        <SectionCard title="Low and out of stock" subtitle="Usable stock (active, unexpired) below the reorder level" flush>
          <DataTable<StockLevel>
            ariaLabel="Low stock"
            rows={a?.lowStock}
            loading={q.isFetching}
            error={q.error}
            onRetry={() => q.refetch()}
            rowKey={(s) => s.medicationId}
            columns={lowColumns}
            emptyTitle="All items are above their reorder level"
          />
        </SectionCard>
        <SectionCard title="Expired, still on hand" subtitle="Cannot be dispensed. Write off with a documented reason." flush>
          <DataTable<BatchView>
            ariaLabel="Expired stock"
            rows={a?.expiredOnHand}
            loading={q.isFetching}
            error={q.error}
            rowKey={(b) => b.id}
            onRowClick={rowClick}
            columns={batchColumns()}
            emptyTitle="No expired stock on the shelves"
          />
        </SectionCard>
        <SectionCard title={`Expiring within ${days} days`} subtitle="Dispensed first (FEFO). Consider redistribution or supplier return." flush>
          <DataTable<BatchView>
            ariaLabel="Near expiry"
            rows={a?.nearExpiry}
            loading={q.isFetching}
            error={q.error}
            rowKey={(b) => b.id}
            onRowClick={rowClick}
            columns={batchColumns()}
            emptyTitle={`No batches expire within ${days} days`}
          />
        </SectionCard>
        <SectionCard title="Quarantined" subtitle="Held stock awaiting a release or disposal decision" flush>
          <DataTable<BatchView>
            ariaLabel="Quarantined batches"
            rows={a?.quarantined}
            loading={q.isFetching}
            error={q.error}
            rowKey={(b) => b.id}
            onRowClick={rowClick}
            columns={batchColumns([{ key: 'status', header: 'Status', render: (b) => <StatusChip value={b.status} /> }])}
            emptyTitle="Nothing in quarantine"
          />
        </SectionCard>
        {a && (
          <Typography variant="caption" color="text.secondary">
            Figures as of {formatDate(new Date().toISOString())}. Usable stock excludes expired and quarantined batches.
          </Typography>
        )}
      </Stack>
      <BatchDrawer batchId={history} onClose={() => setHistory(null)} />
      <ReceiveStockDialog open={!!receiveFor} initialMedicationId={receiveFor} onClose={() => setReceiveFor(null)} />
    </>
  );
}

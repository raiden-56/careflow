'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { PackagePlus, Search } from 'lucide-react';
import { BatchStatus, BatchView, medicationApi, MedicationView, pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { Money } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { PageSkeleton } from '@/components/ui/States';
import { ExpiryText, QuantityText, useDesktopRowClick } from '@/components/pharmacy/PharmacyBits';
import { MedicationPicker } from '@/components/pharmacy/MedicationPicker';
import { BatchActionsMenu } from '@/components/pharmacy/BatchActions';
import { BatchDrawer } from '@/components/pharmacy/Drawers';
import { ReceiveStockDialog } from '@/components/pharmacy/StockDialogs';

const STATUS: { label: string; value: BatchStatus[]; includeEmpty: boolean }[] = [
  { label: 'On hand', value: ['ACTIVE', 'QUARANTINED'], includeEmpty: false },
  { label: 'Active', value: ['ACTIVE'], includeEmpty: false },
  { label: 'Quarantined', value: ['QUARANTINED'], includeEmpty: false },
  { label: 'Depleted', value: ['DEPLETED'], includeEmpty: true },
  { label: 'Written off', value: ['EXPIRED'], includeEmpty: true },
  { label: 'All', value: [], includeEmpty: true },
];

function BatchesContent() {
  const router = useRouter();
  const search = useSearchParams();
  const initialMedId = search.get('medicationId');
  const { hasPermission } = useAuth();
  const canManage = hasPermission('INVENTORY_MANAGE');
  const t = useTableState({ sort: { field: 'expiryDate', direction: 'asc' } });
  const [status, setStatus] = useState(0);
  const [expiring, setExpiring] = useState<string>(search.get('expiringWithinDays') ?? '');
  const [medication, setMedication] = useState<MedicationView | null>(null);
  const [history, setHistory] = useState<UUID | null>(null);
  const [receiveOpen, setReceiveOpen] = useState(false);
  const rowClick = useDesktopRowClick<BatchView>((b) => setHistory(b.id));

  const initial = useQuery({
    queryKey: ['medications', 'one', initialMedId],
    queryFn: () => medicationApi.get(initialMedId!),
    enabled: !!initialMedId,
  });
  useEffect(() => {
    if (initial.data) setMedication(initial.data);
  }, [initial.data]);

  const params = {
    medicationId: medication?.id ?? (initial.isLoading ? initialMedId ?? undefined : undefined),
    status: STATUS[status].value,
    includeEmpty: STATUS[status].includeEmpty,
    expiringWithinDays: expiring ? Number(expiring) : undefined,
    q: t.q || undefined,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const q = useQuery({ queryKey: pharmacyKeys.batches(params), queryFn: () => pharmacyApi.batches(params), placeholderData: keepPreviousData });

  const pickMedication = (m: MedicationView | null) => {
    setMedication(m);
    t.setPage(0);
    router.replace(m ? `/pharmacy/batches?medicationId=${m.id}` : '/pharmacy/batches', { scroll: false });
  };

  return (
    <>
      <PageHeader
        title="Batches & expiry"
        subtitle="Every received batch with its expiry, quantity and status. Quarantine, release and write-offs require a documented reason."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Batches & expiry' }]}
        actions={
          canManage && (
            <Button variant="contained" startIcon={<PackagePlus size={16} />} onClick={() => setReceiveOpen(true)}>
              Receive stock
            </Button>
          )
        }
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {STATUS.map((s, i) => (
              <Chip
                key={s.label}
                label={s.label}
                clickable
                color={status === i ? 'primary' : 'default'}
                variant={status === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setStatus(i);
                  t.setPage(0);
                }}
              />
            ))}
          </Stack>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
            <Box sx={{ flex: 2 }}>
              <MedicationPicker value={medication} onChange={pickMedication} activeOnly={false} label="Medication (all if empty)" />
            </Box>
            <TextField
              placeholder="Batch number or supplier"
              value={t.search}
              onChange={(e) => t.setSearch(e.target.value)}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search batches' } }}
              sx={{ flex: 1.5 }}
            />
            <TextField
              select
              label="Expiring within"
              value={expiring}
              onChange={(e) => {
                setExpiring(e.target.value);
                t.setPage(0);
              }}
              sx={{ flex: 1 }}
            >
              <MenuItem value="">Any expiry</MenuItem>
              <MenuItem value="30">30 days</MenuItem>
              <MenuItem value="60">60 days</MenuItem>
              <MenuItem value="90">90 days</MenuItem>
              <MenuItem value="180">180 days</MenuItem>
            </TextField>
          </Stack>
        </Stack>
        <DataTable<BatchView>
          ariaLabel="Inventory batches"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(b) => b.id}
          onRowClick={rowClick}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No batches match"
          emptyDescription="Change the filters, or receive stock to create a batch."
          columns={[
            {
              key: 'batch',
              header: 'Batch',
              sortKey: 'batchNumber',
              primary: true,
              render: (b) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {b.batchNumber}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {b.medicationName}
                  </Typography>
                </Box>
              ),
            },
            { key: 'expiry', header: 'Expiry', sortKey: 'expiryDate', render: (b) => <ExpiryText date={b.expiryDate} days={b.daysToExpiry} expired={b.expired} /> },
            {
              key: 'qty',
              header: 'On hand',
              sortKey: 'quantityOnHand',
              align: 'right',
              render: (b) => (
                <Box>
                  <QuantityText value={b.quantityOnHand} unit={b.dispenseUnit} strong />
                  <Typography variant="caption" color="text.secondary" component="div">
                    of {b.quantityReceived} received
                  </Typography>
                </Box>
              ),
            },
            { key: 'value', header: 'Value at cost', align: 'right', hideOnMobile: true, render: (b) => <Money value={b.stockValue} /> },
            {
              key: 'received',
              header: 'Received',
              sortKey: 'receivedAt',
              hideOnMobile: true,
              render: (b) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(b.receivedAt, 'DD MMM YYYY')}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {b.supplier ?? '—'}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'status',
              header: 'Status',
              sortKey: 'status',
              render: (b) => <StatusChip value={b.expired && b.status !== 'EXPIRED' && b.quantityOnHand > 0 ? 'EXPIRED' : b.status} label={b.status === 'EXPIRED' ? 'Written off' : undefined} />,
            },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (b) => <BatchActionsMenu batch={b} canManage={canManage} onHistory={(x) => setHistory(x.id)} />,
            },
          ]}
        />
      </Card>
      <BatchDrawer batchId={history} onClose={() => setHistory(null)} />
      <ReceiveStockDialog open={receiveOpen} initialMedicationId={medication?.id} onClose={() => setReceiveOpen(false)} />
    </>
  );
}

export default function BatchesPage() {
  return (
    <Suspense fallback={<PageSkeleton />}>
      <BatchesContent />
    </Suspense>
  );
}

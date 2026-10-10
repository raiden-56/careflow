'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Download, PackagePlus, Search } from 'lucide-react';
import { Availability, pharmacyApi, pharmacyKeys, StockLevel } from '@/lib/api/pharmacy';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState, useToast } from '@/lib/hooks';
import { formatDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { Money } from '@/components/ui/Display';
import { AvailabilityChip, MedicationFlags, QuantityText, RowMenu } from '@/components/pharmacy/PharmacyBits';
import { AdjustStockDialog, ReceiveStockDialog } from '@/components/pharmacy/StockDialogs';
import { colors } from '@/theme/tokens';

export default function InventoryPage() {
  const toast = useToast();
  const router = useRouter();
  const { hasPermission } = useAuth();
  const canManage = hasPermission('INVENTORY_MANAGE');
  const t = useTableState({ sort: { field: 'name', direction: 'asc' } });
  const [availability, setAvailability] = useState<Availability | ''>('');
  const [receiveOpen, setReceiveOpen] = useState(false);
  const [receiveFor, setReceiveFor] = useState<string | null>(null);
  const [adjustFor, setAdjustFor] = useState<StockLevel | null>(null);
  const [exporting, setExporting] = useState(false);

  const params = { q: t.q || undefined, availability: availability || undefined, page: t.page, size: t.size, sort: sortParam(t.sort) };
  const q = useQuery({ queryKey: pharmacyKeys.stock(params), queryFn: () => pharmacyApi.stock(params), placeholderData: keepPreviousData });

  const exportCsv = async () => {
    setExporting(true);
    try {
      await pharmacyApi.exportStock();
      toast.success('Stock report downloaded');
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Inventory"
        subtitle="Usable stock counts only active, unexpired batches. Quarantined and expired stock is shown separately."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Inventory' }]}
        actions={
          canManage && (
            <>
              <Button variant="outlined" startIcon={<Download size={16} />} onClick={exportCsv} disabled={exporting}>
                {exporting ? 'Preparing…' : 'Export CSV'}
              </Button>
              <Button
                variant="contained"
                startIcon={<PackagePlus size={16} />}
                onClick={() => {
                  setReceiveFor(null);
                  setReceiveOpen(true);
                }}
              >
                Receive stock
              </Button>
            </>
          )
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField
            placeholder="Search medication or code"
            value={t.search}
            onChange={(e) => t.setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search stock' } }}
            sx={{ width: { xs: '100%', sm: 360 } }}
          />
          <TextField
            select
            label="Availability"
            value={availability}
            onChange={(e) => {
              setAvailability(e.target.value as Availability);
              t.setPage(0);
            }}
            sx={{ width: { xs: '100%', sm: 220 } }}
          >
            <MenuItem value="">All items</MenuItem>
            <MenuItem value="IN_STOCK">In stock</MenuItem>
            <MenuItem value="LOW_STOCK">Low stock</MenuItem>
            <MenuItem value="OUT_OF_STOCK">Out of stock</MenuItem>
          </TextField>
        </Stack>
        <DataTable<StockLevel>
          ariaLabel="Stock levels"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(s) => s.medicationId}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No items match"
          rowSx={(s) => (s.availability === 'OUT_OF_STOCK' && s.active ? { boxShadow: `inset 3px 0 0 ${colors.danger}` } : undefined)}
          columns={[
            {
              key: 'name',
              header: 'Medication',
              sortKey: 'name',
              primary: true,
              render: (s) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600} component="div">
                    {s.name} <MedicationFlags highAlert={s.isHighAlert} controlled={s.isControlled} />
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {s.code}
                    {!s.active ? ' · inactive' : ''}
                  </Typography>
                </Box>
              ),
            },
            { key: 'usable', header: 'Usable', sortKey: 'usable', render: (s) => <AvailabilityChip value={s.availability} stock={s.usable} unit={s.dispenseUnit} /> },
            { key: 'reorder', header: 'Reorder at', align: 'right', hideOnMobile: true, render: (s) => <QuantityText value={s.reorderLevel} /> },
            {
              key: 'held',
              header: 'Not usable',
              hideOnMobile: true,
              render: (s) =>
                s.quarantined + s.expiredOnHand === 0 ? (
                  <Typography variant="caption" color="text.secondary">
                    —
                  </Typography>
                ) : (
                  <Box>
                    {s.quarantined > 0 && (
                      <Typography variant="caption" component="div" sx={{ color: colors.warning }}>
                        {s.quarantined} quarantined
                      </Typography>
                    )}
                    {s.expiredOnHand > 0 && (
                      <Typography variant="caption" component="div" sx={{ color: colors.danger }}>
                        {s.expiredOnHand} expired
                      </Typography>
                    )}
                  </Box>
                ),
            },
            {
              key: 'expiry',
              header: 'Nearest expiry',
              sortKey: 'nearestExpiry',
              render: (s) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDate(s.nearestExpiry)}
                  </Typography>
                  {s.nearExpiry > 0 && (
                    <Typography variant="caption" sx={{ color: colors.warning }}>
                      {s.nearExpiry} expiring ≤ 90 days
                    </Typography>
                  )}
                </Box>
              ),
            },
            { key: 'batches', header: 'Batches', align: 'right', hideOnMobile: true, render: (s) => s.batchCount },
            { key: 'value', header: 'Value at cost', sortKey: 'stockValue', align: 'right', render: (s) => <Money value={s.stockValue} /> },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (s) => (
                <RowMenu
                  label={`Actions for ${s.code}`}
                  items={[
                    { label: 'View batches', onClick: () => router.push(`/pharmacy/batches?medicationId=${s.medicationId}`) },
                    ...(canManage
                      ? [
                          ...(s.active
                            ? [
                                {
                                  label: 'Receive stock',
                                  onClick: () => {
                                    setReceiveFor(s.medicationId);
                                    setReceiveOpen(true);
                                  },
                                },
                              ]
                            : []),
                          { label: 'Adjust stock', onClick: () => setAdjustFor(s) },
                        ]
                      : []),
                  ]}
                />
              ),
            },
          ]}
        />
      </Card>
      <ReceiveStockDialog open={receiveOpen} initialMedicationId={receiveFor} onClose={() => setReceiveOpen(false)} />
      <AdjustStockDialog open={!!adjustFor} medicationId={adjustFor?.medicationId} medicationName={adjustFor?.name} onClose={() => setAdjustFor(null)} />
    </>
  );
}

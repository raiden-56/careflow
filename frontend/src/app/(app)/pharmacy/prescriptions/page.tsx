'use client';

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Card, Chip, InputAdornment, Stack, TextField } from '@mui/material';
import { Search } from 'lucide-react';
import { pharmacyApi, pharmacyKeys, QueueFilter } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { QueueTable } from '@/components/pharmacy/QueueTable';
import { OrderReviewDialog } from '@/components/pharmacy/OrderReviewDialog';
import { DispenseDialog } from '@/components/pharmacy/DispenseDialog';

const FILTERS: { label: string; value: QueueFilter; empty: string }[] = [
  { label: 'Outstanding', value: 'OUTSTANDING', empty: 'Nothing outstanding: all active prescriptions are verified and supplied for this cycle.' },
  { label: 'Awaiting verification', value: 'AWAITING_VERIFICATION', empty: 'No prescriptions are waiting for verification.' },
  { label: 'Ready to dispense', value: 'READY_TO_DISPENSE', empty: 'No verified prescriptions are due for supply.' },
  { label: 'Queried', value: 'QUERIED', empty: 'No open queries with prescribers.' },
  { label: 'All active', value: 'ALL', empty: 'There are no active prescriptions on open admissions.' },
];

export default function PrescriptionQueuePage() {
  const { hasPermission } = useAuth();
  const t = useTableState();
  const [filter, setFilter] = useState(0);
  const [review, setReview] = useState<UUID | null>(null);
  const [dispense, setDispense] = useState<UUID | null>(null);
  const params = { filter: FILTERS[filter].value, q: t.q || undefined, page: t.page, size: t.size };
  const q = useQuery({ queryKey: pharmacyKeys.queue(params), queryFn: () => pharmacyApi.queue(params), placeholderData: keepPreviousData, refetchInterval: 60_000 });

  return (
    <>
      <PageHeader
        title="Prescription queue"
        subtitle="Review active prescriptions for allergies and high-risk medicines, verify or query the prescriber, then supply the ward. Scheduled medicines are re-supplied every 24 hours."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Prescription queue' }]}
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap role="tablist" aria-label="Queue filter">
            {FILTERS.map((f, i) => (
              <Chip
                key={f.value}
                label={f.label}
                clickable
                role="tab"
                aria-selected={filter === i}
                color={filter === i ? 'primary' : 'default'}
                variant={filter === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setFilter(i);
                  t.setPage(0);
                }}
              />
            ))}
          </Stack>
          <TextField
            placeholder="Search patient, MRN, admission or medication"
            value={t.search}
            onChange={(e) => t.setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search queue' } }}
            sx={{ maxWidth: { sm: 420 } }}
          />
        </Stack>
        <QueueTable
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          emptyTitle={t.q ? 'No matches' : 'Queue is clear'}
          emptyDescription={t.q ? 'No prescriptions match your search.' : FILTERS[filter].empty}
          onReview={(i) => setReview(i.orderId)}
          onDispense={(i) => setDispense(i.orderId)}
          canAct={hasPermission('DISPENSE')}
        />
      </Card>
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

'use client';

import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, InputAdornment, Stack, TextField, Typography } from '@mui/material';
import { Search, Undo2 } from 'lucide-react';
import { DispensationStatus, DispensationView, pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDateTime, nowInHospitalTz } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { Money } from '@/components/ui/Display';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { QueueTable } from '@/components/pharmacy/QueueTable';
import { DispenseDialog } from '@/components/pharmacy/DispenseDialog';
import { OrderReviewDialog } from '@/components/pharmacy/OrderReviewDialog';
import { ReturnDialog } from '@/components/pharmacy/ReturnDialog';
import { DispensationDrawer } from '@/components/pharmacy/Drawers';
import { useDesktopRowClick } from '@/components/pharmacy/PharmacyBits';

const STATUS: { label: string; value: DispensationStatus[] }[] = [
  { label: 'All', value: [] },
  { label: 'Dispensed', value: ['DISPENSED'] },
  { label: 'With returns', value: ['PARTIALLY_RETURNED', 'RETURNED'] },
];

export default function DispensingPage() {
  const { hasPermission } = useAuth();
  const canDispense = hasPermission('DISPENSE');
  const [duePage, setDuePage] = useState(0);
  const [dueSize, setDueSize] = useState(5);
  const [dispense, setDispense] = useState<UUID | null>(null);
  const [review, setReview] = useState<UUID | null>(null);
  const [returning, setReturning] = useState<DispensationView | null>(null);
  const [detail, setDetail] = useState<UUID | null>(null);
  const t = useTableState({ sort: { field: 'dispensedAt', direction: 'desc' } });
  const [from, setFrom] = useState(nowInHospitalTz().subtract(6, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(nowInHospitalTz().format('YYYY-MM-DD'));
  const [status, setStatus] = useState(0);
  const rangeInvalid = !!from && !!to && to < from;
  const historyRowClick = useDesktopRowClick<DispensationView>((d) => setDetail(d.id));

  const dueParams = { filter: 'READY_TO_DISPENSE' as const, page: duePage, size: dueSize };
  const due = useQuery({ queryKey: pharmacyKeys.queue(dueParams), queryFn: () => pharmacyApi.queue(dueParams), placeholderData: keepPreviousData });
  const params = {
    from: from || undefined,
    to: to || undefined,
    status: STATUS[status].value,
    q: t.q || undefined,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const history = useQuery({
    queryKey: pharmacyKeys.dispensations(params),
    queryFn: () => pharmacyApi.dispensations(params),
    placeholderData: keepPreviousData,
    enabled: !rangeInvalid,
  });

  return (
    <>
      <PageHeader
        title="Dispensing"
        subtitle="Supply verified prescriptions to the wards and manage returns. Charges are posted and corrected automatically."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Dispensing' }]}
      />
      <SectionCard title="Supplies due" subtitle="Verified prescriptions with no supply in the current 24-hour cycle" flush sx={{ mb: 5 }}>
        <QueueTable
          rows={due.data?.content}
          loading={due.isFetching}
          error={due.error}
          onRetry={() => due.refetch()}
          page={duePage}
          pageSize={dueSize}
          total={due.data?.totalElements}
          onPageChange={setDuePage}
          onPageSizeChange={(s) => {
            setDueSize(s);
            setDuePage(0);
          }}
          emptyTitle="All supplies are up to date"
          emptyDescription="New prescriptions appear here once they are verified."
          onReview={(i) => setReview(i.orderId)}
          onDispense={(i) => setDispense(i.orderId)}
          canAct={canDispense}
        />
      </SectionCard>

      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Typography variant="h4" component="h2">
            Dispensing history
          </Typography>
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
            <TextField
              placeholder="Search patient, MRN or medication"
              value={t.search}
              onChange={(e) => t.setSearch(e.target.value)}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search dispensing history' } }}
              sx={{ flex: 2 }}
            />
            <TextField
              type="date"
              label="From"
              value={from}
              onChange={(e) => {
                setFrom(e.target.value);
                t.setPage(0);
              }}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ flex: 1 }}
            />
            <TextField
              type="date"
              label="To"
              value={to}
              onChange={(e) => {
                setTo(e.target.value);
                t.setPage(0);
              }}
              error={rangeInvalid}
              helperText={rangeInvalid ? 'End date is before start date' : undefined}
              slotProps={{ inputLabel: { shrink: true } }}
              sx={{ flex: 1 }}
            />
          </Stack>
        </Stack>
        <DataTable<DispensationView>
          ariaLabel="Dispensing history"
          rows={rangeInvalid ? [] : history.data?.content}
          loading={history.isFetching}
          error={history.error}
          onRetry={() => history.refetch()}
          rowKey={(d) => d.id}
          onRowClick={historyRowClick}
          page={t.page}
          pageSize={t.size}
          total={history.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No dispensations in this period"
          emptyDescription="Adjust the dates or filters to see more."
          columns={[
            {
              key: 'med',
              header: 'Medication',
              primary: true,
              render: (d) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {d.medicationName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Batch {d.batchNumber}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'patient',
              header: 'Patient',
              render: (d) => (
                <Box>
                  <Typography variant="body2">{d.patientName}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {d.patientMrn} · {d.admissionNumber}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'qty',
              header: 'Quantity',
              sortKey: 'quantity',
              align: 'right',
              render: (d) => (
                <Box>
                  <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {d.netQuantity} {d.dispenseUnit}
                  </Typography>
                  {d.returnedQuantity > 0 && (
                    <Typography variant="caption" color="text.secondary">
                      {d.returnedQuantity} of {d.quantity} returned
                    </Typography>
                  )}
                </Box>
              ),
            },
            { key: 'charge', header: 'Charge', align: 'right', render: (d) => <Money value={d.chargedAmount} /> },
            {
              key: 'when',
              header: 'Dispensed',
              sortKey: 'dispensedAt',
              render: (d) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(d.dispensedAt)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {d.dispensedByName}
                  </Typography>
                </Box>
              ),
            },
            { key: 'status', header: 'Status', render: (d) => <StatusChip value={d.status} /> },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (d) => (
                <Stack direction="row" spacing={1} justifyContent="flex-end" onClick={(e) => e.stopPropagation()}>
                  <Button size="small" onClick={() => setDetail(d.id)}>
                    Details
                  </Button>
                  {canDispense && d.netQuantity > 0 && (
                    <Button size="small" variant="outlined" startIcon={<Undo2 size={14} />} onClick={() => setReturning(d)}>
                      Return
                    </Button>
                  )}
                </Stack>
              ),
            },
          ]}
        />
      </Card>

      <DispenseDialog orderId={dispense} onClose={() => setDispense(null)} />
      <OrderReviewDialog
        orderId={review}
        onClose={() => setReview(null)}
        onDispense={(id) => {
          setReview(null);
          setDispense(id);
        }}
      />
      <ReturnDialog dispensation={returning} onClose={() => setReturning(null)} />
      <DispensationDrawer dispensationId={detail} onClose={() => setDetail(null)} />
    </>
  );
}

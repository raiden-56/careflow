'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Card, Chip, Grid, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Search } from 'lucide-react';
import { billingApi, billingKeys, type InvoiceStatus, type InvoiceSummary, type PaymentStatus } from '@/lib/api/billing';
import { facilityApi, qk } from '@/lib/api/core';
import { useTableState } from '@/lib/hooks';
import { formatDate, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';

const STATUS_FILTERS: { label: string; value: InvoiceStatus[] }[] = [
  { label: 'Open', value: ['OPEN'] },
  { label: 'Finalized', value: ['FINALIZED'] },
  { label: 'All', value: [] },
];
const PAYMENT_FILTERS: { label: string; value: PaymentStatus[] }[] = [
  { label: 'Any payment status', value: [] },
  { label: 'Balance due (unpaid or partial)', value: ['UNPAID', 'PARTIALLY_PAID'] },
  { label: 'Unpaid', value: ['UNPAID'] },
  { label: 'Partially paid', value: ['PARTIALLY_PAID'] },
  { label: 'Paid', value: ['PAID'] },
  { label: 'Credit balance', value: ['CREDIT_BALANCE'] },
  { label: 'No charges yet', value: ['NO_CHARGES'] },
];

function InvoicesList() {
  const router = useRouter();
  const search = useSearchParams();
  const t = useTableState({ sort: { field: 'createdAt', direction: 'desc' } });
  const [statusFilter, setStatusFilter] = useState(2);
  const [payment, setPayment] = useState(0);
  const [departmentId, setDepartmentId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const initialQ = search.get('q');
  const { setSearch } = t;
  useEffect(() => {
    if (initialQ) setSearch(initialQ);
  }, [initialQ, setSearch]);

  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const params = {
    status: STATUS_FILTERS[statusFilter].value,
    paymentStatus: PAYMENT_FILTERS[payment].value,
    departmentId: departmentId || undefined,
    from: from || undefined,
    to: to || undefined,
    q: t.q,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const q = useQuery({ queryKey: billingKeys.invoices(params), queryFn: () => billingApi.searchInvoices(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Invoices"
        subtitle="Every admission has one invoice. Balances and payment status come from the ledger."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Invoices' }]}
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {STATUS_FILTERS.map((f, i) => (
              <Chip
                key={f.label}
                label={f.label}
                clickable
                color={statusFilter === i ? 'primary' : 'default'}
                variant={statusFilter === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setStatusFilter(i);
                  t.setPage(0);
                }}
              />
            ))}
          </Stack>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, md: 4 }}>
              <TextField
                fullWidth
                placeholder="Invoice, patient, MRN or admission no."
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search invoices' } }}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 3 }}>
              <TextField select fullWidth label="Payment status" value={payment} onChange={(e) => { setPayment(Number(e.target.value)); t.setPage(0); }}>
                {PAYMENT_FILTERS.map((f, i) => (
                  <MenuItem key={f.label} value={i}>
                    {f.label}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 6, md: 2 }}>
              <TextField select fullWidth label="Department" value={departmentId} onChange={(e) => { setDepartmentId(e.target.value); t.setPage(0); }}>
                <MenuItem value="">All</MenuItem>
                {departments.data?.map((d) => (
                  <MenuItem key={d.id} value={d.id}>
                    {d.name}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, md: 1.5 }}>
              <TextField fullWidth type="date" label="From" value={from} onChange={(e) => { setFrom(e.target.value); t.setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
            <Grid size={{ xs: 6, md: 1.5 }}>
              <TextField fullWidth type="date" label="To" value={to} onChange={(e) => { setTo(e.target.value); t.setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
          </Grid>
        </Stack>
        <DataTable<InvoiceSummary>
          ariaLabel="Invoices"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(r) => r.id}
          onRowClick={(r) => router.push(`/billing/invoices/${r.id}`)}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No invoices match these filters"
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              sortKey: 'patientName',
              render: (r) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {r.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {r.mrn}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'invoice',
              header: 'Invoice',
              sortKey: 'invoiceNumber',
              render: (r) => (
                <Box>
                  <Typography variant="body2">{r.invoiceNumber}</Typography>
                  <Stack direction="row" spacing={1} sx={{ mt: 0.5 }} justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
                    <StatusChip value={r.status} />
                    {r.payerType !== 'SELF' && <StatusChip value={r.payerType} label={humanize(r.payerType)} tone="secondary" />}
                  </Stack>
                </Box>
              ),
            },
            {
              key: 'admission',
              header: 'Admission',
              hideOnMobile: true,
              render: (r) =>
                r.admissionNumber ? (
                  <Box>
                    <Typography variant="body2">
                      {r.admissionNumber} · {r.departmentName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {r.dischargedAt ? `Discharged ${formatDate(r.dischargedAt)}` : `Admitted ${formatDate(r.admittedAt)}`}
                    </Typography>
                  </Box>
                ) : (
                  '—'
                ),
            },
            { key: 'charges', header: 'Posted charges', align: 'right', sortKey: 'netCharges', render: (r) => <Money value={r.netCharges} /> },
            { key: 'credits', header: 'Paid & credited', align: 'right', hideOnMobile: true, render: (r) => <Money value={r.credits} /> },
            { key: 'balance', header: 'Balance', align: 'right', sortKey: 'balance', render: (r) => <Money value={r.balance} strong colorize /> },
            { key: 'pay', header: 'Payment', render: (r) => <StatusChip value={r.paymentStatus} /> },
            { key: 'created', header: 'Opened', sortKey: 'createdAt', hideOnMobile: true, render: (r) => formatDate(r.createdAt) },
          ]}
        />
      </Card>
    </>
  );
}

export default function InvoicesPage() {
  return (
    <Suspense>
      <InvoicesList />
    </Suspense>
  );
}

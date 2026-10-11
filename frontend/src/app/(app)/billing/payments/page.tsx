'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Grid, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { CreditCard, Download, Search } from 'lucide-react';
import { billingApi, billingKeys, METHOD_LABEL, PAYMENT_METHODS, type InvoiceSummary, type PaymentMethod, type PaymentResult, type PaymentView } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useDebouncedValue, useTableState, useToast } from '@/lib/hooks';
import { formatDate, formatINR } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { PaymentDialog, type PaymentTarget } from '@/components/billing/PaymentDialog';
import { PaymentsTable } from '@/components/billing/InvoiceSections';

export default function PaymentCollectionPage() {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [find, setFind] = useState('');
  const fq = useDebouncedValue(find, 300);
  const [target, setTarget] = useState<PaymentTarget | null>(null);
  const [last, setLast] = useState<PaymentResult | null>(null);
  const [reversing, setReversing] = useState<PaymentView | null>(null);
  const t = useTableState({ sort: { field: 'receivedAt', direction: 'desc' } });
  const [method, setMethod] = useState<PaymentMethod | ''>('');
  const [status, setStatus] = useState<'' | 'COMPLETED' | 'REVERSED'>('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const canPay = hasPermission('PAYMENT_RECORD');

  const findParams = { q: fq, paymentStatus: ['UNPAID', 'PARTIALLY_PAID', 'NO_CHARGES'] as InvoiceSummary['paymentStatus'][], status: ['OPEN', 'FINALIZED'] as InvoiceSummary['status'][], size: 6, sort: 'balance,desc' };
  const candidates = useQuery({
    queryKey: billingKeys.invoices({ ...findParams, purpose: 'collect' }),
    queryFn: () => billingApi.searchInvoices(fq.length >= 2 ? findParams : { ...findParams, q: undefined }),
  });
  const params = {
    q: t.q,
    method: method ? [method] : undefined,
    status: status ? [status] : undefined,
    from: from || undefined,
    to: to || undefined,
    page: t.page,
    size: t.size,
    sort: 'receivedAt,desc',
  };
  const payments = useQuery({ queryKey: billingKeys.payments(params), queryFn: () => billingApi.payments(params), placeholderData: keepPreviousData });
  const reverse = useMutation({
    mutationFn: ({ p, reason }: { p: PaymentView; reason: string }) => billingApi.reversePayment(p.id, reason),
    onSuccess: () => {
      toast.success('Payment reversed');
      setReversing(null);
      queryClient.invalidateQueries({ queryKey: ['payments'] });
      queryClient.invalidateQueries({ queryKey: ['invoices'] });
      queryClient.invalidateQueries({ queryKey: ['billing'] });
    },
    onError: toast.error,
  });

  return (
    <>
      <PageHeader
        title="Payment collection"
        subtitle="Find the patient's invoice, record the payment and hand over the receipt."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Payments' }]}
      />
      <Stack spacing={5}>
        <SectionCard title="Collect a payment" subtitle={fq.length >= 2 ? 'Matching invoices' : 'Largest balances due - search to find a specific patient'}>
          <TextField
            fullWidth
            placeholder="Search patient name, MRN, invoice or admission number"
            value={find}
            onChange={(e) => setFind(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Find invoice' } }}
            sx={{ mb: 4 }}
          />
          {last && (
            <Alert
              severity="success"
              sx={{ mb: 4 }}
              onClose={() => setLast(null)}
              action={
                <Button color="inherit" size="small" startIcon={<Download size={14} />} onClick={() => billingApi.downloadReceipt(last.payment.id, last.payment.receiptNumber).catch(toast.error)}>
                  Receipt
                </Button>
              }
            >
              {last.replayed ? 'Already recorded: ' : 'Recorded '} {formatINR(last.payment.amount)} from {last.payment.payerName} - receipt {last.payment.receiptNumber}. Balance now{' '}
              {formatINR(last.summary.balance)}.
            </Alert>
          )}
          {candidates.isLoading ? (
            <TableSkeleton rows={3} columns={3} />
          ) : candidates.error ? (
            <ErrorState error={candidates.error} onRetry={() => candidates.refetch()} />
          ) : !candidates.data?.content.length ? (
            <EmptyState title="No invoices found" description="Try the MRN or invoice number." compact />
          ) : (
            <Grid container spacing={3}>
              {candidates.data.content.map((inv) => (
                <Grid key={inv.id} size={{ xs: 12, md: 6 }}>
                  <Card variant="outlined" sx={{ p: 4, height: '100%' }}>
                    <Stack direction="row" justifyContent="space-between" spacing={2}>
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="subtitle1" fontWeight={600} noWrap>
                          {inv.patientName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" component="div">
                          {inv.mrn} · {inv.invoiceNumber}
                          {inv.admissionNumber ? ` · ${inv.admissionNumber}` : ''}
                        </Typography>
                        <Stack direction="row" spacing={1} sx={{ mt: 1 }} flexWrap="wrap" useFlexGap>
                          <StatusChip value={inv.paymentStatus} />
                          {inv.admissionStatus && <StatusChip value={inv.admissionStatus} />}
                        </Stack>
                      </Box>
                      <Box sx={{ textAlign: 'right', flexShrink: 0 }}>
                        <Typography variant="caption" color="text.secondary">
                          Balance
                        </Typography>
                        <Typography variant="h4" component="p" sx={{ fontVariantNumeric: 'tabular-nums' }}>
                          {formatINR(inv.balance)}
                        </Typography>
                      </Box>
                    </Stack>
                    <Stack direction="row" spacing={2} sx={{ mt: 3 }} justifyContent="flex-end">
                      <Button component={Link} href={`/billing/invoices/${inv.id}`} size="small">
                        Open invoice
                      </Button>
                      {canPay && (
                        <Button
                          size="small"
                          variant="contained"
                          startIcon={<CreditCard size={14} />}
                          onClick={() => setTarget({ id: inv.id, invoiceNumber: inv.invoiceNumber, patientName: inv.patientName, balance: inv.balance })}
                        >
                          Record payment
                        </Button>
                      )}
                    </Stack>
                  </Card>
                </Grid>
              ))}
            </Grid>
          )}
        </SectionCard>
        <Card>
          <Box sx={{ px: 5, pt: 4 }}>
            <Typography variant="h4" component="h2">
              Payments & receipts
            </Typography>
            <Typography variant="body2" color="text.secondary">
              Every receipt issued, newest first. Reversed receipts stay on record.
            </Typography>
          </Box>
          <Grid container spacing={3} sx={{ p: 4 }}>
            <Grid size={{ xs: 12, md: 4 }}>
              <TextField
                fullWidth
                placeholder="Receipt, patient, MRN, invoice or payer"
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search payments' } }}
              />
            </Grid>
            <Grid size={{ xs: 6, md: 2 }}>
              <TextField select fullWidth label="Method" value={method} onChange={(e) => { setMethod(e.target.value as PaymentMethod | ''); t.setPage(0); }}>
                <MenuItem value="">All</MenuItem>
                {PAYMENT_METHODS.map((m) => (
                  <MenuItem key={m} value={m}>
                    {METHOD_LABEL[m]}
                  </MenuItem>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, md: 2 }}>
              <TextField select fullWidth label="Status" value={status} onChange={(e) => { setStatus(e.target.value as '' | 'COMPLETED' | 'REVERSED'); t.setPage(0); }}>
                <MenuItem value="">All</MenuItem>
                <MenuItem value="COMPLETED">Completed</MenuItem>
                <MenuItem value="REVERSED">Reversed</MenuItem>
              </TextField>
            </Grid>
            <Grid size={{ xs: 6, md: 2 }}>
              <TextField fullWidth type="date" label="From" value={from} onChange={(e) => { setFrom(e.target.value); t.setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
            <Grid size={{ xs: 6, md: 2 }}>
              <TextField fullWidth type="date" label="To" value={to} onChange={(e) => { setTo(e.target.value); t.setPage(0); }} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
          </Grid>
          <PaymentsTable
            rows={payments.data?.content}
            showPatient
            loading={payments.isFetching}
            error={payments.error}
            onRetry={() => payments.refetch()}
            page={t.page}
            pageSize={t.size}
            total={payments.data?.totalElements}
            onPageChange={t.setPage}
            onPageSizeChange={t.setSize}
            onReverse={canPay ? setReversing : undefined}
          />
          {payments.data && (
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', px: 4, pb: 3 }}>
              {from || to ? `Showing ${from ? formatDate(from) : 'start'} – ${to ? formatDate(to) : 'today'}.` : 'All dates.'} Collections totals are in Financial reports.
            </Typography>
          )}
        </Card>
      </Stack>
      <PaymentDialog
        open={!!target}
        invoice={target}
        onClose={() => setTarget(null)}
        onRecorded={(r) => {
          setLast(r);
          queryClient.invalidateQueries({ queryKey: ['invoices'] });
        }}
      />
      <ConfirmDialog
        open={!!reversing}
        title={`Reverse receipt ${reversing?.receiptNumber ?? ''}?`}
        description={reversing && `${formatINR(reversing.amount)} from ${reversing.patientName} will be added back to the invoice balance and the patient notified.`}
        requireReason
        reasonLabel="Reason (e.g. cheque returned unpaid)"
        confirmLabel="Reverse payment"
        tone="error"
        loading={reverse.isPending}
        onConfirm={(r) => reversing && reverse.mutate({ p: reversing, reason: r! })}
        onClose={() => setReversing(null)}
      />
    </>
  );
}

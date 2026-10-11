'use client';

import { use, useState } from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Grid, Stack, Tab, Tabs, Typography } from '@mui/material';
import { CreditCard, FileDown, Lock, LockOpen } from 'lucide-react';
import { billingApi, billingKeys } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DefinitionList } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { BalanceBreakdown, summaryLines } from '@/components/billing/BalanceBreakdown';
import { PaymentDialog } from '@/components/billing/PaymentDialog';
import {
  AdjustmentsSection,
  ChargesSection,
  ClaimsSection,
  EstimatesSection,
  LedgerSection,
  PaymentsSection,
  RequestsSection,
} from '@/components/billing/InvoiceSections';

type TabKey = 'charges' | 'estimates' | 'payments' | 'adjustments' | 'claims' | 'ledger' | 'requests';

export default function InvoiceWorkspacePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  const { hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<TabKey>('charges');
  const [paying, setPaying] = useState(false);
  const [finalizing, setFinalizing] = useState(false);
  const [reopening, setReopening] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const q = useQuery({ queryKey: billingKeys.invoice(id), queryFn: () => billingApi.invoice(id) });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['invoice', id] });
    queryClient.invalidateQueries({ queryKey: ['invoices'] });
    queryClient.invalidateQueries({ queryKey: ['billing'] });
  };
  const finalize = useMutation({
    mutationFn: () => billingApi.finalize(id),
    onSuccess: (d) => {
      toast.success(`Invoice ${d.invoiceNumber} finalized`);
      setFinalizing(false);
      refresh();
    },
    onError: toast.error,
  });
  const reopen = useMutation({
    mutationFn: (reason: string) => billingApi.reopen(id, reason),
    onSuccess: () => {
      toast.success('Invoice reopened for corrections');
      setReopening(false);
      refresh();
    },
    onError: toast.error,
  });

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const inv = q.data;
  const s = inv.summary;
  const pendingAdjustments = inv.adjustments.filter((a) => a.status === 'PENDING').length;
  const openRequests = inv.requests.filter((r) => r.status === 'OPEN' || r.status === 'IN_REVIEW').length;
  const tabs: { key: TabKey; label: string; count?: number }[] = [
    { key: 'charges', label: 'Charges', count: inv.lines.filter((l) => l.status === 'POSTED').length },
    { key: 'estimates', label: 'Estimates', count: inv.estimates.filter((e) => e.active).length },
    { key: 'payments', label: 'Payments', count: inv.payments.length },
    { key: 'adjustments', label: pendingAdjustments ? `Adjustments (${pendingAdjustments} pending)` : 'Adjustments', count: pendingAdjustments ? undefined : inv.adjustments.length },
    { key: 'claims', label: 'Claims', count: inv.claims.length },
    { key: 'ledger', label: 'Ledger', count: inv.ledger.length },
    { key: 'requests', label: openRequests ? `Queries (${openRequests} open)` : 'Queries', count: openRequests ? undefined : inv.requests.length },
  ];

  return (
    <>
      <PageHeader
        title={`Invoice ${inv.invoiceNumber}`}
        subtitle={
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <StatusChip value={inv.status} />
            <StatusChip value={s.paymentStatus} />
            <span>
              {inv.patient.fullName} · {inv.patient.mrn} · payer {humanize(inv.payerType)}
            </span>
          </Stack>
        }
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Invoices', href: '/billing/invoices' }, { label: inv.invoiceNumber }]}
        actions={
          <>
            <Button
              variant="outlined"
              startIcon={<FileDown size={16} />}
              disabled={downloading}
              onClick={async () => {
                setDownloading(true);
                try {
                  await billingApi.downloadInvoicePdf(inv.id, inv.invoiceNumber);
                } catch (e) {
                  toast.error(e);
                } finally {
                  setDownloading(false);
                }
              }}
            >
              Invoice PDF
            </Button>
            {hasPermission('BILLING_WRITE') && inv.status === 'OPEN' && (
              <Button variant="outlined" startIcon={<Lock size={16} />} onClick={() => setFinalizing(true)}>
                Finalize
              </Button>
            )}
            {hasPermission('BILLING_WRITE') && inv.status === 'FINALIZED' && (
              <Button variant="outlined" startIcon={<LockOpen size={16} />} onClick={() => setReopening(true)}>
                Reopen
              </Button>
            )}
            {hasPermission('PAYMENT_RECORD') && inv.status !== 'CANCELLED' && (
              <Button variant="contained" startIcon={<CreditCard size={16} />} onClick={() => setPaying(true)}>
                Record payment
              </Button>
            )}
          </>
        }
      />
      {inv.status === 'FINALIZED' && (
        <Alert severity="success" icon={<Lock size={18} />} sx={{ mb: 5 }}>
          Final bill generated and reviewed by {inv.reviewedByName} on {formatDateTime(inv.reviewedAt)}. No new charges can be added; payments,
          adjustments and claims are still accepted.
        </Alert>
      )}
      {inv.status === 'OPEN' && inv.reopenedAt && (
        <Alert severity="warning" sx={{ mb: 5 }}>
          Reopened by {inv.reopenedByName} on {formatDateTime(inv.reopenedAt)}: {inv.reopenReason}
        </Alert>
      )}
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 8 }} sx={{ minWidth: 0 }}>
          <Card>
            <Tabs
              value={tab}
              onChange={(_, v) => setTab(v)}
              variant="scrollable"
              scrollButtons="auto"
              allowScrollButtonsMobile
              sx={{ px: 2, borderBottom: 1, borderColor: 'divider' }}
              aria-label="Invoice sections"
            >
              {tabs.map((t) => (
                <Tab key={t.key} value={t.key} label={t.count !== undefined ? `${t.label} (${t.count})` : t.label} />
              ))}
            </Tabs>
            {tab === 'charges' && <ChargesSection invoice={inv} />}
            {tab === 'estimates' && <EstimatesSection invoice={inv} />}
            {tab === 'payments' && <PaymentsSection invoice={inv} onRecord={() => setPaying(true)} />}
            {tab === 'adjustments' && <AdjustmentsSection invoice={inv} />}
            {tab === 'claims' && <ClaimsSection invoice={inv} />}
            {tab === 'ledger' && <LedgerSection invoice={inv} />}
            {tab === 'requests' && <RequestsSection invoice={inv} />}
          </Card>
        </Grid>
        <Grid size={{ xs: 12, lg: 4 }} sx={{ order: { xs: -1, lg: 0 } }}>
          <Stack spacing={5}>
            <SectionCard title="Balance" subtitle="Calculated by the server from the ledger">
              <BalanceBreakdown
                lines={summaryLines(s)}
                balance={s.balance}
                paymentStatus={s.paymentStatus}
                insurancePending={s.insuranceApprovedPending}
                estimates={s.estimatedFutureCharges}
                formula={s.formula}
              />
              {Number(inv.pendingAdjustmentsTotal) !== 0 && (
                <Alert severity="info" sx={{ mt: 3 }}>
                  {pendingAdjustments} adjustment(s) awaiting approval are not yet reflected in the balance.
                </Alert>
              )}
            </SectionCard>
            <SectionCard title="Patient & stay">
              <DefinitionList
                columns={1}
                items={[
                  { label: 'Patient', value: `${inv.patient.fullName} (${inv.patient.mrn})` },
                  { label: 'Phone', value: inv.patient.phone },
                  ...(inv.admission
                    ? [
                        {
                          label: 'Admission',
                          value: (
                            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                              <span>{inv.admission.admissionNumber}</span>
                              <StatusChip value={inv.admission.status} />
                            </Stack>
                          ),
                        },
                        { label: 'Department / doctor', value: `${inv.admission.departmentName ?? '—'} · ${inv.admission.attendingDoctorName ?? '—'}` },
                        { label: 'Bed', value: inv.admission.location },
                        { label: 'Admitted', value: formatDateTime(inv.admission.admittedAt) },
                        { label: 'Discharged', value: inv.admission.dischargedAt ? formatDateTime(inv.admission.dischargedAt) : 'Still admitted' },
                      ]
                    : []),
                  { label: 'Invoice opened', value: `${formatDate(inv.createdAt)}${inv.createdByName ? ` by ${inv.createdByName}` : ''}` },
                ]}
              />
              {inv.admission && inv.admission.status !== 'DISCHARGED' && (
                <Box sx={{ mt: 4 }}>
                  <Typography variant="caption" color="text.secondary">
                    Discharge financial clearance is handled on{' '}
                    <Typography component={Link} href="/billing/discharge" variant="caption" color="primary">
                      Discharge clearance
                    </Typography>
                    .
                  </Typography>
                </Box>
              )}
            </SectionCard>
          </Stack>
        </Grid>
      </Grid>
      <PaymentDialog
        open={paying}
        invoice={{ id: inv.id, invoiceNumber: inv.invoiceNumber, patientName: inv.patient.fullName, balance: s.balance }}
        onClose={() => setPaying(false)}
        onRecorded={() => setTab('payments')}
      />
      <ConfirmDialog
        open={finalizing}
        title={`Finalize ${inv.invoiceNumber}?`}
        description={
          <>
            Outstanding room and nursing charges are posted up to {inv.admission?.dischargedAt ? 'the discharge date' : 'today'}, active estimates are
            retired and the invoice is marked as the reviewed final bill. No new charges can be added afterwards unless it is reopened.
          </>
        }
        confirmLabel="Finalize invoice"
        loading={finalize.isPending}
        onConfirm={() => finalize.mutate()}
        onClose={() => setFinalizing(false)}
      />
      <ConfirmDialog
        open={reopening}
        title={`Reopen ${inv.invoiceNumber}?`}
        description="The invoice returns to open so charges can be corrected. The discharge checklist will show the final bill as not generated until it is finalized again."
        requireReason
        reasonLabel="Reason for reopening"
        confirmLabel="Reopen"
        tone="warning"
        loading={reopen.isPending}
        onConfirm={(r) => reopen.mutate(r!)}
        onClose={() => setReopening(false)}
      />
    </>
  );
}

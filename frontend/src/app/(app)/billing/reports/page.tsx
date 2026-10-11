'use client';

import { useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, Grid, Stack, Tab, Tabs, TextField, Typography } from '@mui/material';
import { Download } from 'lucide-react';
import {
  ADJUSTMENT_LABEL,
  billingApi,
  billingKeys,
  METHOD_LABEL,
  type ReportKey,
} from '@/lib/api/billing';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, formatINR, humanize, nowInHospitalTz } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { ErrorState, PageSkeleton } from '@/components/ui/States';
import { DailyBars, DailyLines, RankedBars } from '@/components/billing/Charts';

const REPORTS: { key: ReportKey; label: string }[] = [
  { key: 'collections', label: 'Collections' },
  { key: 'charges-vs-payments', label: 'Charges vs collections' },
  { key: 'revenue', label: 'Revenue' },
  { key: 'receivables', label: 'Receivables' },
  { key: 'adjustments', label: 'Refunds & adjustments' },
  { key: 'claims', label: 'Claims' },
];

const PRESETS = [
  { label: '7 days', days: 6 },
  { label: '30 days', days: 29 },
  { label: '90 days', days: 89 },
];

function Stat({ label, value, note }: { label: string; value: ReactNode; note?: string }) {
  return (
    <Card variant="outlined" sx={{ p: 3, height: '100%' }}>
      <Typography variant="caption" color="text.secondary">
        {label}
      </Typography>
      <Typography variant="h3" component="p" sx={{ fontVariantNumeric: 'tabular-nums', mt: 0.5 }}>
        {value}
      </Typography>
      {note && (
        <Typography variant="caption" color="text.secondary">
          {note}
        </Typography>
      )}
    </Card>
  );
}

function Basis({ text }: { text: string }) {
  return (
    <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 3 }}>
      {text}
    </Typography>
  );
}

export default function ReportsPage() {
  const toast = useToast();
  const today = nowInHospitalTz();
  const [report, setReport] = useState<ReportKey>('collections');
  const [from, setFrom] = useState(today.subtract(29, 'day').format('YYYY-MM-DD'));
  const [to, setTo] = useState(today.format('YYYY-MM-DD'));
  const [exporting, setExporting] = useState(false);
  const usesRange = report !== 'receivables';

  const exportCsv = async () => {
    setExporting(true);
    try {
      await billingApi.exportReport(report, usesRange ? from : undefined, usesRange ? to : undefined);
    } catch (e) {
      toast.error(e);
    } finally {
      setExporting(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Financial reports"
        subtitle="Collections are money received; posted charges are revenue billed; adjustments are approved discounts, write-offs, notes and refunds."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Reports' }]}
        actions={
          <Button variant="outlined" startIcon={<Download size={16} />} onClick={exportCsv} disabled={exporting}>
            {exporting ? 'Exporting…' : 'Export CSV'}
          </Button>
        }
      />
      <Card sx={{ mb: 5 }}>
        <Tabs value={report} onChange={(_, v) => setReport(v)} variant="scrollable" scrollButtons="auto" allowScrollButtonsMobile sx={{ px: 2 }} aria-label="Reports">
          {REPORTS.map((r) => (
            <Tab key={r.key} value={r.key} label={r.label} />
          ))}
        </Tabs>
        {usesRange && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ sm: 'center' }} sx={{ p: 4, borderTop: 1, borderColor: 'divider' }}>
            <Stack direction="row" spacing={2}>
              <TextField type="date" size="small" label="From" value={from} onChange={(e) => setFrom(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: to } }} />
              <TextField type="date" size="small" label="To" value={to} onChange={(e) => setTo(e.target.value)} slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: from } }} />
            </Stack>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
              {PRESETS.map((p) => (
                <Chip
                  key={p.label}
                  label={`Last ${p.label}`}
                  variant="outlined"
                  clickable
                  onClick={() => {
                    setFrom(today.subtract(p.days, 'day').format('YYYY-MM-DD'));
                    setTo(today.format('YYYY-MM-DD'));
                  }}
                />
              ))}
            </Stack>
          </Stack>
        )}
      </Card>
      {report === 'collections' && <CollectionsReportView from={from} to={to} />}
      {report === 'charges-vs-payments' && <ChargesVsPaymentsView from={from} to={to} />}
      {report === 'revenue' && <RevenueView from={from} to={to} />}
      {report === 'receivables' && <ReceivablesView />}
      {report === 'adjustments' && <AdjustmentsReportView from={from} to={to} />}
      {report === 'claims' && <ClaimsReportView from={from} to={to} />}
    </>
  );
}

function CollectionsReportView({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: billingKeys.report('collections', { from, to }), queryFn: () => billingApi.collections(from, to) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Payments received" value={formatINR(r.totalReceived)} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Payment reversals" value={formatINR(r.totalReversed)} note="Bounced cheques, chargebacks" />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Net collections" value={formatINR(r.totalNet)} />
        </Grid>
      </Grid>
      <SectionCard title="Net collections by day">
        <DailyBars data={r.byDay} name="Net collections" />
        <Basis text={r.basis} />
      </SectionCard>
      <SectionCard title="By payment method" flush>
        <DataTable
          ariaLabel="Collections by payment method"
          rows={r.byMethod}
          rowKey={(m) => m.method}
          dense
          emptyTitle="No payments in this period"
          columns={[
            { key: 'm', header: 'Method', primary: true, render: (m) => METHOD_LABEL[m.method] },
            { key: 'n', header: 'Receipts', align: 'right', render: (m) => m.receipts },
            { key: 'r', header: 'Received', align: 'right', render: (m) => <Money value={m.received} /> },
            { key: 'v', header: 'Reversed', align: 'right', render: (m) => <Money value={m.reversed} /> },
            { key: 't', header: 'Net', align: 'right', render: (m) => <Money value={m.net} strong /> },
          ]}
        />
      </SectionCard>
    </Stack>
  );
}

function ChargesVsPaymentsView({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: billingKeys.report('charges-vs-payments', { from, to }), queryFn: () => billingApi.chargesVsPayments(from, to) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  const active = r.rows.filter((x) => Number(x.postedCharges) !== 0 || Number(x.collections) !== 0 || Number(x.adjustments) !== 0 || Number(x.insuranceSettlements) !== 0);
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Posted charges (net of reversals)" value={formatINR(r.totalPostedCharges)} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Collections (net of reversals)" value={formatINR(r.totalCollections)} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Adjustments (net reduction)" value={formatINR(r.totalAdjustments)} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Insurance settlements" value={formatINR(r.totalInsurance)} />
        </Grid>
      </Grid>
      <SectionCard title="Posted charges vs collections by day">
        <DailyLines
          data={r.rows}
          series={[
            { key: 'postedCharges', label: 'Posted charges' },
            { key: 'collections', label: 'Collections' },
            { key: 'insuranceSettlements', label: 'Insurance settlements' },
          ]}
        />
        <Basis text={r.basis} />
      </SectionCard>
      <SectionCard title="Daily detail" subtitle="Days with activity" flush>
        <DataTable
          ariaLabel="Charges vs collections by day"
          rows={active}
          rowKey={(x) => x.date}
          dense
          emptyTitle="No activity in this period"
          columns={[
            { key: 'd', header: 'Date', primary: true, render: (x) => formatDate(x.date, 'ddd, DD MMM YYYY') },
            { key: 'c', header: 'Posted charges', align: 'right', render: (x) => <Money value={x.postedCharges} /> },
            { key: 'p', header: 'Collections', align: 'right', render: (x) => <Money value={x.collections} /> },
            { key: 'a', header: 'Adjustments', align: 'right', render: (x) => <Money value={x.adjustments} /> },
            { key: 'i', header: 'Insurance', align: 'right', render: (x) => <Money value={x.insuranceSettlements} /> },
          ]}
        />
      </SectionCard>
    </Stack>
  );
}

function RevenueView({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: billingKeys.report('revenue', { from, to }), queryFn: () => billingApi.revenue(from, to) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, sm: 6 }}>
          <Stat label="Revenue - posted charges net of reversals" value={formatINR(r.total)} note={`${formatDate(r.from)} – ${formatDate(r.to)}`} />
        </Grid>
      </Grid>
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="By service category">
            {r.byCategory.length ? (
              <RankedBars data={r.byCategory.map((x) => ({ label: x.label, amount: x.amount }))} name="Revenue by category" />
            ) : (
              <Typography variant="body2" color="text.secondary">
                No charges posted in this period.
              </Typography>
            )}
            <Stack spacing={1} sx={{ mt: 3 }}>
              {r.byCategory.map((x) => (
                <Stack key={x.key} direction="row" justifyContent="space-between">
                  <Typography variant="body2">
                    {x.label} <Typography component="span" variant="caption" color="text.secondary">· {x.lines} line(s)</Typography>
                  </Typography>
                  <Money value={x.amount} />
                </Stack>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 6 }}>
          <SectionCard title="By department">
            {r.byDepartment.length ? (
              <RankedBars data={r.byDepartment.map((x) => ({ label: x.label, amount: x.amount }))} name="Revenue by department" />
            ) : (
              <Typography variant="body2" color="text.secondary">
                No charges posted in this period.
              </Typography>
            )}
            <Stack spacing={1} sx={{ mt: 3 }}>
              {r.byDepartment.map((x) => (
                <Stack key={x.key} direction="row" justifyContent="space-between">
                  <Typography variant="body2">{x.label}</Typography>
                  <Money value={x.amount} />
                </Stack>
              ))}
            </Stack>
          </SectionCard>
        </Grid>
      </Grid>
      <Basis text={r.basis} />
    </Stack>
  );
}

function ReceivablesView() {
  const q = useQuery({ queryKey: billingKeys.report('receivables'), queryFn: () => billingApi.receivables() });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Outstanding receivables" value={formatINR(r.total)} note={`${r.count} invoice(s), as of ${formatDate(r.asOf)}`} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Overdue after discharge" value={formatINR(r.overdueAmount)} note={`${r.overdueCount} invoice(s)`} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Card variant="outlined" sx={{ p: 3, height: '100%' }}>
            <Typography variant="caption" color="text.secondary">
              Aging
            </Typography>
            {r.buckets.map((b) => (
              <Stack key={b.bucket} direction="row" justifyContent="space-between">
                <Typography variant="body2">
                  {b.bucket} days · {b.count}
                </Typography>
                <Money value={b.amount} />
              </Stack>
            ))}
          </Card>
        </Grid>
      </Grid>
      <SectionCard title="Invoices with a balance due" flush>
        <DataTable
          ariaLabel="Receivables"
          rows={r.rows}
          rowKey={(x) => x.invoice.id}
          dense
          emptyTitle="Nothing outstanding"
          columns={[
            {
              key: 'p',
              header: 'Patient',
              primary: true,
              render: (x) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {x.invoice.patientName}
                  </Typography>
                  <Typography component={Link} href={`/billing/invoices/${x.invoice.id}`} variant="caption" color="primary">
                    {x.invoice.invoiceNumber}
                  </Typography>
                </Box>
              ),
            },
            { key: 's', header: 'Stay', render: (x) => (x.invoice.admissionStatus ? <StatusChip value={x.invoice.admissionStatus} /> : '—') },
            { key: 'a', header: 'Age', render: (x) => <Stack direction="row" spacing={1} alignItems="center"><span>{x.ageDays} d</span>{x.overdue && <StatusChip value="OVERDUE" />}</Stack> },
            { key: 'b', header: 'Bucket', hideOnMobile: true, render: (x) => x.bucket },
            { key: 'c', header: 'Posted charges', align: 'right', hideOnMobile: true, render: (x) => <Money value={x.invoice.netCharges} /> },
            { key: 'd', header: 'Balance due', align: 'right', render: (x) => <Money value={x.invoice.balance} strong /> },
          ]}
        />
      </SectionCard>
      <Basis text={r.basis} />
    </Stack>
  );
}

function AdjustmentsReportView({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: billingKeys.report('adjustments', { from, to }), queryFn: () => billingApi.adjustmentsReport(from, to) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Approved reductions" value={formatINR(r.approvedReductions)} note="Discounts, write-offs, credit notes" />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Approved refunds" value={formatINR(r.approvedRefunds)} note="Money paid back" />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <Stat label="Approved debit notes" value={formatINR(r.approvedDebitNotes)} />
        </Grid>
      </Grid>
      <SectionCard title="By type" flush>
        <DataTable
          ariaLabel="Adjustments by type"
          rows={r.byType}
          rowKey={(x) => x.type}
          dense
          columns={[
            { key: 't', header: 'Type', primary: true, render: (x) => ADJUSTMENT_LABEL[x.type] },
            { key: 'ac', header: 'Approved', align: 'right', render: (x) => x.approvedCount },
            { key: 'aa', header: 'Approved amount', align: 'right', render: (x) => <Money value={x.approvedAmount} strong /> },
            { key: 'pc', header: 'Pending', align: 'right', render: (x) => x.pendingCount },
            { key: 'pa', header: 'Pending amount', align: 'right', render: (x) => <Money value={x.pendingAmount} /> },
            { key: 'rc', header: 'Rejected', align: 'right', render: (x) => x.rejectedCount },
          ]}
        />
      </SectionCard>
      <SectionCard title="Requests in this period" flush>
        <DataTable
          ariaLabel="Adjustment requests"
          rows={r.rows}
          rowKey={(x) => x.id}
          dense
          emptyTitle="No adjustments requested in this period"
          columns={[
            {
              key: 'n',
              header: 'Adjustment',
              primary: true,
              render: (x) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {ADJUSTMENT_LABEL[x.type]} · {x.adjustmentNumber}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ maxWidth: 360 }}>
                    {x.reason}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'i',
              header: 'Patient / invoice',
              render: (x) => (
                <Box>
                  <Typography variant="body2">{x.patientName}</Typography>
                  <Typography component={Link} href={`/billing/invoices/${x.invoiceId}`} variant="caption" color="primary">
                    {x.invoiceNumber}
                  </Typography>
                </Box>
              ),
            },
            { key: 'a', header: 'Amount', align: 'right', render: (x) => <Money value={x.amount} /> },
            { key: 's', header: 'Status', render: (x) => <StatusChip value={x.status} /> },
            { key: 'r', header: 'Requested / decided', hideOnMobile: true, render: (x) => `${x.requestedBy} ${formatDateTime(x.requestedAt)}${x.decidedBy ? ` · ${x.decidedBy}` : ''}` },
          ]}
        />
      </SectionCard>
      <Basis text={r.basis} />
    </Stack>
  );
}

function ClaimsReportView({ from, to }: { from: string; to: string }) {
  const q = useQuery({ queryKey: billingKeys.report('claims', { from, to }), queryFn: () => billingApi.claimsReport(from, to) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const r = q.data;
  return (
    <Stack spacing={5}>
      <Grid container spacing={4}>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Claims created" value={r.totalClaims} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Claimed" value={formatINR(r.totalClaimed)} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Settled" value={formatINR(r.totalSettled)} />
        </Grid>
        <Grid size={{ xs: 6, md: 3 }}>
          <Stat label="Approved, pending settlement" value={formatINR(r.totalPendingSettlement)} />
        </Grid>
      </Grid>
      <SectionCard title="By status" flush>
        <DataTable
          ariaLabel="Claims by status"
          rows={r.byStatus}
          rowKey={(x) => x.status}
          dense
          columns={[
            { key: 's', header: 'Status', primary: true, render: (x) => <StatusChip value={x.status} label={humanize(x.status)} /> },
            { key: 'n', header: 'Claims', align: 'right', render: (x) => x.count },
            { key: 'c', header: 'Claimed', align: 'right', render: (x) => <Money value={x.claimed} /> },
            { key: 'a', header: 'Approved', align: 'right', render: (x) => <Money value={x.approved} /> },
            { key: 'st', header: 'Settled', align: 'right', render: (x) => <Money value={x.settled} /> },
            { key: 'p', header: 'Pending settlement', align: 'right', render: (x) => <Money value={x.pendingSettlement} strong /> },
          ]}
        />
      </SectionCard>
      <Basis text={r.basis} />
    </Stack>
  );
}

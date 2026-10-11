'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, CardActionArea, Chip, FormControlLabel, Grid, InputAdornment, Stack, Switch, TextField, Typography } from '@mui/material';
import { AlertTriangle, CreditCard, Search, Timer } from 'lucide-react';
import { billingApi, billingKeys, type OutstandingRow } from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDate, formatDateTime, formatINR, pluralize } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { PageHeader } from '@/components/ui/PageHeader';
import { KpiCard } from '@/components/ui/KpiCard';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { PaymentDialog, type PaymentTarget } from '@/components/billing/PaymentDialog';

const ADMISSION_VIEWS: { label: string; value: string[] }[] = [
  { label: 'All', value: [] },
  { label: 'Currently admitted', value: ['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS'] },
  { label: 'Discharged', value: ['DISCHARGED'] },
];
const BUCKET_LABEL: Record<string, string> = { '0-30': '0–30 days', '31-60': '31–60 days', '61-90': '61–90 days', '90+': 'Over 90 days' };

export default function OutstandingPage() {
  const { hasPermission } = useAuth();
  const t = useTableState({ sort: { field: 'ageDays', direction: 'desc' } });
  const [view, setView] = useState(0);
  const [bucket, setBucket] = useState<string | undefined>();
  const [overdue, setOverdue] = useState(false);
  const [target, setTarget] = useState<PaymentTarget | null>(null);
  const params = {
    admissionStatus: ADMISSION_VIEWS[view].value,
    bucket,
    overdue: overdue || undefined,
    q: t.q,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const q = useQuery({ queryKey: billingKeys.outstanding(params), queryFn: () => billingApi.outstanding(params), placeholderData: keepPreviousData });
  const d = q.data;

  return (
    <>
      <PageHeader
        title="Outstanding balances"
        subtitle="Invoices with money still due, aged from the discharge date (or the invoice date while the patient is admitted)."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Outstanding' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <KpiCard label="Total outstanding" value={d ? formatINR(d.totalOutstanding) : '—'} hint={d ? pluralize(d.invoiceCount, 'invoice') : undefined} icon={Timer} tone="warning" loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <KpiCard
            label="Overdue after discharge"
            value={d ? formatINR(d.overdueAmount) : '—'}
            hint={d ? `${pluralize(d.overdueCount, 'invoice')} discharged 30 or more days ago` : undefined}
            icon={AlertTriangle}
            tone={d && d.overdueCount > 0 ? 'error' : 'neutral'}
            loading={q.isLoading}
          />
        </Grid>
        <Grid size={{ xs: 12, md: 4 }}>
          <Card sx={{ height: '100%', p: 4 }}>
            <Typography variant="body2" color="text.secondary" fontWeight={500} sx={{ mb: 2 }}>
              Aging (select to filter)
            </Typography>
            <Grid container spacing={1}>
              {(d?.buckets ?? []).map((b) => (
                <Grid key={b.bucket} size={6}>
                  <Card variant="outlined" sx={{ borderColor: bucket === b.bucket ? 'primary.main' : undefined, bgcolor: bucket === b.bucket ? colors.primarySoft : undefined }}>
                    <CardActionArea
                      onClick={() => {
                        setBucket(bucket === b.bucket ? undefined : b.bucket);
                        t.setPage(0);
                      }}
                      sx={{ p: 2 }}
                      aria-pressed={bucket === b.bucket}
                    >
                      <Typography variant="caption" color="text.secondary">
                        {BUCKET_LABEL[b.bucket]} · {b.count}
                      </Typography>
                      <Typography variant="body2" fontWeight={700} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {formatINR(b.amount, { compact: true })}
                      </Typography>
                    </CardActionArea>
                  </Card>
                </Grid>
              ))}
            </Grid>
          </Card>
        </Grid>
      </Grid>
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap alignItems="center">
            {ADMISSION_VIEWS.map((v, i) => (
              <Chip
                key={v.label}
                label={v.label}
                clickable
                color={view === i ? 'primary' : 'default'}
                variant={view === i ? 'filled' : 'outlined'}
                onClick={() => {
                  setView(i);
                  t.setPage(0);
                }}
              />
            ))}
            {bucket && <Chip label={`Aging ${BUCKET_LABEL[bucket]}`} onDelete={() => setBucket(undefined)} color="primary" variant="outlined" />}
            <FormControlLabel
              sx={{ ml: { sm: 2 } }}
              control={<Switch checked={overdue} onChange={(e) => { setOverdue(e.target.checked); t.setPage(0); }} />}
              label="Overdue only"
            />
          </Stack>
          <Grid container>
            <Grid size={{ xs: 12, md: 5 }}>
              <TextField
                fullWidth
                placeholder="Patient, MRN, invoice or admission no."
                value={t.search}
                onChange={(e) => t.setSearch(e.target.value)}
                slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search outstanding invoices' } }}
              />
            </Grid>
          </Grid>
        </Stack>
        <DataTable<OutstandingRow>
          ariaLabel="Outstanding balances"
          rows={d?.rows.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(r) => r.invoice.id}
          page={t.page}
          pageSize={t.size}
          total={d?.rows.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          rowSx={(r) => (r.overdue ? { boxShadow: `inset 3px 0 0 ${colors.danger}` } : undefined)}
          emptyTitle="Nothing outstanding in this view"
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              sortKey: 'patientName',
              render: (r) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {r.invoice.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {r.invoice.mrn} ·{' '}
                    <Typography component={Link} href={`/billing/invoices/${r.invoice.id}`} variant="caption" color="primary">
                      {r.invoice.invoiceNumber}
                    </Typography>
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'adm',
              header: 'Stay',
              render: (r) => (
                <Stack spacing={0.5} alignItems={{ xs: 'flex-end', md: 'flex-start' }}>
                  {r.invoice.admissionStatus && <StatusChip value={r.invoice.admissionStatus} />}
                  <Typography variant="caption" color="text.secondary">
                    {r.invoice.dischargedAt ? `Discharged ${formatDate(r.invoice.dischargedAt)}` : `Since ${formatDate(r.referenceDate)}`}
                  </Typography>
                </Stack>
              ),
            },
            {
              key: 'age',
              header: 'Age',
              sortKey: 'ageDays',
              render: (r) => (
                <Stack direction="row" spacing={1} alignItems="center" justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
                  <Typography variant="body2">{pluralize(r.ageDays, 'day')}</Typography>
                  {r.overdue && <StatusChip value="OVERDUE" />}
                </Stack>
              ),
            },
            { key: 'charges', header: 'Posted charges', align: 'right', hideOnMobile: true, render: (r) => <Money value={r.invoice.netCharges} /> },
            { key: 'paid', header: 'Paid & credited', align: 'right', hideOnMobile: true, render: (r) => <Money value={r.invoice.credits} /> },
            { key: 'balance', header: 'Balance due', align: 'right', sortKey: 'balance', render: (r) => <Money value={r.invoice.balance} strong /> },
            {
              key: 'last',
              header: 'Last payment',
              hideOnMobile: true,
              render: (r) => (r.invoice.lastPaymentAt ? formatDateTime(r.invoice.lastPaymentAt) : 'None yet'),
            },
            {
              key: 'act',
              header: '',
              align: 'right',
              render: (r) =>
                hasPermission('PAYMENT_RECORD') ? (
                  <Button
                    size="small"
                    startIcon={<CreditCard size={14} />}
                    onClick={() => {
                      setTarget({ id: r.invoice.id, invoiceNumber: r.invoice.invoiceNumber, patientName: r.invoice.patientName, balance: r.invoice.balance });
                    }}
                  >
                    Collect
                  </Button>
                ) : null,
            },
          ]}
        />
      </Card>
      <PaymentDialog open={!!target} invoice={target} onClose={() => setTarget(null)} />
    </>
  );
}

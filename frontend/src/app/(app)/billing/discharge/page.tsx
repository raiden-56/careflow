'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Chip, Grid, Stack, Typography } from '@mui/material';
import { BadgeIndianRupee, ClipboardCheck, ShieldAlert } from 'lucide-react';
import { useAuth } from '@/lib/auth/AuthProvider';
import { dischargeApi, dqk, type FinancialClearance, type FinancialQueueItem } from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { KpiCard } from '@/components/ui/KpiCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useDischargeMutation } from '@/components/discharge/hooks';

type Filter = 'PENDING' | 'CLEARED' | 'ALL';
const SETTLED = ['PAID', 'NO_CHARGES', 'CREDIT_BALANCE'];

export default function BillingDischargeClearancePage() {
  const { hasPermission } = useAuth();
  const canClear = hasPermission('DISCHARGE_FINANCIAL_CLEAR');
  const [filter, setFilter] = useState<Filter>('PENDING');
  const q = useQuery({ queryKey: dqk.financialQueue(filter), queryFn: () => dischargeApi.financialQueue(filter), refetchInterval: 60_000 });
  const pending = useQuery({ queryKey: dqk.financialQueue('PENDING'), queryFn: () => dischargeApi.financialQueue('PENDING') });
  const [action, setAction] = useState<{ item: FinancialQueueItem; type: FinancialClearance } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const record = useDischargeMutation(
    ({ item, type, reason }: { item: FinancialQueueItem; type: FinancialClearance; reason?: string }) =>
      dischargeApi.financialClearance(item.workflow.workflowId, type, reason),
    {
      success: (r) => (r.workflow?.financial.clearance === 'EXCEPTION' ? 'Authorized exception recorded' : 'Financial clearance recorded'),
      onSuccess: () => setAction(null),
      onError: (e) => setError(errorMessage(e)),
    },
  );

  const p = pending.data ?? [];
  const authorized = p.filter((i) => i.workflow.clinicalDecision === 'AUTHORIZED').length;
  const unsettled = p.filter((i) => i.paymentStatus && !SETTLED.includes(i.paymentStatus)).length;

  return (
    <>
      <PageHeader
        title="Discharge clearance"
        subtitle="Patients being discharged, with authoritative ledger balances. Clearance is an administrative step: it never authorizes or delays a clinical decision."
        breadcrumbs={[{ label: 'Billing', href: '/billing' }, { label: 'Discharge clearance' }]}
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Awaiting clearance" value={p.length} icon={ClipboardCheck} tone="info" loading={pending.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Clinically authorized, awaiting billing" value={authorized} icon={ShieldAlert} tone={authorized ? 'warning' : 'neutral'} loading={pending.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="With an outstanding balance" value={unsettled} icon={BadgeIndianRupee} tone={unsettled ? 'error' : 'neutral'} loading={pending.isLoading} />
        </Grid>
      </Grid>
      <Card>
        <Stack direction="row" spacing={1} sx={{ p: 4, pb: 2 }} flexWrap="wrap" useFlexGap role="group" aria-label="Filter">
          {(
            [
              ['PENDING', 'Awaiting clearance'],
              ['CLEARED', 'Cleared / exceptions (14 days)'],
              ['ALL', 'All'],
            ] as const
          ).map(([k, label]) => (
            <Chip key={k} label={label} clickable color={filter === k ? 'primary' : 'default'} variant={filter === k ? 'filled' : 'outlined'} onClick={() => setFilter(k)} />
          ))}
        </Stack>
        <DataTable<FinancialQueueItem>
          ariaLabel="Discharge clearance queue"
          rows={q.data}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(i) => i.workflow.workflowId}
          emptyTitle={filter === 'PENDING' ? 'Nothing awaiting clearance' : 'No records'}
          emptyDescription="Discharges started by the care team appear here so the final bill can be prepared."
          columns={[
            {
              key: 'patient',
              header: 'Patient',
              primary: true,
              render: (i) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {i.workflow.patient.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {i.workflow.patient.mrn} · {i.workflow.admissionNumber} · {i.workflow.wardName ?? i.workflow.departmentName}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'clinical',
              header: 'Discharge status',
              render: (i) => (
                <Stack spacing={0.5} alignItems="flex-start">
                  <StatusChip value={i.workflow.status} />
                  <Typography variant="caption" color="text.secondary">
                    {i.workflow.clinicalDecision === 'AUTHORIZED' ? 'Clinically authorized' : 'Clinical decision pending'}
                    {i.workflow.plannedDischargeAt ? ` · planned ${formatDateTime(i.workflow.plannedDischargeAt)}` : ''}
                  </Typography>
                </Stack>
              ),
            },
            {
              key: 'invoice',
              header: 'Invoice',
              render: (i) =>
                i.invoiceId ? (
                  <Stack spacing={0.5} alignItems="flex-start">
                    <Typography variant="body2" fontWeight={600}>
                      {i.invoiceNumber}
                    </Typography>
                    {i.invoiceStatus && <StatusChip value={i.invoiceStatus} />}
                  </Stack>
                ) : (
                  '—'
                ),
            },
            {
              key: 'balance',
              header: 'Balance',
              align: 'right',
              render: (i) => (
                <Stack spacing={0.5} alignItems="flex-end">
                  <Money value={i.balance} strong />
                  {i.paymentStatus && <StatusChip value={i.paymentStatus} />}
                </Stack>
              ),
            },
            {
              key: 'clearance',
              header: 'Clearance',
              render: (i) =>
                i.clearance ? (
                  <Stack spacing={0.5} alignItems="flex-start">
                    <StatusChip value={i.clearance} />
                    <Typography variant="caption" color="text.secondary">
                      {i.clearedByName} · {formatDateTime(i.clearedAt)}
                    </Typography>
                    {i.exceptionReason && (
                      <Typography variant="caption" color="text.secondary" sx={{ maxWidth: 240 }}>
                        {i.exceptionReason}
                      </Typography>
                    )}
                  </Stack>
                ) : canClear && (i.workflow.status === 'IN_PROGRESS' || i.workflow.status === 'READY') ? (
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
                    <Button
                      size="small"
                      variant="contained"
                      disabled={!!i.paymentStatus && !SETTLED.includes(i.paymentStatus)}
                      onClick={() => { setError(null); setAction({ item: i, type: 'CLEARED' }); }}
                    >
                      Clear
                    </Button>
                    {i.paymentStatus && !SETTLED.includes(i.paymentStatus) && (
                      <Button size="small" variant="outlined" color="warning" onClick={() => { setError(null); setAction({ item: i, type: 'EXCEPTION' }); }}>
                        Exception
                      </Button>
                    )}
                  </Stack>
                ) : (
                  <StatusChip value="PENDING" label="Pending" tone="warning" />
                ),
            },
          ]}
        />
      </Card>
      <Alert severity="info" sx={{ mt: 5 }}>
        Balances come from the ledger. To settle a balance, record the payment from the invoice; to waive it, use an approved adjustment.
        An authorized exception lets the patient leave with a balance under hospital policy and notifies hospital administrators.
      </Alert>
      <ConfirmDialog
        open={!!action}
        title={action?.type === 'EXCEPTION' ? 'Record authorized financial exception' : 'Record financial clearance'}
        description={
          action && (
            <>
              {action.item.workflow.patient.fullName} · {action.item.invoiceNumber ?? 'no invoice'} · balance <Money value={action.item.balance} strong />
              <br />
              {action.type === 'EXCEPTION'
                ? 'The patient may leave with this balance outstanding. The reason is audited and administrators are notified.'
                : 'Confirms the balance is settled. The clinical discharge decision is not affected.'}
            </>
          )
        }
        requireReason={action?.type === 'EXCEPTION'}
        reasonLabel="Reason and authorization (at least 10 characters)"
        confirmLabel={action?.type === 'EXCEPTION' ? 'Record exception' : 'Record clearance'}
        tone={action?.type === 'EXCEPTION' ? 'warning' : 'primary'}
        loading={record.isPending}
        error={error}
        onConfirm={(reason) => action && record.mutate({ item: action.item, type: action.type, reason })}
        onClose={() => setAction(null)}
      />
    </>
  );
}

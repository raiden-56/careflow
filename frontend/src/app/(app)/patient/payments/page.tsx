'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Card, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { Download, ShieldX, Wallet } from 'lucide-react';
import { billingApi, billingKeys, METHOD_LABEL, portalBillingApi, type PortalPayment } from '@/lib/api/billing';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { EmptyState, PageSkeleton } from '@/components/ui/States';

export default function PatientPaymentsPage() {
  const toast = useToast();
  const { patientId, patient, canSeeBilling } = usePortal();
  const q = useQuery({
    queryKey: billingKeys.portalPayments(patientId ?? ''),
    queryFn: () => portalBillingApi.payments(patientId!),
    enabled: !!patientId && canSeeBilling,
  });

  return (
    <>
      <PageHeader
        title="Payments & receipts"
        subtitle={patient && patient.relationship !== 'SELF' ? `Payments for ${patient.name}` : 'Every payment recorded against your bills, with downloadable receipts.'}
        breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Payments' }]}
        actions={<PortalPatientSwitcher />}
      />
      {!patientId ? (
        <PageSkeleton />
      ) : !canSeeBilling ? (
        <EmptyState icon={ShieldX} title="Billing is not shared with you" description="Your access to this patient's records does not include billing." />
      ) : (
        <Card>
          <DataTable<PortalPayment>
            ariaLabel="Payments"
            rows={q.data}
            loading={q.isLoading}
            error={q.error}
            onRetry={() => q.refetch()}
            rowKey={(p) => p.id}
            emptyTitle="No payments yet"
            emptyDescription="Payments made at the billing desk appear here with a receipt."
            columns={[
              {
                key: 'when',
                header: 'Date',
                primary: true,
                render: (p) => (
                  <Box>
                    <Typography variant="body2" fontWeight={600}>
                      {formatDateTime(p.receivedAt)}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      Receipt {p.receiptNumber}
                    </Typography>
                  </Box>
                ),
              },
              {
                key: 'bill',
                header: 'Bill',
                render: (p) => (
                  <Typography component={Link} href={`/patient/bills/${p.invoiceId}`} variant="body2" color="primary">
                    {p.invoiceNumber}
                  </Typography>
                ),
              },
              { key: 'method', header: 'Method', render: (p) => METHOD_LABEL[p.method] },
              { key: 'payer', header: 'Paid by', hideOnMobile: true, render: (p) => p.payerName ?? '—' },
              { key: 'amount', header: 'Amount', align: 'right', render: (p) => <Money value={p.amount} strong={p.status === 'COMPLETED'} /> },
              {
                key: 'status',
                header: 'Status',
                render: (p) => (
                  <Stack direction="row" spacing={1} justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
                    <StatusChip value={p.status} label={p.status === 'COMPLETED' ? 'Received' : 'Reversed'} />
                    {p.advance && <StatusChip value="ADVANCE" label="Advance" />}
                  </Stack>
                ),
              },
              {
                key: 'receipt',
                header: '',
                align: 'right',
                render: (p) => (
                  <Tooltip title="Download receipt (PDF)">
                    <IconButton aria-label={`Download receipt ${p.receiptNumber}`} onClick={() => billingApi.downloadReceipt(p.id, p.receiptNumber).catch(toast.error)}>
                      <Download size={18} />
                    </IconButton>
                  </Tooltip>
                ),
              },
            ]}
          />
          {q.data && q.data.length > 0 && (
            <Stack direction="row" spacing={1} alignItems="center" sx={{ px: 4, py: 3 }}>
              <Wallet size={14} />
              <Typography variant="caption" color="text.secondary">
                Reversed payments (for example a returned cheque) are kept on record and no longer count towards your bill.
              </Typography>
            </Stack>
          )}
        </Card>
      )}
    </>
  );
}

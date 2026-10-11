'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Divider, Grid, Stack, Typography } from '@mui/material';
import { FileDown, Receipt, ShieldX } from 'lucide-react';
import { billingKeys, portalBillingApi, billingApi, type PortalBillCard } from '@/lib/api/billing';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { useToast } from '@/lib/hooks';
import { formatDate, formatINR, isNegative } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { PageHeader } from '@/components/ui/PageHeader';
import { StatusChip } from '@/components/ui/StatusChip';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';

function Figure({ label, value, emphasis }: { label: string; value: string; emphasis?: boolean }) {
  return (
    <Box>
      <Typography variant="caption" color="text.secondary" component="div">
        {label}
      </Typography>
      <Typography variant={emphasis ? 'h3' : 'body1'} component="p" fontWeight={emphasis ? undefined : 600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
        {formatINR(value)}
      </Typography>
    </Box>
  );
}

function BillCard({ b }: { b: PortalBillCard }) {
  const toast = useToast();
  const credit = isNegative(b.outstanding);
  return (
    <Card sx={{ p: { xs: 4, md: 5 } }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2} sx={{ mb: 4 }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="h4" component="h2">
              {b.departmentName ? `${b.departmentName} stay` : 'Hospital bill'}
            </Typography>
            <StatusChip value={b.paymentStatus} />
            <StatusChip value={b.status} label={b.status === 'FINALIZED' ? 'Final bill' : 'Interim bill'} tone={b.status === 'FINALIZED' ? 'primary' : 'info'} />
          </Stack>
          <Typography variant="body2" color="text.secondary">
            Invoice {b.invoiceNumber}
            {b.admissionNumber ? ` · admission ${b.admissionNumber}` : ''} · {formatDate(b.admittedAt)}
            {b.dischargedAt ? ` – ${formatDate(b.dischargedAt)}` : ' – ongoing'}
          </Typography>
        </Box>
        <Box sx={{ textAlign: { sm: 'right' }, flexShrink: 0 }}>
          <Typography variant="caption" color="text.secondary">
            {credit ? 'Credit in your favour' : 'Outstanding balance'}
          </Typography>
          <Typography variant="h2" component="p" sx={{ fontVariantNumeric: 'tabular-nums', color: credit ? colors.success : undefined }}>
            {formatINR(credit ? b.outstanding.replace('-', '') : b.outstanding)}
          </Typography>
        </Box>
      </Stack>
      <Grid container spacing={3}>
        {[
          { label: 'Charges posted', value: b.totalCharges },
          { label: 'You have paid', value: b.paid },
          ...(Number(b.refunds) > 0 ? [{ label: 'Refunded to you', value: b.refunds }] : []),
          { label: 'Paid by insurance', value: b.insuranceSettled },
          { label: 'Discounts & adjustments', value: b.adjustments },
        ].map((f, _, all) => (
          <Grid key={f.label} size={{ xs: 6, md: 12 / all.length }}>
            <Figure label={f.label} value={f.value} />
          </Grid>
        ))}
      </Grid>
      {Number(b.insurancePending) > 0 && (
        <Alert severity="info" sx={{ mt: 4 }}>
          Your insurer has approved {formatINR(b.insurancePending)} that has not reached the hospital yet. It will reduce your balance once received.
        </Alert>
      )}
      {Number(b.estimatedFutureCharges) > 0 && (
        <Box sx={{ mt: 4, p: 3, borderRadius: 2, border: `1px dashed ${colors.borderStrong}`, bgcolor: colors.surfaceAlt }}>
          <Typography variant="body2">
            <strong>Estimate, not a bill:</strong> upcoming charges of about {formatINR(b.estimatedFutureCharges)} are expected, so the amount due may reach
            roughly {formatINR(b.estimatedOutstanding)}. Estimates can change and are not part of your balance.
          </Typography>
        </Box>
      )}
      <Divider sx={{ my: 4 }} />
      <Stack direction="row" spacing={2} justifyContent="flex-end" flexWrap="wrap" useFlexGap>
        <Button
          startIcon={<FileDown size={16} />}
          onClick={() => billingApi.downloadInvoicePdf(b.invoiceId, b.invoiceNumber).catch(toast.error)}
        >
          Download bill (PDF)
        </Button>
        <Button component={Link} href={`/patient/bills/${b.invoiceId}`} variant="contained" startIcon={<Receipt size={16} />}>
          View itemized bill
        </Button>
      </Stack>
    </Card>
  );
}

export default function PatientBillsPage() {
  const { patientId, patient, canSeeBilling } = usePortal();
  const q = useQuery({
    queryKey: billingKeys.portalBills(patientId ?? ''),
    queryFn: () => portalBillingApi.bills(patientId!),
    enabled: !!patientId && canSeeBilling,
  });
  return (
    <>
      <PageHeader
        title="My bills"
        subtitle={patient && patient.relationship !== 'SELF' ? `Bills for ${patient.name}` : 'Every charge, payment and insurance contribution, explained.'}
        breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Bills' }]}
        actions={<PortalPatientSwitcher />}
      />
      {!patientId ? (
        <CardsSkeleton count={2} />
      ) : !canSeeBilling ? (
        <EmptyState icon={ShieldX} title="Billing is not shared with you" description="Your access to this patient's records does not include billing. The patient can change this at the hospital." />
      ) : q.isLoading ? (
        <CardsSkeleton count={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState icon={Receipt} title="No bills yet" description="Bills appear here once you are admitted." />
      ) : (
        <Stack spacing={5}>
          {q.data.map((b) => (
            <BillCard key={b.invoiceId} b={b} />
          ))}
          <Typography variant="caption" color="text.secondary">
            Amounts are calculated by the hospital&apos;s billing system from posted charges and recorded payments. Questions? Open a bill and raise a query.
          </Typography>
        </Stack>
      )}
    </>
  );
}

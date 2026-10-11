'use client';

import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Card, Chip, Grid, Stack, Typography } from '@mui/material';
import { Pill, ShieldCheck } from 'lucide-react';
import { ck, clinicalApi, OrderView } from '@/lib/api/clinical';
import { PortalPatientSwitcher, usePortal } from '@/lib/portal';
import { formatDate } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';

const FREQ_TEXT: Record<string, string> = {
  OD: 'Once a day',
  BD: 'Twice a day',
  TDS: 'Three times a day',
  QID: 'Four times a day',
  Q4H: 'Every 4 hours',
  Q6H: 'Every 6 hours',
  Q8H: 'Every 8 hours',
  Q12H: 'Every 12 hours',
  HS: 'At bedtime',
  PRN: 'Only when needed',
  STAT: 'Single dose, given immediately',
  ONCE: 'Single dose',
  WEEKLY: 'Once a week',
};
const ROUTE_TEXT: Record<string, string> = {
  ORAL: 'by mouth',
  IV: 'into a vein (drip/injection)',
  IM: 'injection into a muscle',
  SC: 'injection under the skin',
  INHALED: 'inhaled',
  TOPICAL: 'applied to the skin',
  SUBLINGUAL: 'under the tongue',
  RECTAL: 'rectally',
  NASAL: 'in the nose',
  OPHTHALMIC: 'in the eye',
  OTIC: 'in the ear',
};

function MedCard({ o }: { o: OrderView }) {
  const stopped = o.status === 'DISCONTINUED' || o.status === 'COMPLETED';
  return (
    <Card variant="outlined" sx={{ p: { xs: 3, sm: 4 }, height: '100%', opacity: stopped ? 0.8 : 1 }}>
      <Stack direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h5" component="h3" sx={{ wordBreak: 'break-word' }}>
            {o.medicationName}
          </Typography>
          <Typography variant="body2" color="text.secondary">
            {o.dose} {o.doseUnit} · {FREQ_TEXT[o.frequency] ?? o.frequency} · {ROUTE_TEXT[o.route] ?? o.route.toLowerCase()}
          </Typography>
        </Box>
        <StatusChip value={o.status} label={o.status === 'ACTIVE' ? 'Current' : o.status === 'ON_HOLD' ? 'Paused' : 'Stopped'} />
      </Stack>
      {o.patientInstructions && (
        <Alert severity="info" variant="outlined" icon={<Pill size={18} />} sx={{ mt: 3 }}>
          {o.patientInstructions}
        </Alert>
      )}
      <Stack direction="row" spacing={1} sx={{ mt: 3 }} flexWrap="wrap" useFlexGap>
        {o.dischargeMedication && <Chip size="small" color="primary" label="Take at home after discharge" />}
        {o.scheduleTimes && o.frequency !== 'PRN' && <Chip size="small" variant="outlined" label={`Usual times: ${o.scheduleTimes.split(',').join(', ')}`} />}
      </Stack>
      <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>
        Started {formatDate(o.startAt)}
        {o.endAt ? ` · until ${formatDate(o.endAt)}` : ''}
        {o.prescribedBy ? ` · prescribed by ${o.prescribedBy}` : ''}
      </Typography>
    </Card>
  );
}

export default function PatientMedicationsPage() {
  const { patientId, patient, canSeeClinical } = usePortal();
  const q = useQuery({
    queryKey: ck.portalMedications(patientId ?? ''),
    queryFn: () => clinicalApi.portalMedications(patientId!),
    enabled: !!patientId && canSeeClinical,
  });
  const header = (
    <PageHeader
      title="Medications"
      subtitle={patient && patient.relationship !== 'SELF' ? `Medicines shared for ${patient.name}` : 'Medicines your care team has shared with you, with instructions'}
      breadcrumbs={[{ label: 'Home', href: '/patient' }, { label: 'Medications' }]}
      actions={<PortalPatientSwitcher />}
    />
  );
  if (!patientId) return <PageSkeleton />;
  if (!canSeeClinical) {
    return (
      <>
        {header}
        <Card>
          <EmptyState icon={ShieldCheck} title="Medication information is not shared with you" description="Your access to this patient covers billing information only." />
        </Card>
      </>
    );
  }
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) {
    return (
      <>
        {header}
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      </>
    );
  }
  const current = q.data.filter((o) => o.status === 'ACTIVE' || o.status === 'ON_HOLD');
  const past = q.data.filter((o) => o.status !== 'ACTIVE' && o.status !== 'ON_HOLD');
  return (
    <>
      {header}
      <Alert severity="warning" sx={{ mb: 5 }}>
        Do not start, stop or change any medicine without talking to your doctor. Medicines given in hospital are managed by your nurses.
      </Alert>
      {q.data.length === 0 ? (
        <Card>
          <EmptyState icon={Pill} title="No medicines shared yet" description="Your care team shares medicine information and instructions here, especially before you go home." />
        </Card>
      ) : (
        <Stack spacing={5}>
          {current.length > 0 && (
            <Box>
              <Typography variant="h4" component="h2" sx={{ mb: 3 }}>
                Current medicines
              </Typography>
              <Grid container spacing={4}>
                {current.map((o) => (
                  <Grid key={o.id} size={{ xs: 12, md: 6 }}>
                    <MedCard o={o} />
                  </Grid>
                ))}
              </Grid>
            </Box>
          )}
          {past.length > 0 && (
            <Box>
              <Typography variant="h4" component="h2" sx={{ mb: 3 }}>
                Earlier medicines
              </Typography>
              <Grid container spacing={4}>
                {past.map((o) => (
                  <Grid key={o.id} size={{ xs: 12, md: 6 }}>
                    <MedCard o={o} />
                  </Grid>
                ))}
              </Grid>
            </Box>
          )}
        </Stack>
      )}
    </>
  );
}

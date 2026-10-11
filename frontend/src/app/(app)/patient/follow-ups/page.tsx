'use client';

import { useQuery } from '@tanstack/react-query';
import { Box, Card, Chip, Grid, Stack, Typography } from '@mui/material';
import { CalendarClock, MapPin, Stethoscope } from 'lucide-react';
import { dischargeApi, dqk, FOLLOW_UP_TYPE_LABEL, type PortalFollowUp } from '@/lib/api/discharge';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

function FollowUpItem({ f, past }: { f: PortalFollowUp; past?: boolean }) {
  return (
    <Card variant="outlined" sx={{ boxShadow: 'none', borderLeft: `4px solid ${past ? colors.border : colors.primary}` }}>
      <Stack spacing={1.5} sx={{ p: 3 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'center' }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ minWidth: 0 }}>
            <Chip size="small" variant="outlined" label={FOLLOW_UP_TYPE_LABEL[f.type]} />
            <Typography variant="subtitle2" sx={{ wordBreak: 'break-word' }}>
              {f.title}
            </Typography>
          </Stack>
          <StatusChip value={f.status} sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />
        </Stack>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 1, md: 4 }} sx={{ color: 'text.secondary' }}>
          <Stack direction="row" spacing={1} alignItems="center">
            <CalendarClock size={14} aria-hidden />
            <Typography variant="body2">{f.scheduledAt ? `${formatDateTime(f.scheduledAt)}${past ? '' : ` (${fromNow(f.scheduledAt)})`}` : 'Date to be confirmed'}</Typography>
          </Stack>
          {f.location && (
            <Stack direction="row" spacing={1} alignItems="center">
              <MapPin size={14} aria-hidden />
              <Typography variant="body2">{f.location}</Typography>
            </Stack>
          )}
          {(f.clinicianName || f.departmentName) && (
            <Stack direction="row" spacing={1} alignItems="center">
              <Stethoscope size={14} aria-hidden />
              <Typography variant="body2">{[f.clinicianName, f.departmentName].filter(Boolean).join(' · ')}</Typography>
            </Stack>
          )}
        </Stack>
        {f.instructions && (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
            {f.instructions}
          </Typography>
        )}
        {!past && f.patientNotifiedAt && (
          <Typography variant="caption" color="text.secondary">
            We sent you the details on {formatDateTime(f.patientNotifiedAt)}.
          </Typography>
        )}
      </Stack>
    </Card>
  );
}

export default function PatientFollowUpsPage() {
  const { patientId, patient, canSeeClinical } = usePortal();
  const q = useQuery({ queryKey: dqk.portalFollowUps(patientId ?? ''), queryFn: () => dischargeApi.portalFollowUps(patientId!), enabled: !!patientId && canSeeClinical });
  const header = (
    <PageHeader
      title="Follow-up appointments"
      subtitle={patient && patient.relationship !== 'SELF' ? `Viewing ${patient.name}'s follow-ups` : 'Appointments, instructions and tasks after your hospital stay.'}
      breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Follow-up appointments' }]}
      actions={<PortalPatientSwitcher />}
    />
  );
  if (!patientId || (canSeeClinical && q.isLoading)) return <PageSkeleton />;
  if (!canSeeClinical) {
    return (
      <>
        {header}
        <Card>
          <EmptyState title="Not available with your access" description="Your caregiver access covers billing only." />
        </Card>
      </>
    );
  }
  if (q.error || !q.data) return <>{header}<ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  return (
    <>
      {header}
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 7 }}>
          <SectionCard title={`Upcoming (${q.data.upcoming.length})`}>
            {q.data.upcoming.length === 0 ? (
              <EmptyState icon={CalendarClock} title="No upcoming follow-ups" description="New appointments from your care team will appear here and be sent to you." compact />
            ) : (
              <Stack spacing={3}>
                {q.data.upcoming.map((f) => (
                  <FollowUpItem key={f.id} f={f} />
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
        <Grid size={{ xs: 12, lg: 5 }}>
          <SectionCard title="Past">
            {q.data.past.length === 0 ? (
              <Typography variant="body2" color="text.secondary">
                No past follow-ups.
              </Typography>
            ) : (
              <Stack spacing={3}>
                {q.data.past.map((f) => (
                  <Box key={f.id}>
                    <FollowUpItem f={f} past />
                  </Box>
                ))}
              </Stack>
            )}
          </SectionCard>
        </Grid>
      </Grid>
    </>
  );
}

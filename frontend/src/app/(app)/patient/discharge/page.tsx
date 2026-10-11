'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  Checkbox,
  FormControlLabel,
  Grid,
  LinearProgress,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { CalendarClock, CheckCircle2, CircleDashed, Download, FileCheck2, Loader, Pill, ShieldAlert } from 'lucide-react';
import { dischargeApi, dqk, type PortalDischarge } from '@/lib/api/discharge';
import { usePortal, PortalPatientSwitcher } from '@/lib/portal';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { useDischargeMutation } from '@/components/discharge/hooks';
import { colors } from '@/theme/tokens';

const SUMMARY_SECTIONS: { key: keyof NonNullable<PortalDischarge['summary']>; label: string }[] = [
  { key: 'diagnosesText', label: 'Your diagnosis' },
  { key: 'treatmentSummary', label: 'What happened in hospital' },
  { key: 'proceduresText', label: 'Procedures' },
  { key: 'relevantResults', label: 'Test results' },
  { key: 'conditionAtDischarge', label: 'How you were when you left' },
  { key: 'dischargeInstructions', label: 'Looking after yourself at home' },
  { key: 'followUpPlan', label: 'Follow-up' },
];

export default function PatientDischargePage() {
  const { patientId, patient, canSeeClinical } = usePortal();
  const toast = useToast();
  const q = useQuery({ queryKey: dqk.portal(patientId ?? ''), queryFn: () => dischargeApi.portal(patientId!), enabled: !!patientId && canSeeClinical });

  const header = (
    <PageHeader
      title="Discharge information"
      subtitle={patient && patient.relationship !== 'SELF' ? `Viewing ${patient.name}'s records` : 'Your discharge progress, summary, medicines and instructions.'}
      breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Discharge information' }]}
      actions={<PortalPatientSwitcher />}
    />
  );

  if (!patientId || (canSeeClinical && q.isLoading)) return <PageSkeleton />;
  if (!canSeeClinical) {
    return (
      <>
        {header}
        <Card>
          <EmptyState title="Not available with your access" description="Your caregiver access covers billing only. Ask the patient or the hospital to extend it." />
        </Card>
      </>
    );
  }
  if (q.error) return <>{header}<ErrorState error={q.error} onRetry={() => q.refetch()} /></>;
  const d = q.data?.discharge;
  if (!d) {
    return (
      <>
        {header}
        <Card>
          <EmptyState icon={FileCheck2} title="No discharge information yet" description="When your care team starts preparing your discharge, you can follow each step here." />
        </Card>
      </>
    );
  }
  const discharged = d.admissionStatus === 'DISCHARGED';

  return (
    <>
      {header}
      <Card sx={{ mb: 5 }}>
        <Box sx={{ p: 4 }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="h3" component="h2">
                {d.headline}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                {d.departmentName} · {d.doctorName} · admitted {formatDate(d.admittedAt)}
                {d.dischargedAt ? ` · discharged ${formatDateTime(d.dischargedAt)}` : ''}
              </Typography>
            </Box>
            <StatusChip value={d.admissionStatus} size="medium" sx={{ alignSelf: { xs: 'flex-start', sm: 'center' } }} />
          </Stack>
          {d.progress && !discharged && (
            <Box sx={{ mt: 4 }}>
              <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
                <Typography variant="body2" fontWeight={600}>
                  {d.progress.completed} of {d.progress.total} steps done
                </Typography>
                {d.plannedDischargeAt && (
                  <Typography variant="body2" color="text.secondary">
                    Planned for {formatDateTime(d.plannedDischargeAt)} (may change)
                  </Typography>
                )}
              </Stack>
              <LinearProgress variant="determinate" value={d.progress.percent} sx={{ height: 10, borderRadius: 999 }} aria-label="Discharge progress" />
            </Box>
          )}
          {d.doctorApproved && !discharged && (
            <Alert severity="success" sx={{ mt: 4 }}>
              Your doctor approved your discharge on {formatDateTime(d.doctorApprovedAt)}.
            </Alert>
          )}
        </Box>
      </Card>

      {d.warningSigns && (
        <Alert
          severity="error"
          icon={<ShieldAlert size={22} />}
          sx={{ mb: 5, alignItems: 'flex-start', bgcolor: colors.dangerSoft, border: `1px solid ${colors.danger}`, borderLeft: `6px solid ${colors.danger}`, color: colors.ink }}
        >
          <AlertTitle sx={{ fontWeight: 700, color: colors.danger }}>Get medical help straight away if you notice</AlertTitle>
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
            {d.warningSigns}
          </Typography>
        </Alert>
      )}

      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            {d.steps.length > 0 && (
              <SectionCard title="Your discharge steps" subtitle="Updated by your care team as each step is completed.">
                <Stack spacing={2.5} component="ol" sx={{ listStyle: 'none', m: 0, p: 0 }}>
                  {d.steps.map((s, i) => {
                    const Icon = s.status === 'DONE' ? CheckCircle2 : s.status === 'IN_PROGRESS' ? Loader : CircleDashed;
                    const color = s.status === 'DONE' ? colors.success : s.status === 'IN_PROGRESS' ? colors.info : colors.inkSubtle;
                    return (
                      <Stack component="li" key={i} direction="row" spacing={2} alignItems="flex-start">
                        <Box sx={{ color, display: 'flex', pt: 0.25 }} aria-hidden>
                          <Icon size={20} />
                        </Box>
                        <Box>
                          <Typography variant="body2" fontWeight={s.status === 'DONE' ? 500 : 600}>
                            {s.title}
                          </Typography>
                          <Typography variant="caption" color="text.secondary">
                            {s.statusLabel}
                            {s.completedAt ? ` · ${formatDateTime(s.completedAt)}` : ''}
                          </Typography>
                        </Box>
                      </Stack>
                    );
                  })}
                </Stack>
              </SectionCard>
            )}
            <AcknowledgeCard d={d} patientId={patientId} />
            <Card>
              <Stack direction="row" spacing={2} alignItems="center" sx={{ p: 4 }}>
                <CalendarClock size={20} color={colors.primary} aria-hidden />
                <Typography variant="body2" sx={{ flex: 1 }}>
                  {d.upcomingFollowUps
                    ? `You have ${d.upcomingFollowUps} upcoming follow-up item${d.upcomingFollowUps === 1 ? '' : 's'}.`
                    : 'No upcoming follow-up appointments.'}
                </Typography>
                <Button component={Link} href="/patient/follow-ups" size="small">
                  View
                </Button>
              </Stack>
            </Card>
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, lg: 7 }}>
          <Stack spacing={5}>
            <SectionCard title="Your medicines to take at home" subtitle={d.medications.length ? 'Follow these instructions unless your doctor tells you otherwise.' : undefined}>
              {d.medications.length === 0 ? (
                <EmptyState
                  icon={Pill}
                  title="No take-home medicines shared yet"
                  description="Your take-home medicines appear here once your doctor shares your discharge summary."
                  compact
                />
              ) : (
                <Stack spacing={3}>
                  {d.medications.map((m, i) => (
                    <Box key={i} sx={{ p: 3, borderRadius: 2, bgcolor: colors.surfaceAlt, border: `1px solid ${colors.border}` }}>
                      <Typography variant="body2" fontWeight={600}>
                        {m.name}
                      </Typography>
                      <Typography variant="body2" color="text.secondary">
                        {m.dose}, {m.frequency} ({m.route}){m.durationDays ? ` for ${m.durationDays} days` : ''}
                      </Typography>
                      {m.instructions && (
                        <Typography variant="body2" sx={{ mt: 1 }}>
                          {m.instructions}
                        </Typography>
                      )}
                    </Box>
                  ))}
                </Stack>
              )}
            </SectionCard>
            <SectionCard
              title="Discharge summary"
              action={
                d.summary && (
                  <Button size="small" variant="outlined" startIcon={<Download size={14} />} onClick={() => dischargeApi.downloadSummaryPdf(d.admissionId, d.admissionNumber).catch(toast.error)}>
                    Download PDF
                  </Button>
                )
              }
              subtitle={d.summary?.finalizedAt ? `Prepared by ${d.summary.finalizedByName} · ${formatDateTime(d.summary.finalizedAt)}` : undefined}
            >
              {!d.summary ? (
                <EmptyState icon={FileCheck2} title="Not shared yet" description="Your doctor will share the discharge summary when it is ready." compact />
              ) : (
                <Stack spacing={4}>
                  {d.summary.status === 'AMENDED' && <Alert severity="info">This summary was updated by your doctor after it was first shared.</Alert>}
                  {SUMMARY_SECTIONS.filter((s) => d.summary?.[s.key]).map((s) => (
                    <Box key={s.key}>
                      <Typography variant="subtitle2" component="h3">
                        {s.label}
                      </Typography>
                      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 0.5 }}>
                        {String(d.summary?.[s.key])}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              )}
            </SectionCard>
          </Stack>
        </Grid>
      </Grid>
    </>
  );
}

function AcknowledgeCard({ d, patientId }: { d: PortalDischarge; patientId: string }) {
  const [confirmed, setConfirmed] = useState(false);
  const [comment, setComment] = useState('');
  const ack = useDischargeMutation(() => dischargeApi.acknowledge(patientId, comment.trim() || undefined), {
    success: 'Thank you - your confirmation was recorded',
  });
  const a = d.acknowledgement;
  return (
    <SectionCard title="Confirm your instructions">
      {a.acknowledged ? (
        <Alert severity="success">
          Confirmed by {a.acknowledgedByName} on {formatDateTime(a.acknowledgedAt)}.
        </Alert>
      ) : a.available ? (
        <Stack spacing={3}>
          <Typography variant="body2" color="text.secondary">
            Please read your summary, medicines and warning signs. If anything is unclear, ask your nurse or call the hospital before confirming.
          </Typography>
          <FormControlLabel
            control={<Checkbox checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />}
            label="I have read and understood my discharge instructions, medicines and warning signs."
          />
          <TextField label="Questions or comments (optional)" value={comment} onChange={(e) => setComment(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 1000 } }} />
          <Box>
            <Button variant="contained" disabled={!confirmed || ack.isPending} onClick={() => ack.mutate(undefined)}>
              {ack.isPending ? 'Saving…' : 'Confirm'}
            </Button>
          </Box>
        </Stack>
      ) : (
        <Typography variant="body2" color="text.secondary">
          {a.unavailableReason}
        </Typography>
      )}
    </SectionCard>
  );
}

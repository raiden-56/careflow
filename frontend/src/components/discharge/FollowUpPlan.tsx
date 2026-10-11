'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { BellRing, CalendarClock, CalendarPlus, MapPin, Stethoscope, UserRound } from 'lucide-react';
import { dischargeApi, dqk, FOLLOW_UP_TYPE_LABEL, type FollowUpView } from '@/lib/api/discharge';
import { formatDateTime, fromNow } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { SectionCard } from '@/components/ui/SectionCard';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { useDischargeMutation } from './hooks';
import { FollowUpDialog, FollowUpOutcomeDialog } from './FollowUpDialogs';

const ACTIVE = ['PLANNED', 'SCHEDULED'];

/** One follow-up with its notification trail and role-aware actions. */
export function FollowUpCard({
  f,
  canManage,
  onEdit,
  onOutcome,
  showPatient,
  patientHref,
}: {
  f: FollowUpView;
  canManage: boolean;
  onEdit?: (f: FollowUpView) => void;
  onOutcome: (f: FollowUpView, o: 'COMPLETED' | 'MISSED' | 'CANCELLED') => void;
  showPatient?: boolean;
  patientHref?: string;
}) {
  const active = ACTIVE.includes(f.status);
  const resend = useDischargeMutation(() => dischargeApi.resendFollowUp(f.id), { success: 'Notification sent again' });
  const latestByChannel = new Map<string, FollowUpView['deliveries'][number]>();
  f.deliveries.forEach((d) => {
    if (!latestByChannel.has(d.channel)) latestByChannel.set(d.channel, d);
  });
  return (
    <Card variant="outlined" sx={{ boxShadow: 'none', borderLeft: `4px solid ${f.outcomeDue ? colors.warning : active ? colors.primary : colors.border}` }}>
      <Stack spacing={2} sx={{ p: 3 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Chip size="small" variant="outlined" label={FOLLOW_UP_TYPE_LABEL[f.type]} />
              <Typography variant="subtitle2" sx={{ wordBreak: 'break-word' }}>
                {f.title}
              </Typography>
            </Stack>
            {showPatient && (
              <Typography variant="body2" sx={{ mt: 0.5 }}>
                {patientHref ? (
                  <Box component={Link} href={patientHref} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                    {f.patientName}
                  </Box>
                ) : (
                  f.patientName
                )}{' '}
                <Typography component="span" variant="caption" color="text.secondary">
                  {f.patientMrn} · {f.admissionNumber}
                </Typography>
              </Typography>
            )}
          </Box>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ flexShrink: 0 }}>
            {f.outcomeDue && <StatusChip value="OVERDUE" label="Outcome due" tone="warning" />}
            <StatusChip value={f.status} />
          </Stack>
        </Stack>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={{ xs: 1, md: 4 }} flexWrap="wrap" useFlexGap sx={{ color: 'text.secondary' }}>
          <Meta icon={<CalendarClock size={14} />} text={f.scheduledAt ? `${formatDateTime(f.scheduledAt)} (${fromNow(f.scheduledAt)})` : 'No date set'} />
          {f.location && <Meta icon={<MapPin size={14} />} text={f.location} />}
          {(f.clinicianName || f.departmentName) && <Meta icon={<Stethoscope size={14} />} text={[f.clinicianName, f.departmentName].filter(Boolean).join(' · ')} />}
          {f.responsibleName && <Meta icon={<UserRound size={14} />} text={`Responsible: ${f.responsibleName}`} />}
        </Stack>
        {f.labOrderLabel && (
          <Typography variant="body2">
            Investigation: <strong>{f.labOrderLabel}</strong>
          </Typography>
        )}
        {f.description && (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
            {f.description}
          </Typography>
        )}
        {f.outcomeNotes && (
          <Typography variant="body2" color="text.secondary">
            Outcome{f.outcomeRecordedByName ? ` (${f.outcomeRecordedByName})` : ''}: {f.outcomeNotes}
          </Typography>
        )}
        {f.type !== 'RESULT_REVIEW' && (
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="caption" color="text.secondary" sx={{ mr: 1 }}>
              {f.patientNotifiedAt ? `Patient notified ${formatDateTime(f.patientNotifiedAt)}` : 'Patient not notified'}
              {f.reminderSentAt ? ` · reminder ${formatDateTime(f.reminderSentAt)}` : ''}
            </Typography>
            {[...latestByChannel.values()].map((d) => (
              <Tooltip key={d.channel} title={`${d.event.toLowerCase()} via ${d.provider}${d.destination ? ` to ${d.destination}` : ''}${d.error ? ` - ${d.error}` : ''}`}>
                <span>
                  <StatusChip value={d.status} label={`${d.channel.replace('_', '-').toLowerCase()}: ${d.status.toLowerCase()}`} />
                </span>
              </Tooltip>
            ))}
          </Stack>
        )}
        {canManage && active && (
          <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
            {onEdit && (
              <Button size="small" onClick={() => onEdit(f)}>
                Edit
              </Button>
            )}
            <Button size="small" onClick={() => onOutcome(f, 'COMPLETED')}>
              Complete
            </Button>
            {f.scheduledAt && new Date(f.scheduledAt) < new Date() && (
              <Button size="small" onClick={() => onOutcome(f, 'MISSED')}>
                Missed
              </Button>
            )}
            <Button size="small" color="error" onClick={() => onOutcome(f, 'CANCELLED')}>
              Cancel
            </Button>
            {f.type !== 'RESULT_REVIEW' && (
              <Button size="small" startIcon={<BellRing size={14} />} onClick={() => resend.mutate(undefined)} disabled={resend.isPending}>
                Re-send to patient
              </Button>
            )}
          </Stack>
        )}
      </Stack>
    </Card>
  );
}

function Meta({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <Stack direction="row" spacing={1} alignItems="center" sx={{ minWidth: 0 }}>
      <Box sx={{ display: 'flex', flexShrink: 0 }} aria-hidden>
        {icon}
      </Box>
      <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
        {text}
      </Typography>
    </Stack>
  );
}

/** Follow-up plan of one admission (clinicians). */
export function FollowUpPlan({ admissionId, canManage }: { admissionId: string; canManage: boolean }) {
  const q = useQuery({ queryKey: dqk.followUps(admissionId), queryFn: () => dischargeApi.followUps(admissionId) });
  const [editing, setEditing] = useState<FollowUpView | undefined>();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [outcome, setOutcome] = useState<{ f: FollowUpView; o: 'COMPLETED' | 'MISSED' | 'CANCELLED' } | null>(null);
  const list = q.data ?? [];
  const active = list.filter((f) => ACTIVE.includes(f.status));
  const closed = list.filter((f) => !ACTIVE.includes(f.status));
  return (
    <SectionCard
      title="Follow-up plan"
      subtitle="Appointments, result reviews, instructions and tasks after discharge. Patients are notified through the hospital's configured channels."
      action={
        canManage && (
          <Button variant="contained" size="small" startIcon={<CalendarPlus size={14} />} onClick={() => { setEditing(undefined); setDialogOpen(true); }}>
            Add
          </Button>
        )
      }
    >
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState icon={CalendarClock} title="No follow-up documented" description="Add follow-up appointments, result reviews and responsibilities before discharge." compact />
      ) : (
        <Stack spacing={3}>
          {active.map((f) => (
            <FollowUpCard key={f.id} f={f} canManage={canManage} onEdit={(x) => { setEditing(x); setDialogOpen(true); }} onOutcome={(x, o) => setOutcome({ f: x, o })} />
          ))}
          {closed.length > 0 && (
            <Typography variant="overline" color="text.secondary" sx={{ pt: 2 }}>
              Completed, missed and cancelled
            </Typography>
          )}
          {closed.map((f) => (
            <FollowUpCard key={f.id} f={f} canManage={false} onOutcome={() => undefined} />
          ))}
        </Stack>
      )}
      <FollowUpDialog open={dialogOpen} admissionId={admissionId} followUp={editing} onClose={() => setDialogOpen(false)} />
      <FollowUpOutcomeDialog followUp={outcome?.f ?? null} outcome={outcome?.o ?? null} onClose={() => setOutcome(null)} />
    </SectionCard>
  );
}

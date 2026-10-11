'use client';

import { useEffect, useState } from 'react';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Card,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  LinearProgress,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import {
  AlertOctagon,
  BedDouble,
  CalendarClock,
  CheckCircle2,
  Circle,
  CircleDashed,
  CircleSlash,
  DoorOpen,
  FileCheck2,
  Loader,
  Pencil,
  PlayCircle,
  XCircle,
} from 'lucide-react';
import {
  dischargeApi,
  DISPOSITION_LABEL,
  ROLE_TEAM,
  type BlockerView,
  type DischargeDetail,
  type StepStatus,
  type StepView,
  type WorkflowView,
} from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { formatDateTime, fromNow, isoToLocalInput, localInputToIso } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { SectionCard } from '@/components/ui/SectionCard';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { ageLabel, useDischargeMutation } from './hooks';
import { ClinicalDecisionCard, FinancialClearanceCard } from './DecisionPanels';
import { StepUpdateDialog } from './StepUpdateDialog';
import { CompleteDischargeDialog } from './CompleteDischargeDialog';

const STEP_ICON: Record<StepStatus, { icon: typeof Circle; color: string }> = {
  COMPLETED: { icon: CheckCircle2, color: colors.success },
  IN_PROGRESS: { icon: Loader, color: colors.info },
  BLOCKED: { icon: AlertOctagon, color: colors.danger },
  NOT_APPLICABLE: { icon: CircleSlash, color: colors.neutral },
  NOT_STARTED: { icon: CircleDashed, color: colors.inkSubtle },
};

export interface DischargePanelProps {
  detail: DischargeDetail;
  /** Show the bed turnover card (admin / nursing views). */
  showBed?: boolean;
  /** Emphasise departure recording (nursing / admin). */
  emphasizeCompletion?: boolean;
  /** Navigate here after the discharge is completed. */
  afterCompleteHref?: string;
}

/**
 * Shared discharge workflow panel: progress, computed blockers, the two separate decision panels (clinical vs
 * financial), the configurable checklist and role-aware actions. Every action is re-validated by the API.
 */
export function DischargePanel({ detail, showBed, emphasizeCompletion, afterCompleteHref }: DischargePanelProps) {
  const w = detail.workflow;
  const [stepOpen, setStepOpen] = useState<StepView | null>(null);
  const [startOpen, setStartOpen] = useState(false);
  const [planOpen, setPlanOpen] = useState(false);
  const [cancelOpen, setCancelOpen] = useState(false);
  const [completeOpen, setCompleteOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const cancel = useDischargeMutation((reason: string) => dischargeApi.cancel(w!.id, reason), {
    success: 'Discharge workflow cancelled',
    onSuccess: () => setCancelOpen(false),
    onError: (e) => setError(errorMessage(e)),
  });

  const isOpen = w && (w.status === 'IN_PROGRESS' || w.status === 'READY');

  return (
    <Stack spacing={5}>
      {(!w || !isOpen) && detail.actions.canInitiate && (
        <Card>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ sm: 'center' }} justifyContent="space-between" sx={{ p: 4 }}>
            <Box>
              <Typography variant="h4" component="h2">
                {w?.status === 'CANCELLED' ? 'Discharge was postponed' : 'No discharge workflow yet'}
              </Typography>
              <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                Starting discharge planning creates the hospital&apos;s checklist and informs the care team, ward nurses, pharmacy and billing.
              </Typography>
            </Box>
            <Button variant="contained" startIcon={<PlayCircle size={16} />} onClick={() => setStartOpen(true)}>
              Start discharge planning
            </Button>
          </Stack>
        </Card>
      )}
      {!w && !detail.actions.canInitiate && (
        <Card>
          <EmptyState icon={FileCheck2} title="No discharge workflow" description={detail.actions.initiateBlockedReason ?? 'Discharge planning has not started.'} compact />
        </Card>
      )}

      {w && (
        <>
          <OverviewCard
            workflow={w}
            detail={detail}
            emphasizeCompletion={emphasizeCompletion}
            onPlan={() => setPlanOpen(true)}
            onCancel={() => { setError(null); setCancelOpen(true); }}
            onComplete={() => setCompleteOpen(true)}
          />
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, md: 6 }}>
              <ClinicalDecisionCard workflow={w} canAuthorize={detail.actions.canAuthorize} />
            </Grid>
            <Grid size={{ xs: 12, md: 6 }}>
              <FinancialClearanceCard workflow={w} canClear={detail.actions.canClearFinancially} />
            </Grid>
          </Grid>
          {isOpen && (
            <SectionCard
              title={`Discharge blockers (${w.blockers.length})`}
              subtitle="Derived only from the hospital's configured checklist and recorded data. Each shows who owns it and the next action."
            >
              {w.blockers.length === 0 ? (
                <Alert severity="success" icon={<CheckCircle2 size={18} />}>
                  No blockers. All required steps are complete.
                </Alert>
              ) : (
                <Grid container spacing={3}>
                  {w.blockers.map((b) => (
                    <Grid key={b.stepId} size={{ xs: 12, md: 6 }}>
                      <BlockerCard blocker={b} step={w.steps.find((s) => s.id === b.stepId)} onOpen={setStepOpen} />
                    </Grid>
                  ))}
                </Grid>
              )}
            </SectionCard>
          )}
          <SectionCard title="Discharge checklist" subtitle="Steps configured by the hospital. Automatic checks update as records change." flush>
            <Stack divider={<Divider flexItem />}>
              {w.steps.map((s) => (
                <StepRow key={s.id} step={s} onOpen={setStepOpen} />
              ))}
            </Stack>
          </SectionCard>
          {showBed && detail.bed && (
            <SectionCard title="Bed and turnover">
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ xs: 'flex-start', sm: 'center' }}>
                <Stack direction="row" spacing={2} alignItems="center" sx={{ flex: 1, minWidth: 0 }}>
                  <BedDouble size={18} color={colors.inkMuted} aria-hidden />
                  <Typography variant="body2" sx={{ wordBreak: 'break-word' }}>
                    {detail.bed.bedLabel}
                  </Typography>
                </Stack>
                <StatusChip value={detail.bed.status} />
                <Typography variant="caption" color="text.secondary">
                  {detail.bed.released ? 'Released at discharge' : 'Currently occupied'} · status since {formatDateTime(detail.bed.statusChangedAt)}
                </Typography>
              </Stack>
            </SectionCard>
          )}
        </>
      )}

      {detail.history.length > 0 && (
        <SectionCard title="Earlier discharge workflows">
          <Stack spacing={2}>
            {detail.history.map((h) => (
              <Stack key={h.id} direction={{ xs: 'column', sm: 'row' }} spacing={2} alignItems={{ xs: 'flex-start', sm: 'center' }}>
                <StatusChip value={h.status} />
                <Typography variant="body2">
                  Started {formatDateTime(h.initiatedAt)} by {h.initiatedByName ?? '—'}
                  {h.cancelledReason ? ` · Cancelled: ${h.cancelledReason}` : ''}
                </Typography>
              </Stack>
            ))}
          </Stack>
        </SectionCard>
      )}

      {w && <StepUpdateDialog workflowId={w.id} step={stepOpen} onClose={() => setStepOpen(null)} />}
      <StartDialog open={startOpen} admissionId={detail.admission.id} onClose={() => setStartOpen(false)} />
      {w && <PlanDialog open={planOpen} workflow={w} onClose={() => setPlanOpen(false)} />}
      {w && <CompleteDischargeDialog open={completeOpen} workflow={w} bedLabel={detail.bed?.bedLabel} afterCompleteHref={afterCompleteHref} onClose={() => setCompleteOpen(false)} />}
      <ConfirmDialog
        open={cancelOpen}
        title="Cancel this discharge workflow?"
        description="The patient stays admitted and the checklist is closed. The care team, ward nurses and billing are informed."
        requireReason
        reasonLabel="Reason for cancelling"
        tone="error"
        confirmLabel="Cancel workflow"
        loading={cancel.isPending}
        error={error}
        onConfirm={(reason) => cancel.mutate(reason!)}
        onClose={() => setCancelOpen(false)}
      />
    </Stack>
  );
}

function OverviewCard({
  workflow: w,
  detail,
  emphasizeCompletion,
  onPlan,
  onCancel,
  onComplete,
}: {
  workflow: WorkflowView;
  detail: DischargeDetail;
  emphasizeCompletion?: boolean;
  onPlan: () => void;
  onCancel: () => void;
  onComplete: () => void;
}) {
  const open = w.status === 'IN_PROGRESS' || w.status === 'READY';
  return (
    <Card>
      <Box sx={{ p: 4 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} justifyContent="space-between" alignItems={{ md: 'flex-start' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="h3" component="h2">
                Discharge workflow
              </Typography>
              <StatusChip value={w.status} />
              <Chip size="small" variant="outlined" label={`Stage: ${w.stageLabel}`} />
            </Stack>
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
              Started {formatDateTime(w.initiatedAt)} by {w.initiatedByName ?? '—'} · open {ageLabel(w.ageHours)}
            </Typography>
          </Box>
          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
            {open && detail.actions.canEditPlan && (
              <Button size="small" variant="outlined" startIcon={<Pencil size={14} />} onClick={onPlan}>
                Planned time
              </Button>
            )}
            {open && detail.actions.canCancel && (
              <Button size="small" color="error" startIcon={<XCircle size={14} />} onClick={onCancel}>
                Cancel workflow
              </Button>
            )}
          </Stack>
        </Stack>

        <Box sx={{ mt: 4 }}>
          <Stack direction="row" justifyContent="space-between" sx={{ mb: 1 }}>
            <Typography variant="body2" fontWeight={600}>
              {w.progress.completed} of {w.progress.total} steps complete
            </Typography>
            <Typography variant="body2" color="text.secondary">
              {w.progress.percent}%
            </Typography>
          </Stack>
          <LinearProgress
            variant="determinate"
            value={w.progress.percent}
            aria-label="Discharge progress"
            color={w.status === 'COMPLETED' || w.status === 'READY' ? 'success' : 'primary'}
            sx={{ height: 8, borderRadius: 999 }}
          />
        </Box>

        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={4} sx={{ mt: 3 }} flexWrap="wrap" useFlexGap>
          <Stack direction="row" spacing={1.5} alignItems="center">
            <CalendarClock size={16} color={colors.inkMuted} aria-hidden />
            <Typography variant="body2">
              Planned: {w.plannedDischargeAt ? `${formatDateTime(w.plannedDischargeAt)} (${fromNow(w.plannedDischargeAt)})` : 'not set'}
            </Typography>
          </Stack>
          {w.actualDepartureAt && (
            <Stack direction="row" spacing={1.5} alignItems="center">
              <DoorOpen size={16} color={colors.inkMuted} aria-hidden />
              <Typography variant="body2">
                Departed {formatDateTime(w.actualDepartureAt)} · {w.disposition ? DISPOSITION_LABEL[w.disposition] : ''} · recorded by {w.completedByName}
              </Typography>
            </Stack>
          )}
        </Stack>

        {w.status === 'CANCELLED' && (
          <Alert severity="info" sx={{ mt: 4 }}>
            Cancelled{w.cancelledReason ? `: ${w.cancelledReason}` : ''}
          </Alert>
        )}
        {w.status === 'COMPLETED' && (
          <Alert severity="success" sx={{ mt: 4 }}>
            Discharge completed {formatDateTime(w.completedAt)}.
          </Alert>
        )}
        {open && (
          <Alert severity={w.readiness.canComplete ? 'success' : 'info'} sx={{ mt: 4 }}>
            <AlertTitle>{w.readiness.canComplete ? 'Ready for departure' : 'Not ready for departure yet'}</AlertTitle>
            {w.readiness.canComplete
              ? 'All required steps, the clinical authorization and the financial requirement are complete.'
              : `Outstanding: ${w.readiness.outstanding.join(' · ')}`}
            {detail.actions.canComplete && (
              <Box sx={{ mt: 2 }}>
                <Button
                  color={w.readiness.canComplete ? 'success' : 'inherit'}
                  size="small"
                  variant={emphasizeCompletion && w.readiness.canComplete ? 'contained' : 'outlined'}
                  startIcon={<DoorOpen size={14} />}
                  onClick={onComplete}
                >
                  Record departure
                </Button>
              </Box>
            )}
          </Alert>
        )}
      </Box>
    </Card>
  );
}

function BlockerCard({ blocker: b, step, onOpen }: { blocker: BlockerView; step?: StepView; onOpen: (s: StepView) => void }) {
  const blocked = b.stepStatus === 'BLOCKED';
  return (
    <Card
      variant="outlined"
      sx={{ height: '100%', borderColor: blocked ? colors.danger : colors.border, borderLeft: `4px solid ${blocked ? colors.danger : b.automatic ? colors.info : colors.warning}`, boxShadow: 'none' }}
    >
      <Stack spacing={1.5} sx={{ p: 3 }}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography variant="subtitle2" sx={{ mr: 1 }}>
            {b.title}
          </Typography>
          {(b.priority === 'HIGH' || b.priority === 'URGENT') && <StatusChip value={b.priority} />}
          {blocked && <StatusChip value="BLOCKED" />}
        </Stack>
        <Typography variant="body2">{b.explanation}</Typography>
        <Typography variant="body2" color="text.secondary">
          <strong>Next:</strong> {b.nextAction}
        </Typography>
        <Stack direction="row" spacing={2} alignItems="center" justifyContent="space-between" flexWrap="wrap" useFlexGap>
          <Typography variant="caption" color="text.secondary">
            Owner: {ROLE_TEAM[b.ownerRole]} · {blocked ? 'blocked' : 'open'} {fromNow(b.since)}
            {b.automatic ? ' · automatic check' : ''}
          </Typography>
          {step?.canUpdate && (
            <Button size="small" onClick={() => onOpen(step)}>
              Update
            </Button>
          )}
        </Stack>
      </Stack>
    </Card>
  );
}

function StepRow({ step: s, onOpen }: { step: StepView; onOpen: (s: StepView) => void }) {
  const meta = STEP_ICON[s.status];
  const Icon = meta.icon;
  const done = s.status === 'COMPLETED' || s.status === 'NOT_APPLICABLE';
  return (
    <Stack direction="row" spacing={3} sx={{ px: 5, py: 3 }} alignItems="flex-start">
      <Box sx={{ color: meta.color, pt: 0.25, flexShrink: 0 }} aria-hidden>
        <Icon size={20} />
      </Box>
      <Box sx={{ flex: 1, minWidth: 0 }}>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={{ xs: 1, sm: 2 }} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="body2" fontWeight={600}>
                {s.order}. {s.name}
              </Typography>
              {!s.required && <Chip size="small" variant="outlined" label="Optional" />}
              {s.autoCheck && <Chip size="small" variant="outlined" label={s.terminal ? 'On departure' : 'Automatic check'} />}
              {(s.priority === 'HIGH' || s.priority === 'URGENT') && !done && <StatusChip value={s.priority} />}
            </Stack>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
              {ROLE_TEAM[s.responsibleRole]}
              {s.assignedToName ? ` · assigned to ${s.assignedToName}` : ''}
              {s.status === 'COMPLETED' && s.completedAt
                ? ` · completed ${formatDateTime(s.completedAt)}${s.completedByName ? ` by ${s.completedByName}` : ''}`
                : s.status === 'NOT_STARTED' && !s.updatedByName
                  ? ' · not started yet'
                  : ` · updated ${formatDateTime(s.updatedAt)}${s.updatedByName ? ` by ${s.updatedByName}` : ''}`}
            </Typography>
          </Box>
          <Stack direction="row" spacing={2} alignItems="center" sx={{ flexShrink: 0 }}>
            <StatusChip value={s.status} />
            {s.canUpdate && (s.allowedStatuses.length > 0 || !done) && (
              <Button size="small" variant="outlined" onClick={() => onOpen(s)} aria-label={`Update ${s.name}`}>
                Update
              </Button>
            )}
          </Stack>
        </Stack>
        {s.status === 'BLOCKED' && s.blockerReason && (
          <Typography variant="body2" sx={{ mt: 1, color: colors.danger }}>
            Blocked: {s.blockerReason}
          </Typography>
        )}
        {s.check && !s.check.met && !done && !s.terminal && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            {s.check.explanation}
          </Typography>
        )}
        {!done && s.nextAction && (
          <Typography variant="body2" sx={{ mt: 1 }}>
            <strong>Next:</strong> {s.nextAction}
          </Typography>
        )}
        {s.notApplicableReason && s.status === 'NOT_APPLICABLE' && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
            Not applicable: {s.notApplicableReason}
          </Typography>
        )}
        {s.notes && (
          <Typography variant="body2" color="text.secondary" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>
            {s.notes}
          </Typography>
        )}
      </Box>
    </Stack>
  );
}

function StartDialog({ open, admissionId, onClose }: { open: boolean; admissionId: string; onClose: () => void }) {
  const [planned, setPlanned] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const start = useDischargeMutation(
    () => dischargeApi.initiate(admissionId, { plannedDischargeAt: planned ? localInputToIso(planned) : null, notes: notes.trim() || undefined }),
    {
      success: 'Discharge planning started',
      onSuccess: () => {
        setPlanned('');
        setNotes('');
        onClose();
      },
      onError: (e) => setError(errorMessage(e)),
    },
  );
  return (
    <Dialog open={open} onClose={start.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="start-title">
      <DialogTitle id="start-title">Start discharge planning</DialogTitle>
      <DialogContent>
        <Stack spacing={4} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField
            type="datetime-local"
            label="Planned discharge (optional)"
            value={planned}
            onChange={(e) => setPlanned(e.target.value)}
            slotProps={{ inputLabel: { shrink: true }, htmlInput: { min: isoToLocalInput() } }}
            helperText="Shared with the team and shown to the patient as an estimate."
          />
          <TextField label="Notes for the audit trail (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 1000 } }} />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={start.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => start.mutate(undefined)} disabled={start.isPending}>
          {start.isPending ? 'Starting…' : 'Start'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function PlanDialog({ open, workflow, onClose }: { open: boolean; workflow: WorkflowView; onClose: () => void }) {
  const [planned, setPlanned] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setPlanned(workflow.plannedDischargeAt ? isoToLocalInput(workflow.plannedDischargeAt) : '');
      setError(null);
    }
  }, [open, workflow.plannedDischargeAt]);
  const save = useDischargeMutation(() => dischargeApi.plan(workflow.id, planned ? localInputToIso(planned) : null), {
    success: 'Planned discharge time updated',
    onSuccess: onClose,
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Dialog
      open={open}
      onClose={save.isPending ? undefined : onClose}
      maxWidth="xs"
      fullWidth
      aria-labelledby="plan-title"
    >
      <DialogTitle id="plan-title">Planned discharge time</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <TextField type="datetime-local" label="Planned discharge" value={planned} onChange={(e) => setPlanned(e.target.value)} slotProps={{ inputLabel: { shrink: true } }} helperText="Leave empty to clear." />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => save.mutate(undefined)} disabled={save.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

'use client';

import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { ChevronDown, FilePlus2, History, PenLine, Send } from 'lucide-react';
import { ck, clinicalApi, TreatmentPlanView } from '@/lib/api/clinical';
import { ApiError, errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { RHFTextField } from '@/components/forms/Fields';
import { DefinitionList } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FlagChip, useClinicalInvalidate } from '../shared';

function planItems(p: TreatmentPlanView) {
  return [
    { label: 'Goals', value: <Pre text={p.goals} />, span: 2 as const },
    { label: 'Plan', value: <Pre text={p.planDetails} />, span: 2 as const },
    { label: 'Monitoring', value: p.monitoring ? <Pre text={p.monitoring} /> : null },
    { label: 'Diet', value: p.diet },
    { label: 'Activity', value: p.activity },
  ];
}

function Pre({ text }: { text: string }) {
  return (
    <Box component="span" sx={{ whiteSpace: 'pre-wrap' }}>
      {text}
    </Box>
  );
}

/**
 * Treatment plan: the active version prominently, older versions kept as SUPERSEDED (never overwritten),
 * revise with a reason, and release a patient-friendly summary to the portal.
 */
export function TreatmentPlanPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('TREATMENT_PLAN_WRITE');
  const canRelease = hasPermission('RELEASE_TO_PATIENT');
  const [editOpen, setEditOpen] = useState(false);
  const [releasing, setReleasing] = useState(false);
  const q = useQuery({ queryKey: ck.plans(admissionId), queryFn: () => clinicalApi.plans(admissionId) });

  if (q.isLoading) return <TableSkeleton rows={4} columns={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const plans = q.data ?? [];
  const active = plans.find((p) => p.status === 'ACTIVE');
  const history = plans.filter((p) => p !== active);

  return (
    <Box>
      {!active ? (
        <EmptyState
          icon={FilePlus2}
          title="No active treatment plan"
          description={history.length ? 'Earlier versions are listed below.' : 'Document goals, interventions and monitoring for this admission.'}
          action={
            canWrite && (
              <Button variant="contained" startIcon={<FilePlus2 size={16} />} onClick={() => setEditOpen(true)}>
                Create treatment plan
              </Button>
            )
          }
          compact
        />
      ) : (
        <Box sx={{ border: 1, borderColor: 'divider', borderLeft: `4px solid ${colors.primary}`, borderRadius: 2, p: { xs: 3, sm: 5 } }}>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }} sx={{ mb: 4 }}>
            <Box>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="h4" component="h3">
                  Active plan · version {active.planVersion}
                </Typography>
                <StatusChip value={active.status} />
                {active.releasedToPatient && <FlagChip tone="success" icon={<Send size={12} />} label="Released to patient" />}
              </Stack>
              <Typography variant="caption" color="text.secondary">
                {active.createdBy ?? '—'} · {formatDateTime(active.createdAt)}
              </Typography>
            </Box>
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ flexShrink: 0 }}>
              {canWrite && (
                <Button variant="contained" size="small" startIcon={<PenLine size={14} />} onClick={() => setEditOpen(true)}>
                  Revise plan
                </Button>
              )}
              {canRelease && (
                <Button variant="outlined" size="small" startIcon={<Send size={14} />} onClick={() => setReleasing(true)}>
                  {active.releasedToPatient ? 'Update patient summary' : 'Release to patient'}
                </Button>
              )}
            </Stack>
          </Stack>
          {active.revisionReason && (
            <Alert severity="info" variant="outlined" sx={{ mb: 4 }}>
              <strong>Revision reason:</strong> {active.revisionReason}
            </Alert>
          )}
          <DefinitionList items={planItems(active)} />
          <Box sx={{ mt: 4, p: 3, borderRadius: 2, bgcolor: colors.surfaceAlt, border: 1, borderColor: 'divider' }}>
            <Typography variant="caption" color="text.secondary" fontWeight={600} component="div">
              Patient-friendly summary {active.releasedAt ? `· released ${formatDateTime(active.releasedAt)}` : '· not released'}
            </Typography>
            <Typography variant="body2" sx={{ mt: 0.5, whiteSpace: 'pre-wrap' }} color={active.patientSummary ? 'text.primary' : 'text.secondary'}>
              {active.patientSummary || 'No patient summary written yet.'}
            </Typography>
          </Box>
        </Box>
      )}

      {history.length > 0 && (
        <Box sx={{ mt: 5 }}>
          <Stack direction="row" spacing={1} alignItems="center" sx={{ mb: 2 }}>
            <History size={16} color={colors.inkMuted} />
            <Typography variant="subtitle2">Version history ({history.length})</Typography>
          </Stack>
          {history.map((p) => (
            <Accordion key={p.id} disableGutters variant="outlined" sx={{ '&:not(:last-of-type)': { borderBottom: 0 } }}>
              <AccordionSummary expandIcon={<ChevronDown size={16} />} aria-controls={`plan-${p.id}`}>
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="body2" fontWeight={600}>
                      Version {p.planVersion}
                    </Typography>
                    <StatusChip value={p.status} />
                    {p.releasedToPatient && <FlagChip tone="success" label="Was released" />}
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {p.createdBy ?? '—'} · {formatDateTime(p.createdAt)}
                  </Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails id={`plan-${p.id}`}>
                {p.revisionReason && (
                  <Typography variant="body2" sx={{ mb: 3 }}>
                    <strong>Revision reason:</strong> {p.revisionReason}
                  </Typography>
                )}
                {(() => {
                  const next = plans.find((x) => x.id === p.supersededById);
                  return next ? (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mb: 3 }}>
                      Superseded by version {next.planVersion} on {formatDateTime(next.createdAt)}
                      {next.revisionReason ? ` — ${next.revisionReason}` : ''}
                    </Typography>
                  ) : null;
                })()}
                <DefinitionList items={planItems(p)} />
                {p.patientSummary && (
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 3 }}>
                    Patient summary: {p.patientSummary}
                  </Typography>
                )}
              </AccordionDetails>
            </Accordion>
          ))}
        </Box>
      )}

      <PlanDialog open={editOpen} onClose={() => setEditOpen(false)} admissionId={admissionId} active={active} />
      {active && <ReleasePlanDialog open={releasing} onClose={() => setReleasing(false)} plan={active} />}
    </Box>
  );
}

const schema = z.object({
  goals: z.string().trim().min(3, 'Describe the goals of care').max(4000),
  planDetails: z.string().trim().min(3, 'Describe the plan').max(8000),
  monitoring: z.string().trim().max(2000).optional(),
  diet: z.string().trim().max(1000).optional(),
  activity: z.string().trim().max(1000).optional(),
  patientSummary: z.string().trim().max(4000).optional(),
  revisionReason: z.string().trim().max(1000).optional(),
});
type FormValues = z.infer<typeof schema>;

function PlanDialog({ open, onClose, admissionId, active }: { open: boolean; onClose: () => void; admissionId: string; active?: TreatmentPlanView }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const defaults = useMemo<FormValues>(
    () => ({
      goals: active?.goals ?? '',
      planDetails: active?.planDetails ?? '',
      monitoring: active?.monitoring ?? '',
      diet: active?.diet ?? '',
      activity: active?.activity ?? '',
      patientSummary: active?.patientSummary ?? '',
      revisionReason: '',
    }),
    [active],
  );
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: defaults });
  useEffect(() => {
    if (open) {
      reset(defaults);
      setError(null);
      setConflict(false);
    }
    // Only reset when the dialog opens; a background refetch must not wipe what the clinician typed.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      clinicalApi.revisePlan(admissionId, {
        goals: v.goals.trim(),
        planDetails: v.planDetails.trim(),
        monitoring: v.monitoring?.trim() || null,
        diet: v.diet?.trim() || null,
        activity: v.activity?.trim() || null,
        patientSummary: v.patientSummary?.trim() || null,
        revisionReason: active ? v.revisionReason?.trim() || null : null,
        basedOnPlanId: active?.id ?? null,
      }),
    onSuccess: (p) => {
      toast.success(active ? `Treatment plan revised (version ${p.planVersion})` : 'Treatment plan created');
      invalidate(admissionId);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'PLAN_CHANGED') {
        setConflict(true);
        setError(null);
        queryClient.invalidateQueries({ queryKey: ck.plans(admissionId) });
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const submit = handleSubmit((v) => {
    if (active && (v.revisionReason ?? '').trim().length < 3) {
      setFieldError('revisionReason', { message: 'Document why the plan is being revised' });
      return;
    }
    setConflict(false);
    save.mutate(v);
  });

  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="plan-title">
      <DialogTitle id="plan-title">{active ? `Revise treatment plan (currently version ${active.planVersion})` : 'Create treatment plan'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={3} component="form" id="plan-form" noValidate onSubmit={submit}>
          {active && (
            <Alert severity="info">
              Revising creates version {active.planVersion + 1}. Version {active.planVersion} is kept unchanged in the history as superseded.
            </Alert>
          )}
          {conflict && (
            <Alert severity="warning">
              Someone else revised this plan while you were editing. The latest version has been reloaded — review it in the history, then save again to
              base your revision on it.
            </Alert>
          )}
          <RHFTextField control={control} name="goals" label="Goals of care" required multiline minRows={2} />
          <RHFTextField control={control} name="planDetails" label="Plan (interventions, medications, investigations)" required multiline minRows={4} />
          <RHFTextField control={control} name="monitoring" label="Monitoring" multiline minRows={2} />
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="diet" label="Diet" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="activity" label="Activity" />
            </Grid>
          </Grid>
          <RHFTextField
            control={control}
            name="patientSummary"
            label="Patient-friendly summary (optional)"
            multiline
            minRows={2}
            helperText="Plain language. Only visible to the patient after you release the plan."
          />
          {active && <RHFTextField control={control} name="revisionReason" label="Reason for revision" required multiline minRows={2} />}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="plan-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : active ? 'Save new version' : 'Create plan'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function ReleasePlanDialog({ open, onClose, plan }: { open: boolean; onClose: () => void; plan: TreatmentPlanView }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [summary, setSummary] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setSummary(plan.patientSummary ?? '');
      setError(null);
    }
  }, [open, plan]);
  const release = useMutation({
    mutationFn: () => clinicalApi.releasePlan(plan.id, summary.trim()),
    onSuccess: () => {
      toast.success('Treatment plan summary released to the patient portal');
      invalidate(plan.admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <ConfirmDialog
      open={open}
      title="Release plan summary to patient"
      description="The patient (and authorised caregivers) will see this plain-language summary in the portal. Clinical plan details are not shared."
      confirmLabel="Release"
      loading={release.isPending}
      error={error}
      onConfirm={() => {
        if (summary.trim().length < 10) {
          setError('Write a plain-language summary of at least 10 characters.');
          return;
        }
        release.mutate();
      }}
      onClose={() => !release.isPending && onClose()}
    >
      <TextField
        label="Patient-friendly summary"
        value={summary}
        onChange={(e) => setSummary(e.target.value)}
        multiline
        minRows={4}
        required
        autoFocus
        helperText="Avoid jargon and abbreviations. Explain what is planned and what the patient can expect."
      />
    </ConfirmDialog>
  );
}

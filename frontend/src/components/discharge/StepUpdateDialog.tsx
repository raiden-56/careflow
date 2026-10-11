'use client';

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { qk, staffApi } from '@/lib/api/core';
import { dischargeApi, ROLE_TEAM, type StepPriority, type StepStatus, type StepView } from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { statusMeta } from '@/lib/status';
import { useDischargeMutation } from './hooks';

const PRIORITIES: StepPriority[] = ['LOW', 'NORMAL', 'HIGH', 'URGENT'];

/** Update one checklist step. Only transitions the server allows for this user are offered. */
export function StepUpdateDialog({ workflowId, step, onClose }: { workflowId: string; step: StepView | null; onClose: () => void }) {
  const [status, setStatus] = useState<StepStatus>('NOT_STARTED');
  const [priority, setPriority] = useState<StepPriority>('NORMAL');
  const [assignee, setAssignee] = useState('');
  const [blockerReason, setBlockerReason] = useState('');
  const [naReason, setNaReason] = useState('');
  const [confirmNa, setConfirmNa] = useState(false);
  const [nextAction, setNextAction] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!step) return;
    setStatus(step.status);
    setPriority(step.priority);
    setAssignee(step.assignedToId ?? '');
    setBlockerReason(step.blockerReason ?? '');
    setNaReason(step.notApplicableReason ?? '');
    setConfirmNa(false);
    setNextAction(step.nextAction ?? '');
    setNotes(step.notes ?? '');
    setError(null);
  }, [step]);

  const staff = useQuery({
    queryKey: qk.directory({ role: step?.responsibleRole }),
    queryFn: () => staffApi.directory({ role: step!.responsibleRole }),
    enabled: !!step && step.responsibleRole !== 'HOSPITAL_ADMIN',
  });

  const save = useDischargeMutation(
    () =>
      dischargeApi.updateStep(workflowId, step!.id, {
        status: status !== step!.status ? status : undefined,
        priority: priority !== step!.priority ? priority : undefined,
        assignedTo: assignee && assignee !== step!.assignedToId ? assignee : undefined,
        clearAssignee: !assignee && !!step!.assignedToId ? true : undefined,
        blockerReason: status === 'BLOCKED' ? blockerReason.trim() : undefined,
        notApplicableReason: status === 'NOT_APPLICABLE' ? naReason.trim() : undefined,
        confirmRequiredNotApplicable: status === 'NOT_APPLICABLE' ? confirmNa : undefined,
        nextAction: nextAction !== (step!.nextAction ?? '') ? nextAction : undefined,
        notes: notes !== (step!.notes ?? '') ? notes : undefined,
        version: step!.version,
      }),
    { success: 'Step updated', onSuccess: onClose, onError: (e) => setError(errorMessage(e)) },
  );

  if (!step) return null;
  const options: StepStatus[] = [step.status, ...step.allowedStatuses];
  const blockedInvalid = status === 'BLOCKED' && blockerReason.trim().length < 3;
  const naInvalid = status === 'NOT_APPLICABLE' && (naReason.trim().length < 5 || (step.required && !confirmNa));

  return (
    <Dialog open={!!step} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="step-dialog-title">
      <DialogTitle id="step-dialog-title">{step.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={4} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            {step.description} Owned by the {ROLE_TEAM[step.responsibleRole].toLowerCase()} team
            {step.required ? ' · required for discharge' : ' · optional'}.
          </Typography>
          {step.check && (
            <Alert severity={step.check.met ? 'success' : 'info'}>
              Automatic check: {step.check.explanation}
              {!step.check.met && ' This step completes automatically once the underlying record is updated.'}
            </Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <TextField select label="Status" value={status} onChange={(e) => setStatus(e.target.value as StepStatus)} fullWidth>
              {options.map((s) => (
                <MenuItem key={s} value={s}>
                  {statusMeta(s).label}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Priority" value={priority} onChange={(e) => setPriority(e.target.value as StepPriority)} fullWidth>
              {PRIORITIES.map((p) => (
                <MenuItem key={p} value={p}>
                  {statusMeta(p).label}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          {status === 'BLOCKED' && (
            <TextField
              label="What is blocking this step?"
              required
              multiline
              minRows={2}
              value={blockerReason}
              onChange={(e) => setBlockerReason(e.target.value)}
              error={blockedInvalid}
              helperText={blockedInvalid ? 'Describe the blocker (at least 3 characters).' : 'Visible to the discharge team and used for escalation.'}
            />
          )}
          {status === 'NOT_APPLICABLE' && (
            <>
              <TextField
                label="Why is this step not applicable?"
                required
                multiline
                minRows={2}
                value={naReason}
                onChange={(e) => setNaReason(e.target.value)}
                error={naReason.length > 0 && naReason.trim().length < 5}
                helperText="Documented justification (at least 5 characters)."
              />
              {step.required && (
                <FormControlLabel
                  control={<Checkbox checked={confirmNa} onChange={(e) => setConfirmNa(e.target.checked)} />}
                  label="I confirm this required step does not apply to this patient and the justification above is accurate."
                />
              )}
            </>
          )}
          {step.responsibleRole !== 'HOSPITAL_ADMIN' && (
            <TextField select label="Assigned to" value={assignee} onChange={(e) => setAssignee(e.target.value)} helperText={`Any active ${ROLE_TEAM[step.responsibleRole].toLowerCase()} staff member`}>
              <MenuItem value="">
                <em>Unassigned</em>
              </MenuItem>
              {(staff.data ?? []).map((s) => (
                <MenuItem key={s.id} value={s.id}>
                  {s.fullName}
                  {s.departmentName ? ` · ${s.departmentName}` : ''}
                </MenuItem>
              ))}
              {step.assignedToId && !(staff.data ?? []).some((s) => s.id === step.assignedToId) && (
                <MenuItem value={step.assignedToId}>{step.assignedToName}</MenuItem>
              )}
            </TextField>
          )}
          <TextField label="Next action" value={nextAction} onChange={(e) => setNextAction(e.target.value)} slotProps={{ htmlInput: { maxLength: 500 } }} />
          <TextField label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 2000 } }} />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => save.mutate(undefined)} disabled={save.isPending || blockedInvalid || naInvalid}>
          {save.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

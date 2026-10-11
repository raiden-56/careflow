'use client';

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, MenuItem, Stack, TextField, Typography } from '@mui/material';
import {
  dischargeApi,
  dqk,
  FOLLOW_UP_TYPE_LABEL,
  type FollowUpRequest,
  type FollowUpType,
  type FollowUpView,
} from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { applyServerErrors } from '@/lib/hooks';
import { isoToLocalInput, localInputToIso } from '@/lib/format';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { useDischargeMutation } from './hooks';

const TYPES = Object.keys(FOLLOW_UP_TYPE_LABEL) as FollowUpType[];

const schema = z
  .object({
    type: z.enum(['APPOINTMENT', 'RESULT_REVIEW', 'INSTRUCTION', 'TASK', 'TELECONSULT']),
    title: z.string().trim().min(3, 'Enter a short title').max(300),
    description: z.string().max(2000).optional(),
    scheduledAt: z.string().optional(),
    departmentId: z.string().optional(),
    clinicianId: z.string().optional(),
    responsibleUserId: z.string().optional(),
    location: z.string().max(200).optional(),
    labOrderId: z.string().optional(),
    notifyPatient: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if ((v.type === 'APPOINTMENT' || v.type === 'TELECONSULT') && !v.scheduledAt) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['scheduledAt'], message: 'Appointments need a date and time' });
    }
  });
type FormValues = z.infer<typeof schema>;

export interface FollowUpPreset {
  type?: FollowUpType;
  title?: string;
  labOrderId?: string;
}

function defaults(f?: FollowUpView, preset?: FollowUpPreset): FormValues {
  return {
    type: f?.type ?? preset?.type ?? 'APPOINTMENT',
    title: f?.title ?? preset?.title ?? '',
    description: f?.description ?? '',
    scheduledAt: f?.scheduledAt ? isoToLocalInput(f.scheduledAt) : '',
    departmentId: f?.departmentId ?? '',
    clinicianId: f?.clinicianId ?? '',
    responsibleUserId: f?.responsibleUserId ?? '',
    location: f?.location ?? '',
    labOrderId: f?.labOrderId ?? preset?.labOrderId ?? '',
    notifyPatient: true,
  };
}

/** Create or edit a follow-up. The patient is told through the hospital's configured channels. */
export function FollowUpDialog({
  open,
  admissionId,
  followUp,
  preset,
  onClose,
}: {
  open: boolean;
  admissionId: string;
  followUp?: FollowUpView;
  preset?: FollowUpPreset;
  onClose: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const options = useQuery({ queryKey: dqk.followUpOptions(admissionId), queryFn: () => dischargeApi.followUpOptions(admissionId), enabled: open });
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: defaults(followUp, preset) });
  const { control, handleSubmit, reset, watch, setError: setFieldError } = form;
  useEffect(() => {
    if (open) {
      reset(defaults(followUp, preset));
      setError(null);
    }
  }, [open, followUp, preset, reset]);
  const type = watch('type');

  const save = useDischargeMutation(
    (v: FormValues) => {
      const body: FollowUpRequest = {
        type: v.type,
        title: v.title.trim(),
        description: v.description?.trim() || null,
        scheduledAt: v.scheduledAt ? localInputToIso(v.scheduledAt) : null,
        departmentId: v.departmentId || null,
        clinicianId: v.clinicianId || null,
        responsibleUserId: v.responsibleUserId || null,
        location: v.location?.trim() || null,
        labOrderId: v.type === 'RESULT_REVIEW' ? v.labOrderId || null : null,
        notifyPatient: v.type === 'RESULT_REVIEW' ? false : v.notifyPatient,
        version: followUp?.version,
      };
      return followUp ? dischargeApi.updateFollowUp(followUp.id, body) : dischargeApi.createFollowUp(admissionId, body);
    },
    {
      success: (r) =>
        followUp ? 'Follow-up updated' : r.patientNotifiedAt ? 'Follow-up planned and patient notified' : 'Follow-up planned',
      onSuccess: onClose,
      onError: (e) => {
        if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
      },
    },
  );

  const o = options.data;
  const labOptions = [...(o?.labOrders ?? [])];
  if (followUp?.labOrderId && !labOptions.some((l) => l.id === followUp.labOrderId)) {
    labOptions.push({ id: followUp.labOrderId, label: followUp.labOrderLabel ?? 'Linked investigation' });
  }

  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="fu-title">
      <DialogTitle id="fu-title">{followUp ? 'Edit follow-up' : 'Plan a follow-up'}</DialogTitle>
      <Box component="form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))}>
        <DialogContent>
          <Stack spacing={4} sx={{ pt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            {options.error && <Alert severity="warning">Could not load pick-lists: {errorMessage(options.error)}</Alert>}
            <Grid container spacing={3}>
              <Grid size={{ xs: 12, sm: 4 }}>
                <RHFSelect control={control} name="type" label="Type" fullWidth options={TYPES.map((t) => ({ value: t, label: FOLLOW_UP_TYPE_LABEL[t] }))} />
              </Grid>
              <Grid size={{ xs: 12, sm: 8 }}>
                <RHFTextField control={control} name="title" label="Title" required fullWidth placeholder="e.g. Cardiology OPD review" />
              </Grid>
              <Grid size={12}>
                <RHFTextField
                  control={control}
                  name="description"
                  label={type === 'RESULT_REVIEW' ? 'Notes for the reviewing clinician' : 'Instructions for the patient'}
                  multiline
                  minRows={2}
                  fullWidth
                  helperText={type === 'RESULT_REVIEW' ? 'Internal - not shown in the patient portal.' : 'Shown to the patient in the portal.'}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField
                  control={control}
                  name="scheduledAt"
                  label={type === 'APPOINTMENT' || type === 'TELECONSULT' ? 'Date and time *' : 'Due (optional)'}
                  type="datetime-local"
                  fullWidth
                  slotProps={{ htmlInput: { min: isoToLocalInput() } }}
                />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="location" label="Location" fullWidth placeholder="e.g. OPD Block A, Room 12" />
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <RHFSelect control={control} name="departmentId" label="Department" fullWidth emptyLabel="None" options={(o?.departments ?? []).map((d) => ({ value: d.id, label: d.label }))} />
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <RHFSelect control={control} name="clinicianId" label="Clinician" fullWidth emptyLabel="None" options={(o?.clinicians ?? []).map((d) => ({ value: d.id, label: d.label }))} />
              </Grid>
              <Grid size={{ xs: 12, sm: 4 }}>
                <RHFSelect
                  control={control}
                  name="responsibleUserId"
                  label="Responsible"
                  fullWidth
                  emptyLabel="Me"
                  helperText="Who makes sure it happens"
                  options={(o?.staff ?? []).map((d) => ({ value: d.id, label: `${d.label} (${d.detail})` }))}
                />
              </Grid>
              {type === 'RESULT_REVIEW' && (
                <Grid size={12}>
                  <RHFSelect
                    control={control}
                    name="labOrderId"
                    label="Investigation handed over for review"
                    fullWidth
                    emptyLabel="Not linked"
                    helperText="Linking an outstanding result marks it as explicitly handled for discharge."
                    options={labOptions.map((l) => ({ value: l.id, label: `${l.label}${l.detail ? ` · ${l.detail}` : ''}` }))}
                  />
                </Grid>
              )}
              {type !== 'RESULT_REVIEW' && (
                <Grid size={12}>
                  <RHFCheckbox
                    control={control}
                    name="notifyPatient"
                    label={followUp ? 'Notify the patient if the date changes' : 'Notify the patient now (in-app and configured email/SMS channels)'}
                  />
                </Grid>
              )}
            </Grid>
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={onClose} disabled={save.isPending}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : followUp ? 'Save changes' : 'Plan follow-up'}
          </Button>
        </DialogActions>
      </Box>
    </Dialog>
  );
}

const OUTCOME_TEXT = {
  COMPLETED: { title: 'Mark follow-up completed', label: 'Outcome notes (optional)', confirm: 'Mark completed', min: 0 },
  MISSED: { title: 'Record missed follow-up', label: 'What was done (e.g. patient contacted, rebooked)', confirm: 'Record missed', min: 1 },
  CANCELLED: { title: 'Cancel follow-up', label: 'Reason for cancelling', confirm: 'Cancel follow-up', min: 3 },
} as const;

/** Records COMPLETED / MISSED / CANCELLED with outcome notes. */
export function FollowUpOutcomeDialog({
  followUp,
  outcome,
  onClose,
}: {
  followUp: FollowUpView | null;
  outcome: 'COMPLETED' | 'MISSED' | 'CANCELLED' | null;
  onClose: () => void;
}) {
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setNotes('');
    setError(null);
  }, [followUp, outcome]);
  const save = useDischargeMutation(() => dischargeApi.followUpOutcome(followUp!.id, outcome!, notes.trim() || undefined), {
    success: 'Follow-up updated',
    onSuccess: onClose,
    onError: (e) => setError(errorMessage(e)),
  });
  if (!followUp || !outcome) return null;
  const t = OUTCOME_TEXT[outcome];
  const invalid = notes.trim().length < t.min;
  return (
    <Dialog open onClose={save.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="outcome-title">
      <DialogTitle id="outcome-title">{t.title}</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            {followUp.title}
            {followUp.patientName ? ` · ${followUp.patientName}` : ''}
          </Typography>
          {outcome === 'CANCELLED' && followUp.patientNotifiedAt && (
            <Alert severity="info">The patient was notified about this follow-up and will be told it is cancelled.</Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
          <TextField label={t.label} value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={3} required={t.min > 0} slotProps={{ htmlInput: { maxLength: 2000 } }} />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Back
        </Button>
        <Button variant="contained" color={outcome === 'CANCELLED' ? 'error' : 'primary'} onClick={() => save.mutate(undefined)} disabled={save.isPending || invalid}>
          {t.confirm}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

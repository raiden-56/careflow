'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Stack, Typography } from '@mui/material';
import { clinicalApi, Urgency } from '@/lib/api/clinical';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z.object({
  concern: z.string().trim().min(3, 'Describe the concern').max(300),
  observations: z.string().trim().min(3, 'Summarise the relevant observations').max(1500),
  urgency: z.enum(['ROUTINE', 'URGENT', 'EMERGENCY']),
});
type FormValues = z.infer<typeof schema>;

const URGENCY_HELP: Record<Urgency, string> = {
  ROUTINE: 'Review at the next ward round',
  URGENT: 'Review within 30 minutes',
  EMERGENCY: 'Immediate bedside review - call the emergency team if the patient deteriorates',
};

/** Nurse-to-clinician escalation (creates an ESCALATION alert assigned to the attending doctor). */
export function EscalateDialog({
  open,
  onClose,
  admissionId,
  patientName,
  attendingName,
}: {
  open: boolean;
  onClose: () => void;
  admissionId: string;
  patientName: string;
  attendingName?: string;
}) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { concern: '', observations: '', urgency: 'URGENT' },
  });
  useEffect(() => {
    if (open) {
      reset({ concern: '', observations: '', urgency: 'URGENT' });
      setError(null);
    }
  }, [open, reset]);
  const urgency = watch('urgency');
  const m = useMutation({
    mutationFn: (v: FormValues) => clinicalApi.escalate(admissionId, v),
    onSuccess: () => {
      toast.success(`Escalated to ${attendingName ?? 'the attending doctor'}`);
      queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="escalate-title">
      <DialogTitle id="escalate-title">Escalate to doctor</DialogTitle>
      <DialogContent>
        <Stack spacing={4} component="form" id="escalate-form" onSubmit={handleSubmit((v) => m.mutate(v))} noValidate sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Typography variant="body2" color="text.secondary">
            {patientName} · goes to {attendingName ?? 'the attending doctor'} as an alert and an urgent notification. It is recorded on the
            timeline.
          </Typography>
          <RHFSelect
            control={control}
            name="urgency"
            label="Urgency"
            options={[
              { value: 'ROUTINE', label: 'Routine' },
              { value: 'URGENT', label: 'Urgent' },
              { value: 'EMERGENCY', label: 'Emergency' },
            ]}
            helperText={URGENCY_HELP[urgency]}
          />
          <RHFTextField control={control} name="concern" label="Concern" required placeholder="e.g. Increasing breathlessness" />
          <RHFTextField
            control={control}
            name="observations"
            label="Relevant observations (SBAR: situation, background, assessment)"
            required
            multiline
            minRows={4}
          />
          {urgency === 'EMERGENCY' && (
            <Alert severity="error">For a patient in immediate danger, also follow the hospital emergency call procedure.</Alert>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="escalate-form" variant="contained" color={urgency === 'EMERGENCY' ? 'error' : 'primary'} disabled={m.isPending}>
          {m.isPending ? 'Sending…' : 'Escalate'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

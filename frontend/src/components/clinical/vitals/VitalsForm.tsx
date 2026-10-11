'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, AlertTitle, Box, Button, FormControlLabel, Grid, InputAdornment, Stack, Switch, Typography } from '@mui/material';
import { clinicalApi, Consciousness, VitalsRecorded } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDateTime, isoToLocalInput, localInputToIso } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { StatusChip } from '@/components/ui/StatusChip';

const CONSCIOUSNESS: { value: Consciousness; label: string }[] = [
  { value: 'ALERT', label: 'Alert' },
  { value: 'CONFUSED', label: 'New confusion' },
  { value: 'VOICE', label: 'Responds to voice' },
  { value: 'PAIN', label: 'Responds to pain' },
  { value: 'UNRESPONSIVE', label: 'Unresponsive' },
];

/** Optional numeric text input within [min, max]; integers only when `integer`. */
const num = (min: number, max: number, unit: string, integer = false) =>
  z
    .string()
    .trim()
    .optional()
    .refine((v) => !v || (!Number.isNaN(Number(v)) && (!integer || Number.isInteger(Number(v)))), integer ? 'Enter a whole number' : 'Enter a number')
    .refine((v) => !v || Number.isNaN(Number(v)) || (Number(v) >= min && Number(v) <= max), `Must be between ${min} and ${max}${unit ? ' ' + unit : ''}`);

const schema = z
  .object({
    recordedAt: z
      .string()
      .min(1, 'Enter the observation time')
      .refine((v) => new Date(localInputToIso(v)).getTime() <= Date.now() + 5 * 60_000, 'Observation time cannot be in the future'),
    temperatureC: num(25, 45, '°C'),
    pulseBpm: num(0, 300, '', true),
    respiratoryRate: num(0, 80, '', true),
    systolicBp: num(0, 300, '', true),
    diastolicBp: num(0, 200, '', true),
    spo2: num(0, 100, '%', true),
    onOxygen: z.boolean(),
    painScore: num(0, 10, '', true),
    bloodGlucose: num(0, 1000, 'mg/dL'),
    weightKg: num(0, 500, 'kg'),
    consciousness: z.string().optional(),
    notes: z.string().trim().max(1000, 'At most 1000 characters').optional(),
  })
  .superRefine((v, ctx) => {
    const measured = [v.temperatureC, v.pulseBpm, v.respiratoryRate, v.systolicBp, v.diastolicBp, v.spo2, v.painScore, v.bloodGlucose, v.weightKg, v.consciousness].some(
      (x) => !!x && x.trim() !== '',
    );
    if (!measured) ctx.addIssue({ code: 'custom', path: ['temperatureC'], message: 'Record at least one measurement' });
    if (!!v.systolicBp !== !!v.diastolicBp) {
      ctx.addIssue({ code: 'custom', path: [v.systolicBp ? 'diastolicBp' : 'systolicBp'], message: 'Record systolic and diastolic together' });
    } else if (v.systolicBp && v.diastolicBp && Number(v.systolicBp) <= Number(v.diastolicBp)) {
      ctx.addIssue({ code: 'custom', path: ['systolicBp'], message: 'Systolic must be higher than diastolic' });
    }
  });
type FormValues = z.infer<typeof schema>;

const toNum = (v?: string) => (v && v.trim() !== '' ? Number(v) : null);

const blank = (): FormValues => ({
  recordedAt: isoToLocalInput(),
  temperatureC: '',
  pulseBpm: '',
  respiratoryRate: '',
  systolicBp: '',
  diastolicBp: '',
  spo2: '',
  onOxygen: false,
  painScore: '',
  bloodGlucose: '',
  weightKg: '',
  consciousness: '',
  notes: '',
});

export interface VitalsFormProps {
  admissionId: string;
  onRecorded?: (r: VitalsRecorded) => void;
}

/**
 * Vital-sign entry mirroring server validation. After saving it lists any threshold alerts raised:
 * these are decision support (outside configured range), never a diagnosis.
 */
export function VitalsForm({ admissionId, onRecorded }: VitalsFormProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<VitalsRecorded | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: blank() });

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      clinicalApi.recordVitals(admissionId, {
        recordedAt: localInputToIso(v.recordedAt),
        temperatureC: toNum(v.temperatureC),
        pulseBpm: toNum(v.pulseBpm),
        respiratoryRate: toNum(v.respiratoryRate),
        systolicBp: toNum(v.systolicBp),
        diastolicBp: toNum(v.diastolicBp),
        spo2: toNum(v.spo2),
        onOxygen: v.onOxygen,
        painScore: toNum(v.painScore),
        bloodGlucose: toNum(v.bloodGlucose),
        weightKg: toNum(v.weightKg),
        consciousness: (v.consciousness || null) as Consciousness | null,
        notes: v.notes?.trim() || null,
      }),
    onSuccess: (r) => {
      setResult(r);
      setError(null);
      if (r.alertsRaised.length > 0) {
        toast.warning(`Observations saved. ${r.alertsRaised.length} value(s) outside the configured range - review the patient.`);
      } else {
        toast.success('Observations saved');
      }
      queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
      reset(blank());
      onRecorded?.(r);
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const unit = (u: string) => ({ input: { endAdornment: <InputAdornment position="end">{u}</InputAdornment> }, htmlInput: { inputMode: 'decimal' as const } });

  return (
    <Stack spacing={4} component="form" noValidate onSubmit={handleSubmit((v) => { setResult(null); save.mutate(v); })} aria-label="Record vital signs">
      {error && <Alert severity="error">{error}</Alert>}
      {result && result.alertsRaised.length > 0 && (
        <Alert severity="warning" onClose={() => setResult(null)}>
          <AlertTitle>Outside configured range - review the patient</AlertTitle>
          <Stack spacing={1} sx={{ mt: 1 }}>
            {result.alertsRaised.map((a) => (
              <Stack key={a.id} direction="row" spacing={1} alignItems="flex-start" flexWrap="wrap" useFlexGap>
                <StatusChip value={a.severity} />
                <Box sx={{ minWidth: 0, flex: 1 }}>
                  <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
                    {a.title}
                  </Typography>
                  <Typography variant="caption" component="div" sx={{ wordBreak: 'break-word' }}>
                    {a.observedValue ? `Observed ${a.observedValue}` : a.message}
                    {a.thresholdText ? ` · configured range ${a.thresholdText}` : ''}
                  </Typography>
                </Box>
              </Stack>
            ))}
          </Stack>
          <Typography variant="caption" component="div" sx={{ mt: 2 }}>
            Alerts are raised from hospital-configured thresholds to support, not replace, clinical judgement. Escalate according to
            ward protocol.
          </Typography>
        </Alert>
      )}
      {result && result.alertsRaised.length === 0 && (
        <Alert severity="success" onClose={() => setResult(null)}>
          Observations recorded at {formatDateTime(result.vital.recordedAt)}. All values within the configured ranges.
        </Alert>
      )}
      <Grid container spacing={3}>
        <Grid size={{ xs: 12, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="recordedAt" label="Observation time" type="datetime-local" required fullWidth />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="temperatureC" label="Temperature" fullWidth slotProps={unit('°C')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="pulseBpm" label="Pulse" fullWidth slotProps={unit('bpm')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="respiratoryRate" label="Resp. rate" fullWidth slotProps={unit('/min')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="systolicBp" label="Systolic" fullWidth slotProps={unit('mmHg')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="diastolicBp" label="Diastolic" fullWidth slotProps={unit('mmHg')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="spo2" label="SpO₂" fullWidth slotProps={unit('%')} />
        </Grid>
        <Grid size={{ xs: 12, sm: 6, md: 4 }} sx={{ display: 'flex', alignItems: 'center' }}>
          <Controller
            control={control}
            name="onOxygen"
            render={({ field }) => (
              <FormControlLabel control={<Switch checked={field.value} onChange={(e) => field.onChange(e.target.checked)} />} label="On supplemental oxygen" />
            )}
          />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="painScore" label="Pain score" fullWidth slotProps={unit('/10')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="bloodGlucose" label="Glucose" fullWidth slotProps={unit('mg/dL')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFTextField control={control} name="weightKg" label="Weight" fullWidth slotProps={unit('kg')} />
        </Grid>
        <Grid size={{ xs: 6, sm: 6, md: 4 }}>
          <RHFSelect control={control} name="consciousness" label="Consciousness (ACVPU)" emptyLabel="Not assessed" options={CONSCIOUSNESS} fullWidth />
        </Grid>
        <Grid size={{ xs: 12 }}>
          <RHFTextField control={control} name="notes" label="Notes (optional)" multiline minRows={2} fullWidth />
        </Grid>
      </Grid>
      <Stack direction="row" spacing={2} justifyContent="flex-end">
        <Button color="inherit" onClick={() => { reset(blank()); setResult(null); setError(null); }} disabled={save.isPending}>
          Clear
        </Button>
        <Button type="submit" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Save observations'}
        </Button>
      </Stack>
    </Stack>
  );
}

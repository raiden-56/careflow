'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Controller, useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Alert,
  AlertTitle,
  Autocomplete,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  InputAdornment,
  MenuItem,
  Stack,
  TextField,
  ToggleButton,
  ToggleButtonGroup,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { Search, ShieldAlert, TriangleAlert } from 'lucide-react';
import {
  ck,
  DEFAULT_TIMES,
  FREQUENCIES,
  Frequency,
  medicationApi,
  MedicationOption,
  OrderRequest,
  OrderView,
  Route,
  ROUTES,
} from '@/lib/api/clinical';
import { ApiError, errorMessage } from '@/lib/api/client';
import { applyServerErrors, useDebouncedValue, useToast } from '@/lib/hooks';
import { humanize, isoToLocalInput, localInputToIso } from '@/lib/format';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { FlagChip, useClinicalInvalidate } from '../shared';

const UNSCHEDULED: Frequency[] = ['PRN', 'STAT', 'ONCE'];
const FREQUENCY_LABEL: Record<Frequency, string> = {
  OD: 'OD · once daily',
  BD: 'BD · twice daily',
  TDS: 'TDS · three times daily',
  QID: 'QID · four times daily',
  Q4H: 'Q4H · every 4 hours',
  Q6H: 'Q6H · every 6 hours',
  Q8H: 'Q8H · every 8 hours',
  Q12H: 'Q12H · every 12 hours',
  HS: 'HS · at bedtime',
  WEEKLY: 'Weekly',
  PRN: 'PRN · as needed',
  STAT: 'STAT · immediately, once',
  ONCE: 'Once · single dose',
};
const UNIT_SUGGESTIONS = ['mg', 'g', 'mcg', 'IU', 'mL', 'tablet', 'capsule', 'puff', 'drop', 'unit'];

function slotsFor(f: Frequency): number {
  return DEFAULT_TIMES[f] ? DEFAULT_TIMES[f].split(',').length : 0;
}

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/;

const schema = z
  .object({
    medicationId: z.string().min(1, 'Choose a medication'),
    dose: z.string().trim().min(1, 'Enter the dose').max(50),
    doseUnit: z.string().trim().min(1, 'Enter the unit').max(20),
    route: z.string().min(1, 'Choose a route'),
    frequency: z.string().min(1, 'Choose a frequency'),
    times: z.array(z.string()),
    startAt: z.string().optional(),
    durationMode: z.enum(['none', 'days', 'end']),
    durationDays: z.string().optional(),
    endAt: z.string().optional(),
    indication: z.string().trim().max(500).optional(),
    instructions: z.string().trim().max(1000).optional(),
    patientInstructions: z.string().trim().max(1000).optional(),
    dischargeMedication: z.boolean(),
    allergyOverrideReason: z.string().trim().max(1000).optional(),
    reason: z.string().trim().max(1000).optional(),
    // helpers (not sent)
    requiresOverride: z.boolean(),
    modifying: z.boolean(),
  })
  .superRefine((v, ctx) => {
    const f = v.frequency as Frequency;
    if (f && !UNSCHEDULED.includes(f)) {
      const n = slotsFor(f);
      if (v.times.length !== n) ctx.addIssue({ code: 'custom', path: ['times'], message: `${f} needs exactly ${n} administration time(s).` });
      v.times.forEach((t, i) => {
        if (!TIME_RE.test(t)) ctx.addIssue({ code: 'custom', path: ['times', i], message: 'Use HH:mm' });
      });
      if (new Set(v.times).size !== v.times.length) ctx.addIssue({ code: 'custom', path: ['times'], message: 'Administration times must be different.' });
    }
    if (v.durationMode === 'days') {
      const n = Number(v.durationDays);
      if (!v.durationDays || !Number.isInteger(n) || n < 1 || n > 180)
        ctx.addIssue({ code: 'custom', path: ['durationDays'], message: 'Enter 1–180 days' });
    }
    if (v.durationMode === 'end' && !v.endAt) ctx.addIssue({ code: 'custom', path: ['endAt'], message: 'Choose when the course ends' });
    if (v.requiresOverride && (v.allergyOverrideReason ?? '').trim().length < 10)
      ctx.addIssue({ code: 'custom', path: ['allergyOverrideReason'], message: 'Document the clinical reason for overriding the allergy (at least 10 characters).' });
    if (v.modifying && (v.reason ?? '').trim().length < 3)
      ctx.addIssue({ code: 'custom', path: ['reason'], message: 'Document why the order is being changed.' });
  });
type FormValues = z.infer<typeof schema>;

function parseStrength(strength?: string): { dose: string; unit: string } | null {
  if (!strength) return null;
  const m = /^\s*([\d.]+)\s*([A-Za-z]+)/.exec(strength);
  return m ? { dose: m[1], unit: m[2] } : null;
}

export interface PrescribeDialogProps {
  open: boolean;
  onClose: () => void;
  admissionId: string;
  /** When set, the dialog modifies this order: the server discontinues it and creates a replacement. */
  modifyOrder?: OrderView;
  onSaved?: (order: OrderView) => void;
}

/**
 * New prescription / modification. Pre-warns about documented allergies that match the chosen drug, and handles the
 * server's ALLERGY_CONFLICT rule (HTTP 422): the prescriber either chooses a different medication or documents an
 * override reason and resubmits.
 */
export function PrescribeDialog({ open, onClose, admissionId, modifyOrder, onSaved }: PrescribeDialogProps) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [med, setMed] = useState<MedicationOption | null>(null);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 300);
  const [serverConflict, setServerConflict] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const overrideRef = useRef<HTMLDivElement>(null);

  const options = useQuery({
    queryKey: ck.medSearch({ q, admissionId }),
    queryFn: () => medicationApi.search({ q, admissionId }),
    enabled: open,
    staleTime: 30_000,
  });
  // For modifications, look up the current medication to learn its allergy conflicts and flags.
  const current = useQuery({
    queryKey: ck.medSearch({ q: modifyOrder?.genericName ?? modifyOrder?.medicationName, admissionId }),
    queryFn: () => medicationApi.search({ q: modifyOrder?.genericName ?? modifyOrder?.medicationName, admissionId }),
    enabled: open && !!modifyOrder,
  });

  const defaults = useMemo<FormValues>(() => {
    const o = modifyOrder;
    return {
      medicationId: o?.medicationId ?? '',
      dose: o?.dose ?? '',
      doseUnit: o?.doseUnit ?? '',
      route: o?.route ?? '',
      frequency: o?.frequency ?? '',
      times: o ? (o.scheduleTimes ? o.scheduleTimes.split(',') : DEFAULT_TIMES[o.frequency] ? DEFAULT_TIMES[o.frequency].split(',') : []) : [],
      startAt: '',
      durationMode: o?.endAt ? 'end' : 'none',
      durationDays: '',
      endAt: o?.endAt ? isoToLocalInput(o.endAt) : '',
      indication: o?.indication ?? '',
      instructions: o?.instructions ?? '',
      patientInstructions: o?.patientInstructions ?? '',
      dischargeMedication: o?.dischargeMedication ?? false,
      allergyOverrideReason: o?.allergyOverrideReason ?? '',
      reason: '',
      requiresOverride: false,
      modifying: !!o,
    };
  }, [modifyOrder]);

  const { control, handleSubmit, reset, setValue, getValues, setError: setFieldError, formState } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: defaults,
  });
  const frequency = useWatch({ control, name: 'frequency' }) as Frequency | '';
  const times = useWatch({ control, name: 'times' });
  const durationMode = useWatch({ control, name: 'durationMode' });

  useEffect(() => {
    if (open) {
      reset(defaults);
      setSearch('');
      setServerConflict(null);
      setError(null);
      setMed(
        modifyOrder
          ? {
              id: modifyOrder.medicationId,
              code: '',
              displayName: modifyOrder.medicationName,
              genericName: modifyOrder.genericName ?? modifyOrder.medicationName,
              form: modifyOrder.form ?? '',
              strength: modifyOrder.strength ?? '',
              defaultRoute: modifyOrder.route,
              highAlert: modifyOrder.highAlert,
              controlled: modifyOrder.controlled,
              allergyConflicts: [],
            }
          : null,
      );
    }
  }, [open, defaults, reset, modifyOrder]);

  // Enrich the prefilled medication (modify mode) with the server's allergy conflicts.
  useEffect(() => {
    if (!modifyOrder || !current.data) return;
    const match = current.data.find((m) => m.id === modifyOrder.medicationId);
    if (match) setMed((m) => (m && m.id === match.id ? match : m));
  }, [current.data, modifyOrder]);

  const conflicts = med?.allergyConflicts ?? [];
  const requiresOverride = conflicts.length > 0 || !!serverConflict;
  useEffect(() => {
    setValue('requiresOverride', requiresOverride);
  }, [requiresOverride, setValue]);

  const chooseMed = (m: MedicationOption | null) => {
    setMed(m);
    setServerConflict(null);
    setValue('medicationId', m?.id ?? '', { shouldValidate: formState.isSubmitted });
    if (m) {
      const s = parseStrength(m.strength);
      if (!modifyOrder || m.id !== modifyOrder.medicationId) {
        if (s) {
          setValue('dose', s.dose);
          setValue('doseUnit', s.unit);
        }
        setValue('route', m.defaultRoute);
      }
      if (!m.allergyConflicts.length && !modifyOrder?.allergyOverrideReason) setValue('allergyOverrideReason', '');
    }
  };

  const changeFrequency = (f: Frequency) => {
    setValue('frequency', f, { shouldValidate: formState.isSubmitted });
    setValue('times', DEFAULT_TIMES[f] ? DEFAULT_TIMES[f].split(',') : [], { shouldValidate: formState.isSubmitted });
  };

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const f = v.frequency as Frequency;
      const body: OrderRequest = {
        medicationId: v.medicationId,
        dose: v.dose.trim(),
        doseUnit: v.doseUnit.trim(),
        route: v.route as Route,
        frequency: f,
        scheduleTimes: UNSCHEDULED.includes(f) ? null : [...v.times].sort().join(','),
        startAt: v.startAt ? localInputToIso(v.startAt) : null,
        durationDays: v.durationMode === 'days' ? Number(v.durationDays) : null,
        endAt: v.durationMode === 'end' && v.endAt ? localInputToIso(v.endAt) : null,
        indication: v.indication?.trim() || null,
        instructions: v.instructions?.trim() || null,
        patientInstructions: v.patientInstructions?.trim() || null,
        dischargeMedication: v.dischargeMedication,
        allergyOverrideReason: requiresOverride ? v.allergyOverrideReason?.trim() || null : null,
      };
      return modifyOrder ? medicationApi.modify(modifyOrder.id, body, (v.reason ?? '').trim()) : medicationApi.prescribe(admissionId, body);
    },
    onSuccess: (o) => {
      toast.success(modifyOrder ? `Order modified: ${o.medicationName}` : `Prescribed ${o.medicationName}`);
      invalidate(admissionId);
      onSaved?.(o);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'ALLERGY_CONFLICT') {
        setServerConflict(e.message);
        setError(null);
        setValue('requiresOverride', true);
        setTimeout(() => overrideRef.current?.querySelector('textarea')?.focus(), 50);
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const submit = handleSubmit((v) => {
    setError(null);
    save.mutate(v);
  });

  const scheduled = frequency && !UNSCHEDULED.includes(frequency as Frequency);
  const timesError = (formState.errors.times as { message?: string } | undefined)?.message;

  return (
    <Dialog
      open={open}
      onClose={save.isPending ? undefined : onClose}
      maxWidth="md"
      fullWidth
      fullScreen={fullScreen}
      aria-labelledby="prescribe-title"
    >
      <DialogTitle id="prescribe-title">{modifyOrder ? `Modify order: ${modifyOrder.medicationName}` : 'Prescribe medication'}</DialogTitle>
      <DialogContent dividers>
        <Stack spacing={4} component="form" id="prescribe-form" onSubmit={submit} noValidate>
          {modifyOrder && (
            <Alert severity="info">
              Modifying discontinues the current order and creates a replacement linked to it. Administration history stays with the original order.
            </Alert>
          )}

          <Autocomplete<MedicationOption>
            // Hide results of the previous query while the debounced search catches up, so a stale option can't be picked.
            options={search === q && !options.isFetching ? (options.data ?? []) : med ? [med] : []}
            value={med}
            onChange={(_, m) => chooseMed(m)}
            inputValue={search}
            onInputChange={(_, v, reason) => reason !== 'reset' && setSearch(v)}
            filterOptions={(x) => x}
            isOptionEqualToValue={(a, b) => a.id === b.id}
            getOptionLabel={(m) => m.displayName}
            loading={options.isFetching || search !== q}
            loadingText="Searching the formulary…"
            noOptionsText={q ? 'No matching medications in the formulary' : 'Type to search the formulary'}
            renderOption={(props, m) => {
              const { key, ...rest } = props as typeof props & { key: string };
              return (
                <Box component="li" key={key} {...rest} sx={{ display: 'block !important', py: '8px !important' }}>
                  <Typography variant="body2" fontWeight={600}>
                    {m.displayName}
                  </Typography>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
                    <Typography variant="caption" color="text.secondary">
                      {m.genericName} · {humanize(m.form)} · {humanize(m.defaultRoute)}
                      {m.drugClass ? ` · ${m.drugClass}` : ''}
                    </Typography>
                    {m.allergyConflicts.length > 0 && <FlagChip tone="danger" label="Allergy conflict" icon={<ShieldAlert size={12} />} />}
                    {m.highAlert && <FlagChip tone="warning" label="High-alert" />}
                    {m.controlled && <FlagChip tone="info" label="Controlled" />}
                  </Stack>
                </Box>
              );
            }}
            renderInput={(params) => (
              <TextField
                {...params}
                label="Medication"
                required
                placeholder="Search by generic or brand name"
                error={!!formState.errors.medicationId}
                helperText={formState.errors.medicationId?.message}
                slotProps={{
                  input: {
                    ...params.InputProps,
                    startAdornment: (
                      <>
                        <InputAdornment position="start">
                          <Search size={16} />
                        </InputAdornment>
                        {params.InputProps.startAdornment}
                      </>
                    ),
                  },
                }}
              />
            )}
          />

          {med && (conflicts.length > 0 || serverConflict) && (
            <Alert severity="error" icon={<ShieldAlert size={22} />} sx={{ border: 1, borderColor: 'error.main' }} role="alert">
              <AlertTitle sx={{ fontWeight: 700 }}>Allergy conflict</AlertTitle>
              {serverConflict ? (
                <Typography variant="body2">{serverConflict}</Typography>
              ) : (
                <Typography variant="body2">
                  {med.displayName} matches the patient&apos;s documented allerg{conflicts.length === 1 ? 'y' : 'ies'}:{' '}
                  <strong>{conflicts.join('; ')}</strong>.
                </Typography>
              )}
              <Typography variant="body2" sx={{ mt: 1 }}>
                Choose a different medication, or document a clinical reason to override the allergy. Overrides are recorded on the order and in the audit log.
              </Typography>
              <Button size="small" color="error" variant="outlined" sx={{ mt: 2 }} onClick={() => chooseMed(null)}>
                Choose a different medication
              </Button>
            </Alert>
          )}
          {med && (med.highAlert || med.controlled) && (
            <Alert severity="warning" icon={<TriangleAlert size={20} />}>
              {med.highAlert && <span>High-alert medication: double-check dose, route and patient before prescribing. </span>}
              {med.controlled && <span>Controlled medicine: administration requires a witness.</span>}
            </Alert>
          )}

          {requiresOverride && (
            <Box ref={overrideRef}>
              <RHFTextField
                control={control}
                name="allergyOverrideReason"
                label="Allergy override reason"
                required
                multiline
                minRows={2}
                helperText="Explain why the benefit outweighs the risk (at least 10 characters), e.g. tolerated previously, desensitised, no alternative."
              />
            </Box>
          )}

          <Grid container spacing={3}>
            <Grid size={{ xs: 6, sm: 3 }}>
              <RHFTextField control={control} name="dose" label="Dose" required />
            </Grid>
            <Grid size={{ xs: 6, sm: 3 }}>
              <Controller
                control={control}
                name="doseUnit"
                render={({ field, fieldState }) => (
                  <Autocomplete
                    freeSolo
                    options={UNIT_SUGGESTIONS}
                    value={field.value ?? ''}
                    onChange={(_, v) => field.onChange(v ?? '')}
                    onInputChange={(_, v) => field.onChange(v)}
                    renderInput={(p) => <TextField {...p} label="Unit" required error={!!fieldState.error} helperText={fieldState.error?.message} />}
                  />
                )}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="route" label="Route" required options={ROUTES.map((r) => ({ value: r, label: humanize(r) }))} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <Controller
                control={control}
                name="frequency"
                render={({ field, fieldState }) => (
                  <TextField
                    select
                    label="Frequency"
                    required
                    value={field.value ?? ''}
                    onChange={(e) => changeFrequency(e.target.value as Frequency)}
                    error={!!fieldState.error}
                    helperText={fieldState.error?.message}
                  >
                    {FREQUENCIES.map((f) => (
                      <MenuItem key={f} value={f}>
                        {FREQUENCY_LABEL[f]}
                      </MenuItem>
                    ))}
                  </TextField>
                )}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField
                control={control}
                name="startAt"
                label="Start"
                type="datetime-local"
                helperText="Leave empty to start now"
              />
            </Grid>
          </Grid>

          {frequency && (
            <Box>
              <Typography variant="subtitle2" sx={{ mb: 1.5 }}>
                Administration times
              </Typography>
              {scheduled ? (
                <>
                  <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
                    {times.map((t, i) => (
                      <TextField
                        key={i}
                        type="time"
                        size="small"
                        label={`Dose ${i + 1}`}
                        value={t}
                        onChange={(e) => {
                          const next = [...getValues('times')];
                          next[i] = e.target.value;
                          setValue('times', next, { shouldValidate: formState.isSubmitted });
                        }}
                        slotProps={{ inputLabel: { shrink: true }, htmlInput: { step: 300 } }}
                        sx={{ width: 130 }}
                        error={!!(formState.errors.times as unknown as Array<unknown> | undefined)?.[i]}
                      />
                    ))}
                  </Stack>
                  <Typography variant="caption" color={timesError ? 'error' : 'text.secondary'} component="div" sx={{ mt: 1 }}>
                    {timesError ?? `${frequency} requires ${slotsFor(frequency as Frequency)} time(s) in hospital time. Defaults follow the ward schedule.`}
                  </Typography>
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  {frequency === 'PRN'
                    ? 'As-needed orders have no scheduled times; nurses record each dose with a reason.'
                    : 'A single dose is scheduled at the start time.'}
                </Typography>
              )}
            </Box>
          )}

          <Box>
            <Typography variant="subtitle2" sx={{ mb: 1.5 }}>
              Duration
            </Typography>
            <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} alignItems={{ sm: 'flex-start' }}>
              <Controller
                control={control}
                name="durationMode"
                render={({ field }) => (
                  <ToggleButtonGroup
                    exclusive
                    size="small"
                    value={field.value}
                    onChange={(_, v) => v && field.onChange(v)}
                    aria-label="Duration"
                  >
                    <ToggleButton value="none">Until stopped</ToggleButton>
                    <ToggleButton value="days">Days</ToggleButton>
                    <ToggleButton value="end">End time</ToggleButton>
                  </ToggleButtonGroup>
                )}
              />
              {durationMode === 'days' && (
                <RHFTextField
                  control={control}
                  name="durationDays"
                  label="Number of days"
                  type="number"
                  size="small"
                  slotProps={{ htmlInput: { min: 1, max: 180 } }}
                  sx={{ maxWidth: 180 }}
                />
              )}
              {durationMode === 'end' && (
                <RHFTextField control={control} name="endAt" label="Ends" type="datetime-local" size="small" sx={{ maxWidth: 240 }} />
              )}
            </Stack>
          </Box>

          <Divider />
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="indication" label="Indication" placeholder="e.g. Community-acquired pneumonia" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="instructions" label="Instructions for nursing staff" placeholder="e.g. Infuse over 30 min" />
            </Grid>
            <Grid size={12}>
              <RHFTextField
                control={control}
                name="patientInstructions"
                label="Patient instructions (plain language)"
                multiline
                minRows={2}
                helperText="Shown to the patient only when the order is released to the portal."
              />
            </Grid>
            <Grid size={12}>
              <RHFCheckbox control={control} name="dischargeMedication" label="Discharge medication (continue after discharge)" />
            </Grid>
          </Grid>

          {modifyOrder && (
            <RHFTextField control={control} name="reason" label="Reason for change" required multiline minRows={2} />
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button
          type="submit"
          form="prescribe-form"
          variant="contained"
          color={requiresOverride ? 'error' : 'primary'}
          disabled={save.isPending}
        >
          {save.isPending ? 'Saving…' : requiresOverride ? (modifyOrder ? 'Override allergy & modify' : 'Override allergy & prescribe') : modifyOrder ? 'Save changes' : 'Prescribe'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

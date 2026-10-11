'use client';

import { ReactNode, useEffect, useMemo, useState } from 'react';
import { QueryClient, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Alert,
  AlertTitle,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  FormLabel,
  Grid,
  Radio,
  RadioGroup,
  Stack,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { ShieldAlert, TriangleAlert } from 'lucide-react';
import {
  DoseView,
  ExceptionStatus,
  medicationApi,
  OrderView,
  Route,
  ROUTES,
} from '@/lib/api/clinical';
import { qk, staffApi } from '@/lib/api/core';
import { ApiError, errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize, isoToLocalInput, localInputToIso } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { StatusChip } from '@/components/ui/StatusChip';
import { colors } from '@/theme/tokens';

/** Refreshes everything that depends on a medication administration record. */
export function invalidateMedication(queryClient: QueryClient, admissionId: string) {
  queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
  queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
  queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
}

/** Doses that can still be given or have an exception recorded. */
export function isPendingDose(d: DoseView) {
  return d.status === 'SCHEDULED' || d.status === 'DELAYED';
}

/** "500 mg · ORAL · QID" style summary from a dose row. */
export function doseSig(d: Pick<DoseView, 'dose' | 'doseUnit' | 'route' | 'frequency'>) {
  return [d.dose && `${d.dose} ${d.doseUnit ?? ''}`.trim(), d.route, d.frequency].filter(Boolean).join(' · ');
}

const routeOptions = ROUTES.map((r) => ({ value: r, label: humanize(r) }));

/** Time must not be in the future (small clock-skew allowance, as on the server). */
const pastLocalTime = z
  .string()
  .min(1, 'Enter the administration time')
  .refine((v) => {
    try {
      return new Date(localInputToIso(v)).getTime() <= Date.now() + 5 * 60_000;
    } catch {
      return false;
    }
  }, 'Administration time cannot be in the future');

// ------------------------------------------------------------------ shared pieces

interface ContextProps {
  patientName?: string;
  mrn?: string;
  location?: string;
  allergies?: string[];
  medicationName: string;
  sig: string;
  highAlert: boolean;
  controlled: boolean;
  instructions?: string;
  extra?: ReactNode;
}

/** Patient, medication and safety context shown at the top of every administration dialog. */
export function MedicationContext({ patientName, mrn, location, allergies, medicationName, sig, highAlert, controlled, instructions, extra }: ContextProps) {
  return (
    <Stack spacing={3}>
      <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 3, bgcolor: colors.surfaceAlt }}>
        <Typography variant="body2" fontWeight={700} sx={{ wordBreak: 'break-word' }}>
          {patientName ?? 'Patient'}
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div">
          {[mrn, location ?? 'Awaiting bed'].filter(Boolean).join(' · ')}
        </Typography>
        <Typography variant="h4" component="p" sx={{ mt: 2, wordBreak: 'break-word' }}>
          {medicationName}
        </Typography>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
          <Typography variant="body2">{sig}</Typography>
          {highAlert && <Chip size="small" color="error" label="High alert" />}
          {controlled && <Chip size="small" color="warning" variant="outlined" label="Controlled" />}
        </Stack>
        {instructions && (
          <Typography variant="body2" sx={{ mt: 1.5, wordBreak: 'break-word' }}>
            <Box component="span" sx={{ color: 'text.secondary' }}>
              Instructions:{' '}
            </Box>
            {instructions}
          </Typography>
        )}
        {extra}
      </Box>
      {highAlert && (
        <Alert severity="error" icon={<ShieldAlert size={20} />}>
          <AlertTitle>High-alert medication</AlertTitle>
          An independent double-check by a second nurse is recommended before administration (right patient, drug, dose,
          route and time). Record the checking nurse as witness.
        </Alert>
      )}
      {controlled && !highAlert && (
        <Alert severity="warning">Controlled medicine: follow the controlled drug register procedure and record a witness.</Alert>
      )}
      {allergies && allergies.length > 0 && (
        <Alert severity="warning" icon={<TriangleAlert size={20} />}>
          <AlertTitle>Documented allergies</AlertTitle>
          {allergies.join(' · ')}. Check this medicine against the allergy list before giving it.
        </Alert>
      )}
      {allergies && allergies.length === 0 && (
        <Typography variant="caption" color="text.secondary">
          No allergies documented for this patient.
        </Typography>
      )}
    </Stack>
  );
}

/** Witness selector (other nurses). Optional, recommended for high-alert and controlled medicines. */
function useWitnessOptions(enabled: boolean) {
  const { me } = useAuth();
  const staff = useQuery({ queryKey: qk.directory({ role: 'NURSE' }), queryFn: () => staffApi.directory({ role: 'NURSE' }), enabled, staleTime: 5 * 60_000 });
  return useMemo(
    () => (staff.data ?? []).filter((s) => s.id !== me?.id).map((s) => ({ value: s.id, label: `${s.fullName}${s.designation ? ` · ${s.designation}` : ''}` })),
    [staff.data, me?.id],
  );
}

function useDialogLayout() {
  const theme = useTheme();
  return useMediaQuery(theme.breakpoints.down('sm'));
}

// ------------------------------------------------------------------ administer

const administerSchema = z.object({
  administeredAt: pastLocalTime,
  doseGiven: z.string().trim().max(50, 'At most 50 characters'),
  routeGiven: z.string().min(1, 'Choose the route'),
  site: z.string().trim().max(100, 'At most 100 characters').optional(),
  notes: z.string().trim().max(1000, 'At most 1000 characters').optional(),
  witnessUserId: z.string().optional(),
});
type AdministerValues = z.infer<typeof administerSchema>;

export interface AdministerDialogProps {
  dose: DoseView | null;
  onClose: () => void;
  onDone?: (dose: DoseView) => void;
}

/** Records a scheduled dose as given. Passes the dose version back for optimistic concurrency. */
export function AdministerDialog({ dose, onClose, onDone }: AdministerDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const fullScreen = useDialogLayout();
  const [error, setError] = useState<string | null>(null);
  const witnessOptions = useWitnessOptions(!!dose);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<AdministerValues>({
    resolver: zodResolver(administerSchema),
    defaultValues: { administeredAt: '', doseGiven: '', routeGiven: '', site: '', notes: '', witnessUserId: '' },
  });

  useEffect(() => {
    if (dose) {
      reset({
        administeredAt: isoToLocalInput(),
        doseGiven: dose.dose ? `${dose.dose} ${dose.doseUnit ?? ''}`.trim() : '',
        routeGiven: dose.route ?? '',
        site: '',
        notes: '',
        witnessUserId: '',
      });
      setError(null);
    }
  }, [dose, reset]);

  const save = useMutation({
    mutationFn: (v: AdministerValues) =>
      medicationApi.administer(dose!.id, {
        administeredAt: localInputToIso(v.administeredAt),
        doseGiven: v.doseGiven.trim() || null,
        routeGiven: (v.routeGiven || null) as Route | null,
        site: v.site?.trim() || null,
        notes: v.notes?.trim() || null,
        witnessUserId: v.witnessUserId || null,
        version: dose!.version,
      }),
    onSuccess: (d) => {
      toast.success(`${d.medicationName} recorded as given`);
      invalidateMedication(queryClient, d.admissionId);
      onDone?.(d);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        toast.error(e);
        invalidateMedication(queryClient, dose!.admissionId);
        onClose();
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const late = dose && dose.minutesLate > 0;
  return (
    <Dialog open={!!dose} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="administer-title">
      <DialogTitle id="administer-title">Record dose given</DialogTitle>
      <DialogContent>
        {dose && (
          <Stack spacing={4} component="form" id="administer-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <MedicationContext
              patientName={dose.patient?.patientName}
              mrn={dose.patient?.mrn}
              location={dose.patient?.location}
              allergies={dose.patient?.allergies}
              medicationName={dose.medicationName}
              sig={doseSig(dose)}
              highAlert={dose.highAlert}
              controlled={dose.controlled}
              instructions={dose.instructions}
              extra={
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
                  <Typography variant="body2">
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      Scheduled:{' '}
                    </Box>
                    {formatDateTime(dose.scheduledAt)}
                  </Typography>
                  <StatusChip value={dose.displayStatus} />
                  {late && (
                    <Typography variant="caption" color="error.main">
                      {dose.minutesLate} min late
                    </Typography>
                  )}
                </Stack>
              }
            />
            <Grid container spacing={3}>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="administeredAt" label="Time given" type="datetime-local" required fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="doseGiven" label="Dose given" helperText="As prescribed unless changed" fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFSelect control={control} name="routeGiven" label="Route" options={routeOptions} fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="site" label="Site (optional)" placeholder="e.g. left deltoid" fullWidth />
              </Grid>
              <Grid size={{ xs: 12 }}>
                <RHFSelect
                  control={control}
                  name="witnessUserId"
                  label={dose.highAlert || dose.controlled ? 'Witness / second checker (recommended)' : 'Witness (optional)'}
                  emptyLabel="No witness"
                  options={witnessOptions}
                  fullWidth
                />
              </Grid>
              <Grid size={{ xs: 12 }}>
                <RHFTextField control={control} name="notes" label="Notes (optional)" multiline minRows={2} fullWidth />
              </Grid>
            </Grid>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="administer-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Record as given'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ exception

const EXCEPTION_HELP: Record<ExceptionStatus, string> = {
  DELAYED: 'The dose will be given later. It stays on the round until given or another exception is recorded.',
  REFUSED: 'The patient declined the dose.',
  MISSED: 'The dose was not given and will not be given (e.g. patient off the ward, nil by mouth).',
  HELD: 'The dose was withheld on clinical grounds (e.g. parameter outside the hold criteria).',
};

const exceptionSchema = z.object({
  status: z.enum(['DELAYED', 'REFUSED', 'MISSED', 'HELD'], { message: 'Choose what happened' }),
  reason: z.string().trim().min(3, 'Document a reason (at least 3 characters)').max(1000, 'At most 1000 characters'),
  notes: z.string().trim().max(1000, 'At most 1000 characters').optional(),
});
type ExceptionValues = z.infer<typeof exceptionSchema>;

export interface ExceptionDialogProps {
  dose: DoseView | null;
  /** Hospital setting (rounds.nurseMayHoldDose) - HELD is only offered when true. */
  allowHold: boolean;
  onClose: () => void;
  onDone?: (dose: DoseView) => void;
}

/** Records a delayed, refused, missed or held dose with a mandatory reason. The prescription is unchanged. */
export function ExceptionDialog({ dose, allowHold, onClose, onDone }: ExceptionDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const fullScreen = useDialogLayout();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, watch, setValue, formState, setError: setFieldError } = useForm<ExceptionValues>({
    resolver: zodResolver(exceptionSchema),
    defaultValues: { status: undefined, reason: '', notes: '' },
  });
  const status = watch('status');

  const options: ExceptionStatus[] = useMemo(() => {
    const all: ExceptionStatus[] = ['DELAYED', 'REFUSED', 'MISSED', 'HELD'];
    return all.filter((s) => (s !== 'HELD' || allowHold) && !(s === 'DELAYED' && dose?.status === 'DELAYED'));
  }, [allowHold, dose?.status]);

  useEffect(() => {
    if (dose) {
      reset({ status: undefined, reason: '', notes: '' });
      setError(null);
    }
  }, [dose, reset]);

  const save = useMutation({
    mutationFn: (v: ExceptionValues) =>
      medicationApi.recordException(dose!.id, { status: v.status, reason: v.reason.trim(), notes: v.notes?.trim() || null, version: dose!.version }),
    onSuccess: (d) => {
      toast.success(`Dose recorded as ${humanize(d.status).toLowerCase()}`);
      invalidateMedication(queryClient, d.admissionId);
      onDone?.(d);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        toast.error(e);
        invalidateMedication(queryClient, dose!.admissionId);
        onClose();
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  return (
    <Dialog open={!!dose} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="exception-title">
      <DialogTitle id="exception-title">Record a dose exception</DialogTitle>
      <DialogContent>
        {dose && (
          <Stack spacing={4} component="form" id="exception-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <MedicationContext
              patientName={dose.patient?.patientName}
              mrn={dose.patient?.mrn}
              location={dose.patient?.location}
              medicationName={dose.medicationName}
              sig={doseSig(dose)}
              highAlert={false}
              controlled={false}
              instructions={dose.instructions}
              extra={
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1.5 }}>
                  <Typography variant="body2">
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      Scheduled:{' '}
                    </Box>
                    {formatDateTime(dose.scheduledAt)}
                  </Typography>
                  <StatusChip value={dose.displayStatus} />
                </Stack>
              }
            />
            <Box>
              <FormLabel id="exception-status-label" required sx={{ fontSize: '0.875rem' }}>
                What happened?
              </FormLabel>
              <RadioGroup
                aria-labelledby="exception-status-label"
                value={status ?? ''}
                onChange={(e) => setValue('status', e.target.value as ExceptionStatus, { shouldValidate: true })}
              >
                {options.map((s) => (
                  <FormControlLabel
                    key={s}
                    value={s}
                    control={<Radio size="small" />}
                    sx={{ alignItems: 'flex-start', mt: 1, '& .MuiRadio-root': { pt: 0.25 } }}
                    label={
                      <Box>
                        <Typography variant="body2" fontWeight={600}>
                          {humanize(s)}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {EXCEPTION_HELP[s]}
                        </Typography>
                      </Box>
                    }
                  />
                ))}
              </RadioGroup>
              {formState.errors.status && (
                <Typography variant="caption" color="error.main">
                  {formState.errors.status.message}
                </Typography>
              )}
              {!allowHold && (
                <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
                  Hospital policy does not allow nurses to hold a dose. Contact the prescriber if a hold is needed.
                </Typography>
              )}
            </Box>
            <RHFTextField control={control} name="reason" label="Reason" required multiline minRows={2} fullWidth />
            <RHFTextField control={control} name="notes" label="Notes (optional)" multiline minRows={2} fullWidth />
            <Alert severity="info" variant="outlined">
              Recording an exception does not change the prescription. It is added to the medication exception queue for
              follow-up by the responsible team.
            </Alert>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="exception-form" variant="contained" color="warning" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Record exception'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ PRN

const prnSchema = z.object({
  administeredAt: pastLocalTime,
  doseGiven: z.string().trim().min(1, 'Enter the dose given').max(50, 'At most 50 characters'),
  routeGiven: z.string().min(1, 'Choose the route'),
  site: z.string().trim().max(100, 'At most 100 characters').optional(),
  reason: z.string().trim().min(3, 'Document the indication (at least 3 characters)').max(1000, 'At most 1000 characters'),
  notes: z.string().trim().max(1000, 'At most 1000 characters').optional(),
  witnessUserId: z.string().optional(),
});
type PrnValues = z.infer<typeof prnSchema>;

export interface PrnDoseDialogProps {
  order: OrderView | null;
  onClose: () => void;
  onDone?: (dose: DoseView) => void;
}

/** Records an as-needed (PRN) dose against an active PRN order; the indication is required. */
export function PrnDoseDialog({ order, onClose, onDone }: PrnDoseDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const fullScreen = useDialogLayout();
  const [error, setError] = useState<string | null>(null);
  const witnessOptions = useWitnessOptions(!!order);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<PrnValues>({
    resolver: zodResolver(prnSchema),
    defaultValues: { administeredAt: '', doseGiven: '', routeGiven: '', site: '', reason: '', notes: '', witnessUserId: '' },
  });

  useEffect(() => {
    if (order) {
      reset({
        administeredAt: isoToLocalInput(),
        doseGiven: `${order.dose} ${order.doseUnit}`.trim(),
        routeGiven: order.route,
        site: '',
        reason: '',
        notes: '',
        witnessUserId: '',
      });
      setError(null);
    }
  }, [order, reset]);

  const save = useMutation({
    mutationFn: (v: PrnValues) =>
      medicationApi.prnDose(order!.id, {
        administeredAt: localInputToIso(v.administeredAt),
        doseGiven: v.doseGiven.trim(),
        routeGiven: v.routeGiven as Route,
        site: v.site?.trim() || null,
        reason: v.reason.trim(),
        notes: v.notes?.trim() || null,
        witnessUserId: v.witnessUserId || null,
      }),
    onSuccess: (d) => {
      toast.success(`PRN dose of ${d.medicationName} recorded`);
      invalidateMedication(queryClient, d.admissionId);
      onDone?.(d);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 409) {
        setError(e.message);
        invalidateMedication(queryClient, order!.admissionId);
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  return (
    <Dialog open={!!order} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="prn-title">
      <DialogTitle id="prn-title">Record PRN dose</DialogTitle>
      <DialogContent>
        {order && (
          <Stack spacing={4} component="form" id="prn-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
            {error && <Alert severity="error">{error}</Alert>}
            <MedicationContext
              patientName={order.patient?.patientName}
              mrn={order.patient?.mrn}
              location={order.patient?.location}
              allergies={order.patient?.allergies}
              medicationName={order.medicationName}
              sig={`${order.dose} ${order.doseUnit} · ${order.route} · as needed`}
              highAlert={order.highAlert}
              controlled={order.controlled}
              instructions={order.instructions}
              extra={
                <Stack spacing={0.5} sx={{ mt: 1.5 }}>
                  {order.indication && (
                    <Typography variant="body2">
                      <Box component="span" sx={{ color: 'text.secondary' }}>
                        Prescribed for:{' '}
                      </Box>
                      {order.indication}
                    </Typography>
                  )}
                  <Typography variant="body2">
                    <Box component="span" sx={{ color: 'text.secondary' }}>
                      Last given:{' '}
                    </Box>
                    {order.lastAdministeredAt ? `${formatDateTime(order.lastAdministeredAt)} (${fromNow(order.lastAdministeredAt)})` : 'Not given yet'}
                  </Typography>
                </Stack>
              }
            />
            <Grid container spacing={3}>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="administeredAt" label="Time given" type="datetime-local" required fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="doseGiven" label="Dose given" required fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFSelect control={control} name="routeGiven" label="Route" options={routeOptions} fullWidth />
              </Grid>
              <Grid size={{ xs: 12, sm: 6 }}>
                <RHFTextField control={control} name="site" label="Site (optional)" fullWidth />
              </Grid>
              <Grid size={{ xs: 12 }}>
                <RHFTextField
                  control={control}
                  name="reason"
                  label="Indication for this dose"
                  placeholder="e.g. pain score 6/10, temperature 38.6 °C"
                  required
                  multiline
                  minRows={2}
                  fullWidth
                />
              </Grid>
              <Grid size={{ xs: 12 }}>
                <RHFSelect
                  control={control}
                  name="witnessUserId"
                  label={order.highAlert || order.controlled ? 'Witness / second checker (recommended)' : 'Witness (optional)'}
                  emptyLabel="No witness"
                  options={witnessOptions}
                  fullWidth
                />
              </Grid>
              <Grid size={{ xs: 12 }}>
                <RHFTextField control={control} name="notes" label="Notes (optional)" multiline minRows={2} fullWidth />
              </Grid>
            </Grid>
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="prn-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Record PRN dose'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Stack, TextField, Typography } from '@mui/material';
import { CheckCircle2, ClipboardList, Plus, Star, XCircle } from 'lucide-react';
import { ck, clinicalApi, DiagnosisType, DiagnosisView } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FilterChips, FlagChip, MetaLine, RecordCard, useClinicalInvalidate } from '../shared';

const ICD10 = /^[A-TV-Z][0-9][0-9A-Z](\.[0-9A-Z]{1,4})?$/;

const schema = z
  .object({
    description: z.string().trim().min(3, 'Describe the diagnosis').max(500),
    icdCode: z
      .string()
      .trim()
      .toUpperCase()
      .refine((v) => v === '' || ICD10.test(v), 'Use ICD-10 format, e.g. J18.1')
      .optional(),
    diagnosisType: z.enum(['PROVISIONAL', 'CONFIRMED', 'DIFFERENTIAL']),
    primary: z.boolean(),
    notes: z.string().trim().max(2000).optional(),
  })
  .refine((v) => !(v.primary && v.diagnosisType === 'DIFFERENTIAL'), { path: ['primary'], message: 'A differential diagnosis cannot be primary.' });
type FormValues = z.infer<typeof schema>;
const DEFAULTS: FormValues = { description: '', icdCode: '', diagnosisType: 'PROVISIONAL', primary: false, notes: '' };

/** Admission diagnoses: add, resolve, mark entered-in-error (never deleted). */
export function DiagnosesPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('DIAGNOSIS_WRITE');
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [filter, setFilter] = useState<'active' | 'all'>('active');
  const [addOpen, setAddOpen] = useState(false);
  const [resolving, setResolving] = useState<DiagnosisView | null>(null);
  const [resolveNotes, setResolveNotes] = useState('');
  const [erroring, setErroring] = useState<DiagnosisView | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const q = useQuery({ queryKey: ck.diagnoses(admissionId), queryFn: () => clinicalApi.diagnoses(admissionId) });

  const resolve = useMutation({
    mutationFn: ({ d, notes }: { d: DiagnosisView; notes?: string }) => clinicalApi.resolveDiagnosis(d.id, notes),
    onSuccess: (d) => {
      toast.success(`${d.description} marked resolved`);
      invalidate(admissionId);
      setResolving(null);
    },
    onError: (e) => setActionError(errorMessage(e)),
  });
  const inError = useMutation({
    mutationFn: ({ d, reason }: { d: DiagnosisView; reason: string }) => clinicalApi.diagnosisInError(d.id, reason),
    onSuccess: (d) => {
      toast.success(`${d.description} marked as entered in error`);
      invalidate(admissionId);
      setErroring(null);
    },
    onError: (e) => setActionError(errorMessage(e)),
  });

  const all = q.data ?? [];
  const sorted = [...all].sort((a, b) => rank(a) - rank(b));
  const list = filter === 'active' ? sorted.filter((d) => d.status === 'ACTIVE') : sorted;
  const inactiveCount = all.filter((d) => d.status !== 'ACTIVE').length;

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mb: 3 }}>
        <FilterChips
          ariaLabel="Diagnosis filter"
          value={filter}
          onChange={setFilter}
          options={[
            { value: 'active', label: 'Active' },
            { value: 'all', label: `All${inactiveCount ? ` (incl. ${inactiveCount} resolved/in error)` : ''}` },
          ]}
        />
        {canWrite && (
          <Button variant="outlined" startIcon={<Plus size={16} />} onClick={() => setAddOpen(true)} sx={{ alignSelf: { xs: 'flex-start', sm: 'auto' } }}>
            Add diagnosis
          </Button>
        )}
      </Stack>
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState
          icon={ClipboardList}
          title={filter === 'active' ? 'No active diagnoses' : 'No diagnoses recorded'}
          description={canWrite ? 'Record a provisional or confirmed diagnosis for this admission.' : undefined}
          compact
        />
      ) : (
        <Stack spacing={2}>
          {list.map((d) => {
            const active = d.status === 'ACTIVE';
            return (
              <RecordCard key={d.id} accent={d.primary && active ? colors.primary : undefined} muted={!active}>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
                  <Box sx={{ minWidth: 0 }}>
                    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                      <Typography
                        variant="body1"
                        fontWeight={600}
                        sx={{ textDecoration: d.status === 'ENTERED_IN_ERROR' ? 'line-through' : undefined, wordBreak: 'break-word' }}
                      >
                        {d.description}
                      </Typography>
                      {d.icdCode && <FlagChip tone="neutral" label={`ICD-10 ${d.icdCode}`} />}
                    </Stack>
                    <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                      {d.primary && <FlagChip tone="primary" icon={<Star size={12} />} label="Primary" />}
                      <StatusChip value={d.diagnosisType} />
                      {!active && <StatusChip value={d.status} />}
                    </Stack>
                    {d.notes && (
                      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                        {d.notes}
                      </Typography>
                    )}
                    <MetaLine
                      items={[
                        ['Recorded', `${formatDateTime(d.recordedAt)} · ${d.recordedBy ?? '—'}`],
                        d.statusChangedAt ? [d.status === 'RESOLVED' ? 'Resolved' : 'Changed', formatDateTime(d.statusChangedAt)] : null,
                        d.statusReason ? ['Reason', d.statusReason] : null,
                      ]}
                    />
                  </Box>
                  {canWrite && active && (
                    <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                      <Button
                        size="small"
                        variant="outlined"
                        startIcon={<CheckCircle2 size={14} />}
                        onClick={() => {
                          setActionError(null);
                          setResolveNotes('');
                          setResolving(d);
                        }}
                      >
                        Resolve
                      </Button>
                      <Button
                        size="small"
                        color="inherit"
                        startIcon={<XCircle size={14} />}
                        onClick={() => {
                          setActionError(null);
                          setErroring(d);
                        }}
                      >
                        Entered in error
                      </Button>
                    </Stack>
                  )}
                </Stack>
              </RecordCard>
            );
          })}
        </Stack>
      )}

      <AddDiagnosisDialog open={addOpen} onClose={() => setAddOpen(false)} admissionId={admissionId} hasPrimary={all.some((d) => d.primary && d.status === 'ACTIVE')} />
      <ConfirmDialog
        open={!!resolving}
        title="Mark diagnosis resolved?"
        description={resolving?.description}
        confirmLabel="Mark resolved"
        loading={resolve.isPending}
        error={actionError}
        onConfirm={() => resolving && resolve.mutate({ d: resolving, notes: resolveNotes.trim() || undefined })}
        onClose={() => !resolve.isPending && setResolving(null)}
      >
        <TextField label="Notes (optional)" value={resolveNotes} onChange={(e) => setResolveNotes(e.target.value)} multiline minRows={2} />
      </ConfirmDialog>
      <ConfirmDialog
        open={!!erroring}
        title="Mark as entered in error?"
        description={
          <>
            <strong>{erroring?.description}</strong> stays in the record, struck through, with your reason. Use this only for diagnoses recorded by
            mistake.
          </>
        }
        requireReason
        reasonLabel="Why was this entered in error?"
        tone="error"
        confirmLabel="Mark in error"
        loading={inError.isPending}
        error={actionError}
        onConfirm={(reason) => erroring && inError.mutate({ d: erroring, reason: reason! })}
        onClose={() => !inError.isPending && setErroring(null)}
      />
    </Box>
  );
}

function rank(d: DiagnosisView) {
  if (d.status !== 'ACTIVE') return d.status === 'RESOLVED' ? 10 : 20;
  if (d.primary) return 0;
  return { CONFIRMED: 1, PROVISIONAL: 2, DIFFERENTIAL: 3 }[d.diagnosisType];
}

function AddDiagnosisDialog({ open, onClose, admissionId, hasPrimary }: { open: boolean; onClose: () => void; admissionId: string; hasPrimary: boolean }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setValue, setError: setFieldError } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: DEFAULTS });
  const type = useWatch({ control, name: 'diagnosisType' });
  useEffect(() => {
    if (open) {
      reset(DEFAULTS);
      setError(null);
    }
  }, [open, reset]);
  useEffect(() => {
    if (type === 'DIFFERENTIAL') setValue('primary', false);
  }, [type, setValue]);

  const save = useMutation({
    mutationFn: (v: FormValues) =>
      clinicalApi.addDiagnosis(admissionId, {
        description: v.description.trim(),
        icdCode: v.icdCode?.trim() ? v.icdCode.trim().toUpperCase() : null,
        diagnosisType: v.diagnosisType as DiagnosisType,
        primary: v.primary,
        notes: v.notes?.trim() || null,
      }),
    onSuccess: (d) => {
      toast.success(`Diagnosis recorded: ${d.description}`);
      invalidate(admissionId);
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="dx-title">
      <DialogTitle id="dx-title">Add diagnosis</DialogTitle>
      <DialogContent>
        <Stack spacing={3} component="form" id="dx-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
          <RHFTextField control={control} name="description" label="Diagnosis" required placeholder="e.g. Community-acquired pneumonia, right lower lobe" />
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <RHFTextField control={control} name="icdCode" label="ICD-10 code (optional)" placeholder="J18.1" />
            </Grid>
            <Grid size={{ xs: 12, sm: 7 }}>
              <RHFSelect
                control={control}
                name="diagnosisType"
                label="Type"
                options={[
                  { value: 'PROVISIONAL', label: 'Provisional (working diagnosis)' },
                  { value: 'CONFIRMED', label: 'Confirmed' },
                  { value: 'DIFFERENTIAL', label: 'Differential (being considered)' },
                ]}
              />
            </Grid>
          </Grid>
          <Box>
            <RHFCheckbox
              control={control}
              name="primary"
              label="Primary diagnosis for this admission"
              helperText={
                type === 'DIFFERENTIAL'
                  ? 'Differential diagnoses cannot be primary.'
                  : hasPrimary
                    ? 'The current primary diagnosis will become secondary.'
                    : undefined
              }
            />
          </Box>
          <RHFTextField control={control} name="notes" label="Notes (optional)" multiline minRows={2} placeholder="Supporting findings" />
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="dx-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Add diagnosis'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

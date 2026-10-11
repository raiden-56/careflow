'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Stack, Typography } from '@mui/material';
import { BookOpenText, Plus, ShieldAlert } from 'lucide-react';
import { AllergySeverity, AllergyStatus, AllergyType, AllergyView, ck, clinicalApi, HistoryCategory, HistoryView } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, humanize, todayLocalDate } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FlagChip, MetaLine, RecordCard } from '../shared';

/** Invalidate patient-level record + every admission summary/banner that shows allergies. */
function usePatientInvalidate(patientId: string) {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: ['clinical-patient', patientId] });
    queryClient.invalidateQueries({ queryKey: ['clinical'] });
    queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    queryClient.invalidateQueries({ queryKey: ['clinical-catalogue', 'medications'] });
    queryClient.invalidateQueries({ queryKey: ['patient', patientId] });
    queryClient.invalidateQueries({ queryKey: ['admission'] });
  };
}

const SEVERITY_ORDER: Record<AllergySeverity, number> = { LIFE_THREATENING: 0, SEVERE: 1, MODERATE: 2, MILD: 3 };

/** Allergies (severity, status change with reason, never deleted) and medical history for a patient. */
export function AllergiesHistoryPanel({ patientId }: { patientId: string }) {
  return (
    <Grid container spacing={4}>
      <Grid size={{ xs: 12, lg: 6 }}>
        <AllergiesSection patientId={patientId} />
      </Grid>
      <Grid size={{ xs: 12, lg: 6 }}>
        <HistorySection patientId={patientId} />
      </Grid>
    </Grid>
  );
}

type StatusChange<T> = { item: T; status: string; label: string };

function AllergiesSection({ patientId }: { patientId: string }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('ALLERGY_WRITE');
  const toast = useToast();
  const invalidate = usePatientInvalidate(patientId);
  const [addOpen, setAddOpen] = useState(false);
  const [change, setChange] = useState<StatusChange<AllergyView> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({ queryKey: ck.allergies(patientId), queryFn: () => clinicalApi.allergies(patientId) });
  const mutate = useMutation({
    mutationFn: ({ item, status, reason }: { item: AllergyView; status: string; reason: string }) =>
      clinicalApi.changeAllergyStatus(item.id, status as AllergyStatus, reason),
    onSuccess: (a) => {
      toast.success(`${a.allergen}: ${humanize(a.status).toLowerCase()}`);
      invalidate();
      setChange(null);
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const list = [...(q.data ?? [])].sort(
    (a, b) => (a.status === 'ACTIVE' ? 0 : 1) - (b.status === 'ACTIVE' ? 0 : 1) || SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity],
  );
  const active = list.filter((a) => a.status === 'ACTIVE');

  return (
    <SectionCard
      title="Allergies"
      subtitle={q.data ? (active.length ? `${active.length} active` : 'No known active allergies recorded') : undefined}
      action={
        canWrite && (
          <Button size="small" startIcon={<Plus size={14} />} onClick={() => setAddOpen(true)}>
            Add
          </Button>
        )
      }
    >
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState icon={ShieldAlert} title="No allergies recorded" description="Record “No known allergies” in the admission assessment if confirmed with the patient." compact />
      ) : (
        <Stack spacing={2}>
          {list.map((a) => {
            const isActive = a.status === 'ACTIVE';
            const severe = a.severity === 'SEVERE' || a.severity === 'LIFE_THREATENING';
            return (
              <RecordCard key={a.id} accent={isActive ? (severe ? colors.danger : colors.warning) : undefined} muted={!isActive}>
                <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                  <Typography
                    variant="body1"
                    fontWeight={600}
                    sx={{ textDecoration: a.status === 'ENTERED_IN_ERROR' ? 'line-through' : undefined, wordBreak: 'break-word' }}
                  >
                    {a.allergen}
                  </Typography>
                  <StatusChip value={a.severity} />
                  <FlagChip tone="neutral" label={humanize(a.allergyType)} />
                  {!isActive && <StatusChip value={a.status} />}
                </Stack>
                {a.reaction && (
                  <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                    Reaction: {a.reaction}
                  </Typography>
                )}
                <MetaLine
                  items={[
                    ['Recorded', `${formatDateTime(a.recordedAt)} · ${a.recordedBy ?? '—'}`],
                    a.statusChangedAt ? ['Changed', `${formatDateTime(a.statusChangedAt)} · ${a.statusChangedBy ?? '—'}`] : null,
                    a.statusReason ? ['Reason', a.statusReason] : null,
                  ]}
                />
                {canWrite && a.status !== 'ENTERED_IN_ERROR' && (
                  <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 2 }}>
                    {isActive ? (
                      <>
                        <Button size="small" variant="outlined" onClick={() => { setError(null); setChange({ item: a, status: 'INACTIVE', label: 'Mark inactive' }); }}>
                          Mark inactive
                        </Button>
                        <Button size="small" color="inherit" onClick={() => { setError(null); setChange({ item: a, status: 'ENTERED_IN_ERROR', label: 'Entered in error' }); }}>
                          Entered in error
                        </Button>
                      </>
                    ) : (
                      <Button size="small" variant="outlined" onClick={() => { setError(null); setChange({ item: a, status: 'ACTIVE', label: 'Reactivate' }); }}>
                        Reactivate
                      </Button>
                    )}
                  </Stack>
                )}
              </RecordCard>
            );
          })}
        </Stack>
      )}
      <AddAllergyDialog open={addOpen} onClose={() => setAddOpen(false)} patientId={patientId} />
      <ConfirmDialog
        open={!!change}
        title={`${change?.label}: ${change?.item.allergen}`}
        description={
          change?.status === 'ENTERED_IN_ERROR'
            ? 'The allergy stays visible in the record, struck through. It will no longer trigger prescribing checks. This cannot be reversed.'
            : change?.status === 'INACTIVE'
              ? 'Inactive allergies no longer trigger prescribing checks. The entry and your reason stay in the record.'
              : 'The allergy becomes active again and will trigger prescribing checks.'
        }
        requireReason
        reasonLabel="Reason"
        tone={change?.status === 'ACTIVE' ? 'primary' : 'warning'}
        confirmLabel={change?.label}
        loading={mutate.isPending}
        error={error}
        onConfirm={(reason) => change && mutate.mutate({ item: change.item, status: change.status, reason: reason! })}
        onClose={() => !mutate.isPending && setChange(null)}
      />
    </SectionCard>
  );
}

const allergySchema = z.object({
  allergen: z.string().trim().min(2, 'Name the allergen').max(200),
  allergyType: z.enum(['DRUG', 'FOOD', 'ENVIRONMENTAL', 'OTHER']),
  reaction: z.string().trim().max(500).optional(),
  severity: z.enum(['MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING']),
});
type AllergyForm = z.infer<typeof allergySchema>;
const ALLERGY_DEFAULTS: AllergyForm = { allergen: '', allergyType: 'DRUG', reaction: '', severity: 'MODERATE' };

function AddAllergyDialog({ open, onClose, patientId }: { open: boolean; onClose: () => void; patientId: string }) {
  const toast = useToast();
  const invalidate = usePatientInvalidate(patientId);
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<AllergyForm>({ resolver: zodResolver(allergySchema), defaultValues: ALLERGY_DEFAULTS });
  useEffect(() => {
    if (open) {
      reset(ALLERGY_DEFAULTS);
      setError(null);
    }
  }, [open, reset]);
  const save = useMutation({
    mutationFn: (v: AllergyForm) =>
      clinicalApi.addAllergy(patientId, {
        allergen: v.allergen.trim(),
        allergyType: v.allergyType as AllergyType,
        reaction: v.reaction?.trim() || null,
        severity: v.severity as AllergySeverity,
      }),
    onSuccess: (a) => {
      toast.success(`Allergy recorded: ${a.allergen}`);
      invalidate();
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="allergy-title">
      <DialogTitle id="allergy-title">Record allergy</DialogTitle>
      <DialogContent>
        <Stack spacing={3} component="form" id="allergy-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
          <RHFTextField control={control} name="allergen" label="Allergen" required placeholder="e.g. Penicillin, Peanuts, Latex" />
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="allergyType"
                label="Type"
                options={['DRUG', 'FOOD', 'ENVIRONMENTAL', 'OTHER'].map((t) => ({ value: t, label: humanize(t) }))}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="severity"
                label="Severity"
                options={['MILD', 'MODERATE', 'SEVERE', 'LIFE_THREATENING'].map((t) => ({ value: t, label: humanize(t) }))}
              />
            </Grid>
          </Grid>
          <RHFTextField control={control} name="reaction" label="Reaction" placeholder="e.g. Urticarial rash, anaphylaxis" />
          <Alert severity="info" variant="outlined">
            Drug allergies are checked automatically when medications are prescribed.
          </Alert>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="allergy-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Record allergy'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ------------------------------------------------------------------ history

const CATEGORIES: HistoryCategory[] = ['MEDICAL', 'SURGICAL', 'FAMILY', 'SOCIAL', 'MEDICATION'];

function HistorySection({ patientId }: { patientId: string }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('ALLERGY_WRITE');
  const toast = useToast();
  const invalidate = usePatientInvalidate(patientId);
  const [addOpen, setAddOpen] = useState(false);
  const [change, setChange] = useState<StatusChange<HistoryView> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({ queryKey: ck.history(patientId), queryFn: () => clinicalApi.history(patientId) });
  const mutate = useMutation({
    mutationFn: ({ item, status, reason }: { item: HistoryView; status: string; reason: string }) =>
      clinicalApi.changeHistoryStatus(item.id, status as HistoryView['status'], reason),
    onSuccess: (h) => {
      toast.success(`${h.conditionName}: ${humanize(h.status).toLowerCase()}`);
      invalidate();
      setChange(null);
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const list = [...(q.data ?? [])].sort(
    (a, b) => (a.status === 'ACTIVE' ? 0 : a.status === 'RESOLVED' ? 1 : 2) - (b.status === 'ACTIVE' ? 0 : b.status === 'RESOLVED' ? 1 : 2) ||
      CATEGORIES.indexOf(a.category) - CATEGORIES.indexOf(b.category),
  );

  return (
    <SectionCard
      title="Medical history"
      action={
        canWrite && (
          <Button size="small" startIcon={<Plus size={14} />} onClick={() => setAddOpen(true)}>
            Add
          </Button>
        )
      }
    >
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState icon={BookOpenText} title="No history recorded" compact />
      ) : (
        <Stack spacing={2}>
          {list.map((h) => (
            <RecordCard key={h.id} muted={h.status !== 'ACTIVE'}>
              <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                <FlagChip tone="primary" label={humanize(h.category)} />
                <Typography
                  variant="body1"
                  fontWeight={600}
                  sx={{ textDecoration: h.status === 'ENTERED_IN_ERROR' ? 'line-through' : undefined, wordBreak: 'break-word' }}
                >
                  {h.conditionName}
                </Typography>
                {h.status !== 'ACTIVE' && <StatusChip value={h.status} />}
              </Stack>
              {h.details && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5 }}>
                  {h.details}
                </Typography>
              )}
              <MetaLine
                items={[
                  h.onsetDate ? ['Since', formatDate(h.onsetDate)] : null,
                  ['Recorded', `${formatDate(h.recordedAt)} · ${h.recordedBy ?? '—'}`],
                  h.statusReason ? ['Reason', h.statusReason] : null,
                ]}
              />
              {canWrite && h.status !== 'ENTERED_IN_ERROR' && (
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 2 }}>
                  {h.status === 'ACTIVE' ? (
                    <Button size="small" variant="outlined" onClick={() => { setError(null); setChange({ item: h, status: 'RESOLVED', label: 'Mark resolved' }); }}>
                      Mark resolved
                    </Button>
                  ) : (
                    <Button size="small" variant="outlined" onClick={() => { setError(null); setChange({ item: h, status: 'ACTIVE', label: 'Mark active' }); }}>
                      Mark active
                    </Button>
                  )}
                  <Button size="small" color="inherit" onClick={() => { setError(null); setChange({ item: h, status: 'ENTERED_IN_ERROR', label: 'Entered in error' }); }}>
                    Entered in error
                  </Button>
                </Stack>
              )}
            </RecordCard>
          ))}
        </Stack>
      )}
      <AddHistoryDialog open={addOpen} onClose={() => setAddOpen(false)} patientId={patientId} />
      <ConfirmDialog
        open={!!change}
        title={`${change?.label}: ${change?.item.conditionName}`}
        description={
          change?.status === 'ENTERED_IN_ERROR'
            ? 'The entry stays in the record, struck through, with your reason. This cannot be reversed.'
            : 'The change and your reason are kept in the record.'
        }
        requireReason
        reasonLabel="Reason"
        tone={change?.status === 'ENTERED_IN_ERROR' ? 'warning' : 'primary'}
        confirmLabel={change?.label}
        loading={mutate.isPending}
        error={error}
        onConfirm={(reason) => change && mutate.mutate({ item: change.item, status: change.status, reason: reason! })}
        onClose={() => !mutate.isPending && setChange(null)}
      />
    </SectionCard>
  );
}

const historySchema = z.object({
  category: z.enum(['MEDICAL', 'SURGICAL', 'FAMILY', 'SOCIAL', 'MEDICATION']),
  conditionName: z.string().trim().min(2, 'Name the condition').max(300),
  details: z.string().trim().max(2000).optional(),
  onsetDate: z
    .string()
    .optional()
    .refine((v) => !v || v <= todayLocalDate(), 'Onset date cannot be in the future'),
});
type HistoryForm = z.infer<typeof historySchema>;
const HISTORY_DEFAULTS: HistoryForm = { category: 'MEDICAL', conditionName: '', details: '', onsetDate: '' };

function AddHistoryDialog({ open, onClose, patientId }: { open: boolean; onClose: () => void; patientId: string }) {
  const toast = useToast();
  const invalidate = usePatientInvalidate(patientId);
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<HistoryForm>({ resolver: zodResolver(historySchema), defaultValues: HISTORY_DEFAULTS });
  useEffect(() => {
    if (open) {
      reset(HISTORY_DEFAULTS);
      setError(null);
    }
  }, [open, reset]);
  const save = useMutation({
    mutationFn: (v: HistoryForm) =>
      clinicalApi.addHistory(patientId, {
        category: v.category as HistoryCategory,
        conditionName: v.conditionName.trim(),
        details: v.details?.trim() || null,
        onsetDate: v.onsetDate || null,
      }),
    onSuccess: (h) => {
      toast.success(`History recorded: ${h.conditionName}`);
      invalidate();
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="history-title">
      <DialogTitle id="history-title">Add medical history</DialogTitle>
      <DialogContent>
        <Stack spacing={3} component="form" id="history-form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))} sx={{ pt: 1 }}>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <RHFSelect control={control} name="category" label="Category" options={CATEGORIES.map((c) => ({ value: c, label: humanize(c) }))} />
            </Grid>
            <Grid size={{ xs: 12, sm: 7 }}>
              <RHFTextField control={control} name="conditionName" label="Condition / item" required />
            </Grid>
          </Grid>
          <RHFTextField control={control} name="details" label="Details" multiline minRows={2} />
          <Box sx={{ maxWidth: 240 }}>
            <RHFTextField control={control} name="onsetDate" label="Onset date (optional)" type="date" />
          </Box>
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="history-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : 'Add'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

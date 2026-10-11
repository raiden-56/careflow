'use client';

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Card, Grid, Stack, Typography } from '@mui/material';
import { Download, FilePen, FilePlus2, Send, ShieldCheck } from 'lucide-react';
import { dischargeApi, dqk, type SummaryFields, type SummaryView } from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { RHFTextField } from '@/components/forms/Fields';
import { StatusChip } from '@/components/ui/StatusChip';
import { SectionCard } from '@/components/ui/SectionCard';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { useDischargeMutation } from './hooks';

const SECTIONS: { key: keyof SummaryFields; label: string; required?: boolean; rows: number; hint?: string }[] = [
  { key: 'presentingComplaint', label: 'Presenting complaint / reason for admission', rows: 3 },
  { key: 'diagnosesText', label: 'Diagnoses', required: true, rows: 3 },
  { key: 'treatmentSummary', label: 'Hospital course and treatment', required: true, rows: 5 },
  { key: 'proceduresText', label: 'Procedures', rows: 3 },
  { key: 'relevantResults', label: 'Relevant investigation results', rows: 3, hint: 'Pre-filled with reviewed results only.' },
  { key: 'conditionAtDischarge', label: 'Condition at discharge', required: true, rows: 3 },
  { key: 'dischargeMedications', label: 'Discharge medications', rows: 4, hint: 'Pre-filled from active discharge prescriptions with patient instructions.' },
  { key: 'dischargeInstructions', label: 'Instructions for the patient', required: true, rows: 4 },
  { key: 'followUpPlan', label: 'Follow-up plan', rows: 3 },
  { key: 'warningSigns', label: 'Warning signs - when to seek urgent care', rows: 3, hint: 'Shown prominently to the patient once released.' },
];

const section = z.string().max(10000, 'At most 10,000 characters').optional();
const schema = z.object({
  presentingComplaint: section,
  diagnosesText: section,
  treatmentSummary: section,
  proceduresText: section,
  relevantResults: section,
  conditionAtDischarge: section,
  dischargeMedications: section,
  dischargeInstructions: section,
  followUpPlan: section,
  warningSigns: section,
  amendmentReason: z.string().max(1000).optional(),
});
type FormValues = z.infer<typeof schema>;

function toForm(s?: SummaryView): FormValues {
  const v: FormValues = { amendmentReason: '' };
  SECTIONS.forEach((x) => {
    v[x.key] = s?.[x.key] ?? '';
  });
  return v;
}

/**
 * Discharge summary: pre-filled draft from recorded data, edited and finalized by the doctor, released to the
 * patient, amendable with an audited reason. Read-only for other clinical staff.
 */
export function SummaryEditor({ admissionId, admissionNumber }: { admissionId: string; admissionNumber?: string }) {
  const toast = useToast();
  const q = useQuery({ queryKey: dqk.summary(admissionId), queryFn: () => dischargeApi.summary(admissionId) });
  const s = q.data?.summary;
  const canWrite = !!q.data?.canWrite;
  const [amending, setAmending] = useState(false);
  const [confirm, setConfirm] = useState<'finalize' | 'release' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: toForm(s) });
  const { reset, handleSubmit, control, formState, setError: setFieldError, watch } = form;

  useEffect(() => {
    reset(toForm(s));
  }, [s, reset]);

  const create = useDischargeMutation(() => dischargeApi.createSummary(admissionId), { success: 'Draft created from recorded data' });
  const save = useDischargeMutation(
    (v: FormValues) => {
      const body: SummaryFields & { amendmentReason?: string; version: number } = { version: s!.version };
      SECTIONS.forEach((x) => {
        body[x.key] = v[x.key] ?? '';
      });
      if (s!.status !== 'DRAFT') body.amendmentReason = v.amendmentReason;
      return dischargeApi.updateSummary(admissionId, body);
    },
    {
      success: () => (s?.status === 'DRAFT' ? 'Draft saved' : 'Amendment saved'),
      onSuccess: () => setAmending(false),
      onError: (e) => {
        if (!applyServerErrors(e, setFieldError)) toast.error(e);
      },
    },
  );
  const finalize = useDischargeMutation(() => dischargeApi.finalizeSummary(admissionId, s!.version), {
    success: 'Discharge summary finalized',
    onSuccess: () => setConfirm(null),
    onError: (e) => setError(errorMessage(e)),
  });
  const release = useDischargeMutation(() => dischargeApi.releaseSummary(admissionId), {
    success: 'Summary released to the patient portal',
    onSuccess: () => setConfirm(null),
    onError: (e) => setError(errorMessage(e)),
  });

  if (q.isLoading) return <Card><TableSkeleton rows={4} columns={2} /></Card>;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  if (!s) {
    return (
      <Card>
        <EmptyState
          icon={FilePlus2}
          title="No discharge summary yet"
          description={
            canWrite
              ? 'Create a draft pre-filled only from recorded data (admission reason, diagnoses, procedures, reviewed results, treatment plan, discharge medicines and follow-ups). Every section stays editable.'
              : 'The treating doctor has not started the discharge summary.'
          }
          action={
            canWrite && (
              <Button variant="contained" startIcon={<FilePlus2 size={16} />} onClick={() => create.mutate(undefined)} disabled={create.isPending}>
                {create.isPending ? 'Creating…' : 'Create draft from records'}
              </Button>
            )
          }
        />
      </Card>
    );
  }

  const editable = canWrite && (s.status === 'DRAFT' || amending);
  const amendReason = watch('amendmentReason') ?? '';
  const missing = SECTIONS.filter((x) => x.required && !(s[x.key] ?? '').trim()).map((x) => x.label.toLowerCase());

  return (
    <Stack spacing={5}>
      <Card>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ p: 4 }} justifyContent="space-between" alignItems={{ md: 'center' }}>
          <Box sx={{ minWidth: 0 }}>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="h4" component="h2">
                Discharge summary
              </Typography>
              <StatusChip value={s.status} />
              {s.released ? <StatusChip value="RELEASED" label="Shared with patient" tone="success" /> : <StatusChip value="NOT_RELEASED" label="Not shared with patient" tone="neutral" />}
            </Stack>
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
              Prepared by {s.preparedByName} · {formatDateTime(s.preparedAt)}
              {s.finalizedAt ? ` · Finalized by ${s.finalizedByName} · ${formatDateTime(s.finalizedAt)}` : ''}
              {s.releasedAt ? ` · Released ${formatDateTime(s.releasedAt)}` : ''}
              {` · Last edited by ${s.updatedByName ?? '—'} · ${formatDateTime(s.updatedAt)}`}
            </Typography>
          </Box>
          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
            <Button variant="outlined" startIcon={<Download size={16} />} onClick={() => dischargeApi.downloadSummaryPdf(admissionId, admissionNumber).catch(toast.error)}>
              PDF
            </Button>
            {canWrite && s.status === 'DRAFT' && (
              <Button variant="contained" startIcon={<ShieldCheck size={16} />} disabled={formState.isDirty} onClick={() => { setError(null); setConfirm('finalize'); }}>
                Finalize
              </Button>
            )}
            {canWrite && s.status !== 'DRAFT' && !amending && (
              <Button variant="outlined" startIcon={<FilePen size={16} />} onClick={() => setAmending(true)}>
                Amend
              </Button>
            )}
            {q.data?.canRelease && s.status !== 'DRAFT' && !s.released && (
              <Button variant="contained" startIcon={<Send size={16} />} onClick={() => { setError(null); setConfirm('release'); }}>
                Release to patient
              </Button>
            )}
          </Stack>
        </Stack>
        {canWrite && s.status === 'DRAFT' && formState.isDirty && (
          <Alert severity="info" sx={{ borderRadius: 0 }}>
            Save your changes before finalizing.
          </Alert>
        )}
        {canWrite && s.status === 'DRAFT' && !formState.isDirty && missing.length > 0 && (
          <Alert severity="warning" sx={{ borderRadius: 0 }}>
            Complete before finalizing: {missing.join(', ')}.
          </Alert>
        )}
      </Card>

      {editable ? (
        <SectionCard title={amending ? 'Amend finalized summary' : 'Edit draft'} subtitle="Pre-filled from recorded data. Review every section; nothing is decided automatically.">
          <Box component="form" noValidate onSubmit={handleSubmit((v) => save.mutate(v))}>
            <Grid container spacing={4}>
              {SECTIONS.map((x) => (
                <Grid key={x.key} size={{ xs: 12, md: x.rows >= 4 ? 12 : 6 }}>
                  <RHFTextField
                    control={control}
                    name={x.key}
                    label={x.label + (x.required ? ' *' : '')}
                    multiline
                    minRows={x.rows}
                    fullWidth
                    helperText={x.hint}
                  />
                </Grid>
              ))}
              {amending && (
                <Grid size={12}>
                  <RHFTextField
                    control={control}
                    name="amendmentReason"
                    label="Reason for amendment *"
                    fullWidth
                    helperText="Recorded in the amendment history and audit log (at least 5 characters)."
                  />
                </Grid>
              )}
            </Grid>
            <Stack direction="row" spacing={2} justifyContent="flex-end" sx={{ mt: 4 }}>
              {amending && (
                <Button color="inherit" onClick={() => { setAmending(false); reset(toForm(s)); }}>
                  Discard
                </Button>
              )}
              {!amending && formState.isDirty && (
                <Button color="inherit" onClick={() => reset(toForm(s))}>
                  Discard changes
                </Button>
              )}
              <Button type="submit" variant="contained" disabled={save.isPending || (!amending && !formState.isDirty) || (amending && amendReason.trim().length < 5)}>
                {save.isPending ? 'Saving…' : amending ? 'Save amendment' : 'Save draft'}
              </Button>
            </Stack>
          </Box>
        </SectionCard>
      ) : (
        <SectionCard title="Summary content">
          <Grid container spacing={4}>
            {SECTIONS.map((x) => (
              <Grid key={x.key} size={{ xs: 12, md: 6 }}>
                <Typography variant="caption" color="text.secondary" component="h3" sx={{ fontWeight: 600 }}>
                  {x.label}
                </Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 0.5, wordBreak: 'break-word' }}>
                  {s[x.key] || '—'}
                </Typography>
              </Grid>
            ))}
          </Grid>
        </SectionCard>
      )}

      {s.amendments.length > 0 && (
        <SectionCard title="Amendment history">
          <Stack spacing={2}>
            {s.amendments.map((a) => (
              <Typography key={a.amendedAt} variant="body2">
                {formatDateTime(a.amendedAt)} · {a.amendedByName}: {a.reason}
              </Typography>
            ))}
          </Stack>
        </SectionCard>
      )}

      <ConfirmDialog
        open={confirm === 'finalize'}
        title="Finalize discharge summary?"
        description="Finalizing signs the summary in your name. Later changes are recorded as amendments with a reason."
        confirmLabel="Finalize"
        loading={finalize.isPending}
        error={error}
        onConfirm={() => finalize.mutate(undefined)}
        onClose={() => setConfirm(null)}
      />
      <ConfirmDialog
        open={confirm === 'release'}
        title="Release summary to the patient?"
        description="The patient and authorized caregivers can read the summary, take-home medicines and warning signs in the portal, and download the PDF."
        confirmLabel="Release"
        loading={release.isPending}
        error={error}
        onConfirm={() => release.mutate(undefined)}
        onClose={() => setConfirm(null)}
      />
    </Stack>
  );
}

'use client';

import { ChangeEvent, useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
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
import { CalendarClock, CheckCircle2, Plus, Scissors, XCircle } from 'lucide-react';
import { CatalogueItem, ck, clinicalApi, ProcedureView } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, formatINR, isoToLocalInput, localInputToIso } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { DefinitionList } from '@/components/ui/Display';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FlagChip, MetaLine, RecordCard, useClinicalInvalidate } from '../shared';

/** Procedures for an admission: plan, complete (posts the tariff charge when linked), cancel with reason. */
export function ProceduresPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('PROCEDURE_WRITE');
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [planOpen, setPlanOpen] = useState(false);
  const [completing, setCompleting] = useState<ProcedureView | null>(null);
  const [cancelling, setCancelling] = useState<ProcedureView | null>(null);
  const [cancelError, setCancelError] = useState<string | null>(null);
  const q = useQuery({ queryKey: ck.procedures(admissionId), queryFn: () => clinicalApi.procedures(admissionId) });
  const cancel = useMutation({
    mutationFn: ({ p, reason }: { p: ProcedureView; reason: string }) => clinicalApi.cancelProcedure(p.id, reason),
    onSuccess: (p) => {
      toast.success(`${p.name} cancelled`);
      invalidate(admissionId);
      setCancelling(null);
    },
    onError: (e) => setCancelError(errorMessage(e)),
  });

  const list = [...(q.data ?? [])].sort((a, b) => order(a) - order(b));

  return (
    <Box>
      {canWrite && (
        <Stack direction="row" justifyContent="flex-end" sx={{ mb: 3 }}>
          <Button variant="outlined" startIcon={<Plus size={16} />} onClick={() => setPlanOpen(true)}>
            Plan procedure
          </Button>
        </Stack>
      )}
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : list.length === 0 ? (
        <EmptyState icon={Scissors} title="No procedures planned or performed" description="Bedside and theatre procedures for this admission appear here." compact />
      ) : (
        <Stack spacing={2}>
          {list.map((p) => (
            <RecordCard key={p.id} accent={p.status === 'PLANNED' ? colors.info : undefined} muted={p.status === 'CANCELLED'}>
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="body1" fontWeight={600}>
                      {p.name}
                    </Typography>
                    <StatusChip value={p.status} />
                    {p.chargePosted && <FlagChip tone="neutral" label="Charge posted" />}
                  </Stack>
                  {p.tariffName && p.tariffName !== p.name && (
                    <Typography variant="caption" color="text.secondary">
                      Billed as {p.tariffName}
                    </Typography>
                  )}
                  <MetaLine
                    items={[
                      p.scheduledAt ? ['Scheduled', formatDateTime(p.scheduledAt)] : null,
                      p.performedAt ? ['Performed', `${formatDateTime(p.performedAt)}${p.performedBy ? ` · ${p.performedBy}` : ''}`] : null,
                      ['Planned by', `${p.createdBy ?? '—'} · ${formatDateTime(p.createdAt)}`],
                    ]}
                  />
                </Box>
                {canWrite && p.status === 'PLANNED' && (
                  <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
                    <Button size="small" variant="contained" startIcon={<CheckCircle2 size={14} />} onClick={() => setCompleting(p)}>
                      Record completion
                    </Button>
                    <Button
                      size="small"
                      color="error"
                      startIcon={<XCircle size={14} />}
                      onClick={() => {
                        setCancelError(null);
                        setCancelling(p);
                      }}
                    >
                      Cancel
                    </Button>
                  </Stack>
                )}
              </Stack>
              {(p.findings || p.complications || p.anaesthesia || p.notes) && (
                <Box sx={{ mt: 3 }}>
                  <DefinitionList
                    items={[
                      ...(p.anaesthesia ? [{ label: 'Anaesthesia', value: p.anaesthesia }] : []),
                      ...(p.findings ? [{ label: 'Findings', value: p.findings, span: 2 as const }] : []),
                      ...(p.complications ? [{ label: 'Complications', value: p.complications, span: 2 as const }] : []),
                      ...(p.notes ? [{ label: p.status === 'CANCELLED' ? 'Notes / cancellation' : 'Notes', value: p.notes, span: 2 as const }] : []),
                    ]}
                  />
                </Box>
              )}
            </RecordCard>
          ))}
        </Stack>
      )}
      <PlanProcedureDialog open={planOpen} onClose={() => setPlanOpen(false)} admissionId={admissionId} />
      <CompleteProcedureDialog procedure={completing} onClose={() => setCompleting(null)} />
      <ConfirmDialog
        open={!!cancelling}
        title="Cancel procedure?"
        description={<><strong>{cancelling?.name}</strong> will be marked cancelled and kept in the record. No charge is posted for cancelled procedures.</>}
        requireReason
        reasonLabel="Reason for cancelling"
        tone="error"
        confirmLabel="Cancel procedure"
        cancelLabel="Keep"
        loading={cancel.isPending}
        error={cancelError}
        onConfirm={(reason) => cancelling && cancel.mutate({ p: cancelling, reason: reason! })}
        onClose={() => !cancel.isPending && setCancelling(null)}
      />
    </Box>
  );
}

function order(p: ProcedureView) {
  return p.status === 'PLANNED' ? 0 : p.status === 'COMPLETED' ? 1 : 2;
}

function PlanProcedureDialog({ open, onClose, admissionId }: { open: boolean; onClose: () => void; admissionId: string }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [name, setName] = useState('');
  const [tariff, setTariff] = useState<CatalogueItem | null>(null);
  const [scheduledAt, setScheduledAt] = useState('');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (open) {
      setName('');
      setTariff(null);
      setScheduledAt('');
      setNotes('');
      setError(null);
      setTouched(false);
    }
  }, [open]);
  const cat = useQuery({
    queryKey: ck.catalogue({ category: 'PROCEDURE' }),
    queryFn: () => clinicalApi.catalogue({ category: 'PROCEDURE' }),
    enabled: open,
    staleTime: 60_000,
  });
  const save = useMutation({
    mutationFn: () =>
      clinicalApi.planProcedure(admissionId, {
        name: name.trim(),
        tariffId: tariff?.tariffId ?? null,
        scheduledAt: scheduledAt ? localInputToIso(scheduledAt) : null,
        notes: notes.trim() || null,
      }),
    onSuccess: (p) => {
      toast.success(`${p.name} planned`);
      invalidate(admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const invalid = name.trim().length < 3;
  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="proc-title">
      <DialogTitle id="proc-title">Plan procedure</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Autocomplete<CatalogueItem>
            options={cat.data ?? []}
            loading={cat.isLoading}
            value={tariff}
            onChange={(_, v) => {
              setTariff(v);
              if (v) setName(v.name);
            }}
            getOptionLabel={(c) => `${c.name} · ${formatINR(c.unitPrice)}`}
            isOptionEqualToValue={(a, b) => a.tariffId === b.tariffId}
            renderInput={(p) => <TextField {...p} label="Billable procedure (optional)" helperText="Links the hospital tariff; the charge is posted on completion." />}
          />
          <TextField
            label="Procedure name"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onBlur={() => setTouched(true)}
            required
            error={touched && invalid}
            helperText={touched && invalid ? 'Name the procedure' : ' '}
          />
          <TextField
            type="datetime-local"
            label="Scheduled for (optional)"
            value={scheduledAt}
            onChange={(e) => setScheduledAt(e.target.value)}
            slotProps={{ inputLabel: { shrink: true } }}
          />
          <TextField label="Notes (optional)" value={notes} onChange={(e) => setNotes(e.target.value)} multiline minRows={2} placeholder="Indication, consent status, preparation" />
          {tariff && (
            <Alert severity="info" icon={<CalendarClock size={18} />}>
              A charge of <strong>{formatINR(tariff.unitPrice)}</strong> is posted to the patient&apos;s bill when the procedure is recorded as completed.
            </Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button
          variant="contained"
          disabled={save.isPending}
          onClick={() => {
            setTouched(true);
            if (!invalid) save.mutate();
          }}
        >
          {save.isPending ? 'Saving…' : 'Plan procedure'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function CompleteProcedureDialog({ procedure, onClose }: { procedure: ProcedureView | null; onClose: () => void }) {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [v, setV] = useState({ performedAt: '', anaesthesia: '', findings: '', complications: '', notes: '' });
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (procedure) {
      setV({ performedAt: isoToLocalInput(null), anaesthesia: '', findings: '', complications: '', notes: procedure.notes ?? '' });
      setError(null);
    }
  }, [procedure]);
  const save = useMutation({
    mutationFn: () =>
      clinicalApi.completeProcedure(procedure!.id, {
        performedAt: v.performedAt ? localInputToIso(v.performedAt) : null,
        anaesthesia: v.anaesthesia.trim() || null,
        findings: v.findings.trim() || null,
        complications: v.complications.trim() || null,
        notes: v.notes.trim() || null,
      }),
    onSuccess: (p) => {
      toast.success(p.chargePosted ? `${p.name} completed; charge posted to the bill` : `${p.name} recorded as completed`);
      invalidate(p.admissionId);
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const set = (k: keyof typeof v) => (e: ChangeEvent<HTMLInputElement>) => setV((s) => ({ ...s, [k]: e.target.value }));
  return (
    <Dialog open={!!procedure} onClose={save.isPending ? undefined : onClose} maxWidth="sm" fullWidth fullScreen={fullScreen} aria-labelledby="proc-done-title">
      <DialogTitle id="proc-done-title">Record completion: {procedure?.name}</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField type="datetime-local" label="Performed at" value={v.performedAt} onChange={set('performedAt')} slotProps={{ inputLabel: { shrink: true } }} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <TextField label="Anaesthesia" value={v.anaesthesia} onChange={set('anaesthesia')} placeholder="e.g. Local, 2% lignocaine" />
            </Grid>
          </Grid>
          <TextField label="Findings" value={v.findings} onChange={set('findings')} multiline minRows={2} />
          <TextField label="Complications" value={v.complications} onChange={set('complications')} multiline minRows={2} placeholder="None, or describe" />
          <TextField label="Notes" value={v.notes} onChange={set('notes')} multiline minRows={2} />
          {procedure?.tariffId && (
            <Alert severity="info">Completing this procedure posts its tariff charge{procedure.tariffName ? ` (${procedure.tariffName})` : ''} to the patient&apos;s bill.</Alert>
          )}
          {error && <Alert severity="error">{error}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button variant="contained" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? 'Saving…' : 'Record completion'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

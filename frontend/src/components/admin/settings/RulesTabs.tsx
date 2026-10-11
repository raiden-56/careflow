'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Dialog, DialogActions, DialogContent, DialogTitle, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { Pencil, Plus } from 'lucide-react';
import { adminApi, EscalationEvent, EscalationView, ThresholdView, VitalParameter } from '@/lib/api/admin';
import type { Role } from '@/lib/api/types';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { humanize } from '@/lib/format';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const EVENTS: EscalationEvent[] = ['MEDICATION_OVERDUE', 'CRITICAL_OBSERVATION', 'ALERT_UNACKNOWLEDGED', 'DISCHARGE_BLOCKED', 'HANDOVER_UNACKNOWLEDGED', 'LAB_RESULT_UNREVIEWED'];
const STAFF_ROLES: Role[] = ['NURSE', 'DOCTOR', 'PHARMACIST', 'BILLING', 'HOSPITAL_ADMIN'];
const PARAMS: VitalParameter[] = ['TEMPERATURE_C', 'PULSE_BPM', 'RESPIRATORY_RATE', 'SYSTOLIC_BP', 'DIASTOLIC_BP', 'SPO2', 'PAIN_SCORE', 'BLOOD_GLUCOSE'];
const PARAM_LABEL: Record<VitalParameter, string> = {
  TEMPERATURE_C: 'Temperature (°C)',
  PULSE_BPM: 'Pulse (bpm)',
  RESPIRATORY_RATE: 'Respiratory rate (/min)',
  SYSTOLIC_BP: 'Systolic BP (mmHg)',
  DIASTOLIC_BP: 'Diastolic BP (mmHg)',
  SPO2: 'SpO₂ (%)',
  PAIN_SCORE: 'Pain score (0-10)',
  BLOOD_GLUCOSE: 'Blood glucose (mg/dL)',
};

function Header({ title, subtitle, onAdd, addLabel }: { title: string; subtitle: string; onAdd: () => void; addLabel: string }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2} sx={{ p: 4 }}>
      <Box>
        <Typography variant="h4" component="h2">
          {title}
        </Typography>
        <Typography variant="body2" color="text.secondary">
          {subtitle}
        </Typography>
      </Box>
      <Button variant="contained" startIcon={<Plus size={16} />} onClick={onAdd} sx={{ alignSelf: { sm: 'flex-start' }, flexShrink: 0 }}>
        {addLabel}
      </Button>
    </Stack>
  );
}

// ================================================================== escalations

const escSchema = z.object({
  eventType: z.enum(EVENTS as [EscalationEvent, ...EscalationEvent[]], { message: 'Select an event' }),
  thresholdMinutes: z.coerce.number({ message: 'Enter minutes' }).int('Whole minutes only').min(0, 'Zero or more').max(10080, 'At most 7 days'),
  escalateToRole: z.enum(STAFF_ROLES as [Role, ...Role[]], { message: 'Select a role' }),
  escalationLevel: z.coerce.number().int().min(1, '1-5').max(5, '1-5'),
  priority: z.enum(['LOW', 'NORMAL', 'HIGH', 'CRITICAL']),
  description: z.string().trim().min(3, 'Describe the rule').max(500),
  active: z.boolean(),
});
type EscValues = z.infer<typeof escSchema>;

export function EscalationsTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'escalations'], queryFn: adminApi.escalations });
  const [dialog, setDialog] = useState<{ open: boolean; rule?: EscalationView }>({ open: false });
  return (
    <Card>
      <Header
        title="Escalation rules"
        subtitle="When an event stays unresolved past the threshold, the role is notified. Multiple levels escalate further over time."
        onAdd={() => setDialog({ open: true })}
        addLabel="New rule"
      />
      <DataTable<EscalationView>
        ariaLabel="Escalation rules"
        rows={q.data}
        loading={q.isFetching}
        error={q.error}
        onRetry={() => q.refetch()}
        rowKey={(r) => r.id}
        emptyTitle="No escalation rules"
        columns={[
          {
            key: 'event',
            header: 'Event',
            primary: true,
            render: (r) => (
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {humanize(r.eventType)} · level {r.escalationLevel}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {r.description}
                </Typography>
              </Box>
            ),
          },
          { key: 'after', header: 'After', render: (r) => (r.thresholdMinutes === 0 ? 'Immediately' : `${r.thresholdMinutes} min`) },
          { key: 'role', header: 'Notify', render: (r) => humanize(r.escalateToRole) },
          { key: 'prio', header: 'Priority', render: (r) => <StatusChip value={r.priority} /> },
          { key: 'status', header: 'Status', render: (r) => <StatusChip value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
          {
            key: 'edit',
            header: '',
            align: 'right',
            render: (r) => (
              <Tooltip title="Edit">
                <IconButton size="small" aria-label={`Edit ${r.eventType} level ${r.escalationLevel}`} onClick={() => setDialog({ open: true, rule: r })}>
                  <Pencil size={16} />
                </IconButton>
              </Tooltip>
            ),
          },
        ]}
      />
      <EscalationDialog
        open={dialog.open}
        rule={dialog.rule}
        onClose={() => setDialog({ open: false })}
        onSaved={() => {
          toast.success('Escalation rule saved');
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['settings', 'escalations'] });
        }}
      />
    </Card>
  );
}

function EscalationDialog({ open, rule, onClose, onSaved }: { open: boolean; rule?: EscalationView; onClose: () => void; onSaved: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<EscValues>({ resolver: zodResolver(escSchema) as never });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        eventType: (rule?.eventType ?? '') as EscalationEvent,
        thresholdMinutes: rule?.thresholdMinutes ?? 30,
        escalateToRole: (rule?.escalateToRole ?? '') as Role,
        escalationLevel: rule?.escalationLevel ?? 1,
        priority: rule?.priority ?? 'HIGH',
        description: rule?.description ?? '',
        active: rule?.active ?? true,
      });
    }
  }, [open, rule, reset]);
  const m = useMutation({
    mutationFn: (v: EscValues) => (rule ? adminApi.updateEscalation(rule.id, { ...v, version: rule.version }) : adminApi.createEscalation(v)),
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="esc-title">
      <DialogTitle id="esc-title">{rule ? 'Edit escalation rule' : 'New escalation rule'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="esc-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <RHFSelect control={control} name="eventType" label="Event" required fullWidth options={EVENTS.map((e) => ({ value: e, label: humanize(e) }))} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="thresholdMinutes" label="After (minutes)" type="number" required sx={{ flex: 1 }} />
            <RHFTextField control={control} name="escalationLevel" label="Level" type="number" required sx={{ flex: 1 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="escalateToRole" label="Notify role" required options={STAFF_ROLES.map((r) => ({ value: r, label: humanize(r) }))} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="priority" label="Priority" options={['LOW', 'NORMAL', 'HIGH', 'CRITICAL'].map((p) => ({ value: p, label: humanize(p) }))} sx={{ flex: 1 }} />
          </Stack>
          <RHFTextField control={control} name="description" label="Description" required fullWidth multiline minRows={2} />
          <RHFCheckbox control={control} name="active" label="Active" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="esc-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ================================================================== thresholds

const decimalOpt = z
  .string()
  .trim()
  .regex(/^(\d{1,6}(\.\d{1,2})?)?$/, 'Enter a number')
  .optional();
const thrSchema = z
  .object({
    parameter: z.enum(PARAMS as [VitalParameter, ...VitalParameter[]], { message: 'Select a parameter' }),
    minValue: decimalOpt,
    maxValue: decimalOpt,
    severity: z.enum(['WARNING', 'CRITICAL']),
    description: z.string().trim().min(3, 'Describe the threshold').max(300),
    notifyRole: z.string().optional(),
    active: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (!v.minValue && !v.maxValue) ctx.addIssue({ code: 'custom', path: ['minValue'], message: 'Set a lower or upper bound' });
    if (v.minValue && v.maxValue && Number(v.minValue) >= Number(v.maxValue))
      ctx.addIssue({ code: 'custom', path: ['maxValue'], message: 'Upper bound must be above the lower bound' });
  });
type ThrValues = z.infer<typeof thrSchema>;

export function ThresholdsTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'thresholds'], queryFn: adminApi.thresholds });
  const [dialog, setDialog] = useState<{ open: boolean; rule?: ThresholdView }>({ open: false });
  return (
    <Card>
      <Header
        title="Observation thresholds"
        subtitle="Decision support for vital signs: crossing a threshold raises an alert for the care team. Defaults are examples — confirm them with clinical governance."
        onAdd={() => setDialog({ open: true })}
        addLabel="New threshold"
      />
      <Box sx={{ px: 4, pb: 3 }}>
        <Alert severity="info">These rules are configuration only. The clinical module evaluates them when observations are recorded; they never diagnose.</Alert>
      </Box>
      <DataTable<ThresholdView>
        ariaLabel="Observation thresholds"
        rows={q.data}
        loading={q.isFetching}
        error={q.error}
        onRetry={() => q.refetch()}
        rowKey={(r) => r.id}
        emptyTitle="No thresholds configured"
        columns={[
          {
            key: 'param',
            header: 'Parameter',
            primary: true,
            render: (r) => (
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {PARAM_LABEL[r.parameter]}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {r.description}
                </Typography>
              </Box>
            ),
          },
          {
            key: 'bounds',
            header: 'Alert when',
            render: (r) => [r.minValue ? `< ${Number(r.minValue)}` : null, r.maxValue ? `> ${Number(r.maxValue)}` : null].filter(Boolean).join(' or '),
          },
          { key: 'sev', header: 'Severity', render: (r) => <StatusChip value={r.severity} /> },
          { key: 'notify', header: 'Notify', render: (r) => (r.notifyRole ? humanize(r.notifyRole) : 'Care team') },
          { key: 'status', header: 'Status', render: (r) => <StatusChip value={r.active ? 'ACTIVE' : 'INACTIVE'} /> },
          {
            key: 'edit',
            header: '',
            align: 'right',
            render: (r) => (
              <Tooltip title="Edit">
                <IconButton size="small" aria-label={`Edit ${r.parameter} ${r.severity}`} onClick={() => setDialog({ open: true, rule: r })}>
                  <Pencil size={16} />
                </IconButton>
              </Tooltip>
            ),
          },
        ]}
      />
      <ThresholdDialog
        open={dialog.open}
        rule={dialog.rule}
        onClose={() => setDialog({ open: false })}
        onSaved={() => {
          toast.success('Threshold saved');
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['settings', 'thresholds'] });
        }}
      />
    </Card>
  );
}

function ThresholdDialog({ open, rule, onClose, onSaved }: { open: boolean; rule?: ThresholdView; onClose: () => void; onSaved: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<ThrValues>({ resolver: zodResolver(thrSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        parameter: (rule?.parameter ?? '') as VitalParameter,
        minValue: rule?.minValue ? String(Number(rule.minValue)) : '',
        maxValue: rule?.maxValue ? String(Number(rule.maxValue)) : '',
        severity: rule?.severity ?? 'WARNING',
        description: rule?.description ?? '',
        notifyRole: rule?.notifyRole ?? '',
        active: rule?.active ?? true,
      });
    }
  }, [open, rule, reset]);
  const m = useMutation({
    mutationFn: (v: ThrValues) => {
      const body = {
        ...v,
        minValue: v.minValue || null,
        maxValue: v.maxValue || null,
        notifyRole: (v.notifyRole || null) as Role | null,
        version: rule?.version,
      };
      return rule ? adminApi.updateThreshold(rule.id, body) : adminApi.createThreshold(body);
    },
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="thr-title">
      <DialogTitle id="thr-title">{rule ? 'Edit threshold' : 'New observation threshold'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="thr-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <RHFSelect control={control} name="parameter" label="Parameter" required fullWidth options={PARAMS.map((p) => ({ value: p, label: PARAM_LABEL[p] }))} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="minValue" label="Alert below" inputMode="decimal" sx={{ flex: 1 }} />
            <RHFTextField control={control} name="maxValue" label="Alert above" inputMode="decimal" sx={{ flex: 1 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="severity" label="Severity" options={[{ value: 'WARNING', label: 'Warning' }, { value: 'CRITICAL', label: 'Critical' }]} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="notifyRole" label="Notify" emptyLabel="Care team (default)" options={[{ value: 'NURSE', label: 'Nurse' }, { value: 'DOCTOR', label: 'Doctor' }]} sx={{ flex: 1 }} />
          </Stack>
          <RHFTextField control={control} name="description" label="Description" required fullWidth />
          <RHFCheckbox control={control} name="active" label="Active" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="thr-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

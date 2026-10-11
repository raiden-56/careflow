'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Checkbox,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  Grid,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { ArrowDown, ArrowUp, KeyRound, Pencil, PlugZap, Plus, Save } from 'lucide-react';
import { adminApi, DischargeCategory, DischargeTemplateView, IntegrationMode, IntegrationView } from '@/lib/api/admin';
import type { Role } from '@/lib/api/types';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { fromNow, humanize } from '@/lib/format';
import { optionalText } from '@/lib/validation';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

// ================================================================== discharge checklist

const CATEGORIES: DischargeCategory[] = ['CLINICAL', 'NURSING', 'PHARMACY', 'ADMINISTRATIVE', 'FINANCIAL', 'PATIENT'];
const ROLES: Role[] = ['DOCTOR', 'NURSE', 'PHARMACIST', 'BILLING', 'HOSPITAL_ADMIN'];

const stepSchema = z.object({
  code: z.string().trim().regex(/^[A-Z][A-Z0-9_]{2,39}$/, 'Use upper-case letters, digits and _ (3-40)'),
  name: z.string().trim().min(3, 'Name is required').max(200),
  description: optionalText(1000, 'Description'),
  category: z.enum(CATEGORIES as [DischargeCategory, ...DischargeCategory[]], { message: 'Select a category' }),
  responsibleRole: z.enum(ROLES as [Role, ...Role[]], { message: 'Select a role' }),
  required: z.boolean(),
  active: z.boolean(),
});
type StepValues = z.infer<typeof stepSchema>;

export function DischargeChecklistTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'discharge-templates'], queryFn: adminApi.dischargeTemplates });
  const [order, setOrder] = useState<DischargeTemplateView[]>([]);
  const [dialog, setDialog] = useState<{ open: boolean; step?: DischargeTemplateView }>({ open: false });
  useEffect(() => {
    if (q.data) setOrder(q.data);
  }, [q.data]);
  const reordered = q.data && order.some((s, i) => s.id !== q.data[i]?.id);
  const save = useMutation({
    mutationFn: () => adminApi.reorderDischargeTemplates(order.map((s) => s.id)),
    onSuccess: (d) => {
      toast.success('Checklist order saved');
      qc.setQueryData(['settings', 'discharge-templates'], d);
    },
    onError: toast.error,
  });
  const move = (i: number, dir: -1 | 1) => {
    setOrder((o) => {
      const n = [...o];
      const j = i + dir;
      if (j < 0 || j >= n.length) return o;
      [n[i], n[j]] = [n[j], n[i]];
      return n;
    });
  };
  if (q.isLoading) return <CardsSkeleton count={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return (
    <Card>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2} sx={{ p: 4 }}>
        <Box>
          <Typography variant="h4" component="h2">
            Discharge checklist template
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Steps copied into each new discharge workflow. Changes do not alter workflows already in progress.
          </Typography>
        </Box>
        <Stack direction="row" spacing={2} sx={{ flexShrink: 0, alignSelf: { sm: 'flex-start' } }}>
          {reordered && (
            <Button color="inherit" onClick={() => q.data && setOrder(q.data)}>
              Undo
            </Button>
          )}
          {reordered && (
            <Button variant="outlined" startIcon={<Save size={16} />} onClick={() => save.mutate()} disabled={save.isPending}>
              Save order
            </Button>
          )}
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setDialog({ open: true })}>
            Add step
          </Button>
        </Stack>
      </Stack>
      <Divider />
      {order.length === 0 ? (
        <EmptyState title="No checklist steps" compact />
      ) : (
        <Stack component="ol" sx={{ m: 0, p: 0, listStyle: 'none' }} divider={<Divider flexItem />}>
          {order.map((s, i) => (
            <Stack component="li" key={s.id} direction="row" spacing={2} alignItems="center" sx={{ px: 4, py: 2.5, opacity: s.active ? 1 : 0.55 }}>
              <Typography variant="body2" color="text.secondary" sx={{ width: 24, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>
                {i + 1}
              </Typography>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" fontWeight={600}>
                  {s.name}
                </Typography>
                <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 0.5 }}>
                  <Chip size="small" label={humanize(s.category)} variant="outlined" />
                  <Chip size="small" label={humanize(s.responsibleRole)} variant="outlined" />
                  {s.required ? <Chip size="small" label="Required" color="primary" variant="outlined" /> : <Chip size="small" label="Optional" variant="outlined" />}
                  {s.autoCheck && <Chip size="small" label={`Auto-check: ${humanize(s.autoCheck)}`} variant="outlined" />}
                  {!s.active && <StatusChip value="INACTIVE" />}
                </Stack>
              </Box>
              <Stack direction="row" spacing={0.5}>
                <IconButton size="small" aria-label={`Move ${s.name} up`} disabled={i === 0} onClick={() => move(i, -1)}>
                  <ArrowUp size={16} />
                </IconButton>
                <IconButton size="small" aria-label={`Move ${s.name} down`} disabled={i === order.length - 1} onClick={() => move(i, 1)}>
                  <ArrowDown size={16} />
                </IconButton>
                <Tooltip title="Edit step">
                  <IconButton size="small" aria-label={`Edit ${s.name}`} onClick={() => setDialog({ open: true, step: s })}>
                    <Pencil size={16} />
                  </IconButton>
                </Tooltip>
              </Stack>
            </Stack>
          ))}
        </Stack>
      )}
      <StepDialog
        open={dialog.open}
        step={dialog.step}
        onClose={() => setDialog({ open: false })}
        onSaved={() => {
          toast.success('Checklist step saved');
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['settings', 'discharge-templates'] });
        }}
      />
    </Card>
  );
}

function StepDialog({ open, step, onClose, onSaved }: { open: boolean; step?: DischargeTemplateView; onClose: () => void; onSaved: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<StepValues>({ resolver: zodResolver(stepSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: step?.code ?? '',
        name: step?.name ?? '',
        description: step?.description ?? '',
        category: (step?.category ?? '') as DischargeCategory,
        responsibleRole: (step?.responsibleRole ?? '') as Role,
        required: step?.required ?? true,
        active: step?.active ?? true,
      });
    }
  }, [open, step, reset]);
  const m = useMutation({
    mutationFn: (v: StepValues) => {
      const body = { ...v, description: v.description || null, autoCheck: step?.autoCheck ?? null, version: step?.version };
      return step ? adminApi.updateDischargeTemplate(step.id, body) : adminApi.createDischargeTemplate(body);
    },
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="step-title">
      <DialogTitle id="step-title">{step ? 'Edit checklist step' : 'Add checklist step'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="step-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <RHFTextField control={control} name="code" label="Code" required fullWidth disabled={!!step} helperText={step ? 'Workflows reference this code' : 'e.g. DIET_COUNSELLING'} />
          <RHFTextField control={control} name="name" label="Step name" required fullWidth />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="category" label="Category" required options={CATEGORIES.map((c) => ({ value: c, label: humanize(c) }))} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="responsibleRole" label="Responsible role" required options={ROLES.map((r) => ({ value: r, label: humanize(r) }))} sx={{ flex: 1 }} />
          </Stack>
          <RHFTextField control={control} name="description" label="Description" fullWidth multiline minRows={2} />
          {step?.autoCheck && (
            <Typography variant="caption" color="text.secondary">
              Automatic check: {humanize(step.autoCheck)} (managed by the discharge workflow)
            </Typography>
          )}
          <RHFCheckbox control={control} name="required" label="Required before discharge completion" />
          <RHFCheckbox control={control} name="active" label="Active (included in new workflows)" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="step-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

// ================================================================== integrations

const MODES: IntegrationMode[] = ['DISABLED', 'DEV_ADAPTER', 'SANDBOX', 'LIVE'];

export function IntegrationsTab() {
  const q = useQuery({ queryKey: ['settings', 'integrations'], queryFn: adminApi.integrations });
  if (q.isLoading) return <CardsSkeleton count={4} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  return (
    <Stack spacing={4}>
      <Alert severity="warning" icon={<PlugZap size={18} />}>
        No live external integration is implemented in this build. Modes record the intended configuration and are audited; SMS and email go to the
        development outbox. Secrets are never stored here — reference the name of a secret held in your secret manager.
      </Alert>
      <Grid container spacing={4}>
        {q.data!.map((i) => (
          <Grid key={i.id} size={{ xs: 12, lg: 6 }}>
            <IntegrationCard integration={i} />
          </Grid>
        ))}
      </Grid>
    </Stack>
  );
}

function IntegrationCard({ integration }: { integration: IntegrationView }) {
  const toast = useToast();
  const qc = useQueryClient();
  const [mode, setMode] = useState<IntegrationMode>(integration.mode);
  const [endpoint, setEndpoint] = useState(integration.endpointUrl ?? '');
  const [secret, setSecret] = useState(integration.secretName ?? '');
  const [notes, setNotes] = useState(integration.notes ?? '');
  const [confirmLive, setConfirmLive] = useState(false);
  const [ack, setAck] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setMode(integration.mode);
    setEndpoint(integration.endpointUrl ?? '');
    setSecret(integration.secretName ?? '');
    setNotes(integration.notes ?? '');
  }, [integration]);
  const dirty = mode !== integration.mode || endpoint !== (integration.endpointUrl ?? '') || secret !== (integration.secretName ?? '') || notes !== (integration.notes ?? '');
  const m = useMutation({
    mutationFn: (live: boolean) =>
      adminApi.updateIntegration(integration.integrationType, {
        mode,
        endpointUrl: endpoint || null,
        secretName: secret || null,
        notes: notes || null,
        confirmLive: live,
        version: integration.version,
      }),
    onSuccess: () => {
      toast.success(`${integration.label} updated`);
      setError(null);
      setConfirmLive(false);
      qc.invalidateQueries({ queryKey: ['settings', 'integrations'] });
    },
    onError: (e) => {
      setConfirmLive(false);
      setError(errorMessage(e));
    },
  });
  const submit = () => {
    if (mode === 'LIVE' && integration.mode !== 'LIVE') {
      setAck(false);
      setConfirmLive(true);
    } else m.mutate(false);
  };
  return (
    <Card sx={{ p: 4, height: '100%' }}>
      <Stack direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h5" component="h3">
            {integration.label}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {integration.integrationType} · updated {fromNow(integration.updatedAt)}
            {integration.updatedBy ? ` by ${integration.updatedBy}` : ''}
          </Typography>
        </Box>
        <StatusChip value={integration.mode} />
      </Stack>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 2 }}>
        {integration.adapterStatus}
      </Typography>
      {integration.boundaryNote && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
          {integration.boundaryNote}
        </Typography>
      )}
      <Stack spacing={3} sx={{ mt: 3 }}>
        {error && <Alert severity="error">{error}</Alert>}
        <TextField select label="Mode" value={mode} onChange={(e) => setMode(e.target.value as IntegrationMode)} fullWidth>
          {MODES.map((x) => (
            <MenuItem key={x} value={x}>
              {humanize(x)}
              {x === 'LIVE' ? ' (requires confirmation)' : ''}
            </MenuItem>
          ))}
        </TextField>
        <TextField
          label="Endpoint URL"
          value={endpoint}
          onChange={(e) => setEndpoint(e.target.value)}
          fullWidth
          placeholder="https://sandbox.example.test/api"
          helperText={mode === 'SANDBOX' || mode === 'LIVE' ? 'Required, https only' : 'Optional'}
        />
        <TextField
          label="Secret reference (name only)"
          value={secret}
          onChange={(e) => setSecret(e.target.value.toUpperCase())}
          fullWidth
          placeholder="ABDM_CLIENT_SECRET"
          helperText="The name of an environment/secret-manager entry. Never paste the secret itself."
          slotProps={{ input: { startAdornment: <KeyRound size={14} style={{ marginRight: 8 }} /> } }}
        />
        <TextField label="Notes" value={notes} onChange={(e) => setNotes(e.target.value)} fullWidth multiline minRows={2} />
        <Stack direction="row" justifyContent="flex-end">
          <Button variant="contained" disabled={!dirty || m.isPending} onClick={submit}>
            Save
          </Button>
        </Stack>
      </Stack>
      <ConfirmDialog
        open={confirmLive}
        title={`Mark ${integration.label} as LIVE?`}
        tone="error"
        confirmLabel="Confirm LIVE mode"
        loading={m.isPending}
        description="LIVE mode declares that this boundary should talk to a production provider. This build has no live connector, so no traffic will be sent — but the change is recorded in the audit log as a security-relevant action."
        onConfirm={() => (ack ? m.mutate(true) : undefined)}
        onClose={() => setConfirmLive(false)}
      >
        <FormControlLabel
          control={<Checkbox checked={ack} onChange={(e) => setAck(e.target.checked)} />}
          label="I confirm production credentials are managed in the secret manager and the provider agreement is in place."
        />
        {!ack && (
          <Typography variant="caption" color="text.secondary">
            Tick the confirmation to continue.
          </Typography>
        )}
      </ConfirmDialog>
    </Card>
  );
}

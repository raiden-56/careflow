'use client';

import { ReactNode, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Chip, Collapse, Stack, Tooltip, Typography } from '@mui/material';
import { Activity, Plus, X } from 'lucide-react';
import { ck, clinicalApi, VitalParameter, VitalView } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, humanize } from '@/lib/format';
import { SectionCard } from '@/components/ui/SectionCard';
import { DataTable } from '@/components/ui/DataTable';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { TrendExplorer } from '@/components/clinical/trends/TrendExplorer';
import { colors } from '@/theme/tokens';
import { VitalsForm } from './VitalsForm';

/** Highest breach severity for a parameter on one observation set ("SPO2:WARNING"). */
export function breachOf(v: Pick<VitalView, 'breaches'>, p: VitalParameter): 'WARNING' | 'CRITICAL' | undefined {
  let sev: 'WARNING' | 'CRITICAL' | undefined;
  for (const b of v.breaches ?? []) {
    const [param, level] = b.split(':');
    if (param !== p) continue;
    if (level === 'CRITICAL') return 'CRITICAL';
    if (level === 'WARNING') sev = 'WARNING';
  }
  return sev;
}

/** A measurement, highlighted when it was outside the configured range. */
export function VitalValue({ vital, param, children }: { vital: VitalView; param: VitalParameter | VitalParameter[]; children: ReactNode }) {
  const params = Array.isArray(param) ? param : [param];
  const sevs = params.map((p) => breachOf(vital, p));
  const sev = sevs.includes('CRITICAL') ? 'CRITICAL' : sevs.includes('WARNING') ? 'WARNING' : undefined;
  if (!sev || vital.enteredInError) return <Box component="span" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>{children}</Box>;
  const critical = sev === 'CRITICAL';
  return (
    <Tooltip title={`${critical ? 'Critical' : 'Warning'}: outside the configured range`}>
      <Box
        component="span"
        sx={{
          px: 1,
          py: 0.25,
          borderRadius: 1,
          fontWeight: 700,
          whiteSpace: 'nowrap',
          fontVariantNumeric: 'tabular-nums',
          color: critical ? colors.danger : colors.warning,
          bgcolor: critical ? colors.dangerSoft : colors.warningSoft,
        }}
      >
        {children}
        <Box component="span" sx={{ position: 'absolute', width: '1px', height: '1px', m: '-1px', p: 0, overflow: 'hidden', clip: 'rect(0 0 0 0)', whiteSpace: 'nowrap', border: 0 }}>
          {' '}
          ({critical ? 'critical' : 'warning'} - outside range)
        </Box>
      </Box>
    </Tooltip>
  );
}

const dash = (v?: number | null, unit = '') => (v === null || v === undefined ? '—' : `${v}${unit}`);

/** Admission vitals tab: entry form, threshold-aware observation table and trend chart. */
export function VitalsPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const canWrite = hasPermission('VITALS_WRITE');
  const [formOpen, setFormOpen] = useState(false);
  const [inError, setInError] = useState<VitalView | null>(null);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const q = useQuery({ queryKey: ck.vitals(admissionId), queryFn: () => clinicalApi.vitals(admissionId) });

  const markError = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => clinicalApi.vitalInError(id, reason),
    onSuccess: () => {
      toast.success('Observation marked as entered in error');
      setInError(null);
      queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    },
    onError: toast.error,
  });

  const rows = (q.data ?? []).slice().sort((a, b) => b.recordedAt.localeCompare(a.recordedAt));
  const pageRows = rows.slice(page * size, page * size + size);
  const strike = (v: VitalView) => (v.enteredInError ? { textDecoration: 'line-through', color: 'text.disabled' } : undefined);

  return (
    <Stack spacing={5}>
      {canWrite && (
        <SectionCard
          title="Record observations"
          subtitle="Values outside hospital-configured ranges raise an alert for review."
          action={
            <Button
              size="small"
              variant={formOpen ? 'text' : 'contained'}
              color={formOpen ? 'inherit' : 'primary'}
              startIcon={formOpen ? <X size={16} /> : <Plus size={16} />}
              onClick={() => setFormOpen((o) => !o)}
              aria-expanded={formOpen}
            >
              {formOpen ? 'Close' : 'New set'}
            </Button>
          }
        >
          <Collapse in={formOpen} unmountOnExit>
            <VitalsForm admissionId={admissionId} />
          </Collapse>
          {!formOpen && (
            <Typography variant="body2" color="text.secondary">
              Open the form to record temperature, pulse, respiration, blood pressure, SpO₂, pain, glucose, weight and consciousness.
            </Typography>
          )}
        </SectionCard>
      )}

      <SectionCard title="Trends">
        <TrendExplorer admissionId={admissionId} compact />
      </SectionCard>

      <SectionCard title="Recent observations" subtitle="Highlighted values were outside the configured range when recorded." flush>
        <DataTable<VitalView>
          rows={q.data ? pageRows : undefined}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(v) => v.id}
          page={page}
          pageSize={size}
          total={rows.length}
          onPageChange={setPage}
          onPageSizeChange={(s) => {
            setSize(s);
            setPage(0);
          }}
          pageSizeOptions={[10, 25, 50]}
          dense
          ariaLabel="Vital sign observations"
          emptyTitle="No observations recorded"
          rowSx={(v) => (v.enteredInError ? { opacity: 0.7 } : undefined)}
          columns={[
            {
              key: 'time',
              header: 'Recorded',
              primary: true,
              render: (v) => (
                <Box sx={strike(v)}>
                  <Typography variant="body2" fontWeight={600} sx={{ whiteSpace: 'nowrap', textDecoration: 'inherit' }}>
                    {formatDateTime(v.recordedAt)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {v.recordedBy ?? ''}
                  </Typography>
                  {v.enteredInError && (
                    <Chip size="small" label="Entered in error" sx={{ mt: 0.5 }} title={v.errorReason} />
                  )}
                </Box>
              ),
            },
            { key: 'temp', header: 'Temp', render: (v) => <Box sx={strike(v)}><VitalValue vital={v} param="TEMPERATURE_C">{dash(v.temperatureC, ' °C')}</VitalValue></Box> },
            { key: 'pulse', header: 'Pulse', render: (v) => <Box sx={strike(v)}><VitalValue vital={v} param="PULSE_BPM">{dash(v.pulseBpm)}</VitalValue></Box> },
            { key: 'rr', header: 'RR', render: (v) => <Box sx={strike(v)}><VitalValue vital={v} param="RESPIRATORY_RATE">{dash(v.respiratoryRate)}</VitalValue></Box> },
            {
              key: 'bp',
              header: 'BP',
              render: (v) => (
                <Box sx={strike(v)}>
                  <VitalValue vital={v} param={['SYSTOLIC_BP', 'DIASTOLIC_BP']}>
                    {v.systolicBp !== undefined && v.systolicBp !== null ? `${v.systolicBp}/${v.diastolicBp ?? '—'}` : '—'}
                  </VitalValue>
                </Box>
              ),
            },
            {
              key: 'spo2',
              header: 'SpO₂',
              render: (v) => (
                <Box sx={strike(v)}>
                  <VitalValue vital={v} param="SPO2">{dash(v.spo2, '%')}</VitalValue>
                  {v.onOxygen && (
                    <Typography component="span" variant="caption" color="text.secondary">
                      {' '}
                      on O₂
                    </Typography>
                  )}
                </Box>
              ),
            },
            { key: 'pain', header: 'Pain', render: (v) => <Box sx={strike(v)}><VitalValue vital={v} param="PAIN_SCORE">{dash(v.painScore)}</VitalValue></Box> },
            { key: 'glucose', header: 'Glucose', render: (v) => <Box sx={strike(v)}><VitalValue vital={v} param="BLOOD_GLUCOSE">{dash(v.bloodGlucose)}</VitalValue></Box> },
            { key: 'acvpu', header: 'ACVPU', hideOnMobile: true, render: (v) => <Box sx={strike(v)}>{v.consciousness ? humanize(v.consciousness) : '—'}</Box> },
            {
              key: 'notes',
              header: 'Notes',
              hideOnMobile: true,
              render: (v) => (
                <Typography variant="caption" sx={{ display: 'block', maxWidth: 220, wordBreak: 'break-word' }}>
                  {v.enteredInError ? `Error: ${v.errorReason ?? ''}` : v.notes ?? '—'}
                </Typography>
              ),
            },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (v) =>
                canWrite && !v.enteredInError ? (
                  <Button size="small" color="inherit" onClick={() => setInError(v)} sx={{ whiteSpace: 'nowrap' }}>
                    Mark in error
                  </Button>
                ) : null,
            },
          ]}
          emptyDescription={
            <Stack direction="row" spacing={1} alignItems="center" justifyContent="center">
              <Activity size={14} aria-hidden />
              <span>Recorded observations appear here and on the trend chart.</span>
            </Stack>
          }
        />
      </SectionCard>

      <ConfirmDialog
        open={!!inError}
        title="Mark observation as entered in error?"
        description={
          inError
            ? `The set recorded ${formatDateTime(inError.recordedAt)} stays in the record, struck through, and is excluded from trends.`
            : undefined
        }
        requireReason
        reasonLabel="Reason (e.g. recorded against the wrong patient)"
        tone="warning"
        confirmLabel="Mark in error"
        loading={markError.isPending}
        onConfirm={(reason) => inError && markError.mutate({ id: inError.id, reason: reason! })}
        onClose={() => setInError(null)}
      />
    </Stack>
  );
}

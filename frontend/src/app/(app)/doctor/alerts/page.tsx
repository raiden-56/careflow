'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Chip, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { BellRing, CheckCircle2 } from 'lucide-react';
import { AlertSeverity, AlertStatus, AlertType, AlertView, ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { useAlertActions } from '@/components/clinical/alerts/AlertsPanel';
import { FilterChips } from '@/components/clinical/shared';
import { colors } from '@/theme/tokens';

type StatusFilter = 'unresolved' | 'resolved' | 'all';
const STATUS: Record<StatusFilter, AlertStatus[] | undefined> = {
  unresolved: ['OPEN', 'ACKNOWLEDGED'],
  resolved: ['RESOLVED'],
  all: undefined,
};
const TYPES: AlertType[] = ['VITAL_THRESHOLD', 'LAB_CRITICAL', 'ESCALATION', 'MEDICATION', 'ALLERGY', 'OTHER'];

export default function DoctorAlertsPage() {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('ALERT_MANAGE');
  const t = useTableState({ size: 20 });
  const [status, setStatus] = useState<StatusFilter>('unresolved');
  const [severity, setSeverity] = useState<AlertSeverity | ''>('');
  const [type, setType] = useState<AlertType | ''>('');
  const [resolving, setResolving] = useState<AlertView | null>(null);
  const { acknowledge, resolve } = useAlertActions();

  const params = { status: STATUS[status], severity: severity || undefined, type: type || undefined, page: t.page, size: t.size };
  const q = useQuery({ queryKey: ck.myAlerts(params), queryFn: () => clinicalApi.myAlerts(params), placeholderData: keepPreviousData });
  const busy = acknowledge.isPending || resolve.isPending;

  return (
    <>
      <PageHeader
        title="Clinical alerts"
        subtitle="Threshold, critical-result and escalation alerts for patients under your care."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Clinical alerts' }]}
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <FilterChips<StatusFilter>
            ariaLabel="Alert status"
            value={status}
            onChange={(v) => {
              setStatus(v);
              t.setPage(0);
            }}
            options={[
              { value: 'unresolved', label: 'Unresolved' },
              { value: 'resolved', label: 'Resolved' },
              { value: 'all', label: 'All' },
            ]}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <TextField
              select
              label="Severity"
              value={severity}
              onChange={(e) => {
                setSeverity(e.target.value as AlertSeverity | '');
                t.setPage(0);
              }}
              sx={{ maxWidth: { sm: 200 } }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
            >
              <MenuItem value="">All severities</MenuItem>
              <MenuItem value="CRITICAL">Critical</MenuItem>
              <MenuItem value="WARNING">Warning</MenuItem>
              <MenuItem value="INFO">Info</MenuItem>
            </TextField>
            <TextField
              select
              label="Type"
              value={type}
              onChange={(e) => {
                setType(e.target.value as AlertType | '');
                t.setPage(0);
              }}
              sx={{ maxWidth: { sm: 240 } }}
              slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }}
            >
              <MenuItem value="">All types</MenuItem>
              {TYPES.map((x) => (
                <MenuItem key={x} value={x}>
                  {humanize(x)}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
        </Stack>
        <DataTable<AlertView>
          ariaLabel="Clinical alerts"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(a) => a.id}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          emptyTitle={status === 'unresolved' ? 'No unresolved alerts' : 'No alerts match these filters'}
          emptyDescription={status === 'unresolved' ? 'New threshold breaches and critical results for your patients will appear here.' : undefined}
          rowSx={(a) =>
            a.severity === 'CRITICAL' && a.status !== 'RESOLVED'
              ? { bgcolor: colors.dangerSoft, boxShadow: `inset 4px 0 0 ${colors.danger}` }
              : a.status === 'RESOLVED'
                ? { opacity: 0.85 }
                : undefined
          }
          columns={[
            {
              key: 'alert',
              header: 'Alert',
              width: '34%',
              primary: true,
              render: (a) => (
                <Box sx={{ minWidth: { md: 280 }, maxWidth: 520 }}>
                  <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 0.5 }}>
                    <StatusChip value={a.severity} />
                    <Typography variant="caption" color="text.secondary">
                      {humanize(a.alertType)}
                    </Typography>
                  </Stack>
                  <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
                    {a.title}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ wordBreak: 'break-word' }}>
                    {a.message}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'patient',
              header: 'Patient',
              render: (a) =>
                a.patient ? (
                  <Box>
                    <Typography
                      component={Link}
                      href={`/doctor/patients/${a.admissionId}`}
                      variant="body2"
                      fontWeight={600}
                      sx={{ color: 'primary.main', textDecoration: 'none', '&:hover': { textDecoration: 'underline' } }}
                    >
                      {a.patient.patientName}
                    </Typography>
                    <Typography variant="caption" color="text.secondary" component="div">
                      {a.patient.mrn} · {a.patient.location ?? 'Awaiting bed'}
                    </Typography>
                  </Box>
                ) : (
                  <Typography component={Link} href={`/doctor/patients/${a.admissionId}`} variant="body2">
                    Open record
                  </Typography>
                ),
            },
            {
              key: 'observed',
              header: 'Observed / threshold',
              render: (a) =>
                a.observedValue || a.thresholdText ? (
                  <Box>
                    {a.observedValue && (
                      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                        {a.observedValue}
                      </Typography>
                    )}
                    {a.thresholdText && (
                      <Typography variant="caption" color="text.secondary" component="div">
                        Threshold: {a.thresholdText}
                      </Typography>
                    )}
                  </Box>
                ) : (
                  '—'
                ),
            },
            {
              key: 'raised',
              header: 'Raised',
              render: (a) => (
                <Box>
                  <Typography variant="body2" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(a.raisedAt)}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {fromNow(a.raisedAt)}
                    {a.raisedBy ? ` · ${a.raisedBy}` : ''}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'esc',
              header: 'Escalation',
              hideOnMobile: false,
              render: (a) =>
                a.escalationLevel > 0 ? (
                  <Chip
                    size="small"
                    variant="outlined"
                    color={a.status === 'RESOLVED' ? 'default' : 'error'}
                    icon={<BellRing size={13} />}
                    label={`Level ${a.escalationLevel}`}
                    title={a.lastEscalatedAt ? `Last escalated ${formatDateTime(a.lastEscalatedAt)}` : undefined}
                  />
                ) : (
                  <Typography variant="caption" color="text.secondary">
                    Not escalated
                  </Typography>
                ),
            },
            {
              key: 'status',
              header: 'Status',
              render: (a) => (
                <Box>
                  <StatusChip value={a.status} />
                  {a.status === 'ACKNOWLEDGED' && a.acknowledgedBy && (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                      by {a.acknowledgedBy}
                    </Typography>
                  )}
                  {a.status === 'RESOLVED' && (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5, maxWidth: 220, wordBreak: 'break-word' }}>
                      {a.resolvedBy ?? '—'}, {formatDateTime(a.resolvedAt)}
                      {a.resolutionNotes ? `: ${a.resolutionNotes}` : ''}
                    </Typography>
                  )}
                </Box>
              ),
            },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (a) =>
                canManage && a.status !== 'RESOLVED' ? (
                  <Stack direction="row" spacing={1} justifyContent="flex-end">
                    {a.status === 'OPEN' && (
                      <Button size="small" variant="outlined" disabled={busy} onClick={() => acknowledge.mutate(a)}>
                        Acknowledge
                      </Button>
                    )}
                    <Button
                      size="small"
                      variant="contained"
                      color={a.severity === 'CRITICAL' ? 'error' : 'primary'}
                      disabled={busy}
                      startIcon={<CheckCircle2 size={14} />}
                      onClick={() => setResolving(a)}
                    >
                      Resolve
                    </Button>
                  </Stack>
                ) : null,
            },
          ]}
        />
      </Card>
      <Typography variant="caption" color="text.secondary" component="p" sx={{ mt: 3 }}>
        Alerts compare observations and results with hospital-configured thresholds. They support, but do not replace, clinical judgement.
      </Typography>
      <ConfirmDialog
        open={!!resolving}
        title="Resolve alert"
        description={
          resolving && (
            <>
              <strong>{resolving.title}</strong>
              {resolving.patient ? ` — ${resolving.patient.patientName}` : ''}. Document the assessment and action taken.
            </>
          )
        }
        requireReason
        reasonLabel="Resolution notes (action taken)"
        confirmLabel="Resolve alert"
        loading={resolve.isPending}
        onConfirm={(notes) => resolving && resolve.mutate({ alert: resolving, notes: notes! }, { onSuccess: () => setResolving(null) })}
        onClose={() => !resolve.isPending && setResolving(null)}
      />
    </>
  );
}

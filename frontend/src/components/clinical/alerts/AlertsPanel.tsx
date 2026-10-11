'use client';

import { useState } from 'react';
import Link from 'next/link';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Chip, Stack, Typography } from '@mui/material';
import { AlertTriangle, BellRing, CheckCircle2 } from 'lucide-react';
import { AlertView, ck, clinicalApi } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

/** One alert with acknowledge / resolve actions (permission-aware). */
export function AlertCard({
  alert,
  onAcknowledge,
  onResolve,
  busy,
  patientLink,
}: {
  alert: AlertView;
  onAcknowledge?: (a: AlertView) => void;
  onResolve?: (a: AlertView) => void;
  busy?: boolean;
  /** Show the patient and link to their record. */
  patientLink?: string;
}) {
  const critical = alert.severity === 'CRITICAL';
  const resolved = alert.status === 'RESOLVED';
  const accent = resolved ? colors.border : critical ? colors.danger : alert.severity === 'WARNING' ? colors.warning : colors.info;
  return (
    <Box
      sx={{
        border: 1,
        borderColor: 'divider',
        borderLeft: `4px solid ${accent}`,
        borderRadius: 2,
        p: 3,
        bgcolor: !resolved && critical ? colors.dangerSoft : 'background.paper',
        opacity: resolved ? 0.85 : 1,
      }}
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <StatusChip value={alert.severity} />
            <StatusChip value={alert.status} />
            <Chip size="small" variant="outlined" label={humanize(alert.alertType)} />
            {alert.escalationLevel > 0 && !resolved && (
              <Chip size="small" color="error" variant="outlined" icon={<BellRing size={13} />} label={`Escalated L${alert.escalationLevel}`} />
            )}
          </Stack>
          <Typography variant="body2" fontWeight={600} sx={{ mt: 1.5, wordBreak: 'break-word' }}>
            {alert.title}
          </Typography>
          {patientLink && alert.patient && (
            <Typography variant="body2" sx={{ mt: 0.5 }}>
              <Box component={Link} href={patientLink} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                {alert.patient.patientName}
              </Box>{' '}
              <Typography component="span" variant="caption" color="text.secondary">
                {alert.patient.mrn} · {alert.patient.location ?? 'Awaiting bed'}
              </Typography>
            </Typography>
          )}
          <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, wordBreak: 'break-word' }}>
            {alert.message}
          </Typography>
          {(alert.observedValue || alert.thresholdText) && alert.alertType === 'VITAL_THRESHOLD' && (
            <Typography variant="caption" component="div" sx={{ mt: 1 }}>
              Observed <strong>{alert.observedValue}</strong>
              {alert.thresholdText ? ` · threshold ${alert.thresholdText}` : ''}
            </Typography>
          )}
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
            Raised {formatDateTime(alert.raisedAt)} ({fromNow(alert.raisedAt)}) by {alert.raisedBy ?? 'System'}
            {alert.assignedToName ? ` · for ${alert.assignedToName}` : ''}
            {alert.acknowledgedAt ? ` · acknowledged by ${alert.acknowledgedBy ?? '—'} ${formatDateTime(alert.acknowledgedAt)}` : ''}
          </Typography>
          {resolved && (
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
              Resolved by {alert.resolvedBy ?? '—'} {formatDateTime(alert.resolvedAt)}: {alert.resolutionNotes}
            </Typography>
          )}
        </Box>
        {!resolved && (onAcknowledge || onResolve) && (
          <Stack direction="row" spacing={1} sx={{ flexShrink: 0 }}>
            {alert.status === 'OPEN' && onAcknowledge && (
              <Button size="small" variant="outlined" disabled={busy} onClick={() => onAcknowledge(alert)}>
                Acknowledge
              </Button>
            )}
            {onResolve && (
              <Button size="small" variant="contained" color={critical ? 'error' : 'primary'} disabled={busy} onClick={() => onResolve(alert)}>
                Resolve
              </Button>
            )}
          </Stack>
        )}
      </Stack>
    </Box>
  );
}

/** Acknowledge / resolve mutations shared by the alert lists. */
export function useAlertActions(admissionId?: string) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const invalidate = (a: AlertView) => {
    queryClient.invalidateQueries({ queryKey: ['clinical', admissionId ?? a.admissionId] });
    queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    queryClient.invalidateQueries({ queryKey: ['admission', a.admissionId, 'timeline'] });
    queryClient.invalidateQueries({ queryKey: ['notifications'] });
  };
  const acknowledge = useMutation({
    mutationFn: (a: AlertView) => clinicalApi.acknowledgeAlert(a.id),
    onSuccess: (a) => {
      toast.success('Alert acknowledged');
      invalidate(a);
    },
    onError: toast.error,
  });
  const resolve = useMutation({
    mutationFn: ({ alert, notes }: { alert: AlertView; notes: string }) => clinicalApi.resolveAlert(alert.id, notes),
    onSuccess: (a) => {
      toast.success('Alert resolved');
      invalidate(a);
    },
    onError: toast.error,
  });
  return { acknowledge, resolve };
}

/** Alerts for one admission: unresolved first, with history toggle. */
export function AlertsPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('ALERT_MANAGE');
  const [showResolved, setShowResolved] = useState(false);
  const [resolving, setResolving] = useState<AlertView | null>(null);
  const q = useQuery({ queryKey: ck.alerts(admissionId), queryFn: () => clinicalApi.admissionAlerts(admissionId) });
  const { acknowledge, resolve } = useAlertActions(admissionId);

  if (q.isLoading) return <TableSkeleton rows={3} columns={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const all = q.data ?? [];
  const unresolved = all.filter((a) => a.status !== 'RESOLVED');
  const resolved = all.filter((a) => a.status === 'RESOLVED');
  const list = showResolved ? all : unresolved;
  return (
    <Box>
      <Stack direction="row" spacing={1} sx={{ mb: 3 }} flexWrap="wrap" useFlexGap>
        <Chip
          label={`Unresolved (${unresolved.length})`}
          clickable
          color={!showResolved ? 'primary' : 'default'}
          variant={!showResolved ? 'filled' : 'outlined'}
          onClick={() => setShowResolved(false)}
        />
        <Chip
          label={`All (${all.length})`}
          clickable
          color={showResolved ? 'primary' : 'default'}
          variant={showResolved ? 'filled' : 'outlined'}
          onClick={() => setShowResolved(true)}
        />
      </Stack>
      {list.length === 0 ? (
        <EmptyState
          icon={unresolved.length === 0 ? CheckCircle2 : AlertTriangle}
          title={showResolved ? 'No alerts recorded' : 'No unresolved alerts'}
          description={!showResolved && resolved.length > 0 ? `${resolved.length} resolved alert(s) in history.` : undefined}
          compact
        />
      ) : (
        <Stack spacing={2}>
          {list.map((a) => (
            <AlertCard
              key={a.id}
              alert={a}
              busy={acknowledge.isPending || resolve.isPending}
              onAcknowledge={canManage ? (x) => acknowledge.mutate(x) : undefined}
              onResolve={canManage ? setResolving : undefined}
            />
          ))}
        </Stack>
      )}
      <Alert severity="info" variant="outlined" sx={{ mt: 4 }}>
        Threshold alerts compare observations with hospital-configured ranges. They support, but do not replace, clinical judgement.
      </Alert>
      <ConfirmDialog
        open={!!resolving}
        title="Resolve alert"
        description={resolving?.title}
        requireReason
        reasonLabel="Resolution notes (action taken)"
        confirmLabel="Resolve"
        loading={resolve.isPending}
        onConfirm={(notes) => resolving && resolve.mutate({ alert: resolving, notes: notes! }, { onSuccess: () => setResolving(null) })}
        onClose={() => setResolving(null)}
      />
    </Box>
  );
}

'use client';

import { ReactNode, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Box, Button, Stack, TextField, Typography } from '@mui/material';
import { BadgeCheck, CirclePause, CirclePlay, CircleStop, Hourglass, Pencil, Pill, Plus, Send, ShieldAlert, TriangleAlert } from 'lucide-react';
import { ck, medicationApi, OrderStatus, OrderView, orderSig } from '@/lib/api/clinical';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { FilterChips, FlagChip, MetaLine, RecordCard, useClinicalInvalidate } from '../shared';
import { PrescribeDialog } from './PrescribeDialog';

export type OrderActionKind = 'hold' | 'resume' | 'discontinue' | 'release';

/** Status / safety flags for an order (high-alert, controlled, allergy override, discharge, released, verification). */
export function OrderFlags({ order, compact }: { order: OrderView; compact?: boolean }) {
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {order.highAlert && <FlagChip tone="danger" icon={<TriangleAlert size={12} />} label="High-alert" />}
      {order.controlled && <FlagChip tone="info" label="Controlled" />}
      {order.allergyOverrideReason && (
        <FlagChip tone="danger" icon={<ShieldAlert size={12} />} label="Allergy override" title={`Override reason: ${order.allergyOverrideReason}`} />
      )}
      {order.dischargeMedication && <FlagChip tone="primary" label="Discharge medication" />}
      {order.releasedToPatient && <FlagChip tone="success" icon={<Send size={12} />} label="Released to patient" />}
      {!compact &&
        (order.verifiedAt ? (
          <FlagChip
            tone="success"
            icon={<BadgeCheck size={12} />}
            label="Pharmacist verified"
            title={`Verified by ${order.verifiedBy ?? 'pharmacist'} ${formatDateTime(order.verifiedAt)}`}
          />
        ) : (
          order.status !== 'DISCONTINUED' && <FlagChip tone="neutral" icon={<Hourglass size={12} />} label="Awaiting pharmacist verification" />
        ))}
    </Stack>
  );
}

/**
 * Hold / resume / discontinue / release-to-patient confirmation dialogs for medication orders.
 * Returns `request(kind, order)` to open one and the `dialogs` element to render once.
 */
export function useOrderActions() {
  const toast = useToast();
  const invalidate = useClinicalInvalidate();
  const [pending, setPending] = useState<{ kind: OrderActionKind; order: OrderView } | null>(null);
  const [instructions, setInstructions] = useState('');
  const [error, setError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: ({ kind, order, reason }: { kind: OrderActionKind; order: OrderView; reason?: string }) => {
      switch (kind) {
        case 'hold':
          return medicationApi.hold(order.id, reason!);
        case 'resume':
          return medicationApi.resume(order.id);
        case 'discontinue':
          return medicationApi.discontinue(order.id, reason!);
        case 'release':
          return medicationApi.release(order.id, reason);
      }
    },
    onSuccess: (o, v) => {
      const verb = { hold: 'put on hold', resume: 'resumed', discontinue: 'discontinued', release: 'released to the patient portal' }[v.kind];
      toast.success(`${o.medicationName} ${verb}`);
      invalidate(o.admissionId);
      setPending(null);
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const request = (kind: OrderActionKind, order: OrderView) => {
    setError(null);
    setInstructions(order.patientInstructions ?? '');
    setPending({ kind, order });
  };
  const close = () => !mutation.isPending && setPending(null);
  const o = pending?.order;
  const title = o ? `${o.medicationName} — ${orderSig(o)}` : '';

  const dialogs = (
    <>
      <ConfirmDialog
        open={pending?.kind === 'hold'}
        title="Put order on hold?"
        description={<>Scheduled doses of <strong>{title}</strong> will be held until the order is resumed. Nurses see the hold reason on the MAR.</>}
        requireReason
        reasonLabel="Reason for hold"
        tone="warning"
        confirmLabel="Hold order"
        loading={mutation.isPending}
        error={error}
        onConfirm={(reason) => o && mutation.mutate({ kind: 'hold', order: o, reason })}
        onClose={close}
      />
      <ConfirmDialog
        open={pending?.kind === 'resume'}
        title="Resume order?"
        description={<>Doses of <strong>{title}</strong> will be scheduled again from the next administration time.</>}
        confirmLabel="Resume order"
        loading={mutation.isPending}
        error={error}
        onConfirm={() => o && mutation.mutate({ kind: 'resume', order: o })}
        onClose={close}
      />
      <ConfirmDialog
        open={pending?.kind === 'discontinue'}
        title="Discontinue order?"
        description={<>Remaining scheduled doses of <strong>{title}</strong> are cancelled. The order stays in the record as discontinued.</>}
        requireReason
        reasonLabel="Reason for discontinuing"
        tone="error"
        confirmLabel="Discontinue"
        loading={mutation.isPending}
        error={error}
        onConfirm={(reason) => o && mutation.mutate({ kind: 'discontinue', order: o, reason })}
        onClose={close}
      />
      <ConfirmDialog
        open={pending?.kind === 'release'}
        title="Release to patient portal?"
        description={<>The patient will see <strong>{o?.medicationName}</strong> with the plain-language instructions below.</>}
        confirmLabel="Release"
        loading={mutation.isPending}
        error={error}
        onConfirm={() => {
          if (!o) return;
          if (instructions.trim().length < 3) {
            setError('Write plain-language instructions for the patient before releasing.');
            return;
          }
          mutation.mutate({ kind: 'release', order: o, reason: instructions.trim() });
        }}
        onClose={close}
      >
        <TextField
          label="Patient instructions"
          value={instructions}
          onChange={(e) => setInstructions(e.target.value)}
          multiline
          minRows={3}
          required
          autoFocus
          helperText="Plain language, e.g. what it is for, when to take it, what to watch for."
        />
      </ConfirmDialog>
    </>
  );
  return { request, dialogs, busy: mutation.isPending };
}

/** Action buttons for one order, gated by permission and status. */
export function OrderActionButtons({
  order,
  onAction,
  onModify,
  size = 'small',
}: {
  order: OrderView;
  onAction: (kind: OrderActionKind, order: OrderView) => void;
  onModify?: (order: OrderView) => void;
  size?: 'small' | 'medium';
}) {
  const { hasPermission } = useAuth();
  const canPrescribe = hasPermission('PRESCRIBE');
  const canRelease = hasPermission('RELEASE_TO_PATIENT');
  const live = order.status === 'ACTIVE' || order.status === 'ON_HOLD';
  const buttons: ReactNode[] = [];
  if (canPrescribe && live && onModify)
    buttons.push(
      <Button key="modify" size={size} variant="outlined" startIcon={<Pencil size={14} />} onClick={() => onModify(order)}>
        Modify
      </Button>,
    );
  if (canPrescribe && order.status === 'ACTIVE')
    buttons.push(
      <Button key="hold" size={size} variant="outlined" color="warning" startIcon={<CirclePause size={14} />} onClick={() => onAction('hold', order)}>
        Hold
      </Button>,
    );
  if (canPrescribe && order.status === 'ON_HOLD')
    buttons.push(
      <Button key="resume" size={size} variant="outlined" startIcon={<CirclePlay size={14} />} onClick={() => onAction('resume', order)}>
        Resume
      </Button>,
    );
  if (canPrescribe && live)
    buttons.push(
      <Button key="dc" size={size} variant="outlined" color="error" startIcon={<CircleStop size={14} />} onClick={() => onAction('discontinue', order)}>
        Discontinue
      </Button>,
    );
  if (canRelease && !order.releasedToPatient && order.status !== 'DISCONTINUED')
    buttons.push(
      <Button key="release" size={size} startIcon={<Send size={14} />} onClick={() => onAction('release', order)}>
        Release to patient
      </Button>,
    );
  if (!buttons.length) return null;
  return (
    <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
      {buttons}
    </Stack>
  );
}

const FILTERS: { value: 'current' | 'all'; label: string; status?: OrderStatus[] }[] = [
  { value: 'current', label: 'Active & on hold', status: ['ACTIVE', 'ON_HOLD'] },
  { value: 'all', label: 'All orders' },
];

/** Medication orders for one admission with prescribing actions. */
export function OrdersPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const [filter, setFilter] = useState<'current' | 'all'>('current');
  const [prescribeOpen, setPrescribeOpen] = useState(false);
  const [modifying, setModifying] = useState<OrderView | undefined>();
  const status = FILTERS.find((f) => f.value === filter)?.status;
  const params = { status };
  const q = useQuery({ queryKey: ck.orders(admissionId, params), queryFn: () => medicationApi.orders(admissionId, params) });
  const actions = useOrderActions();

  const openPrescribe = (o?: OrderView) => {
    setModifying(o);
    setPrescribeOpen(true);
  };

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ mb: 3 }}>
        <FilterChips ariaLabel="Order status filter" options={FILTERS} value={filter} onChange={setFilter} />
        {hasPermission('PRESCRIBE') && (
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => openPrescribe()} sx={{ alignSelf: { xs: 'flex-start', sm: 'auto' } }}>
            Prescribe
          </Button>
        )}
      </Stack>
      {q.isLoading ? (
        <TableSkeleton rows={4} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState
          icon={Pill}
          title={filter === 'current' ? 'No active medication orders' : 'No medication orders yet'}
          description={filter === 'current' ? 'Discontinued and completed orders are listed under All orders.' : undefined}
          compact
        />
      ) : (
        <Stack spacing={2} sx={{ opacity: q.isFetching ? 0.7 : 1 }}>
          {q.data.map((o) => (
            <OrderCard key={o.id} order={o} actions={<OrderActionButtons order={o} onAction={actions.request} onModify={openPrescribe} />} />
          ))}
        </Stack>
      )}
      {actions.dialogs}
      <PrescribeDialog open={prescribeOpen} onClose={() => setPrescribeOpen(false)} admissionId={admissionId} modifyOrder={modifying} />
    </Box>
  );
}

/** Readable card for one medication order. */
export function OrderCard({ order: o, actions, header }: { order: OrderView; actions?: ReactNode; header?: ReactNode }) {
  const inactive = o.status === 'DISCONTINUED' || o.status === 'COMPLETED';
  const accent = o.status === 'ON_HOLD' ? colors.warning : o.highAlert && !inactive ? colors.danger : undefined;
  return (
    <RecordCard accent={accent} muted={inactive}>
      {header}
      <Stack direction={{ xs: 'column', md: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ md: 'flex-start' }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body1" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
              {o.medicationName}
            </Typography>
            <StatusChip value={o.status} />
          </Stack>
          <Typography variant="body2" sx={{ mt: 0.5, fontWeight: 500 }}>
            {o.dose} {o.doseUnit} · {humanize(o.route)} · {o.frequency}
            {o.scheduleTimes ? (
              <Typography component="span" variant="body2" color="text.secondary">
                {' '}
                at {o.scheduleTimes.split(',').join(', ')}
              </Typography>
            ) : null}
          </Typography>
          {o.indication && (
            <Typography variant="body2" color="text.secondary">
              For: {o.indication}
            </Typography>
          )}
          {o.instructions && (
            <Typography variant="body2" color="text.secondary">
              Instructions: {o.instructions}
            </Typography>
          )}
        </Box>
        {(o.status === 'ACTIVE' || o.status === 'ON_HOLD') && (
          <Box sx={{ textAlign: { md: 'right' }, flexShrink: 0 }}>
            {o.nextDoseAt && o.status === 'ACTIVE' && (
              <Typography variant="body2">
                Next dose <strong>{formatDateTime(o.nextDoseAt, 'DD MMM, HH:mm')}</strong>
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary">
              Last given {o.lastAdministeredAt ? `${formatDateTime(o.lastAdministeredAt, 'DD MMM, HH:mm')} (${fromNow(o.lastAdministeredAt)})` : '— not yet'}
            </Typography>
          </Box>
        )}
      </Stack>
      <Box sx={{ mt: 1.5 }}>
        <OrderFlags order={o} />
      </Box>
      {o.allergyOverrideReason && (
        <Alert severity="error" variant="outlined" icon={<ShieldAlert size={16} />} sx={{ mt: 2, py: 0 }}>
          <Typography variant="caption" component="div">
            <strong>Allergy override documented:</strong> {o.allergyOverrideReason}
          </Typography>
        </Alert>
      )}
      {o.status === 'ON_HOLD' && o.holdReason && (
        <Alert severity="warning" sx={{ mt: 2, py: 0 }}>
          <Typography variant="caption" component="div">
            On hold since {formatDateTime(o.heldAt)}: {o.holdReason}
          </Typography>
        </Alert>
      )}
      {o.status === 'DISCONTINUED' && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1.5 }}>
          Discontinued {formatDateTime(o.discontinuedAt)} by {o.discontinuedBy ?? '—'}
          {o.discontinueReason ? `: ${o.discontinueReason}` : ''}
        </Typography>
      )}
      <MetaLine
        items={[
          ['Start', formatDateTime(o.startAt)],
          o.endAt ? ['Ends', formatDateTime(o.endAt)] : ['Duration', 'Until stopped'],
          o.durationDays ? ['Course', `${o.durationDays} day(s)`] : null,
          ['Prescribed', `${o.prescribedBy ?? '—'} · ${formatDateTime(o.prescribedAt)}`],
          o.verifiedAt ? ['Verified', `${o.verifiedBy ?? '—'} · ${formatDateTime(o.verifiedAt)}`] : null,
          o.replacesOrderId ? ['Replaces', 'an earlier order (modified)'] : null,
        ]}
      />
      {o.patientInstructions && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
          Patient instructions: {o.patientInstructions}
        </Typography>
      )}
      {actions && <Box sx={{ mt: 2.5 }}>{actions}</Box>}
    </RecordCard>
  );
}

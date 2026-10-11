'use client';

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Divider, Stack, Typography } from '@mui/material';
import { Pill, PlusCircle, ShieldAlert } from 'lucide-react';
import { ck, DoseView, medicationApi, OrderStatus, OrderView, orderSig } from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDate, formatDateTime, fromNow, inHospitalTz, nowInHospitalTz } from '@/lib/format';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { DataTable } from '@/components/ui/DataTable';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';
import { AdministerDialog, ExceptionDialog, PrnDoseDialog } from './DoseDialogs';
import { DoseCard } from './DoseCard';

const ORDER_STATUSES: OrderStatus[] = ['ACTIVE', 'ON_HOLD'];

/** One prescription line (read-only for nurses) with safety flags, hold reason and next/last dose. */
function OrderRow({ order, onPrn }: { order: OrderView; onPrn?: (o: OrderView) => void }) {
  const held = order.status === 'ON_HOLD';
  const prn = order.frequency === 'PRN';
  return (
    <Box
      sx={{
        border: 1,
        borderColor: 'divider',
        borderLeft: `4px solid ${held ? colors.warning : order.highAlert ? colors.danger : colors.primary}`,
        borderRadius: 2,
        p: 3,
        bgcolor: held ? colors.warningSoft : 'background.paper',
      }}
    >
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
              {order.medicationName}
            </Typography>
            <StatusChip value={order.status} />
            {prn && <Chip size="small" variant="outlined" label="PRN" />}
            {order.highAlert && <Chip size="small" color="error" icon={<ShieldAlert size={13} />} label="High alert" />}
            {order.controlled && <Chip size="small" color="warning" variant="outlined" label="Controlled" />}
          </Stack>
          <Typography variant="body2" sx={{ mt: 0.5 }}>
            {orderSig(order)}
            {order.scheduleTimes ? ` (${order.scheduleTimes.split(',').join(', ')})` : ''}
          </Typography>
          {order.indication && (
            <Typography variant="caption" color="text.secondary" component="div">
              For: {order.indication}
            </Typography>
          )}
          {order.instructions && (
            <Typography variant="caption" component="div" sx={{ wordBreak: 'break-word' }}>
              Instructions: {order.instructions}
            </Typography>
          )}
          {held && (
            <Typography variant="caption" color="warning.main" component="div" sx={{ mt: 0.5, fontWeight: 600 }}>
              On hold{order.heldAt ? ` since ${formatDateTime(order.heldAt)}` : ''}: {order.holdReason ?? 'no reason recorded'}. Do not give.
            </Typography>
          )}
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
            {order.prescribedBy ? `Prescribed by ${order.prescribedBy} · ` : ''}
            {formatDate(order.startAt)}
            {order.endAt ? ` to ${formatDate(order.endAt)}` : ''}
            {order.nextDoseAt && !held ? ` · next ${formatDateTime(order.nextDoseAt)}` : ''}
            {order.lastAdministeredAt ? ` · last given ${fromNow(order.lastAdministeredAt)}` : ''}
          </Typography>
        </Box>
        {prn && !held && onPrn && (
          <Button size="small" variant="outlined" startIcon={<PlusCircle size={15} />} onClick={() => onPrn(order)} sx={{ flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'center' } }}>
            Record PRN dose
          </Button>
        )}
      </Stack>
    </Box>
  );
}

/** Admission-level Medications / MAR tab: prescriptions, 48 h dose schedule, PRN and recent records. */
export function MarPanel({ admissionId }: { admissionId: string }) {
  const { hasPermission } = useAuth();
  const canAdminister = hasPermission('MED_ADMINISTER');
  const [range] = useState(() => {
    const now = nowInHospitalTz().startOf('hour');
    return { from: now.subtract(24, 'hour').toISOString(), to: now.add(25, 'hour').toISOString() };
  });
  const [give, setGive] = useState<DoseView | null>(null);
  const [exception, setException] = useState<DoseView | null>(null);
  const [prn, setPrn] = useState<OrderView | null>(null);
  const [historyPage, setHistoryPage] = useState(0);
  const [historySize, setHistorySize] = useState(10);

  const ordersParams = { status: ORDER_STATUSES };
  const orders = useQuery({ queryKey: ck.orders(admissionId, ordersParams), queryFn: () => medicationApi.orders(admissionId, ordersParams) });
  const mar = useQuery({ queryKey: ck.mar(admissionId, range), queryFn: () => medicationApi.admissionMar(admissionId, range) });
  const historyParams = { admissionId, page: historyPage, size: historySize };
  const history = useQuery({ queryKey: ck.marHistory(historyParams), queryFn: () => medicationApi.history(historyParams) });
  // The hold policy is a hospital setting exposed on the rounds view.
  const policy = useQuery({ queryKey: ck.rounds({ hours: 4 }), queryFn: () => medicationApi.rounds({ hours: 4 }), enabled: canAdminister, staleTime: 60_000 });
  const allowHold = !!policy.data?.nurseMayHoldDose;

  // Attach the patient's context (allergies, name) from the order list when dose rows lack it.
  const patient = orders.data?.find((o) => o.patient)?.patient;
  const days = useMemo(() => {
    const groups = new Map<string, DoseView[]>();
    for (const d of mar.data ?? []) {
      const key = inHospitalTz(d.scheduledAt).format('YYYY-MM-DD');
      const list = groups.get(key) ?? [];
      list.push(d.patient ? d : { ...d, patient });
      groups.set(key, list);
    }
    return [...groups.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([day, doses]) => ({ day, doses: doses.sort((a, b) => a.scheduledAt.localeCompare(b.scheduledAt)) }));
  }, [mar.data, patient]);

  const today = nowInHospitalTz().format('YYYY-MM-DD');
  const dayLabel = (day: string) => {
    const d = { format: (f: string) => formatDate(day, f) };
    if (day === today) return `Today · ${d.format('DD MMM')}`;
    if (day === nowInHospitalTz().subtract(1, 'day').format('YYYY-MM-DD')) return `Yesterday · ${d.format('DD MMM')}`;
    if (day === nowInHospitalTz().add(1, 'day').format('YYYY-MM-DD')) return `Tomorrow · ${d.format('DD MMM')}`;
    return formatDate(day);
  };

  const all = orders.data ?? [];
  const pendingCount = (mar.data ?? []).filter((d) => d.displayStatus === 'DUE' || d.displayStatus === 'OVERDUE').length;

  return (
    <Stack spacing={5}>
      <SectionCard
        title="Current prescriptions"
        subtitle="Active and held orders. Prescriptions are changed by the prescriber; nurses record administration only."
      >
        {orders.isLoading ? (
          <TableSkeleton rows={3} columns={3} />
        ) : orders.error ? (
          <ErrorState error={orders.error} onRetry={() => orders.refetch()} />
        ) : all.length === 0 ? (
          <EmptyState icon={Pill} title="No active prescriptions" compact />
        ) : (
          <Stack spacing={2}>
            {all
              .slice()
              .sort((a, b) => Number(a.frequency === 'PRN') - Number(b.frequency === 'PRN') || a.medicationName.localeCompare(b.medicationName))
              .map((o) => (
                <OrderRow key={o.id} order={o} onPrn={canAdminister && o.status === 'ACTIVE' ? setPrn : undefined} />
              ))}
          </Stack>
        )}
      </SectionCard>

      <SectionCard
        title="Dose schedule"
        subtitle={`Previous 24 hours and next 24 hours${pendingCount ? ` · ${pendingCount} due or overdue now` : ''}`}
      >
        {mar.isLoading ? (
          <TableSkeleton rows={4} columns={3} />
        ) : mar.error ? (
          <ErrorState error={mar.error} onRetry={() => mar.refetch()} />
        ) : days.length === 0 ? (
          <EmptyState title="No scheduled doses in this window" description="PRN medicines are recorded from the prescription list above." compact />
        ) : (
          <Stack spacing={4} divider={<Divider flexItem />}>
            {days.map(({ day, doses }) => (
              <Box key={day}>
                <Typography variant="overline" color="text.secondary" component="h3" sx={{ display: 'block', mb: 2 }}>
                  {dayLabel(day)}
                </Typography>
                <Stack spacing={2}>
                  {doses.map((d) => (
                    <DoseCard key={d.id} dose={d} onGive={canAdminister ? setGive : undefined} onException={canAdminister ? setException : undefined} />
                  ))}
                </Stack>
              </Box>
            ))}
          </Stack>
        )}
      </SectionCard>

      <SectionCard title="Administration record" subtitle="Recorded doses and exceptions, newest first." flush>
        <DataTable<DoseView>
          rows={history.data?.content}
          loading={history.isFetching}
          error={history.error}
          onRetry={() => history.refetch()}
          rowKey={(d) => d.id}
          page={historyPage}
          pageSize={historySize}
          total={history.data?.totalElements}
          onPageChange={setHistoryPage}
          onPageSizeChange={(s) => {
            setHistorySize(s);
            setHistoryPage(0);
          }}
          pageSizeOptions={[10, 20, 50]}
          dense
          ariaLabel="Administration record"
          emptyTitle="Nothing recorded yet"
          columns={[
            {
              key: 'med',
              header: 'Medication',
              primary: true,
              render: (d) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {d.medicationName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {d.doseGiven ?? (d.dose ? `${d.dose} ${d.doseUnit ?? ''}` : '')} {d.routeGiven ?? d.route ?? ''}
                  </Typography>
                </Box>
              ),
            },
            { key: 'scheduled', header: 'Scheduled', render: (d) => formatDateTime(d.scheduledAt) },
            { key: 'status', header: 'Status', render: (d) => <StatusChip value={d.status} /> },
            { key: 'given', header: 'Given / recorded', render: (d) => formatDateTime(d.administeredAt ?? d.recordedAt) },
            { key: 'by', header: 'By', render: (d) => (d.recordedBy ?? '—') + (d.witness ? ` (witness ${d.witness})` : '') },
            { key: 'reason', header: 'Reason / notes', hideOnMobile: false, render: (d) => d.reason ?? d.notes ?? '—' },
          ]}
        />
      </SectionCard>

      <AdministerDialog dose={give} onClose={() => setGive(null)} />
      <ExceptionDialog dose={exception} allowHold={allowHold} onClose={() => setException(null)} />
      <PrnDoseDialog order={prn} onClose={() => setPrn(null)} />
    </Stack>
  );
}

'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Grid, MenuItem, Stack, TextField, ToggleButton, ToggleButtonGroup, Typography, useMediaQuery, useTheme } from '@mui/material';
import { AlarmClock, CalendarClock, CheckCircle2, ClipboardList, Clock, PlusCircle, RefreshCw, ShieldAlert } from 'lucide-react';
import { ck, DoseView, medicationApi, OrderView } from '@/lib/api/clinical';
import { useAuth, useMe } from '@/lib/auth/AuthProvider';
import { formatDateTime, formatTime, fromNow, inHospitalTz, nowInHospitalTz } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { KpiCard } from '@/components/ui/KpiCard';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { AdministerDialog, ExceptionDialog, PrnDoseDialog } from '@/components/clinical/mar/DoseDialogs';
import { DoseCard } from '@/components/clinical/mar/DoseCard';
import { colors } from '@/theme/tokens';

const HOURS = [4, 8, 12] as const;
const patientHref = (admissionId: string) => `/nurse/patients/${admissionId}?tab=medications`;

function PrnRow({ order, onRecord }: { order: OrderView; onRecord?: (o: OrderView) => void }) {
  return (
    <Box sx={{ border: 1, borderColor: 'divider', borderRadius: 2, p: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }}>
        <Box sx={{ minWidth: 0 }}>
          {order.patient && (
            <Typography variant="body2" sx={{ mb: 0.5 }}>
              <Box component={Link} href={patientHref(order.admissionId)} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                {order.patient.patientName}
              </Box>{' '}
              <Typography component="span" variant="caption" color="text.secondary">
                {order.patient.location ?? 'Awaiting bed'}
              </Typography>
            </Typography>
          )}
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
              {order.medicationName}
            </Typography>
            {order.highAlert && <Chip size="small" color="error" icon={<ShieldAlert size={13} />} label="High alert" />}
            {order.controlled && <Chip size="small" color="warning" variant="outlined" label="Controlled" />}
          </Stack>
          <Typography variant="caption" color="text.secondary" component="div">
            {order.dose} {order.doseUnit} · {order.route} · as needed{order.indication ? ` for ${order.indication}` : ''}
          </Typography>
          <Typography variant="caption" color="text.secondary" component="div">
            Last given: {order.lastAdministeredAt ? `${formatDateTime(order.lastAdministeredAt)} (${fromNow(order.lastAdministeredAt)})` : 'not given yet'}
          </Typography>
        </Box>
        {onRecord && (
          <Button size="small" variant="outlined" startIcon={<PlusCircle size={15} />} onClick={() => onRecord(order)} sx={{ flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'center' } }}>
            Record PRN dose
          </Button>
        )}
      </Stack>
    </Box>
  );
}

export default function MedicationRoundsPage() {
  const me = useMe();
  const { hasPermission } = useAuth();
  const canAdminister = hasPermission('MED_ADMINISTER');
  const wards = me.staff?.wards ?? [];
  const [wardId, setWardId] = useState('');
  const [hours, setHours] = useState<number>(4);
  const theme = useTheme();
  const compact = useMediaQuery(theme.breakpoints.down('sm'));
  const [give, setGive] = useState<DoseView | null>(null);
  const [exception, setException] = useState<DoseView | null>(null);
  const [prn, setPrn] = useState<OrderView | null>(null);

  const params = { hours, wardId: wardId || undefined };
  const q = useQuery({
    queryKey: ck.rounds(params),
    queryFn: () => medicationApi.rounds(params),
    placeholderData: keepPreviousData,
    refetchInterval: 60_000,
  });

  const sections = useMemo(() => {
    const doses = (q.data?.groups ?? []).flatMap((g) => g.doses);
    const byTime = (a: DoseView, b: DoseView) => a.scheduledAt.localeCompare(b.scheduledAt);
    const overdue = doses.filter((d) => d.displayStatus === 'OVERDUE').sort(byTime);
    const due = doses.filter((d) => d.displayStatus === 'DUE' || d.displayStatus === 'DELAYED').sort(byTime);
    const upcoming = new Map<string, DoseView[]>();
    for (const g of q.data?.groups ?? []) {
      const list = g.doses.filter((d) => d.displayStatus === 'SCHEDULED');
      if (list.length) upcoming.set(g.slot, [...(upcoming.get(g.slot) ?? []), ...list].sort(byTime));
    }
    return { overdue, due, upcoming: [...upcoming.entries()].sort(([a], [b]) => a.localeCompare(b)) };
  }, [q.data]);

  if (q.isLoading) return <PageSkeleton />;
  const d = q.data;
  const today = nowInHospitalTz().format('YYYY-MM-DD');
  const slotLabel = (slot: string) => (inHospitalTz(slot).format('YYYY-MM-DD') === today ? formatTime(slot) : formatDateTime(slot, 'DD MMM, HH:mm'));
  const nothing = d && sections.overdue.length === 0 && sections.due.length === 0 && sections.upcoming.length === 0;
  const doseActions = { onGive: canAdminister ? setGive : undefined, onException: canAdminister ? setException : undefined };

  return (
    <>
      <PageHeader
        title="Medication rounds"
        subtitle={
          d
            ? `Doses for your patients over the next ${hours} hours. Due window ${d.dueWindowMinutes} min; a dose becomes overdue ${d.graceMinutes} min after its time.`
            : 'Doses for your patients.'
        }
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Medication rounds' }]}
        actions={
          <>
            <Button component={Link} href="/nurse/exceptions" variant="outlined" startIcon={<AlarmClock size={16} />}>
              Exception queue
            </Button>
            <Button variant="text" color="inherit" startIcon={<RefreshCw size={16} />} onClick={() => q.refetch()} disabled={q.isFetching}>
              Refresh
            </Button>
          </>
        }
      />

      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ mb: 5 }} alignItems={{ sm: 'center' }}>
        <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} sx={{ maxWidth: { sm: 260 } }} slotProps={{ select: { displayEmpty: true }, inputLabel: { shrink: true } }} size="small">
          <MenuItem value="">All my wards</MenuItem>
          {wards.map((w) => (
            <MenuItem key={w.id} value={w.id}>
              {w.name}
            </MenuItem>
          ))}
        </TextField>
        <ToggleButtonGroup
          exclusive
          size="small"
          value={hours}
          onChange={(_, v) => v && setHours(v)}
          aria-label="Hours ahead"
        >
          {HOURS.map((h) => (
            <ToggleButton key={h} value={h} sx={{ px: 3, whiteSpace: 'nowrap' }}>
              Next {h} h
            </ToggleButton>
          ))}
        </ToggleButtonGroup>
        {d && (
          <Typography variant="caption" color="text.secondary">
            Updated {formatTime(d.generatedAt)}
          </Typography>
        )}
      </Stack>

      {q.error && !d ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : (
        d && (
          <Stack spacing={5}>
            <Grid container spacing={3}>
              <Grid size={{ xs: 4 }}>
                <KpiCard label="Overdue" value={d.overdue} tone={d.overdue ? 'error' : 'neutral'} icon={compact ? undefined : AlarmClock} />
              </Grid>
              <Grid size={{ xs: 4 }}>
                <KpiCard label="Due now" value={d.due} tone={d.due ? 'info' : 'neutral'} icon={compact ? undefined : Clock} />
              </Grid>
              <Grid size={{ xs: 4 }}>
                <KpiCard label="Upcoming" value={d.upcoming} tone="neutral" icon={compact ? undefined : CalendarClock} />
              </Grid>
            </Grid>

            {nothing && (
              <SectionCard>
                <EmptyState
                  icon={CheckCircle2}
                  title="No doses due in this window"
                  description={`Nothing is due or overdue for your patients in the next ${hours} hours.`}
                />
              </SectionCard>
            )}

            {sections.overdue.length > 0 && (
              <SectionCard
                title={
                  <Box component="span" sx={{ color: colors.danger }}>
                    Overdue ({sections.overdue.length})
                  </Box>
                }
                subtitle="Give now or record why the dose was delayed, refused, missed or held."
                sx={{ borderColor: colors.danger }}
              >
                <Stack spacing={2}>
                  {sections.overdue.map((x) => (
                    <DoseCard key={x.id} dose={x} patientHref={patientHref(x.admissionId)} showDate {...doseActions} />
                  ))}
                </Stack>
              </SectionCard>
            )}

            {sections.due.length > 0 && (
              <SectionCard title={`Due now (${sections.due.length})`} subtitle="Within the due window, including doses recorded as delayed.">
                <Stack spacing={2}>
                  {sections.due.map((x) => (
                    <DoseCard key={x.id} dose={x} patientHref={patientHref(x.admissionId)} {...doseActions} />
                  ))}
                </Stack>
              </SectionCard>
            )}

            {sections.upcoming.length > 0 && (
              <SectionCard title="Upcoming" subtitle="Grouped by round time.">
                <Stack spacing={4}>
                  {sections.upcoming.map(([slot, doses]) => (
                    <Box key={slot}>
                      <Typography variant="overline" color="text.secondary" component="h3" sx={{ display: 'block', mb: 1.5 }}>
                        {slotLabel(slot)} round · {doses.length} dose{doses.length === 1 ? '' : 's'}
                      </Typography>
                      <Stack spacing={2}>
                        {doses.map((x) => (
                          <DoseCard key={x.id} dose={x} patientHref={patientHref(x.admissionId)} {...doseActions} />
                        ))}
                      </Stack>
                    </Box>
                  ))}
                </Stack>
              </SectionCard>
            )}

            <SectionCard title={`As-needed (PRN) medicines (${d.prnOrders.length})`} subtitle="Record each PRN dose with its indication.">
              {d.prnOrders.length === 0 ? (
                <EmptyState icon={ClipboardList} title="No active PRN orders" compact />
              ) : (
                <Stack spacing={2}>
                  {d.prnOrders.map((o) => (
                    <PrnRow key={o.id} order={o} onRecord={canAdminister && o.status === 'ACTIVE' ? setPrn : undefined} />
                  ))}
                </Stack>
              )}
            </SectionCard>
          </Stack>
        )
      )}

      <AdministerDialog dose={give} onClose={() => setGive(null)} />
      <ExceptionDialog dose={exception} allowHold={!!d?.nurseMayHoldDose} onClose={() => setException(null)} />
      <PrnDoseDialog order={prn} onClose={() => setPrn(null)} />
    </>
  );
}

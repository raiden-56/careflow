'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Box, Button, Card, Stack, TablePagination, TextField, MenuItem, Typography } from '@mui/material';
import { ExternalLink, Pill, ShieldAlert } from 'lucide-react';
import { ck, medicationApi, OrderStatus, OrderView, PatientRef } from '@/lib/api/clinical';
import { useTableState } from '@/lib/hooks';
import { PageHeader } from '@/components/ui/PageHeader';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { OrderActionButtons, OrderCard, useOrderActions } from '@/components/clinical/orders/OrdersPanel';
import { PrescribeDialog } from '@/components/clinical/orders/PrescribeDialog';
import { FilterChips } from '@/components/clinical/shared';
import { colors } from '@/theme/tokens';

type Filter = 'current' | 'active' | 'hold' | 'all';
const STATUS: Record<Filter, OrderStatus[] | undefined> = {
  current: ['ACTIVE', 'ON_HOLD'],
  active: ['ACTIVE'],
  hold: ['ON_HOLD'],
  all: undefined,
};
type Sort = 'patient' | 'recent' | 'next';

export default function DoctorPrescriptionsPage() {
  const t = useTableState({ size: 20 });
  const [filter, setFilter] = useState<Filter>('current');
  const [sort, setSort] = useState<Sort>('patient');
  const [modifying, setModifying] = useState<OrderView | null>(null);
  const actions = useOrderActions();
  const params = { status: STATUS[filter], page: t.page, size: t.size };
  const q = useQuery({ queryKey: ck.myOrders(params), queryFn: () => medicationApi.myOrders(params), placeholderData: keepPreviousData });

  const groups = useMemo(() => {
    const rows = q.data?.content ?? [];
    if (sort !== 'patient') {
      const sorted = [...rows].sort((a, b) =>
        sort === 'recent'
          ? Date.parse(b.prescribedAt) - Date.parse(a.prescribedAt)
          : (a.nextDoseAt ? Date.parse(a.nextDoseAt) : Infinity) - (b.nextDoseAt ? Date.parse(b.nextDoseAt) : Infinity),
      );
      return [{ key: 'all', patient: undefined as PatientRef | undefined, orders: sorted }];
    }
    const map = new Map<string, { key: string; patient?: PatientRef; orders: OrderView[] }>();
    rows.forEach((o) => {
      const g = map.get(o.admissionId) ?? { key: o.admissionId, patient: o.patient, orders: [] };
      g.orders.push(o);
      map.set(o.admissionId, g);
    });
    return [...map.values()].sort((a, b) => (a.patient?.patientName ?? '').localeCompare(b.patient?.patientName ?? ''));
  }, [q.data, sort]);

  return (
    <>
      <PageHeader
        title="Prescriptions"
        subtitle="Medication orders across your patients. Prescribe new medication from the patient record."
        breadcrumbs={[{ label: 'Clinician', href: '/doctor' }, { label: 'Prescriptions' }]}
      />
      <Card sx={{ mb: 4 }}>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} justifyContent="space-between" alignItems={{ md: 'center' }} sx={{ p: 4 }}>
          <FilterChips<Filter>
            ariaLabel="Order status"
            value={filter}
            onChange={(v) => {
              setFilter(v);
              t.setPage(0);
            }}
            options={[
              { value: 'current', label: 'Active & on hold' },
              { value: 'active', label: 'Active' },
              { value: 'hold', label: 'On hold' },
              { value: 'all', label: 'All' },
            ]}
          />
          <TextField select size="small" label="Arrange by" value={sort} onChange={(e) => setSort(e.target.value as Sort)} sx={{ minWidth: 200, maxWidth: { md: 240 } }}>
            <MenuItem value="patient">Patient</MenuItem>
            <MenuItem value="next">Next dose due</MenuItem>
            <MenuItem value="recent">Most recently prescribed</MenuItem>
          </TextField>
        </Stack>
      </Card>

      {q.isLoading ? (
        <Card>
          <TableSkeleton />
        </Card>
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.content.length ? (
        <Card>
          <EmptyState icon={Pill} title="No medication orders match this filter" description="Orders you prescribe, or for patients under your care, appear here." />
        </Card>
      ) : (
        <Stack spacing={4} sx={{ opacity: q.isFetching ? 0.7 : 1, transition: 'opacity 120ms' }}>
          {groups.map((g) => (
            <Card key={g.key} sx={{ p: { xs: 3, sm: 4 } }}>
              {g.patient && <PatientGroupHeader patient={g.patient} count={g.orders.length} />}
              <Stack spacing={2}>
                {g.orders.map((o) => (
                  <OrderCard
                    key={o.id}
                    order={o}
                    header={
                      !g.patient && o.patient ? (
                        <Typography variant="caption" component="div" sx={{ mb: 1 }}>
                          <Box component={Link} href={`/doctor/patients/${o.admissionId}?tab=prescriptions`} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                            {o.patient.patientName}
                          </Box>{' '}
                          <Box component="span" sx={{ color: 'text.secondary' }}>
                            {o.patient.mrn} · {o.patient.location ?? 'Awaiting bed'}
                          </Box>
                        </Typography>
                      ) : undefined
                    }
                    actions={<OrderActionButtons order={o} onAction={actions.request} onModify={setModifying} />}
                  />
                ))}
              </Stack>
            </Card>
          ))}
          <Card>
            <TablePagination
              component="div"
              count={q.data.totalElements}
              page={t.page}
              rowsPerPage={t.size}
              rowsPerPageOptions={[10, 20, 50]}
              onPageChange={(_, p) => t.setPage(p)}
              onRowsPerPageChange={(e) => t.setSize(parseInt(e.target.value, 10))}
              labelRowsPerPage="Orders per page"
            />
          </Card>
        </Stack>
      )}
      {actions.dialogs}
      <PrescribeDialog
        open={!!modifying}
        onClose={() => setModifying(null)}
        admissionId={modifying?.admissionId ?? ''}
        modifyOrder={modifying ?? undefined}
      />
    </>
  );
}

function PatientGroupHeader({ patient, count }: { patient: PatientRef; count: number }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'flex-start' }} sx={{ mb: 3 }}>
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="h4" component="h2">
          {patient.patientName}
        </Typography>
        <Typography variant="caption" color="text.secondary" component="div">
          {patient.mrn} · {patient.admissionNumber} · {patient.location ?? 'Awaiting bed'} · {count} order{count === 1 ? '' : 's'} on this page
        </Typography>
        {patient.allergies && patient.allergies.length > 0 ? (
          <Stack direction="row" spacing={0.75} alignItems="center" sx={{ mt: 0.5, color: colors.danger }}>
            <ShieldAlert size={14} />
            <Typography variant="caption" fontWeight={600}>
              Allergies: {patient.allergies.join('; ')}
            </Typography>
          </Stack>
        ) : patient.allergies ? (
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
            No active allergies recorded
          </Typography>
        ) : null}
      </Box>
      <Button
        component={Link}
        href={`/doctor/patients/${patient.admissionId}?tab=prescriptions`}
        size="small"
        variant="outlined"
        endIcon={<ExternalLink size={14} />}
        sx={{ flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'auto' } }}
      >
        Patient record
      </Button>
    </Stack>
  );
}

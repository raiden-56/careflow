'use client';

import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Card, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Plus, Search } from 'lucide-react';
import { Availability, MEDICATION_FORMS, medicationApi, MedicationForm, MedicationView, pharmacyKeys } from '@/lib/api/pharmacy';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState, useToast } from '@/lib/hooks';
import { humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { Money } from '@/components/ui/Display';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { AvailabilityChip, MedicationFlags, RowMenu, useDesktopRowClick } from '@/components/pharmacy/PharmacyBits';
import { MedicationFormDialog } from '@/components/pharmacy/MedicationFormDialog';

type Flag = '' | 'HIGH_ALERT' | 'CONTROLLED';

export default function CataloguePage() {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const canManage = hasPermission('MEDICATION_CATALOGUE_MANAGE');
  const t = useTableState({ sort: { field: 'genericName', direction: 'asc' } });
  const [form, setForm] = useState<MedicationForm | ''>('');
  const [drugClass, setDrugClass] = useState('');
  const [flag, setFlag] = useState<Flag>('');
  const [active, setActive] = useState<'true' | 'false' | ''>('true');
  const [availability, setAvailability] = useState<Availability | ''>('');
  const [editing, setEditing] = useState<MedicationView | null>(null);
  const [creating, setCreating] = useState(false);
  const [toggle, setToggle] = useState<MedicationView | null>(null);
  const [toggleError, setToggleError] = useState<string | null>(null);

  const classes = useQuery({ queryKey: pharmacyKeys.drugClasses, queryFn: medicationApi.drugClasses });
  const params = {
    q: t.q || undefined,
    form: form || undefined,
    drugClass: drugClass || undefined,
    highAlert: flag === 'HIGH_ALERT' ? true : undefined,
    controlled: flag === 'CONTROLLED' ? true : undefined,
    active: active === '' ? undefined : active === 'true',
    availability: availability || undefined,
    page: t.page,
    size: t.size,
    sort: sortParam(t.sort),
  };
  const q = useQuery({ queryKey: pharmacyKeys.medications(params), queryFn: () => medicationApi.search(params), placeholderData: keepPreviousData });

  const toggleM = useMutation({
    mutationFn: (reason: string) => (toggle!.active ? medicationApi.deactivate(toggle!.id, reason) : medicationApi.activate(toggle!.id, reason)),
    onSuccess: (m) => {
      toast.success(`${m.displayName} ${m.active ? 'reactivated' : 'deactivated'}`);
      setToggle(null);
      queryClient.invalidateQueries({ queryKey: ['medications'] });
      queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
    },
    onError: (e) => setToggleError(errorMessage(e)),
  });

  const resetPage = () => t.setPage(0);
  const rowClick = useDesktopRowClick<MedicationView>(canManage ? (m) => setEditing(m) : undefined);

  return (
    <>
      <PageHeader
        title="Medication catalogue"
        subtitle="Items prescribers can order, with pricing, reorder levels and live usable stock."
        breadcrumbs={[{ label: 'Pharmacy', href: '/pharmacy' }, { label: 'Medication catalogue' }]}
        actions={
          canManage && (
            <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setCreating(true)}>
              Add medication
            </Button>
          )
        }
      />
      <Card>
        <Stack spacing={3} sx={{ p: 4 }}>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
            <TextField
              placeholder="Search name, brand, code or class"
              value={t.search}
              onChange={(e) => t.setSearch(e.target.value)}
              slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> }, htmlInput: { 'aria-label': 'Search catalogue' } }}
              sx={{ flex: 2 }}
            />
            <TextField select label="Form" value={form} onChange={(e) => { setForm(e.target.value as MedicationForm); resetPage(); }} sx={{ flex: 1 }}>
              <MenuItem value="">All forms</MenuItem>
              {MEDICATION_FORMS.map((f) => (
                <MenuItem key={f} value={f}>
                  {humanize(f)}
                </MenuItem>
              ))}
            </TextField>
            <TextField select label="Drug class" value={drugClass} onChange={(e) => { setDrugClass(e.target.value); resetPage(); }} sx={{ flex: 1 }}>
              <MenuItem value="">All classes</MenuItem>
              {classes.data?.map((c) => (
                <MenuItem key={c} value={c}>
                  {c}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          <Stack direction={{ xs: 'column', md: 'row' }} spacing={3}>
            <TextField select label="Risk flag" value={flag} onChange={(e) => { setFlag(e.target.value as Flag); resetPage(); }} sx={{ flex: 1 }}>
              <MenuItem value="">Any</MenuItem>
              <MenuItem value="HIGH_ALERT">High-alert only</MenuItem>
              <MenuItem value="CONTROLLED">Controlled only</MenuItem>
            </TextField>
            <TextField select label="Availability" value={availability} onChange={(e) => { setAvailability(e.target.value as Availability); resetPage(); }} sx={{ flex: 1 }}>
              <MenuItem value="">Any</MenuItem>
              <MenuItem value="IN_STOCK">In stock</MenuItem>
              <MenuItem value="LOW_STOCK">Low stock</MenuItem>
              <MenuItem value="OUT_OF_STOCK">Out of stock</MenuItem>
            </TextField>
            <TextField select label="Status" value={active} onChange={(e) => { setActive(e.target.value as 'true' | 'false' | ''); resetPage(); }} sx={{ flex: 1 }}>
              <MenuItem value="true">Active</MenuItem>
              <MenuItem value="false">Inactive</MenuItem>
              <MenuItem value="">All</MenuItem>
            </TextField>
          </Stack>
        </Stack>
        <DataTable<MedicationView>
          ariaLabel="Medication catalogue"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(m) => m.id}
          onRowClick={rowClick}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle="No medications match"
          emptyDescription="Try different filters, or add the item to the catalogue."
          columns={[
            {
              key: 'name',
              header: 'Medication',
              sortKey: 'genericName',
              primary: true,
              render: (m) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600} component="div">
                    {m.genericName} {m.strength} <MedicationFlags highAlert={m.isHighAlert} controlled={m.isControlled} />
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {m.code}
                    {m.brandName ? ` · ${m.brandName}` : ''}
                  </Typography>
                </Box>
              ),
            },
            {
              key: 'form',
              header: 'Form / route',
              sortKey: 'form',
              render: (m) => `${humanize(m.form)} · ${m.defaultRoute}`,
            },
            {
              key: 'class',
              header: 'Class',
              sortKey: 'drugClass',
              hideOnMobile: true,
              render: (m) => (
                <Box>
                  <Typography variant="body2">{m.drugClass ?? '—'}</Typography>
                  {m.allergenGroup && (
                    <Typography variant="caption" color="text.secondary">
                      Allergen group: {m.allergenGroup}
                    </Typography>
                  )}
                </Box>
              ),
            },
            {
              key: 'price',
              header: 'Price',
              sortKey: 'unitPrice',
              align: 'right',
              render: (m) => (
                <Box>
                  <Money value={m.unitPrice} />
                  <Typography variant="caption" color="text.secondary" component="div">
                    per {m.dispenseUnit}
                  </Typography>
                </Box>
              ),
            },
            { key: 'reorder', header: 'Reorder at', sortKey: 'reorderLevel', align: 'right', hideOnMobile: true, render: (m) => m.reorderLevel },
            { key: 'stock', header: 'Usable stock', sortKey: 'stockOnHand', render: (m) => <AvailabilityChip value={m.availability} stock={m.stockOnHand} /> },
            { key: 'status', header: 'Status', render: (m) => <StatusChip value={m.active ? 'ACTIVE' : 'INACTIVE'} /> },
            ...(canManage
              ? [
                  {
                    key: 'actions',
                    header: '',
                    align: 'right' as const,
                    render: (m: MedicationView) => (
                      <RowMenu
                        label={`Actions for ${m.code}`}
                        items={[
                          { label: 'Edit details', onClick: () => setEditing(m) },
                          {
                            label: m.active ? 'Deactivate' : 'Reactivate',
                            danger: m.active,
                            onClick: () => {
                              setToggleError(null);
                              setToggle(m);
                            },
                          },
                        ]}
                      />
                    ),
                  },
                ]
              : []),
          ]}
        />
      </Card>

      <MedicationFormDialog open={creating || !!editing} medication={editing} onClose={() => { setCreating(false); setEditing(null); }} />
      <ConfirmDialog
        open={!!toggle}
        title={toggle?.active ? `Deactivate ${toggle?.displayName}?` : `Reactivate ${toggle?.displayName}?`}
        description={
          toggle?.active
            ? 'Prescribers will no longer find it for new prescriptions. Existing active prescriptions can still be supplied; stock is unchanged.'
            : 'It becomes available to prescribers again.'
        }
        confirmLabel={toggle?.active ? 'Deactivate' : 'Reactivate'}
        tone={toggle?.active ? 'error' : 'primary'}
        requireReason
        loading={toggleM.isPending}
        error={toggleError}
        onConfirm={(r) => toggleM.mutate(r!)}
        onClose={() => setToggle(null)}
      />
    </>
  );
}

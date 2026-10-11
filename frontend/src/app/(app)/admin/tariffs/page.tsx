'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  IconButton,
  InputAdornment,
  MenuItem,
  Stack,
  Switch,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { Pencil, Plus, Power, PowerOff, Search } from 'lucide-react';
import { adminApi, BillingUnit, TariffCategory, TariffView } from '@/lib/api/admin';
import { facilityApi, qk } from '@/lib/api/core';
import { applyServerErrors, useTableState, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDate, formatINR, humanize, todayLocalDate } from '@/lib/format';
import { optionalText } from '@/lib/validation';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { Money } from '@/components/ui/Display';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const CATEGORIES: TariffCategory[] = ['ROOM', 'CONSULTATION', 'NURSING', 'LAB', 'IMAGING', 'PROCEDURE', 'PHARMACY', 'CONSUMABLE', 'OTHER'];
const UNITS: BillingUnit[] = ['PER_UNIT', 'PER_DAY', 'PER_VISIT', 'PER_HOUR'];
const ROOM_TYPES = ['GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'DELUXE', 'ICU', 'ISOLATION'];

const schema = z
  .object({
    code: z.string().trim().regex(/^[A-Z0-9_-]{2,32}$/, 'Use 2-32 upper-case letters, digits, - or _'),
    name: z.string().trim().min(2, 'Name is required').max(200),
    category: z.enum(CATEGORIES as [TariffCategory, ...TariffCategory[]], { message: 'Select a category' }),
    unitPrice: z.string().trim().regex(/^\d{1,10}(\.\d{1,2})?$/, 'Enter a price of zero or more, e.g. 1500 or 1500.50'),
    taxRate: z
      .string()
      .trim()
      .regex(/^\d{1,3}(\.\d{1,2})?$/, 'Enter a percentage, e.g. 5 or 12.5')
      .refine((v) => Number(v) <= 100, 'Tax must be between 0 and 100'),
    billingUnit: z.enum(UNITS as [BillingUnit, ...BillingUnit[]]),
    roomType: z.string().optional(),
    departmentId: z.string().optional(),
    description: optionalText(500, 'Description'),
    effectiveFrom: z.string().optional(),
  })
  .superRefine((v, ctx) => {
    if (v.category === 'ROOM' && !v.roomType) ctx.addIssue({ code: 'custom', path: ['roomType'], message: 'Room tariffs need a room type' });
    if (v.category === 'ROOM' && v.billingUnit !== 'PER_DAY' && v.billingUnit !== 'PER_HOUR')
      ctx.addIssue({ code: 'custom', path: ['billingUnit'], message: 'Room tariffs are charged per day or per hour' });
    if (v.category !== 'ROOM' && v.roomType) ctx.addIssue({ code: 'custom', path: ['roomType'], message: 'Only room tariffs have a room type' });
  });
type Values = z.infer<typeof schema>;

export default function TariffsPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const t = useTableState({ sort: { field: 'code', direction: 'asc' } });
  const [category, setCategory] = useState('');
  const [includeInactive, setIncludeInactive] = useState(false);
  const [dialog, setDialog] = useState<{ open: boolean; tariff?: TariffView }>({ open: false });
  const [toggle, setToggle] = useState<TariffView | null>(null);
  const params = { q: t.q, category: category || undefined, includeInactive, page: t.page, size: t.size, sort: sortParam(t.sort) };
  const q = useQuery({ queryKey: ['tariffs', params], queryFn: () => adminApi.tariffs(params), placeholderData: keepPreviousData });

  const toggleM = useMutation({
    mutationFn: ({ tariff, reason }: { tariff: TariffView; reason?: string }) =>
      tariff.active ? adminApi.deactivateTariff(tariff.id, reason) : adminApi.activateTariff(tariff.id),
    onSuccess: (x) => {
      toast.success(`${x.code} ${x.active ? 'reactivated' : 'deactivated'}`);
      setToggle(null);
      qc.invalidateQueries({ queryKey: ['tariffs'] });
    },
    onError: toast.error,
  });

  return (
    <>
      <PageHeader
        title="Services & tariffs"
        subtitle="Prices used for charge capture. Changes apply to new charges only — posted charges are never repriced. Every price change is audited."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Services & tariffs' }]}
        actions={
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setDialog({ open: true })}>
            Add tariff
          </Button>
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ p: 4 }} alignItems={{ md: 'center' }}>
          <TextField
            placeholder="Search code or name"
            value={t.search}
            onChange={(e) => t.setSearch(e.target.value)}
            aria-label="Search tariffs"
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { md: 320 } }}
          />
          <TextField select label="Category" value={category} onChange={(e) => { setCategory(e.target.value); t.setPage(0); }} sx={{ minWidth: 200 }}>
            <MenuItem value="">All categories</MenuItem>
            {CATEGORIES.map((c) => (
              <MenuItem key={c} value={c}>
                {humanize(c)}
              </MenuItem>
            ))}
          </TextField>
          <FormControlLabel control={<Switch checked={includeInactive} onChange={(e) => { setIncludeInactive(e.target.checked); t.setPage(0); }} />} label="Show inactive" />
        </Stack>
        <DataTable<TariffView>
          ariaLabel="Tariffs"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(x) => x.id}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle={t.q || category ? 'No tariffs match these filters' : 'No tariffs configured'}
          rowSx={(x) => (x.active ? undefined : { opacity: 0.6 })}
          columns={[
            {
              key: 'code',
              header: 'Service',
              sortKey: 'code',
              primary: true,
              render: (x) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {x.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                    {x.code}
                  </Typography>
                </Box>
              ),
            },
            { key: 'cat', header: 'Category', sortKey: 'category', render: (x) => humanize(x.category) + (x.roomType ? ` · ${humanize(x.roomType)}` : '') },
            { key: 'price', header: 'Price', sortKey: 'unitPrice', align: 'right', render: (x) => <Money value={x.unitPrice} strong /> },
            { key: 'unit', header: 'Unit', render: (x) => humanize(x.billingUnit).replace('Per ', 'per ') },
            { key: 'tax', header: 'Tax', align: 'right', render: (x) => `${Number(x.taxRate)}%` },
            { key: 'eff', header: 'Effective from', hideOnMobile: true, render: (x) => formatDate(x.effectiveFrom) },
            { key: 'status', header: 'Status', render: (x) => <StatusChip value={x.active ? 'ACTIVE' : 'INACTIVE'} /> },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (x) => (
                <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                  <Tooltip title="Edit">
                    <IconButton size="small" aria-label={`Edit ${x.code}`} onClick={() => setDialog({ open: true, tariff: x })}>
                      <Pencil size={16} />
                    </IconButton>
                  </Tooltip>
                  <Tooltip title={x.active ? 'Deactivate' : 'Reactivate'}>
                    <IconButton size="small" aria-label={`${x.active ? 'Deactivate' : 'Reactivate'} ${x.code}`} onClick={() => setToggle(x)}>
                      {x.active ? <PowerOff size={16} /> : <Power size={16} />}
                    </IconButton>
                  </Tooltip>
                </Stack>
              ),
            },
          ]}
        />
      </Card>
      <TariffDialog
        open={dialog.open}
        tariff={dialog.tariff}
        onClose={() => setDialog({ open: false })}
        onSaved={(x, created) => {
          toast.success(created ? `${x.code} created` : `${x.code} saved`);
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['tariffs'] });
        }}
      />
      <ConfirmDialog
        open={!!toggle}
        title={toggle?.active ? `Deactivate ${toggle?.code}?` : `Reactivate ${toggle?.code}?`}
        description={
          toggle?.active
            ? 'Staff will no longer be able to charge this service. Existing charges are unaffected and the tariff can be reactivated later.'
            : 'The tariff becomes available for new charges again at its current price.'
        }
        tone={toggle?.active ? 'error' : 'primary'}
        requireReason={toggle?.active}
        confirmLabel={toggle?.active ? 'Deactivate' : 'Reactivate'}
        loading={toggleM.isPending}
        onConfirm={(reason) => toggle && toggleM.mutate({ tariff: toggle, reason })}
        onClose={() => setToggle(null)}
      />
    </>
  );
}

function TariffDialog({ open, tariff, onClose, onSaved }: { open: boolean; tariff?: TariffView; onClose: () => void; onSaved: (t: TariffView, created: boolean) => void }) {
  const [error, setError] = useState<string | null>(null);
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments, enabled: open });
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<Values>({ resolver: zodResolver(schema) });
  const category = watch('category');
  const price = watch('unitPrice');
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: tariff?.code ?? '',
        name: tariff?.name ?? '',
        category: (tariff?.category ?? '') as TariffCategory,
        unitPrice: tariff?.unitPrice ?? '',
        taxRate: tariff ? String(Number(tariff.taxRate)) : '0',
        billingUnit: tariff?.billingUnit ?? 'PER_UNIT',
        roomType: tariff?.roomType ?? '',
        departmentId: tariff?.departmentId ?? '',
        description: tariff?.description ?? '',
        effectiveFrom: tariff?.effectiveFrom ?? todayLocalDate(),
      });
    }
  }, [open, tariff, reset]);
  const m = useMutation({
    mutationFn: (v: Values) => {
      const body = {
        ...v,
        roomType: v.roomType || null,
        departmentId: v.departmentId || null,
        description: v.description || null,
        effectiveFrom: v.effectiveFrom || null,
        version: tariff?.version,
      };
      return tariff ? adminApi.updateTariff(tariff.id, body) : adminApi.createTariff(body);
    },
    onSuccess: (x) => onSaved(x, !tariff),
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  const priceChanged = tariff && price && Number(price) !== Number(tariff.unitPrice);
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="tariff-title">
      <DialogTitle id="tariff-title">{tariff ? `Edit ${tariff.code}` : 'Add tariff'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="tariff-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="code" label="Code" required disabled={!!tariff} sx={{ flex: 1 }} helperText={tariff ? 'Codes are permanent' : undefined} />
            <RHFTextField control={control} name="name" label="Service name" required sx={{ flex: 2 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="category" label="Category" required options={CATEGORIES.map((c) => ({ value: c, label: humanize(c) }))} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="billingUnit" label="Billing unit" options={UNITS.map((u) => ({ value: u, label: humanize(u) }))} sx={{ flex: 1 }} />
          </Stack>
          {category === 'ROOM' && (
            <RHFSelect control={control} name="roomType" label="Room type" required options={ROOM_TYPES.map((r) => ({ value: r, label: humanize(r) }))} fullWidth helperText="Daily room charges use the tariff for the bed's room type." />
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField
              control={control}
              name="unitPrice"
              label="Unit price"
              required
              inputMode="decimal"
              sx={{ flex: 1 }}
              slotProps={{ input: { startAdornment: <InputAdornment position="start">₹</InputAdornment> } }}
            />
            <RHFTextField
              control={control}
              name="taxRate"
              label="Tax rate"
              required
              inputMode="decimal"
              sx={{ flex: 1 }}
              slotProps={{ input: { endAdornment: <InputAdornment position="end">%</InputAdornment> } }}
            />
          </Stack>
          {priceChanged && (
            <Alert severity="warning">
              Price changes from {formatINR(tariff!.unitPrice)} to {formatINR(price)}. Applies to new charges only and is recorded in the audit log.
            </Alert>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="departmentId" label="Department" emptyLabel="Any department" options={(departments.data ?? []).map((d) => ({ value: d.id, label: d.name }))} sx={{ flex: 2 }} />
            <RHFTextField control={control} name="effectiveFrom" label="Effective from" type="date" sx={{ flex: 1 }} />
          </Stack>
          <RHFTextField control={control} name="description" label="Description" fullWidth multiline minRows={2} />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="tariff-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

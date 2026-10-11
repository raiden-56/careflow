'use client';

import { useEffect, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery } from '@tanstack/react-query';
import { Alert, Autocomplete, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Stack, TextField, Typography } from '@mui/material';
import { billingApi, billingKeys, CATEGORIES, CATEGORY_LABEL, type ChargeCategory, type TariffOption } from '@/lib/api/billing';
import { applyServerErrors, useDebouncedValue } from '@/lib/hooks';
import { formatINR, todayLocalDate } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const chargeSchema = z
  .object({
    quantity: z
      .string()
      .trim()
      .regex(/^\d{1,4}(\.\d{1,2})?$/, 'Enter a quantity like 1 or 2.5')
      .refine((v) => Number(v) > 0, 'Quantity must be greater than zero'),
    serviceDate: z.string().min(1, 'Service date is required'),
    overridePrice: z.string().trim().regex(/^(\d{1,10}(\.\d{1,2})?)?$/, 'Enter a price like 1500 or 1500.50').optional(),
    overrideReason: z.string().trim().max(500).optional(),
    note: z.string().trim().max(200).optional(),
  })
  .superRefine((v, ctx) => {
    if (v.overridePrice && (!v.overrideReason || v.overrideReason.length < 3)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['overrideReason'], message: 'A reason is required when overriding the tariff price' });
    }
  });
type ChargeValues = z.infer<typeof chargeSchema>;

/** Posts a charge from an active tariff. A price override needs a documented reason (audited). */
export function AddChargeDialog({
  open,
  invoiceId,
  onClose,
  onDone,
}: {
  open: boolean;
  invoiceId: string;
  onClose: () => void;
  onDone: () => void;
}) {
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 250);
  const [tariff, setTariff] = useState<TariffOption | null>(null);
  const [category, setCategory] = useState<ChargeCategory | ''>('');
  const [error, setError] = useState<string | null>(null);
  const tariffs = useQuery({
    queryKey: billingKeys.tariffs({ q, category }),
    queryFn: () => billingApi.tariffs({ q: q || undefined, category: category || undefined }),
    enabled: open,
  });
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<ChargeValues>({
    resolver: zodResolver(chargeSchema),
    defaultValues: { quantity: '1', serviceDate: todayLocalDate(), overridePrice: '', overrideReason: '', note: '' },
  });
  const overridePrice = useWatch({ control, name: 'overridePrice' });

  useEffect(() => {
    if (open) {
      setTariff(null);
      setSearch('');
      setCategory('');
      setError(null);
      reset({ quantity: '1', serviceDate: todayLocalDate(), overridePrice: '', overrideReason: '', note: '' });
    }
  }, [open, reset]);

  const submit = handleSubmit(async (v) => {
    if (!tariff) {
      setError('Choose a service from the tariff list.');
      return;
    }
    setError(null);
    try {
      await billingApi.addCharge(invoiceId, {
        tariffId: tariff.id,
        quantity: v.quantity,
        serviceDate: v.serviceDate,
        unitPriceOverride: v.overridePrice || null,
        overrideReason: v.overrideReason || null,
        note: v.note || null,
      });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });

  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="charge-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="charge-title">Add charge</DialogTitle>
        <DialogContent>
          <Grid container spacing={3} sx={{ pt: 1 }}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <TextField select label="Category" value={category} onChange={(e) => setCategory(e.target.value as ChargeCategory | '')} fullWidth slotProps={{ select: { native: true } }}>
                <option value="">All categories</option>
                {CATEGORIES.map((c) => (
                  <option key={c} value={c}>
                    {CATEGORY_LABEL[c]}
                  </option>
                ))}
              </TextField>
            </Grid>
            <Grid size={{ xs: 12, sm: 7 }}>
              <Autocomplete<TariffOption>
                options={tariffs.data ?? []}
                loading={tariffs.isFetching}
                value={tariff}
                onChange={(_, t) => setTariff(t)}
                inputValue={search}
                onInputChange={(_, v, reason) => reason !== 'reset' && setSearch(v)}
                filterOptions={(x) => x}
                getOptionLabel={(t) => `${t.name} (${t.code})`}
                isOptionEqualToValue={(a, b) => a.id === b.id}
                renderOption={(props, t) => (
                  <li {...props} key={t.id}>
                    <Box sx={{ width: '100%' }}>
                      <Stack direction="row" justifyContent="space-between" spacing={2}>
                        <Typography variant="body2">{t.name}</Typography>
                        <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                          {formatINR(t.unitPrice)}
                        </Typography>
                      </Stack>
                      <Typography variant="caption" color="text.secondary">
                        {t.code} · {CATEGORY_LABEL[t.category]}
                        {Number(t.taxRate) > 0 ? ` · tax ${t.taxRate}%` : ''}
                      </Typography>
                    </Box>
                  </li>
                )}
                renderInput={(params) => <TextField {...params} label="Service (tariff)" required placeholder="Search name or code" />}
                noOptionsText={q ? 'No active tariff matches' : 'Type to search'}
              />
            </Grid>
            {tariff && (
              <Grid size={12}>
                <Alert severity="info" icon={false}>
                  Tariff price {formatINR(tariff.unitPrice)} per {tariff.billingUnit.toLowerCase().replace('per_', '')}
                  {Number(tariff.taxRate) > 0 ? `, plus ${tariff.taxRate}% tax` : ''}. The server calculates the line total.
                </Alert>
              </Grid>
            )}
            <Grid size={{ xs: 6, sm: 4 }}>
              <RHFTextField control={control} name="quantity" label="Quantity" required fullWidth />
            </Grid>
            <Grid size={{ xs: 6, sm: 4 }}>
              <RHFTextField control={control} name="serviceDate" label="Service date" type="date" required fullWidth slotProps={{ htmlInput: { max: todayLocalDate() } }} />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="overridePrice" label="Override unit price" fullWidth helperText="Leave empty to use the tariff" />
            </Grid>
            {overridePrice ? (
              <Grid size={12}>
                <RHFTextField control={control} name="overrideReason" label="Reason for price override" required fullWidth multiline minRows={2} />
              </Grid>
            ) : null}
            <Grid size={12}>
              <RHFTextField control={control} name="note" label="Detail shown on the bill (optional)" fullWidth helperText="e.g. 'left forearm', 'second dressing'" />
            </Grid>
          </Grid>
          {error && (
            <Alert severity="error" sx={{ mt: 3 }}>
              {error}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button onClick={onClose} color="inherit" disabled={formState.isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? 'Posting…' : 'Post charge'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

const estimateSchema = z.object({
  category: z.enum(['ROOM', 'PROCEDURE', 'LAB', 'PHARMACY', 'CONSULTATION', 'NURSING', 'IMAGING', 'CONSUMABLE', 'OTHER']),
  description: z.string().trim().min(3, 'Describe the expected charge').max(500),
  estimatedAmount: z.string().trim().regex(/^\d{1,12}(\.\d{1,2})?$/, 'Enter an amount like 4200'),
  basis: z.string().trim().min(3, 'Explain the basis, e.g. "3 more days of general ward"').max(500),
});
type EstimateValues = z.infer<typeof estimateSchema>;

/** Adds an informational estimate. Estimates never change the balance. */
export function EstimateDialog({ open, invoiceId, onClose, onDone }: { open: boolean; invoiceId: string; onClose: () => void; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<EstimateValues>({
    resolver: zodResolver(estimateSchema),
    defaultValues: { category: 'ROOM', description: '', estimatedAmount: '', basis: '' },
  });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({ category: 'ROOM', description: '', estimatedAmount: '', basis: '' });
    }
  }, [open, reset]);
  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      await billingApi.addEstimate(invoiceId, v);
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });
  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="estimate-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="estimate-title">Add estimated charge</DialogTitle>
        <DialogContent>
          <Alert severity="info" sx={{ mb: 4 }}>
            Estimates help the family plan. They are labelled as estimates everywhere and are never part of the balance.
          </Alert>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="category" label="Category" fullWidth options={CATEGORIES.map((c) => ({ value: c, label: CATEGORY_LABEL[c] }))} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="estimatedAmount" label="Estimated amount (INR)" required fullWidth />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="description" label="Description" required fullWidth />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="basis" label="Basis" required fullWidth placeholder="e.g. 3 more days of general ward" />
            </Grid>
          </Grid>
          {error && (
            <Alert severity="error" sx={{ mt: 3 }}>
              {error}
            </Alert>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button onClick={onClose} color="inherit" disabled={formState.isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" variant="contained" disabled={formState.isSubmitting}>
            Add estimate
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

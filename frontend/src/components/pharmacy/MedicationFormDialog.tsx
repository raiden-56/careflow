'use client';

import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, useMediaQuery, useTheme } from '@mui/material';
import { MEDICATION_FORMS, MEDICATION_ROUTES, medicationApi, MedicationForm, MedicationRoute, MedicationView } from '@/lib/api/pharmacy';
import { ApiError, errorMessage } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { humanize } from '@/lib/format';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z.object({
  code: z
    .string()
    .trim()
    .regex(/^[A-Za-z0-9][A-Za-z0-9-]{1,31}$/, 'Use 2-32 letters, digits or hyphens'),
  genericName: z.string().trim().min(1, 'Generic name is required').max(200),
  brandName: z.string().trim().max(200).optional(),
  form: z.enum(MEDICATION_FORMS as [MedicationForm, ...MedicationForm[]]),
  strength: z.string().trim().min(1, 'Strength is required').max(50),
  defaultRoute: z.enum(MEDICATION_ROUTES as [MedicationRoute, ...MedicationRoute[]]),
  drugClass: z.string().trim().max(100).optional(),
  allergenGroup: z.string().trim().max(100).optional(),
  isHighAlert: z.boolean(),
  isControlled: z.boolean(),
  unitPrice: z
    .string()
    .trim()
    .regex(/^\d{1,10}(\.\d{1,2})?$/, 'Enter a price like 12 or 12.50'),
  dispenseUnit: z.string().trim().min(1, 'Dispense unit is required').max(20),
  reorderLevel: z.coerce.number().int('Whole number').min(0, 'Cannot be negative').max(1_000_000),
});
type Values = z.infer<typeof schema>;

const EMPTY: Values = {
  code: '',
  genericName: '',
  brandName: '',
  form: 'TABLET',
  strength: '',
  defaultRoute: 'ORAL',
  drugClass: '',
  allergenGroup: '',
  isHighAlert: false,
  isControlled: false,
  unitPrice: '',
  dispenseUnit: 'TAB/CAP',
  reorderLevel: 0,
};

/** Create or edit a catalogue item. The code is fixed once created. */
export function MedicationFormDialog({ open, medication, onClose }: { open: boolean; medication: MedicationView | null; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const { control, handleSubmit, reset, setError } = useForm<Values>({ resolver: zodResolver(schema), defaultValues: EMPTY });
  useEffect(() => {
    if (!open) return;
    reset(
      medication
        ? {
            code: medication.code,
            genericName: medication.genericName,
            brandName: medication.brandName ?? '',
            form: medication.form,
            strength: medication.strength,
            defaultRoute: medication.defaultRoute,
            drugClass: medication.drugClass ?? '',
            allergenGroup: medication.allergenGroup ?? '',
            isHighAlert: medication.isHighAlert,
            isControlled: medication.isControlled,
            unitPrice: medication.unitPrice,
            dispenseUnit: medication.dispenseUnit,
            reorderLevel: medication.reorderLevel,
          }
        : EMPTY,
    );
  }, [open, medication, reset]);

  const m = useMutation({
    mutationFn: (v: Values) => {
      const body = {
        ...v,
        brandName: v.brandName || null,
        drugClass: v.drugClass || null,
        allergenGroup: v.allergenGroup || null,
        version: medication?.version,
      };
      return medication ? medicationApi.update(medication.id, body) : medicationApi.create(body);
    },
    onSuccess: (res) => {
      toast.success(medication ? `${res.displayName} updated` : `${res.displayName} added to the catalogue`);
      queryClient.invalidateQueries({ queryKey: ['medications'] });
      queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'DUPLICATE_CODE') setError('code', { message: e.message });
      else if (!applyServerErrors(e, setError)) toast.error(e);
    },
  });

  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="med-form-title">
      <DialogTitle id="med-form-title">{medication ? `Edit ${medication.code}` : 'Add medication'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={3} component="form" id="medication-form" onSubmit={handleSubmit((v) => m.mutate(v))} noValidate sx={{ pt: 1 }}>
          <Grid size={{ xs: 12, sm: 4 }}>
            <RHFTextField control={control} name="code" label="Code" required disabled={!!medication} helperText={medication ? 'Codes cannot change' : 'e.g. MED-AMOX500'} />
          </Grid>
          <Grid size={{ xs: 12, sm: 8 }}>
            <RHFTextField control={control} name="genericName" label="Generic name" required />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="brandName" label="Brand name" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="strength" label="Strength" required helperText="e.g. 500 mg, 4 mg/4 mL" />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <RHFSelect control={control} name="form" label="Form" options={MEDICATION_FORMS.map((f) => ({ value: f, label: humanize(f) }))} />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <RHFSelect control={control} name="defaultRoute" label="Default route" options={MEDICATION_ROUTES.map((r) => ({ value: r, label: humanize(r) }))} />
          </Grid>
          <Grid size={{ xs: 12, sm: 4 }}>
            <RHFTextField control={control} name="dispenseUnit" label="Dispense unit" required helperText="e.g. TAB/CAP, VIAL, BAG" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="drugClass" label="Drug class" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="allergenGroup" label="Allergen group" helperText="Matched against documented allergies, e.g. PENICILLIN, NSAID" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="unitPrice" label="Unit price (₹)" required slotProps={{ htmlInput: { inputMode: 'decimal' } }} helperText="Charged per dispense unit" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFTextField control={control} name="reorderLevel" label="Reorder level" type="number" required slotProps={{ htmlInput: { min: 0 } }} helperText="Low-stock alert below this usable quantity" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFCheckbox control={control} name="isHighAlert" label="High-alert medication" />
          </Grid>
          <Grid size={{ xs: 12, sm: 6 }}>
            <RHFCheckbox control={control} name="isControlled" label="Controlled drug" />
          </Grid>
          {m.error instanceof ApiError && m.error.fieldErrors.length === 0 && m.error.code !== 'DUPLICATE_CODE' && (
            <Grid size={12}>
              <Alert severity="error">{errorMessage(m.error)}</Alert>
            </Grid>
          )}
        </Grid>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="medication-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : medication ? 'Save changes' : 'Add medication'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

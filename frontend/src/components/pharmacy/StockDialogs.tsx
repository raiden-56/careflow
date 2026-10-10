'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { BatchView, medicationApi, MedicationView, pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import { errorMessage, ApiError } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDate, todayLocalDate } from '@/lib/format';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { MedicationPicker } from './MedicationPicker';

function invalidateStock(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
  queryClient.invalidateQueries({ queryKey: ['medications'] });
}

const receiveSchema = z.object({
  batchNumber: z
    .string()
    .trim()
    .min(1, 'Batch number is required')
    .max(50)
    .regex(/^[A-Za-z0-9][A-Za-z0-9./_-]*$/, 'Use letters, digits and . / _ - only'),
  expiryDate: z
    .string()
    .min(1, 'Expiry date is required')
    .refine((v) => v > todayLocalDate(), 'Expiry must be after today'),
  quantity: z.coerce.number().int('Whole units only').min(1, 'At least 1').max(1_000_000),
  unitCost: z
    .string()
    .trim()
    .regex(/^\d{1,10}(\.\d{1,2})?$/, 'Enter a cost like 12 or 12.50'),
  supplier: z.string().trim().max(200).optional(),
});
type ReceiveValues = z.infer<typeof receiveSchema>;

/** Goods receipt into a new batch (RECEIPT movement on the server). */
export function ReceiveStockDialog({ open, onClose, initialMedicationId }: { open: boolean; onClose: () => void; initialMedicationId?: string | null }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [medication, setMedication] = useState<MedicationView | null>(null);
  const [medError, setMedError] = useState<string | undefined>();
  const { control, handleSubmit, reset, setError } = useForm<ReceiveValues>({
    resolver: zodResolver(receiveSchema),
    defaultValues: { batchNumber: '', expiryDate: '', quantity: 1, unitCost: '', supplier: '' },
  });
  const initial = useQuery({
    queryKey: ['medications', 'one', initialMedicationId],
    queryFn: () => medicationApi.get(initialMedicationId!),
    enabled: open && !!initialMedicationId,
  });
  useEffect(() => {
    if (open) {
      reset({ batchNumber: '', expiryDate: '', quantity: 1, unitCost: '', supplier: '' });
      setMedication(null);
      setMedError(undefined);
    }
  }, [open, reset]);
  useEffect(() => {
    if (open && initial.data) setMedication(initial.data);
  }, [open, initial.data]);

  const m = useMutation({
    mutationFn: (v: ReceiveValues) =>
      pharmacyApi.receive({ medicationId: medication!.id, ...v, supplier: v.supplier || null }),
    onSuccess: (b) => {
      toast.success(`Received ${b.quantityReceived} ${b.dispenseUnit ?? ''} of ${b.medicationName} (batch ${b.batchNumber})`);
      invalidateStock(queryClient);
      onClose();
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'DUPLICATE_BATCH') setError('batchNumber', { message: e.message });
      else if (!applyServerErrors(e, setError)) toast.error(e);
    },
  });
  const submit = handleSubmit((v) => {
    if (!medication) {
      setMedError('Choose a medication');
      return;
    }
    m.mutate(v);
  });

  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="receive-title">
      <DialogTitle id="receive-title">Receive stock</DialogTitle>
      <DialogContent>
        <Stack spacing={4} component="form" id="receive-form" onSubmit={submit} noValidate sx={{ pt: 1 }}>
          <MedicationPicker value={medication} onChange={(v) => { setMedication(v); setMedError(undefined); }} required error={medError} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="batchNumber" label="Batch number" required sx={{ flex: 1 }} />
            <RHFTextField control={control} name="expiryDate" label="Expiry date" type="date" required sx={{ flex: 1 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="quantity" label={`Quantity${medication ? ` (${medication.dispenseUnit})` : ''}`} type="number" required sx={{ flex: 1 }} slotProps={{ htmlInput: { min: 1 } }} />
            <RHFTextField control={control} name="unitCost" label="Unit cost (₹)" required sx={{ flex: 1 }} slotProps={{ htmlInput: { inputMode: 'decimal' } }} />
          </Stack>
          <RHFTextField control={control} name="supplier" label="Supplier" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="receive-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : 'Receive stock'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const adjustSchema = z
  .object({
    batchId: z.string().min(1, 'Choose a batch'),
    direction: z.enum(['IN', 'OUT']),
    quantity: z.coerce.number().int('Whole units only').min(1, 'At least 1').max(1_000_000),
    reasonCode: z.enum(['COUNT_CORRECTION', 'DAMAGE', 'OTHER']),
    reason: z.string().trim().min(3, 'Document a reason (at least 3 characters)').max(450),
  })
  .refine((v) => !(v.direction === 'IN' && v.reasonCode === 'DAMAGE'), { path: ['reasonCode'], message: 'Damage can only reduce stock' });
type AdjustValues = z.infer<typeof adjustSchema>;

/**
 * Stock adjustment with a mandatory reason. Pass a batch to adjust it directly, or a medication id to pick
 * one of its batches.
 */
export function AdjustStockDialog({
  open,
  onClose,
  batch,
  medicationId,
  medicationName,
}: {
  open: boolean;
  onClose: () => void;
  batch?: BatchView | null;
  medicationId?: string | null;
  medicationName?: string;
}) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const batchParams = { medicationId: medicationId ?? undefined, status: ['ACTIVE', 'QUARANTINED', 'DEPLETED'] as BatchView['status'][], includeEmpty: true, size: 100 };
  const batches = useQuery({
    queryKey: pharmacyKeys.batches(batchParams),
    queryFn: () => pharmacyApi.batches(batchParams),
    enabled: open && !batch && !!medicationId,
  });
  const { control, handleSubmit, reset, setError, watch } = useForm<AdjustValues>({
    resolver: zodResolver(adjustSchema),
    defaultValues: { batchId: '', direction: 'OUT', quantity: 1, reasonCode: 'COUNT_CORRECTION', reason: '' },
  });
  useEffect(() => {
    if (open) reset({ batchId: batch?.id ?? '', direction: 'OUT', quantity: 1, reasonCode: 'COUNT_CORRECTION', reason: '' });
  }, [open, batch, reset]);
  const options = batch ? [batch] : batches.data?.content ?? [];
  const selected = options.find((b) => b.id === watch('batchId'));

  const m = useMutation({
    mutationFn: (v: AdjustValues) =>
      pharmacyApi.adjust(v.batchId, { direction: v.direction, quantity: v.quantity, reasonCode: v.reasonCode, reason: v.reason }),
    onSuccess: (b) => {
      toast.success(`Batch ${b.batchNumber} now has ${b.quantityOnHand} on hand`);
      invalidateStock(queryClient);
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setError)) toast.error(e);
    },
  });

  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="adjust-title">
      <DialogTitle id="adjust-title">Adjust stock{medicationName || batch ? ` — ${batch?.medicationName ?? medicationName}` : ''}</DialogTitle>
      <DialogContent>
        <Stack spacing={4} component="form" id="adjust-form" onSubmit={handleSubmit((v) => m.mutate(v))} noValidate sx={{ pt: 1 }}>
          {!batch && batches.isSuccess && options.length === 0 && <Alert severity="info">This medication has no batches yet. Receive stock first.</Alert>}
          <Controller
            control={control}
            name="batchId"
            render={({ field, fieldState }) => (
              <TextField {...field} select label="Batch" required disabled={!!batch} error={!!fieldState.error} helperText={fieldState.error?.message ?? (selected ? `${selected.quantityOnHand} on hand · ${selected.status.toLowerCase()}` : ' ')}>
                {options.map((b) => (
                  <MenuItem key={b.id} value={b.id}>
                    {b.batchNumber} · exp {formatDate(b.expiryDate)} · {b.quantityOnHand} on hand
                  </MenuItem>
                ))}
              </TextField>
            )}
          />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect
              control={control}
              name="direction"
              label="Direction"
              sx={{ flex: 1 }}
              options={[
                { value: 'OUT', label: 'Remove stock' },
                { value: 'IN', label: 'Add stock' },
              ]}
            />
            <RHFTextField control={control} name="quantity" label="Quantity" type="number" required sx={{ flex: 1 }} slotProps={{ htmlInput: { min: 1 } }} />
          </Stack>
          <RHFSelect
            control={control}
            name="reasonCode"
            label="Reason type"
            options={[
              { value: 'COUNT_CORRECTION', label: 'Count correction' },
              { value: 'DAMAGE', label: 'Damage / breakage' },
              { value: 'OTHER', label: 'Other' },
            ]}
          />
          <RHFTextField control={control} name="reason" label="Reason (recorded in the stock ledger)" required multiline minRows={2} />
          <Typography variant="caption" color="text.secondary">
            Adjustments are permanent ledger entries. Stock can never go below zero.
          </Typography>
          {m.error instanceof ApiError && m.error.fieldErrors.length === 0 && <Alert severity="error">{errorMessage(m.error)}</Alert>}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="adjust-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : 'Record adjustment'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

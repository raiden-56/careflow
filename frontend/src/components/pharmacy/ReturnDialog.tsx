'use client';

import { useEffect } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Checkbox, Dialog, DialogActions, DialogContent, DialogTitle, FormControlLabel, Stack, TextField, Typography } from '@mui/material';
import { DispensationView, pharmacyApi } from '@/lib/api/pharmacy';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDateTime } from '@/lib/format';

export interface ReturnDialogProps {
  dispensation: DispensationView | null;
  onClose: () => void;
}

/** Return part or all of a dispensation; billing is corrected by reversal on the server. */
export function ReturnDialog({ dispensation, onClose }: ReturnDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const remaining = dispensation ? dispensation.quantity - dispensation.returnedQuantity : 0;
  const schema = z.object({
    quantity: z.coerce.number().int('Whole units only').min(1, 'At least 1').max(Math.max(remaining, 1), `At most ${remaining}`),
    reason: z.string().trim().min(3, 'Document a reason (at least 3 characters)').max(450),
    damaged: z.boolean(),
  });
  type Values = z.infer<typeof schema>;
  const { control, handleSubmit, reset, setError, watch } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { quantity: remaining || 1, reason: '', damaged: false },
  });
  useEffect(() => {
    if (dispensation) reset({ quantity: dispensation.quantity - dispensation.returnedQuantity, reason: '', damaged: false });
  }, [dispensation, reset]);
  const damaged = watch('damaged');

  const m = useMutation({
    mutationFn: (v: Values) => pharmacyApi.returnStock(dispensation!.id, v),
    onSuccess: (res) => {
      toast.success(`Return recorded · ${res.status === 'RETURNED' ? 'fully returned' : `${res.netQuantity} ${res.dispenseUnit ?? ''} remain supplied`}`);
      queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
      queryClient.invalidateQueries({ queryKey: ['medications'] });
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setError)) toast.error(e);
    },
  });

  return (
    <Dialog open={!!dispensation} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="return-title">
      <DialogTitle id="return-title">Record a return</DialogTitle>
      <DialogContent>
        {dispensation && (
          <Stack spacing={4} component="form" id="return-form" onSubmit={handleSubmit((v) => m.mutate(v))} noValidate sx={{ pt: 1 }}>
            <Typography variant="body2" color="text.secondary">
              {dispensation.medicationName} for {dispensation.patientName} ({dispensation.patientMrn}) · dispensed{' '}
              {formatDateTime(dispensation.dispensedAt)} from batch {dispensation.batchNumber}. {remaining} of {dispensation.quantity}{' '}
              {dispensation.dispenseUnit} can still be returned.
            </Typography>
            <Controller
              control={control}
              name="quantity"
              render={({ field, fieldState }) => (
                <TextField {...field} type="number" label={`Quantity returned (${dispensation.dispenseUnit ?? 'units'})`} required error={!!fieldState.error} helperText={fieldState.error?.message ?? ' '} slotProps={{ htmlInput: { min: 1, max: remaining } }} />
              )}
            />
            <Controller
              control={control}
              name="reason"
              render={({ field, fieldState }) => (
                <TextField {...field} label="Reason" required multiline minRows={2} error={!!fieldState.error} helperText={fieldState.error?.message ?? ' '} />
              )}
            />
            <Controller
              control={control}
              name="damaged"
              render={({ field }) => (
                <FormControlLabel
                  control={<Checkbox checked={field.value} onChange={(e) => field.onChange(e.target.checked)} />}
                  label="Damaged or contaminated: do not restock"
                />
              )}
            />
            <Alert severity="info">
              {damaged
                ? 'The units are recorded as returned and then written off as damage; stock on hand does not change.'
                : `The units go back into batch ${dispensation.batchNumber}.`}{' '}
              The original pharmacy charge is reversed and any remaining quantity is re-posted at the original price.
            </Alert>
            {m.error && !(m.error as { fieldErrors?: unknown[] }).fieldErrors?.length && <Alert severity="error">{errorMessage(m.error)}</Alert>}
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="return-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : 'Record return'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

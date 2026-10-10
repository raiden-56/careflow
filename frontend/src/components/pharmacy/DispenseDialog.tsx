'use client';

import { useEffect, useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, Controller } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  FormControlLabel,
  FormHelperText,
  Radio,
  RadioGroup,
  Skeleton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { ApiError } from '@/lib/api/client';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatDate, formatINR } from '@/lib/format';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { SectionCard } from '@/components/ui/SectionCard';
import { ErrorState } from '@/components/ui/States';
import { OrderSummary } from './OrderSummary';
import { WarningList } from './PharmacyBits';
import { colors } from '@/theme/tokens';

const schema = z.object({
  batchId: z.string().min(1, 'Choose a batch'),
  quantity: z.coerce.number().int('Whole units only').min(1, 'At least 1').max(1000, 'At most 1000 per dispensation'),
  notes: z.string().trim().max(1000, 'At most 1000 characters').optional(),
  safetyChecksConfirmed: z.boolean(),
});
type FormValues = z.infer<typeof schema>;

export interface DispenseDialogProps {
  orderId: UUID | null;
  onClose: () => void;
}

/** Dispense against a verified prescription from a chosen batch (FEFO suggested). */
export function DispenseDialog({ orderId, onClose }: DispenseDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const detail = useQuery({
    queryKey: pharmacyKeys.order(orderId ?? 'none'),
    queryFn: () => pharmacyApi.order(orderId!),
    enabled: !!orderId,
  });
  const d = detail.data;
  const item = d?.item;
  const needsSafety = !!item && (item.isHighAlert || item.isControlled || item.warnings.some((w) => w.type === 'ALLERGY'));
  const blocked = !!item && (!item.verified || !!item.openQuery);

  const form = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { batchId: '', quantity: 1, notes: '', safetyChecksConfirmed: false },
  });
  const { control, handleSubmit, reset, watch, setError, formState } = form;

  useEffect(() => {
    if (d) {
      const suggested = d.batches.find((b) => b.suggested) ?? d.batches[0];
      reset({
        batchId: suggested?.batchId ?? '',
        quantity: d.item.suggestedQuantity ?? 1,
        notes: '',
        safetyChecksConfirmed: false,
      });
    }
  }, [d, reset]);

  const batchId = watch('batchId');
  const quantity = Number(watch('quantity'));
  const selected = useMemo(() => d?.batches.find((b) => b.batchId === batchId), [d, batchId]);
  const overStock = !!selected && quantity > selected.quantityOnHand;

  const mutation = useMutation({
    mutationFn: (v: FormValues) =>
      pharmacyApi.dispense({
        orderId: orderId!,
        batchId: v.batchId,
        quantity: v.quantity,
        notes: v.notes || undefined,
        safetyChecksConfirmed: v.safetyChecksConfirmed,
      }),
    onSuccess: (res) => {
      toast.success(
        `Dispensed ${res.quantity} ${res.dispenseUnit ?? ''} of ${res.medicationName}${d?.admission.location ? ' · ward nurses notified' : ''}`,
      );
      queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
      queryClient.invalidateQueries({ queryKey: ['medications'] });
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setError)) toast.error(e);
      queryClient.invalidateQueries({ queryKey: pharmacyKeys.order(orderId!) });
    },
  });

  const submit = handleSubmit((v) => {
    if (needsSafety && !v.safetyChecksConfirmed) {
      setError('safetyChecksConfirmed', { type: 'manual', message: 'Confirm the safety checks to continue' });
      return;
    }
    mutation.mutate(v);
  });

  return (
    <Dialog open={!!orderId} onClose={mutation.isPending ? undefined : onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="dispense-title">
      <DialogTitle id="dispense-title">Dispense medication</DialogTitle>
      <DialogContent dividers>
        {detail.isLoading && (
          <Stack spacing={3}>
            <Skeleton height={90} />
            <Skeleton height={160} />
          </Stack>
        )}
        {detail.error && <ErrorState error={detail.error} onRetry={() => detail.refetch()} />}
        {d && item && (
          <Stack spacing={4}>
            <PatientBanner admission={d.admission} />
            <Box sx={{ mt: -3 }}>
              <OrderSummary item={item} />
            </Box>
            <WarningList warnings={item.warnings} />
            {blocked && (
              <Alert severity="warning">
                {item.openQuery
                  ? 'A query to the prescriber is still open. Resolve it before dispensing.'
                  : 'This prescription has not been verified by a pharmacist yet. Verify it from the prescription queue first.'}
              </Alert>
            )}
            <Divider />
            <Box component="form" id="dispense-form" onSubmit={submit} noValidate>
              <Stack spacing={4}>
                <Box>
                  <Typography variant="subtitle2" sx={{ mb: 1 }}>
                    Batch (earliest usable expiry first)
                  </Typography>
                  {d.batches.length === 0 ? (
                    <Alert severity="error">No usable stock: every batch is empty, expired or quarantined.</Alert>
                  ) : (
                    <Controller
                      control={control}
                      name="batchId"
                      render={({ field, fieldState }) => (
                        <>
                          <RadioGroup {...field} aria-label="Batch">
                            {d.batches.map((b) => (
                              <FormControlLabel
                                key={b.batchId}
                                value={b.batchId}
                                control={<Radio size="small" />}
                                label={
                                  <Typography variant="body2" component="span">
                                    <strong>{b.batchNumber}</strong> · expires {formatDate(b.expiryDate)} ({b.daysToExpiry} days) ·{' '}
                                    {b.quantityOnHand} on hand{b.suggested ? ' · FEFO suggestion' : ''}
                                  </Typography>
                                }
                              />
                            ))}
                          </RadioGroup>
                          {fieldState.error && <FormHelperText error>{fieldState.error.message}</FormHelperText>}
                        </>
                      )}
                    />
                  )}
                </Box>
                <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
                  <Controller
                    control={control}
                    name="quantity"
                    render={({ field, fieldState }) => (
                      <TextField
                        {...field}
                        type="number"
                        label={`Quantity (${item.dispenseUnit})`}
                        required
                        error={!!fieldState.error || overStock}
                        helperText={
                          fieldState.error?.message ??
                          (overStock
                            ? `Only ${selected?.quantityOnHand} in this batch`
                            : item.suggestedQuantity
                              ? `Suggested for ${d.supplyCycleHours} h: ${item.suggestedQuantityBasis}`
                              : item.suggestedQuantityBasis)
                        }
                        slotProps={{ htmlInput: { min: 1, max: 1000, step: 1 } }}
                        sx={{ flex: 1 }}
                      />
                    )}
                  />
                  <Controller
                    control={control}
                    name="notes"
                    render={({ field, fieldState }) => (
                      <TextField {...field} label="Notes (optional)" error={!!fieldState.error} helperText={fieldState.error?.message ?? ' '} sx={{ flex: 1 }} />
                    )}
                  />
                </Stack>
                {needsSafety && (
                  <Controller
                    control={control}
                    name="safetyChecksConfirmed"
                    render={({ field, fieldState }) => (
                      <Box sx={{ p: 3, borderRadius: 2, bgcolor: colors.warningSoft, border: 1, borderColor: colors.border }}>
                        <FormControlLabel
                          control={<Checkbox checked={field.value} onChange={(e) => field.onChange(e.target.checked)} />}
                          label="I have completed the required safety checks (allergy review, independent double check, controlled-drug register) for this medication."
                        />
                        {fieldState.error && <FormHelperText error>{fieldState.error.message}</FormHelperText>}
                      </Box>
                    )}
                  />
                )}
                <Typography variant="caption" color="text.secondary">
                  Unit price {formatINR(item.unitPrice)}. The pharmacy charge is calculated and posted to the patient&apos;s invoice by the server.
                  Dispensing supplies the ward only; administration is recorded by nursing on the MAR.
                </Typography>
                {mutation.error instanceof ApiError && mutation.error.fieldErrors.length === 0 && (
                  <Alert severity="error">{mutation.error.message}</Alert>
                )}
              </Stack>
            </Box>
            {d.dispensations.length > 0 && (
              <SectionCard title="Previous supplies" sx={{ boxShadow: 'none', border: 1, borderColor: 'divider' }}>
                <Stack spacing={1}>
                  {d.dispensations.slice(0, 5).map((x) => (
                    <Typography key={x.id} variant="body2">
                      {formatDate(x.dispensedAt, 'DD MMM, HH:mm')} · {x.netQuantity} {x.dispenseUnit} (batch {x.batchNumber})
                      {x.returnedQuantity > 0 ? ` · ${x.returnedQuantity} returned` : ''} · {x.dispensedByName}
                    </Typography>
                  ))}
                </Stack>
              </SectionCard>
            )}
          </Stack>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, py: 3 }}>
        <Button color="inherit" onClick={onClose} disabled={mutation.isPending}>
          Cancel
        </Button>
        <Button
          type="submit"
          form="dispense-form"
          variant="contained"
          disabled={!d || blocked || d.batches.length === 0 || overStock || mutation.isPending || formState.isSubmitting}
        >
          {mutation.isPending ? 'Dispensing…' : 'Dispense'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

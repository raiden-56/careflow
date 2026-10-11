'use client';

import { useEffect, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Stack, Typography } from '@mui/material';
import { ApiError, newIdempotencyKey } from '@/lib/api/client';
import { billingApi, METHOD_LABEL, PAYMENT_METHODS, type PaymentMethod, type PaymentResult } from '@/lib/api/billing';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatINR } from '@/lib/format';
import { moneyString } from '@/lib/validation';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z
  .object({
    amount: moneyString,
    method: z.enum(['CASH', 'CARD', 'UPI', 'NET_BANKING', 'CHEQUE', 'DEMAND_DRAFT', 'ONLINE']),
    methodReference: z.string().trim().max(100).optional(),
    payerName: z.string().trim().min(2, 'Enter who is paying').max(200),
    notes: z.string().trim().max(500).optional(),
    allowAdvance: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (v.method !== 'CASH' && !v.methodReference) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['methodReference'], message: 'Reference is required for non-cash payments' });
    }
  });
type Values = z.infer<typeof schema>;

const REFERENCE_HINT: Record<PaymentMethod, string> = {
  CASH: 'Optional',
  CARD: 'POS approval code',
  UPI: 'UPI transaction ID',
  NET_BANKING: 'Bank reference / UTR',
  CHEQUE: 'Cheque number and bank',
  DEMAND_DRAFT: 'DD number and bank',
  ONLINE: 'Gateway transaction ID',
};

export interface PaymentTarget {
  id: string;
  invoiceNumber: string;
  patientName: string;
  balance: string;
}

/**
 * Records a payment. A single idempotency key is created when the dialog opens and reused for every
 * retry of the same payment, so a double click or network retry can never create a duplicate receipt.
 */
export function PaymentDialog({
  open,
  invoice,
  defaultPayer,
  onClose,
  onRecorded,
}: {
  open: boolean;
  invoice: PaymentTarget | null;
  defaultPayer?: string;
  onClose: () => void;
  onRecorded?: (r: PaymentResult) => void;
}) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [key, setKey] = useState(() => newIdempotencyKey());
  const [error, setError] = useState<string | null>(null);
  const [overpaid, setOverpaid] = useState(false);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { amount: '', method: 'CASH', methodReference: '', payerName: '', notes: '', allowAdvance: false },
  });
  const method = useWatch({ control, name: 'method' });

  useEffect(() => {
    if (open) {
      setKey(newIdempotencyKey());
      setError(null);
      setOverpaid(false);
      const due = invoice && Number(invoice.balance) > 0 ? invoice.balance : '';
      reset({ amount: due, method: 'CASH', methodReference: '', payerName: defaultPayer ?? invoice?.patientName ?? '', notes: '', allowAdvance: false });
    }
  }, [open, invoice, defaultPayer, reset]);

  const submit = handleSubmit(async (v) => {
    if (!invoice) return;
    setError(null);
    try {
      const r = await billingApi.recordPayment(invoice.id, {
        amount: v.amount,
        method: v.method,
        methodReference: v.methodReference || null,
        payerName: v.payerName,
        idempotencyKey: key,
        allowAdvance: v.allowAdvance,
        notes: v.notes || null,
      });
      toast.success(
        r.replayed
          ? `Payment already recorded - receipt ${r.payment.receiptNumber}`
          : `Payment recorded - receipt ${r.payment.receiptNumber}`,
      );
      queryClient.invalidateQueries({ queryKey: ['invoice', invoice.id] });
      queryClient.invalidateQueries({ queryKey: ['invoices'] });
      queryClient.invalidateQueries({ queryKey: ['payments'] });
      queryClient.invalidateQueries({ queryKey: ['billing'] });
      onRecorded?.(r);
      onClose();
    } catch (e) {
      if (e instanceof ApiError && e.code === 'OVERPAYMENT') setOverpaid(true);
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });

  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="pay-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="pay-title">Record payment</DialogTitle>
        <DialogContent>
          {invoice && (
            <Box sx={{ mb: 4, p: 3, borderRadius: 2, bgcolor: 'action.hover' }}>
              <Stack direction="row" justifyContent="space-between" spacing={2} flexWrap="wrap" useFlexGap>
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {invoice.patientName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    Invoice {invoice.invoiceNumber}
                  </Typography>
                </Box>
                <Box sx={{ textAlign: 'right' }}>
                  <Typography variant="caption" color="text.secondary">
                    Outstanding now
                  </Typography>
                  <Typography variant="body1" fontWeight={700} sx={{ fontVariantNumeric: 'tabular-nums' }}>
                    {formatINR(invoice.balance)}
                  </Typography>
                </Box>
              </Stack>
            </Box>
          )}
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="amount" label="Amount (INR)" required fullWidth autoFocus slotProps={{ htmlInput: { inputMode: 'decimal' } }} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="method" label="Method" required fullWidth options={PAYMENT_METHODS.map((m) => ({ value: m, label: METHOD_LABEL[m] }))} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="methodReference" label="Reference" fullWidth helperText={REFERENCE_HINT[method]} required={method !== 'CASH'} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="payerName" label="Received from" required fullWidth />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="notes" label="Notes" fullWidth multiline minRows={2} />
            </Grid>
            <Grid size={12}>
              <RHFCheckbox
                control={control}
                name="allowAdvance"
                label="Accept any amount above the outstanding balance as an advance deposit"
                helperText="The excess stays on the invoice as a credit balance until it is used or refunded."
              />
            </Grid>
          </Grid>
          {overpaid && (
            <Alert severity="warning" sx={{ mt: 3 }}>
              This amount is more than what is owed. Reduce it, or tick the advance deposit option if the patient is paying in advance.
            </Alert>
          )}
          {error && !overpaid && (
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
            {formState.isSubmitting ? 'Recording…' : 'Record payment'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

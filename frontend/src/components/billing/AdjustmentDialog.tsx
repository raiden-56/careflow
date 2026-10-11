'use client';

import { useEffect, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid } from '@mui/material';
import { ADJUSTMENT_LABEL, billingApi, METHOD_LABEL, PAYMENT_METHODS, type InvoiceDetail, type PaymentMethod } from '@/lib/api/billing';
import { applyServerErrors } from '@/lib/hooks';
import { formatDate, formatINR } from '@/lib/format';
import { moneyString } from '@/lib/validation';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z
  .object({
    type: z.enum(['DISCOUNT', 'WRITE_OFF', 'CREDIT_NOTE', 'REFUND', 'DEBIT_NOTE']),
    amount: moneyString,
    reason: z.string().trim().min(3, 'Document the reason').max(1000),
    relatedPaymentId: z.string().optional(),
    refundMethod: z.string().optional(),
  })
  .superRefine((v, ctx) => {
    if (v.type === 'REFUND') {
      if (!v.relatedPaymentId) ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['relatedPaymentId'], message: 'Select the payment being refunded' });
      if (!v.refundMethod) ctx.addIssue({ code: z.ZodIssueCode.custom, path: ['refundMethod'], message: 'Select how the refund is paid out' });
    }
  });
type Values = z.infer<typeof schema>;

const HELP: Record<Values['type'], string> = {
  DISCOUNT: 'Reduces the balance (concession). Cannot exceed the outstanding balance.',
  WRITE_OFF: 'Waives an amount that will not be collected. Cannot exceed the outstanding balance.',
  CREDIT_NOTE: 'Credits the patient (e.g. billing correction). Cannot exceed the outstanding balance.',
  DEBIT_NOTE: 'Adds an amount owed that is not a tariff charge.',
  REFUND: 'Money paid back to the payer. Limited to net payments received less refunds already issued or pending.',
};

/** Requests an adjustment. It only affects the balance after a different authorized person approves it. */
export function AdjustmentDialog({ open, invoice, onClose, onDone }: { open: boolean; invoice: InvoiceDetail; onClose: () => void; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { type: 'DISCOUNT', amount: '', reason: '', relatedPaymentId: '', refundMethod: '' },
  });
  const type = useWatch({ control, name: 'type' });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({ type: 'DISCOUNT', amount: '', reason: '', relatedPaymentId: '', refundMethod: '' });
    }
  }, [open, reset]);
  const payments = invoice.payments.filter((p) => p.status === 'COMPLETED');

  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      await billingApi.requestAdjustment(invoice.id, {
        type: v.type,
        amount: v.amount,
        reason: v.reason,
        relatedPaymentId: v.type === 'REFUND' ? v.relatedPaymentId || null : null,
        refundMethod: v.type === 'REFUND' && v.refundMethod ? (v.refundMethod as PaymentMethod) : null,
      });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });

  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="adj-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="adj-title">Request adjustment</DialogTitle>
        <DialogContent>
          <Alert severity="info" sx={{ mb: 4 }}>
            Adjustments need approval by a different authorized person before they change the balance.
          </Alert>
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="type"
                label="Type"
                fullWidth
                options={(['DISCOUNT', 'WRITE_OFF', 'CREDIT_NOTE', 'DEBIT_NOTE', 'REFUND'] as const).map((t) => ({ value: t, label: ADJUSTMENT_LABEL[t] }))}
                helperText={HELP[type]}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField
                control={control}
                name="amount"
                label="Amount (INR)"
                required
                fullWidth
                helperText={
                  type === 'REFUND'
                    ? `Refundable now: ${formatINR(invoice.refundableAmount)}`
                    : type === 'DEBIT_NOTE'
                      ? ' '
                      : `Outstanding: ${formatINR(invoice.summary.balance)}`
                }
              />
            </Grid>
            {type === 'REFUND' && (
              <>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <RHFSelect
                    control={control}
                    name="relatedPaymentId"
                    label="Payment being refunded"
                    fullWidth
                    required
                    emptyLabel={payments.length ? 'Select a receipt' : 'No completed payments'}
                    options={payments.map((p) => ({
                      value: p.id,
                      label: `${p.receiptNumber} · ${formatINR(p.amount)} · ${formatDate(p.receivedAt)}`,
                    }))}
                  />
                </Grid>
                <Grid size={{ xs: 12, sm: 6 }}>
                  <RHFSelect
                    control={control}
                    name="refundMethod"
                    label="Refund paid by"
                    fullWidth
                    required
                    emptyLabel="Select method"
                    options={PAYMENT_METHODS.map((m) => ({ value: m, label: METHOD_LABEL[m] }))}
                  />
                </Grid>
              </>
            )}
            <Grid size={12}>
              <RHFTextField control={control} name="reason" label="Reason" required fullWidth multiline minRows={2} />
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
            Submit for approval
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

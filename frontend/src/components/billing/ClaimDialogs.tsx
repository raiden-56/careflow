'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Stack } from '@mui/material';
import { billingApi, type ClaimView } from '@/lib/api/billing';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { formatINR } from '@/lib/format';
import { moneyString } from '@/lib/validation';
import { useAuth } from '@/lib/auth/AuthProvider';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';

const createSchema = z.object({
  payerName: z.string().trim().min(2, 'Payer is required').max(200),
  payerType: z.enum(['INSURER', 'TPA', 'CORPORATE', 'GOVERNMENT']),
  policyNumber: z.string().trim().min(2, 'Policy / member number is required').max(100),
  preAuthNumber: z.string().trim().max(100).optional(),
  claimedAmount: moneyString,
  notes: z.string().trim().max(1000).optional(),
});
type CreateValues = z.infer<typeof createSchema>;

export function CreateClaimDialog({ open, invoiceId, onClose, onDone }: { open: boolean; invoiceId: string; onClose: () => void; onDone: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<CreateValues>({
    resolver: zodResolver(createSchema),
    defaultValues: { payerName: '', payerType: 'INSURER', policyNumber: '', preAuthNumber: '', claimedAmount: '', notes: '' },
  });
  useEffect(() => {
    if (open) {
      setError(null);
      reset();
    }
  }, [open, reset]);
  const submit = handleSubmit(async (v) => {
    setError(null);
    try {
      await billingApi.createClaim(invoiceId, { ...v, preAuthNumber: v.preAuthNumber || null, notes: v.notes || null });
      onDone();
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });
  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="claim-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="claim-title">New insurance / third-party claim</DialogTitle>
        <DialogContent>
          <Grid container spacing={3} sx={{ pt: 1 }}>
            <Grid size={{ xs: 12, sm: 8 }}>
              <RHFTextField control={control} name="payerName" label="Payer" required fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFSelect
                control={control}
                name="payerType"
                label="Payer type"
                fullWidth
                options={[
                  { value: 'INSURER', label: 'Insurer' },
                  { value: 'TPA', label: 'TPA' },
                  { value: 'CORPORATE', label: 'Corporate' },
                  { value: 'GOVERNMENT', label: 'Government scheme' },
                ]}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="policyNumber" label="Policy / member no." required fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="preAuthNumber" label="Pre-authorisation no." fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="claimedAmount" label="Claimed amount (INR)" required fullWidth />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="notes" label="Notes" fullWidth multiline minRows={2} />
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
            Create draft claim
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

const amountSchema = z.object({
  amount: moneyString,
  reference: z.string().trim().max(100).optional(),
  notes: z.string().trim().max(1000).optional(),
});
type AmountValues = z.infer<typeof amountSchema>;

function ClaimAmountDialog({
  open,
  mode,
  claim,
  onClose,
  onDone,
}: {
  open: boolean;
  mode: 'approve' | 'settle';
  claim: ClaimView;
  onClose: () => void;
  onDone: (c: ClaimView) => void;
}) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError, formState } = useForm<AmountValues>({
    resolver: zodResolver(amountSchema),
    defaultValues: { amount: '', reference: '', notes: '' },
  });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({ amount: mode === 'approve' ? claim.claimedAmount : claim.pendingSettlement, reference: '', notes: '' });
    }
  }, [open, reset, mode, claim]);
  const submit = handleSubmit(async (v) => {
    setError(null);
    if (mode === 'settle' && !v.reference) {
      setFieldError('reference', { message: 'Enter the payment reference (UTR / cheque no.)' });
      return;
    }
    try {
      const c =
        mode === 'approve'
          ? await billingApi.approveClaim(claim.id, v.amount, v.notes || undefined)
          : await billingApi.settleClaim(claim.id, { amount: v.amount, reference: v.reference!, notes: v.notes || null });
      onDone(c);
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  });
  return (
    <Dialog open={open} onClose={formState.isSubmitting ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="claim-amount-title">
      <form onSubmit={submit} noValidate>
        <DialogTitle id="claim-amount-title">{mode === 'approve' ? `Record approval - ${claim.claimNumber}` : `Record settlement - ${claim.claimNumber}`}</DialogTitle>
        <DialogContent>
          <Alert severity="info" sx={{ mb: 3 }}>
            {mode === 'approve'
              ? `Claimed ${formatINR(claim.claimedAmount)}. An amount below the claim is recorded as partially approved. Approval does not change the balance until settlement.`
              : `Approved, pending settlement: ${formatINR(claim.pendingSettlement)}. Each settlement posts its own ledger entry; part payments are allowed.`}
          </Alert>
          <Stack spacing={3}>
            <RHFTextField control={control} name="amount" label={mode === 'approve' ? 'Approved amount (INR)' : 'Amount received (INR)'} required fullWidth />
            {mode === 'settle' && <RHFTextField control={control} name="reference" label="Payment reference (UTR / cheque no.)" required fullWidth />}
            <RHFTextField control={control} name="notes" label="Notes" fullWidth multiline minRows={2} />
          </Stack>
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
            {mode === 'approve' ? 'Record approval' : 'Record settlement'}
          </Button>
        </DialogActions>
      </form>
    </Dialog>
  );
}

/** Lifecycle buttons for a claim, shown according to its status and the user's permission. */
export function ClaimActions({ claim, size = 'small' }: { claim: ClaimView; size?: 'small' | 'medium' }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<'approve' | 'settle' | 'reject' | null>(null);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['claims'] });
    queryClient.invalidateQueries({ queryKey: ['invoice', claim.invoiceId] });
    queryClient.invalidateQueries({ queryKey: ['invoices'] });
    queryClient.invalidateQueries({ queryKey: ['billing'] });
  };
  const simple = useMutation({
    mutationFn: (action: 'submit' | 'review') => (action === 'submit' ? billingApi.submitClaim(claim.id) : billingApi.reviewClaim(claim.id)),
    onSuccess: (c) => {
      toast.success(`Claim ${c.claimNumber} ${c.status === 'SUBMITTED' ? 'submitted' : 'marked under review'}`);
      refresh();
    },
    onError: toast.error,
  });
  const reject = useMutation({
    mutationFn: (reason: string) => billingApi.rejectClaim(claim.id, reason),
    onSuccess: () => {
      toast.success('Claim marked as rejected');
      setDialog(null);
      refresh();
    },
    onError: toast.error,
  });
  if (!hasPermission('INSURANCE_MANAGE')) return null;
  const s = claim.status;
  return (
    <>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
        {s === 'DRAFT' && (
          <Button size={size} variant="outlined" disabled={simple.isPending} onClick={() => simple.mutate('submit')}>
            Submit
          </Button>
        )}
        {s === 'SUBMITTED' && (
          <Button size={size} variant="outlined" disabled={simple.isPending} onClick={() => simple.mutate('review')}>
            Under review
          </Button>
        )}
        {(s === 'SUBMITTED' || s === 'UNDER_REVIEW') && (
          <>
            <Button size={size} variant="contained" onClick={() => setDialog('approve')}>
              Approve
            </Button>
            <Button size={size} color="error" onClick={() => setDialog('reject')}>
              Reject
            </Button>
          </>
        )}
        {(s === 'APPROVED' || s === 'PARTIALLY_APPROVED') && Number(claim.pendingSettlement) > 0 && (
          <Button size={size} variant="contained" onClick={() => setDialog('settle')}>
            Record settlement
          </Button>
        )}
      </Stack>
      {(dialog === 'approve' || dialog === 'settle') && (
        <ClaimAmountDialog
          open
          mode={dialog}
          claim={claim}
          onClose={() => setDialog(null)}
          onDone={(c) => {
            toast.success(dialog === 'approve' ? `Approval recorded (${formatINR(c.approvedAmount)})` : 'Settlement recorded and posted to the ledger');
            setDialog(null);
            refresh();
          }}
        />
      )}
      <ConfirmDialog
        open={dialog === 'reject'}
        title={`Reject claim ${claim.claimNumber}?`}
        description="Record the payer's rejection. The patient will be informed; the balance is unchanged."
        requireReason
        reasonLabel="Rejection reason from the payer"
        confirmLabel="Mark rejected"
        tone="error"
        loading={reject.isPending}
        onConfirm={(r) => reject.mutate(r!)}
        onClose={() => setDialog(null)}
      />
    </>
  );
}

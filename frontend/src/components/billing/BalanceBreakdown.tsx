'use client';

import { Box, Divider, Stack, Tooltip, Typography } from '@mui/material';
import { Info } from 'lucide-react';
import type { BalanceSummary } from '@/lib/api/billing';
import { formatINR, isNegative } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { StatusChip } from '@/components/ui/StatusChip';

export interface BreakdownLine {
  label: string;
  amount: string;
  operator: '+' | '-' | '=';
  hint?: string;
}

/** Turns the server's balance summary into ordered display lines. Values are shown as returned (no arithmetic). */
export function summaryLines(s: BalanceSummary): BreakdownLine[] {
  const lines: BreakdownLine[] = [{ label: 'Posted charges', amount: s.grossCharges, operator: '+' }];
  const add = (label: string, amount: string, operator: '+' | '-', hint?: string) => {
    if (Number(amount) !== 0) lines.push({ label, amount, operator, hint });
  };
  add('Charge reversals', s.chargeReversals, '-');
  add('Debit notes', s.debitNotes, '+');
  add('Payments received', s.payments, '-');
  add('Payment reversals', s.paymentReversals, '+', 'e.g. bounced cheques');
  add('Refunds paid out', s.refunds, '+');
  add('Discounts', s.discounts, '-');
  add('Write-offs', s.writeOffs, '-');
  add('Credit notes', s.creditNotes, '-');
  add('Insurance settlements', s.insuranceSettled, '-');
  return lines;
}

/** A calculation table: each line with its operator and the server-computed result. */
export function BalanceBreakdown({
  lines,
  balance,
  balanceLabel,
  paymentStatus,
  insurancePending,
  estimates,
  formula,
  dense,
}: {
  lines: BreakdownLine[];
  balance: string;
  balanceLabel?: string;
  paymentStatus?: string;
  insurancePending?: string;
  estimates?: string;
  formula?: string;
  dense?: boolean;
}) {
  const credit = isNegative(balance);
  return (
    <Box>
      <Stack spacing={dense ? 1.5 : 2} component="dl" sx={{ m: 0 }}>
        {lines.map((l) => (
          <Stack key={l.label} direction="row" justifyContent="space-between" alignItems="baseline" spacing={3}>
            <Stack direction="row" spacing={1.5} alignItems="baseline" sx={{ minWidth: 0 }}>
              <Box
                component="span"
                aria-hidden
                sx={{ width: 16, flexShrink: 0, textAlign: 'center', color: 'text.secondary', fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}
              >
                {l.operator === '+' ? '+' : '−'}
              </Box>
              <Typography component="dt" variant="body2" color="text.secondary" sx={{ minWidth: 0 }}>
                {l.label}
                {l.hint && (
                  <Typography component="span" variant="caption" color="text.secondary" sx={{ display: 'block' }}>
                    {l.hint}
                  </Typography>
                )}
              </Typography>
            </Stack>
            <Typography component="dd" variant="body2" sx={{ m: 0, fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
              {l.operator === '-' ? `− ${formatINR(l.amount)}` : formatINR(l.amount)}
            </Typography>
          </Stack>
        ))}
      </Stack>
      <Divider sx={{ my: 3 }} />
      <Stack direction="row" justifyContent="space-between" alignItems="center" spacing={3}>
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography variant="subtitle1" fontWeight={700}>
            {balanceLabel ?? (credit ? 'Credit balance' : 'Outstanding balance')}
          </Typography>
          {paymentStatus && <StatusChip value={paymentStatus} />}
        </Stack>
        <Typography
          variant="h3"
          component="p"
          sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', color: credit ? colors.success : undefined }}
        >
          {formatINR(credit ? balance.replace('-', '') : balance)}
        </Typography>
      </Stack>
      {(insurancePending && Number(insurancePending) > 0) || (estimates && Number(estimates) > 0) ? (
        <Stack spacing={1.5} sx={{ mt: 3, p: 3, borderRadius: 2, bgcolor: colors.surfaceAlt, border: `1px dashed ${colors.borderStrong}` }}>
          <Typography variant="caption" color="text.secondary" fontWeight={600}>
            Shown separately - not part of the balance
          </Typography>
          {insurancePending && Number(insurancePending) > 0 && (
            <Stack direction="row" justifyContent="space-between" spacing={2}>
              <Typography variant="body2">Insurance approved, pending settlement</Typography>
              <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatINR(insurancePending)}
              </Typography>
            </Stack>
          )}
          {estimates && Number(estimates) > 0 && (
            <Stack direction="row" justifyContent="space-between" spacing={2}>
              <Typography variant="body2">Estimated upcoming charges</Typography>
              <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                {formatINR(estimates)}
              </Typography>
            </Stack>
          )}
        </Stack>
      ) : null}
      {formula && (
        <Stack direction="row" spacing={1.5} sx={{ mt: 3 }} alignItems="flex-start">
          <Tooltip title="Computed by the server from the append-only ledger">
            <Box component="span" sx={{ color: 'text.secondary', mt: '2px', display: 'inline-flex' }}>
              <Info size={14} />
            </Box>
          </Tooltip>
          <Typography variant="caption" color="text.secondary">
            {formula}
          </Typography>
        </Stack>
      )}
    </Box>
  );
}

'use client';

import { useState } from 'react';
import { Alert, Box, Button, Card, Stack, Typography } from '@mui/material';
import { BadgeIndianRupee, ShieldCheck, Stethoscope, Undo2 } from 'lucide-react';
import { dischargeApi, type FinancialClearance, type WorkflowView } from '@/lib/api/discharge';
import { formatDateTime, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { colors } from '@/theme/tokens';
import { errorMessage } from '@/lib/api/client';
import { useDischargeMutation } from './hooks';

function PanelShell({ icon, title, caption, children, tone }: { icon: React.ReactNode; title: string; caption: string; children: React.ReactNode; tone: string }) {
  return (
    <Card sx={{ height: '100%', borderTop: `3px solid ${tone}` }}>
      <Box sx={{ p: 4 }}>
        <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }}>
          <Box sx={{ color: tone, display: 'flex' }} aria-hidden>
            {icon}
          </Box>
          <Typography variant="h4" component="h2">
            {title}
          </Typography>
        </Stack>
        <Typography variant="caption" color="text.secondary" component="p" sx={{ mb: 3 }}>
          {caption}
        </Typography>
        {children}
      </Box>
    </Card>
  );
}

/** The clinician's discharge decision. Separate from (and never implied by) financial clearance. */
export function ClinicalDecisionCard({ workflow, canAuthorize }: { workflow: WorkflowView; canAuthorize: boolean }) {
  const [dialog, setDialog] = useState<'AUTHORIZE' | 'REVOKE' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const c = workflow.clinical;
  const open = workflow.status === 'IN_PROGRESS' || workflow.status === 'READY';
  const decide = useDischargeMutation(
    ({ decision, notes }: { decision: 'AUTHORIZE' | 'REVOKE'; notes: string }) => dischargeApi.clinicalDecision(workflow.id, decision, notes),
    {
      success: (r) => (r.workflow?.clinical.decision === 'AUTHORIZED' ? 'Discharge clinically authorized' : 'Authorization withdrawn'),
      onSuccess: () => setDialog(null),
      onError: (e) => setError(errorMessage(e)),
    },
  );
  return (
    <PanelShell
      icon={<Stethoscope size={18} />}
      title="Clinical decision"
      caption="Recorded only by the attending doctor or a doctor on the care team. Billing status never affects it."
      tone={colors.primary}
    >
      <Stack spacing={2}>
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
          {c.decision ? <StatusChip value={c.decision} /> : <StatusChip value="PENDING" label="Awaiting decision" tone="warning" />}
          {c.decidedByName && (
            <Typography variant="body2" color="text.secondary">
              {c.decidedByName} · {formatDateTime(c.decidedAt)}
            </Typography>
          )}
        </Stack>
        {c.notesVisible && c.notes && (
          <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
            {c.notes}
          </Typography>
        )}
        {!c.notesVisible && c.decision && (
          <Typography variant="caption" color="text.secondary">
            Clinical notes are visible to the care team only.
          </Typography>
        )}
        {canAuthorize && open && (
          <Stack direction="row" spacing={2} sx={{ pt: 1 }}>
            {c.decision !== 'AUTHORIZED' ? (
              <Button variant="contained" startIcon={<ShieldCheck size={16} />} onClick={() => { setError(null); setDialog('AUTHORIZE'); }}>
                Authorize discharge
              </Button>
            ) : (
              <Button variant="outlined" color="warning" startIcon={<Undo2 size={16} />} onClick={() => { setError(null); setDialog('REVOKE'); }}>
                Withdraw authorization
              </Button>
            )}
          </Stack>
        )}
      </Stack>
      <ConfirmDialog
        open={dialog !== null}
        title={dialog === 'AUTHORIZE' ? 'Authorize discharge' : 'Withdraw discharge authorization'}
        description={
          dialog === 'AUTHORIZE'
            ? 'This is your clinical decision that the patient is fit for discharge. It does not complete the discharge: remaining checklist steps still apply.'
            : 'The patient will not be discharged until a doctor authorizes again. The patient, ward nurses and billing are informed.'
        }
        requireReason
        reasonLabel={dialog === 'AUTHORIZE' ? 'Clinical basis for the decision' : 'Reason for withdrawing'}
        confirmLabel={dialog === 'AUTHORIZE' ? 'Authorize' : 'Withdraw'}
        tone={dialog === 'AUTHORIZE' ? 'primary' : 'warning'}
        loading={decide.isPending}
        error={error}
        onConfirm={(notes) => decide.mutate({ decision: dialog!, notes: notes! })}
        onClose={() => setDialog(null)}
      />
    </PanelShell>
  );
}

const SETTLED = ['PAID', 'NO_CHARGES', 'CREDIT_BALANCE'];

/** Administrative financial clearance (or authorized exception). Never a clinical reason to delay. */
export function FinancialClearanceCard({ workflow, canClear }: { workflow: WorkflowView; canClear: boolean }) {
  const [dialog, setDialog] = useState<FinancialClearance | null>(null);
  const [error, setError] = useState<string | null>(null);
  const f = workflow.financial;
  const open = workflow.status === 'IN_PROGRESS' || workflow.status === 'READY';
  const clear = useDischargeMutation(
    ({ type, reason }: { type: FinancialClearance; reason?: string }) => dischargeApi.financialClearance(workflow.id, type, reason),
    {
      success: (r) => (r.workflow?.financial.clearance === 'EXCEPTION' ? 'Authorized exception recorded' : 'Financial clearance recorded'),
      onSuccess: () => setDialog(null),
      onError: (e) => setError(errorMessage(e)),
    },
  );
  const settled = f.paymentStatus ? SETTLED.includes(f.paymentStatus) : !f.invoiceId;
  return (
    <PanelShell
      icon={<BadgeIndianRupee size={18} />}
      title="Financial clearance"
      caption={
        f.requiredForDischarge
          ? 'Administrative step recorded by billing. It never authorizes or delays a clinical decision.'
          : 'Hospital policy: financial clearance is not required before departure.'
      }
      tone={colors.warning}
    >
      <Stack spacing={2}>
        <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
          {f.clearance ? <StatusChip value={f.clearance} label={f.clearance === 'CLEARED' ? 'Cleared' : 'Authorized exception'} /> : <StatusChip value="PENDING" label="Pending" tone="warning" />}
          {f.clearedByName && (
            <Typography variant="body2" color="text.secondary">
              {f.clearedByName} · {formatDateTime(f.clearedAt)}
            </Typography>
          )}
        </Stack>
        <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
          <Typography variant="body2" component="span">
            Invoice {f.invoiceNumber ?? '—'}
          </Typography>
          {f.invoiceStatus && <StatusChip value={f.invoiceStatus} />}
        </Stack>
        {f.amountsVisible && (
          <Box>
            <Stack direction="row" spacing={1.5} alignItems="center" flexWrap="wrap" useFlexGap>
              <Typography variant="body2" component="span">
                Outstanding balance: <Money value={f.currentBalance} strong />
              </Typography>
              {f.paymentStatus && <StatusChip value={f.paymentStatus} />}
            </Stack>
            {f.balanceAtClearance && (
              <Typography variant="caption" color="text.secondary" component="div">
                Balance when cleared: <Money value={f.balanceAtClearance} />
              </Typography>
            )}
          </Box>
        )}
        {f.exceptionReason && (
          <Alert severity="warning" sx={{ py: 0 }}>
            Exception: {f.exceptionReason}
          </Alert>
        )}
        {canClear && open && (
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} sx={{ pt: 1 }}>
            <Button variant="contained" disabled={!settled} onClick={() => { setError(null); setDialog('CLEARED'); }}>
              Record clearance
            </Button>
            {f.exceptionAllowed && !settled && (
              <Button variant="outlined" color="warning" onClick={() => { setError(null); setDialog('EXCEPTION'); }}>
                Record authorized exception
              </Button>
            )}
          </Stack>
        )}
        {canClear && open && !settled && (
          <Typography variant="caption" color="text.secondary">
            Clearance needs a settled balance ({humanize(f.paymentStatus)}). Collect payment in billing, or record an authorized exception.
          </Typography>
        )}
      </Stack>
      <ConfirmDialog
        open={dialog !== null}
        title={dialog === 'EXCEPTION' ? 'Record authorized financial exception' : 'Record financial clearance'}
        description={
          dialog === 'EXCEPTION'
            ? 'The patient may leave with an outstanding balance under hospital policy. Hospital administrators are notified and the reason is audited.'
            : 'Confirms the ledger balance is settled for this admission.'
        }
        requireReason={dialog === 'EXCEPTION'}
        reasonLabel="Reason and authorization (at least 10 characters)"
        confirmLabel={dialog === 'EXCEPTION' ? 'Record exception' : 'Record clearance'}
        tone={dialog === 'EXCEPTION' ? 'warning' : 'primary'}
        loading={clear.isPending}
        error={error}
        onConfirm={(reason) => clear.mutate({ type: dialog!, reason })}
        onClose={() => setDialog(null)}
      />
    </PanelShell>
  );
}

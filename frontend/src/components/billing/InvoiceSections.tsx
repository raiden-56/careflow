'use client';

import { useState, type ReactNode } from 'react';
import Link from 'next/link';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Chip, IconButton, Stack, Tooltip, Typography } from '@mui/material';
import { BedDouble, Check, Download, Plus, Undo2, X } from 'lucide-react';
import {
  ADJUSTMENT_LABEL,
  billingApi,
  CATEGORY_LABEL,
  LEDGER_LABEL,
  METHOD_LABEL,
  REQUEST_TYPE_LABEL,
  type AdjustmentView,
  type InvoiceDetail,
  type LedgerView,
  type LineView,
  type PaymentView,
  type RequestView,
} from '@/lib/api/billing';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, formatINR, isNegative } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { DataTable, type DataTableProps } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState } from '@/components/ui/States';
import { Money } from '@/components/ui/Display';
import { AddChargeDialog, EstimateDialog } from './ChargeDialogs';
import { AdjustmentDialog } from './AdjustmentDialog';
import { ClaimActions, CreateClaimDialog } from './ClaimDialogs';
import { RespondDialog } from './RespondDialog';

function useRefresh(invoiceId: string) {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: ['invoice', invoiceId] });
    queryClient.invalidateQueries({ queryKey: ['invoices'] });
    queryClient.invalidateQueries({ queryKey: ['billing'] });
    queryClient.invalidateQueries({ queryKey: ['adjustments'] });
    queryClient.invalidateQueries({ queryKey: ['payments'] });
  };
}

function SectionToolbar({ children, note }: { children?: ReactNode; note?: ReactNode }) {
  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" alignItems={{ sm: 'center' }} sx={{ p: 4, pb: 3 }}>
      <Typography variant="body2" color="text.secondary" component="div" sx={{ minWidth: 0 }}>
        {note}
      </Typography>
      <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap sx={{ flexShrink: 0 }}>
        {children}
      </Stack>
    </Stack>
  );
}

// ------------------------------------------------------------------ charges

export function ChargesSection({ invoice }: { invoice: InvoiceDetail }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const refresh = useRefresh(invoice.id);
  const [adding, setAdding] = useState(false);
  const [reversing, setReversing] = useState<LineView | null>(null);
  const [roomWarnings, setRoomWarnings] = useState<string[]>([]);
  const open = invoice.status === 'OPEN';
  const canWrite = hasPermission('BILLING_WRITE') && open;
  const room = useMutation({
    mutationFn: () => billingApi.postRoomCharges(invoice.id),
    onSuccess: (r) => {
      setRoomWarnings(r.warnings);
      if (r.roomDaysPosted + r.nursingDaysPosted === 0) {
        toast.info(r.postedThrough ? `Room charges are already posted through ${formatDate(r.postedThrough)}` : 'No completed room days to charge yet');
      } else {
        toast.success(`Posted ${r.roomDaysPosted} room day(s) and ${r.nursingDaysPosted} nursing day(s): ${formatINR(r.amountPosted)}`);
      }
      refresh();
    },
    onError: toast.error,
  });
  const reverse = useMutation({
    mutationFn: ({ line, reason }: { line: LineView; reason: string }) => billingApi.reverseCharge(invoice.id, line.id, reason),
    onSuccess: () => {
      toast.success('Charge reversed');
      setReversing(null);
      refresh();
    },
    onError: toast.error,
  });
  const byId = new Map(invoice.lines.map((l) => [l.id, l]));

  return (
    <Box>
      <SectionToolbar
        note={
          <>
            Posted charges are never edited or deleted - corrections are reversal lines.
            {invoice.roomChargesPostedUntil && ` Room and nursing days posted through ${formatDate(invoice.roomChargesPostedUntil)}.`}
          </>
        }
      >
        {canWrite && invoice.admission && (
          <Button variant="outlined" size="small" startIcon={<BedDouble size={14} />} disabled={room.isPending} onClick={() => room.mutate()}>
            {room.isPending ? 'Posting…' : 'Post room charges'}
          </Button>
        )}
        {canWrite && (
          <Button variant="contained" size="small" startIcon={<Plus size={14} />} onClick={() => setAdding(true)}>
            Add charge
          </Button>
        )}
      </SectionToolbar>
      {roomWarnings.length > 0 && (
        <Alert severity="warning" sx={{ mx: 4, mb: 3 }} onClose={() => setRoomWarnings([])}>
          {roomWarnings.map((w) => (
            <div key={w}>{w}</div>
          ))}
        </Alert>
      )}
      {invoice.categoryTotals.length > 0 && (
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ px: 4, pb: 3 }}>
          {invoice.categoryTotals.map((c) => (
            <Chip key={c.category} variant="outlined" label={`${CATEGORY_LABEL[c.category]} · ${formatINR(c.amount)}`} />
          ))}
        </Stack>
      )}
      <DataTable<LineView>
        ariaLabel="Posted charges"
        rows={invoice.lines}
        rowKey={(l) => l.id}
        dense
        emptyTitle="No charges posted yet"
        emptyDescription={canWrite ? 'Add a charge from the tariff list or post room charges.' : undefined}
        rowSx={(l) => (l.status !== 'POSTED' ? { '& td': { color: colors.inkMuted } } : undefined)}
        columns={[
          {
            key: 'date',
            header: 'Date',
            width: 110,
            render: (l) => (
              <Tooltip title={`Posted by ${l.postedByName ?? 'system'}, ${formatDateTime(l.postedAt)}`}>
                <Box component="span" sx={{ whiteSpace: 'nowrap' }}>
                  {formatDate(l.serviceDate)}
                </Box>
              </Tooltip>
            ),
          },
          {
            key: 'desc',
            header: 'Description',
            primary: true,
            render: (l) => (
              <Box sx={{ minWidth: 0 }}>
                <Typography variant="body2" sx={{ textDecoration: l.status === 'REVERSED' ? 'line-through' : undefined }}>
                  {l.description}
                </Typography>
                <Typography variant="caption" color="text.secondary" component="div">
                  {CATEGORY_LABEL[l.category]}
                  {l.tariffCode ? ` · ${l.tariffCode}` : ''}
                </Typography>
                {l.reason && (
                  <Typography variant="caption" color="text.secondary" component="div">
                    {l.status === 'REVERSAL' ? 'Reason: ' : ''}
                    {l.reason}
                  </Typography>
                )}
                {l.reversesLineId && byId.get(l.reversesLineId) && (
                  <Typography variant="caption" color="warning.main" component="div">
                    Reverses the charge of {formatDate(byId.get(l.reversesLineId)!.serviceDate)}
                  </Typography>
                )}
              </Box>
            ),
          },
          {
            key: 'qty',
            header: 'Qty × price',
            align: 'right',
            hideOnMobile: true,
            render: (l) => <Box component="span" sx={{ whiteSpace: 'nowrap' }}>{`${Number(l.quantity)} × ${formatINR(l.unitPrice)}`}</Box>,
          },
          { key: 'tax', header: 'Tax', align: 'right', hideOnMobile: true, render: (l) => <Money value={l.taxAmount} /> },
          { key: 'total', header: 'Amount', align: 'right', render: (l) => <Money value={l.totalAmount} strong={l.status === 'POSTED'} /> },
          { key: 'status', header: 'Status', render: (l) => <StatusChip value={l.status} /> },
          {
            key: 'actions',
            header: '',
            align: 'right',
            render: (l) =>
              canWrite && l.status === 'POSTED' ? (
                <Tooltip title="Reverse this charge">
                  <IconButton size="small" aria-label={`Reverse ${l.description}`} onClick={() => setReversing(l)}>
                    <Undo2 size={16} />
                  </IconButton>
                </Tooltip>
              ) : null,
          },
        ]}
      />
      <AddChargeDialog
        open={adding}
        invoiceId={invoice.id}
        onClose={() => setAdding(false)}
        onDone={() => {
          toast.success('Charge posted');
          setAdding(false);
          refresh();
        }}
      />
      <ConfirmDialog
        open={!!reversing}
        title="Reverse this charge?"
        description={
          reversing && (
            <>
              <strong>{reversing.description}</strong> ({formatINR(reversing.totalAmount)}, {formatDate(reversing.serviceDate)}) will be offset by a
              negative reversal line. The original stays on record.
            </>
          )
        }
        requireReason
        reasonLabel="Reason for reversal"
        confirmLabel="Reverse charge"
        tone="warning"
        loading={reverse.isPending}
        onConfirm={(reason) => reversing && reverse.mutate({ line: reversing, reason: reason! })}
        onClose={() => setReversing(null)}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ estimates

export function EstimatesSection({ invoice }: { invoice: InvoiceDetail }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const refresh = useRefresh(invoice.id);
  const [adding, setAdding] = useState(false);
  const canWrite = hasPermission('BILLING_WRITE') && invoice.status === 'OPEN';
  const remove = useMutation({
    mutationFn: (estimateId: string) => billingApi.deactivateEstimate(invoice.id, estimateId),
    onSuccess: () => {
      toast.success('Estimate removed');
      refresh();
    },
    onError: toast.error,
  });
  return (
    <Box>
      <SectionToolbar note="Estimated future charges help families plan. They are clearly labelled and never affect the balance.">
        {canWrite && (
          <Button variant="contained" size="small" startIcon={<Plus size={14} />} onClick={() => setAdding(true)}>
            Add estimate
          </Button>
        )}
      </SectionToolbar>
      <DataTable
        ariaLabel="Estimates"
        rows={invoice.estimates}
        rowKey={(e) => e.id}
        dense
        emptyTitle="No estimates"
        rowSx={(e) => (!e.active ? { '& td': { color: colors.inkSubtle } } : undefined)}
        columns={[
          {
            key: 'desc',
            header: 'Estimate',
            primary: true,
            render: (e) => (
              <Box>
                <Typography variant="body2">{e.description}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {CATEGORY_LABEL[e.category]} · basis: {e.basis}
                </Typography>
              </Box>
            ),
          },
          { key: 'amount', header: 'Estimated', align: 'right', render: (e) => <Money value={e.estimatedAmount} /> },
          { key: 'by', header: 'Added', hideOnMobile: true, render: (e) => `${e.createdByName ?? ''} · ${formatDate(e.createdAt)}` },
          { key: 'state', header: 'Status', render: (e) => <StatusChip value={e.active ? 'ACTIVE' : 'INACTIVE'} label={e.active ? 'Active' : 'Removed'} /> },
          {
            key: 'actions',
            header: '',
            align: 'right',
            render: (e) =>
              canWrite && e.active ? (
                <Tooltip title="Remove estimate">
                  <IconButton size="small" aria-label={`Remove estimate ${e.description}`} disabled={remove.isPending} onClick={() => remove.mutate(e.id)}>
                    <X size={16} />
                  </IconButton>
                </Tooltip>
              ) : null,
          },
        ]}
      />
      <EstimateDialog
        open={adding}
        invoiceId={invoice.id}
        onClose={() => setAdding(false)}
        onDone={() => {
          toast.success('Estimate added');
          setAdding(false);
          refresh();
        }}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ payments

export function PaymentsTable({
  rows,
  showPatient,
  onReverse,
  loading,
  error,
  onRetry,
  page,
  pageSize,
  total,
  onPageChange,
  onPageSizeChange,
}: {
  rows: PaymentView[] | undefined;
  showPatient?: boolean;
  onReverse?: (p: PaymentView) => void;
  loading?: boolean;
  error?: unknown;
  onRetry?: () => void;
  page?: number;
  pageSize?: number;
  total?: number;
  onPageChange?: (p: number) => void;
  onPageSizeChange?: (s: number) => void;
}) {
  const toast = useToast();
  return (
    <DataTable<PaymentView>
      ariaLabel="Payments"
      rows={rows}
      loading={loading}
      error={error}
      onRetry={onRetry}
      rowKey={(p) => p.id}
      dense
      page={page}
      pageSize={pageSize}
      total={total}
      onPageChange={onPageChange}
      onPageSizeChange={onPageSizeChange}
      emptyTitle="No payments recorded"
      columns={[
        {
          key: 'receipt',
          header: 'Receipt',
          primary: true,
          render: (p) => (
            <Box>
              <Typography variant="body2" fontWeight={600} sx={{ whiteSpace: 'nowrap', textDecoration: p.status === 'REVERSED' ? 'line-through' : undefined }}>
                {p.receiptNumber}
              </Typography>
              <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
                {formatDateTime(p.receivedAt)}
              </Typography>
            </Box>
          ),
        },
        ...(showPatient
          ? [
              {
                key: 'patient',
                header: 'Patient / invoice',
                render: (p: PaymentView) => (
                  <Box>
                    <Typography variant="body2">{p.patientName}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {p.mrn} · {p.invoiceNumber}
                    </Typography>
                  </Box>
                ),
              },
            ]
          : []),
        {
          key: 'method',
          header: 'Method',
          render: (p) => (
            <Box>
              <Typography variant="body2">{METHOD_LABEL[p.method]}</Typography>
              {p.methodReference && (
                <Typography variant="caption" color="text.secondary">
                  {p.methodReference}
                </Typography>
              )}
            </Box>
          ),
        },
        { key: 'payer', header: 'Paid by', hideOnMobile: true, render: (p) => p.payerName ?? '—' },
        { key: 'amount', header: 'Amount', align: 'right', render: (p) => <Money value={p.amount} strong={p.status === 'COMPLETED'} /> },
        {
          key: 'status',
          header: 'Status',
          render: (p) => (
            <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap justifyContent={{ xs: 'flex-end', md: 'flex-start' }}>
              <StatusChip value={p.status} />
              {p.advance && <StatusChip value="ADVANCE" label="Advance" />}
              {Number(p.refundedAmount) > 0 && <StatusChip value="REFUND" tone="secondary" label={`Refunded ${formatINR(p.refundedAmount)}`} />}
            </Stack>
          ),
        },
        {
          key: 'by',
          header: 'Recorded by',
          hideOnMobile: true,
          render: (p) => (
            <Box>
              <Typography variant="body2">{p.receivedByName}</Typography>
              {p.reversalReason && (
                <Typography variant="caption" color="error.main">
                  Reversed: {p.reversalReason}
                </Typography>
              )}
            </Box>
          ),
        },
        {
          key: 'actions',
          header: '',
          align: 'right',
          render: (p) => (
            <Stack direction="row" spacing={0.5} justifyContent="flex-end">
              <Tooltip title="Download receipt (PDF)">
                <IconButton
                  size="small"
                  aria-label={`Download receipt ${p.receiptNumber}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    billingApi.downloadReceipt(p.id, p.receiptNumber).catch(toast.error);
                  }}
                >
                  <Download size={16} />
                </IconButton>
              </Tooltip>
              {onReverse && p.status === 'COMPLETED' && (
                <Tooltip title="Reverse payment (e.g. bounced cheque)">
                  <IconButton size="small" aria-label={`Reverse payment ${p.receiptNumber}`} onClick={() => onReverse(p)}>
                    <Undo2 size={16} />
                  </IconButton>
                </Tooltip>
              )}
            </Stack>
          ),
        },
      ]}
    />
  );
}

export function PaymentsSection({ invoice, onRecord }: { invoice: InvoiceDetail; onRecord: () => void }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const refresh = useRefresh(invoice.id);
  const [reversing, setReversing] = useState<PaymentView | null>(null);
  const canPay = hasPermission('PAYMENT_RECORD') && invoice.status !== 'CANCELLED';
  const reverse = useMutation({
    mutationFn: ({ p, reason }: { p: PaymentView; reason: string }) => billingApi.reversePayment(p.id, reason),
    onSuccess: () => {
      toast.success('Payment reversed');
      setReversing(null);
      refresh();
    },
    onError: toast.error,
  });
  return (
    <Box>
      <SectionToolbar note="Partial and multiple payments are supported. Reversals keep the original receipt on record.">
        {canPay && (
          <Button variant="contained" size="small" startIcon={<Plus size={14} />} onClick={onRecord}>
            Record payment
          </Button>
        )}
      </SectionToolbar>
      <PaymentsTable rows={invoice.payments} onReverse={canPay ? setReversing : undefined} />
      <ConfirmDialog
        open={!!reversing}
        title={`Reverse receipt ${reversing?.receiptNumber ?? ''}?`}
        description={reversing && `${formatINR(reversing.amount)} will be added back to the balance and the patient will be notified.`}
        requireReason
        reasonLabel="Reason (e.g. cheque returned unpaid)"
        confirmLabel="Reverse payment"
        tone="error"
        loading={reverse.isPending}
        onConfirm={(reason) => reversing && reverse.mutate({ p: reversing, reason: reason! })}
        onClose={() => setReversing(null)}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ adjustments

export function AdjustmentDecisionButtons({ adjustment, onDone }: { adjustment: AdjustmentView; onDone?: () => void }) {
  const { hasPermission, me } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [rejecting, setRejecting] = useState(false);
  const [approving, setApproving] = useState(false);
  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['adjustments'] });
    queryClient.invalidateQueries({ queryKey: ['invoice', adjustment.invoiceId] });
    queryClient.invalidateQueries({ queryKey: ['invoices'] });
    queryClient.invalidateQueries({ queryKey: ['billing'] });
    onDone?.();
  };
  const approve = useMutation({
    mutationFn: () => billingApi.approveAdjustment(adjustment.id),
    onSuccess: (a) => {
      toast.success(`${ADJUSTMENT_LABEL[a.type]} ${a.adjustmentNumber} approved and posted`);
      setApproving(false);
      refresh();
    },
    onError: toast.error,
  });
  const reject = useMutation({
    mutationFn: (notes: string) => billingApi.rejectAdjustment(adjustment.id, notes),
    onSuccess: () => {
      toast.success('Adjustment rejected');
      setRejecting(false);
      refresh();
    },
    onError: toast.error,
  });
  if (adjustment.status !== 'PENDING' || !hasPermission('ADJUSTMENT_APPROVE')) return null;
  if (me?.id === adjustment.requestedById) {
    return (
      <Typography variant="caption" color="text.secondary" sx={{ whiteSpace: 'nowrap' }}>
        Awaiting another approver
      </Typography>
    );
  }
  return (
    // Stop clicks (including those inside the portal dialogs) from reaching a clickable table row.
    <Box onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      <Stack direction="row" spacing={1} justifyContent="flex-end">
        <Button size="small" variant="contained" startIcon={<Check size={14} />} onClick={() => setApproving(true)}>
          Approve
        </Button>
        <Button size="small" color="error" startIcon={<X size={14} />} onClick={() => setRejecting(true)}>
          Reject
        </Button>
      </Stack>
      <ConfirmDialog
        open={approving}
        title={`Approve ${ADJUSTMENT_LABEL[adjustment.type].toLowerCase()} ${adjustment.adjustmentNumber}?`}
        description={
          <>
            {formatINR(adjustment.amount)} on {adjustment.invoiceNumber} ({adjustment.patientName}) will be posted to the ledger and{' '}
            {adjustment.balanceSign < 0 ? 'reduce' : 'increase'} the balance. Requested by {adjustment.requestedByName}: “{adjustment.reason}”.
          </>
        }
        confirmLabel="Approve and post"
        loading={approve.isPending}
        onConfirm={() => approve.mutate()}
        onClose={() => setApproving(false)}
      />
      <ConfirmDialog
        open={rejecting}
        title={`Reject ${adjustment.adjustmentNumber}?`}
        description="The requester is notified. Nothing is posted to the ledger."
        requireReason
        reasonLabel="Reason for rejection"
        confirmLabel="Reject"
        tone="error"
        loading={reject.isPending}
        onConfirm={(r) => reject.mutate(r!)}
        onClose={() => setRejecting(false)}
      />
    </Box>
  );
}

export function AdjustmentsTable({ rows, showInvoice, ...rest }: { rows: AdjustmentView[] | undefined; showInvoice?: boolean } & Partial<
  Pick<DataTableProps<AdjustmentView>, 'loading' | 'error' | 'onRetry' | 'page' | 'pageSize' | 'total' | 'onPageChange' | 'onPageSizeChange' | 'onRowClick' | 'emptyTitle'>
>) {
  return (
    <DataTable<AdjustmentView>
      ariaLabel="Adjustments"
      rows={rows}
      rowKey={(a) => a.id}
      dense
      emptyTitle="No adjustments"
      {...rest}
      columns={[
        {
          key: 'no',
          header: 'Adjustment',
          primary: true,
          render: (a) => (
            <Box sx={{ minWidth: { md: 190 } }}>
              <Typography variant="body2" fontWeight={600} sx={{ whiteSpace: 'nowrap' }}>
                {ADJUSTMENT_LABEL[a.type]} · {a.adjustmentNumber}
              </Typography>
              <Typography variant="caption" color="text.secondary" component="div" sx={{ maxWidth: 360 }}>
                {a.reason}
                {a.relatedReceiptNumber ? ` · against ${a.relatedReceiptNumber}` : ''}
                {a.refundMethod ? ` · via ${METHOD_LABEL[a.refundMethod]}` : ''}
              </Typography>
            </Box>
          ),
        },
        ...(showInvoice
          ? [
              {
                key: 'inv',
                header: 'Patient / invoice',
                render: (a: AdjustmentView) => (
                  <Box>
                    <Typography variant="body2">{a.patientName}</Typography>
                    <Typography variant="caption" color="text.secondary">
                      {a.mrn} ·{' '}
                      <Typography component={Link} href={`/billing/invoices/${a.invoiceId}`} variant="caption" color="primary">
                        {a.invoiceNumber}
                      </Typography>
                    </Typography>
                  </Box>
                ),
              },
            ]
          : []),
        {
          key: 'amount',
          header: 'Effect on balance',
          align: 'right',
          render: (a) => (
            <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', fontWeight: 600 }}>
              {a.balanceSign < 0 ? '− ' : '+ '}
              {formatINR(a.amount)}
            </Typography>
          ),
        },
        { key: 'status', header: 'Status', render: (a) => <StatusChip value={a.status} /> },
        {
          key: 'who',
          header: 'Requested / decided',
          render: (a) => (
            <Box sx={{ minWidth: { md: 150 }, maxWidth: 260 }}>
              <Typography variant="body2">{a.requestedByName}</Typography>
              <Typography variant="caption" color="text.secondary" component="div" sx={{ whiteSpace: 'nowrap' }}>
                Requested {formatDateTime(a.requestedAt)}
              </Typography>
              {a.decidedAt && (
                <Box sx={{ mt: 1 }}>
                  <Typography variant="body2">
                    {a.status === 'REJECTED' ? 'Rejected' : 'Approved'} by {a.decidedByName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" component="div" sx={{ whiteSpace: 'nowrap' }}>
                    {formatDateTime(a.decidedAt)}
                  </Typography>
                  {a.decisionNotes && (
                    <Typography variant="caption" color="text.secondary" component="div">
                      {a.decisionNotes}
                    </Typography>
                  )}
                </Box>
              )}
            </Box>
          ),
        },
        { key: 'actions', header: '', align: 'right', render: (a) => <AdjustmentDecisionButtons adjustment={a} /> },
      ]}
    />
  );
}

export function AdjustmentsSection({ invoice }: { invoice: InvoiceDetail }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const refresh = useRefresh(invoice.id);
  const [open, setOpen] = useState(false);
  const can = hasPermission('ADJUSTMENT_REQUEST') && invoice.status !== 'CANCELLED';
  return (
    <Box>
      <SectionToolbar note="Discounts, write-offs, credit/debit notes and refunds post to the ledger only after approval by a different person.">
        {can && (
          <Button variant="contained" size="small" startIcon={<Plus size={14} />} onClick={() => setOpen(true)}>
            Request adjustment
          </Button>
        )}
      </SectionToolbar>
      <AdjustmentsTable rows={invoice.adjustments} />
      <AdjustmentDialog
        open={open}
        invoice={invoice}
        onClose={() => setOpen(false)}
        onDone={() => {
          toast.success('Adjustment submitted for approval');
          setOpen(false);
          refresh();
        }}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ claims

export function ClaimsSection({ invoice }: { invoice: InvoiceDetail }) {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const refresh = useRefresh(invoice.id);
  const [open, setOpen] = useState(false);
  return (
    <Box>
      <SectionToolbar note="Approved amounts reduce the balance only when the payer settles. Settlements can arrive in parts.">
        {hasPermission('INSURANCE_MANAGE') && invoice.status !== 'CANCELLED' && (
          <Button variant="contained" size="small" startIcon={<Plus size={14} />} onClick={() => setOpen(true)}>
            New claim
          </Button>
        )}
      </SectionToolbar>
      {invoice.claims.length === 0 ? (
        <EmptyState title="No claims on this invoice" compact />
      ) : (
        <Stack spacing={3} sx={{ px: 4, pb: 4 }}>
          {invoice.claims.map((c) => (
            <Card key={c.id} variant="outlined" sx={{ p: 4 }}>
              <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} justifyContent="space-between">
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="subtitle1" fontWeight={600}>
                      {c.payerName}
                    </Typography>
                    <StatusChip value={c.status} />
                  </Stack>
                  <Typography variant="caption" color="text.secondary" component="div">
                    {c.claimNumber} · policy {c.policyNumber}
                    {c.preAuthNumber ? ` · pre-auth ${c.preAuthNumber}` : ''} · created by {c.createdByName} {formatDate(c.createdAt)}
                  </Typography>
                  {c.rejectionReason && (
                    <Typography variant="body2" color="error.main" sx={{ mt: 1 }}>
                      Rejected: {c.rejectionReason}
                    </Typography>
                  )}
                  {c.notes && (
                    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                      {c.notes}
                    </Typography>
                  )}
                </Box>
                <Stack direction="row" spacing={4} sx={{ flexShrink: 0 }}>
                  {[
                    ['Claimed', c.claimedAmount],
                    ['Approved', c.approvedAmount],
                    ['Settled', c.settledAmount],
                    ['Pending', c.pendingSettlement],
                  ].map(([label, v]) => (
                    <Box key={label} sx={{ textAlign: 'right' }}>
                      <Typography variant="caption" color="text.secondary">
                        {label}
                      </Typography>
                      <Typography variant="body2" fontWeight={600} sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                        {formatINR(v)}
                      </Typography>
                    </Box>
                  ))}
                </Stack>
              </Stack>
              {c.settlements.length > 0 && (
                <Box sx={{ mt: 3 }}>
                  <Typography variant="caption" color="text.secondary" fontWeight={600}>
                    Settlements received
                  </Typography>
                  {c.settlements.map((s) => (
                    <Stack key={s.id} direction="row" justifyContent="space-between" spacing={2} sx={{ py: 1, borderBottom: 1, borderColor: 'divider' }}>
                      <Typography variant="body2">
                        {formatDate(s.settledAt)} · {s.reference}
                        {s.notes ? ` · ${s.notes}` : ''}
                      </Typography>
                      <Money value={s.amount} />
                    </Stack>
                  ))}
                </Box>
              )}
              <Box sx={{ mt: 3 }}>
                <ClaimActions claim={c} />
              </Box>
            </Card>
          ))}
        </Stack>
      )}
      <CreateClaimDialog
        open={open}
        invoiceId={invoice.id}
        onClose={() => setOpen(false)}
        onDone={() => {
          toast.success('Draft claim created');
          setOpen(false);
          refresh();
        }}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ ledger

export function LedgerSection({ invoice }: { invoice: InvoiceDetail }) {
  return (
    <Box>
      <SectionToolbar note="The append-only ledger is the single source of truth for the balance. Positive entries increase what is owed; negative entries reduce it." />
      <DataTable<LedgerView>
        ariaLabel="Ledger"
        rows={invoice.ledger}
        rowKey={(e) => e.id}
        dense
        emptyTitle="No ledger entries yet"
        columns={[
          { key: 'at', header: 'Posted', render: (e) => formatDateTime(e.createdAt), width: 160 },
          {
            key: 'desc',
            header: 'Entry',
            primary: true,
            render: (e) => (
              <Box>
                <Typography variant="body2">{e.description}</Typography>
                <Typography variant="caption" color="text.secondary">
                  {LEDGER_LABEL[e.entryType]}
                  {e.referenceNumber ? ` · ${e.referenceNumber}` : ''} · {e.createdByName}
                </Typography>
              </Box>
            ),
          },
          {
            key: 'amount',
            header: 'Amount',
            align: 'right',
            render: (e) => (
              <Typography
                variant="body2"
                sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', color: isNegative(e.amount) ? colors.success : undefined }}
              >
                {formatINR(e.amount)}
              </Typography>
            ),
          },
          { key: 'running', header: 'Balance after', align: 'right', render: (e) => <Money value={e.runningBalance} strong /> },
        ]}
      />
    </Box>
  );
}

// ------------------------------------------------------------------ patient queries

export function RequestsSection({ invoice }: { invoice: InvoiceDetail }) {
  const { hasPermission } = useAuth();
  const [responding, setResponding] = useState<RequestView | null>(null);
  return (
    <Box>
      <SectionToolbar note="Questions and disputes raised by the patient or their authorized caregiver from the portal." />
      {invoice.requests.length === 0 ? (
        <EmptyState title="No patient queries on this invoice" compact />
      ) : (
        <Stack spacing={3} sx={{ px: 4, pb: 4 }}>
          {invoice.requests.map((r) => (
            <Card key={r.id} variant="outlined" sx={{ p: 4 }}>
              <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2}>
                <Box sx={{ minWidth: 0 }}>
                  <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                    <Typography variant="subtitle2">{r.subject}</Typography>
                    <StatusChip value={r.status} />
                    <Chip size="small" variant="outlined" label={REQUEST_TYPE_LABEL[r.requestType]} />
                  </Stack>
                  <Typography variant="caption" color="text.secondary">
                    {r.raisedByName} · {formatDateTime(r.raisedAt)}
                    {r.lineItemDescription ? ` · about ${r.lineItemDescription}` : ''}
                  </Typography>
                  <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>
                    {r.description}
                  </Typography>
                  {r.response && (
                    <Box sx={{ mt: 2, pl: 3, borderLeft: `3px solid ${colors.primarySoft}` }}>
                      <Typography variant="caption" color="text.secondary">
                        Response by {r.respondedByName} · {formatDateTime(r.respondedAt)}
                      </Typography>
                      <Typography variant="body2">{r.response}</Typography>
                    </Box>
                  )}
                </Box>
                {hasPermission('BILLING_WRITE') && (r.status === 'OPEN' || r.status === 'IN_REVIEW') && (
                  <Box sx={{ flexShrink: 0 }}>
                    <Button size="small" variant="outlined" onClick={() => setResponding(r)}>
                      Respond
                    </Button>
                  </Box>
                )}
              </Stack>
            </Card>
          ))}
        </Stack>
      )}
      <RespondDialog request={responding} onClose={() => setResponding(null)} />
    </Box>
  );
}

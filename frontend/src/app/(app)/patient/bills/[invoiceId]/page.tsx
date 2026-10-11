'use client';

import { use, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Accordion,
  AccordionDetails,
  AccordionSummary,
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { ChevronDown, Download, FileDown, MessageSquarePlus } from 'lucide-react';
import {
  billingApi,
  billingKeys,
  CATEGORY_LABEL,
  METHOD_LABEL,
  portalBillingApi,
  REQUEST_TYPE_LABEL,
  type PortalInvoice,
  type RequestType,
} from '@/lib/api/billing';
import { useToast } from '@/lib/hooks';
import { formatDate, formatDateTime, formatINR } from '@/lib/format';
import { colors } from '@/theme/tokens';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { Money } from '@/components/ui/Display';
import { EmptyState, ErrorState, PageSkeleton } from '@/components/ui/States';
import { BalanceBreakdown } from '@/components/billing/BalanceBreakdown';

function RaiseQueryDialog({ invoice, open, onClose }: { invoice: PortalInvoice; open: boolean; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [type, setType] = useState<RequestType>('QUERY');
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');
  const [lineItemId, setLineItemId] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [touched, setTouched] = useState(false);
  const items = invoice.categories.flatMap((c) => c.items.filter((i) => !i.reversed));
  const invalid = subject.trim().length < 3 || description.trim().length < 5;
  const submit = async () => {
    setTouched(true);
    if (invalid) return;
    setSaving(true);
    setError(null);
    try {
      await portalBillingApi.raiseRequest(invoice.id, { requestType: type, subject: subject.trim(), description: description.trim(), lineItemId: lineItemId || null });
      toast.success('Your query has been sent to the billing team');
      queryClient.invalidateQueries({ queryKey: billingKeys.portalInvoice(invoice.id) });
      setSubject('');
      setDescription('');
      setLineItemId('');
      setTouched(false);
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };
  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="raise-title">
      <DialogTitle id="raise-title">Ask about this bill</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          <TextField select label="What do you need?" value={type} onChange={(e) => setType(e.target.value as RequestType)} fullWidth>
            {(Object.keys(REQUEST_TYPE_LABEL) as RequestType[]).map((k) => (
              <MenuItem key={k} value={k}>
                {k === 'DISPUTE' ? 'Dispute a charge' : k === 'QUERY' ? 'Ask a question' : k === 'ITEMIZATION' ? 'Request a detailed (itemized) bill' : 'Discuss a payment plan'}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="About a specific charge (optional)" value={lineItemId} onChange={(e) => setLineItemId(e.target.value)} fullWidth>
            <MenuItem value="">The whole bill</MenuItem>
            {items.map((i) => (
              <MenuItem key={i.id} value={i.id}>
                {formatDate(i.serviceDate)} · {i.description} · {formatINR(i.totalAmount)}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Subject"
            value={subject}
            onChange={(e) => setSubject(e.target.value)}
            required
            fullWidth
            error={touched && subject.trim().length < 3}
            helperText={touched && subject.trim().length < 3 ? 'Add a short subject' : ' '}
            slotProps={{ htmlInput: { maxLength: 300 } }}
          />
          <TextField
            label="Details"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            required
            fullWidth
            multiline
            minRows={4}
            error={touched && description.trim().length < 5}
            helperText={touched && description.trim().length < 5 ? 'Describe your question' : 'The billing team will reply here and notify you.'}
            slotProps={{ htmlInput: { maxLength: 2000 } }}
          />
        </Stack>
        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>
          Cancel
        </Button>
        <Button variant="contained" onClick={submit} disabled={saving}>
          {saving ? 'Sending…' : 'Send to billing team'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default function PatientBillDetail({ params }: { params: Promise<{ invoiceId: string }> }) {
  const { invoiceId } = use(params);
  const toast = useToast();
  const [raising, setRaising] = useState(false);
  const q = useQuery({ queryKey: billingKeys.portalInvoice(invoiceId), queryFn: () => portalBillingApi.invoice(invoiceId) });
  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const v = q.data;
  const s = v.summary;

  return (
    <>
      <PageHeader
        title={v.admission?.departmentName ? `${v.admission.departmentName} bill` : `Bill ${v.invoiceNumber}`}
        subtitle={
          <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
            <StatusChip value={s.paymentStatus} />
            <StatusChip value={v.status} label={v.status === 'FINALIZED' ? 'Final bill' : 'Interim bill (stay ongoing)'} tone={v.status === 'FINALIZED' ? 'primary' : 'info'} />
            <span>
              {v.invoiceNumber} · {v.patient.fullName}
            </span>
          </Stack>
        }
        breadcrumbs={[{ label: 'Dashboard', href: '/patient' }, { label: 'Bills', href: '/patient/bills' }, { label: v.invoiceNumber }]}
        actions={
          <>
            <Button variant="outlined" startIcon={<FileDown size={16} />} onClick={() => billingApi.downloadInvoicePdf(v.id, v.invoiceNumber).catch(toast.error)}>
              Download itemized bill (PDF)
            </Button>
            <Button variant="contained" startIcon={<MessageSquarePlus size={16} />} onClick={() => setRaising(true)}>
              Ask a question
            </Button>
          </>
        }
      />
      <Grid container spacing={5}>
        <Grid size={{ xs: 12, lg: 5 }}>
          <Stack spacing={5}>
            <SectionCard title="How your balance is calculated" subtitle="Worked out by the hospital's billing system, step by step">
              <BalanceBreakdown
                lines={v.explanation.filter((l) => l.operator !== '=').map((l) => ({ label: l.label, amount: l.amount, operator: l.operator, hint: l.hint }))}
                balance={s.balance}
                paymentStatus={s.paymentStatus}
                insurancePending={s.insuranceApprovedPending}
                estimates={s.estimatedFutureCharges}
              />
              <Stack component="ul" spacing={1.5} sx={{ mt: 4, mb: 0, pl: 5 }}>
                {v.explanationNotes.map((n) => (
                  <Typography key={n} component="li" variant="body2" color="text.secondary">
                    {n}
                  </Typography>
                ))}
              </Stack>
            </SectionCard>
            <SectionCard title="Your stay">
              <Stack spacing={2}>
                <Typography variant="body2">
                  <strong>{v.hospitalName}</strong>
                </Typography>
                {v.admission && (
                  <>
                    <Typography variant="body2" color="text.secondary">
                      Admission {v.admission.admissionNumber} · {v.admission.departmentName} · {v.admission.attendingDoctorName}
                    </Typography>
                    <Typography variant="body2" color="text.secondary">
                      {formatDateTime(v.admission.admittedAt)} – {v.admission.dischargedAt ? formatDateTime(v.admission.dischargedAt) : 'ongoing'}
                    </Typography>
                  </>
                )}
                {v.finalizedAt && (
                  <Typography variant="body2" color="text.secondary">
                    Final bill issued {formatDate(v.finalizedAt)}
                  </Typography>
                )}
              </Stack>
            </SectionCard>
          </Stack>
        </Grid>
        <Grid size={{ xs: 12, lg: 7 }}>
          <Stack spacing={5}>
            <SectionCard title="Charges" subtitle="Grouped by type of service. Reversed charges are shown crossed out and are not counted.">
              {v.categories.length === 0 ? (
                <EmptyState title="No charges yet" compact />
              ) : (
                v.categories.map((c) => (
                  <Accordion key={c.category} disableGutters elevation={0} sx={{ border: 1, borderColor: 'divider', '&:not(:last-of-type)': { borderBottom: 0 }, '&::before': { display: 'none' } }}>
                    <AccordionSummary expandIcon={<ChevronDown size={18} />} aria-controls={`cat-${c.category}`}>
                      <Stack direction="row" justifyContent="space-between" sx={{ width: '100%', pr: 2 }} spacing={2}>
                        <Typography variant="body2" fontWeight={600}>
                          {CATEGORY_LABEL[c.category]}{' '}
                          <Typography component="span" variant="caption" color="text.secondary">
                            · {c.items.filter((i) => !i.reversed).length} item(s)
                          </Typography>
                        </Typography>
                        <Money value={c.total} strong />
                      </Stack>
                    </AccordionSummary>
                    <AccordionDetails id={`cat-${c.category}`} sx={{ pt: 0 }}>
                      <Stack divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}>
                        {c.items.map((i) => (
                          <Stack key={i.id} direction="row" justifyContent="space-between" spacing={2} sx={{ py: 1.5, color: i.reversed ? colors.inkSubtle : undefined }}>
                            <Box sx={{ minWidth: 0 }}>
                              <Typography variant="body2" sx={{ textDecoration: i.reversed ? 'line-through' : undefined }}>
                                {i.description}
                              </Typography>
                              <Typography variant="caption" color="text.secondary">
                                {formatDate(i.serviceDate)} · {Number(i.quantity)} × {formatINR(i.unitPrice)}
                                {Number(i.taxAmount) > 0 ? ` + tax ${formatINR(i.taxAmount)}` : ''}
                                {i.reversed ? ' · reversed, not charged' : ''}
                              </Typography>
                            </Box>
                            <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap', textDecoration: i.reversed ? 'line-through' : undefined }}>
                              {formatINR(i.totalAmount)}
                            </Typography>
                          </Stack>
                        ))}
                      </Stack>
                    </AccordionDetails>
                  </Accordion>
                ))
              )}
            </SectionCard>
            <SectionCard title="Payments & receipts">
              {v.payments.length === 0 ? (
                <EmptyState title="No payments yet" description="Pay at the billing desk; your receipts will appear here." compact />
              ) : (
                <Stack divider={<Box sx={{ borderBottom: 1, borderColor: 'divider' }} />}>
                  {v.payments.map((p) => (
                    <Stack key={p.id} direction="row" justifyContent="space-between" alignItems="center" spacing={2} sx={{ py: 2 }}>
                      <Box sx={{ minWidth: 0 }}>
                        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
                          <Typography variant="body2" fontWeight={600} sx={{ textDecoration: p.status === 'REVERSED' ? 'line-through' : undefined }}>
                            {formatINR(p.amount)}
                          </Typography>
                          {p.status === 'REVERSED' && <StatusChip value="REVERSED" />}
                          {p.advance && <StatusChip value="ADVANCE" label="Advance deposit" />}
                        </Stack>
                        <Typography variant="caption" color="text.secondary">
                          {formatDateTime(p.receivedAt)} · {METHOD_LABEL[p.method]} · receipt {p.receiptNumber}
                          {p.payerName ? ` · paid by ${p.payerName}` : ''}
                        </Typography>
                      </Box>
                      <Tooltip title="Download receipt (PDF)">
                        <IconButton aria-label={`Download receipt ${p.receiptNumber}`} onClick={() => billingApi.downloadReceipt(p.id, p.receiptNumber).catch(toast.error)}>
                          <Download size={18} />
                        </IconButton>
                      </Tooltip>
                    </Stack>
                  ))}
                </Stack>
              )}
            </SectionCard>
            {(v.adjustments.length > 0 || v.insurance.length > 0) && (
              <SectionCard title="Insurance, discounts & refunds">
                <Stack spacing={3}>
                  {v.insurance.map((c) => (
                    <Box key={c.claimNumber} sx={{ p: 3, borderRadius: 2, border: 1, borderColor: 'divider' }}>
                      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2" fontWeight={600}>
                          {c.payerName}
                        </Typography>
                        <StatusChip value={c.status} />
                      </Stack>
                      <Typography variant="caption" color="text.secondary" component="div">
                        Claim {c.claimNumber} · claimed {formatINR(c.claimedAmount)}
                        {c.approvedAmount ? ` · approved ${formatINR(c.approvedAmount)}` : ''} · paid to hospital {formatINR(c.settledAmount)}
                      </Typography>
                      {Number(c.pendingSettlement) > 0 && (
                        <Typography variant="caption" color="info.main" component="div">
                          {formatINR(c.pendingSettlement)} approved and awaiting payment from the insurer - not yet deducted from your balance.
                        </Typography>
                      )}
                      {c.rejectionReason && (
                        <Typography variant="caption" color="error.main" component="div">
                          Not approved: {c.rejectionReason}
                        </Typography>
                      )}
                    </Box>
                  ))}
                  {v.adjustments.map((a) => (
                    <Stack key={a.adjustmentNumber} direction="row" justifyContent="space-between" spacing={2}>
                      <Box>
                        <Typography variant="body2">{a.label}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {a.adjustmentNumber} · {formatDate(a.approvedAt)}
                        </Typography>
                      </Box>
                      <Typography variant="body2" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                        {a.balanceSign < 0 ? '− ' : '+ '}
                        {formatINR(a.amount)}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </SectionCard>
            )}
            {v.estimates.length > 0 && (
              <SectionCard title="Estimated upcoming charges" subtitle="Estimates only - not part of your balance and may change">
                <Stack spacing={2}>
                  {v.estimates.map((e) => (
                    <Stack key={e.description} direction="row" justifyContent="space-between" spacing={2} sx={{ p: 2, borderRadius: 2, border: `1px dashed ${colors.borderStrong}` }}>
                      <Box>
                        <Typography variant="body2">{e.description}</Typography>
                        <Typography variant="caption" color="text.secondary">
                          {e.basis}
                        </Typography>
                      </Box>
                      <Typography variant="body2" color="text.secondary" sx={{ fontVariantNumeric: 'tabular-nums', whiteSpace: 'nowrap' }}>
                        ≈ {formatINR(e.estimatedAmount)}
                      </Typography>
                    </Stack>
                  ))}
                </Stack>
              </SectionCard>
            )}
            <SectionCard
              title="Your questions"
              action={
                <Button size="small" startIcon={<MessageSquarePlus size={14} />} onClick={() => setRaising(true)}>
                  Ask
                </Button>
              }
            >
              {v.requests.length === 0 ? (
                <EmptyState title="No questions raised" description="If anything on this bill is unclear, ask the billing team." compact />
              ) : (
                <Stack spacing={3}>
                  {v.requests.map((r) => (
                    <Box key={r.id} sx={{ p: 3, borderRadius: 2, border: 1, borderColor: 'divider' }}>
                      <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                        <Typography variant="body2" fontWeight={600}>
                          {r.subject}
                        </Typography>
                        <StatusChip value={r.status} />
                      </Stack>
                      <Typography variant="caption" color="text.secondary">
                        {REQUEST_TYPE_LABEL[r.requestType]} · {r.raisedByName} · {formatDateTime(r.raisedAt)}
                        {r.lineItemDescription ? ` · about ${r.lineItemDescription}` : ''}
                      </Typography>
                      <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>
                        {r.description}
                      </Typography>
                      {r.response && (
                        <Box sx={{ mt: 2, pl: 3, borderLeft: `3px solid ${colors.primarySoft}` }}>
                          <Typography variant="caption" color="text.secondary">
                            Billing team · {formatDateTime(r.respondedAt)}
                          </Typography>
                          <Typography variant="body2">{r.response}</Typography>
                        </Box>
                      )}
                    </Box>
                  ))}
                </Stack>
              )}
            </SectionCard>
          </Stack>
        </Grid>
      </Grid>
      <RaiseQueryDialog invoice={v} open={raising} onClose={() => setRaising(false)} />
    </>
  );
}

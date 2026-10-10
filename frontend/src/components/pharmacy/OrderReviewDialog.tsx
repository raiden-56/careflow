'use client';

import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  FormControlLabel,
  Skeleton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { CheckCircle2, MessageSquareWarning, Pill } from 'lucide-react';
import { pharmacyApi, pharmacyKeys } from '@/lib/api/pharmacy';
import type { UUID } from '@/lib/api/types';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { PatientBanner } from '@/components/patient/PatientBanner';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ErrorState } from '@/components/ui/States';
import { StatusChip } from '@/components/ui/StatusChip';
import { OrderSummary } from './OrderSummary';
import { WarningList } from './PharmacyBits';

export interface OrderReviewDialogProps {
  orderId: UUID | null;
  onClose: () => void;
  onDispense: (orderId: UUID) => void;
}

/** Pharmacist review: safety warnings, verification and queries to the prescriber. */
export function OrderReviewDialog({ orderId, onClose, onDispense }: OrderReviewDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const { hasPermission } = useAuth();
  const canDispense = hasPermission('DISPENSE');
  const theme = useTheme();
  const fullScreen = useMediaQuery(theme.breakpoints.down('sm'));
  const [verifyOpen, setVerifyOpen] = useState(false);
  const [queryOpen, setQueryOpen] = useState(false);
  const [resolveOpen, setResolveOpen] = useState(false);
  const [ack, setAck] = useState(false);
  const [note, setNote] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);

  const detail = useQuery({
    queryKey: pharmacyKeys.order(orderId ?? 'none'),
    queryFn: () => pharmacyApi.order(orderId!),
    enabled: !!orderId,
  });
  const d = detail.data;
  const item = d?.item;
  const allergyMatch = !!item?.warnings.some((w) => w.type === 'ALLERGY');
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['pharmacy'] });

  const verifyM = useMutation({
    mutationFn: () => pharmacyApi.verify(orderId!, { allergyAcknowledged: ack, note: note.trim() || undefined }),
    onSuccess: () => {
      toast.success(`${item?.medicationName} verified`);
      setVerifyOpen(false);
      refresh();
    },
    onError: (e) => setActionError(errorMessage(e)),
  });
  const queryM = useMutation({
    mutationFn: (message: string) => pharmacyApi.raiseQuery(orderId!, message),
    onSuccess: () => {
      toast.success(`Query sent to ${item?.prescribedByName ?? 'the prescriber'}`);
      setQueryOpen(false);
      refresh();
    },
    onError: (e) => setActionError(errorMessage(e)),
  });
  const resolveM = useMutation({
    mutationFn: (resolution: string) => pharmacyApi.resolveQuery(item!.openQuery!.id, resolution),
    onSuccess: () => {
      toast.success('Query resolved');
      setResolveOpen(false);
      refresh();
    },
    onError: (e) => setActionError(errorMessage(e)),
  });

  const openVerify = () => {
    setAck(false);
    setNote('');
    setActionError(null);
    setVerifyOpen(true);
  };

  return (
    <>
      <Dialog open={!!orderId} onClose={onClose} maxWidth="md" fullWidth fullScreen={fullScreen} aria-labelledby="review-title">
        <DialogTitle id="review-title">Prescription review</DialogTitle>
        <DialogContent dividers>
          {detail.isLoading && (
            <Stack spacing={3}>
              <Skeleton height={90} />
              <Skeleton height={200} />
            </Stack>
          )}
          {detail.error && <ErrorState error={detail.error} onRetry={() => detail.refetch()} />}
          {d && item && (
            <Stack spacing={4}>
              <PatientBanner admission={d.admission} />
              <Box sx={{ mt: -3 }}>
                <OrderSummary item={item} />
              </Box>
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Safety checks
                </Typography>
                <WarningList warnings={item.warnings} />
              </Box>
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 1 }}>
                  Other active medications this admission
                </Typography>
                {d.otherActiveMedications.length === 0 ? (
                  <Typography variant="body2" color="text.secondary">
                    None
                  </Typography>
                ) : (
                  <Box component="ul" sx={{ m: 0, pl: 5 }}>
                    {d.otherActiveMedications.map((m) => (
                      <Typography component="li" variant="body2" key={m}>
                        {m}
                      </Typography>
                    ))}
                  </Box>
                )}
                <Alert severity="info" variant="outlined" sx={{ mt: 2 }}>
                  {d.interactionNote}
                </Alert>
              </Box>
              {d.queries.length > 0 && (
                <Box>
                  <Typography variant="subtitle2" sx={{ mb: 2 }}>
                    Queries to prescriber
                  </Typography>
                  <Stack spacing={2}>
                    {d.queries.map((q) => (
                      <Box key={q.id} sx={{ p: 3, border: 1, borderColor: 'divider', borderRadius: 2 }}>
                        <Stack direction="row" spacing={2} alignItems="center" sx={{ mb: 1 }}>
                          <StatusChip value={q.status === 'OPEN' ? 'QUERIED' : 'RESOLVED'} />
                          <Typography variant="caption" color="text.secondary">
                            {q.raisedByName} · {formatDateTime(q.raisedAt)}
                          </Typography>
                        </Stack>
                        <Typography variant="body2">{q.message}</Typography>
                        {q.resolution && (
                          <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
                            Resolution: {q.resolution} ({q.resolvedByName}, {formatDateTime(q.resolvedAt)})
                          </Typography>
                        )}
                      </Box>
                    ))}
                  </Stack>
                </Box>
              )}
            </Stack>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 6, py: 3, flexWrap: 'wrap', gap: 2 }}>
          <Button color="inherit" onClick={onClose}>
            Close
          </Button>
          {item && canDispense && (
            <>
              {item.openQuery ? (
                <Button
                  variant="outlined"
                  onClick={() => {
                    setActionError(null);
                    setResolveOpen(true);
                  }}
                >
                  Resolve query
                </Button>
              ) : (
                <Button
                  variant="outlined"
                  color="warning"
                  startIcon={<MessageSquareWarning size={16} />}
                  onClick={() => {
                    setActionError(null);
                    setQueryOpen(true);
                  }}
                >
                  Query prescriber
                </Button>
              )}
              {!item.verified ? (
                <Button variant="contained" startIcon={<CheckCircle2 size={16} />} onClick={openVerify}>
                  Verify
                </Button>
              ) : (
                <Button
                  variant="contained"
                  startIcon={<Pill size={16} />}
                  disabled={!!item.openQuery}
                  onClick={() => onDispense(item.orderId)}
                >
                  Dispense
                </Button>
              )}
            </>
          )}
        </DialogActions>
      </Dialog>

      <ConfirmDialog
        open={verifyOpen}
        title="Verify prescription?"
        description={
          <>
            You confirm that {item?.medicationName} {item?.dose} {item?.doseUnit} {item?.route} {item?.frequency} is appropriate to
            supply. Verification is recorded against your name and does not change the prescription.
          </>
        }
        confirmLabel="Verify"
        loading={verifyM.isPending}
        error={actionError}
        onConfirm={() => {
          if (allergyMatch && !ack) {
            setActionError('Acknowledge the documented allergy before verifying.');
            return;
          }
          verifyM.mutate();
        }}
        onClose={() => setVerifyOpen(false)}
      >
        <Stack spacing={2}>
          {allergyMatch && (
            <>
              <Alert severity="error">The patient has a documented allergy that may apply to this medication.</Alert>
              <FormControlLabel
                control={<Checkbox checked={ack} onChange={(e) => setAck(e.target.checked)} />}
                label="I have reviewed the allergy with the prescriber and it is safe to proceed."
              />
            </>
          )}
          <TextField label="Verification note (optional)" value={note} onChange={(e) => setNote(e.target.value)} multiline minRows={2} slotProps={{ htmlInput: { maxLength: 500 } }} />
        </Stack>
      </ConfirmDialog>
      <ConfirmDialog
        open={queryOpen}
        title="Query the prescriber"
        description={`${item?.prescribedByName ?? 'The prescriber'} is notified and the prescription stays on hold in the queue until the query is resolved.`}
        confirmLabel="Send query"
        tone="warning"
        requireReason
        reasonLabel="Question for the prescriber"
        loading={queryM.isPending}
        error={actionError}
        onConfirm={(message) => {
          if (!message || message.length < 5) {
            setActionError('Write at least 5 characters.');
            return;
          }
          queryM.mutate(message);
        }}
        onClose={() => setQueryOpen(false)}
      />
      <ConfirmDialog
        open={resolveOpen}
        title="Resolve query"
        description={item?.openQuery?.message}
        confirmLabel="Mark resolved"
        requireReason
        reasonLabel="Prescriber's response / outcome"
        loading={resolveM.isPending}
        error={actionError}
        onConfirm={(r) => resolveM.mutate(r!)}
        onClose={() => setResolveOpen(false)}
      />
    </>
  );
}

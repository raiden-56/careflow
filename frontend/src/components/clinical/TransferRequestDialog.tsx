'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { admissionApi } from '@/lib/api/core';
import type { AdmissionSummary, BedView } from '@/lib/api/types';
import { useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { BedPicker } from '@/components/beds/BedPicker';

/** Request an inter-ward / inter-bed transfer (bed management approves and completes it). */
export function TransferRequestDialog({ admission, open, onClose }: { admission: AdmissionSummary; open: boolean; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [bed, setBed] = useState<BedView | null>(null);
  const [reason, setReason] = useState('');
  const [priority, setPriority] = useState<'ROUTINE' | 'URGENT'>('ROUTINE');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setBed(null);
      setReason('');
      setPriority('ROUTINE');
      setError(null);
    }
  }, [open]);
  const m = useMutation({
    mutationFn: () => admissionApi.requestTransfer(admission.id, { toBedId: bed?.id, toWardId: bed?.wardId, reason: reason.trim(), priority }),
    onSuccess: () => {
      toast.success('Transfer requested - bed management has been notified');
      queryClient.invalidateQueries({ queryKey: ['transfers'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admission.id] });
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="transfer-title">
      <DialogTitle id="transfer-title">Request transfer · {admission.patient.fullName}</DialogTitle>
      <DialogContent>
        <Stack spacing={4} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <Typography variant="body2" color="text.secondary">
            Current bed: {admission.location ? `${admission.location.wardName} · ${admission.location.roomNumber}-${admission.location.bedNumber}` : '—'}.
            Choose a destination bed; bed management approves and completes the move.
          </Typography>
          <BedPicker value={bed?.id} onChange={setBed} requireIsolation={admission.requiresIsolation} />
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <TextField label="Reason" required value={reason} onChange={(e) => setReason(e.target.value)} multiline minRows={2} />
            <TextField
              select
              label="Priority"
              value={priority}
              onChange={(e) => setPriority(e.target.value as 'ROUTINE' | 'URGENT')}
              sx={{ maxWidth: { sm: 180 } }}
            >
              <MenuItem value="ROUTINE">Routine</MenuItem>
              <MenuItem value="URGENT">Urgent</MenuItem>
            </TextField>
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button variant="contained" disabled={!bed || reason.trim().length < 3 || m.isPending} onClick={() => m.mutate()}>
          {m.isPending ? 'Requesting…' : 'Request transfer'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

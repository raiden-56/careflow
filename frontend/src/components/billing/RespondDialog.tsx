'use client';

import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { billingApi, REQUEST_TYPE_LABEL, type RequestStatus, type RequestView } from '@/lib/api/billing';
import { useToast } from '@/lib/hooks';
import { formatDateTime } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';

/** Billing staff answer a patient query / dispute: move to review, resolve or reject with a response. */
export function RespondDialog({ request, onClose }: { request: RequestView | null; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<RequestStatus>('RESOLVED');
  const [response, setResponse] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (request) {
      setStatus(request.status === 'OPEN' ? 'IN_REVIEW' : 'RESOLVED');
      setResponse(request.response ?? '');
      setError(null);
    }
  }, [request]);
  const closing = status === 'RESOLVED' || status === 'REJECTED';
  const invalid = closing && response.trim().length < 3;

  const save = async () => {
    if (!request || invalid) return;
    setSaving(true);
    setError(null);
    try {
      await billingApi.respondRequest(request.id, status, response.trim() || undefined);
      toast.success(status === 'IN_REVIEW' ? 'Marked as in review - the patient has been informed' : 'Response sent to the patient');
      queryClient.invalidateQueries({ queryKey: ['billing-requests'] });
      queryClient.invalidateQueries({ queryKey: ['invoice', request.invoiceId] });
      queryClient.invalidateQueries({ queryKey: ['billing'] });
      onClose();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open={!!request} onClose={saving ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="respond-title">
      <DialogTitle id="respond-title">Respond to patient query</DialogTitle>
      {request && (
        <DialogContent>
          <Box sx={{ p: 3, mb: 4, borderRadius: 2, bgcolor: 'action.hover' }}>
            <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap sx={{ mb: 1 }}>
              <StatusChip value={request.status} />
              <Typography variant="caption" color="text.secondary">
                {REQUEST_TYPE_LABEL[request.requestType]} · {request.patientName} · {request.invoiceNumber} · raised by {request.raisedByName}{' '}
                {formatDateTime(request.raisedAt)}
              </Typography>
            </Stack>
            <Typography variant="subtitle2">{request.subject}</Typography>
            <Typography variant="body2" sx={{ mt: 1, whiteSpace: 'pre-wrap' }}>
              {request.description}
            </Typography>
            {request.lineItemDescription && (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
                About charge: {request.lineItemDescription}
              </Typography>
            )}
          </Box>
          <Stack spacing={3}>
            <TextField select label="Outcome" value={status} onChange={(e) => setStatus(e.target.value as RequestStatus)} fullWidth>
              <MenuItem value="IN_REVIEW" disabled={request.status === 'IN_REVIEW'}>
                In review (acknowledge, keep open)
              </MenuItem>
              <MenuItem value="RESOLVED">Resolved</MenuItem>
              <MenuItem value="REJECTED">Not upheld (rejected)</MenuItem>
            </TextField>
            <TextField
              label="Response to the patient"
              value={response}
              onChange={(e) => setResponse(e.target.value)}
              multiline
              minRows={3}
              required={closing}
              error={invalid && response.length > 0}
              helperText={closing ? 'Visible to the patient and their authorized caregivers.' : 'Optional while in review.'}
              slotProps={{ htmlInput: { maxLength: 2000 } }}
            />
          </Stack>
          {error && (
            <Alert severity="error" sx={{ mt: 3 }}>
              {error}
            </Alert>
          )}
        </DialogContent>
      )}
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button onClick={onClose} color="inherit" disabled={saving}>
          Cancel
        </Button>
        <Button variant="contained" onClick={save} disabled={saving || invalid}>
          {saving ? 'Saving…' : 'Send'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

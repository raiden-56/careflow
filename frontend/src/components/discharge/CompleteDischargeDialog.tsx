'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { dischargeApi, DISPOSITION_LABEL, type Disposition, type WorkflowView } from '@/lib/api/discharge';
import { errorMessage } from '@/lib/api/client';
import { isoToLocalInput, localInputToIso } from '@/lib/format';
import { useDischargeMutation } from './hooks';

const EXCEPTIONAL: Disposition[] = ['LAMA', 'ABSCONDED', 'DECEASED'];

/**
 * Records the actual departure and completes the discharge. Standard dispositions need every requirement met
 * (the server re-checks); exceptional dispositions (LAMA, absconded, deceased) need documented circumstances.
 */
export function CompleteDischargeDialog({
  open,
  workflow,
  bedLabel,
  afterCompleteHref,
  onClose,
}: {
  open: boolean;
  workflow: WorkflowView;
  bedLabel?: string;
  /** Where to go after completion (e.g. ward staff lose access to closed admissions). */
  afterCompleteHref?: string;
  onClose: () => void;
}) {
  const router = useRouter();
  const [departure, setDeparture] = useState('');
  const [disposition, setDisposition] = useState<Disposition>('HOME');
  const [notes, setNotes] = useState('');
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (open) {
      setDeparture(isoToLocalInput());
      setDisposition('HOME');
      setNotes('');
      setError(null);
    }
  }, [open]);

  const complete = useDischargeMutation(
    () => dischargeApi.complete(workflow.id, { actualDepartureAt: localInputToIso(departure), disposition, notes: notes.trim() || undefined }),
    {
      success: 'Discharge completed',
      onSuccess: () => {
        onClose();
        if (afterCompleteHref) router.push(afterCompleteHref);
      },
      onError: (e) => setError(errorMessage(e)) },
  );

  const exceptional = EXCEPTIONAL.includes(disposition);
  const notesInvalid = exceptional && notes.trim().length < 10;
  const blocked = !exceptional && !workflow.readiness.canComplete;

  return (
    <Dialog open={open} onClose={complete.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="complete-title">
      <DialogTitle id="complete-title">Record departure and complete discharge</DialogTitle>
      <DialogContent>
        <Stack spacing={4} sx={{ pt: 1 }}>
          <Typography variant="body2" color="text.secondary">
            Completing the discharge closes the admission, releases {bedLabel ?? 'the bed'} for turnover, cancels remaining scheduled
            doses and notifies the patient. This cannot be undone.
          </Typography>
          {error && <Alert severity="error">{error}</Alert>}
          {blocked && (
            <Alert severity="warning">
              Not ready for a standard discharge. Outstanding:
              <Box component="ul" sx={{ m: 0, mt: 1, pl: 4 }}>
                {workflow.readiness.outstanding.map((o) => (
                  <li key={o}>{o}</li>
                ))}
              </Box>
            </Alert>
          )}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <TextField
              type="datetime-local"
              label="Actual departure time"
              value={departure}
              onChange={(e) => setDeparture(e.target.value)}
              slotProps={{ inputLabel: { shrink: true }, htmlInput: { max: isoToLocalInput() } }}
              fullWidth
              required
            />
            <TextField select label="Discharge status" value={disposition} onChange={(e) => setDisposition(e.target.value as Disposition)} fullWidth>
              {(Object.keys(DISPOSITION_LABEL) as Disposition[]).map((d) => (
                <MenuItem key={d} value={d}>
                  {DISPOSITION_LABEL[d]}
                </MenuItem>
              ))}
            </TextField>
          </Stack>
          {exceptional && (
            <Alert severity="info">
              {DISPOSITION_LABEL[disposition]} is recorded without the standard checklist. Document the circumstances; remaining steps are
              closed as not applicable with this note and the event is audited.
            </Alert>
          )}
          <TextField
            label={exceptional ? 'Circumstances (required)' : 'Departure notes (optional)'}
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            multiline
            minRows={2}
            required={exceptional}
            error={notesInvalid && notes.length > 0}
            helperText={exceptional ? 'At least 10 characters.' : 'e.g. escorted by family, transport arranged.'}
            slotProps={{ htmlInput: { maxLength: 1000 } }}
          />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={complete.isPending}>
          Cancel
        </Button>
        <Button variant="contained" onClick={() => complete.mutate(undefined)} disabled={complete.isPending || !departure || notesInvalid || blocked}>
          {complete.isPending ? 'Completing…' : 'Complete discharge'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

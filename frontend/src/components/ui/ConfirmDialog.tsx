'use client';

import { ReactNode, useEffect, useState } from 'react';
import {
  Alert,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogContentText,
  DialogTitle,
  TextField,
} from '@mui/material';

export interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  tone?: 'primary' | 'error' | 'warning';
  /** Require a reason (e.g. reversals, exceptions). Passed to onConfirm. */
  requireReason?: boolean;
  reasonLabel?: string;
  loading?: boolean;
  error?: string | null;
  onConfirm: (reason?: string) => void;
  onClose: () => void;
  children?: ReactNode;
}

/** Confirmation for consequential actions; can require a documented reason. */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel = 'Confirm',
  cancelLabel = 'Cancel',
  tone = 'primary',
  requireReason,
  reasonLabel = 'Reason',
  loading,
  error,
  onConfirm,
  onClose,
  children,
}: ConfirmDialogProps) {
  const [reason, setReason] = useState('');
  const [touched, setTouched] = useState(false);
  useEffect(() => {
    if (open) {
      setReason('');
      setTouched(false);
    }
  }, [open]);
  const invalid = requireReason && reason.trim().length < 3;
  return (
    <Dialog open={open} onClose={loading ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="confirm-title">
      <DialogTitle id="confirm-title">{title}</DialogTitle>
      <DialogContent>
        {description && <DialogContentText component="div" sx={{ mb: requireReason || children ? 3 : 0 }}>{description}</DialogContentText>}
        {children}
        {requireReason && (
          <TextField
            autoFocus
            label={reasonLabel}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            onBlur={() => setTouched(true)}
            multiline
            minRows={2}
            required
            error={touched && invalid}
            helperText={touched && invalid ? 'Please document a reason (at least 3 characters).' : ' '}
            sx={{ mt: 1 }}
          />
        )}
        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button onClick={onClose} disabled={loading} color="inherit">
          {cancelLabel}
        </Button>
        <Button
          variant="contained"
          color={tone}
          disabled={loading || invalid}
          onClick={() => {
            setTouched(true);
            if (!invalid) onConfirm(requireReason ? reason.trim() : undefined);
          }}
        >
          {loading ? 'Working…' : confirmLabel}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

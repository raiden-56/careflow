'use client';

import { useEffect, useMemo, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Button, Collapse, Dialog, DialogActions, DialogContent, DialogTitle, Grid, Link as MuiLink, Stack } from '@mui/material';
import { clinicalApi, NoteType, noteTypeLabel, NoteView } from '@/lib/api/clinical';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z.object({
  noteType: z.string().min(1, 'Choose a note type'),
  title: z.string().trim().min(3, 'Give the note a short title').max(300),
  subjective: z.string().max(8000).optional(),
  objective: z.string().max(8000).optional(),
  assessment: z.string().max(8000).optional(),
  plan: z.string().max(8000).optional(),
  content: z.string().trim().min(3, 'Note content is required').max(20000),
  reason: z.string().max(1000).optional(),
});
type FormValues = z.infer<typeof schema>;

export interface NoteFormDialogProps {
  open: boolean;
  onClose: () => void;
  admissionId: string;
  /** Note types the user may choose (filter by permission before passing). */
  allowedTypes: NoteType[];
  defaultType?: NoteType;
  /** When set, the dialog amends this note (creates a new version referencing it; reason required). */
  amend?: NoteView;
  onSaved?: (note: NoteView) => void;
}

/**
 * Create or amend a clinical / nursing note. SOAP sections are optional; content is required.
 * Amendments never edit the original note - the server creates a new note and marks the original AMENDED.
 */
export function NoteFormDialog({ open, onClose, admissionId, allowedTypes, defaultType, amend, onSaved }: NoteFormDialogProps) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [showSoap, setShowSoap] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const initialType = amend?.noteType ?? defaultType ?? allowedTypes[0];
  const defaults = useMemo<FormValues>(
    () => ({
      noteType: initialType ?? '',
      title: amend?.title ?? '',
      subjective: amend?.subjective ?? '',
      objective: amend?.objective ?? '',
      assessment: amend?.assessment ?? '',
      plan: amend?.plan ?? '',
      content: amend?.content ?? '',
      reason: '',
    }),
    [amend, initialType],
  );
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<FormValues>({ resolver: zodResolver(schema), defaultValues: defaults });

  useEffect(() => {
    if (open) {
      reset(defaults);
      setError(null);
      setShowSoap(!!(amend && (amend.subjective || amend.objective || amend.assessment || amend.plan)));
    }
  }, [open, defaults, reset, amend]);

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const soap = {
        subjective: v.subjective?.trim() || null,
        objective: v.objective?.trim() || null,
        assessment: v.assessment?.trim() || null,
        plan: v.plan?.trim() || null,
      };
      if (amend) {
        return clinicalApi.amendNote(amend.id, { title: v.title.trim(), content: v.content.trim(), reason: (v.reason ?? '').trim(), ...soap });
      }
      return clinicalApi.addNote(admissionId, { noteType: v.noteType as NoteType, title: v.title.trim(), content: v.content.trim(), ...soap });
    },
    onSuccess: (n) => {
      toast.success(amend ? 'Amendment saved; the original is kept as amended' : `${noteTypeLabel(n.noteType)} saved`);
      queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
      queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
      queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
      onSaved?.(n);
      onClose();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });

  const submit = handleSubmit((v) => {
    if (amend && (v.reason ?? '').trim().length < 3) {
      setFieldError('reason', { message: 'Document the reason for the amendment' });
      return;
    }
    save.mutate(v);
  });

  return (
    <Dialog open={open} onClose={save.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="note-dialog-title">
      <DialogTitle id="note-dialog-title">{amend ? `Amend note: ${amend.title}` : 'New note'}</DialogTitle>
      <DialogContent>
        <Stack spacing={4} component="form" id="note-form" onSubmit={submit} sx={{ pt: 1 }} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          {amend && (
            <Alert severity="info">
              Amending creates a new version linked to the original. The original remains in the record, marked as amended.
            </Alert>
          )}
          <Grid container spacing={3}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <RHFSelect
                control={control}
                name="noteType"
                label="Note type"
                disabled={!!amend || allowedTypes.length < 2}
                options={(amend ? [amend.noteType] : allowedTypes).map((t) => ({ value: t, label: noteTypeLabel(t) }))}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 7 }}>
              <RHFTextField control={control} name="title" label="Title" required />
            </Grid>
          </Grid>
          <div>
            <MuiLink component="button" type="button" variant="body2" onClick={() => setShowSoap((s) => !s)} aria-expanded={showSoap}>
              {showSoap ? 'Hide structured sections' : 'Add structured sections (subjective, objective, assessment, plan)'}
            </MuiLink>
            <Collapse in={showSoap}>
              <Grid container spacing={3} sx={{ mt: 1 }}>
                <Grid size={{ xs: 12, md: 6 }}>
                  <RHFTextField control={control} name="subjective" label="Subjective" multiline minRows={2} />
                </Grid>
                <Grid size={{ xs: 12, md: 6 }}>
                  <RHFTextField control={control} name="objective" label="Objective" multiline minRows={2} />
                </Grid>
                <Grid size={{ xs: 12, md: 6 }}>
                  <RHFTextField control={control} name="assessment" label="Assessment" multiline minRows={2} />
                </Grid>
                <Grid size={{ xs: 12, md: 6 }}>
                  <RHFTextField control={control} name="plan" label="Plan" multiline minRows={2} />
                </Grid>
              </Grid>
            </Collapse>
          </div>
          <RHFTextField control={control} name="content" label="Note" required multiline minRows={5} />
          {amend && <RHFTextField control={control} name="reason" label="Reason for amendment" required multiline minRows={2} />}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="note-form" variant="contained" disabled={save.isPending}>
          {save.isPending ? 'Saving…' : amend ? 'Save amendment' : 'Save note'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

'use client';

import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { Bot } from 'lucide-react';
import { clinicalApi, DraftView } from '@/lib/api/clinical';
import { useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDateTime, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';

/**
 * Review of a rule-based draft summary. Every statement lists its source records. Accepting creates a
 * note authored by the reviewing clinician (optionally edited); rejecting keeps the draft out of the record.
 */
export function DraftReviewDialog({ draft, onClose }: { draft: DraftView | null; onClose: () => void }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [title, setTitle] = useState('');
  const [content, setContent] = useState('');
  const [rejecting, setRejecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (draft) {
      setTitle(draft.draftType === 'HANDOVER' ? 'Handover summary (reviewed draft)' : 'Progress summary (reviewed draft)');
      setContent(draft.content);
      setError(null);
    }
  }, [draft]);
  const refresh = () => {
    if (!draft) return;
    queryClient.invalidateQueries({ queryKey: ['clinical', draft.admissionId] });
    queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    queryClient.invalidateQueries({ queryKey: ['admission', draft.admissionId, 'timeline'] });
  };
  const accept = useMutation({
    mutationFn: () =>
      clinicalApi.acceptDraft(draft!.id, { title: title.trim() || undefined, editedContent: content.trim() !== draft!.content ? content.trim() : undefined }),
    onSuccess: () => {
      toast.success('Draft accepted and saved as your note');
      refresh();
      onClose();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const reject = useMutation({
    mutationFn: (reason: string) => clinicalApi.rejectDraft(draft!.id, reason),
    onSuccess: () => {
      toast.info('Draft rejected');
      setRejecting(false);
      refresh();
      onClose();
    },
    onError: toast.error,
  });
  const open = !!draft;
  const editable = draft?.status === 'DRAFT';
  return (
    <>
      <Dialog open={open} onClose={accept.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="draft-title">
        <DialogTitle id="draft-title">
          <Stack direction="row" spacing={2} alignItems="center">
            <Bot size={20} aria-hidden />
            <span>{draft ? humanize(draft.draftType) : 'Draft'}</span>
            {draft && <StatusChip value={draft.status} />}
          </Stack>
        </DialogTitle>
        <DialogContent>
          {draft && (
            <Stack spacing={4} sx={{ pt: 1 }}>
              <Alert severity="warning">{draft.disclaimer}</Alert>
              {error && <Alert severity="error">{error}</Alert>}
              <Box>
                <Typography variant="subtitle2" sx={{ mb: 2 }}>
                  Statements and their sources
                </Typography>
                <Stack spacing={2} divider={<Divider flexItem />}>
                  {draft.sourceRefs.map((r) => (
                    <Box key={r.index}>
                      <Typography variant="body2">{r.statement}</Typography>
                      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mt: 1 }}>
                        {r.sources.slice(0, 6).map((s, i) => (
                          <Chip
                            key={`${s.type}-${s.id ?? i}`}
                            size="small"
                            variant="outlined"
                            label={`${humanize(s.type)}${s.label ? ': ' + s.label : ''}${s.at ? ' · ' + formatDateTime(s.at, 'DD MMM HH:mm') : ''}`}
                          />
                        ))}
                        {r.sources.length > 6 && <Chip size="small" label={`+${r.sources.length - 6} more`} />}
                      </Stack>
                    </Box>
                  ))}
                </Stack>
              </Box>
              {editable ? (
                <>
                  <TextField label="Note title" value={title} onChange={(e) => setTitle(e.target.value)} />
                  <TextField
                    label="Note content (edit before accepting if needed)"
                    value={content}
                    onChange={(e) => setContent(e.target.value)}
                    multiline
                    minRows={8}
                    helperText="Accepting saves this text as a note authored by you."
                  />
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  {draft.status === 'ACCEPTED'
                    ? `Accepted by ${draft.reviewedBy ?? '—'} on ${formatDateTime(draft.reviewedAt)} and saved as a note.`
                    : `Rejected by ${draft.reviewedBy ?? '—'} on ${formatDateTime(draft.reviewedAt)}.`}
                </Typography>
              )}
              <Typography variant="caption" color="text.secondary">
                Generated by {draft.generator} for {draft.createdBy ?? '—'} · {formatDateTime(draft.createdAt)}
              </Typography>
            </Stack>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={onClose} disabled={accept.isPending}>
            Close
          </Button>
          {editable && (
            <>
              <Button color="error" onClick={() => setRejecting(true)} disabled={accept.isPending}>
                Reject
              </Button>
              <Button variant="contained" onClick={() => accept.mutate()} disabled={accept.isPending || content.trim().length < 3}>
                {accept.isPending ? 'Saving…' : 'Accept as my note'}
              </Button>
            </>
          )}
        </DialogActions>
      </Dialog>
      <ConfirmDialog
        open={rejecting}
        title="Reject draft?"
        description="The draft will not become part of the record."
        requireReason
        tone="error"
        confirmLabel="Reject draft"
        loading={reject.isPending}
        onConfirm={(r) => reject.mutate(r!)}
        onClose={() => setRejecting(false)}
      />
    </>
  );
}

'use client';

import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Chip,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import { Bot, ChevronDown, ChevronUp, FilePlus2, Send } from 'lucide-react';
import {
  ck,
  clinicalApi,
  DOCTOR_NOTE_TYPES,
  DraftView,
  NoteType,
  noteTypeLabel,
  NoteView,
  NURSING_NOTE_TYPES,
} from '@/lib/api/clinical';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { formatDateTime } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { NoteFormDialog } from './NoteFormDialog';
import { DraftReviewDialog } from './DraftReviewDialog';

type Filter = 'all' | 'doctor' | 'nursing';

function Section({ label, text }: { label: string; text?: string }) {
  if (!text) return null;
  return (
    <Box sx={{ mt: 2 }}>
      <Typography variant="caption" color="text.secondary" fontWeight={600} sx={{ textTransform: 'uppercase', letterSpacing: 0.4 }}>
        {label}
      </Typography>
      <Typography variant="body2" sx={{ whiteSpace: 'pre-line', wordBreak: 'break-word' }}>
        {text}
      </Typography>
    </Box>
  );
}

function NoteCard({
  note,
  canAmend,
  canRelease,
  onAmend,
  onError,
  onRelease,
}: {
  note: NoteView;
  canAmend: boolean;
  canRelease: boolean;
  onAmend: (n: NoteView) => void;
  onError: (n: NoteView) => void;
  onRelease: (n: NoteView) => void;
}) {
  const [open, setOpen] = useState(false);
  const inactive = note.status !== 'FINAL';
  const preview = note.assessment ?? note.content;
  return (
    <Box sx={{ py: 3 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between">
        <Box sx={{ minWidth: 0, flex: 1 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Chip size="small" label={noteTypeLabel(note.noteType)} color={NURSING_NOTE_TYPES.includes(note.noteType) ? 'secondary' : 'primary'} variant="outlined" />
            {note.status !== 'FINAL' && <StatusChip value={note.status} />}
            {note.amendsNoteId && <Chip size="small" label="Amendment" variant="outlined" />}
            {note.releasedToPatient && <StatusChip value="RELEASED" label="Shared with patient" tone="success" />}
          </Stack>
          <Typography
            variant="body2"
            fontWeight={600}
            sx={{ mt: 1, textDecoration: note.status === 'ENTERED_IN_ERROR' ? 'line-through' : undefined, wordBreak: 'break-word' }}
          >
            {note.title}
          </Typography>
          <Typography variant="caption" color="text.secondary">
            {note.authorName ?? '—'} · {formatDateTime(note.authoredAt)}
          </Typography>
          {!open && (
            <Typography
              variant="body2"
              color="text.secondary"
              sx={{ mt: 1, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', wordBreak: 'break-word' }}
            >
              {preview}
            </Typography>
          )}
        </Box>
        <Stack direction="row" spacing={1} sx={{ flexShrink: 0, alignSelf: { xs: 'flex-start', sm: 'flex-start' } }} flexWrap="wrap" useFlexGap>
          <Button size="small" onClick={() => setOpen((o) => !o)} endIcon={open ? <ChevronUp size={14} /> : <ChevronDown size={14} />} aria-expanded={open}>
            {open ? 'Less' : 'Read'}
          </Button>
          {!inactive && canAmend && (
            <Button size="small" onClick={() => onAmend(note)}>
              Amend
            </Button>
          )}
          {!inactive && canRelease && !note.releasedToPatient && (
            <Button size="small" startIcon={<Send size={14} />} onClick={() => onRelease(note)}>
              Release
            </Button>
          )}
          {!inactive && canAmend && (
            <Button size="small" color="error" onClick={() => onError(note)}>
              Entered in error
            </Button>
          )}
        </Stack>
      </Stack>
      <Collapse in={open} unmountOnExit>
        <Box sx={{ mt: 1, pl: { sm: 0 } }}>
          <Section label="Subjective" text={note.subjective} />
          <Section label="Objective" text={note.objective} />
          <Section label="Assessment" text={note.assessment} />
          <Section label="Plan" text={note.plan} />
          <Section label="Note" text={note.content} />
          {note.patientSummary && (
            <Alert severity={note.releasedToPatient ? 'success' : 'info'} variant="outlined" sx={{ mt: 2 }}>
              <Typography variant="caption" fontWeight={600} component="div">
                Patient-friendly summary {note.releasedToPatient ? `(shared ${formatDateTime(note.releasedAt)})` : '(not shared yet)'}
              </Typography>
              {note.patientSummary}
            </Alert>
          )}
          {note.correctionReason && (
            <Alert severity="warning" sx={{ mt: 2 }}>
              {note.status === 'ENTERED_IN_ERROR' ? 'Entered in error: ' : note.amendsNoteId ? 'Amendment reason: ' : 'Amended: '}
              {note.correctionReason}
            </Alert>
          )}
        </Box>
      </Collapse>
    </Box>
  );
}

/**
 * Clinical and nursing notes for an admission: filter by discipline, create, amend, mark entered in error,
 * release a plain-language summary to the patient, and generate rule-based draft summaries.
 */
export function NotesPanel({ admissionId, defaultFilter = 'all' }: { admissionId: string; defaultFilter?: Filter }) {
  const { me, hasPermission } = useAuth();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [filter, setFilter] = useState<Filter>(defaultFilter);
  const [size, setSize] = useState(10);
  const [creating, setCreating] = useState(false);
  const [amending, setAmending] = useState<NoteView | null>(null);
  const [erroring, setErroring] = useState<NoteView | null>(null);
  const [releasing, setReleasing] = useState<NoteView | null>(null);
  const [summary, setSummary] = useState('');
  const [releaseError, setReleaseError] = useState<string | null>(null);
  const [draft, setDraft] = useState<DraftView | null>(null);

  const allowedTypes = useMemo<NoteType[]>(
    () => [...(hasPermission('CLINICAL_NOTE_WRITE') ? DOCTOR_NOTE_TYPES : []), ...(hasPermission('NURSING_NOTE_WRITE') ? NURSING_NOTE_TYPES : [])],
    [hasPermission],
  );
  const types = filter === 'doctor' ? DOCTOR_NOTE_TYPES : filter === 'nursing' ? NURSING_NOTE_TYPES : undefined;
  const params = { type: types, page: 0, size };
  const q = useQuery({ queryKey: ck.notes(admissionId, params), queryFn: () => clinicalApi.notes(admissionId, params) });
  const drafts = useQuery({ queryKey: ck.drafts(admissionId), queryFn: () => clinicalApi.drafts(admissionId) });

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['clinical', admissionId] });
    queryClient.invalidateQueries({ queryKey: ['clinical-lists'] });
    queryClient.invalidateQueries({ queryKey: ['admission', admissionId, 'timeline'] });
  };
  const markError = useMutation({
    mutationFn: ({ note, reason }: { note: NoteView; reason: string }) => clinicalApi.noteInError(note.id, reason),
    onSuccess: () => {
      toast.success('Note marked as entered in error');
      setErroring(null);
      refresh();
    },
    onError: toast.error,
  });
  const release = useMutation({
    mutationFn: () => clinicalApi.releaseNote(releasing!.id, summary.trim()),
    onSuccess: () => {
      toast.success('Summary shared with the patient portal');
      setReleasing(null);
      refresh();
    },
    onError: (e) => setReleaseError(errorMessage(e)),
  });
  const generate = useMutation({
    mutationFn: () => clinicalApi.generateDraft(admissionId, hasPermission('CLINICAL_NOTE_WRITE') ? 'PROGRESS_SUMMARY' : 'HANDOVER'),
    onSuccess: (d) => {
      queryClient.invalidateQueries({ queryKey: ck.drafts(admissionId) });
      setDraft(d);
    },
    onError: toast.error,
  });

  const canWrite = allowedTypes.length > 0;
  const canRelease = hasPermission('RELEASE_TO_PATIENT');
  const pendingDrafts = (drafts.data ?? []).filter((d) => d.status === 'DRAFT');

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2} justifyContent="space-between" sx={{ mb: 3 }}>
        <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap>
          {(['all', 'doctor', 'nursing'] as Filter[]).map((f) => (
            <Chip
              key={f}
              label={f === 'all' ? 'All notes' : f === 'doctor' ? 'Medical' : 'Nursing'}
              clickable
              color={filter === f ? 'primary' : 'default'}
              variant={filter === f ? 'filled' : 'outlined'}
              onClick={() => setFilter(f)}
            />
          ))}
        </Stack>
        <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
          {hasPermission('AI_DRAFT_USE') && canWrite && (
            <Button variant="outlined" startIcon={<Bot size={16} />} onClick={() => generate.mutate()} disabled={generate.isPending}>
              {generate.isPending ? 'Preparing…' : 'Draft summary'}
            </Button>
          )}
          {canWrite && (
            <Button variant="contained" startIcon={<FilePlus2 size={16} />} onClick={() => setCreating(true)}>
              New note
            </Button>
          )}
        </Stack>
      </Stack>
      {pendingDrafts.length > 0 && (
        <Alert
          severity="info"
          sx={{ mb: 3 }}
          action={
            <Button color="inherit" size="small" onClick={() => setDraft(pendingDrafts[0])}>
              Review
            </Button>
          }
        >
          {pendingDrafts.length} draft summary awaiting review. Drafts are not part of the record until accepted.
        </Alert>
      )}
      {q.isLoading ? (
        <TableSkeleton rows={4} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.content.length ? (
        <EmptyState title="No notes yet" description="Assessments, progress and nursing notes appear here." compact />
      ) : (
        <>
          <Stack divider={<Divider flexItem />}>
            {q.data.content.map((n) => (
              <NoteCard
                key={n.id}
                note={n}
                canAmend={n.authorId === me?.id && allowedTypes.includes(n.noteType)}
                canRelease={canRelease}
                onAmend={setAmending}
                onError={setErroring}
                onRelease={(note) => {
                  setSummary(note.patientSummary ?? '');
                  setReleaseError(null);
                  setReleasing(note);
                }}
              />
            ))}
          </Stack>
          {q.data.totalElements > q.data.content.length && (
            <Button sx={{ mt: 2 }} onClick={() => setSize((s) => s + 10)} disabled={q.isFetching}>
              Show older notes ({q.data.totalElements - q.data.content.length} more)
            </Button>
          )}
        </>
      )}
      {(drafts.data ?? []).some((d) => d.status !== 'DRAFT') && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 3 }}>
          Draft history:{' '}
          {(drafts.data ?? [])
            .filter((d) => d.status !== 'DRAFT')
            .slice(0, 5)
            .map((d, i) => (
              <Box component="span" key={d.id}>
                {i > 0 ? ' · ' : ''}
                <Box component="button" type="button" onClick={() => setDraft(d)} sx={{ border: 0, bgcolor: 'transparent', p: 0, color: 'primary.main', cursor: 'pointer', font: 'inherit' }}>
                  {formatDateTime(d.createdAt, 'DD MMM HH:mm')} ({d.status.toLowerCase()})
                </Box>
              </Box>
            ))}
        </Typography>
      )}

      <NoteFormDialog open={creating} onClose={() => setCreating(false)} admissionId={admissionId} allowedTypes={allowedTypes} />
      <NoteFormDialog
        open={!!amending}
        onClose={() => setAmending(null)}
        admissionId={admissionId}
        allowedTypes={allowedTypes}
        amend={amending ?? undefined}
      />
      <ConfirmDialog
        open={!!erroring}
        title="Mark note as entered in error?"
        description="The note stays in the record, struck through, with your reason. If it was shared with the patient it is withdrawn from the portal."
        requireReason
        tone="error"
        confirmLabel="Mark entered in error"
        loading={markError.isPending}
        onConfirm={(reason) => erroring && markError.mutate({ note: erroring, reason: reason! })}
        onClose={() => setErroring(null)}
      />
      <Dialog open={!!releasing} onClose={release.isPending ? undefined : () => setReleasing(null)} maxWidth="sm" fullWidth>
        <DialogTitle>Share with patient</DialogTitle>
        <DialogContent>
          <Stack spacing={3} sx={{ pt: 1 }}>
            {releaseError && <Alert severity="error">{releaseError}</Alert>}
            <Typography variant="body2" color="text.secondary">
              Only this plain-language summary is shown in the patient portal (and to authorised caregivers), not the clinical note.
            </Typography>
            <TextField
              label="Patient-friendly summary"
              value={summary}
              onChange={(e) => setSummary(e.target.value)}
              multiline
              minRows={4}
              required
              helperText={`${summary.trim().length}/4000`}
            />
          </Stack>
        </DialogContent>
        <DialogActions sx={{ px: 6, pb: 4 }}>
          <Button color="inherit" onClick={() => setReleasing(null)} disabled={release.isPending}>
            Cancel
          </Button>
          <Button variant="contained" onClick={() => release.mutate()} disabled={release.isPending || summary.trim().length < 10 || summary.length > 4000}>
            {release.isPending ? 'Sharing…' : 'Share summary'}
          </Button>
        </DialogActions>
      </Dialog>
      <DraftReviewDialog draft={draft} onClose={() => setDraft(null)} />
    </Box>
  );
}

'use client';

import { useMemo, useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import {
  Box,
  Button,
  Card,
  Chip,
  Collapse,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Link as MuiLink,
  MenuItem,
  Stack,
  TablePagination,
  TextField,
  Typography,
} from '@mui/material';
import { FileText, Plus } from 'lucide-react';
import { ck, clinicalApi, NoteType, noteTypeLabel, NoteView, NURSING_NOTE_TYPES } from '@/lib/api/clinical';
import { admissionApi, qk } from '@/lib/api/core';
import type { AdmissionSummary } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { formatDateTime, fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { NoteFormDialog } from '@/components/clinical/notes/NoteFormDialog';

const OPEN_STATUSES = new Set(['PENDING_BED', 'ADMITTED', 'DISCHARGE_IN_PROGRESS']);
const SOAP: { key: 'subjective' | 'objective' | 'assessment' | 'plan'; label: string }[] = [
  { key: 'subjective', label: 'Subjective' },
  { key: 'objective', label: 'Objective' },
  { key: 'assessment', label: 'Assessment' },
  { key: 'plan', label: 'Plan' },
];

function NoteCard({ note, admission }: { note: NoteView; admission?: AdmissionSummary }) {
  const [expanded, setExpanded] = useState(false);
  const hasSoap = SOAP.some((s) => note[s.key]);
  const long = note.content.length > 280 || hasSoap;
  const inError = note.status === 'ENTERED_IN_ERROR';
  return (
    <Box sx={{ p: 4 }}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1} justifyContent="space-between" alignItems={{ sm: 'flex-start' }}>
        <Box sx={{ minWidth: 0 }}>
          <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
            <Chip size="small" variant="outlined" label={noteTypeLabel(note.noteType)} />
            {note.status !== 'FINAL' && <StatusChip value={note.status} />}
          </Stack>
          <Typography variant="body1" fontWeight={600} sx={{ mt: 1, wordBreak: 'break-word', textDecoration: inError ? 'line-through' : undefined }}>
            {note.title}
          </Typography>
          <Typography variant="caption" color="text.secondary" component="div">
            {admission ? (
              <>
                <Box component={Link} href={`/nurse/patients/${note.admissionId}?tab=notes`} sx={{ color: 'primary.main', fontWeight: 600, textDecoration: 'none' }}>
                  {admission.patient.fullName}
                </Box>
                {' · '}
                {admission.admissionNumber} ·{' '}
              </>
            ) : (
              <>
                <Box component={Link} href={`/nurse/patients/${note.admissionId}?tab=notes`} sx={{ color: 'primary.main', textDecoration: 'none' }}>
                  Open patient record
                </Box>
                {' · '}
              </>
            )}
            {note.authorName ?? 'Unknown author'} · {formatDateTime(note.authoredAt)} ({fromNow(note.authoredAt)})
          </Typography>
        </Box>
      </Stack>
      <Typography
        variant="body2"
        sx={{
          mt: 2,
          whiteSpace: 'pre-wrap',
          wordBreak: 'break-word',
          color: inError ? 'text.disabled' : undefined,
          ...(!expanded && long ? { display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden' } : {}),
        }}
      >
        {note.content}
      </Typography>
      {hasSoap && (
        <Collapse in={expanded}>
          <Box sx={{ mt: 2, display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' } }}>
            {SOAP.filter((s) => note[s.key]).map((s) => (
              <Box key={s.key} sx={{ minWidth: 0 }}>
                <Typography variant="caption" color="text.secondary">
                  {s.label}
                </Typography>
                <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                  {note[s.key]}
                </Typography>
              </Box>
            ))}
          </Box>
        </Collapse>
      )}
      {long && (
        <MuiLink component="button" type="button" variant="body2" onClick={() => setExpanded((e) => !e)} sx={{ mt: 1 }} aria-expanded={expanded}>
          {expanded ? 'Show less' : 'Show full note'}
        </MuiLink>
      )}
      {note.correctionReason && (
        <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
          {inError ? 'Entered in error' : 'Correction'}: {note.correctionReason}
        </Typography>
      )}
    </Box>
  );
}

function ChoosePatientDialog({
  open,
  onClose,
  onContinue,
  admissions,
  loading,
}: {
  open: boolean;
  onClose: () => void;
  onContinue: (admissionId: string, type: NoteType) => void;
  admissions: AdmissionSummary[];
  loading: boolean;
}) {
  const [admissionId, setAdmissionId] = useState('');
  const [type, setType] = useState<NoteType>('NURSING_ASSESSMENT');
  const eligible = admissions.filter((a) => OPEN_STATUSES.has(a.status));
  return (
    <Dialog open={open} onClose={onClose} maxWidth="xs" fullWidth aria-labelledby="choose-patient-title">
      <DialogTitle id="choose-patient-title">New nursing entry</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 2 }}>
          <TextField
            select
            label="Patient"
            required
            value={admissionId}
            onChange={(e) => setAdmissionId(e.target.value)}
            helperText={loading ? 'Loading your patients…' : eligible.length === 0 ? 'You have no assigned inpatients.' : undefined}
          >
            {eligible.map((a) => (
              <MenuItem key={a.id} value={a.id}>
                {a.patient.fullName} · {a.location ? `${a.location.wardName ?? ''} ${a.location.roomNumber ?? ''}-${a.location.bedNumber}`.trim() : 'awaiting bed'}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Entry type" value={type} onChange={(e) => setType(e.target.value as NoteType)}>
            {NURSING_NOTE_TYPES.map((t) => (
              <MenuItem key={t} value={t}>
                {noteTypeLabel(t)}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose}>
          Cancel
        </Button>
        <Button variant="contained" disabled={!admissionId} onClick={() => onContinue(admissionId, type)}>
          Continue
        </Button>
      </DialogActions>
    </Dialog>
  );
}

export default function NursingAssessmentsPage() {
  const { hasPermission } = useAuth();
  const canWrite = hasPermission('NURSING_NOTE_WRITE');
  const [types, setTypes] = useState<NoteType[]>([]);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [chooseOpen, setChooseOpen] = useState(false);
  const [noteTarget, setNoteTarget] = useState<{ admissionId: string; type: NoteType } | null>(null);

  const params = { type: types.length ? types : NURSING_NOTE_TYPES, page, size };
  const q = useQuery({ queryKey: ck.myNotes(params), queryFn: () => clinicalApi.myNotes(params), placeholderData: keepPreviousData });
  const admissions = useQuery({ queryKey: qk.myAdmissions, queryFn: admissionApi.my });
  const byId = useMemo(() => new Map((admissions.data ?? []).map((a) => [a.id, a])), [admissions.data]);
  const toggle = (t: NoteType) => {
    setTypes((cur) => (cur.includes(t) ? cur.filter((x) => x !== t) : [...cur, t]));
    setPage(0);
  };
  const notes = q.data?.content ?? [];

  return (
    <>
      <PageHeader
        title="Nursing assessments"
        subtitle="Nursing assessments, observations and patient education notes across your patients."
        breadcrumbs={[{ label: 'Nursing', href: '/nurse' }, { label: 'Nursing assessments' }]}
        actions={
          canWrite && (
            <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setChooseOpen(true)}>
              New assessment
            </Button>
          )
        }
      />
      <Card>
        <Stack direction="row" spacing={1} sx={{ p: 4 }} flexWrap="wrap" useFlexGap role="group" aria-label="Note type filter">
          <Chip
            label="All nursing notes"
            clickable
            color={types.length === 0 ? 'primary' : 'default'}
            variant={types.length === 0 ? 'filled' : 'outlined'}
            onClick={() => {
              setTypes([]);
              setPage(0);
            }}
          />
          {NURSING_NOTE_TYPES.map((t) => {
            const on = types.includes(t);
            return (
              <Chip key={t} label={noteTypeLabel(t)} clickable color={on ? 'primary' : 'default'} variant={on ? 'filled' : 'outlined'} onClick={() => toggle(t)} aria-pressed={on} />
            );
          })}
        </Stack>
        <Divider />
        {q.isLoading ? (
          <TableSkeleton rows={4} columns={2} />
        ) : q.error ? (
          <Box sx={{ p: 4 }}>
            <ErrorState error={q.error} onRetry={() => q.refetch()} />
          </Box>
        ) : notes.length === 0 ? (
          <EmptyState
            icon={FileText}
            title="No nursing notes yet"
            description="Assessments, observations and education notes you and colleagues record for your patients appear here."
            action={
              canWrite && (
                <Button variant="outlined" startIcon={<Plus size={16} />} onClick={() => setChooseOpen(true)}>
                  New assessment
                </Button>
              )
            }
          />
        ) : (
          <Box sx={{ opacity: q.isFetching ? 0.6 : 1, transition: 'opacity 120ms' }}>
            <Stack divider={<Divider />}>
              {notes.map((n) => (
                <NoteCard key={n.id} note={n} admission={byId.get(n.admissionId)} />
              ))}
            </Stack>
            <TablePagination
              component="div"
              count={q.data?.totalElements ?? 0}
              page={page}
              rowsPerPage={size}
              rowsPerPageOptions={[10, 20, 50]}
              onPageChange={(_, p) => setPage(p)}
              onRowsPerPageChange={(e) => {
                setSize(parseInt(e.target.value, 10));
                setPage(0);
              }}
              sx={{ borderTop: 1, borderColor: 'divider' }}
            />
          </Box>
        )}
      </Card>

      <ChoosePatientDialog
        open={chooseOpen}
        onClose={() => setChooseOpen(false)}
        admissions={admissions.data ?? []}
        loading={admissions.isLoading}
        onContinue={(admissionId, type) => {
          setChooseOpen(false);
          setNoteTarget({ admissionId, type });
        }}
      />
      {noteTarget && (
        <NoteFormDialog
          open
          onClose={() => setNoteTarget(null)}
          admissionId={noteTarget.admissionId}
          allowedTypes={NURSING_NOTE_TYPES}
          defaultType={noteTarget.type}
        />
      )}
    </>
  );
}

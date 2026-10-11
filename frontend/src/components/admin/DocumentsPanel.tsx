'use client';

import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  IconButton,
  MenuItem,
  Stack,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { Archive, Download, FileText, Image as ImageIcon, Send, Upload } from 'lucide-react';
import { documentApi, DocumentType, DocumentView } from '@/lib/api/admin';
import { errorMessage } from '@/lib/api/client';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useToast } from '@/lib/hooks';
import { formatDateTime, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';

export const DOCUMENT_TYPES: DocumentType[] = ['LAB_REPORT', 'IMAGING', 'DISCHARGE_SUMMARY', 'CONSENT_FORM', 'ID_PROOF', 'INSURANCE', 'REFERRAL', 'OTHER'];
const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPT = 'application/pdf,image/png,image/jpeg';

export function formatBytes(n: number) {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}

/** Downloads a document through a short-lived signed link (requested after the server's access check). */
export function useDocumentDownload() {
  const toast = useToast();
  const [busy, setBusy] = useState<string | null>(null);
  const download = async (d: DocumentView) => {
    setBusy(d.id);
    try {
      await documentApi.download(d.id, d.fileName);
    } catch (e) {
      toast.error(e);
    } finally {
      setBusy(null);
    }
  };
  return { download, busy };
}

/**
 * Patient documents for clinical screens: list, secure upload (PDF/PNG/JPEG ≤ 10 MB), release to the
 * patient portal, archive (never deleted) and audited download. Access is enforced by the API
 * (clinical access to the patient), so this panel can be embedded on any care-team page.
 */
export function DocumentsPanel({ patientId, admissionId, title = 'Documents' }: { patientId: string; admissionId?: string; title?: string }) {
  const toast = useToast();
  const qc = useQueryClient();
  const { hasPermission } = useAuth();
  const canUpload = hasPermission('DOCUMENT_UPLOAD');
  const canRelease = hasPermission('RELEASE_TO_PATIENT');
  const key = ['documents', patientId, admissionId ?? 'all'];
  const q = useQuery({ queryKey: key, queryFn: () => documentApi.list({ patientId, admissionId }) });
  const [upload, setUpload] = useState(false);
  const [release, setRelease] = useState<DocumentView | null>(null);
  const [archive, setArchive] = useState<DocumentView | null>(null);
  const { download, busy } = useDocumentDownload();
  const refresh = () => qc.invalidateQueries({ queryKey: ['documents', patientId] });
  const releaseM = useMutation({
    mutationFn: (d: DocumentView) => documentApi.release(d.id),
    onSuccess: () => {
      toast.success('Released to the patient portal');
      setRelease(null);
      refresh();
    },
    onError: toast.error,
  });
  const archiveM = useMutation({
    mutationFn: ({ d, reason }: { d: DocumentView; reason?: string }) => documentApi.archive(d.id, reason),
    onSuccess: () => {
      toast.success('Document archived');
      setArchive(null);
      refresh();
    },
    onError: toast.error,
  });

  return (
    <Box>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 3 }}>
        <Typography variant="h5" component="h3">
          {title}
        </Typography>
        {canUpload && (
          <Button size="small" variant="outlined" startIcon={<Upload size={14} />} onClick={() => setUpload(true)}>
            Upload
          </Button>
        )}
      </Stack>
      {q.isLoading ? (
        <TableSkeleton rows={3} columns={3} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : !q.data?.length ? (
        <EmptyState icon={FileText} title="No documents yet" description={canUpload ? 'Upload reports, images or forms (PDF, PNG or JPEG).' : undefined} compact />
      ) : (
        <Stack divider={<Divider flexItem />}>
          {q.data.map((d) => (
            <Stack key={d.id} direction="row" spacing={2} alignItems="center" sx={{ py: 2 }}>
              <Box sx={{ color: 'text.secondary', display: 'grid' }} aria-hidden>
                {d.contentType === 'application/pdf' ? <FileText size={18} /> : <ImageIcon size={18} />}
              </Box>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" fontWeight={500} sx={{ wordBreak: 'break-word' }}>
                  {d.title}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {humanize(d.documentType)} · {formatBytes(d.sizeBytes)} · {formatDateTime(d.uploadedAt)}
                  {d.uploadedByName ? ` · ${d.uploadedByName}` : ''}
                </Typography>
              </Box>
              {d.releasedToPatient ? <StatusChip value="RELEASED" label="Shared with patient" tone="success" /> : null}
              <Tooltip title="Download">
                <IconButton size="small" aria-label={`Download ${d.title}`} disabled={busy === d.id} onClick={() => download(d)}>
                  <Download size={16} />
                </IconButton>
              </Tooltip>
              {canRelease && !d.releasedToPatient && (
                <Tooltip title="Release to patient portal">
                  <IconButton size="small" aria-label={`Release ${d.title} to patient`} onClick={() => setRelease(d)}>
                    <Send size={16} />
                  </IconButton>
                </Tooltip>
              )}
              {canUpload && (
                <Tooltip title="Archive">
                  <IconButton size="small" aria-label={`Archive ${d.title}`} onClick={() => setArchive(d)}>
                    <Archive size={16} />
                  </IconButton>
                </Tooltip>
              )}
            </Stack>
          ))}
        </Stack>
      )}
      <UploadDialog open={upload} patientId={patientId} admissionId={admissionId} onClose={() => setUpload(false)} onDone={() => { setUpload(false); refresh(); }} />
      <ConfirmDialog
        open={!!release}
        title="Release to the patient portal?"
        description={`"${release?.title}" becomes visible to the patient and any caregiver with clinical access. Make sure it has been reviewed.`}
        confirmLabel="Release"
        loading={releaseM.isPending}
        onConfirm={() => release && releaseM.mutate(release)}
        onClose={() => setRelease(null)}
      />
      <ConfirmDialog
        open={!!archive}
        title="Archive this document?"
        description="Archived documents are hidden from lists and the patient portal but kept for the record. They are never deleted."
        tone="warning"
        requireReason
        confirmLabel="Archive"
        loading={archiveM.isPending}
        onConfirm={(reason) => archive && archiveM.mutate({ d: archive, reason })}
        onClose={() => setArchive(null)}
      />
    </Box>
  );
}

function UploadDialog({ open, patientId, admissionId, onClose, onDone }: { open: boolean; patientId: string; admissionId?: string; onClose: () => void; onDone: () => void }) {
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [type, setType] = useState<DocumentType>('LAB_REPORT');
  const [error, setError] = useState<string | null>(null);
  const m = useMutation({
    mutationFn: () => {
      const form = new FormData();
      form.append('file', file!);
      form.append('patientId', patientId);
      if (admissionId) form.append('admissionId', admissionId);
      form.append('documentType', type);
      form.append('title', title.trim());
      return documentApi.upload(form);
    },
    onSuccess: () => {
      toast.success('Document uploaded');
      setFile(null);
      setTitle('');
      onDone();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const pick = (f: File | null) => {
    setError(null);
    if (!f) return setFile(null);
    if (!ACCEPT.split(',').includes(f.type)) return setError('Only PDF, PNG and JPEG files are accepted.');
    if (f.size > MAX_BYTES) return setError('Files must be 10 MB or smaller.');
    setFile(f);
    if (!title) setTitle(f.name.replace(/\.[^.]+$/, ''));
  };
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="upload-title">
      <DialogTitle id="upload-title">Upload document</DialogTitle>
      <DialogContent>
        <Stack spacing={3} sx={{ pt: 1 }}>
          {error && <Alert severity="error">{error}</Alert>}
          <input ref={input} type="file" accept={ACCEPT} hidden onChange={(e) => pick(e.target.files?.[0] ?? null)} aria-label="Choose file" />
          <Button variant="outlined" startIcon={<Upload size={16} />} onClick={() => input.current?.click()}>
            {file ? 'Choose a different file' : 'Choose file'}
          </Button>
          {file && (
            <Typography variant="body2" sx={{ wordBreak: 'break-all' }}>
              {file.name} · {formatBytes(file.size)}
            </Typography>
          )}
          <TextField select label="Document type" value={type} onChange={(e) => setType(e.target.value as DocumentType)}>
            {DOCUMENT_TYPES.map((t) => (
              <MenuItem key={t} value={t}>
                {humanize(t)}
              </MenuItem>
            ))}
          </TextField>
          <TextField label="Title" value={title} onChange={(e) => setTitle(e.target.value)} required slotProps={{ htmlInput: { maxLength: 300 } }} />
          <Typography variant="caption" color="text.secondary">
            PDF, PNG or JPEG up to 10 MB. The file content is verified on the server. New documents are visible to the care team only until released.
          </Typography>
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button variant="contained" disabled={!file || !title.trim() || m.isPending} onClick={() => m.mutate()}>
          {m.isPending ? 'Uploading…' : 'Upload'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

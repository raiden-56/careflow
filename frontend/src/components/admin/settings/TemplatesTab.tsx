'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  IconButton,
  Stack,
  Tooltip,
  Typography,
} from '@mui/material';
import { Pencil, Plus } from 'lucide-react';
import { adminApi, Channel, TemplateView } from '@/lib/api/admin';
import { applyServerErrors, useDebouncedValue, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { fromNow, humanize } from '@/lib/format';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z
  .object({
    code: z.string().trim().regex(/^[A-Z][A-Z0-9_]{2,63}$/, 'Use upper-case letters, digits and _ (3-64)'),
    channel: z.enum(['IN_APP', 'EMAIL', 'SMS']),
    subject: z.string().trim().min(1, 'Subject is required').max(300),
    body: z.string().trim().min(1, 'Message body is required').max(4000),
    active: z.boolean(),
  })
  .superRefine((v, ctx) => {
    if (v.channel === 'SMS' && v.body.length > 480) ctx.addIssue({ code: 'custom', path: ['body'], message: 'SMS templates are limited to 480 characters' });
  });
type Values = z.infer<typeof schema>;

export function TemplatesTab() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['settings', 'templates'], queryFn: adminApi.templates });
  const [dialog, setDialog] = useState<{ open: boolean; t?: TemplateView }>({ open: false });
  return (
    <Card>
      <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={2} sx={{ p: 4 }}>
        <Box>
          <Typography variant="h4" component="h2">
            Notification templates
          </Typography>
          <Typography variant="body2" color="text.secondary">
            Placeholders like {'{{patient}}'} are filled in when a message is sent. Email and SMS use development adapters in this build.
          </Typography>
        </Box>
        <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setDialog({ open: true })} sx={{ alignSelf: { sm: 'flex-start' }, flexShrink: 0 }}>
          New template
        </Button>
      </Stack>
      <DataTable<TemplateView>
        ariaLabel="Notification templates"
        rows={q.data}
        loading={q.isFetching}
        error={q.error}
        onRetry={() => q.refetch()}
        rowKey={(t) => t.id}
        emptyTitle="No templates"
        columns={[
          {
            key: 'code',
            header: 'Template',
            primary: true,
            render: (t) => (
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  {t.subject}
                </Typography>
                <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                  {t.code}
                </Typography>
              </Box>
            ),
          },
          { key: 'channel', header: 'Channel', render: (t) => <Chip size="small" label={humanize(t.channel)} variant="outlined" /> },
          { key: 'ph', header: 'Placeholders', hideOnMobile: true, render: (t) => (t.placeholders.length ? t.placeholders.join(', ') : '—') },
          { key: 'upd', header: 'Updated', render: (t) => `${fromNow(t.updatedAt)}${t.updatedBy ? ' · ' + t.updatedBy : ''}` },
          { key: 'status', header: 'Status', render: (t) => <StatusChip value={t.active ? 'ACTIVE' : 'INACTIVE'} /> },
          {
            key: 'edit',
            header: '',
            align: 'right',
            render: (t) => (
              <Tooltip title="Edit">
                <IconButton size="small" aria-label={`Edit ${t.code} ${t.channel}`} onClick={() => setDialog({ open: true, t })}>
                  <Pencil size={16} />
                </IconButton>
              </Tooltip>
            ),
          },
        ]}
      />
      <TemplateDialog
        open={dialog.open}
        template={dialog.t}
        onClose={() => setDialog({ open: false })}
        onSaved={() => {
          toast.success('Template saved');
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['settings', 'templates'] });
        }}
      />
    </Card>
  );
}

function TemplateDialog({ open, template, onClose, onSaved }: { open: boolean; template?: TemplateView; onClose: () => void; onSaved: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<Values>({ resolver: zodResolver(schema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: template?.code ?? '',
        channel: (template?.channel ?? 'IN_APP') as Channel,
        subject: template?.subject ?? '',
        body: template?.body ?? '',
        active: template?.active ?? true,
      });
    }
  }, [open, template, reset]);
  const subject = useDebouncedValue(watch('subject') ?? '', 400);
  const body = useDebouncedValue(watch('body') ?? '', 400);
  const channel = watch('channel');
  const preview = useQuery({
    queryKey: ['settings', 'template-preview', subject, body, channel],
    queryFn: () => adminApi.previewTemplate({ subject, body, channel }),
    enabled: open && !!subject.trim() && !!body.trim(),
  });
  const m = useMutation({
    mutationFn: (v: Values) => (template ? adminApi.updateTemplate(template.id, { ...v, version: template.version }) : adminApi.createTemplate(v)),
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="tpl-title">
      <DialogTitle id="tpl-title">{template ? `Edit ${template.code}` : 'New notification template'}</DialogTitle>
      <DialogContent>
        <Grid container spacing={4} sx={{ pt: 1 }}>
          <Grid size={{ xs: 12, md: 7 }}>
            <Stack component="form" id="tpl-form" spacing={3} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
              {error && <Alert severity="error">{error}</Alert>}
              <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
                <RHFTextField control={control} name="code" label="Code" required disabled={!!template} sx={{ flex: 2 }} />
                <RHFSelect control={control} name="channel" label="Channel" disabled={!!template} options={['IN_APP', 'EMAIL', 'SMS'].map((c) => ({ value: c, label: humanize(c) }))} sx={{ flex: 1 }} />
              </Stack>
              <RHFTextField control={control} name="subject" label="Subject / title" required fullWidth />
              <RHFTextField control={control} name="body" label="Message" required fullWidth multiline minRows={5} />
              <RHFCheckbox control={control} name="active" label="Active" helperText="Inactive templates are not used for sending." />
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, md: 5 }}>
            <Typography variant="subtitle2" sx={{ mb: 2 }}>
              Preview with sample values
            </Typography>
            <Card variant="outlined" sx={{ p: 3, bgcolor: 'background.default' }}>
              {preview.data ? (
                <>
                  <Typography variant="body2" fontWeight={600} sx={{ wordBreak: 'break-word' }}>
                    {preview.data.subject}
                  </Typography>
                  <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap', mt: 1, wordBreak: 'break-word' }}>
                    {preview.data.body}
                  </Typography>
                  {preview.data.smsSegments && (
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 2 }}>
                      {preview.data.smsSegments} SMS segment{preview.data.smsSegments > 1 ? 's' : ''}
                    </Typography>
                  )}
                </>
              ) : (
                <Typography variant="body2" color="text.secondary">
                  Enter a subject and message to see a preview.
                </Typography>
              )}
            </Card>
            {preview.data?.unknownPlaceholders.length ? (
              <Alert severity="warning" sx={{ mt: 3 }}>
                Unknown placeholders: {preview.data.unknownPlaceholders.map((p) => `{{${p}}}`).join(', ')}. They will not be filled in.
              </Alert>
            ) : null}
            <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 3 }}>
              Available placeholders
            </Typography>
            <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1} sx={{ mt: 1 }}>
              {Object.keys(preview.data?.knownPlaceholders ?? { hospital: '', patient: '', title: '', when: '', location: '', amount: '', invoice: '', receipt: '', doctor: '', ward: '' }).map((p) => (
                <Chip key={p} size="small" label={`{{${p}}}`} variant="outlined" sx={{ fontFamily: 'monospace' }} />
              ))}
            </Stack>
          </Grid>
        </Grid>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="tpl-form" variant="contained" disabled={m.isPending}>
          Save template
        </Button>
      </DialogActions>
    </Dialog>
  );
}

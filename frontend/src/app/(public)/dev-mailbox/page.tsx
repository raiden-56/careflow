'use client';

import Link from 'next/link';
import { useQuery } from '@tanstack/react-query';
import { Alert, Box, Chip, Divider, Stack, Typography } from '@mui/material';
import { authApi } from '@/lib/api/core';
import { formatDateTime } from '@/lib/format';
import { ErrorState, TableSkeleton } from '@/components/ui/States';

/** DEVELOPMENT ONLY: messages captured by the dev email/SMS adapter (404 unless enabled on the server). */
export default function DevMailboxPage() {
  const q = useQuery({ queryKey: ['dev-outbox'], queryFn: authApi.devOutbox, refetchInterval: 5000, retry: false });
  const linkify = (body: string) =>
    body.split(/(https?:\/\/[^\s]+)/g).map((part, i) =>
      /^https?:\/\//.test(part) ? (
        <a key={i} href={part.replace(/^https?:\/\/[^/]+/, '')}>
          {part}
        </a>
      ) : (
        <span key={i}>{part}</span>
      ),
    );
  return (
    <Stack spacing={4}>
      <Typography variant="h2" component="h1">
        Development mailbox
      </Typography>
      <Alert severity="warning">
        Development adapter only. Messages are stored locally and never sent. This page is disabled in production.
      </Alert>
      {q.isLoading && <TableSkeleton rows={3} columns={1} />}
      {q.error && <ErrorState error={q.error} title="Mailbox unavailable" />}
      <Box sx={{ maxHeight: 460, overflowY: 'auto' }}>
        {q.data?.length === 0 && <Typography color="text.secondary">No messages yet.</Typography>}
        {q.data?.map((m) => (
          <Box key={m.id} sx={{ py: 3 }}>
            <Stack direction="row" spacing={1} alignItems="center">
              <Chip size="small" label={m.channel} />
              <Typography variant="body2" fontWeight={600}>
                {m.subject}
              </Typography>
            </Stack>
            <Typography variant="caption" color="text.secondary">
              To {m.destination} · {formatDateTime(m.createdAt)}
            </Typography>
            <Typography variant="body2" sx={{ whiteSpace: 'pre-line', mt: 1, wordBreak: 'break-word' }}>
              {linkify(m.body)}
            </Typography>
            <Divider sx={{ mt: 3 }} />
          </Box>
        ))}
      </Box>
      <Typography component={Link} href="/login" variant="body2" color="primary" sx={{ textDecoration: 'none' }}>
        ← Back to sign in
      </Typography>
    </Stack>
  );
}

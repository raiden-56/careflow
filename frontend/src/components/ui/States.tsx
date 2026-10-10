'use client';

import { ReactNode } from 'react';
import { Alert, AlertTitle, Box, Button, Card, Skeleton, Stack, Typography } from '@mui/material';
import { Inbox, RotateCcw, ShieldX } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { ApiError } from '@/lib/api/client';
import { colors } from '@/theme/tokens';

export interface EmptyStateProps {
  title: string;
  description?: ReactNode;
  icon?: LucideIcon;
  action?: ReactNode;
  compact?: boolean;
}

export function EmptyState({ title, description, icon: Icon = Inbox, action, compact }: EmptyStateProps) {
  return (
    <Stack alignItems="center" textAlign="center" spacing={2} sx={{ py: compact ? 6 : 12, px: 4 }} role="status">
      <Box
        sx={{ width: 48, height: 48, borderRadius: 999, display: 'grid', placeItems: 'center', bgcolor: colors.neutralSoft, color: colors.neutral }}
        aria-hidden
      >
        <Icon size={22} />
      </Box>
      <Typography variant="h5" component="p">
        {title}
      </Typography>
      {description && (
        <Typography variant="body2" color="text.secondary" sx={{ maxWidth: 440 }} component="div">
          {description}
        </Typography>
      )}
      {action && <Box sx={{ pt: 1 }}>{action}</Box>}
    </Stack>
  );
}

export interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}

/** Inline error with a retry action; maps 403/404 to clear, non-technical messages. */
export function ErrorState({ error, onRetry, title }: ErrorStateProps) {
  const e = error instanceof ApiError ? error : null;
  if (e?.status === 403) {
    return (
      <EmptyState
        icon={ShieldX}
        title="You don't have access to this"
        description="Your role does not include permission for this information. Contact your administrator if you believe this is wrong."
      />
    );
  }
  if (e?.status === 404) {
    return <EmptyState title="Not found" description="This record does not exist or is not available to you." />;
  }
  return (
    <Alert
      severity="error"
      action={
        onRetry && (
          <Button color="inherit" size="small" startIcon={<RotateCcw size={14} />} onClick={onRetry}>
            Retry
          </Button>
        )
      }
    >
      <AlertTitle>{title ?? 'Could not load data'}</AlertTitle>
      {error instanceof Error ? error.message : 'An unexpected error occurred.'}
      {e?.traceId && (
        <Typography variant="caption" component="div" sx={{ mt: 1, opacity: 0.8 }}>
          Reference: {e.traceId}
        </Typography>
      )}
    </Alert>
  );
}

export function TableSkeleton({ rows = 6, columns = 5 }: { rows?: number; columns?: number }) {
  return (
    <Box sx={{ p: 4 }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: rows }).map((_, r) => (
        <Stack key={r} direction="row" spacing={4} sx={{ py: 2 }}>
          {Array.from({ length: columns }).map((__, c) => (
            <Skeleton key={c} variant="text" sx={{ flex: c === 0 ? 2 : 1 }} />
          ))}
        </Stack>
      ))}
    </Box>
  );
}

export function CardsSkeleton({ count = 4, height = 110 }: { count?: number; height?: number }) {
  return (
    <Box
      sx={{ display: 'grid', gap: 4, gridTemplateColumns: { xs: '1fr', sm: '1fr 1fr', lg: `repeat(${Math.min(count, 4)}, 1fr)` } }}
      aria-busy="true"
    >
      {Array.from({ length: count }).map((_, i) => (
        <Card key={i} sx={{ p: 4 }}>
          <Skeleton width="50%" />
          <Skeleton width="30%" height={height / 2.5} />
          <Skeleton width="70%" />
        </Card>
      ))}
    </Box>
  );
}

export function PageSkeleton() {
  return (
    <Stack spacing={5} aria-busy="true">
      <Box>
        <Skeleton width={220} height={40} />
        <Skeleton width={340} />
      </Box>
      <CardsSkeleton />
      <Card>
        <TableSkeleton />
      </Card>
    </Stack>
  );
}

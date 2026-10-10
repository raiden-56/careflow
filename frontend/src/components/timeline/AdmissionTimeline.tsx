'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Button, Chip, Stack } from '@mui/material';
import { admissionApi, qk } from '@/lib/api/core';
import type { TimelineCategory } from '@/lib/api/types';
import { humanize } from '@/lib/format';
import { ErrorState, TableSkeleton } from '@/components/ui/States';
import { Timeline } from './Timeline';

const CATEGORIES: TimelineCategory[] = [
  'ADMISSION',
  'CLINICAL',
  'MEDICATION',
  'OBSERVATION',
  'INVESTIGATION',
  'NURSING',
  'TRANSFER',
  'BILLING',
  'DISCHARGE',
];

/** Self-loading admission timeline with category filters. The server applies role visibility. */
export function AdmissionTimeline({ admissionId, categories = CATEGORIES }: { admissionId: string; categories?: TimelineCategory[] }) {
  const [filter, setFilter] = useState<TimelineCategory[]>([]);
  const [size, setSize] = useState(30);
  const params = { category: filter, size };
  const q = useQuery({
    queryKey: qk.timeline(admissionId, params),
    queryFn: () => admissionApi.timeline(admissionId, params),
  });
  const toggle = (c: TimelineCategory) => setFilter((f) => (f.includes(c) ? f.filter((x) => x !== c) : [...f, c]));
  return (
    <Box>
      <Stack direction="row" spacing={1} flexWrap="wrap" useFlexGap sx={{ mb: 4 }} role="group" aria-label="Filter timeline">
        {categories.map((c) => (
          <Chip
            key={c}
            label={humanize(c)}
            size="small"
            clickable
            color={filter.includes(c) ? 'primary' : 'default'}
            variant={filter.includes(c) ? 'filled' : 'outlined'}
            onClick={() => toggle(c)}
          />
        ))}
        {filter.length > 0 && (
          <Button size="small" onClick={() => setFilter([])}>
            Clear
          </Button>
        )}
      </Stack>
      {q.isLoading ? (
        <TableSkeleton rows={5} columns={2} />
      ) : q.error ? (
        <ErrorState error={q.error} onRetry={() => q.refetch()} />
      ) : (
        <>
          <Timeline items={q.data?.content ?? []} />
          {q.data && q.data.totalElements > q.data.content.length && (
            <Button sx={{ mt: 4 }} onClick={() => setSize((s) => s + 30)}>
              Load older events
            </Button>
          )}
        </>
      )}
    </Box>
  );
}

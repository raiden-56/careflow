'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Box, Card, CardActionArea, Chip, FormControlLabel, MenuItem, Stack, Switch, TextField, Typography } from '@mui/material';
import { BedDouble, Check } from 'lucide-react';
import { facilityApi, qk } from '@/lib/api/core';
import type { BedType, BedView } from '@/lib/api/types';
import { humanize } from '@/lib/format';
import { EmptyState, ErrorState, TableSkeleton } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

export interface BedPickerProps {
  value?: string | null;
  onChange: (bed: BedView | null) => void;
  requireIsolation?: boolean;
  defaultWardId?: string;
}

/** Lists currently AVAILABLE beds (server-filtered) for assignment. Conflicts are still checked server-side. */
export function BedPicker({ value, onChange, requireIsolation, defaultWardId }: BedPickerProps) {
  const [wardId, setWardId] = useState(defaultWardId ?? '');
  const [bedType, setBedType] = useState<BedType | ''>('');
  const [isolationOnly, setIsolationOnly] = useState(!!requireIsolation);
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });
  const params = { wardId: wardId || undefined, bedType: bedType || undefined, isolationOnly };
  const beds = useQuery({ queryKey: qk.availableBeds(params), queryFn: () => facilityApi.availableBeds(params) });

  return (
    <Box>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ mb: 4 }} alignItems={{ sm: 'center' }}>
        <TextField select label="Ward" value={wardId} onChange={(e) => setWardId(e.target.value)} sx={{ maxWidth: { sm: 240 } }}>
          <MenuItem value="">All wards</MenuItem>
          {wards.data?.filter((w) => w.active).map((w) => (
            <MenuItem key={w.id} value={w.id}>
              {w.name} ({w.available} free)
            </MenuItem>
          ))}
        </TextField>
        <TextField select label="Bed type" value={bedType} onChange={(e) => setBedType(e.target.value as BedType)} sx={{ maxWidth: { sm: 200 } }}>
          <MenuItem value="">Any type</MenuItem>
          {(['STANDARD', 'ICU', 'ISOLATION', 'PAEDIATRIC', 'BARIATRIC', 'MATERNITY'] as BedType[]).map((t) => (
            <MenuItem key={t} value={t}>
              {humanize(t)}
            </MenuItem>
          ))}
        </TextField>
        <FormControlLabel
          control={<Switch checked={isolationOnly} onChange={(e) => setIsolationOnly(e.target.checked)} disabled={requireIsolation} />}
          label="Isolation-capable only"
        />
      </Stack>
      {beds.isLoading ? (
        <TableSkeleton rows={3} columns={3} />
      ) : beds.error ? (
        <ErrorState error={beds.error} onRetry={() => beds.refetch()} />
      ) : !beds.data?.length ? (
        <EmptyState icon={BedDouble} title="No available beds match" description="Try another ward or bed type, or admit now and assign a bed when one becomes available." compact />
      ) : (
        <Box
          role="radiogroup"
          aria-label="Available beds"
          sx={{ display: 'grid', gap: 2, gridTemplateColumns: { xs: '1fr 1fr', sm: 'repeat(3, 1fr)', md: 'repeat(4, 1fr)' }, maxHeight: 340, overflowY: 'auto', p: 0.5 }}
        >
          {beds.data.map((b) => {
            const selected = value === b.id;
            return (
              <Card key={b.id} sx={{ borderColor: selected ? colors.primary : undefined, borderWidth: selected ? 2 : 1, boxShadow: 'none' }}>
                <CardActionArea role="radio" aria-checked={selected} onClick={() => onChange(selected ? null : b)} sx={{ p: 3 }}>
                  <Stack direction="row" justifyContent="space-between" alignItems="flex-start">
                    <Box>
                      <Typography variant="body2" fontWeight={700}>
                        {b.roomNumber}-{b.bedNumber}
                      </Typography>
                      <Typography variant="caption" color="text.secondary" component="div">
                        {b.wardName}
                      </Typography>
                    </Box>
                    {selected && <Check size={18} color={colors.primary} />}
                  </Stack>
                  <Stack direction="row" spacing={0.5} sx={{ mt: 1.5 }} flexWrap="wrap" useFlexGap>
                    <Chip size="small" label={humanize(b.roomType)} />
                    {b.isolationCapable && <Chip size="small" label="Isolation" color="warning" variant="outlined" />}
                    {b.oxygenSupported && <Chip size="small" label="O₂" variant="outlined" />}
                  </Stack>
                </CardActionArea>
              </Card>
            );
          })}
        </Box>
      )}
    </Box>
  );
}

'use client';

import { ReactNode } from 'react';
import Link from 'next/link';
import { Box, Card, CardActionArea, Skeleton, Stack, Typography } from '@mui/material';
import type { LucideIcon } from 'lucide-react';
import { StatusTone } from '@/lib/status';
import { toneColors } from './StatusChip';

export interface KpiCardProps {
  label: string;
  value: ReactNode;
  hint?: ReactNode;
  icon?: LucideIcon;
  tone?: StatusTone;
  href?: string;
  loading?: boolean;
}

/** Headline metric. Values must come from the API; never compute financial totals in the UI. */
export function KpiCard({ label, value, hint, icon: Icon, tone = 'primary', href, loading }: KpiCardProps) {
  const t = toneColors(tone);
  const body = (
    <Stack direction="row" spacing={3} alignItems="flex-start" sx={{ p: 4, height: '100%' }}>
      {Icon && (
        <Box
          sx={{
            width: 40,
            height: 40,
            borderRadius: 2.5,
            display: 'grid',
            placeItems: 'center',
            bgcolor: t.bg,
            color: t.fg,
            flexShrink: 0,
          }}
          aria-hidden
        >
          <Icon size={20} />
        </Box>
      )}
      <Box sx={{ minWidth: 0 }}>
        <Typography variant="body2" color="text.secondary" fontWeight={500} noWrap>
          {label}
        </Typography>
        {loading ? (
          <Skeleton width={80} height={36} />
        ) : (
          <Typography variant="h2" component="div" sx={{ mt: 0.5, lineHeight: 1.2 }}>
            {value}
          </Typography>
        )}
        {hint && (
          <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
            {hint}
          </Typography>
        )}
      </Box>
    </Stack>
  );
  return (
    <Card sx={{ height: '100%' }}>
      {href ? (
        <CardActionArea component={Link} href={href} sx={{ height: '100%' }}>
          {body}
        </CardActionArea>
      ) : (
        body
      )}
    </Card>
  );
}

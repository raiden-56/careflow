'use client';

import { Box, Stack, Typography } from '@mui/material';
import {
  Activity,
  ArrowLeftRight,
  BedDouble,
  ClipboardList,
  FileCheck2,
  FlaskConical,
  HeartPulse,
  Pill,
  Receipt,
  ShieldAlert,
  Stethoscope,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { TimelineCategory, TimelineItem } from '@/lib/api/types';
import { formatDateTime, humanize } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

const ICONS: Record<TimelineCategory, { icon: LucideIcon; color: string; bg: string }> = {
  ADMISSION: { icon: BedDouble, color: colors.primaryDark, bg: colors.primarySoft },
  CLINICAL: { icon: Stethoscope, color: colors.info, bg: colors.infoSoft },
  MEDICATION: { icon: Pill, color: colors.secondary, bg: colors.secondarySoft },
  OBSERVATION: { icon: HeartPulse, color: colors.danger, bg: colors.dangerSoft },
  INVESTIGATION: { icon: FlaskConical, color: colors.info, bg: colors.infoSoft },
  NURSING: { icon: ClipboardList, color: colors.secondary, bg: colors.secondarySoft },
  BILLING: { icon: Receipt, color: colors.warning, bg: colors.warningSoft },
  DISCHARGE: { icon: FileCheck2, color: colors.success, bg: colors.successSoft },
  TRANSFER: { icon: ArrowLeftRight, color: colors.primaryDark, bg: colors.primarySoft },
  SAFETY: { icon: ShieldAlert, color: colors.danger, bg: colors.dangerSoft },
};

/** Chronological, role-filtered event list (newest first). */
export function Timeline({ items, emptyText = 'No events recorded yet.' }: { items: TimelineItem[]; emptyText?: string }) {
  if (items.length === 0) return <EmptyState title="No timeline events" description={emptyText} icon={Activity} compact />;
  return (
    <Box component="ol" sx={{ listStyle: 'none', m: 0, p: 0, position: 'relative' }} aria-label="Timeline">
      {items.map((e, i) => {
        const meta = ICONS[e.category] ?? ICONS.CLINICAL;
        const Icon = meta.icon;
        return (
          <Box component="li" key={e.id} sx={{ display: 'flex', gap: 3, position: 'relative', pb: i === items.length - 1 ? 0 : 5 }}>
            {i < items.length - 1 && (
              <Box aria-hidden sx={{ position: 'absolute', left: 17, top: 36, bottom: 0, width: 2, bgcolor: 'divider' }} />
            )}
            <Box
              aria-hidden
              sx={{ width: 36, height: 36, borderRadius: 999, bgcolor: meta.bg, color: meta.color, display: 'grid', placeItems: 'center', flexShrink: 0 }}
            >
              <Icon size={17} />
            </Box>
            <Box sx={{ minWidth: 0, flex: 1, pt: 0.5 }}>
              <Stack direction="row" spacing={2} alignItems="center" flexWrap="wrap" useFlexGap>
                <Typography variant="body2" fontWeight={600}>
                  {e.title}
                </Typography>
                {e.status && <StatusChip value={e.status} />}
              </Stack>
              {e.detail && (
                <Typography variant="body2" color="text.secondary" sx={{ mt: 0.5, whiteSpace: 'pre-line' }}>
                  {e.detail}
                </Typography>
              )}
              <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                {formatDateTime(e.occurredAt)} · {humanize(e.category)}
                {e.actorName ? ` · ${e.actorName}` : ''}
              </Typography>
            </Box>
          </Box>
        );
      })}
    </Box>
  );
}

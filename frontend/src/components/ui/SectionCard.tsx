'use client';

import { ReactNode } from 'react';
import { Box, Card, Stack, SxProps, Theme, Typography } from '@mui/material';

export interface SectionCardProps {
  title?: ReactNode;
  subtitle?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  /** Remove inner padding (e.g. for full-bleed tables). */
  flush?: boolean;
  sx?: SxProps<Theme>;
  id?: string;
}

/** Standard content card with an optional header row. */
export function SectionCard({ title, subtitle, action, children, flush, sx, id }: SectionCardProps) {
  return (
    <Card id={id} sx={{ overflow: 'hidden', ...sx }}>
      {(title || action) && (
        <Stack
          direction="row"
          alignItems="flex-start"
          justifyContent="space-between"
          spacing={2}
          sx={{ px: 5, pt: 4, pb: flush ? 3 : 1 }}
        >
          <Box sx={{ minWidth: 0 }}>
            {title && (
              <Typography variant="h4" component="h2">
                {title}
              </Typography>
            )}
            {subtitle && (
              <Typography variant="body2" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                {subtitle}
              </Typography>
            )}
          </Box>
          {action && <Box sx={{ flexShrink: 0 }}>{action}</Box>}
        </Stack>
      )}
      <Box sx={flush ? undefined : { px: 5, pb: 5, pt: title || action ? 3 : 5 }}>{children}</Box>
    </Card>
  );
}

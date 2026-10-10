'use client';

import { ReactNode } from 'react';
import Link from 'next/link';
import { Box, Breadcrumbs, Stack, Typography } from '@mui/material';
import { ChevronRight } from 'lucide-react';
import { colors } from '@/theme/tokens';

export interface Crumb {
  label: string;
  href?: string;
}

export interface PageHeaderProps {
  title: ReactNode;
  subtitle?: ReactNode;
  breadcrumbs?: Crumb[];
  actions?: ReactNode;
  /** Extra content under the title row (e.g. tabs, filters). */
  children?: ReactNode;
}

/** Page title block with breadcrumbs and primary actions. Every page starts with one. */
export function PageHeader({ title, subtitle, breadcrumbs, actions, children }: PageHeaderProps) {
  return (
    <Box component="header" sx={{ mb: 5 }}>
      {breadcrumbs && breadcrumbs.length > 0 && (
        <Breadcrumbs
          separator={<ChevronRight size={14} color={colors.inkSubtle} />}
          aria-label="breadcrumb"
          sx={{ mb: 2, '& .MuiBreadcrumbs-li': { fontSize: '0.8125rem' } }}
        >
          {breadcrumbs.map((c, i) =>
            c.href && i < breadcrumbs.length - 1 ? (
              <Typography
                key={c.label + i}
                component={Link}
                href={c.href}
                variant="body2"
                sx={{ color: 'text.secondary', textDecoration: 'none', '&:hover': { color: 'primary.main' } }}
              >
                {c.label}
              </Typography>
            ) : (
              <Typography key={c.label + i} variant="body2" color="text.primary" fontWeight={500}>
                {c.label}
              </Typography>
            ),
          )}
        </Breadcrumbs>
      )}
      <Stack
        direction={{ xs: 'column', sm: 'row' }}
        spacing={3}
        alignItems={{ xs: 'flex-start', sm: 'center' }}
        justifyContent="space-between"
      >
        <Box sx={{ minWidth: 0 }}>
          <Typography variant="h2" component="h1" sx={{ wordBreak: 'break-word' }}>
            {title}
          </Typography>
          {subtitle && (
            <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }} component="div">
              {subtitle}
            </Typography>
          )}
        </Box>
        {actions && (
          <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap sx={{ flexShrink: 0 }}>
            {actions}
          </Stack>
        )}
      </Stack>
      {children && <Box sx={{ mt: 4 }}>{children}</Box>}
    </Box>
  );
}

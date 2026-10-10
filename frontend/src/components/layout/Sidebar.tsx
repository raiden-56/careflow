'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Box, Divider, List, ListItemButton, ListItemIcon, ListItemText, Stack, Tooltip, Typography } from '@mui/material';
import { HeartPulse } from 'lucide-react';
import { NAV, ROLE_HOME, ROLE_LABEL } from '@/lib/nav';
import { useAuth } from '@/lib/auth/AuthProvider';
import { colors, layout } from '@/theme/tokens';

export function SidebarContent({ collapsed, onNavigate }: { collapsed: boolean; onNavigate?: () => void }) {
  const pathname = usePathname() ?? '';
  const { me, hasPermission } = useAuth();
  if (!me) return null;
  const sections = NAV[me.role];
  const home = ROLE_HOME[me.role];
  const isActive = (href: string) => (href === home ? pathname === href : pathname === href || pathname.startsWith(href + '/'));

  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
      <Stack
        direction="row"
        alignItems="center"
        spacing={2.5}
        component={Link}
        href={home}
        onClick={onNavigate}
        sx={{ height: layout.topBarHeight, px: collapsed ? 0 : 5, justifyContent: collapsed ? 'center' : 'flex-start', textDecoration: 'none', color: 'inherit', flexShrink: 0 }}
        aria-label="CareFlow home"
      >
        <Box sx={{ width: 34, height: 34, borderRadius: 2.5, bgcolor: colors.primary, color: '#fff', display: 'grid', placeItems: 'center', flexShrink: 0 }}>
          <HeartPulse size={19} />
        </Box>
        {!collapsed && (
          <Box sx={{ minWidth: 0 }}>
            <Typography sx={{ fontWeight: 700, fontSize: '1.0625rem', lineHeight: 1.1, color: colors.ink }}>CareFlow</Typography>
            <Typography variant="caption" color="text.secondary" noWrap>
              {ROLE_LABEL[me.role]}
            </Typography>
          </Box>
        )}
      </Stack>
      <Divider />
      <Box component="nav" aria-label="Main navigation" sx={{ flex: 1, overflowY: 'auto', py: 3, px: collapsed ? 2 : 3 }}>
        {sections.map((section, si) => {
          const items = section.items.filter((i) => !i.permission || hasPermission(i.permission));
          if (items.length === 0) return null;
          return (
            <Box key={si} sx={{ mb: 3 }}>
              {section.title && !collapsed && (
                <Typography variant="overline" color="text.secondary" sx={{ px: 3, display: 'block', mb: 1 }}>
                  {section.title}
                </Typography>
              )}
              {section.title && collapsed && <Divider sx={{ my: 2 }} />}
              <List disablePadding dense>
                {items.map((item) => {
                  const active = isActive(item.href);
                  const Icon = item.icon;
                  const button = (
                    <ListItemButton
                      key={item.href}
                      component={Link}
                      href={item.href}
                      onClick={onNavigate}
                      selected={active}
                      aria-current={active ? 'page' : undefined}
                      sx={{
                        mb: 0.5,
                        minHeight: 40,
                        px: collapsed ? 0 : 3,
                        justifyContent: collapsed ? 'center' : 'flex-start',
                        color: active ? colors.primaryDark : colors.inkMuted,
                        '&.Mui-selected': { bgcolor: colors.primarySoft, '&:hover': { bgcolor: colors.primarySoft } },
                      }}
                    >
                      <ListItemIcon sx={{ minWidth: collapsed ? 0 : 36, color: 'inherit', justifyContent: 'center' }}>
                        <Icon size={18} aria-hidden />
                      </ListItemIcon>
                      {!collapsed && (
                        <ListItemText
                          primary={item.label}
                          slotProps={{ primary: { fontSize: '0.875rem', fontWeight: active ? 600 : 500, noWrap: true } }}
                        />
                      )}
                    </ListItemButton>
                  );
                  return collapsed ? (
                    <Tooltip key={item.href} title={item.label} placement="right">
                      {button}
                    </Tooltip>
                  ) : (
                    button
                  );
                })}
              </List>
            </Box>
          );
        })}
      </Box>
      {!collapsed && (
        <Box sx={{ px: 5, py: 3, borderTop: 1, borderColor: 'divider' }}>
          <Typography variant="caption" color="text.secondary" component="div" noWrap>
            {me.organization.name}
          </Typography>
        </Box>
      )}
    </Box>
  );
}

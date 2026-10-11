'use client';

import { ReactNode, useEffect, useState } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import {
  Alert,
  AppBar,
  Avatar,
  Box,
  Divider,
  Drawer,
  IconButton,
  ListItemIcon,
  Menu,
  MenuItem,
  Stack,
  Toolbar,
  Tooltip,
  Typography,
  useMediaQuery,
  useTheme,
} from '@mui/material';
import { LogOut, Menu as MenuIcon, PanelLeftClose, PanelLeftOpen, UserRound, Bell, Download, WifiOff } from 'lucide-react';
import { useAuth } from '@/lib/auth/AuthProvider';
import { ROLE_HOME, ROLE_LABEL, ROLE_PREFIXES, SHARED_ROUTES } from '@/lib/nav';
import { initials } from '@/lib/format';
import { useNotificationStream } from '@/lib/realtime';
import { usePwa } from '@/lib/pwa';
import { colors, layout } from '@/theme/tokens';
import { PageSkeleton } from '@/components/ui/States';
import { SidebarContent } from './Sidebar';
import { NotificationBell } from './NotificationBell';
import { GlobalSearch } from './GlobalSearch';

const COLLAPSE_KEY = 'careflow.sidebar.collapsed';

export function AppShell({ children }: { children: ReactNode }) {
  const theme = useTheme();
  const isDesktop = useMediaQuery(theme.breakpoints.up('lg'));
  const { me, isLoading, error, logout } = useAuth();
  const pwa = usePwa();
  const router = useRouter();
  const pathname = usePathname() ?? '/';
  const [collapsed, setCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [menuAnchor, setMenuAnchor] = useState<HTMLElement | null>(null);

  useEffect(() => {
    try {
      setCollapsed(localStorage.getItem(COLLAPSE_KEY) === '1');
    } catch {
      // storage unavailable
    }
  }, []);

  useNotificationStream(!!me);

  // Not signed in -> login; wrong area for role -> unauthorized page.
  useEffect(() => {
    if (isLoading) return;
    if (!me) {
      router.replace(`/login?next=${encodeURIComponent(pathname)}`);
      return;
    }
    const allowed = [...ROLE_PREFIXES[me.role], ...SHARED_ROUTES];
    if (pathname === '/') {
      router.replace(ROLE_HOME[me.role]);
    } else if (!allowed.some((p) => pathname === p || pathname.startsWith(p + '/'))) {
      router.replace('/unauthorized');
    }
  }, [isLoading, me, pathname, router, error]);

  if (isLoading || !me) {
    return (
      <Box sx={{ p: 8 }}>
        <PageSkeleton />
      </Box>
    );
  }

  const width = collapsed ? layout.sidebarCollapsedWidth : layout.sidebarWidth;
  const toggleCollapsed = () => {
    setCollapsed((c) => {
      try {
        localStorage.setItem(COLLAPSE_KEY, c ? '0' : '1');
      } catch {
        // ignore
      }
      return !c;
    });
  };

  return (
    <Box sx={{ display: 'flex', minHeight: '100vh' }}>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      {isDesktop ? (
        <Box
          component="aside"
          sx={{
            width,
            flexShrink: 0,
            borderRight: 1,
            borderColor: 'divider',
            bgcolor: 'background.paper',
            position: 'sticky',
            top: 0,
            alignSelf: 'flex-start',
            height: '100vh',
            zIndex: theme.zIndex.appBar + 1,
            transition: 'width 160ms ease',
          }}
        >
          <SidebarContent collapsed={collapsed} />
        </Box>
      ) : (
        <Drawer open={mobileOpen} onClose={() => setMobileOpen(false)} slotProps={{ paper: { sx: { width: layout.sidebarWidth } } }}>
          <SidebarContent collapsed={false} onNavigate={() => setMobileOpen(false)} />
        </Drawer>
      )}
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <AppBar position="sticky" color="inherit" elevation={0} sx={{ borderBottom: 1, borderColor: 'divider', bgcolor: 'background.paper' }}>
          <Toolbar sx={{ minHeight: `${layout.topBarHeight}px !important`, gap: 2, px: { xs: 3, md: 5 } }}>
            {isDesktop ? (
              <Tooltip title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
                <IconButton onClick={toggleCollapsed} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}>
                  {collapsed ? <PanelLeftOpen size={20} /> : <PanelLeftClose size={20} />}
                </IconButton>
              </Tooltip>
            ) : (
              <IconButton onClick={() => setMobileOpen(true)} aria-label="Open navigation">
                <MenuIcon size={20} />
              </IconButton>
            )}
            <Box sx={{ flex: 1, minWidth: 0, display: 'flex' }}>
              <GlobalSearch />
            </Box>
            <NotificationBell />
            <Tooltip title="Account">
              <IconButton onClick={(e) => setMenuAnchor(e.currentTarget)} aria-label="Account menu" sx={{ p: 1 }}>
                <Avatar sx={{ width: 34, height: 34, bgcolor: colors.primary, fontSize: '0.875rem', fontWeight: 700 }}>
                  {initials(me.fullName)}
                </Avatar>
              </IconButton>
            </Tooltip>
            <Menu
              anchorEl={menuAnchor}
              open={!!menuAnchor}
              onClose={() => setMenuAnchor(null)}
              anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
              transformOrigin={{ vertical: 'top', horizontal: 'right' }}
              slotProps={{ paper: { sx: { minWidth: 240, mt: 1 } } }}
            >
              <Box sx={{ px: 4, py: 2 }}>
                <Typography variant="body2" fontWeight={600}>
                  {me.fullName}
                </Typography>
                <Typography variant="caption" color="text.secondary" component="div">
                  {me.email}
                </Typography>
                <Typography variant="caption" color="primary" component="div" sx={{ mt: 0.5, fontWeight: 600 }}>
                  {ROLE_LABEL[me.role]}
                </Typography>
              </Box>
              <Divider />
              <MenuItem component={Link} href="/profile" onClick={() => setMenuAnchor(null)}>
                <ListItemIcon>
                  <UserRound size={16} />
                </ListItemIcon>
                Profile & security
              </MenuItem>
              <MenuItem component={Link} href="/notifications" onClick={() => setMenuAnchor(null)}>
                <ListItemIcon>
                  <Bell size={16} />
                </ListItemIcon>
                Notifications
              </MenuItem>
              {pwa.canInstall && (
                <MenuItem
                  onClick={() => {
                    setMenuAnchor(null);
                    pwa.install();
                  }}
                >
                  <ListItemIcon>
                    <Download size={16} />
                  </ListItemIcon>
                  Install CareFlow app
                </MenuItem>
              )}
              <Divider />
              <MenuItem
                onClick={() => {
                  setMenuAnchor(null);
                  logout();
                }}
              >
                <ListItemIcon>
                  <LogOut size={16} />
                </ListItemIcon>
                Sign out
              </MenuItem>
            </Menu>
          </Toolbar>
        </AppBar>
        {pwa.offline && (
          <Alert
            severity="warning"
            icon={<WifiOff size={18} />}
            role="status"
            sx={{ borderRadius: 0, borderBottom: 1, borderColor: 'divider', position: 'sticky', top: layout.topBarHeight + 1, zIndex: theme.zIndex.appBar - 1 }}
          >
            You&apos;re offline. Records shown may be out of date and changes can&apos;t be saved until the connection returns.
          </Alert>
        )}
        <Box component="main" id="main" tabIndex={-1} sx={{ flex: 1, px: { xs: 4, md: 8 }, py: { xs: 5, md: 7 }, width: '100%', maxWidth: layout.contentMaxWidth, mx: 'auto', outline: 'none' }}>
          {children}
        </Box>
        <Stack component="footer" direction="row" justifyContent="center" sx={{ py: 4, color: 'text.secondary' }}>
          <Typography variant="caption">CareFlow HMS · {me.organization.name}</Typography>
        </Stack>
      </Box>
    </Box>
  );
}

'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge, Box, Button, Divider, IconButton, List, ListItemButton, Popover, Stack, Tooltip, Typography } from '@mui/material';
import { Bell, CheckCheck } from 'lucide-react';
import { notificationApi, qk } from '@/lib/api/core';
import type { NotificationView } from '@/lib/api/types';
import { fromNow } from '@/lib/format';
import { StatusChip } from '@/components/ui/StatusChip';
import { EmptyState } from '@/components/ui/States';
import { colors } from '@/theme/tokens';

export function NotificationBell() {
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const router = useRouter();
  const queryClient = useQueryClient();
  const count = useQuery({ queryKey: qk.unreadCount, queryFn: notificationApi.unreadCount, refetchInterval: 30_000 });
  const list = useQuery({
    queryKey: qk.notifications({ size: 8 }),
    queryFn: () => notificationApi.list({ size: 8 }),
    enabled: !!anchor,
  });
  const markRead = useMutation({
    mutationFn: notificationApi.markRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const markAll = useMutation({
    mutationFn: notificationApi.markAllRead,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  });
  const unread = count.data?.count ?? 0;

  const open = (n: NotificationView) => {
    if (!n.readAt) markRead.mutate(n.id);
    setAnchor(null);
    if (n.link) router.push(n.link);
  };

  return (
    <>
      <Tooltip title="Notifications">
        <IconButton onClick={(e) => setAnchor(e.currentTarget)} aria-label={`Notifications, ${unread} unread`}>
          <Badge badgeContent={unread} color="error" max={99}>
            <Bell size={20} />
          </Badge>
        </IconButton>
      </Tooltip>
      <Popover
        open={!!anchor}
        anchorEl={anchor}
        onClose={() => setAnchor(null)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{ paper: { sx: { width: { xs: 'calc(100vw - 32px)', sm: 380 }, maxHeight: 520, mt: 1 } } }}
      >
        <Stack direction="row" alignItems="center" justifyContent="space-between" sx={{ px: 4, py: 3 }}>
          <Typography variant="h5">Notifications</Typography>
          <Button size="small" startIcon={<CheckCheck size={14} />} disabled={unread === 0} onClick={() => markAll.mutate()}>
            Mark all read
          </Button>
        </Stack>
        <Divider />
        {list.data && list.data.content.length === 0 && <EmptyState title="You're all caught up" compact />}
        <List disablePadding>
          {list.data?.content.map((n) => (
            <ListItemButton
              key={n.id}
              onClick={() => open(n)}
              sx={{ alignItems: 'flex-start', py: 3, px: 4, borderRadius: 0, bgcolor: n.readAt ? undefined : colors.primarySoft + '80', borderBottom: 1, borderColor: 'divider' }}
            >
              <Box sx={{ minWidth: 0 }}>
                <Stack direction="row" spacing={1} alignItems="center">
                  {(n.priority === 'HIGH' || n.priority === 'CRITICAL') && <StatusChip value={n.priority} />}
                  <Typography variant="body2" fontWeight={n.readAt ? 500 : 700} sx={{ wordBreak: 'break-word' }}>
                    {n.title}
                  </Typography>
                </Stack>
                <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 0.5 }}>
                  {n.message}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {fromNow(n.createdAt)}
                </Typography>
              </Box>
            </ListItemButton>
          ))}
        </List>
        <Box sx={{ p: 2, textAlign: 'center' }}>
          <Button
            size="small"
            onClick={() => {
              setAnchor(null);
              router.push('/notifications');
            }}
          >
            View all notifications
          </Button>
        </Box>
      </Popover>
    </>
  );
}

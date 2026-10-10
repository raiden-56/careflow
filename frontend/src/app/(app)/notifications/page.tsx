'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Card, Stack, Tab, Tabs, Typography } from '@mui/material';
import { CheckCheck } from 'lucide-react';
import { notificationApi, qk } from '@/lib/api/core';
import type { NotificationView } from '@/lib/api/types';
import { formatDateTime, fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { useToast } from '@/lib/hooks';

export default function NotificationsPage() {
  const router = useRouter();
  const toast = useToast();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<'all' | 'unread'>('unread');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const params = { unreadOnly: tab === 'unread', page, size };
  const q = useQuery({ queryKey: qk.notifications(params), queryFn: () => notificationApi.list(params) });
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['notifications'] });
  const markRead = useMutation({ mutationFn: notificationApi.markRead, onSuccess: invalidate });
  const ack = useMutation({
    mutationFn: notificationApi.acknowledge,
    onSuccess: () => {
      invalidate();
      toast.success('Acknowledged');
    },
    onError: toast.error,
  });
  const markAll = useMutation({
    mutationFn: notificationApi.markAllRead,
    onSuccess: (r) => {
      invalidate();
      toast.success(`${r.updated} marked as read`);
    },
  });

  const open = (n: NotificationView) => {
    if (!n.readAt) markRead.mutate(n.id);
    if (n.link) router.push(n.link);
  };

  return (
    <>
      <PageHeader
        title="Notifications"
        subtitle="Assignments, alerts, results, billing and discharge updates relevant to you."
        breadcrumbs={[{ label: 'Home', href: '/' }, { label: 'Notifications' }]}
        actions={
          <Button startIcon={<CheckCheck size={16} />} variant="outlined" onClick={() => markAll.mutate()}>
            Mark all as read
          </Button>
        }
      />
      <Card>
        <Tabs value={tab} onChange={(_, v) => { setTab(v); setPage(0); }} sx={{ px: 4, borderBottom: 1, borderColor: 'divider' }}>
          <Tab value="unread" label="Unread" />
          <Tab value="all" label="All" />
        </Tabs>
        <DataTable<NotificationView>
          rows={q.data?.content}
          loading={q.isLoading}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(n) => n.id}
          onRowClick={open}
          page={page}
          pageSize={size}
          total={q.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={setSize}
          emptyTitle={tab === 'unread' ? "You're all caught up" : 'No notifications yet'}
          rowSx={(n) => (n.readAt ? undefined : { bgcolor: 'rgba(15,118,110,0.04)' })}
          columns={[
            {
              key: 'title',
              header: 'Notification',
              primary: true,
              render: (n) => (
                <Box>
                  <Typography variant="body2" fontWeight={n.readAt ? 500 : 700}>
                    {n.title}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {n.message}
                  </Typography>
                </Box>
              ),
            },
            { key: 'category', header: 'Type', render: (n) => humanize(n.category) },
            { key: 'priority', header: 'Priority', render: (n) => <StatusChip value={n.priority} /> },
            {
              key: 'when',
              header: 'Received',
              render: (n) => (
                <span title={formatDateTime(n.createdAt)}>{fromNow(n.createdAt)}</span>
              ),
            },
            {
              key: 'ack',
              header: '',
              align: 'right',
              render: (n) =>
                n.requiresAck && !n.acknowledgedAt ? (
                  <Stack direction="row" justifyContent="flex-end">
                    <Button
                      size="small"
                      variant="contained"
                      onClick={(e) => {
                        e.stopPropagation();
                        ack.mutate(n.id);
                      }}
                    >
                      Acknowledge
                    </Button>
                  </Stack>
                ) : n.acknowledgedAt ? (
                  <StatusChip value="ACKNOWLEDGED" />
                ) : null,
            },
          ]}
        />
      </Card>
    </>
  );
}

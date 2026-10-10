'use client';

import { useEffect } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useSnackbar } from 'notistack';
import type { NotificationView } from './api/types';

/**
 * Subscribes to server-sent notification events. Best effort: the unread counter also polls, and
 * critical alerts are persisted server-side, so nothing depends on this stream being connected.
 */
export function useNotificationStream(enabled: boolean) {
  const queryClient = useQueryClient();
  const { enqueueSnackbar } = useSnackbar();
  useEffect(() => {
    if (!enabled || typeof window === 'undefined' || !('EventSource' in window)) return;
    let es: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | null = null;
    let closed = false;
    const connect = () => {
      es = new EventSource('/api/notifications/stream');
      es.addEventListener('notification', (ev) => {
        try {
          const n = JSON.parse((ev as MessageEvent).data) as NotificationView;
          queryClient.invalidateQueries({ queryKey: ['notifications'] });
          if (n.priority === 'HIGH' || n.priority === 'CRITICAL') {
            enqueueSnackbar(n.title, { variant: n.priority === 'CRITICAL' ? 'error' : 'warning' });
          }
          // Let screens refresh data related to the event.
          window.dispatchEvent(new CustomEvent('careflow:notification', { detail: n }));
        } catch {
          // ignore malformed events
        }
      });
      es.onerror = () => {
        es?.close();
        if (!closed) retry = setTimeout(connect, 15_000);
      };
    };
    connect();
    return () => {
      closed = true;
      es?.close();
      if (retry) clearTimeout(retry);
    };
  }, [enabled, queryClient, enqueueSnackbar]);
}

'use client';

import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Box, IconButton, ListItemText, Menu, MenuItem } from '@mui/material';
import { MoreVertical } from 'lucide-react';
import { BatchView, pharmacyApi } from '@/lib/api/pharmacy';
import { errorMessage } from '@/lib/api/client';
import { useToast } from '@/lib/hooks';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { AdjustStockDialog } from './StockDialogs';

type Action = 'quarantine' | 'release' | 'writeOff';

const COPY: Record<Action, { title: string; description: (b: BatchView) => string; confirm: string; tone: 'primary' | 'error' | 'warning'; done: string }> = {
  quarantine: {
    title: 'Quarantine batch?',
    description: (b) => `${b.quantityOnHand} units of ${b.medicationName} (batch ${b.batchNumber}) will be held and cannot be dispensed until released.`,
    confirm: 'Quarantine',
    tone: 'warning',
    done: 'quarantined',
  },
  release: {
    title: 'Release from quarantine?',
    description: (b) => `Batch ${b.batchNumber} returns to usable stock and can be dispensed again.`,
    confirm: 'Release',
    tone: 'primary',
    done: 'released',
  },
  writeOff: {
    title: 'Write off expired stock?',
    description: (b) => `All ${b.quantityOnHand} remaining units of batch ${b.batchNumber} (expired ${b.expiryDate}) are removed from stock with an expiry write-off entry. This cannot be undone.`,
    confirm: 'Write off',
    tone: 'error',
    done: 'written off',
  },
};

/** Which state changes are valid for a batch (mirrors the server rules; the server remains authoritative). */
export function batchActions(b: BatchView) {
  return {
    quarantine: b.status === 'ACTIVE' && !b.expired && b.quantityOnHand > 0,
    release: b.status === 'QUARANTINED' && !b.expired,
    writeOff: b.expired && b.quantityOnHand > 0,
    adjust: b.status !== 'EXPIRED',
  };
}

/** Row menu with quarantine / release / write-off / adjust / history for one batch. */
export function BatchActionsMenu({ batch, onHistory, canManage }: { batch: BatchView; onHistory: (b: BatchView) => void; canManage: boolean }) {
  const toast = useToast();
  const queryClient = useQueryClient();
  const [anchor, setAnchor] = useState<HTMLElement | null>(null);
  const [action, setAction] = useState<Action | null>(null);
  const [adjust, setAdjust] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const allowed = batchActions(batch);

  const m = useMutation({
    mutationFn: (reason: string) =>
      action === 'quarantine'
        ? pharmacyApi.quarantine(batch.id, reason)
        : action === 'release'
          ? pharmacyApi.release(batch.id, reason)
          : pharmacyApi.writeOff(batch.id, reason),
    onSuccess: (b) => {
      toast.success(`Batch ${b.batchNumber} ${COPY[action!].done}`);
      setAction(null);
      queryClient.invalidateQueries({ queryKey: ['pharmacy'] });
      queryClient.invalidateQueries({ queryKey: ['medications'] });
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const open = (a: Action) => {
    setAnchor(null);
    setError(null);
    setAction(a);
  };

  // Dialogs and menus render in portals, but React events still bubble to the table row: contain them.
  return (
    <Box component="span" onClick={(e) => e.stopPropagation()} onKeyDown={(e) => e.stopPropagation()}>
      <IconButton
        size="small"
        aria-label={`Actions for batch ${batch.batchNumber}`}
        onClick={(e) => {
          e.stopPropagation();
          setAnchor(e.currentTarget);
        }}
      >
        <MoreVertical size={16} />
      </IconButton>
      <Menu anchorEl={anchor} open={!!anchor} onClose={() => setAnchor(null)}>
        <MenuItem
          onClick={() => {
            setAnchor(null);
            onHistory(batch);
          }}
        >
          <ListItemText>Movement history</ListItemText>
        </MenuItem>
        {canManage && allowed.adjust && (
          <MenuItem
            onClick={() => {
              setAnchor(null);
              setAdjust(true);
            }}
          >
            <ListItemText>Adjust quantity</ListItemText>
          </MenuItem>
        )}
        {canManage && allowed.quarantine && (
          <MenuItem onClick={() => open('quarantine')}>
            <ListItemText>Quarantine</ListItemText>
          </MenuItem>
        )}
        {canManage && allowed.release && (
          <MenuItem onClick={() => open('release')}>
            <ListItemText>Release from quarantine</ListItemText>
          </MenuItem>
        )}
        {canManage && allowed.writeOff && (
          <MenuItem onClick={() => open('writeOff')} sx={{ color: 'error.main' }}>
            <ListItemText>Write off (expired)</ListItemText>
          </MenuItem>
        )}
      </Menu>
      {action && (
        <ConfirmDialog
          open
          title={COPY[action].title}
          description={COPY[action].description(batch)}
          confirmLabel={COPY[action].confirm}
          tone={COPY[action].tone}
          requireReason
          loading={m.isPending}
          error={error}
          onConfirm={(r) => m.mutate(r!)}
          onClose={() => setAction(null)}
        />
      )}
      <AdjustStockDialog open={adjust} batch={batch} onClose={() => setAdjust(false)} />
    </Box>
  );
}

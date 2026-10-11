'use client';

import { useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Card, IconButton, InputAdornment, ListItemIcon, Menu, MenuItem, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { KeyRound, Lock, MailPlus, MoreVertical, Pencil, Search, UserCheck, UserPlus, UserX } from 'lucide-react';
import { adminApi, StaffView } from '@/lib/api/admin';
import type { Role } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { useTableState, useToast } from '@/lib/hooks';
import { fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable, sortParam } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { STAFF_ROLES, StaffDialog } from '@/components/admin/StaffDialog';

const ROLE_LABEL: Record<string, string> = Object.fromEntries(STAFF_ROLES.map((r) => [r.value, r.label]));

export default function StaffPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const { me } = useAuth();
  const t = useTableState({ sort: { field: 'name', direction: 'asc' } });
  const [role, setRole] = useState<Role | ''>('');
  const [status, setStatus] = useState('');
  const [dialog, setDialog] = useState<{ open: boolean; staff?: StaffView | null }>({ open: false });
  const [menu, setMenu] = useState<{ anchor: HTMLElement; staff: StaffView } | null>(null);
  const [confirm, setConfirm] = useState<{ staff: StaffView; status: StaffView['status'] } | null>(null);
  const params = { q: t.q, role, status: status || undefined, page: t.page, size: t.size, sort: sortParam(t.sort) };
  const q = useQuery({ queryKey: ['staff', 'list', params], queryFn: () => adminApi.staff(params), placeholderData: keepPreviousData });

  const statusM = useMutation({
    mutationFn: ({ s, next, reason }: { s: StaffView; next: StaffView['status']; reason?: string }) => adminApi.staffStatus(s.id, next, reason),
    onSuccess: (s) => {
      toast.success(`${s.fullName} is now ${s.status.toLowerCase()}`);
      setConfirm(null);
      qc.invalidateQueries({ queryKey: ['staff'] });
    },
    onError: toast.error,
  });
  const inviteM = useMutation({
    mutationFn: (s: StaffView) => adminApi.resendInvite(s.id),
    onSuccess: () => toast.success('Invitation link sent'),
    onError: toast.error,
  });

  return (
    <>
      <PageHeader
        title="Staff"
        subtitle="Accounts, professional profiles and ward assignments. Disabling an account signs the person out immediately."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Staff' }]}
        actions={
          <Button variant="contained" startIcon={<UserPlus size={16} />} onClick={() => setDialog({ open: true, staff: null })}>
            Add staff
          </Button>
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', md: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField
            placeholder="Search name or email"
            value={t.search}
            onChange={(e) => t.setSearch(e.target.value)}
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { md: 320 } }}
            aria-label="Search staff"
          />
          <TextField select label="Role" value={role} onChange={(e) => { setRole(e.target.value as Role); t.setPage(0); }} sx={{ minWidth: 200 }}>
            <MenuItem value="">All roles</MenuItem>
            {STAFF_ROLES.map((r) => (
              <MenuItem key={r.value} value={r.value}>
                {r.label}
              </MenuItem>
            ))}
          </TextField>
          <TextField select label="Status" value={status} onChange={(e) => { setStatus(e.target.value); t.setPage(0); }} sx={{ minWidth: 160 }}>
            <MenuItem value="">Any status</MenuItem>
            <MenuItem value="ACTIVE">Active</MenuItem>
            <MenuItem value="DISABLED">Disabled</MenuItem>
            <MenuItem value="LOCKED">Locked</MenuItem>
          </TextField>
        </Stack>
        <DataTable<StaffView>
          ariaLabel="Staff"
          rows={q.data?.content}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(s) => s.id}
          page={t.page}
          pageSize={t.size}
          total={q.data?.totalElements}
          onPageChange={t.setPage}
          onPageSizeChange={t.setSize}
          sort={t.sort}
          onSortChange={t.setSort}
          emptyTitle={t.q ? 'No staff match your search' : 'No staff accounts yet'}
          columns={[
            {
              key: 'name',
              header: 'Name',
              sortKey: 'name',
              primary: true,
              render: (s) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {s.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-all' }}>
                    {s.email}
                  </Typography>
                </Box>
              ),
            },
            { key: 'role', header: 'Role', sortKey: 'role', render: (s) => ROLE_LABEL[s.role] ?? s.role },
            {
              key: 'dept',
              header: 'Department / designation',
              render: (s) => (
                <Box>
                  <Typography variant="body2">{s.departmentName ?? '—'}</Typography>
                  <Typography variant="caption" color="text.secondary">
                    {[s.designation, s.specialty].filter(Boolean).join(' · ') || ' '}
                  </Typography>
                </Box>
              ),
            },
            { key: 'wards', header: 'Wards', hideOnMobile: true, render: (s) => (s.wards.length ? s.wards.join(', ') : '—') },
            {
              key: 'status',
              header: 'Status',
              render: (s) => (
                <Stack direction="row" spacing={1}>
                  <StatusChip value={s.status} />
                  {s.locked && <StatusChip value="LOCKED" label="Temporarily locked" />}
                </Stack>
              ),
            },
            { key: 'login', header: 'Last sign-in', sortKey: 'lastLoginAt', render: (s) => (s.lastLoginAt ? fromNow(s.lastLoginAt) : 'Never') },
            {
              key: 'actions',
              header: '',
              align: 'right',
              render: (s) => (
                <Stack direction="row" spacing={0.5} justifyContent="flex-end">
                  <Tooltip title="Edit">
                    <IconButton size="small" aria-label={`Edit ${s.fullName}`} onClick={() => setDialog({ open: true, staff: s })}>
                      <Pencil size={16} />
                    </IconButton>
                  </Tooltip>
                  <IconButton size="small" aria-label={`More actions for ${s.fullName}`} onClick={(e) => setMenu({ anchor: e.currentTarget, staff: s })}>
                    <MoreVertical size={16} />
                  </IconButton>
                </Stack>
              ),
            },
          ]}
        />
      </Card>

      <Menu anchorEl={menu?.anchor} open={!!menu} onClose={() => setMenu(null)}>
        {menu && menu.staff.status === 'ACTIVE' && (
          <MenuItem onClick={() => { inviteM.mutate(menu.staff); setMenu(null); }}>
            <ListItemIcon><MailPlus size={16} /></ListItemIcon>
            Resend invitation / set-password link
          </MenuItem>
        )}
        {menu && menu.staff.locked && (
          <MenuItem onClick={() => { statusM.mutate({ s: menu.staff, next: 'ACTIVE', reason: 'Unlocked by administrator' }); setMenu(null); }}>
            <ListItemIcon><KeyRound size={16} /></ListItemIcon>
            Unlock account
          </MenuItem>
        )}
        {menu && menu.staff.status !== 'ACTIVE' && (
          <MenuItem onClick={() => { setConfirm({ staff: menu.staff, status: 'ACTIVE' }); setMenu(null); }}>
            <ListItemIcon><UserCheck size={16} /></ListItemIcon>
            Enable account
          </MenuItem>
        )}
        {menu && menu.staff.status === 'ACTIVE' && menu.staff.id !== me?.id && (
          <MenuItem onClick={() => { setConfirm({ staff: menu.staff, status: 'DISABLED' }); setMenu(null); }} sx={{ color: 'error.main' }}>
            <ListItemIcon><UserX size={16} /></ListItemIcon>
            Disable account
          </MenuItem>
        )}
        {menu && menu.staff.status === 'ACTIVE' && menu.staff.id !== me?.id && (
          <MenuItem onClick={() => { setConfirm({ staff: menu.staff, status: 'LOCKED' }); setMenu(null); }}>
            <ListItemIcon><Lock size={16} /></ListItemIcon>
            Lock account
          </MenuItem>
        )}
      </Menu>

      <StaffDialog
        open={dialog.open}
        staff={dialog.staff}
        onClose={() => setDialog({ open: false })}
        onSaved={(s, created) => {
          toast.success(created ? `${s.fullName} added — invitation sent` : 'Staff details saved');
          setDialog({ open: false });
          qc.invalidateQueries({ queryKey: ['staff'] });
        }}
      />
      <ConfirmDialog
        open={!!confirm}
        title={confirm?.status === 'ACTIVE' ? `Enable ${confirm?.staff.fullName}?` : `${confirm?.status === 'LOCKED' ? 'Lock' : 'Disable'} ${confirm?.staff.fullName}?`}
        description={
          confirm?.status === 'ACTIVE'
            ? 'The person will be able to sign in again with their existing password.'
            : 'All of their active sessions end immediately and they cannot sign in until re-enabled. Their records and audit history are kept.'
        }
        tone={confirm?.status === 'ACTIVE' ? 'primary' : 'error'}
        requireReason={confirm?.status !== 'ACTIVE'}
        confirmLabel={confirm?.status === 'ACTIVE' ? 'Enable' : confirm?.status === 'LOCKED' ? 'Lock account' : 'Disable account'}
        loading={statusM.isPending}
        onConfirm={(reason) => confirm && statusM.mutate({ s: confirm.staff, next: confirm.status, reason })}
        onClose={() => setConfirm(null)}
      />
    </>
  );
}

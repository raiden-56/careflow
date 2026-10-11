'use client';

import { Suspense, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Card, IconButton, InputAdornment, ListItemIcon, Menu, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { KeyRound, MailPlus, MoreVertical, Search, UserCheck, UserPlus, UserX } from 'lucide-react';
import { AdminAccount, platformApi } from '@/lib/api/platform';
import { useDebouncedValue, useToast } from '@/lib/hooks';
import { fromNow } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ProvisionAdminDialog } from '@/components/platform/OrganizationDialogs';

export default function PlatformUsersPage() {
  return (
    <Suspense>
      <Content />
    </Suspense>
  );
}

function Content() {
  const toast = useToast();
  const qc = useQueryClient();
  const params0 = useSearchParams();
  const [org, setOrg] = useState(params0.get('org') ?? '');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [provision, setProvision] = useState(false);
  const [menu, setMenu] = useState<{ anchor: HTMLElement; a: AdminAccount } | null>(null);
  const [confirm, setConfirm] = useState<{ a: AdminAccount; status: AdminAccount['status'] } | null>(null);
  const q = useDebouncedValue(search, 300);
  const orgs = useQuery({ queryKey: ['platform', 'orgs', { size: 100 }], queryFn: () => platformApi.organizations({ size: 100 }) });
  const params = { organizationId: org || undefined, q: q || undefined, page, size };
  const list = useQuery({ queryKey: ['platform', 'admins', params], queryFn: () => platformApi.admins(params), placeholderData: keepPreviousData });
  const refresh = () => qc.invalidateQueries({ queryKey: ['platform'] });
  const statusM = useMutation({
    mutationFn: ({ a, status, reason }: { a: AdminAccount; status: AdminAccount['status']; reason?: string }) => platformApi.adminStatus(a.id, status, reason),
    onSuccess: (a) => {
      toast.success(`${a.fullName} is now ${a.status.toLowerCase()}`);
      setConfirm(null);
      refresh();
    },
    onError: toast.error,
  });
  const inviteM = useMutation({
    mutationFn: (a: AdminAccount) => platformApi.resendInvite(a.id),
    onSuccess: () => toast.success('Invitation sent'),
    onError: toast.error,
  });
  return (
    <>
      <PageHeader
        title="User provisioning"
        subtitle="Hospital administrator accounts. Hospital administrators manage their own staff; the platform team only provisions and secures administrator access."
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'User provisioning' }]}
        actions={
          <Button variant="contained" startIcon={<UserPlus size={16} />} onClick={() => setProvision(true)}>
            Provision administrator
          </Button>
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField
            placeholder="Search name or email"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
            aria-label="Search administrators"
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { sm: 320 } }}
          />
          <TextField select label="Hospital" value={org} onChange={(e) => { setOrg(e.target.value); setPage(0); }} sx={{ minWidth: 260 }}>
            <MenuItem value="">All hospitals</MenuItem>
            {(orgs.data?.content ?? []).map((o) => (
              <MenuItem key={o.id} value={o.id}>
                {o.name}
              </MenuItem>
            ))}
          </TextField>
        </Stack>
        <DataTable<AdminAccount>
          ariaLabel="Hospital administrators"
          rows={list.data?.content}
          loading={list.isFetching}
          error={list.error}
          onRetry={() => list.refetch()}
          rowKey={(a) => a.id}
          page={page}
          pageSize={size}
          total={list.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={setSize}
          emptyTitle="No administrator accounts match"
          columns={[
            {
              key: 'name',
              header: 'Administrator',
              primary: true,
              render: (a) => (
                <Box sx={{ minWidth: 0 }}>
                  <Typography variant="body2" fontWeight={600}>
                    {a.fullName}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-all' }}>
                    {a.email}
                  </Typography>
                </Box>
              ),
            },
            { key: 'org', header: 'Hospital', render: (a) => `${a.organizationName} (${a.organizationCode})` },
            { key: 'login', header: 'Last sign-in', render: (a) => (a.invitationPending ? 'Invitation pending' : a.lastLoginAt ? fromNow(a.lastLoginAt) : 'Never') },
            { key: 'status', header: 'Status', render: (a) => <Stack direction="row" spacing={1}><StatusChip value={a.status} />{a.locked && <StatusChip value="LOCKED" label="Temporarily locked" />}</Stack> },
            {
              key: 'act',
              header: '',
              align: 'right',
              render: (a) => (
                <IconButton size="small" aria-label={`Actions for ${a.fullName}`} onClick={(e) => setMenu({ anchor: e.currentTarget, a })}>
                  <MoreVertical size={16} />
                </IconButton>
              ),
            },
          ]}
        />
      </Card>
      <Menu anchorEl={menu?.anchor} open={!!menu} onClose={() => setMenu(null)}>
        {menu?.a.status === 'ACTIVE' && (
          <MenuItem onClick={() => { inviteM.mutate(menu.a); setMenu(null); }}>
            <ListItemIcon><MailPlus size={16} /></ListItemIcon>
            Send set-password link
          </MenuItem>
        )}
        {menu?.a.locked && (
          <MenuItem onClick={() => { statusM.mutate({ a: menu.a, status: 'ACTIVE', reason: 'Unlocked by platform team' }); setMenu(null); }}>
            <ListItemIcon><KeyRound size={16} /></ListItemIcon>
            Unlock
          </MenuItem>
        )}
        {menu && menu.a.status !== 'ACTIVE' && (
          <MenuItem onClick={() => { setConfirm({ a: menu.a, status: 'ACTIVE' }); setMenu(null); }}>
            <ListItemIcon><UserCheck size={16} /></ListItemIcon>
            Enable
          </MenuItem>
        )}
        {menu?.a.status === 'ACTIVE' && (
          <MenuItem onClick={() => { setConfirm({ a: menu.a, status: 'DISABLED' }); setMenu(null); }} sx={{ color: 'error.main' }}>
            <ListItemIcon><UserX size={16} /></ListItemIcon>
            Disable
          </MenuItem>
        )}
      </Menu>
      <ProvisionAdminDialog
        open={provision}
        organizations={(orgs.data?.content ?? []).map((o) => ({ id: o.id, name: o.name, status: o.status }))}
        organizationId={org || undefined}
        onClose={() => setProvision(false)}
        onSaved={(a) => {
          toast.success(`${a.fullName} provisioned for ${a.organizationName} — invitation sent`);
          setProvision(false);
          refresh();
        }}
      />
      <ConfirmDialog
        open={!!confirm}
        title={confirm?.status === 'ACTIVE' ? `Enable ${confirm?.a.fullName}?` : `Disable ${confirm?.a.fullName}?`}
        description={confirm?.status === 'ACTIVE' ? 'The administrator can sign in again.' : 'Their sessions end immediately. Make sure the hospital keeps at least one active administrator.'}
        tone={confirm?.status === 'ACTIVE' ? 'primary' : 'error'}
        requireReason={confirm?.status !== 'ACTIVE'}
        confirmLabel={confirm?.status === 'ACTIVE' ? 'Enable' : 'Disable'}
        loading={statusM.isPending}
        onConfirm={(reason) => confirm && statusM.mutate({ a: confirm.a, status: confirm.status, reason })}
        onClose={() => setConfirm(null)}
      />
    </>
  );
}

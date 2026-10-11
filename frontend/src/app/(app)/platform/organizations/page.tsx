'use client';

import { useState } from 'react';
import Link from 'next/link';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Box, Button, Card, Divider, Drawer, IconButton, InputAdornment, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { Building2, Pause, Pencil, Play, Plus, Search, UserPlus, X } from 'lucide-react';
import { OrganizationRow, platformApi } from '@/lib/api/platform';
import { useDebouncedValue, useToast } from '@/lib/hooks';
import { formatDate, fromNow, humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { DefinitionList } from '@/components/ui/Display';
import { ErrorState, TableSkeleton } from '@/components/ui/States';
import { OrganizationDialog, ProvisionAdminDialog } from '@/components/platform/OrganizationDialogs';

export default function OrganizationsPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<string | null>(null);
  const [create, setCreate] = useState(false);
  const q = useDebouncedValue(search, 300);
  const params = { q: q || undefined, status: status || undefined, page, size };
  const list = useQuery({ queryKey: ['platform', 'orgs', params], queryFn: () => platformApi.organizations(params), placeholderData: keepPreviousData });

  return (
    <>
      <PageHeader
        title="Organizations"
        subtitle="Hospitals using CareFlow. Each hospital's data is isolated; suspending a hospital signs out all of its users."
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'Organizations' }]}
        actions={
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setCreate(true)}>
            Register hospital
          </Button>
        }
      />
      <Card>
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3} sx={{ p: 4 }}>
          <TextField
            placeholder="Search name, code or city"
            value={search}
            onChange={(e) => { setSearch(e.target.value); setPage(0); }}
            aria-label="Search organizations"
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { sm: 340 } }}
          />
          <TextField select label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }} sx={{ minWidth: 160 }}>
            <MenuItem value="">Any status</MenuItem>
            <MenuItem value="ACTIVE">Active</MenuItem>
            <MenuItem value="SUSPENDED">Suspended</MenuItem>
          </TextField>
        </Stack>
        <DataTable<OrganizationRow>
          ariaLabel="Organizations"
          rows={list.data?.content}
          loading={list.isFetching}
          error={list.error}
          onRetry={() => list.refetch()}
          rowKey={(o) => o.id}
          onRowClick={(o) => setSelected(o.id)}
          page={page}
          pageSize={size}
          total={list.data?.totalElements}
          onPageChange={setPage}
          onPageSizeChange={setSize}
          emptyTitle="No hospitals match"
          columns={[
            {
              key: 'name',
              header: 'Hospital',
              primary: true,
              render: (o) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {o.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary">
                    {o.code} · {[o.city, o.state].filter(Boolean).join(', ') || '—'}
                  </Typography>
                </Box>
              ),
            },
            { key: 'admins', header: 'Admins', align: 'right', render: (o) => o.hospitalAdmins },
            { key: 'staff', header: 'Staff', align: 'right', render: (o) => o.staffUsers },
            { key: 'beds', header: 'Wards / beds', align: 'right', render: (o) => `${o.wards} / ${o.beds}` },
            { key: 'login', header: 'Last sign-in', render: (o) => (o.lastLoginAt ? fromNow(o.lastLoginAt) : 'Never') },
            { key: 'created', header: 'Registered', hideOnMobile: true, render: (o) => formatDate(o.createdAt) },
            { key: 'status', header: 'Status', render: (o) => <StatusChip value={o.status} /> },
          ]}
        />
      </Card>
      <OrganizationDrawer id={selected} onClose={() => setSelected(null)} />
      <OrganizationDialog
        open={create}
        onClose={() => setCreate(false)}
        onSaved={(o) => {
          toast.success(`${o.name} registered with default templates`);
          setCreate(false);
          qc.invalidateQueries({ queryKey: ['platform'] });
          setSelected(o.id);
        }}
      />
    </>
  );
}

function OrganizationDrawer({ id, onClose }: { id: string | null; onClose: () => void }) {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['platform', 'org', id], queryFn: () => platformApi.organization(id!), enabled: !!id });
  const [edit, setEdit] = useState(false);
  const [suspend, setSuspend] = useState(false);
  const [provision, setProvision] = useState(false);
  const refresh = () => qc.invalidateQueries({ queryKey: ['platform'] });
  const suspendM = useMutation({
    mutationFn: (reason: string) => platformApi.suspend(id!, reason),
    onSuccess: (o) => {
      toast.success(`${o.name} suspended; its users have been signed out`);
      setSuspend(false);
      refresh();
    },
    onError: toast.error,
  });
  const reactivateM = useMutation({
    mutationFn: () => platformApi.reactivate(id!),
    onSuccess: (o) => {
      toast.success(`${o.name} reactivated`);
      refresh();
    },
    onError: toast.error,
  });
  const o = q.data;
  return (
    <Drawer anchor="right" open={!!id} onClose={onClose} slotProps={{ paper: { sx: { width: { xs: '100%', sm: 560 } } } }}>
      <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ px: 5, py: 4 }}>
        <Stack direction="row" spacing={2} alignItems="center" sx={{ minWidth: 0 }}>
          <Building2 size={20} aria-hidden />
          <Typography variant="h4" component="h2" noWrap>
            {o?.name ?? 'Organization'}
          </Typography>
        </Stack>
        <IconButton aria-label="Close" onClick={onClose}>
          <X size={18} />
        </IconButton>
      </Stack>
      <Divider />
      <Box sx={{ p: 5, overflowY: 'auto' }}>
        {q.isLoading ? (
          <TableSkeleton rows={8} columns={2} />
        ) : q.error ? (
          <ErrorState error={q.error} />
        ) : o ? (
          <Stack spacing={5}>
            <Stack direction="row" spacing={2} flexWrap="wrap" useFlexGap>
              <StatusChip value={o.status} size="medium" />
              <Button size="small" startIcon={<Pencil size={14} />} onClick={() => setEdit(true)}>
                Edit
              </Button>
              {o.status === 'ACTIVE' ? (
                <Button size="small" color="error" startIcon={<Pause size={14} />} onClick={() => setSuspend(true)}>
                  Suspend
                </Button>
              ) : (
                <Button size="small" startIcon={<Play size={14} />} onClick={() => reactivateM.mutate()} disabled={reactivateM.isPending}>
                  Reactivate
                </Button>
              )}
              <Button size="small" component={Link} href={`/platform/permissions?org=${o.id}`}>
                Permission templates
              </Button>
            </Stack>
            <DefinitionList
              items={[
                { label: 'Code', value: o.code },
                { label: 'Time zone', value: o.timezone },
                { label: 'Legal name', value: o.legalName },
                { label: 'Registration', value: o.registrationNumber },
                { label: 'Address', value: [o.address, o.city, o.state].filter(Boolean).join(', '), span: 2 },
                { label: 'Phone', value: o.phone },
                { label: 'Email', value: o.email },
                { label: 'Wards / beds', value: `${o.wards} / ${o.beds}` },
                { label: 'Registered', value: formatDate(o.createdAt) },
                { label: 'Accounts by role', value: Object.entries(o.usersByRole).map(([r, n]) => `${humanize(r)}: ${n}`).join(' · ') || '—', span: 2 },
              ]}
            />
            <Box>
              <Stack direction="row" justifyContent="space-between" alignItems="center" sx={{ mb: 2 }}>
                <Typography variant="subtitle2">Hospital administrators</Typography>
                <Button size="small" startIcon={<UserPlus size={14} />} onClick={() => setProvision(true)} disabled={o.status !== 'ACTIVE'}>
                  Provision
                </Button>
              </Stack>
              {o.administrators.length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  No administrator yet. Provision one so the hospital can configure staff and wards.
                </Typography>
              ) : (
                <Stack divider={<Divider flexItem />}>
                  {o.administrators.map((a) => (
                    <Stack key={a.id} direction="row" spacing={2} alignItems="center" sx={{ py: 2 }}>
                      <Box sx={{ flex: 1, minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={500}>
                          {a.fullName}
                        </Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ wordBreak: 'break-all' }}>
                          {a.email} · {a.invitationPending ? 'invitation pending' : a.lastLoginAt ? `last sign-in ${fromNow(a.lastLoginAt)}` : 'never signed in'}
                        </Typography>
                      </Box>
                      <StatusChip value={a.locked ? 'LOCKED' : a.status} />
                    </Stack>
                  ))}
                </Stack>
              )}
              <Button component={Link} href={`/platform/users?org=${o.id}`} size="small" sx={{ mt: 2 }}>
                Manage administrator accounts
              </Button>
            </Box>
          </Stack>
        ) : null}
      </Box>
      <OrganizationDialog
        open={edit}
        org={o}
        onClose={() => setEdit(false)}
        onSaved={() => {
          toast.success('Organization updated');
          setEdit(false);
          refresh();
        }}
      />
      <ProvisionAdminDialog
        open={provision}
        organizations={o ? [{ id: o.id, name: o.name, status: o.status }] : []}
        organizationId={o?.id}
        onClose={() => setProvision(false)}
        onSaved={(a) => {
          toast.success(`${a.fullName} provisioned — invitation sent`);
          setProvision(false);
          refresh();
        }}
      />
      <ConfirmDialog
        open={suspend}
        title={`Suspend ${o?.name}?`}
        description="All users of this hospital are signed out immediately and cannot sign in until it is reactivated. No data is deleted."
        tone="error"
        requireReason
        confirmLabel="Suspend hospital"
        loading={suspendM.isPending}
        onConfirm={(r) => suspendM.mutate(r!)}
        onClose={() => setSuspend(false)}
      />
    </Drawer>
  );
}

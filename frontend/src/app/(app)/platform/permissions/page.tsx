'use client';

import { Fragment, Suspense, useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Chip,
  Grid,
  MenuItem,
  Stack,
  Tab,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tabs,
  TextField,
  Typography,
} from '@mui/material';
import { Check, RotateCcw } from 'lucide-react';
import { OrgRoleMatrix, platformApi } from '@/lib/api/platform';
import type { Role } from '@/lib/api/types';
import { useToast } from '@/lib/hooks';
import { humanize } from '@/lib/format';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';

const ROLE_ORDER: Role[] = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'PHARMACIST', 'BILLING', 'PATIENT'];

export default function PermissionsPage() {
  return (
    <Suspense>
      <Content />
    </Suspense>
  );
}

function Content() {
  const params = useSearchParams();
  const [tab, setTab] = useState<'defaults' | 'orgs'>(params.get('org') ? 'orgs' : 'defaults');
  return (
    <>
      <PageHeader
        title="Permission templates"
        subtitle="Default least-privilege templates applied to every new hospital, and each hospital's current role matrix. Super administrators never hold clinical or financial permissions."
        breadcrumbs={[{ label: 'Platform', href: '/platform' }, { label: 'Permission templates' }]}
      />
      <Card sx={{ mb: 5 }}>
        <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 4 }}>
          <Tab value="defaults" label="Default templates" />
          <Tab value="orgs" label="Hospital role matrices" />
        </Tabs>
      </Card>
      {tab === 'defaults' ? <Defaults /> : <OrgMatrices initialOrg={params.get('org') ?? ''} />}
    </>
  );
}

function Defaults() {
  const q = useQuery({ queryKey: ['platform', 'templates'], queryFn: platformApi.templates });
  const grouped = useMemo(() => {
    const m = new Map<string, Array<{ code: string; description: string }>>();
    (q.data?.permissions ?? []).forEach((p) => {
      if (!m.has(p.category)) m.set(p.category, []);
      m.get(p.category)!.push(p);
    });
    return [...m.entries()];
  }, [q.data]);
  if (q.isLoading) return <CardsSkeleton count={2} />;
  if (q.error) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const roles = ROLE_ORDER.filter((r) => q.data!.defaults[r]);
  return (
    <Card>
      <TableContainer sx={{ overflowX: 'auto', maxHeight: { md: 'calc(100vh - 320px)' } }}>
        <Table size="small" stickyHeader aria-label="Default permission templates">
          <TableHead>
            <TableRow>
              <TableCell sx={{ minWidth: 260, position: 'sticky', left: 0, zIndex: 3, bgcolor: 'background.paper' }}>Permission</TableCell>
              {roles.map((r) => (
                <TableCell key={r} align="center" sx={{ minWidth: 90 }}>
                  {humanize(r)}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {grouped.map(([cat, perms]) => (
              <Fragment key={cat}>
                <TableRow>
                  <TableCell colSpan={roles.length + 1} sx={{ bgcolor: 'action.hover', fontWeight: 600, py: 1.5, position: 'sticky', left: 0 }}>
                    {cat}
                  </TableCell>
                </TableRow>
                {perms.map((p) => (
                  <TableRow key={p.code}>
                    <TableCell sx={{ position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 1 }}>
                      <Typography variant="body2">{p.description}</Typography>
                      <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                        {p.code}
                      </Typography>
                    </TableCell>
                    {roles.map((r) => (
                      <TableCell key={r} align="center">
                        {q.data!.defaults[r].includes(p.code) ? <Check size={16} aria-label={`${r} has ${p.code}`} color="#0F766E" /> : <Box component="span" sx={{ color: 'text.disabled' }} aria-label="not granted">·</Box>}
                      </TableCell>
                    ))}
                  </TableRow>
                ))}
              </Fragment>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Card>
  );
}

function OrgMatrices({ initialOrg }: { initialOrg: string }) {
  const toast = useToast();
  const qc = useQueryClient();
  const orgs = useQuery({ queryKey: ['platform', 'orgs', { size: 100 }], queryFn: () => platformApi.organizations({ size: 100 }) });
  const [org, setOrg] = useState(initialOrg);
  const [reset, setReset] = useState<OrgRoleMatrix['roles'][number] | null>(null);
  useEffect(() => {
    if (!org && orgs.data?.content.length) setOrg(orgs.data.content[0].id);
  }, [orgs.data, org]);
  const matrix = useQuery({ queryKey: ['platform', 'org-roles', org], queryFn: () => platformApi.orgRoles(org), enabled: !!org });
  const resetM = useMutation({
    mutationFn: (role: Role) => platformApi.resetRole(org, role),
    onSuccess: (m) => {
      toast.success('Role reset to the default template');
      setReset(null);
      qc.setQueryData(['platform', 'org-roles', org], m);
    },
    onError: toast.error,
  });
  return (
    <Stack spacing={4}>
      <Card sx={{ p: 4 }}>
        <TextField select label="Hospital" value={org} onChange={(e) => setOrg(e.target.value)} sx={{ minWidth: 300, maxWidth: '100%' }}>
          {(orgs.data?.content ?? []).map((o) => (
            <MenuItem key={o.id} value={o.id}>
              {o.name}
            </MenuItem>
          ))}
        </TextField>
      </Card>
      {!org ? (
        <EmptyState title="No hospitals registered" compact />
      ) : matrix.isLoading ? (
        <CardsSkeleton count={4} />
      ) : matrix.error ? (
        <ErrorState error={matrix.error} onRetry={() => matrix.refetch()} />
      ) : (
        <>
          <Alert severity="info">
            Hospitals tailor their own role templates. Resetting a role restores the platform default for that hospital, takes effect on the next request of
            every user with the role, and is recorded in both the platform and the hospital audit logs.
          </Alert>
          <Grid container spacing={4}>
            {matrix.data!.roles.map((r) => (
              <Grid key={r.role} size={{ xs: 12, md: 6 }}>
                <SectionCard
                  title={r.name}
                  subtitle={`${r.users} account${r.users === 1 ? '' : 's'} · ${r.current.length} permissions`}
                  action={
                    r.matchesDefault ? (
                      <Chip size="small" color="success" variant="outlined" label="Matches default" />
                    ) : (
                      <Button size="small" startIcon={<RotateCcw size={14} />} onClick={() => setReset(r)}>
                        Reset
                      </Button>
                    )
                  }
                >
                  {r.matchesDefault ? (
                    <Typography variant="body2" color="text.secondary">
                      No changes from the default template.
                    </Typography>
                  ) : (
                    <Stack spacing={2}>
                      {r.added.length > 0 && (
                        <Box>
                          <Typography variant="caption" color="text.secondary">
                            Added by the hospital
                          </Typography>
                          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1} sx={{ mt: 0.5 }}>
                            {r.added.map((p) => (
                              <Chip key={p} size="small" color="warning" variant="outlined" label={`+ ${p}`} />
                            ))}
                          </Stack>
                        </Box>
                      )}
                      {r.removed.length > 0 && (
                        <Box>
                          <Typography variant="caption" color="text.secondary">
                            Removed by the hospital
                          </Typography>
                          <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1} sx={{ mt: 0.5 }}>
                            {r.removed.map((p) => (
                              <Chip key={p} size="small" variant="outlined" label={`− ${p}`} />
                            ))}
                          </Stack>
                        </Box>
                      )}
                    </Stack>
                  )}
                </SectionCard>
              </Grid>
            ))}
          </Grid>
        </>
      )}
      <ConfirmDialog
        open={!!reset}
        title={`Reset ${reset?.name} to the default template?`}
        description={reset ? `${reset.added.length} added permission(s) will be removed and ${reset.removed.length} default permission(s) restored for ${reset.users} account(s).` : ''}
        tone="warning"
        confirmLabel="Reset role"
        loading={resetM.isPending}
        onConfirm={() => reset && resetM.mutate(reset.role)}
        onClose={() => setReset(null)}
      />
    </Stack>
  );
}

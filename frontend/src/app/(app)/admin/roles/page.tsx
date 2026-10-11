'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Box,
  Button,
  Card,
  Checkbox,
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { Lock, RotateCcw, Save } from 'lucide-react';
import { adminApi, RoleMatrix } from '@/lib/api/admin';
import type { Role } from '@/lib/api/types';
import { useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { PageHeader } from '@/components/ui/PageHeader';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { ErrorState, PageSkeleton } from '@/components/ui/States';

const ROLE_ORDER: Role[] = ['HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'PHARMACIST', 'BILLING', 'PATIENT'];
const SHORT: Record<string, string> = {
  HOSPITAL_ADMIN: 'Admin',
  DOCTOR: 'Doctor',
  NURSE: 'Nurse',
  PHARMACIST: 'Pharmacist',
  BILLING: 'Billing',
  PATIENT: 'Patient',
};
/** Permissions the hospital administrator role must keep (server enforces the same rule). */
const ADMIN_LOCKED = new Set(['ROLE_MANAGE', 'USER_MANAGE']);

export default function RolesPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['roles'], queryFn: adminApi.roles });
  const [draft, setDraft] = useState<Record<string, Set<string>>>({});
  const [confirm, setConfirm] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    if (q.data) setDraft(fromMatrix(q.data));
  }, [q.data]);

  const original = useMemo(() => (q.data ? fromMatrix(q.data) : {}), [q.data]);
  const changes = useMemo(() => {
    const out: Array<{ role: Role; added: string[]; removed: string[] }> = [];
    for (const role of ROLE_ORDER) {
      const a = original[role];
      const b = draft[role];
      if (!a || !b) continue;
      const added = [...b].filter((p) => !a.has(p)).sort();
      const removed = [...a].filter((p) => !b.has(p)).sort();
      if (added.length || removed.length) out.push({ role, added, removed });
    }
    return out;
  }, [draft, original]);

  const grouped = useMemo(() => {
    const m = new Map<string, RoleMatrix['permissions']>();
    (q.data?.permissions ?? []).forEach((p) => {
      if (!m.has(p.category)) m.set(p.category, []);
      m.get(p.category)!.push(p);
    });
    return [...m.entries()];
  }, [q.data]);

  const save = useMutation({
    mutationFn: async () => {
      for (const c of changes) {
        await adminApi.updateRole(c.role, [...draft[c.role]]);
      }
    },
    onSuccess: () => {
      toast.success('Role permissions updated. Changes apply to signed-in users on their next request.');
      setConfirm(false);
      qc.invalidateQueries({ queryKey: ['roles'] });
      qc.invalidateQueries({ queryKey: ['me'] });
    },
    onError: (e) => {
      setSaveError(errorMessage(e));
      qc.invalidateQueries({ queryKey: ['roles'] });
    },
  });

  if (q.isLoading) return <PageSkeleton />;
  if (q.error || !q.data) return <ErrorState error={q.error} onRetry={() => q.refetch()} />;
  const roles = ROLE_ORDER.filter((r) => q.data.roles.some((x) => x.role === r));
  const counts = Object.fromEntries(q.data.roles.map((r) => [r.role, r.userCount]));

  const toggle = (role: Role, perm: string) => {
    setDraft((d) => {
      const next = new Set(d[role]);
      if (next.has(perm)) next.delete(perm);
      else next.add(perm);
      return { ...d, [role]: next };
    });
  };

  return (
    <>
      <PageHeader
        title="Roles & permissions"
        subtitle="Least-privilege templates for each role in this hospital. Object-level rules (care team, ward, own records) still apply on top of these permissions."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Roles & permissions' }]}
        actions={
          <>
            <Button startIcon={<RotateCcw size={16} />} color="inherit" disabled={!changes.length} onClick={() => setDraft(original)}>
              Discard
            </Button>
            <Button variant="contained" startIcon={<Save size={16} />} disabled={!changes.length} onClick={() => { setSaveError(null); setConfirm(true); }}>
              Review {changes.length ? `${changes.length} change${changes.length > 1 ? 's' : ''}` : 'changes'}
            </Button>
          </>
        }
      />
      <Alert severity="info" icon={<Lock size={18} />} sx={{ mb: 4 }}>
        Administrators always keep <strong>role management</strong> and <strong>user management</strong> so the hospital cannot lock itself
        out. Clinical record access for administrators must be granted explicitly and is audited. Patients can only hold portal access.
      </Alert>
      {changes.length > 0 && (
        <Alert severity="warning" sx={{ mb: 4 }}>
          Unsaved changes for {changes.map((c) => SHORT[c.role]).join(', ')}.
        </Alert>
      )}
      <Card>
        <TableContainer sx={{ maxHeight: { md: 'calc(100vh - 300px)' }, overflowX: 'auto' }}>
          <Table size="small" stickyHeader aria-label="Role permission matrix">
            <TableHead>
              <TableRow>
                <TableCell sx={{ minWidth: 260, position: 'sticky', left: 0, zIndex: 3, bgcolor: 'background.paper' }}>Permission</TableCell>
                {roles.map((r) => (
                  <TableCell key={r} align="center" sx={{ minWidth: 92 }}>
                    <Typography variant="body2" fontWeight={600}>
                      {SHORT[r]}
                    </Typography>
                    <Typography variant="caption" color="text.secondary">
                      {counts[r] ?? 0} user{counts[r] === 1 ? '' : 's'}
                    </Typography>
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {grouped.map(([category, perms]) => (
                <Fragment key={category}>
                  <TableRow>
                    <TableCell colSpan={roles.length + 1} sx={{ bgcolor: 'action.hover', fontWeight: 600, py: 1.5, position: 'sticky', left: 0 }}>
                      {category}
                    </TableCell>
                  </TableRow>
                  {perms.map((p) => (
                    <TableRow key={p.code} hover>
                      <TableCell sx={{ position: 'sticky', left: 0, bgcolor: 'background.paper', zIndex: 1 }}>
                        <Typography variant="body2">{p.description}</Typography>
                        <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                          {p.code}
                        </Typography>
                      </TableCell>
                      {roles.map((r) => {
                        const checked = draft[r]?.has(p.code) ?? false;
                        const changed = checked !== (original[r]?.has(p.code) ?? false);
                        const locked = (r === 'HOSPITAL_ADMIN' && ADMIN_LOCKED.has(p.code)) || (r === 'PATIENT' && p.code !== 'PORTAL_ACCESS') || (r !== 'PATIENT' && p.code === 'PORTAL_ACCESS');
                        const box = (
                          <Checkbox
                            size="small"
                            checked={checked}
                            disabled={locked}
                            onChange={() => toggle(r, p.code)}
                            slotProps={{ input: { 'aria-label': `${SHORT[r]}: ${p.description}` } }}
                            sx={changed ? { bgcolor: 'warning.light', borderRadius: 1 } : undefined}
                          />
                        );
                        return (
                          <TableCell key={r} align="center" padding="checkbox">
                            {locked && r === 'HOSPITAL_ADMIN' ? <Tooltip title="Kept to prevent administrator lock-out">{box}</Tooltip> : box}
                          </TableCell>
                        );
                      })}
                    </TableRow>
                  ))}
                </Fragment>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      </Card>

      <ConfirmDialog
        open={confirm}
        title="Apply permission changes?"
        description="These changes take effect immediately for every user with the role and are recorded in the audit log."
        confirmLabel="Apply changes"
        tone="warning"
        loading={save.isPending}
        error={saveError}
        onConfirm={() => save.mutate()}
        onClose={() => setConfirm(false)}
      >
        <Stack spacing={3}>
          {changes.map((c) => (
            <Box key={c.role}>
              <Typography variant="subtitle2" sx={{ mb: 1 }}>
                {SHORT[c.role]} ({counts[c.role] ?? 0} users)
              </Typography>
              <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1}>
                {c.added.map((p) => (
                  <Chip key={'+' + p} size="small" color="success" variant="outlined" label={`+ ${p}`} />
                ))}
                {c.removed.map((p) => (
                  <Chip key={'-' + p} size="small" color="error" variant="outlined" label={`− ${p}`} />
                ))}
              </Stack>
            </Box>
          ))}
        </Stack>
      </ConfirmDialog>
    </>
  );
}

function fromMatrix(m: RoleMatrix): Record<string, Set<string>> {
  return Object.fromEntries(m.roles.map((r) => [r.role, new Set(r.permissions)]));
}

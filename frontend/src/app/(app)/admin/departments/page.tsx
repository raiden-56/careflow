'use client';

import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Box, Button, Card, Dialog, DialogActions, DialogContent, DialogTitle, Grid, IconButton, InputAdornment, Stack, TextField, Tooltip, Typography } from '@mui/material';
import { Building2, Pencil, Plus, Search } from 'lucide-react';
import { adminApi } from '@/lib/api/admin';
import { facilityApi, qk, staffApi } from '@/lib/api/core';
import type { DepartmentView } from '@/lib/api/types';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { emptyToNull, optionalText } from '@/lib/validation';
import { PageHeader } from '@/components/ui/PageHeader';
import { DataTable } from '@/components/ui/DataTable';
import { StatusChip } from '@/components/ui/StatusChip';
import { KpiCard } from '@/components/ui/KpiCard';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const schema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9_-]{2,32}$/, 'Use 2-32 upper-case letters, digits, - or _'),
  name: z.string().trim().min(2, 'Name is required').max(200),
  specialty: optionalText(200, 'Specialty'),
  description: optionalText(1000, 'Description'),
  headUserId: z.string().optional(),
  active: z.boolean(),
});
type Values = z.infer<typeof schema>;

export default function DepartmentsPage() {
  const toast = useToast();
  const qc = useQueryClient();
  const q = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments });
  const [search, setSearch] = useState('');
  const [edit, setEdit] = useState<{ open: boolean; dept?: DepartmentView }>({ open: false });
  const rows = useMemo(() => {
    const s = search.trim().toLowerCase();
    return (q.data ?? []).filter((d) => !s || d.name.toLowerCase().includes(s) || d.code.toLowerCase().includes(s) || (d.specialty ?? '').toLowerCase().includes(s));
  }, [q.data, search]);

  return (
    <>
      <PageHeader
        title="Departments"
        subtitle="Clinical and administrative departments, specialties and heads of department. Deactivate instead of deleting — admissions keep their history."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Departments' }]}
        actions={
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setEdit({ open: true })}>
            Add department
          </Button>
        }
      />
      <Grid container spacing={4} sx={{ mb: 5 }}>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Active departments" value={(q.data ?? []).filter((d) => d.active).length} icon={Building2} loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Staff assigned" value={(q.data ?? []).reduce((s, d) => s + d.staffCount, 0)} icon={Building2} tone="info" loading={q.isLoading} />
        </Grid>
        <Grid size={{ xs: 12, sm: 4 }}>
          <KpiCard label="Wards linked" value={(q.data ?? []).reduce((s, d) => s + d.wardCount, 0)} icon={Building2} tone="secondary" loading={q.isLoading} />
        </Grid>
      </Grid>
      <Card>
        <Box sx={{ p: 4 }}>
          <TextField
            placeholder="Search departments"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search departments"
            slotProps={{ input: { startAdornment: <InputAdornment position="start"><Search size={16} /></InputAdornment> } }}
            sx={{ maxWidth: { sm: 320 } }}
          />
        </Box>
        <DataTable<DepartmentView>
          ariaLabel="Departments"
          rows={q.data ? rows : undefined}
          loading={q.isFetching}
          error={q.error}
          onRetry={() => q.refetch()}
          rowKey={(d) => d.id}
          emptyTitle={search ? 'No departments match' : 'No departments yet'}
          columns={[
            {
              key: 'name',
              header: 'Department',
              primary: true,
              render: (d) => (
                <Box>
                  <Typography variant="body2" fontWeight={600}>
                    {d.name}
                  </Typography>
                  <Typography variant="caption" color="text.secondary" sx={{ fontFamily: 'monospace' }}>
                    {d.code}
                  </Typography>
                </Box>
              ),
            },
            { key: 'spec', header: 'Specialty', render: (d) => d.specialty ?? '—' },
            { key: 'head', header: 'Head of department', render: (d) => d.headName ?? '—' },
            { key: 'staff', header: 'Staff', align: 'right', render: (d) => d.staffCount },
            { key: 'wards', header: 'Wards', align: 'right', render: (d) => d.wardCount },
            { key: 'status', header: 'Status', render: (d) => <StatusChip value={d.active ? 'ACTIVE' : 'INACTIVE'} /> },
            {
              key: 'edit',
              header: '',
              align: 'right',
              render: (d) => (
                <Tooltip title="Edit">
                  <IconButton size="small" aria-label={`Edit ${d.name}`} onClick={() => setEdit({ open: true, dept: d })}>
                    <Pencil size={16} />
                  </IconButton>
                </Tooltip>
              ),
            },
          ]}
        />
      </Card>
      <DepartmentDialog
        open={edit.open}
        dept={edit.dept}
        onClose={() => setEdit({ open: false })}
        onSaved={() => {
          toast.success(edit.dept ? 'Department updated' : 'Department created');
          setEdit({ open: false });
          qc.invalidateQueries({ queryKey: qk.departments });
          qc.invalidateQueries({ queryKey: qk.wards });
        }}
      />
    </>
  );
}

function DepartmentDialog({ open, dept, onClose, onSaved }: { open: boolean; dept?: DepartmentView; onClose: () => void; onSaved: () => void }) {
  const [error, setError] = useState<string | null>(null);
  const doctors = useQuery({ queryKey: qk.directory({ role: 'DOCTOR' }), queryFn: () => staffApi.directory({ role: 'DOCTOR' }), enabled: open });
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<Values>({ resolver: zodResolver(schema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: dept?.code ?? '',
        name: dept?.name ?? '',
        specialty: dept?.specialty ?? '',
        description: dept?.description ?? '',
        headUserId: dept?.headUserId ?? '',
        active: dept?.active ?? true,
      });
    }
  }, [open, dept, reset]);
  const m = useMutation({
    mutationFn: (v: Values) => {
      const body = { ...emptyToNull({ code: v.code, name: v.name, specialty: v.specialty ?? '', description: v.description ?? '', headUserId: v.headUserId ?? '' }), active: v.active };
      return dept ? adminApi.updateDepartment(dept.id, body as never) : adminApi.createDepartment(body as never);
    },
    onSuccess: onSaved,
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="dept-title">
      <DialogTitle id="dept-title">{dept ? `Edit ${dept.name}` : 'Add department'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="dept-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="code" label="Code" required disabled={!!dept} sx={{ flex: 1 }} helperText={dept ? 'Codes cannot change' : 'e.g. NEPHRO'} />
            <RHFTextField control={control} name="name" label="Name" required sx={{ flex: 2 }} />
          </Stack>
          <RHFTextField control={control} name="specialty" label="Specialty" fullWidth />
          <RHFSelect
            control={control}
            name="headUserId"
            label="Head of department"
            fullWidth
            emptyLabel="Not assigned"
            options={(doctors.data ?? []).map((d) => ({ value: d.id, label: `${d.fullName}${d.departmentName ? ' · ' + d.departmentName : ''}` }))}
          />
          <RHFTextField control={control} name="description" label="Description" fullWidth multiline minRows={2} />
          <RHFCheckbox control={control} name="active" label="Active" helperText="Inactive departments are hidden from new admissions and staff assignment." />
          {dept && (
            <Typography variant="caption" color="text.secondary">
              {dept.staffCount} staff and {dept.wardCount} wards are linked to this department.
            </Typography>
          )}
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="dept-form" variant="contained" disabled={m.isPending}>
          {m.isPending ? 'Saving…' : 'Save'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

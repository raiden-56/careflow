'use client';

import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
  Button,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Grid,
  TextField,
  Typography,
} from '@mui/material';
import { adminApi, StaffView } from '@/lib/api/admin';
import { facilityApi, qk } from '@/lib/api/core';
import type { Role, WardView } from '@/lib/api/types';
import { applyServerErrors } from '@/lib/hooks';
import { emptyToNull, optionalText, phoneSchema } from '@/lib/validation';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { errorMessage } from '@/lib/api/client';

export const STAFF_ROLES: { value: Role; label: string }[] = [
  { value: 'DOCTOR', label: 'Doctor' },
  { value: 'NURSE', label: 'Nurse' },
  { value: 'PHARMACIST', label: 'Pharmacist' },
  { value: 'BILLING', label: 'Billing & accounts' },
  { value: 'HOSPITAL_ADMIN', label: 'Hospital administrator' },
];

const schema = z
  .object({
    email: z.string().trim().email('Enter a valid email address').max(200),
    fullName: z.string().trim().min(2, 'Full name is required').max(200),
    phone: phoneSchema,
    role: z.enum(['DOCTOR', 'NURSE', 'PHARMACIST', 'BILLING', 'HOSPITAL_ADMIN'], { message: 'Select a role' }),
    departmentId: z.string().optional(),
    designation: optionalText(100, 'Designation'),
    specialty: optionalText(200, 'Specialty'),
    registrationNumber: optionalText(100, 'Registration number'),
    qualification: optionalText(200, 'Qualification'),
    employeeCode: optionalText(50, 'Employee code'),
  });
type Values = z.infer<typeof schema>;

export function StaffDialog({
  open,
  staff,
  onClose,
  onSaved,
}: {
  open: boolean;
  staff?: StaffView | null;
  onClose: () => void;
  onSaved: (s: StaffView, created: boolean) => void;
}) {
  const editing = !!staff;
  const [error, setError] = useState<string | null>(null);
  const [wards, setWards] = useState<WardView[]>([]);
  const [saving, setSaving] = useState(false);
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments, enabled: open });
  const wardList = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards, enabled: open });
  const current = useQuery({
    queryKey: ['staff', staff?.id, 'wards'],
    queryFn: () => adminApi.staffWards(staff!.id),
    enabled: open && !!staff,
  });
  const { control, handleSubmit, reset, watch, setError: setFieldError } = useForm<Values>({ resolver: zodResolver(schema) });
  const role = watch('role');

  useEffect(() => {
    if (!open) return;
    setError(null);
    reset({
      email: staff?.email ?? '',
      fullName: staff?.fullName ?? '',
      phone: staff?.phone ?? '',
      role: (staff?.role ?? '') as Values['role'],
      departmentId: staff?.departmentId ?? '',
      designation: staff?.designation ?? '',
      specialty: staff?.specialty ?? '',
      registrationNumber: staff?.registrationNumber ?? '',
      qualification: staff?.qualification ?? '',
      employeeCode: staff?.employeeCode ?? '',
    });
    if (!staff) setWards([]);
  }, [open, staff, reset]);

  useEffect(() => {
    if (current.data && wardList.data) {
      const ids = new Set(current.data.map((w) => w.wardId));
      setWards(wardList.data.filter((w) => ids.has(w.id)));
    }
  }, [current.data, wardList.data]);

  const wardsAllowed = role === 'NURSE' || role === 'DOCTOR';

  const submit = handleSubmit(async (v) => {
    setSaving(true);
    setError(null);
    try {
      const common = emptyToNull({
        fullName: v.fullName,
        phone: v.phone ?? '',
        departmentId: v.departmentId ?? '',
        designation: v.designation ?? '',
        specialty: v.specialty ?? '',
        registrationNumber: v.registrationNumber ?? '',
        qualification: v.qualification ?? '',
        employeeCode: v.employeeCode ?? '',
      });
      let saved: StaffView;
      if (editing) {
        saved = await adminApi.updateStaff(staff!.id, { ...common, version: staff!.version } as never);
        await adminApi.setStaffWards(staff!.id, wardsAllowed ? wards.map((w) => w.id) : []);
      } else {
        saved = await adminApi.createStaff({ ...common, email: v.email, role: v.role, wardIds: wardsAllowed ? wards.map((w) => w.id) : [] } as never);
      }
      onSaved(saved, !editing);
    } catch (e) {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    } finally {
      setSaving(false);
    }
  });

  return (
    <Dialog open={open} onClose={saving ? undefined : onClose} maxWidth="md" fullWidth aria-labelledby="staff-dialog-title">
      <DialogTitle id="staff-dialog-title">{editing ? `Edit ${staff?.fullName}` : 'Add staff member'}</DialogTitle>
      <DialogContent>
        <form id="staff-form" onSubmit={submit} noValidate>
          <Grid container spacing={3} sx={{ pt: 1 }}>
            {error && (
              <Grid size={12}>
                <Alert severity="error">{error}</Alert>
              </Grid>
            )}
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="fullName" label="Full name" required fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="email" label="Work email" required fullWidth disabled={editing} type="email" helperText={editing ? 'Sign-in email cannot be changed' : 'An invitation link will be emailed'} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect control={control} name="role" label="Role" required fullWidth options={STAFF_ROLES} disabled={editing} helperText={editing ? 'Roles are fixed per account' : undefined} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="phone" label="Phone" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFSelect
                control={control}
                name="departmentId"
                label="Department"
                fullWidth
                emptyLabel="No department"
                options={(departments.data ?? []).filter((d) => d.active || d.id === staff?.departmentId).map((d) => ({ value: d.id, label: d.name }))}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="designation" label="Designation" fullWidth placeholder="e.g. Staff Nurse" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="specialty" label="Specialty" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="registrationNumber" label="Registration number" fullWidth helperText="Medical / nursing council registration" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="qualification" label="Qualification" fullWidth />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="employeeCode" label="Employee code" fullWidth />
            </Grid>
            {wardsAllowed && (
              <Grid size={12}>
                <Autocomplete
                  multiple
                  options={(wardList.data ?? []).filter((w) => w.active)}
                  value={wards}
                  onChange={(_, v) => setWards(v)}
                  getOptionLabel={(w) => w.name}
                  isOptionEqualToValue={(a, b) => a.id === b.id}
                  renderTags={(value, getTagProps) =>
                    value.map((w, i) => {
                      const { key, ...rest } = getTagProps({ index: i });
                      return <Chip key={key} size="small" label={w.name} {...rest} />;
                    })
                  }
                  renderInput={(params) => (
                    <TextField
                      {...params}
                      label="Ward assignments"
                      helperText={role === 'NURSE' ? 'Nurses see and care for patients in their assigned wards.' : 'Optional for doctors.'}
                    />
                  )}
                />
              </Grid>
            )}
            {!editing && (
              <Grid size={12}>
                <Typography variant="caption" color="text.secondary">
                  The new account must set its own password through the emailed invitation (valid for 3 days). In this
                  development build, emails appear in the dev mailbox.
                </Typography>
              </Grid>
            )}
          </Grid>
        </form>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={saving}>
          Cancel
        </Button>
        <Button type="submit" form="staff-form" variant="contained" disabled={saving}>
          {saving ? 'Saving…' : editing ? 'Save changes' : 'Create & invite'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

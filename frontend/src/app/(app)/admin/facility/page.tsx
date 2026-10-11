'use client';

import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Alert,
  Autocomplete,
  Box,
  Button,
  Card,
  CardActionArea,
  Chip,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Divider,
  Grid,
  IconButton,
  Stack,
  Tab,
  Tabs,
  TextField,
  Tooltip,
  Typography,
} from '@mui/material';
import { BedDouble, DoorOpen, Pencil, Plus, UserMinus, UserPlus } from 'lucide-react';
import { adminApi, ConfigBed, RoomView, WardStaffView } from '@/lib/api/admin';
import { facilityApi, qk, staffApi } from '@/lib/api/core';
import type { BedType, DirectoryEntry, RoomType, WardType, WardView } from '@/lib/api/types';
import { applyServerErrors, useToast } from '@/lib/hooks';
import { errorMessage } from '@/lib/api/client';
import { fromNow, humanize } from '@/lib/format';
import { optionalText } from '@/lib/validation';
import { PageHeader } from '@/components/ui/PageHeader';
import { SectionCard } from '@/components/ui/SectionCard';
import { StatusChip } from '@/components/ui/StatusChip';
import { ConfirmDialog } from '@/components/ui/ConfirmDialog';
import { CardsSkeleton, EmptyState, ErrorState } from '@/components/ui/States';
import { RHFCheckbox, RHFSelect, RHFTextField } from '@/components/forms/Fields';

const WARD_TYPES: WardType[] = ['GENERAL', 'ICU', 'HDU', 'PRIVATE', 'PAEDIATRIC', 'MATERNITY', 'ISOLATION', 'DAYCARE'];
const ROOM_TYPES: RoomType[] = ['GENERAL', 'SEMI_PRIVATE', 'PRIVATE', 'DELUXE', 'ICU', 'ISOLATION'];
const BED_TYPES: BedType[] = ['STANDARD', 'ICU', 'ISOLATION', 'PAEDIATRIC', 'BARIATRIC', 'MATERNITY'];
const opts = (vals: string[]) => vals.map((v) => ({ value: v, label: humanize(v) }));

export default function FacilityPage() {
  const qc = useQueryClient();
  const wards = useQuery({ queryKey: qk.wards, queryFn: facilityApi.wards });
  const [selected, setSelected] = useState<string | null>(null);
  const [wardDialog, setWardDialog] = useState<{ open: boolean; ward?: WardView }>({ open: false });
  const ward = wards.data?.find((w) => w.id === selected) ?? wards.data?.[0];

  useEffect(() => {
    if (!selected && wards.data?.length) setSelected(wards.data[0].id);
  }, [wards.data, selected]);

  return (
    <>
      <PageHeader
        title="Wards, rooms & beds"
        subtitle="Configure the physical layout and ward nursing cover. Occupied beds cannot be moved or deactivated; bed status changes happen on the bed board."
        breadcrumbs={[{ label: 'Administration', href: '/admin' }, { label: 'Wards, rooms & beds' }]}
        actions={
          <Button variant="contained" startIcon={<Plus size={16} />} onClick={() => setWardDialog({ open: true })}>
            Add ward
          </Button>
        }
      />
      {wards.isLoading ? (
        <CardsSkeleton />
      ) : wards.error ? (
        <ErrorState error={wards.error} onRetry={() => wards.refetch()} />
      ) : !wards.data?.length ? (
        <Card>
          <EmptyState icon={BedDouble} title="No wards yet" description="Create the first ward, then add its rooms and beds." action={<Button variant="contained" onClick={() => setWardDialog({ open: true })}>Add ward</Button>} />
        </Card>
      ) : (
        <Grid container spacing={5}>
          <Grid size={{ xs: 12, md: 4 }}>
            <Stack spacing={2} role="list" aria-label="Wards">
              {wards.data.map((w) => (
                <Card key={w.id} role="listitem" sx={{ borderColor: w.id === ward?.id ? 'primary.main' : undefined, borderWidth: w.id === ward?.id ? 2 : 1, borderStyle: 'solid' }}>
                  <CardActionArea onClick={() => setSelected(w.id)} sx={{ p: 3 }} aria-current={w.id === ward?.id}>
                    <Stack direction="row" justifyContent="space-between" spacing={2} alignItems="flex-start">
                      <Box sx={{ minWidth: 0 }}>
                        <Typography variant="body2" fontWeight={600}>
                          {w.name}
                        </Typography>
                        <Typography variant="caption" color="text.secondary">
                          {w.code} · {humanize(w.wardType)} · Floor {w.floor ?? '—'}
                        </Typography>
                      </Box>
                      {!w.active && <StatusChip value="INACTIVE" />}
                    </Stack>
                    <Typography variant="caption" color="text.secondary" component="div" sx={{ mt: 1 }}>
                      {w.totalBeds} beds · {w.occupied} occupied · {w.available} available
                    </Typography>
                  </CardActionArea>
                </Card>
              ))}
            </Stack>
          </Grid>
          <Grid size={{ xs: 12, md: 8 }}>{ward && <WardPanel key={ward.id} ward={ward} onEdit={() => setWardDialog({ open: true, ward })} />}</Grid>
        </Grid>
      )}
      <WardDialog
        open={wardDialog.open}
        ward={wardDialog.ward}
        onClose={() => setWardDialog({ open: false })}
        onSaved={(id) => {
          setWardDialog({ open: false });
          setSelected(id);
          qc.invalidateQueries({ queryKey: qk.wards });
          qc.invalidateQueries({ queryKey: ['beds'] });
        }}
      />
    </>
  );
}

// ------------------------------------------------------------------ ward panel

function WardPanel({ ward, onEdit }: { ward: WardView; onEdit: () => void }) {
  const [tab, setTab] = useState<'layout' | 'staff'>('layout');
  return (
    <SectionCard
      title={ward.name}
      subtitle={`${ward.departmentName ?? 'No department'} · ${humanize(ward.genderPolicy)} · ${ward.totalBeds} active beds`}
      action={
        <Button size="small" startIcon={<Pencil size={14} />} onClick={onEdit}>
          Edit ward
        </Button>
      }
      flush
    >
      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ px: 4, borderBottom: 1, borderColor: 'divider' }}>
        <Tab value="layout" label="Rooms & beds" />
        <Tab value="staff" label="Ward staff" />
      </Tabs>
      <Box sx={{ p: { xs: 3, sm: 5 } }}>{tab === 'layout' ? <RoomsAndBeds ward={ward} /> : <WardStaff ward={ward} />}</Box>
    </SectionCard>
  );
}

function RoomsAndBeds({ ward }: { ward: WardView }) {
  const qc = useQueryClient();
  const rooms = useQuery({ queryKey: ['facility', 'rooms', ward.id], queryFn: () => adminApi.rooms(ward.id) });
  const beds = useQuery({ queryKey: ['facility', 'beds', ward.id], queryFn: () => adminApi.wardBeds(ward.id) });
  const [roomDialog, setRoomDialog] = useState<{ open: boolean; room?: RoomView }>({ open: false });
  const [bedDialog, setBedDialog] = useState<{ open: boolean; bed?: ConfigBed; roomId?: string }>({ open: false });
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['facility'] });
    qc.invalidateQueries({ queryKey: qk.wards });
    qc.invalidateQueries({ queryKey: ['beds'] });
  };
  if (rooms.isLoading || beds.isLoading) return <CardsSkeleton count={2} height={80} />;
  if (rooms.error || beds.error) return <ErrorState error={rooms.error ?? beds.error} onRetry={() => { rooms.refetch(); beds.refetch(); }} />;
  return (
    <Stack spacing={4}>
      <Stack direction="row" justifyContent="flex-end">
        <Button variant="outlined" size="small" startIcon={<DoorOpen size={14} />} onClick={() => setRoomDialog({ open: true })}>
          Add room
        </Button>
      </Stack>
      {!rooms.data?.length && <EmptyState icon={DoorOpen} title="No rooms in this ward" description="Add a room, then add its beds." compact />}
      {rooms.data?.map((r) => {
        const roomBeds = (beds.data ?? []).filter((b) => b.roomId === r.id);
        return (
          <Card key={r.id} variant="outlined">
            <Stack direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" alignItems={{ sm: 'center' }} spacing={2} sx={{ px: 3, py: 2.5 }}>
              <Box>
                <Typography variant="body2" fontWeight={600}>
                  Room {r.roomNumber} {!r.active && <StatusChip value="INACTIVE" sx={{ ml: 1 }} />}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {humanize(r.roomType)} · {roomBeds.length} bed{roomBeds.length === 1 ? '' : 's'}
                </Typography>
              </Box>
              <Stack direction="row" spacing={1}>
                <Button size="small" onClick={() => setRoomDialog({ open: true, room: r })}>
                  Edit room
                </Button>
                <Button size="small" startIcon={<Plus size={14} />} onClick={() => setBedDialog({ open: true, roomId: r.id })} disabled={!r.active}>
                  Add bed
                </Button>
              </Stack>
            </Stack>
            <Divider />
            {roomBeds.length === 0 ? (
              <Typography variant="caption" color="text.secondary" sx={{ display: 'block', px: 3, py: 2 }}>
                No beds yet.
              </Typography>
            ) : (
              <Stack direction="row" flexWrap="wrap" useFlexGap spacing={1.5} sx={{ p: 3 }}>
                {roomBeds.map((b) => (
                  <Chip
                    key={b.id}
                    icon={<BedDouble size={14} />}
                    label={`${b.bedNumber} · ${humanize(b.bedType)}${b.active ? '' : ' · inactive'}`}
                    variant={b.active ? 'outlined' : 'filled'}
                    onClick={() => setBedDialog({ open: true, bed: b, roomId: r.id })}
                    onDelete={() => setBedDialog({ open: true, bed: b, roomId: r.id })}
                    deleteIcon={<Pencil size={12} aria-label={`Edit bed ${b.bedNumber}`} />}
                    sx={{ opacity: b.active ? 1 : 0.6 }}
                  />
                ))}
              </Stack>
            )}
          </Card>
        );
      })}
      <RoomDialog open={roomDialog.open} wardId={ward.id} room={roomDialog.room} onClose={() => setRoomDialog({ open: false })} onSaved={() => { setRoomDialog({ open: false }); refresh(); }} />
      <BedDialog
        open={bedDialog.open}
        bed={bedDialog.bed}
        roomId={bedDialog.roomId}
        rooms={rooms.data ?? []}
        onClose={() => setBedDialog({ open: false })}
        onSaved={() => { setBedDialog({ open: false }); refresh(); }}
      />
    </Stack>
  );
}

function WardStaff({ ward }: { ward: WardView }) {
  const toast = useToast();
  const qc = useQueryClient();
  const staff = useQuery({ queryKey: ['facility', 'ward-staff', ward.id], queryFn: () => adminApi.wardStaff(ward.id) });
  const nurses = useQuery({ queryKey: qk.directory({ role: 'NURSE' }), queryFn: () => staffApi.directory({ role: 'NURSE' }) });
  const doctors = useQuery({ queryKey: qk.directory({ role: 'DOCTOR' }), queryFn: () => staffApi.directory({ role: 'DOCTOR' }) });
  const [pick, setPick] = useState<DirectoryEntry | null>(null);
  const [remove, setRemove] = useState<WardStaffView | null>(null);
  const options = useMemo(() => {
    const assigned = new Set((staff.data ?? []).map((s) => s.userId));
    return [...(nurses.data ?? []), ...(doctors.data ?? [])].filter((d) => !assigned.has(d.id));
  }, [staff.data, nurses.data, doctors.data]);
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['facility', 'ward-staff', ward.id] });
    qc.invalidateQueries({ queryKey: ['staff'] });
  };
  const add = useMutation({
    mutationFn: (userId: string) => adminApi.assignWardStaff(ward.id, userId),
    onSuccess: (s) => {
      toast.success(`${s.fullName} assigned to ${ward.name}`);
      setPick(null);
      refresh();
    },
    onError: toast.error,
  });
  const del = useMutation({
    mutationFn: (s: WardStaffView) => adminApi.removeWardStaff(ward.id, s.assignmentId),
    onSuccess: () => {
      toast.success('Assignment ended');
      setRemove(null);
      refresh();
    },
    onError: toast.error,
  });
  return (
    <Stack spacing={4}>
      <Stack direction={{ xs: 'column', sm: 'row' }} spacing={2}>
        <Autocomplete
          sx={{ flex: 1 }}
          options={options}
          value={pick}
          onChange={(_, v) => setPick(v)}
          getOptionLabel={(o) => `${o.fullName} · ${humanize(o.role)}${o.departmentName ? ' · ' + o.departmentName : ''}`}
          renderInput={(p) => <TextField {...p} label="Assign a nurse or doctor" />}
        />
        <Button variant="contained" startIcon={<UserPlus size={16} />} disabled={!pick || add.isPending} onClick={() => pick && add.mutate(pick.id)}>
          Assign
        </Button>
      </Stack>
      {staff.isLoading ? (
        <CardsSkeleton count={2} height={60} />
      ) : staff.error ? (
        <ErrorState error={staff.error} onRetry={() => staff.refetch()} />
      ) : !staff.data?.length ? (
        <Alert severity="warning">No staff assigned. Nurses only see patients in wards they are assigned to.</Alert>
      ) : (
        <Stack divider={<Divider flexItem />}>
          {staff.data.map((s) => (
            <Stack key={s.assignmentId} direction="row" alignItems="center" spacing={2} sx={{ py: 2 }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography variant="body2" fontWeight={500}>
                  {s.fullName}
                </Typography>
                <Typography variant="caption" color="text.secondary">
                  {humanize(s.role)} · assigned {fromNow(s.assignedAt)}
                </Typography>
              </Box>
              <Tooltip title="End assignment">
                <IconButton size="small" aria-label={`Remove ${s.fullName} from ${ward.name}`} onClick={() => setRemove(s)}>
                  <UserMinus size={16} />
                </IconButton>
              </Tooltip>
            </Stack>
          ))}
        </Stack>
      )}
      <ConfirmDialog
        open={!!remove}
        title={`Remove ${remove?.fullName} from ${ward.name}?`}
        description="They will no longer see this ward's patients unless they are on a patient's care team."
        tone="error"
        confirmLabel="Remove"
        loading={del.isPending}
        onConfirm={() => remove && del.mutate(remove)}
        onClose={() => setRemove(null)}
      />
    </Stack>
  );
}

// ------------------------------------------------------------------ dialogs

const wardSchema = z.object({
  code: z.string().trim().regex(/^[A-Z0-9_-]{2,32}$/, 'Use 2-32 upper-case letters, digits, - or _'),
  name: z.string().trim().min(2, 'Name is required').max(200),
  wardType: z.enum(WARD_TYPES as [WardType, ...WardType[]], { message: 'Select a ward type' }),
  departmentId: z.string().optional(),
  floor: optionalText(20, 'Floor'),
  genderPolicy: z.enum(['MIXED', 'MALE', 'FEMALE']),
  active: z.boolean(),
});

function WardDialog({ open, ward, onClose, onSaved }: { open: boolean; ward?: WardView; onClose: () => void; onSaved: (id: string) => void }) {
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const departments = useQuery({ queryKey: qk.departments, queryFn: facilityApi.departments, enabled: open });
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<z.infer<typeof wardSchema>>({ resolver: zodResolver(wardSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        code: ward?.code ?? '',
        name: ward?.name ?? '',
        wardType: (ward?.wardType ?? '') as WardType,
        departmentId: ward?.departmentId ?? '',
        floor: ward?.floor ?? '',
        genderPolicy: ward?.genderPolicy ?? 'MIXED',
        active: ward?.active ?? true,
      });
    }
  }, [open, ward, reset]);
  const m = useMutation({
    mutationFn: (v: z.infer<typeof wardSchema>) => {
      const body = { ...v, departmentId: v.departmentId || null, floor: v.floor || null };
      return (ward ? adminApi.updateWard(ward.id, body) : adminApi.createWard(body)) as Promise<WardView>;
    },
    onSuccess: (w) => {
      toast.success(ward ? 'Ward updated' : 'Ward created');
      onSaved(w.id);
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="sm" fullWidth aria-labelledby="ward-title">
      <DialogTitle id="ward-title">{ward ? `Edit ${ward.name}` : 'Add ward'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="ward-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFTextField control={control} name="code" label="Code" required sx={{ flex: 1 }} />
            <RHFTextField control={control} name="name" label="Name" required sx={{ flex: 2 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="wardType" label="Ward type" required options={opts(WARD_TYPES)} sx={{ flex: 1 }} />
            <RHFSelect control={control} name="genderPolicy" label="Gender policy" options={opts(['MIXED', 'MALE', 'FEMALE'])} sx={{ flex: 1 }} />
          </Stack>
          <Stack direction={{ xs: 'column', sm: 'row' }} spacing={3}>
            <RHFSelect control={control} name="departmentId" label="Department" emptyLabel="None" options={(departments.data ?? []).map((d) => ({ value: d.id, label: d.name }))} sx={{ flex: 2 }} />
            <RHFTextField control={control} name="floor" label="Floor" sx={{ flex: 1 }} />
          </Stack>
          <RHFCheckbox control={control} name="active" label="Active" helperText="Inactive wards are hidden from the bed board and bed selection." />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="ward-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const roomSchema = z.object({
  roomNumber: z.string().trim().min(1, 'Room number is required').max(20),
  roomType: z.enum(ROOM_TYPES as [RoomType, ...RoomType[]], { message: 'Select a room type' }),
  active: z.boolean(),
});

function RoomDialog({ open, wardId, room, onClose, onSaved }: { open: boolean; wardId: string; room?: RoomView; onClose: () => void; onSaved: () => void }) {
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<z.infer<typeof roomSchema>>({ resolver: zodResolver(roomSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({ roomNumber: room?.roomNumber ?? '', roomType: (room?.roomType ?? '') as RoomType, active: room?.active ?? true });
    }
  }, [open, room, reset]);
  const m = useMutation({
    mutationFn: (v: z.infer<typeof roomSchema>) => (room ? adminApi.updateRoom(wardId, room.id, v) : adminApi.createRoom(wardId, v)),
    onSuccess: () => {
      toast.success(room ? 'Room updated' : 'Room added');
      onSaved();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="room-title">
      <DialogTitle id="room-title">{room ? `Edit room ${room.roomNumber}` : 'Add room'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="room-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          <RHFTextField control={control} name="roomNumber" label="Room number" required fullWidth />
          <RHFSelect control={control} name="roomType" label="Room type" required options={opts(ROOM_TYPES)} fullWidth helperText="Room type selects the daily room tariff." />
          <RHFCheckbox control={control} name="active" label="Active" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="room-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

const bedSchema = z.object({
  roomId: z.string().min(1, 'Select a room'),
  bedNumber: z.string().trim().min(1, 'Bed number is required').max(20),
  bedType: z.enum(BED_TYPES as [BedType, ...BedType[]], { message: 'Select a bed type' }),
  isolationCapable: z.boolean(),
  oxygenSupported: z.boolean(),
  notes: optionalText(500, 'Notes'),
  active: z.boolean(),
});

function BedDialog({
  open,
  bed,
  roomId,
  rooms,
  onClose,
  onSaved,
}: {
  open: boolean;
  bed?: ConfigBed;
  roomId?: string;
  rooms: RoomView[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const { control, handleSubmit, reset, setError: setFieldError } = useForm<z.infer<typeof bedSchema>>({ resolver: zodResolver(bedSchema) });
  useEffect(() => {
    if (open) {
      setError(null);
      reset({
        roomId: bed?.roomId ?? roomId ?? '',
        bedNumber: bed?.bedNumber ?? '',
        bedType: (bed?.bedType ?? 'STANDARD') as BedType,
        isolationCapable: bed?.isolationCapable ?? false,
        oxygenSupported: bed?.oxygenSupported ?? false,
        notes: bed?.notes ?? '',
        active: bed?.active ?? true,
      });
    }
  }, [open, bed, roomId, reset]);
  const occupied = bed?.status === 'OCCUPIED';
  const m = useMutation({
    mutationFn: (v: z.infer<typeof bedSchema>) => {
      const body = { ...v, notes: v.notes || null };
      return bed ? adminApi.updateBed(bed.id, body) : adminApi.createBed(body);
    },
    onSuccess: () => {
      toast.success(bed ? 'Bed updated' : 'Bed added');
      onSaved();
    },
    onError: (e) => {
      if (!applyServerErrors(e, setFieldError)) setError(errorMessage(e));
    },
  });
  return (
    <Dialog open={open} onClose={m.isPending ? undefined : onClose} maxWidth="xs" fullWidth aria-labelledby="bed-title">
      <DialogTitle id="bed-title">{bed ? `Edit bed ${bed.bedNumber}` : 'Add bed'}</DialogTitle>
      <DialogContent>
        <Stack component="form" id="bed-form" spacing={3} sx={{ pt: 1 }} onSubmit={handleSubmit((v) => m.mutate(v))} noValidate>
          {error && <Alert severity="error">{error}</Alert>}
          {bed && (
            <Stack direction="row" spacing={1} alignItems="center">
              <Typography variant="caption" color="text.secondary">
                Current status
              </Typography>
              <StatusChip value={bed.status} />
            </Stack>
          )}
          {occupied && <Alert severity="info">This bed is occupied: it cannot be moved to another room or deactivated until it is released.</Alert>}
          <RHFSelect control={control} name="roomId" label="Room" required fullWidth disabled={occupied} options={rooms.map((r) => ({ value: r.id, label: `Room ${r.roomNumber} (${humanize(r.roomType)})` }))} />
          <Stack direction="row" spacing={3}>
            <RHFTextField control={control} name="bedNumber" label="Bed number" required sx={{ flex: 1 }} />
            <RHFSelect control={control} name="bedType" label="Bed type" options={opts(BED_TYPES)} sx={{ flex: 1 }} />
          </Stack>
          <RHFCheckbox control={control} name="isolationCapable" label="Isolation capable" />
          <RHFCheckbox control={control} name="oxygenSupported" label="Piped oxygen" />
          <RHFTextField control={control} name="notes" label="Notes" fullWidth multiline minRows={2} />
          <RHFCheckbox control={control} name="active" label="Active (available for allocation)" />
        </Stack>
      </DialogContent>
      <DialogActions sx={{ px: 6, pb: 4 }}>
        <Button color="inherit" onClick={onClose} disabled={m.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="bed-form" variant="contained" disabled={m.isPending}>
          Save
        </Button>
      </DialogActions>
    </Dialog>
  );
}

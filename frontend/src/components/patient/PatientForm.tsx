'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Alert, Box, Button, Divider, Grid, Stack, Typography } from '@mui/material';
import type { PatientDetail, PatientRequest } from '@/lib/api/types';
import { ApiError } from '@/lib/api/client';
import { applyServerErrors } from '@/lib/hooks';
import { emailOptional, emptyToNull, optionalText, phoneSchema, pincodeSchema } from '@/lib/validation';
import { RHFSelect, RHFTextField } from '@/components/forms/Fields';
import { todayLocalDate } from '@/lib/format';

const schema = z.object({
  firstName: z.string().trim().min(1, 'First name is required').max(100),
  lastName: z.string().trim().min(1, 'Last name is required').max(100),
  dateOfBirth: z
    .string()
    .min(1, 'Date of birth is required')
    .refine((v) => v <= todayLocalDate(), 'Date of birth cannot be in the future')
    .refine((v) => v >= '1900-01-01', 'Enter a valid date of birth'),
  gender: z.enum(['MALE', 'FEMALE', 'OTHER', 'UNDISCLOSED'], { message: 'Select a gender' }),
  bloodGroup: z.string().optional(),
  phone: phoneSchema,
  email: emailOptional,
  addressLine: optionalText(500, 'Address'),
  city: optionalText(100, 'City'),
  state: optionalText(100, 'State'),
  pincode: pincodeSchema,
  emergencyContactName: optionalText(200, 'Name'),
  emergencyContactPhone: phoneSchema,
  emergencyContactRelation: optionalText(50, 'Relationship'),
  abhaNumber: z
    .string()
    .trim()
    .regex(/^[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{4}$/, 'Format: 12-3456-7890-1234')
    .optional()
    .or(z.literal('')),
  preferredLanguage: optionalText(50, 'Language'),
});
export type PatientFormValues = z.infer<typeof schema>;

const BLOOD_GROUPS = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'];

function toValues(p?: PatientDetail): PatientFormValues {
  return {
    firstName: p?.firstName ?? '',
    lastName: p?.lastName ?? '',
    dateOfBirth: p?.dateOfBirth ?? '',
    gender: (p?.gender ?? '') as PatientFormValues['gender'],
    bloodGroup: p?.bloodGroup ?? '',
    phone: p?.phone ?? '',
    email: p?.email ?? '',
    addressLine: p?.addressLine ?? '',
    city: p?.city ?? '',
    state: p?.state ?? '',
    pincode: p?.pincode ?? '',
    emergencyContactName: p?.emergencyContactName ?? '',
    emergencyContactPhone: p?.emergencyContactPhone ?? '',
    emergencyContactRelation: p?.emergencyContactRelation ?? '',
    abhaNumber: p?.abhaNumber ?? '',
    preferredLanguage: p?.preferredLanguage ?? '',
  };
}

export interface PatientFormProps {
  initial?: PatientDetail;
  submitLabel: string;
  onSubmit: (body: PatientRequest) => Promise<void>;
  onCancel?: () => void;
}

/** Registration / demographics form with client validation mirroring the server rules. */
export function PatientForm({ initial, submitLabel, onSubmit, onCancel }: PatientFormProps) {
  const [error, setError] = useState<string | null>(null);
  const [duplicate, setDuplicate] = useState<string | null>(null);
  const { control, handleSubmit, setError: setFieldError, formState } = useForm<PatientFormValues>({
    resolver: zodResolver(schema),
    defaultValues: toValues(initial),
  });

  const submit = async (values: PatientFormValues, confirmNotDuplicate = false) => {
    setError(null);
    try {
      await onSubmit({ ...(emptyToNull(values) as PatientRequest), version: initial?.version, confirmNotDuplicate });
      setDuplicate(null);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'POSSIBLE_DUPLICATE') {
        setDuplicate(e.message);
        return;
      }
      if (!applyServerErrors(e, setFieldError)) setError((e as Error).message);
    }
  };

  return (
    <form noValidate onSubmit={handleSubmit((v) => submit(v))}>
      <Stack spacing={5}>
        {error && <Alert severity="error">{error}</Alert>}
        {duplicate && (
          <Alert
            severity="warning"
            action={
              <Button color="inherit" size="small" onClick={handleSubmit((v) => submit(v, true))}>
                Register anyway
              </Button>
            }
          >
            {duplicate}
          </Alert>
        )}
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 3 }}>
            Identity
          </Typography>
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="firstName" label="First name" required autoFocus={!initial} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="lastName" label="Last name" required />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="dateOfBirth" label="Date of birth" type="date" required />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFSelect
                control={control}
                name="gender"
                label="Gender"
                required
                options={[
                  { value: 'FEMALE', label: 'Female' },
                  { value: 'MALE', label: 'Male' },
                  { value: 'OTHER', label: 'Other' },
                  { value: 'UNDISCLOSED', label: 'Prefer not to say' },
                ]}
              />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFSelect control={control} name="bloodGroup" label="Blood group" emptyLabel="Unknown" options={BLOOD_GROUPS.map((b) => ({ value: b, label: b }))} />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="abhaNumber" label="ABHA number (optional)" placeholder="12-3456-7890-1234" helperText="Stored for future ABDM linking; not verified." />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="preferredLanguage" label="Preferred language" />
            </Grid>
          </Grid>
        </Box>
        <Divider />
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 3 }}>
            Contact
          </Typography>
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="phone" label="Mobile number" type="tel" />
            </Grid>
            <Grid size={{ xs: 12, sm: 6 }}>
              <RHFTextField control={control} name="email" label="Email" type="email" />
            </Grid>
            <Grid size={12}>
              <RHFTextField control={control} name="addressLine" label="Address" />
            </Grid>
            <Grid size={{ xs: 12, sm: 5 }}>
              <RHFTextField control={control} name="city" label="City" />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="state" label="State" />
            </Grid>
            <Grid size={{ xs: 12, sm: 3 }}>
              <RHFTextField control={control} name="pincode" label="PIN code" inputMode="numeric" />
            </Grid>
          </Grid>
        </Box>
        <Divider />
        <Box>
          <Typography variant="subtitle2" sx={{ mb: 3 }}>
            Emergency contact
          </Typography>
          <Grid container spacing={4}>
            <Grid size={{ xs: 12, sm: 5 }}>
              <RHFTextField control={control} name="emergencyContactName" label="Name" />
            </Grid>
            <Grid size={{ xs: 12, sm: 4 }}>
              <RHFTextField control={control} name="emergencyContactPhone" label="Phone" type="tel" />
            </Grid>
            <Grid size={{ xs: 12, sm: 3 }}>
              <RHFTextField control={control} name="emergencyContactRelation" label="Relationship" />
            </Grid>
          </Grid>
        </Box>
        <Stack direction="row" spacing={2} justifyContent="flex-end">
          {onCancel && (
            <Button onClick={onCancel} color="inherit">
              Cancel
            </Button>
          )}
          <Button type="submit" variant="contained" disabled={formState.isSubmitting}>
            {formState.isSubmitting ? 'Saving…' : submitLabel}
          </Button>
        </Stack>
      </Stack>
    </form>
  );
}

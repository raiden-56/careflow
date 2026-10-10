'use client';

import { ReactNode } from 'react';
import { Control, Controller, FieldPath, FieldValues } from 'react-hook-form';
import { Checkbox, FormControlLabel, FormHelperText, MenuItem, TextField, TextFieldProps } from '@mui/material';

type BaseProps<T extends FieldValues> = {
  control: Control<T>;
  name: FieldPath<T>;
  label: ReactNode;
  helperText?: ReactNode;
};

/** React Hook Form + MUI text input with validation messages. */
export function RHFTextField<T extends FieldValues>({
  control,
  name,
  label,
  helperText,
  ...rest
}: BaseProps<T> & Omit<TextFieldProps, 'name' | 'label'>) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TextField
          {...rest}
          {...field}
          value={field.value ?? ''}
          label={label}
          error={!!fieldState.error}
          helperText={fieldState.error?.message ?? helperText}
          slotProps={{
            ...rest.slotProps,
            inputLabel: rest.type === 'date' || rest.type === 'datetime-local' || rest.type === 'time'
              ? { shrink: true }
              : undefined,
          }}
        />
      )}
    />
  );
}

export interface Option {
  value: string;
  label: ReactNode;
  disabled?: boolean;
}

export function RHFSelect<T extends FieldValues>({
  control,
  name,
  label,
  helperText,
  options,
  emptyLabel,
  ...rest
}: BaseProps<T> & { options: Option[]; emptyLabel?: string } & Omit<TextFieldProps, 'name' | 'label' | 'select'>) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <TextField
          {...rest}
          {...field}
          select
          value={field.value ?? ''}
          label={label}
          error={!!fieldState.error}
          helperText={fieldState.error?.message ?? helperText}
        >
          {emptyLabel !== undefined && (
            <MenuItem value="">
              <em>{emptyLabel}</em>
            </MenuItem>
          )}
          {options.map((o) => (
            <MenuItem key={o.value} value={o.value} disabled={o.disabled}>
              {o.label}
            </MenuItem>
          ))}
        </TextField>
      )}
    />
  );
}

export function RHFCheckbox<T extends FieldValues>({ control, name, label, helperText }: BaseProps<T>) {
  return (
    <Controller
      control={control}
      name={name}
      render={({ field, fieldState }) => (
        <>
          <FormControlLabel control={<Checkbox checked={!!field.value} onChange={(e) => field.onChange(e.target.checked)} />} label={label} />
          {(fieldState.error || helperText) && (
            <FormHelperText error={!!fieldState.error}>{fieldState.error?.message ?? helperText}</FormHelperText>
          )}
        </>
      )}
    />
  );
}

'use client';

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Autocomplete, Box, TextField, Typography } from '@mui/material';
import { medicationApi, MedicationView, pharmacyKeys } from '@/lib/api/pharmacy';
import { useDebouncedValue } from '@/lib/hooks';

export interface MedicationPickerProps {
  value: MedicationView | null;
  onChange: (m: MedicationView | null) => void;
  label?: string;
  activeOnly?: boolean;
  error?: string;
  required?: boolean;
  size?: 'small' | 'medium';
}

/** Searchable catalogue picker (server-side search). */
export function MedicationPicker({ value, onChange, label = 'Medication', activeOnly = true, error, required, size }: MedicationPickerProps) {
  const [input, setInput] = useState('');
  const q = useDebouncedValue(input, 250);
  const params = { q: q || undefined, activeOnly, size: 25, sort: 'genericName,asc' };
  const query = useQuery({ queryKey: pharmacyKeys.medications(params), queryFn: () => medicationApi.search(params) });
  return (
    <Autocomplete<MedicationView>
      value={value}
      onChange={(_, v) => onChange(v)}
      inputValue={input}
      onInputChange={(_, v) => setInput(v)}
      options={query.data?.content ?? []}
      loading={query.isFetching}
      filterOptions={(x) => x}
      getOptionLabel={(m) => `${m.displayName} · ${m.code}`}
      isOptionEqualToValue={(a, b) => a.id === b.id}
      size={size}
      noOptionsText={q ? 'No matching medications' : 'Type to search the catalogue'}
      renderOption={(props, m) => {
        const { key, ...rest } = props as typeof props & { key: string };
        return (
          <Box component="li" key={key} {...rest}>
            <Box sx={{ minWidth: 0 }}>
              <Typography variant="body2" fontWeight={600}>
                {m.displayName}
              </Typography>
              <Typography variant="caption" color="text.secondary">
                {m.code} · {m.stockOnHand} {m.dispenseUnit} usable
              </Typography>
            </Box>
          </Box>
        );
      }}
      renderInput={(params) => (
        <TextField {...params} label={label} required={required} error={!!error} helperText={error} placeholder="Search name or code" />
      )}
    />
  );
}

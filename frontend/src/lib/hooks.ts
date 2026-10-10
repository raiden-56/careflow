'use client';

import { useCallback, useEffect, useState } from 'react';
import { useSnackbar } from 'notistack';
import { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { ApiError, errorMessage } from './api/client';

export function useDebouncedValue<T>(value: T, delay = 300): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(t);
  }, [value, delay]);
  return debounced;
}

/** Success/error toasts with consistent wording. */
export function useToast() {
  const { enqueueSnackbar } = useSnackbar();
  return {
    success: useCallback((msg: string) => enqueueSnackbar(msg, { variant: 'success' }), [enqueueSnackbar]),
    info: useCallback((msg: string) => enqueueSnackbar(msg, { variant: 'info' }), [enqueueSnackbar]),
    warning: useCallback((msg: string) => enqueueSnackbar(msg, { variant: 'warning' }), [enqueueSnackbar]),
    error: useCallback((e: unknown) => enqueueSnackbar(errorMessage(e), { variant: 'error' }), [enqueueSnackbar]),
  };
}

/**
 * Copies backend field validation errors onto react-hook-form fields. Returns true when at least
 * one field error was applied (so the caller can skip a generic toast).
 */
export function applyServerErrors<T extends FieldValues>(e: unknown, setError: UseFormSetError<T>): boolean {
  if (!(e instanceof ApiError) || e.fieldErrors.length === 0) return false;
  e.fieldErrors.forEach((fe) => setError(fe.field as Path<T>, { type: 'server', message: fe.message }));
  return true;
}

/** Simple server-side table state (page, size, sort, search). */
export function useTableState(initial?: { size?: number; sort?: { field: string; direction: 'asc' | 'desc' } }) {
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(initial?.size ?? 20);
  const [sort, setSort] = useState(initial?.sort);
  const [search, setSearch] = useState('');
  const q = useDebouncedValue(search, 300);
  useEffect(() => setPage(0), [q, size]);
  return { page, setPage, size, setSize, sort, setSort, search, setSearch, q };
}

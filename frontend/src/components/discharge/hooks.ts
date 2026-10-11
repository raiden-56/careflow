'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { DISCHARGE_INVALIDATE } from '@/lib/api/discharge';
import { useToast } from '@/lib/hooks';

/** Mutation that toasts and refreshes every discharge-related query on success. */
export function useDischargeMutation<TVars, TResult>(
  fn: (vars: TVars) => Promise<TResult>,
  opts: { success?: string | ((r: TResult) => string); onSuccess?: (r: TResult) => void; onError?: (e: unknown) => void } = {},
) {
  const qc = useQueryClient();
  const toast = useToast();
  return useMutation({
    mutationFn: fn,
    onSuccess: (r) => {
      DISCHARGE_INVALIDATE.forEach((key) => qc.invalidateQueries({ queryKey: [...key] }));
      if (opts.success) toast.success(typeof opts.success === 'function' ? opts.success(r) : opts.success);
      opts.onSuccess?.(r);
    },
    onError: (e) => {
      if (opts.onError) opts.onError(e);
      else toast.error(e);
    },
  });
}

/** Hours as a compact age label (e.g. "20 h", "3 d"). */
export function ageLabel(hours: number) {
  if (hours < 1) return '< 1 h';
  if (hours < 48) return `${hours} h`;
  return `${Math.floor(hours / 24)} d`;
}

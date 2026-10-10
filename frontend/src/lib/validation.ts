import { z } from 'zod';

/** Mirrors the backend password policy (backend remains authoritative). */
export const passwordSchema = z
  .string()
  .min(10, 'Use at least 10 characters')
  .max(128, 'Use at most 128 characters')
  .regex(/[A-Z]/, 'Include an upper-case letter')
  .regex(/[a-z]/, 'Include a lower-case letter')
  .regex(/[0-9]/, 'Include a digit');

export const optionalText = (max: number, label = 'This field') =>
  z.string().trim().max(max, `${label} must be at most ${max} characters`).optional().or(z.literal(''));

export const phoneSchema = z
  .string()
  .trim()
  .regex(/^[0-9+\- ]{7,20}$/, 'Enter a valid phone number')
  .optional()
  .or(z.literal(''));

export const pincodeSchema = z.string().trim().regex(/^[0-9]{6}$/, 'Enter a 6-digit PIN code').optional().or(z.literal(''));

export const emailOptional = z.string().trim().email('Enter a valid email address').optional().or(z.literal(''));

/** Converts '' to null for optional API fields. */
export function emptyToNull<T extends Record<string, unknown>>(obj: T): T {
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) out[k] = v === '' ? null : v;
  return out as T;
}

/** Positive decimal string with up to 2 decimals (money input). */
export const moneyString = z
  .string()
  .trim()
  .regex(/^\d{1,12}(\.\d{1,2})?$/, 'Enter an amount like 1500 or 1500.50')
  .refine((v) => Number(v) > 0, 'Amount must be greater than zero');

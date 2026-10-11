/**
 * CareFlow design tokens. Calm, trustworthy clinical palette: warm-neutral surfaces, deep navy ink,
 * teal primary accent and a muted green secondary. Status colours are used consistently for
 * workflow states and alerts (see `src/lib/status.ts`).
 */
export const colors = {
  ink: '#10263A', // primary text (deep navy)
  inkMuted: '#4A5B6C', // secondary text (slate)
  inkSubtle: '#7A8896',
  canvas: '#F6F5F1', // app background (warm neutral)
  surface: '#FFFFFF',
  surfaceAlt: '#FAF9F6',
  border: '#E4E1DA',
  borderStrong: '#CFCBC2',

  primary: '#0F766E', // teal
  primaryDark: '#0B5A54',
  primaryLight: '#14958B',
  primarySoft: '#E5F2F0',

  secondary: '#4F7A64', // muted green
  secondarySoft: '#EAF2ED',

  success: '#1E7A46',
  successSoft: '#E6F4EC',
  warning: '#A95E08',
  warningSoft: '#FDF1E1',
  danger: '#B42318',
  dangerSoft: '#FCE9E7',
  info: '#1F5FAD',
  infoSoft: '#E7F0FB',
  neutral: '#5B6875',
  neutralSoft: '#EEF0F2',

  focus: '#2563EB',
} as const;

export const radius = {
  sm: 6,
  md: 10,
  lg: 14,
  pill: 999,
} as const;

/** 4px base spacing scale (MUI theme.spacing unit = 4). */
export const spacing = {
  xxs: 1, // 4px
  xs: 2, // 8px
  sm: 3, // 12px
  md: 4, // 16px
  lg: 6, // 24px
  xl: 8, // 32px
  xxl: 12, // 48px
} as const;

export const typeScale = {
  display: { fontSize: '1.875rem', lineHeight: 1.2, fontWeight: 650 },
  h1: { fontSize: '1.5rem', lineHeight: 1.25, fontWeight: 650 },
  h2: { fontSize: '1.25rem', lineHeight: 1.3, fontWeight: 600 },
  h3: { fontSize: '1.0625rem', lineHeight: 1.35, fontWeight: 600 },
  body: { fontSize: '0.9375rem', lineHeight: 1.55 },
  small: { fontSize: '0.8125rem', lineHeight: 1.5 },
  micro: { fontSize: '0.75rem', lineHeight: 1.4 },
} as const;

export const shadows = {
  card: '0 1px 2px rgba(16, 38, 58, 0.06), 0 1px 1px rgba(16, 38, 58, 0.04)',
  raised: '0 8px 24px rgba(16, 38, 58, 0.10)',
} as const;

export const layout = {
  sidebarWidth: 264,
  sidebarCollapsedWidth: 76,
  topBarHeight: 64,
  contentMaxWidth: 1920,
} as const;

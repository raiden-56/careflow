'use client';

import { createTheme, alpha } from '@mui/material/styles';
import { colors, radius, shadows, typeScale } from './tokens';

declare module '@mui/material/styles' {
  interface Palette {
    neutral: Palette['primary'];
  }
  interface PaletteOptions {
    neutral?: PaletteOptions['primary'];
  }
}

declare module '@mui/material/Chip' {
  interface ChipPropsColorOverrides {
    neutral: true;
  }
}

declare module '@mui/material/Button' {
  interface ButtonPropsColorOverrides {
    neutral: true;
  }
}

const fontFamily = 'var(--font-inter), "Inter", "Segoe UI", system-ui, -apple-system, sans-serif';

export const theme = createTheme({
  spacing: 4,
  shape: { borderRadius: radius.md },
  palette: {
    mode: 'light',
    primary: { main: colors.primary, dark: colors.primaryDark, light: colors.primaryLight, contrastText: '#fff' },
    secondary: { main: colors.secondary, contrastText: '#fff' },
    success: { main: colors.success, light: colors.successSoft },
    warning: { main: colors.warning, light: colors.warningSoft },
    error: { main: colors.danger, light: colors.dangerSoft },
    info: { main: colors.info, light: colors.infoSoft },
    neutral: { main: colors.neutral, light: colors.neutralSoft, contrastText: '#fff' },
    background: { default: colors.canvas, paper: colors.surface },
    text: { primary: colors.ink, secondary: colors.inkMuted, disabled: colors.inkSubtle },
    divider: colors.border,
  },
  typography: {
    fontFamily,
    fontSize: 14,
    h1: { ...typeScale.display, letterSpacing: '-0.01em' },
    h2: { ...typeScale.h1, letterSpacing: '-0.01em' },
    h3: typeScale.h2,
    h4: typeScale.h3,
    h5: { fontSize: '1rem', fontWeight: 600 },
    h6: { fontSize: '0.9375rem', fontWeight: 600 },
    subtitle1: { fontSize: '0.9375rem', fontWeight: 500 },
    subtitle2: { fontSize: '0.8125rem', fontWeight: 600, color: colors.inkMuted },
    body1: typeScale.body,
    body2: typeScale.small,
    caption: typeScale.micro,
    overline: { fontSize: '0.6875rem', fontWeight: 600, letterSpacing: '0.08em' },
    button: { textTransform: 'none', fontWeight: 600, letterSpacing: 0 },
  },
  components: {
    MuiCssBaseline: {
      styleOverrides: {
        body: { backgroundColor: colors.canvas },
        '*:focus-visible': { outline: `2px solid ${colors.focus}`, outlineOffset: 2 },
        '@media (prefers-reduced-motion: reduce)': {
          '*': { animationDuration: '0.01ms !important', transitionDuration: '0.01ms !important' },
        },
      },
    },
    MuiButtonBase: { defaultProps: { disableRipple: false } },
    MuiButton: {
      defaultProps: { disableElevation: true },
      styleOverrides: {
        root: { borderRadius: radius.md, minHeight: 38, paddingInline: 16 },
        sizeSmall: { minHeight: 32, paddingInline: 12 },
        sizeLarge: { minHeight: 46 },
      },
    },
    MuiIconButton: {
      styleOverrides: { root: { borderRadius: radius.md } },
    },
    MuiPaper: {
      defaultProps: { elevation: 0 },
      styleOverrides: {
        root: { backgroundImage: 'none' },
        outlined: { borderColor: colors.border },
      },
    },
    MuiCard: {
      defaultProps: { variant: 'outlined' },
      styleOverrides: {
        root: { borderRadius: radius.lg, borderColor: colors.border, boxShadow: shadows.card },
      },
    },
    MuiCardHeader: {
      styleOverrides: {
        root: { padding: '16px 20px 8px' },
        title: { fontSize: '1rem', fontWeight: 600 },
        subheader: { fontSize: '0.8125rem' },
      },
    },
    MuiCardContent: {
      styleOverrides: { root: { padding: '12px 20px 20px', '&:last-child': { paddingBottom: 20 } } },
    },
    MuiChip: {
      styleOverrides: {
        root: { borderRadius: radius.sm, fontWeight: 600, fontSize: '0.75rem' },
        sizeSmall: { height: 24 },
      },
    },
    MuiTextField: { defaultProps: { size: 'small', fullWidth: true } },
    MuiFormControl: { defaultProps: { size: 'small', fullWidth: true } },
    MuiSelect: { defaultProps: { size: 'small' } },
    MuiOutlinedInput: {
      styleOverrides: {
        root: {
          backgroundColor: colors.surface,
          '& .MuiOutlinedInput-notchedOutline': { borderColor: colors.borderStrong },
        },
      },
    },
    MuiInputLabel: { styleOverrides: { root: { fontSize: '0.875rem' } } },
    MuiTableCell: {
      styleOverrides: {
        root: { borderColor: colors.border, paddingTop: 10, paddingBottom: 10 },
        head: {
          fontWeight: 600,
          fontSize: '0.75rem',
          textTransform: 'uppercase',
          letterSpacing: '0.04em',
          color: colors.inkMuted,
          backgroundColor: colors.surfaceAlt,
        },
      },
    },
    MuiTableRow: {
      styleOverrides: {
        root: { '&.MuiTableRow-hover:hover': { backgroundColor: alpha(colors.primary, 0.04) } },
      },
    },
    MuiTab: {
      styleOverrides: { root: { textTransform: 'none', fontWeight: 600, minHeight: 44 } },
    },
    MuiTabs: { styleOverrides: { root: { minHeight: 44 } } },
    MuiTooltip: {
      defaultProps: { arrow: true },
      styleOverrides: { tooltip: { fontSize: '0.75rem', backgroundColor: colors.ink } },
    },
    MuiDialog: {
      styleOverrides: { paper: { borderRadius: radius.lg } },
    },
    MuiDialogTitle: { styleOverrides: { root: { fontSize: '1.0625rem', fontWeight: 600 } } },
    MuiAlert: {
      styleOverrides: { root: { borderRadius: radius.md, alignItems: 'flex-start' } },
    },
    MuiLinearProgress: { styleOverrides: { root: { borderRadius: radius.pill, height: 6 } } },
    MuiDrawer: { styleOverrides: { paper: { borderColor: colors.border } } },
    MuiListItemButton: { styleOverrides: { root: { borderRadius: radius.md } } },
    MuiSkeleton: { defaultProps: { animation: 'wave' } },
  },
});

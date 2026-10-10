import type { Config } from 'tailwindcss';

// Tailwind is used for layout utilities only; MUI owns component styling.
// Preflight is disabled so it does not fight MUI's CssBaseline.
const config: Config = {
  content: ['./src/**/*.{ts,tsx}'],
  corePlugins: { preflight: false },
  important: '#__careflow',
  theme: {
    extend: {
      colors: {
        ink: '#10263A',
        teal: { DEFAULT: '#0F766E', soft: '#E6F2F0' },
        canvas: '#F6F5F1',
      },
    },
  },
  plugins: [],
};

export default config;

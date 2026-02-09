import { createTheme, type PaletteMode } from '@mui/material/styles'

const shared = {
  primary: {
    main: '#4DAB54',
    light: '#82c087',
    dark: '#3a8a40',
    contrastText: '#fff',
  },
  secondary: {
    main: '#688ffd',
    light: '#aabdf3',
  },
  warning: {
    main: '#ffd052',
    dark: '#ac7d00',
    light: '#ffe192',
  },
  error: {
    main: '#df483d',
  },
}

export function createAppTheme(mode: PaletteMode) {
  return createTheme({
    palette: {
      mode,
      ...shared,
      ...(mode === 'light'
        ? {
            background: { default: '#f9fafb', paper: '#fff' },
            text: { primary: '#333', secondary: '#666' },
          }
        : {
            background: { default: '#121212', paper: '#1e1e1e' },
            text: { primary: '#e0e0e0', secondary: '#aaa' },
          }),
    },
    typography: {
      fontFamily: "'Roboto', sans-serif",
      fontSize: 14,
      h5: { fontWeight: 500, fontSize: '1.4rem' },
      h6: { fontWeight: 600 },
      subtitle1: { fontWeight: 500 },
    },
    shape: { borderRadius: 8 },
    components: {
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: { textTransform: 'none', fontWeight: 500 },
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: { root: { borderRadius: 8 } },
      },
      MuiChip: {
        styleOverrides: { root: { fontWeight: 500 } },
      },
      MuiListItemButton: {
        styleOverrides: {
          root: {
            borderRadius: 8,
            marginLeft: 8,
            marginRight: 8,
            '&.Mui-selected': {
              backgroundColor: 'rgba(77, 171, 84, 0.1)',
              '&:hover': {
                backgroundColor: 'rgba(77, 171, 84, 0.15)',
              },
            },
          },
        },
      },
      MuiListItemIcon: {
        styleOverrides: { root: { minWidth: 40 } },
      },
      MuiDrawer: {
        styleOverrides: {
          paper: {
            borderRight: mode === 'light' ? '1px solid #e8e8e8' : '1px solid #333',
          },
        },
      },
    },
  })
}
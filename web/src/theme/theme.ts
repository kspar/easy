import { createTheme, type PaletteMode } from '@mui/material/styles'

const GREEN = {
  50: '#f0fdf4',
  100: '#dcfce7',
  200: '#bbf7d0',
  300: '#86efac',
  400: '#4ade80',
  500: '#22c55e',
  600: '#16a34a',
  700: '#15803d',
  800: '#166534',
  900: '#14532d',
}

export function createAppTheme(mode: PaletteMode) {
  const isLight = mode === 'light'

  return createTheme({
    palette: {
      mode,
      primary: {
        main: GREEN[600],
        light: GREEN[500],
        dark: GREEN[700],
        contrastText: '#fff',
      },
      secondary: {
        main: '#5c6bc0',
        light: '#8e99a4',
        dark: '#3949ab',
      },
      success: {
        main: GREEN[600],
        light: GREEN[50],
      },
      warning: {
        main: '#f9a825',
        light: '#fff8e1',
      },
      error: {
        main: '#e53935',
        light: '#ffebee',
      },
      info: {
        main: '#1e88e5',
        light: '#e3f2fd',
      },
      background: isLight
        ? { default: '#f5f5f5', paper: '#ffffff' }
        : { default: '#121212', paper: '#1e1e1e' },
      text: isLight
        ? { primary: '#212121', secondary: '#757575' }
        : { primary: '#e0e0e0', secondary: '#9e9e9e' },
      divider: isLight ? '#e0e0e0' : '#333',
    },
    typography: {
      fontFamily: "'Roboto', 'Helvetica', 'Arial', sans-serif",
      fontSize: 14,
      h4: { fontWeight: 400, letterSpacing: '0.01em' },
      h5: { fontWeight: 400, fontSize: '1.5rem' },
      h6: { fontWeight: 500, fontSize: '1.15rem' },
      subtitle1: { fontWeight: 500, fontSize: '0.95rem' },
      subtitle2: { fontWeight: 500, fontSize: '0.875rem' },
      body2: { fontSize: '0.875rem' },
      caption: { fontSize: '0.75rem', letterSpacing: '0.02em' },
      overline: { fontSize: '0.68rem', fontWeight: 600, letterSpacing: '0.08em' },
    },
    shape: { borderRadius: 12 },
    shadows: [
      'none',
      '0 1px 3px rgba(0,0,0,0.08)',
      '0 2px 6px rgba(0,0,0,0.08)',
      '0 3px 8px rgba(0,0,0,0.1)',
      '0 4px 12px rgba(0,0,0,0.1)',
      '0 6px 16px rgba(0,0,0,0.1)',
      '0 8px 20px rgba(0,0,0,0.12)',
      '0 10px 24px rgba(0,0,0,0.12)',
      '0 12px 28px rgba(0,0,0,0.14)',
      ...Array(16).fill('0 12px 28px rgba(0,0,0,0.14)'),
    ] as unknown as typeof createTheme extends (o: infer O) => unknown
      ? O extends { shadows?: infer S }
        ? S
        : never
      : never,
    components: {
      MuiCssBaseline: {
        styleOverrides: {
          '*': {
            // Webkit (Chrome, Safari, Edge)
            '&::-webkit-scrollbar': {
              width: 6,
              height: 6,
            },
            '&::-webkit-scrollbar-track': {
              background: 'transparent',
            },
            '&::-webkit-scrollbar-thumb': {
              background: 'rgba(128, 128, 128, 0.25)',
              borderRadius: 3,
            },
            '&::-webkit-scrollbar-thumb:hover': {
              background: 'rgba(128, 128, 128, 0.45)',
            },
            // Firefox
            scrollbarWidth: 'thin',
            scrollbarColor: 'rgba(128, 128, 128, 0.25) transparent',
          },

          /**
           * A focus ring on everything reached by keyboard.
           *
           * MUI's defaults leave several components with **no visual change at all** when tabbed
           * to — measured on `IconButton`, `TableSortLabel` and outlined `Chip`, whose computed
           * background, outline and box-shadow are byte-identical focused and unfocused. Their
           * feedback is the ripple, which is a click effect: it plays once and fades, so a keyboard
           * user who tabs and then pauses has nothing on screen telling them where they are.
           *
           * `:focus-visible` rather than `:focus`, so a mouse click does not leave a ring behind —
           * that is the reason the browser distinguishes them, and why this is not the eyesore the
           * old `outline: none` habit was reacting to.
           *
           * `currentColor` so it works on both palettes without a second rule, and an offset so it
           * sits outside the control rather than on its edge.
           *
           * **The selector is doubled on purpose.** `:focus-visible` alone is specificity (0,1,0),
           * exactly the same as MUI's own `.MuiButtonBase-root { outline: 0 }` — and CssBaseline is
           * injected before component styles, so on a tie MUI wins and this rule computes to
           * `outline: none`. Measured: the element matched `:focus-visible`, the rule was in the
           * stylesheet, and the computed outline was still `none`. Repeating the pseudo-class takes
           * it to (0,2,0), which beats a single class without reaching for `!important`.
           */
          ':focus-visible:focus-visible': {
            outline: '2px solid currentColor',
            outlineOffset: 2,
          },
        },
      },
      MuiButton: {
        defaultProps: { disableElevation: true },
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 500,
            borderRadius: 8,
            padding: '8px 20px',
          },
          sizeSmall: {
            padding: '4px 12px',
            fontSize: '0.8125rem',
          },
          containedPrimary: {
            '&:hover': {
              backgroundColor: GREEN[700],
            },
          },
          outlined: {
            borderWidth: '1.5px',
            '&:hover': { borderWidth: '1.5px' },
          },
        },
      },
      MuiCard: {
        defaultProps: { variant: 'outlined' },
        styleOverrides: {
          root: {
            borderRadius: 12,
            borderColor: isLight ? '#e8e8e8' : '#333',
            transition: 'box-shadow 0.2s ease, border-color 0.2s ease',
            '&:hover': {
              borderColor: isLight ? '#d0d0d0' : '#444',
              boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
            },
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { fontWeight: 500, borderRadius: 8 },
          sizeSmall: { height: 26 },
        },
      },
      MuiListItemButton: {
        styleOverrides: {
          root: {
            borderRadius: 8,
            marginLeft: 8,
            marginRight: 8,
            marginBottom: 2,
            padding: '8px 12px',
            transition: 'background-color 0.15s ease',
            '&.Mui-selected': {
              backgroundColor: isLight
                ? `${GREEN[50]}`
                : 'rgba(76, 175, 80, 0.12)',
              '&:hover': {
                backgroundColor: isLight
                  ? `${GREEN[100]}`
                  : 'rgba(76, 175, 80, 0.18)',
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
            border: 'none',
            boxShadow: isLight
              ? '1px 0 0 #e8e8e8'
              : '1px 0 0 #2a2a2a',
            overflowX: 'hidden',
          },
        },
      },
      MuiAppBar: {
        styleOverrides: {
          root: {
            backgroundImage: 'none',
          },
        },
      },
      MuiTab: {
        styleOverrides: {
          root: {
            textTransform: 'none',
            fontWeight: 500,
            minHeight: 44,
          },
        },
      },
      MuiTabs: {
        styleOverrides: {
          indicator: {
            height: 3,
            borderRadius: '3px 3px 0 0',
          },
        },
      },
      MuiTableHead: {
        styleOverrides: {
          root: {
            '& .MuiTableCell-head': {
              fontWeight: 600,
              fontSize: '0.75rem',
              textTransform: 'uppercase',
              letterSpacing: '0.05em',
              color: isLight ? '#757575' : '#9e9e9e',
              borderBottom: `2px solid ${isLight ? '#e0e0e0' : '#333'}`,
            },
          },
        },
      },
      MuiTableCell: {
        styleOverrides: {
          root: {
            padding: '10px 16px',
            borderColor: isLight ? '#f0f0f0' : '#2a2a2a',
          },
        },
      },
      MuiPaper: {
        styleOverrides: {
          outlined: {
            borderColor: isLight ? '#e8e8e8' : '#333',
          },
        },
      },
      MuiInputLabel: {
        styleOverrides: {
          root: {
            '&.Mui-focused': {
              color: isLight ? '#212121' : '#e0e0e0',
            },
          },
        },
      },
      MuiAlert: {
        styleOverrides: {
          root: { borderRadius: 8 },
        },
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { borderRadius: 6, fontSize: '0.75rem' },
        },
      },
    },
  })
}

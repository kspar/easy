import { alpha, createTheme, type PaletteMode } from '@mui/material/styles'

/**
 * The one green (EZ-1798). Exported so call sites that genuinely need a ramp step — the courses
 * activity dots, the autograde animation — take it from here instead of hand-copying hexes that
 * strand on the next retune; pair with `alpha()` from '@mui/material/styles' for translucency.
 */
export const GREEN = {
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

/**
 * The shade rule's one value (EZ-1798): what renders instead of primary when primary would be
 * small text on a dark surface — GREEN[700] is 3.74:1 there, the one pairing one-green cannot
 * carry. Applied below to tabs, text/outlined buttons, primary links and outlined primary chips.
 */
const PRIMARY_ON_DARK = GREEN[500]

export function createAppTheme(mode: PaletteMode) {
  const isLight = mode === 'light'

  return createTheme({
    palette: {
      mode,
      // One green (EZ-1798, decided 2026-08-28): GREEN[700] is the brand colour everywhere.
      // The arithmetic behind the step down from GREEN[600] (X-012): white text on 700 is
      // 5.02:1 in both modes, and 700 as text passes AA on the light backgrounds. Small green
      // text on *dark* surfaces is the one pairing 700 cannot carry (3.74:1) — use
      // `primary.light` there. A shade rule, not a second green.
      //
      // No `secondary` and no hand-set `*.light` tints: all six were mode-blind, near-white and
      // unused (X-014) — each one a trap for whoever reaches for it next. MUI derives the
      // missing shades itself.
      primary: {
        main: GREEN[700],
        light: GREEN[500],
        dark: GREEN[800],
        contrastText: '#fff',
      },
      success: {
        main: GREEN[600],
      },
      warning: {
        main: '#f9a825',
      },
      error: {
        main: '#e53935',
      },
      info: {
        main: '#1e88e5',
      },
      background: isLight
        ? { default: '#f5f5f5', paper: '#ffffff' }
        : { default: '#121212', paper: '#1e1e1e' },
      text: isLight
        // #6b6b6b, not #757575: secondary text sits on the page background as often as on
        // paper, and #757575 is 4.23:1 there — an AA fail across 177 use sites (X-013).
        // #6b6b6b is 4.89:1 on the page background and better on paper.
        ? { primary: '#212121', secondary: '#6b6b6b' }
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
    // No bespoke shadow scale (X-014): the old 25-entry array was 16 copies of one value behind
    // a type cast, and nothing in the app renders theme elevation — cards are outlined, buttons
    // disable it. Menus and dialogs get MUI's own defaults, which is what they were designed on.
    shape: { borderRadius: 12 },
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
              backgroundColor: GREEN[800],
            },
          },
          // The shade rule — see MuiTab.
          ...(isLight
            ? {}
            : {
                textPrimary: { color: PRIMARY_ON_DARK },
                outlinedPrimary: { color: PRIMARY_ON_DARK },
              }),
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
            // No default hover (X-014): most cards are not interactive, and animating them all
            // implied an affordance that was not there. The one interactive Card (CoursesPage)
            // carries its own hover in sx.
          },
        },
      },
      MuiChip: {
        styleOverrides: {
          root: { fontWeight: 500, borderRadius: 8 },
          sizeSmall: { height: 26 },
          // The shade rule — see PRIMARY_ON_DARK. An outlined primary chip renders its label in
          // primary.main, which on dark paper is the failing small-text pairing.
          ...(isLight
            ? {}
            : {
                colorPrimary: {
                  '&.MuiChip-outlined': { color: PRIMARY_ON_DARK, borderColor: PRIMARY_ON_DARK },
                },
              }),
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
              // The ramp's own step at low alpha — not the Material green 500 that used to sit
              // here as a third green family (EZ-1798).
              backgroundColor: isLight ? `${GREEN[50]}` : alpha(GREEN[400], 0.12),
              '&:hover': {
                backgroundColor: isLight ? `${GREEN[100]}` : alpha(GREEN[400], 0.18),
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
            // The shade rule (EZ-1798): GREEN[700] as small text on dark is 3.74:1 — the one
            // pairing one-green cannot carry — so everything that renders primary as text on a
            // dark surface steps up the ramp instead. Same below for text/outlined buttons and
            // links.
            ...(isLight ? {} : { '&.Mui-selected': { color: PRIMARY_ON_DARK } }),
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
              color: isLight ? '#6b6b6b' : '#9e9e9e',
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
      MuiLink: {
        // A variant scoped to color="primary" (the default), not a bare root override: the bare
        // form would also repaint a future <Link color="error"> green, since named palette
        // colors arrive through styles this override would beat.
        defaultProps: { color: 'primary' },
        variants: isLight
          ? []
          : [{ props: { color: 'primary' }, style: { color: PRIMARY_ON_DARK } }],
      },
      MuiTooltip: {
        styleOverrides: {
          tooltip: { borderRadius: 6, fontSize: '0.75rem' },
        },
      },
    },
  })
}

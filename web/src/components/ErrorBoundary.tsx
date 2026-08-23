import { Component } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import CrashScreen from './CrashScreen.tsx'
import { record } from '../features/bug-report/breadcrumbs.ts'

/**
 * Catches a render throw and offers to report it.
 *
 * There was no error boundary in this app at all before EZ-1786. A throw during render fell through
 * to React Router's default error screen — English-only, unstyled, and silent: nobody was told, and
 * the person looking at it had no way to say what they had been doing. That is the failure this is
 * for, and the report button is most of the value. A crash the reporter can describe with one click,
 * with the last half hour of console output already attached, is a bug that can be fixed.
 *
 * ### Where it sits, and why that matters
 *
 * Inside `QueryProvider` and outside `RouterProvider`. Outside the router because a throw while the
 * router or a layout is rendering has to be caught too, and a boundary within the route tree cannot
 * see those. Inside the query provider because the report is posted through the same mutation as any
 * other, and that needs both the client and the auth token the provider installs.
 *
 * That placement is also why `BugReportDialog` takes its page URL as a prop rather than calling
 * `useLocation`: there is no router context out here to read one from.
 *
 * ### What it does not do
 *
 * Recover. There is no retry button and no state reset, because the boundary cannot know whether the
 * throw was transient — and a retry that re-throws immediately gives the reporter a flickering page
 * instead of an explanation. `CrashScreen` offers a reload, which is honest about being a blunt
 * instrument.
 */

interface Props {
  children: ReactNode
}

interface State {
  error: Error | null
}

export default class ErrorBoundary extends Component<Props, State> {
  // A field rather than a constructor: `erasableSyntaxOnly` forbids constructor parameter
  // properties, and without those there is nothing for a constructor to do here.
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Into the breadcrumb buffer as well as the console, so it is attached to whatever report
    // follows. The component stack is the part `window.onerror` cannot supply — it is what says
    // *which* piece of the page threw, which is usually the first thing worth knowing.
    const stack = info.componentStack?.trim().split('\n').slice(0, 5).join(' < ') ?? ''
    record('error', `render error: ${error.message}${stack ? ` | ${stack}` : ''}`)

    // Still logged, because a developer with the console open should see this the usual way. The
    // console patch in breadcrumbs.ts forwards to the real console, so this is not swallowed.
    console.error('Render error caught by ErrorBoundary', error)
  }

  render() {
    const { error } = this.state
    return error ? <CrashScreen error={error} /> : this.props.children
  }
}

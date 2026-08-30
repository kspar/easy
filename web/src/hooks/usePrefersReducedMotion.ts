import useMediaQuery from '@mui/material/useMediaQuery'

/**
 * Whether the viewer has asked their OS to reduce motion.
 *
 * A one-line wrapper over the `useMediaQuery` the app already uses for its breakpoints, so there is
 * one spelling of this question rather than three. It exists as a named hook because of what
 * callers have to know when they use it:
 *
 * The CSS half of the preference is handled where it can be — `JoinCard` and `RobotFace` carry
 * `@media (prefers-reduced-motion: reduce)` rules. That trick only works for animations whose
 * elements look right standing still. It does not work for a `forwards` animation that starts from
 * `opacity: 0`, or for a stroke dashed entirely out of view: turning those off leaves the element
 * stuck on its *initial* frame, which is invisible. Those sites have to branch in JS and render the
 * finished state instead, which is what this hook is for — see `AutogradeAnimation`.
 */
export default function usePrefersReducedMotion(): boolean {
  return useMediaQuery('(prefers-reduced-motion: reduce)')
}

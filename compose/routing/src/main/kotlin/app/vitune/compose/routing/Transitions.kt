package app.vitune.compose.routing

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition

// Car build (v4.2): route changes are instant — no scale/fade. The old
// scale + fade ran a full-screen animation on every push and pop, which
// cost frames on the weak radio for no benefit. List scrolling and
// flinging are untouched; this is only the screen change itself.
val defaultStacking = ContentTransform(
    initialContentExit = ExitTransition.None,
    targetContentEnter = EnterTransition.None,
    targetContentZIndex = 1f
)

val defaultUnstacking = ContentTransform(
    initialContentExit = ExitTransition.None,
    targetContentEnter = EnterTransition.None,
    targetContentZIndex = 0f
)

val defaultStill = ContentTransform(
    initialContentExit = ExitTransition.None,
    targetContentEnter = EnterTransition.None,
    targetContentZIndex = 1f
)

val TransitionScope<*>.isStacking: Boolean
    get() = initialState == null && targetState != null

val TransitionScope<*>.isUnstacking: Boolean
    get() = initialState != null && targetState == null

val TransitionScope<*>.isStill: Boolean
    get() = initialState == null && targetState == null

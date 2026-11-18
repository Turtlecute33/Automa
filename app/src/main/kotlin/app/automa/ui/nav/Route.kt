package app.automa.ui.nav

/** Screens. The back stack lives in [Navigator] (a ViewModel). */
sealed interface Route {
    data object Home : Route
    data class Terminal(val id: Int) : Route
    data class Chat(val id: Int) : Route
    data object Settings : Route
    data object Keys : Route
}

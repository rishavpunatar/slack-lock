package com.slacklock

/**
 * Pure helpers for telling the work Gmail account apart from personal ones.
 *
 * Work and personal Gmail run in the same app, so the package name alone can't
 * distinguish them. Instead we look at Gmail's account button (top-right avatar),
 * whose accessibility description names the signed-in account, e.g.
 * "Signed in as Jane Doe jane@example.org Account and settings".
 */
object WorkAccount {

    private val EMAIL = Regex("""[a-z0-9._%+'-]+@[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}""")
    private val DOMAIN = Regex("""[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,}""")

    /** View-id fragments used by Gmail's account button across recent versions. */
    private val ACCOUNT_BUTTON_IDS = listOf("selected_account_disc", "account_particle_disc")

    /**
     * Accepts a full address ("name@example.org") to lock just that account, or a
     * domain ("@example.org" / "example.org") to lock every account on it.
     * Returns the normalised form, or null if the input is neither.
     */
    fun normalise(input: String): String? {
        val s = input.trim().lowercase()
        if (EMAIL.matches(s)) return s
        val domain = s.removePrefix("@")
        return if (DOMAIN.matches(domain)) "@$domain" else null
    }

    fun matches(configured: String, email: String): Boolean {
        val e = email.trim().lowercase()
        return if (configured.startsWith("@")) e.endsWith(configured) else e == configured
    }

    /**
     * If this node is Gmail's account button, returns the signed-in address it
     * names; otherwise null. Everything else on screen (inbox rows, message
     * text) is ignored, so an email that merely mentions the work address
     * doesn't count.
     */
    fun signedInAccountFromNode(viewId: String?, description: CharSequence?): String? {
        if (description.isNullOrEmpty()) return null
        val isAccountButton = ACCOUNT_BUTTON_IDS.any { viewId?.contains(it) == true } ||
            description.contains("signed in as", ignoreCase = true)
        if (!isAccountButton) return null
        return EMAIL.find(description.toString().lowercase())?.value
    }
}

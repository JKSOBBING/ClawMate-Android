package com.clawmate.app.gallery.geocode

/**
 * Pure, deterministic mapping from platform-locality fields to a single-line
 * display place. Kept free of Android dependencies so it is unit-testable with
 * no device and never fabricates values: fields that are null/blank are simply
 * omitted.
 */
object AddressFormatter {

    /**
     * Builds a compact display address from the standard admin-area/locality
     * fields. Order mirrors common convention (admin -> locality -> thoroughfare)
     * and skips blanks, joined with no extra spaces where a hierarchy already
     * conveys separation.
     */
    fun format(
        country: String?,
        adminArea: String?,
        subAdminArea: String?,
        locality: String?,
        subLocality: String?,
        thoroughfare: String?,
        featureName: String?,
    ): String {
        val parts = partsOf(
            listOf(adminArea, subAdminArea, locality, subLocality, thoroughfare),
        )
        // A premise/feature name is the best single anchor when no thoroughfare
        // exists; otherwise it is redundant with the thoroughfare and skipped.
        val name = featureName?.trim().orEmpty()
        if (parts.isEmpty() && name.isNotEmpty()) return name
        val joined = parts.joinToString(separator = "")
        return if (name.isNotEmpty() && thoroughfare.isNullOrBlank()) {
            joined + name
        } else {
            joined
        }
    }

    /**
     * Returns the best available city-ish token for display, empty if none.
     * Prefers locality, then sub-admin, then admin area.
     */
    fun cityOf(
        adminArea: String?,
        subAdminArea: String?,
        locality: String?,
    ): String = firstNonBlank(listOf(locality, subAdminArea, adminArea))

    /** Province/admin-area token, empty if absent. */
    fun provinceOf(adminArea: String?): String = adminArea?.trim().orEmpty()

    /** District/township token, empty if absent. Prefers the 区/县 level. */
    fun districtOf(
        subAdminArea: String?,
        subLocality: String?,
    ): String = firstNonBlank(listOf(subAdminArea, subLocality))

    private fun firstNonBlank(values: List<String?>): String {
        values.forEach { v ->
            val t = v?.trim().orEmpty()
            if (t.isNotEmpty()) return t
        }
        return ""
    }

    private fun partsOf(values: List<String?>): List<String> {
        val out = mutableListOf<String>()
        values.forEach { v ->
            val t = v?.trim().orEmpty()
            if (t.isNotEmpty()) out += t
        }
        return out
    }
}

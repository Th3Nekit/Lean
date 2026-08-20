package com.th3web.lean.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.th3web.lean.data.AppearanceNorm
import com.th3web.lean.ui.screen.APP_ICON_OPTIONS

/**
 * The launcher icon is wired across three places that cannot see each other: the
 * manifest's `<activity-alias>` set, [AppIcon]'s key → alias map, and the picker's option
 * list. Nothing makes them agree, and every way they can disagree is bad in a way the
 * user cannot recover from:
 *
 *  - a picker option whose alias is missing from the manifest → tapping it disables the
 *    alias that WAS enabled and enables nothing, and the app disappears from the home
 *    screen with no obvious way back;
 *  - an alias in the manifest that nothing lists → dead weight, but also a variant a user
 *    can be stuck on with no way to see it selected;
 *  - a key in [AppIcon] the picker never offers → the same, one layer down.
 *
 * So the manifest is read as text (unit tests run with the module directory as the
 * working directory) and the three sets are compared directly.
 */
class AppIconWiringTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("cannot find the manifest from ${File(".").absolutePath}", file.isFile)
        file.readText()
    }

    private val manifestAliases: Set<String> by lazy {
        Regex("""<activity-alias\s+android:name="\.(\w+)"""")
            .findAll(manifest)
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `every icon the picker offers has an alias in the manifest`() {
        val missing = APP_ICON_OPTIONS.map { it.first }.filter { key ->
            val alias = AppIcon.aliasFor(key)?.substringAfterLast('.')
            alias == null || alias !in manifestAliases
        }
        assertEquals("picker options with no manifest alias", emptyList<String>(), missing)
    }

    @Test
    fun `the manifest declares no launcher alias nothing can select`() {
        val offered = APP_ICON_OPTIONS.map { it.first }
            .mapNotNull { AppIcon.aliasFor(it)?.substringAfterLast('.') }
            .toSet()
        assertEquals("aliases nothing offers", emptySet<String>(), manifestAliases - offered)
    }

    /**
     * The default has to be first: [AppIcon.apply] falls back to the first entry for an
     * unknown variant, which is how a restored backup naming a variant this build dropped
     * still lands somewhere sensible.
     */
    @Test
    fun `the default variant leads both lists`() {
        assertEquals(AppIcon.DEFAULT, APP_ICON_OPTIONS.first().first)
        assertEquals(AppIcon.DEFAULT, AppIcon.variants().first())
    }

    /**
     * The read path has to accept what the write path stores.
     *
     * This is the failure that hid the longest: choosing an icon wrote its key and the
     * launcher changed, so it looked like it worked — but [AppearanceNorm.appIcon] kept a
     * hand-maintained whitelist of three keys and turned everything else back into
     * "default" on the way out. The picker therefore drew its ring on the first tile
     * whatever you picked, and a restored backup silently lost the choice.
     */
    @Test
    fun `every variant survives being read back`() {
        val lost = AppIcon.variants().filter { AppearanceNorm.appIcon(it) != it }
        assertEquals("variants the normaliser resets to default", emptyList<String>(), lost)
    }

    @Test
    fun `the normaliser knows exactly the variants that exist`() {
        assertEquals(AppIcon.variants().toSet(), AppearanceNorm.APP_ICON_KEYS)
    }

    @Test
    fun `anything else still falls back to the default`() {
        assertEquals(AppIcon.DEFAULT, AppearanceNorm.appIcon(null))
        assertEquals(AppIcon.DEFAULT, AppearanceNorm.appIcon(""))
        assertEquals(AppIcon.DEFAULT, AppearanceNorm.appIcon("from-a-newer-build"))
    }

    @Test
    fun `no variant is listed twice`() {
        val keys = APP_ICON_OPTIONS.map { it.first }
        assertEquals(keys.size, keys.toSet().size)
        val labels = APP_ICON_OPTIONS.map { it.second }
        assertEquals("two variants share a name", labels.size, labels.toSet().size)
    }
}

package com.lagradost.cloudstream3.lab

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.navigation.fragment.NavHostFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Offline fixture tests only. Never calls a real provider or runs product auto-tests. */
@RunWith(AndroidJUnit4::class)
class ProviderHostSmokeTest {
    @Test fun launchesOnlyTheLabAndExtensionScreens() {
        ActivityScenario.launch(ExtensionLabActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val menu = activity.findViewById<BottomNavigationView>(R.id.nav_view)
                assertEquals(2, menu.menu.size())
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                assertEquals(R.id.lab_home, host.navController.currentDestination?.id)
                menu.selectedItemId = R.id.navigation_settings_extensions
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                assertEquals(R.id.navigation_settings_extensions, host.navController.currentDestination?.id)
                host.navController.navigate(R.id.navigation_settings_extensions_to_navigation_settings_plugins)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val host = activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                assertEquals(R.id.navigation_settings_plugins, host.navController.currentDestination?.id)
            }
        }
    }

    @Test fun providerPickerFiltersByNameAndSelectsOnlyOnTap() {
        val alpha = CleanupFixtureProvider().apply { name = "Alpha fixture" }
        val beta = CleanupFixtureProvider().apply { name = "Beta fixture" }
        ActivityScenario.launch(ExtensionLabActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                APIHolder.apis.add(alpha)
                APIHolder.apis.add(beta)
            }
            try {
                onView(withText("Select provider")).perform(click())
                onView(withHint("Search installed providers")).perform(replaceText("bEtA"))
                onView(withText("Beta fixture")).perform(click())
                scenario.onActivity { activity ->
                    fun descendants(view: View): List<View> = listOf(view) +
                        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
                        else emptyList()
                    val views = descendants(activity.findViewById(android.R.id.content))
                    assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "Beta fixture" })
                    val scroll = views.filterIsInstance<ScrollView>().first()
                    val response = descendants(scroll).filterIsInstance<TextView>().last()
                    response.text = "Offline response\n".repeat(500)
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    fun findScroll(view: View): ScrollView? {
                        if (view is ScrollView) return view
                        if (view is ViewGroup) for (i in 0 until view.childCount) {
                            findScroll(view.getChildAt(i))?.let { return it }
                        }
                        return null
                    }
                    val scroll = findScroll(activity.findViewById(android.R.id.content))!!
                    scroll.scrollTo(0, 2000)
                    val frame = scroll.parent as ViewGroup
                    val top = frame.getChildAt(1)
                    assertEquals(View.VISIBLE, top.visibility)
                    top.performClick()
                }
                // Wait for the explicit smooth-scroll animation, then verify its final offset.
                var atTop = false
                repeat(30) {
                    if (!atTop) {
                        android.os.SystemClock.sleep(50)
                        scenario.onActivity { activity ->
                            fun offset(view: View): Int? {
                                if (view is ScrollView) return view.scrollY
                                if (view is ViewGroup) for (i in 0 until view.childCount) {
                                    offset(view.getChildAt(i))?.let { return it }
                                }
                                return null
                            }
                            atTop = offset(activity.findViewById(android.R.id.content)) == 0
                        }
                    }
                }
                assertTrue("Back-to-top should return the response scroll to zero", atTop)
            } finally {
                scenario.onActivity { APIHolder.apis.remove(alpha); APIHolder.apis.remove(beta) }
            }
        }
    }

    @Test fun loadsCallsAndRemovesAnActualDexProviderExtension() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pluginFile = File.createTempFile("fixture-provider-", ".cs3", context.cacheDir)
        try {
            // A CS3 extension is a zip of dex code plus a root manifest.json. Reuse the
            // fixture class in this test APK; the plugin gets its own PathClassLoader.
            ZipFile(instrumentation.context.applicationInfo.sourceDir).use { apk ->
                ZipOutputStream(pluginFile.outputStream()).use { output ->
                    val dexEntries = apk.entries().asSequence().filter { it.name.matches(Regex("classes[0-9]*\\.dex")) }.toList()
                    assertTrue("Test fixture must contain dex bytecode", dexEntries.isNotEmpty())
                    for (entry in dexEntries) {
                        output.putNextEntry(ZipEntry(entry.name))
                        apk.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                    output.putNextEntry(ZipEntry("manifest.json"))
                    output.write("""{"name":"Cleanup fixture","version":1,"pluginClassName":"com.lagradost.cloudstream3.lab.CleanupFixturePlugin","requiresResources":false}""".toByteArray())
                    output.closeEntry()
                }
            }
            assertTrue(PluginManager.loadPlugin(context, pluginFile,
                PluginData("CleanupFixture", null, false, pluginFile.absolutePath, 1)))
            val provider = APIHolder.apis.first { it.sourcePlugin == pluginFile.absolutePath }
            assertEquals("CleanupFixtureProvider", provider.name)
            assertEquals(emptyList<SearchResponse>(), ManualProviderRunner.run(provider,
                ManualProviderRunner.Request(ManualProviderRunner.Method.SEARCH, "offline fixture")))
            assertTrue(PluginManager.getPluginsLocal().any { it.filePath == pluginFile.absolutePath })
            assertTrue(PluginManager.deletePlugin(pluginFile))
            assertFalse(APIHolder.apis.any { it.sourcePlugin == pluginFile.absolutePath })
            assertFalse(PluginManager.getPluginsLocal().any { it.filePath == pluginFile.absolutePath })
        } finally {
            PluginManager.deletePlugin(pluginFile)
        }
    }
}

/** These public classes are loaded from the copied fixture dex, not from the app. */
class CleanupFixturePlugin : Plugin() {
    override fun load(context: Context) { registerMainAPI(CleanupFixtureProvider()) }
}

class CleanupFixtureProvider : MainAPI() {
    override var name = "CleanupFixtureProvider"
    override var mainUrl = "https://example.invalid"
    override suspend fun search(query: String): List<SearchResponse> = emptyList()
}

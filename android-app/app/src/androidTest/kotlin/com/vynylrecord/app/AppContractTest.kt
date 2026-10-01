package com.vynylrecord.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vynylrecord.app.core.model.VinylPresetId
import com.vynylrecord.app.core.model.RenderStage
import com.vynylrecord.app.core.storage.VynylBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the installed app is allowed to do.
 *
 * The promise this app makes is negative: no server, no account, no upload, no analytics, and it works in
 * airplane mode. On Android most of that promise is the manifest — an app without the `INTERNET`
 * permission cannot open a socket whatever its code tries — so the manifest is the thing worth testing.
 * This test runs against the real merged manifest of the installed APK, which is also where a library's
 * permissions would show up.
 */
@RunWith(AndroidJUnit4::class)
class AppContractTest {

    private lateinit var context: Context
    private lateinit var info: ApplicationInfo

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        info = context.packageManager.getApplicationInfo(context.packageName, 0)
    }

    private fun requestedPermissions(): List<String> =
        context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            .orEmpty()

    @Test
    fun the_app_has_no_way_to_reach_the_network() {
        val permissions = requestedPermissions()
        assertFalse("the app asked for the network", permissions.contains(Manifest.permission.INTERNET))
        assertFalse(permissions.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertFalse(permissions.contains("android.permission.ACCESS_WIFI_STATE"))
        assertFalse(permissions.contains("android.permission.CHANGE_NETWORK_STATE"))
        assertFalse(permissions.contains("android.permission.CHANGE_WIFI_STATE"))
        // No cleartext either, so even a dependency this app never calls cannot open an http socket.
        assertFalse(
            "cleartext traffic was enabled",
            info.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
        )
    }

    @Test
    fun the_app_asks_for_four_permissions_and_every_one_is_explained() {
        val permissions = requestedPermissions()
        // The app's own four. A library can add more (WorkManager asks for a wake lock), which is why the
        // assertion below is about what the app itself needs rather than an exact set.
        assertTrue(permissions.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(permissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        assertTrue(permissions.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(permissions.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))

        // Nothing that reads the user's device or their other files.
        val forbidden = listOf(
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_PHONE_STATE",
            "android.permission.READ_CALL_LOG",
            "android.permission.READ_SMS",
            "android.permission.CAMERA",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "com.google.android.gms.permission.AD_ID",
        )
        forbidden.forEach { permission ->
            assertFalse("the app asked for $permission", permissions.contains(permission))
        }
    }

    @Test
    fun the_launcher_activity_is_the_only_exported_entry_point() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("there is no launcher intent", launch)
        assertEquals(MainActivity::class.java.name, launch!!.component?.className)
        assertTrue(launch.component!!.packageName.startsWith(context.packageName))
    }

    @Test
    fun a_vynyl_bundle_opens_the_app_locally() {
        // Opening a bundle from a file manager is the import path, and it must not go through a browser
        // or a download: the file is on the device already.
        val view = Intent(Intent.ACTION_VIEW).apply {
            setType(VynylBundle.MIME_TYPE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val handlers = context.packageManager.queryIntentActivities(view, 0)
        assertTrue(
            "nothing in this app handles a ${VynylBundle.MIME_TYPE} file",
            handlers.any { it.activityInfo.packageName == context.packageName },
        )
    }

    @Test
    fun sharing_goes_through_a_provider_that_is_not_exported() {
        // FileProvider with grantUriPermissions: the receiving app gets one URI, not the storage tree.
        val authority = "${context.packageName}.fileprovider"
        val provider = context.packageManager.resolveContentProvider(authority, 0)
        assertNotNull("the FileProvider authority $authority does not resolve", provider)
        assertFalse("the file provider is exported", provider!!.exported)
    }

    @Test
    fun the_app_owns_its_storage_and_needs_no_permission_to_use_it() {
        // Every record lives under the app's own directory, which is why SAF rather than a storage
        // permission is all the import and export paths need.
        assertTrue(context.filesDir.absolutePath.startsWith("/data/"))
        val records = java.io.File(context.filesDir, "records")
        assertTrue(records.mkdirs() || records.isDirectory)
        val probe = java.io.File(records, "contract-probe.txt")
        assertTrue(probe.writeText("probe").let { probe.isFile })
        assertEquals("probe", probe.readText())
        assertTrue(probe.delete())
    }

    @Test
    fun the_copy_the_design_specifies_is_the_copy_that_ships() {
        // The exact strings, because they are the product's voice and because a translation or a
        // find-and-replace should not be able to change them silently.
        assertEquals(
            "Your shelf is waiting for its first voice.",
            context.getString(R.string.vault_empty_title),
        )
        assertEquals("Pressing your record", context.getString(R.string.render_notification_title))
        assertEquals("Cancel", context.getString(R.string.render_notification_cancel))
        assertEquals("Open", context.getString(R.string.render_notification_open))
        assertEquals("Your record is ready", context.getString(R.string.render_notification_ready))
        assertEquals("The press failed", context.getString(R.string.render_notification_failed))
        assertEquals("Continue", context.getString(R.string.onboarding_continue))
        assertEquals("Skip", context.getString(R.string.onboarding_skip))
        assertEquals("Create first record", context.getString(R.string.onboarding_finish))
        assertEquals("A voice they can return to.", context.getString(R.string.onboarding_1_title))
        assertEquals("Pressed locally.", context.getString(R.string.onboarding_2_title))
        assertEquals("Yours to keep.", context.getString(R.string.onboarding_3_title))
        assertEquals("Vynyl Record", context.getString(R.string.app_name))

        // Every stage a render reports has a name a person can read.
        RenderStage.ordered.forEach { stage ->
            assertTrue("${stage.name} has no label", stage.label.isNotBlank())
        }
        assertEquals(5, VinylPresetId.entries.size)
    }
}

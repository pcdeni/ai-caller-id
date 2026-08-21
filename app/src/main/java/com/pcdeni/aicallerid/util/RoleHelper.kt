package com.pcdeni.aicallerid.util

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object RoleHelper {

    fun hasScreeningRole(context: Context): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    fun screeningRoleIntent(context: Context): Intent {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)
    }

    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun overlayPermissionIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}"),
    )

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
}

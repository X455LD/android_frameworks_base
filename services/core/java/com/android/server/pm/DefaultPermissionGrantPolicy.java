/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm;

import android.Manifest;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal.PackagesProvider;
import android.content.pm.PackageParser;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.UserHandle;
import android.provider.MediaStore;
import android.util.ArraySet;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static android.os.Process.FIRST_APPLICATION_UID;

/**
 * This class is the policy for granting runtime permissions to
 * platform components and default handlers in the system such
 * that the device is usable out-of-the-box. For example, the
 * shell UID is a part of the system and the Phone app should
 * have phone related permission by default.
 */
final class DefaultPermissionGrantPolicy {
    private static final String TAG = "DefaultPermissionGrantPolicy";
    private static final boolean DEBUG = false;

    private static final String PACKAGE_MIME_TYPE = "application/vnd.android.package-archive";

    private static final Set<String> PHONE_PERMISSIONS = new ArraySet<>();
    static {
        PHONE_PERMISSIONS.add(Manifest.permission.READ_PHONE_STATE);
        PHONE_PERMISSIONS.add(Manifest.permission.CALL_PHONE);
        PHONE_PERMISSIONS.add( Manifest.permission.READ_CALL_LOG);
        PHONE_PERMISSIONS.add(Manifest.permission.WRITE_CALL_LOG);
        PHONE_PERMISSIONS.add(Manifest.permission.ADD_VOICEMAIL);
        PHONE_PERMISSIONS.add(Manifest.permission.USE_SIP);
        PHONE_PERMISSIONS.add(Manifest.permission.PROCESS_OUTGOING_CALLS);
    }

    private static final Set<String> CONTACTS_PERMISSIONS = new ArraySet<>();
    static {
        CONTACTS_PERMISSIONS.add(Manifest.permission.READ_CONTACTS);
        CONTACTS_PERMISSIONS.add(Manifest.permission.WRITE_CONTACTS);
    }

    private static final Set<String> LOCATION_PERMISSIONS = new ArraySet<>();
    static {
        LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_FINE_LOCATION);
        LOCATION_PERMISSIONS.add(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private static final Set<String> CALENDAR_PERMISSIONS = new ArraySet<>();
    static {
        CALENDAR_PERMISSIONS.add(Manifest.permission.READ_CALENDAR);
        CALENDAR_PERMISSIONS.add(Manifest.permission.WRITE_CALENDAR);
    }

    private static final Set<String> SMS_PERMISSIONS = new ArraySet<>();
    static {
        SMS_PERMISSIONS.add(Manifest.permission.SEND_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.READ_SMS);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_WAP_PUSH);
        SMS_PERMISSIONS.add(Manifest.permission.RECEIVE_MMS);
        SMS_PERMISSIONS.add(Manifest.permission.READ_CELL_BROADCASTS);
    }

    private static final Set<String> MICROPHONE_PERMISSIONS = new ArraySet<>();
    static {
        MICROPHONE_PERMISSIONS.add(Manifest.permission.RECORD_AUDIO);
    }

    private static final Set<String> CAMERA_PERMISSIONS = new ArraySet<>();
    static {
        CAMERA_PERMISSIONS.add(Manifest.permission.CAMERA);
    }

    private static final Set<String> SENSORS_PERMISSIONS = new ArraySet<>();
    static {
        SENSORS_PERMISSIONS.add(Manifest.permission.BODY_SENSORS);
    }

    private static final Set<String> STORAGE_PERMISSIONS = new ArraySet<>();
    static {
        STORAGE_PERMISSIONS.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        STORAGE_PERMISSIONS.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    private static final Set<String> SETTINGS_PERMISSIONS = new ArraySet<>();
    static {
        SETTINGS_PERMISSIONS.add(Manifest.permission.WRITE_SETTINGS);
    }

    private static final Set<String> INSTALLER_PERMISSIONS = new ArraySet<>();
    static {
        INSTALLER_PERMISSIONS.add(Manifest.permission.GRANT_REVOKE_PERMISSIONS);
        INSTALLER_PERMISSIONS.add(Manifest.permission.INTERACT_ACROSS_USERS_FULL);
        INSTALLER_PERMISSIONS.add(Manifest.permission.CLEAR_APP_USER_DATA);
        INSTALLER_PERMISSIONS.add(Manifest.permission.KILL_UID);
    }

    private static final Set<String> VERIFIER_PERMISSIONS = new ArraySet<>();
    static {
        INSTALLER_PERMISSIONS.add(Manifest.permission.GRANT_REVOKE_PERMISSIONS);
    }

    private final PackageManagerService mService;

    private PackagesProvider mImePackagesProvider;
    private PackagesProvider mLocationPackagesProvider;
    private PackagesProvider mVoiceInteractionPackagesProvider;

    public DefaultPermissionGrantPolicy(PackageManagerService service) {
        mService = service;
    }

    public void setImePackagesProviderLPr(PackagesProvider provider) {
        mImePackagesProvider = provider;
    }

    public void setLocationPackagesProviderLPw(PackagesProvider provider) {
        mLocationPackagesProvider = provider;
    }

    public void setVoiceInteractionPackagesProviderLPw(PackagesProvider provider) {
        mVoiceInteractionPackagesProvider = provider;
    }

    public void grantDefaultPermissions(int userId) {
        grantPermissionsToSysComponentsAndPrivApps(userId);
        grantDefaultSystemHandlerPermissions(userId);
    }

    private void grantPermissionsToSysComponentsAndPrivApps(int userId) {
        Log.i(TAG, "Granting permissions to platform components");

        synchronized (mService.mPackages) {
            for (PackageParser.Package pkg : mService.mPackages.values()) {
                if (!isSysComponentOrPersistentPrivApp(pkg)
                        || !doesPackageSupportRuntimePermissions(pkg)) {
                    continue;
                }
                final int permissionCount = pkg.requestedPermissions.size();
                for (int i = 0; i < permissionCount; i++) {
                    String permission = pkg.requestedPermissions.get(i);
                    BasePermission bp = mService.mSettings.mPermissions.get(permission);
                    if (bp != null && bp.isRuntime()) {
                        final int flags = mService.getPermissionFlags(permission,
                                pkg.packageName, userId);
                        if ((flags & PackageManager.FLAG_PERMISSION_SYSTEM_FIXED) == 0) {
                            mService.grantRuntimePermission(pkg.packageName, permission, userId);
                            mService.updatePermissionFlags(permission, pkg.packageName,
                                    PackageManager.MASK_PERMISSION_FLAGS,
                                    PackageManager.FLAG_PERMISSION_SYSTEM_FIXED, userId);
                            if (DEBUG) {
                                Log.i(TAG, "Granted " + permission + " to system component "
                                        + pkg.packageName);
                            }
                        }
                    }
                }
            }
        }
    }

    private void grantDefaultSystemHandlerPermissions(int userId) {
        Log.i(TAG, "Granting permissions to default platform handlers");

        final PackagesProvider imePackagesProvider;
        final PackagesProvider locationPackagesProvider;
        final PackagesProvider voiceInteractionPackagesProvider;

        synchronized (mService.mPackages) {
            imePackagesProvider = mImePackagesProvider;
            locationPackagesProvider = mLocationPackagesProvider;
            voiceInteractionPackagesProvider = mVoiceInteractionPackagesProvider;
        }

        String[] imePackageNames = (imePackagesProvider != null)
                ? imePackagesProvider.getPackages(userId) : null;
        String[] voiceInteractPackageNames = (voiceInteractionPackagesProvider != null)
                ? voiceInteractionPackagesProvider.getPackages(userId) : null;
        String[] locationPackageNames = (locationPackagesProvider != null)
                ? locationPackagesProvider.getPackages(userId) : null;

        synchronized (mService.mPackages) {
            // Installers
            Intent installerIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            installerIntent.addCategory(Intent.CATEGORY_DEFAULT);
            installerIntent.setDataAndType(Uri.fromFile(new File("foo.apk")),
                    PACKAGE_MIME_TYPE);
            List<PackageParser.Package> installerPackages =
                    getPrivilegedHandlerActivityPackagesLPr(installerIntent, userId);
            final int installerCount = installerPackages.size();
            for (int i = 0; i < installerCount; i++) {
                PackageParser.Package installPackage = installerPackages.get(i);
                grantInstallPermissionsLPw(installPackage, INSTALLER_PERMISSIONS, userId);
            }

            // Verifiers
            Intent verifierIntent = new Intent(Intent.ACTION_PACKAGE_NEEDS_VERIFICATION);
            verifierIntent.setType(PACKAGE_MIME_TYPE);
            List<PackageParser.Package> verifierPackages =
                    getPrivilegedHandlerReceiverPackagesLPr(verifierIntent, userId);
            final int verifierCount = verifierPackages.size();
            for (int i = 0; i < verifierCount; i++) {
                PackageParser.Package verifierPackage = verifierPackages.get(i);
                grantInstallPermissionsLPw(verifierPackage, VERIFIER_PERMISSIONS, userId);
            }

            // SetupWizard
            Intent setupIntent = new Intent(Intent.ACTION_MAIN);
            setupIntent.addCategory(Intent.CATEGORY_HOME);
            PackageParser.Package setupPackage = getDefaultSystemHandlerActvityPackageLPr(
                    setupIntent, userId);
            if (setupPackage != null
                    && doesPackageSupportRuntimePermissions(setupPackage)) {
                grantRuntimePermissionsLPw(setupPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(setupPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(setupPackage, SETTINGS_PERMISSIONS, userId);
            }

            // Phone
            Intent dialerIntent = new Intent(Intent.ACTION_DIAL);
            PackageParser.Package dialerPackage = getDefaultSystemHandlerActvityPackageLPr(
                    dialerIntent, userId);
            if (dialerPackage != null
                    && doesPackageSupportRuntimePermissions(dialerPackage)) {
                grantRuntimePermissionsLPw(dialerPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(dialerPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(dialerPackage, SMS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(dialerPackage, MICROPHONE_PERMISSIONS, userId);
            }

            // Camera
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            PackageParser.Package cameraPackage = getDefaultSystemHandlerActvityPackageLPr(
                    cameraIntent, userId);
            if (cameraPackage != null
                    && doesPackageSupportRuntimePermissions(cameraPackage)) {
                grantRuntimePermissionsLPw(cameraPackage, CAMERA_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(cameraPackage, MICROPHONE_PERMISSIONS, userId);
            }

            // Messaging
            Intent messagingIntent = new Intent(Intent.ACTION_MAIN);
            messagingIntent.addCategory(Intent.CATEGORY_APP_MESSAGING);
            PackageParser.Package messagingPackage = getDefaultSystemHandlerActvityPackageLPr(
                    messagingIntent, userId);
            if (messagingPackage != null
                    && doesPackageSupportRuntimePermissions(messagingPackage)) {
                grantRuntimePermissionsLPw(messagingPackage, PHONE_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(messagingPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(messagingPackage, SMS_PERMISSIONS, userId);
            }

            // Calendar
            Intent calendarIntent = new Intent(Intent.ACTION_MAIN);
            calendarIntent.addCategory(Intent.CATEGORY_APP_CALENDAR);
            PackageParser.Package calendarPackage = getDefaultSystemHandlerActvityPackageLPr(
                    calendarIntent, userId);
            if (calendarPackage != null
                    && doesPackageSupportRuntimePermissions(calendarPackage)) {
                grantRuntimePermissionsLPw(calendarPackage, CALENDAR_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(calendarPackage, CONTACTS_PERMISSIONS, userId);
            }

            // Contacts
            Intent contactsIntent = new Intent(Intent.ACTION_MAIN);
            contactsIntent.addCategory(Intent.CATEGORY_APP_CONTACTS);
            PackageParser.Package contactsPackage = getDefaultSystemHandlerActvityPackageLPr(
                    contactsIntent, userId);
            if (contactsPackage != null
                    && doesPackageSupportRuntimePermissions(contactsPackage)) {
                grantRuntimePermissionsLPw(contactsPackage, CONTACTS_PERMISSIONS, userId);
                grantRuntimePermissionsLPw(contactsPackage, PHONE_PERMISSIONS, userId);
            }

            // Maps
            Intent mapsIntent = new Intent(Intent.ACTION_MAIN);
            mapsIntent.addCategory(Intent.CATEGORY_APP_MAPS);
            PackageParser.Package mapsPackage = getDefaultSystemHandlerActvityPackageLPr(
                    mapsIntent, userId);
            if (mapsPackage != null
                    && doesPackageSupportRuntimePermissions(mapsPackage)) {
                grantRuntimePermissionsLPw(mapsPackage, LOCATION_PERMISSIONS, userId);
            }

            // Email
            Intent emailIntent = new Intent(Intent.ACTION_MAIN);
            emailIntent.addCategory(Intent.CATEGORY_APP_EMAIL);
            PackageParser.Package emailPackage = getDefaultSystemHandlerActvityPackageLPr(
                    emailIntent, userId);
            if (emailPackage != null
                    && doesPackageSupportRuntimePermissions(emailPackage)) {
                grantRuntimePermissionsLPw(emailPackage, CONTACTS_PERMISSIONS, userId);
            }

            // Browser
            Intent browserIntent = new Intent(Intent.ACTION_MAIN);
            browserIntent.addCategory(Intent.CATEGORY_APP_BROWSER);
            PackageParser.Package browserPackage = getDefaultSystemHandlerActvityPackageLPr(
                    browserIntent, userId);
            if (browserPackage != null
                    && doesPackageSupportRuntimePermissions(browserPackage)) {
                grantRuntimePermissionsLPw(browserPackage, LOCATION_PERMISSIONS, userId);
            }

            // IME
            if (imePackageNames != null) {
                for (String imePackageName : imePackageNames) {
                    PackageParser.Package imePackage = getSystemPackageLPr(imePackageName);
                    if (imePackage != null
                            && doesPackageSupportRuntimePermissions(imePackage)) {
                        grantRuntimePermissionsLPw(imePackage, CONTACTS_PERMISSIONS, userId);
                    }
                }
            }

            // Voice interaction
            if (voiceInteractPackageNames != null) {
                for (String voiceInteractPackageName : voiceInteractPackageNames) {
                    PackageParser.Package voiceInteractPackage = getSystemPackageLPr(
                            voiceInteractPackageName);
                    if (voiceInteractPackage != null
                            && doesPackageSupportRuntimePermissions(voiceInteractPackage)) {
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                CONTACTS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                CALENDAR_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                MICROPHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                PHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                SMS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(voiceInteractPackage,
                                LOCATION_PERMISSIONS, userId);
                    }
                }
            }

            // Location
            if (locationPackageNames != null) {
                for (String packageName : locationPackageNames) {
                    PackageParser.Package locationPackage = getSystemPackageLPr(packageName);
                    if (locationPackage != null
                            && doesPackageSupportRuntimePermissions(locationPackage)) {
                        grantRuntimePermissionsLPw(locationPackage, CONTACTS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, CALENDAR_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, MICROPHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, PHONE_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, SMS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, LOCATION_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, CAMERA_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, SENSORS_PERMISSIONS, userId);
                        grantRuntimePermissionsLPw(locationPackage, STORAGE_PERMISSIONS, userId);
                    }
                }
            }
        }
    }

    private List<PackageParser.Package> getPrivilegedHandlerReceiverPackagesLPr(
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mService.queryIntentReceivers(
                intent, intent.resolveTypeIfNeeded(mService.mContext.getContentResolver()),
                0, userId);
        return getPrivilegedPackages(handlers);
    }

    private List<PackageParser.Package> getPrivilegedHandlerActivityPackagesLPr(
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mService.queryIntentActivities(
                intent, intent.resolveTypeIfNeeded(mService.mContext.getContentResolver()),
                0, userId);
        return getPrivilegedPackages(handlers);
    }

    private List<PackageParser.Package> getPrivilegedPackages(List<ResolveInfo> resolveInfos) {
        List<PackageParser.Package> handlerPackages = new ArrayList<>();
        final int handlerCount = resolveInfos.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = resolveInfos.get(i);
            PackageParser.Package handlerPackage = getPrivilegedPackageLPr(
                    handler.activityInfo.packageName);
            if (handlerPackage != null) {
                handlerPackages.add(handlerPackage);
            }
        }
        return handlerPackages;
    }

    private PackageParser.Package getDefaultSystemHandlerActvityPackageLPr(
            Intent intent, int userId) {
        List<ResolveInfo> handlers = mService.queryIntentActivities(intent, null, 0, userId);
        final int handlerCount = handlers.size();
        for (int i = 0; i < handlerCount; i++) {
            ResolveInfo handler = handlers.get(i);
            // TODO: This is a temporary hack to figure out the setup app.
            PackageParser.Package handlerPackage = getSystemPackageLPr(
                    handler.activityInfo.packageName);
            if (handlerPackage != null) {
                return handlerPackage;
            }
        }
        return null;
    }

    private PackageParser.Package getSystemPackageLPr(String packageName) {
        PackageParser.Package pkg = mService.mPackages.get(packageName);
        if (pkg != null && pkg.isSystemApp()) {
            return !isSysComponentOrPersistentPrivApp(pkg) ? pkg : null;
        }
        return null;
    }

    private PackageParser.Package getPrivilegedPackageLPr(String packageName) {
        PackageParser.Package pkg = mService.mPackages.get(packageName);
        if (pkg != null && pkg.applicationInfo.isPrivilegedApp()) {
            return !isSysComponentOrPersistentPrivApp(pkg) ? pkg : null;
        }
        return null;
    }

    private void grantRuntimePermissionsLPw(PackageParser.Package pkg, Set<String> permissions,
            int userId) {
        List<String> requestedPermissions = pkg.requestedPermissions;

        if (pkg.isUpdatedSystemApp()) {
            PackageSetting sysPs = mService.mSettings.getDisabledSystemPkgLPr(pkg.packageName);
            if (sysPs != null) {
                requestedPermissions = sysPs.pkg.requestedPermissions;
            }
        }

        final int permissionCount = requestedPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = requestedPermissions.get(i);
            if (permissions.contains(permission)) {
                final int flags = mService.getPermissionFlags(permission, pkg.packageName, userId);

                // If any flags are set to the permission, then it is either set in
                // its current state by the system or device/profile owner or the user.
                // In all these cases we do not want to clobber the current state.
                if (flags == 0) {
                    mService.grantRuntimePermission(pkg.packageName, permission, userId);
                    if (DEBUG) {
                        Log.i(TAG, "Granted " + permission + " to default handler "
                                + pkg.packageName);
                    }
                }
            }
        }
    }

    private void grantInstallPermissionsLPw(PackageParser.Package pkg, Set<String> permissions,
            int userId) {
        List<String> requestedPermissions = pkg.requestedPermissions;

        if (pkg.isUpdatedSystemApp()) {
            PackageSetting sysPs = mService.mSettings.getDisabledSystemPkgLPr(pkg.packageName);
            if (sysPs != null) {
                requestedPermissions = sysPs.pkg.requestedPermissions;
            }
        }

        final int permissionCount = requestedPermissions.size();
        for (int i = 0; i < permissionCount; i++) {
            String permission = requestedPermissions.get(i);
            if (permissions.contains(permission)) {
                final int flags = mService.getPermissionFlags(permission, pkg.packageName, userId);

                // If any flags are set to the permission, then it is either set in
                // its current state by the system or device/profile owner or the user.
                // In all these cases we do not want to clobber the current state.
                if (flags == 0) {
                    mService.grantInstallPermissionLPw(permission, pkg);
                    if (DEBUG) {
                        Log.i(TAG, "Granted install " + permission + " to " + pkg.packageName);
                    }
                }
            }
        }
    }

    private static boolean isSysComponentOrPersistentPrivApp(PackageParser.Package pkg) {
        return UserHandle.getAppId(pkg.applicationInfo.uid) < FIRST_APPLICATION_UID
                || ((pkg.applicationInfo.privateFlags
                & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0
                && (pkg.applicationInfo.flags & ApplicationInfo.FLAG_PERSISTENT) != 0);
    }

    private static boolean doesPackageSupportRuntimePermissions(PackageParser.Package pkg) {
        return pkg.applicationInfo.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1;
    }
}

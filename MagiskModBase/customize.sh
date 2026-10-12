PKGNAME="com.tsinbei.pixelxpert"
APKNAME="PixelXpert.apk"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"

prepareSQL(){
	unzip $ZIPFILE sqlite3 -d $TMPDIR/ > /dev/null
	chmod +x $TMPDIR/sqlite3

	SQLITEPATH="$TMPDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

#grant silent root access to given UID
grantRootUID(){
	if [ -z "$1" ]; then
		ui_print "- Error: No UID provided for root grant"
		return 1
	fi
	DBPATH=$MAGISKDBPATH

	#new record - older magisk compatibility
	CMD="insert into policies (uid, package_name, policy, until, logging, notification) values ($1, '$2', 2, 0, 1, 0);" && runSQL
	#new record
	CMD="insert into policies (uid, policy, until, logging, notification) values ($1, 2, 0, 1, 0);" && runSQL
	#previously present record
	CMD="update policies set policy = 2, until = 0, logging = 1, notification = 0 where uid = $1;" && runSQL
}


#grant root access to given package name
grantRootPkg(){
	ui_print "- \tGranting root access to $1..."
	UID=$(pm list packages -U $1 --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

#obsolete since the app is installed as a user app: KernelSU's "Umount modules by
#default" can no longer hide the app or bootloop the device, since the module
#doesn't mount anything into /system anymore
#testKernelSU()
#{
#	if [[ $(ksud -V 2>&1 | grep "not found" | wc -c) -eq 0 ]]; then #KSU installed
#		if [[ $(pm list packages | grep $PKGNAME | wc -c) -eq 0 ]]; then #PixelXpert NOT installed yet
#			ui_print ''
#			ui_print '*******************************'
#			ui_print 'KernelSU binaries found!'
#			ui_print ''
#			ui_print '                CAUTION!:'
#			ui_print 'Before installation, you MUST disable'
#			ui_print '"Umount modules by default"'
#			ui_print 'Otherwise, your device will fall into BOOTLOOP!'
#			ui_print ''
#			ui_print 'Do you wish to continue?'
#			ui_print 'Volume Up: Continue'
#			ui_print 'Volume Down: Abort'
#			if [[ "$(getevent -l | grep -m 1 KEY_VOLUME)" == *"VOLUMEDOWN"* ]]; then
#				abort 'Installation cancelled'
#			fi
#		fi
#	fi
#}

assertPixelRom()
{
	PixelTipsPattern="TipsPrebuilt*"
	PixelTipsParent="/product/priv-app"

	if ! find "$PixelTipsParent" -maxdepth 1 -name "$PixelTipsPattern" -print -quit | grep -q .; then
		ui_print 'Device does not seem to be a Pixel'
		ui_print 'phone, containing an original ROM.'

		abort 'Installation aborted due to incompatibility'
	fi
}

assertSupportedAndroid()
{
	if [ "$(getprop ro.build.version.sdk)" -lt 36 ]; then
		ui_print 'This build is not compatible with'
		ui_print 'your Android version.'

		abort 'Installation aborted due to incompatibility'
	fi
}


assertPixelRom

assertSupportedAndroid

prepareSQL

ui_print ''
ui_print ''

#only grant here if the app is already installed; fresh installs are granted after pm install below
if command -v magisk > /dev/null 2>&1 && pm list packages | grep -q "package:$PKGNAME"; then
	grantRootApps
fi

set_perm $MODPATH/service.sh 0 0 0755

ui_print ''
ui_print ''

#install the app as a regular user app instead of mounting it as a priv-app.
#user apps live in /data/app and survive OTAs and temporary root loss.
ui_print '- Installing Pixel Xpert app...'
APK_PATH="$MODPATH/$APKNAME"
if [ -f "$APK_PATH" ]; then
	pm install -r -d -g "$APK_PATH" > /dev/null 2>&1
	if [ $? -eq 0 ]; then
		ui_print '- App installed successfully!'
		#the UID may be new (fresh install): make sure root is granted now
		if command -v magisk > /dev/null 2>&1; then
			grantRootApps
		fi
	else
		ui_print '- Failed to install app! Will retry on boot.'
		touch "$MODPATH/install_needed"
	fi
else
	ui_print '- APK not found in zip!'
fi

#migration from the old priv-app mount model: once the system folder stops being
#mounted, Android wipes the system app and its data on the next boot. Back the
#device-protected preferences up now; service.sh reinstalls and restores them.
if pm list packages -s | grep -q "package:$PKGNAME"; then
	if [ -d "/data/user_de/0/$PKGNAME/shared_prefs" ]; then
		ui_print '- System app detected. Backing up preferences for migration...'
		mkdir -p "$MODPATH/px_backup_de"
		cp -af "/data/user_de/0/$PKGNAME/shared_prefs" "$MODPATH/px_backup_de/"
	fi
	#Android wipes the app on boot because the system base disappears: reinstall then
	touch "$MODPATH/install_needed"
	ui_print '- Migrating from system app: after the first reboot,'
	ui_print '  please reboot once more to activate all features.'
fi

#make sure no system folder remains, so nothing mounts as priv-app
rm -rf "$MODPATH/system"

if [ -f "$LSPDDBPATH" ]; then
	ui_print ''
	ui_print ''

	ui_print ''
	ui_print ''
	ui_print 'Installation Complete!'
	ui_print 'Please Reboot your device to activate'
else
	ui_print 'Lsposed not found!!'
	ui_print 'This module will not work without Lsposed'
	ui_print 'Please:'
	ui_print '- Install Lsposed'
	ui_print '- Reboot'
#	ui_print '- Manually enable PixelXpert in Lsposed'
#	ui_print '- Reboot'
fi

ui_print ''
ui_print '  **********************'
ui_print '  * Brought to you by: *'
ui_print '  *                    *'
ui_print '  * PixelXpert team    *'
ui_print '  **********************'
ui_print ''

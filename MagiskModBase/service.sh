PKGNAME="com.tsinbei.pixelxpert"
APKNAME="PixelXpert.apk"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"
MODDIR=${0%/*}

prepareSQL(){
	chmod +x $MODDIR/sqlite3
	SQLITEPATH="$MODDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

#grant silent root access to given UID
grantRootUID(){
	if [ -z "$1" ]; then
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
	echo "- \tGranting root access to $1..."
	UID=$(pm list packages -U $1 --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

prepareSQL

if command -v magisk > /dev/null 2>&1; then
	grantRootApps
fi

# Wait for boot to finish before touching the package manager
until [ "$(getprop sys.boot_completed)" = "1" ]; do
	sleep 1
done

if [ -f "$MODDIR/install_needed" ]; then
	APP_EXISTS=$(pm list packages | grep "package:$PKGNAME")

	pm install -r -d -g "$MODDIR/$APKNAME" > /dev/null 2>&1
	rm -f "$MODDIR/install_needed"

	# Only restore backup if the app was actually wiped by Android (migration from system app)
	if [ -z "$APP_EXISTS" ] && [ -d "$MODDIR/px_backup_de/shared_prefs" ]; then
		mkdir -p "/data/user_de/0/$PKGNAME"
		cp -af "$MODDIR/px_backup_de/shared_prefs" "/data/user_de/0/$PKGNAME/"

		APP_UID=$(pm list packages -U $PKGNAME --user 0 | grep ":$PKGNAME " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)
		if [ -n "$APP_UID" ]; then
			chown -R $APP_UID:$APP_UID "/data/user_de/0/$PKGNAME"
			restorecon -R "/data/user_de/0/$PKGNAME"
		fi
	fi

	# Cleanup backup regardless
	rm -rf "$MODDIR/px_backup_de"

	# The app was (re)installed after zygote started, so running processes have no
	# module hooks yet: restart SystemUI now. Framework hooks need one more reboot.
	if command -v magisk > /dev/null 2>&1; then
		grantRootApps
	fi
	killall com.android.systemui
fi

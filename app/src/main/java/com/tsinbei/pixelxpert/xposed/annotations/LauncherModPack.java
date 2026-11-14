package com.tsinbei.pixelxpert.xposed.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.tsinbei.pixelxpert.annotations.BaseModPack;
import com.tsinbei.pixelxpert.Constants;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@BaseModPack(targetPackage = Constants.LAUNCHER_PACKAGE)
public @interface LauncherModPack { }
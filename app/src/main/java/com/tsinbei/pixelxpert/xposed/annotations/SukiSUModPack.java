package com.tsinbei.pixelxpert.xposed.annotations;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import com.tsinbei.pixelxpert.Constants;
import com.tsinbei.pixelxpert.annotations.BaseModPack;

@Retention(RUNTIME)
@Target(TYPE)
@BaseModPack(targetPackage = Constants.SUKISU_PACKAGE)
public @interface SukiSUModPack { }

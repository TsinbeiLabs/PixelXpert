package com.tsinbei.pixelxpert.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.ANNOTATION_TYPE)
public @interface BaseModPack {
	/**
	 *
	 * the package name to match against. must match exactly. case sensitive
	 * or empty for all packages
	 */
	String targetPackage();
}
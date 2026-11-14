package com.tsinbei.pixelxpert.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * If not defined, default priority of 99 will be assumed. modpacks will be loaded in order of priority from small to large
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ModPackPriority {
	int priority();
}
package com.tsinbei.pixelxpert.utils;

import com.tsinbei.pixelxpert.PixelXpert;

public class DisplayUtils {
	public static boolean isTablet() {
		return PixelXpert.get().getResources().getConfiguration().smallestScreenWidthDp >= 600;
	}
}
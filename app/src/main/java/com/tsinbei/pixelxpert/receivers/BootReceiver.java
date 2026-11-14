package com.tsinbei.pixelxpert.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.tsinbei.pixelxpert.BuildConfig;
import com.tsinbei.pixelxpert.utils.TimeSyncScheduler;
import com.tsinbei.pixelxpert.utils.UpdateScheduler;

public class BootReceiver extends BroadcastReceiver {
	@Override
	public void onReceive(Context context, Intent intent) {
		if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))
		{
			if(BuildConfig.DEBUG)
				Log.d("BootReceiver", "Broadcast received: " + intent.getAction());

			UpdateScheduler.scheduleUpdates(context);
			TimeSyncScheduler.scheduleTimeSync(context);
		}
	}
}
package org.smssecure.smssecure.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.smssecure.smssecure.util.XmppUtil;

public class XmppServiceBoot extends BroadcastReceiver {
  private static final String TAG = XmppServiceBoot.class.getSimpleName();

  private static final String BOOT_COMPLETED_EVENT = "android.intent.action.BOOT_COMPLETED";

  private Context context;

  @Override
  public void onReceive(final Context context, Intent intent) {
    Log.w(TAG, "onReceive()");
    this.context = context;

    if (intent.getAction().equals(BOOT_COMPLETED_EVENT)) {
      XmppUtil.startService(context);
    } else {
      throw new AssertionError("Unknown event passed to XmppServiceBoot");
    }
  }
}

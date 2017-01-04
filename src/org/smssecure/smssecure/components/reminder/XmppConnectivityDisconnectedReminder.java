package org.smssecure.smssecure.components.reminder;

import android.content.Context;

import org.smssecure.smssecure.R;
import org.smssecure.smssecure.service.XmppService;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.XmppUtil;

public class XmppConnectivityDisconnectedReminder extends Reminder {

  public XmppConnectivityDisconnectedReminder(final Context context) {
    super(context.getString(R.string.XmppService_cannot_reach_xmpp_server),
          context.getString(R.string.XmppService_xmpp_features_in_silence_are_disabled),
          null);
  }

  public static boolean isEligible(Context context) {
    XmppService xmppService = XmppService.getInstance();
    return SilencePreferences.isXmppRegistered(context) &&
          !XmppUtil.isXmppAvailable(context)            &&
          xmppService != null                           &&
          !xmppService.getConnectionStatus().equals(XmppService.CONNECTED);
  }

  public boolean isDismissable() {
    return false;
  }
}

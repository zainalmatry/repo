package org.smssecure.smssecure.mms;

import android.content.Context;

import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.recipients.Recipients;

import java.util.List;

public class OutgoingXmppMediaMessage extends OutgoingMediaMessage {

  public OutgoingXmppMediaMessage(Recipients recipients, String body,
                                    List<Attachment> attachments,
                                    long sentTimeMillis,
                                    int distributionType)
  {
    super(recipients, body, attachments, sentTimeMillis, -1, distributionType);
  }

  public OutgoingXmppMediaMessage(OutgoingMediaMessage base) {
    super(base);
  }

  @Override
  public boolean isSecure() {
    return true;
  }

  @Override
  public boolean isXmpp() {
    return true;
  }
}

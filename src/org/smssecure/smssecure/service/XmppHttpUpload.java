package org.smssecure.smssecure.service;

// http://stackoverflow.com/questions/35007157/how-to-handle-xmpp-xep-0363-http-file-upload-feature-at-client-side-using-smack

import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.StanzaListener;
import org.jivesoftware.smack.filter.StanzaFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.packet.XMPPError;
import org.jivesoftware.smack.provider.IntrospectionProvider;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;

public class XmppHttpUpload implements StanzaListener{
  private XMPPTCPConnection connection;
  private String id;
  private SlotGrantedListener listener;

  static {
    ProviderManager.addIQProvider("slot", "urn:xmpp:http:upload", new SlotIQProvider());
  }

  public static class SlotIQProvider extends IntrospectionProvider.IQIntrospectionProvider {
    public SlotIQProvider() {
      super(SlotIQ.class);
    }
  }

  public static class SlotIQ extends IQ {
    private String put;
    private String get;

    public SlotIQ() {
      super("request", "urn:xmpp:http:upload");
    }

    public String getPut() {
      return put;
    }

    public void setPut(String put) {
      this.put = put;
    }

    public String getGet() {
      return get;
    }

    public void setGet(String get) {
      this.get = get;
    }

    @Override
    protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
      return null;
    }
  }

  public interface SlotGrantedListener {
    void slotGranted(String put, String get);
    void slotDenied(String error);
  }

  public XmppHttpUpload(XMPPTCPConnection connection) {
    this.connection = connection;
  }

  public void requestSlot(String to, final long messageId, final long fileSize, final String contentType, SlotGrantedListener listener)
    throws SmackException.NotConnectedException
  {
    this.listener = listener;
    final IQ iq = new IQ("request", "urn:xmpp:http:upload") {
      @Override
      protected IQChildElementXmlStringBuilder getIQChildElementBuilder(IQChildElementXmlStringBuilder xml) {
        xml.rightAngleBracket();
        xml.element("filename", String.valueOf(messageId));
        xml.element("size", String.valueOf(fileSize));
        xml.element("content-type", contentType);
        return xml;
      }
    };
    iq.setType(IQ.Type.get);
    iq.setFrom(connection.getUser());
    iq.setTo(to);

    id = iq.getStanzaId();

    connection.addAsyncStanzaListener(this, new StanzaFilter() {
      @Override
      public boolean accept(Stanza stanza) {
        return id != null && id.equals(stanza.getStanzaId());
      }
    });

    connection.sendStanza(iq);
  }

  @Override
  public void processPacket(Stanza packet) throws SmackException.NotConnectedException {
    connection.removeAsyncStanzaListener(this);
    if (((IQ)packet).getType() != IQ.Type.error) {
      SlotIQ slotIQ = (SlotIQ) packet;
      listener.slotGranted(slotIQ.getPut(), slotIQ.getGet());
    } else {
      XMPPError error = packet.getError();
      String errDesc = error.getDescriptiveText(null);
      String errText = errDesc != null ? String.format("%s (%s)", errDesc, error.toString()) : error.toString();
      listener.slotDenied(errText);
    }
  }

}

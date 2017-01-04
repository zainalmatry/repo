package org.smssecure.smssecure.service;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import org.smssecure.smssecure.ApplicationContext;
import org.smssecure.smssecure.attachments.Attachment;
import org.smssecure.smssecure.BuildConfig;
import org.smssecure.smssecure.crypto.MasterSecret;
import org.smssecure.smssecure.crypto.SmsCipher;
import org.smssecure.smssecure.crypto.storage.SilenceAxolotlStore;
import org.smssecure.smssecure.database.DatabaseFactory;
import org.smssecure.smssecure.database.EncryptingSmsDatabase;
import org.smssecure.smssecure.database.MmsDatabase;
import org.smssecure.smssecure.jobs.MmsSendJob;
import org.smssecure.smssecure.jobs.SmsReceiveJob;
import org.smssecure.smssecure.jobs.SmsSentJob;
import org.smssecure.smssecure.R;
import org.smssecure.smssecure.recipients.Recipient;
import org.smssecure.smssecure.recipients.Recipients;
import org.smssecure.smssecure.recipients.RecipientFactory;
import org.smssecure.smssecure.service.SmsDeliveryListener;
import org.smssecure.smssecure.service.XmppHttpUpload;
import org.smssecure.smssecure.service.XmppHttpUpload.SlotGrantedListener;
import org.smssecure.smssecure.sms.MultipartSmsMessageHandler;
import org.smssecure.smssecure.sms.OutgoingEncryptedMessage;
import org.smssecure.smssecure.sms.OutgoingTextMessage;
import org.smssecure.smssecure.transport.UndeliverableMessageException;
import org.smssecure.smssecure.util.Base64;
import org.smssecure.smssecure.util.ServiceUtil;
import org.smssecure.smssecure.util.SilencePreferences;
import org.smssecure.smssecure.util.XmppUtil;
import org.smssecure.smssecure.util.XmppUtil.XmppServer;

import org.jivesoftware.smack.AbstractXMPPConnection;
import org.jivesoftware.smack.chat.Chat;
import org.jivesoftware.smack.chat.ChatManager;
import org.jivesoftware.smack.chat.ChatManagerListener;
import org.jivesoftware.smack.chat.ChatMessageListener;
import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.ExtensionElement;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.Stanza;
import org.jivesoftware.smack.PacketListener;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.roster.Roster;
import org.jivesoftware.smack.roster.RosterEntry;
import org.jivesoftware.smack.roster.RosterListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smackx.disco.packet.DiscoverInfo;
import org.jivesoftware.smackx.disco.ServiceDiscoveryManager;
import org.jivesoftware.smackx.iqregister.AccountManager;
import org.jivesoftware.smackx.ping.android.ServerPingWithAlarmManager;
import org.jivesoftware.smackx.receipts.DeliveryReceipt;
import org.jivesoftware.smackx.receipts.DeliveryReceiptManager;
import org.jivesoftware.smackx.receipts.DeliveryReceiptRequest;
import org.jivesoftware.smackx.receipts.ReceiptReceivedListener;
import org.jivesoftware.smackx.xdata.FormField;
import org.jivesoftware.smackx.xdata.packet.DataForm;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.lang.Runnable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.List;

public class XmppService extends Service {
  private static final String TAG = XmppService.class.getSimpleName();

  private static XmppService instance;
  private HttpUploadService httpUploadService = null;

  private Looper                     looper;
  private ConfigureAndConnectHandler configureAndConnectHandler;

  public  static final int    SERVICE_RUNNING_ID      = 4142;
  public  static final String XMPP_CONNECTIVITY_EVENT = "org.smssecure.smssecure.XMPP_CONNECTIVITY_UPDATE";
  private static final String USER_AGENT              = "Silence/" + BuildConfig.VERSION_NAME;

  public static Integer DISCONNECTED = 0;
  public static Integer CONNECTED    = 1;
  public static Integer CONNECTING   = 2;
  private Integer connectionStatus = DISCONNECTED;
  private boolean hasBeenConnected = false;

  private static int RECONNECTION_DELAY = 10; // 10s
  private static long IDLE = 1000000;
  private static long XMPP_SEND_MEDIA_TIMEOUT = 120; // 2min

  private XMPPTCPConnection   connection;
  private XmppServer          registeredXmppServer;
  private Roster              roster;
  private ReconnectionManager reconnectionManager;

  private final class ConfigureAndConnectHandler extends Handler {
    public ConfigureAndConnectHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(android.os.Message message) {
      configureAndConnect();
    }
  }

  @Override
  public void onCreate() {
    Log.w(TAG, "onCreate()");
    instance = this;

    ServerPingWithAlarmManager.onCreate(getApplicationContext());
    if (!SilencePreferences.isXmppRegistered(this)) {
      Log.w(TAG, "Xmpp is disabled. Exiting...");
      stopSelf();
    } else {
      HandlerThread thread = new HandlerThread("XmppConfigureAndConnectHandlerThread", Process.THREAD_PRIORITY_BACKGROUND);
      thread.start();
      configureAndConnectHandler = new ConfigureAndConnectHandler(thread.getLooper());
    }

  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    Log.w(TAG, "onStartCommand(): received start id " + startId + ": " + intent);
    if (SilencePreferences.isXmppRegistered(this)){
      configureAndConnectHandler.sendMessage(configureAndConnectHandler.obtainMessage());
    }

    return START_STICKY;
  }

  private void configureAndConnect() {
    XmppService instance = this;
    instance.registeredXmppServer = XmppUtil.getRegisteredServer((Context) instance);

    XMPPTCPConnectionConfiguration.Builder configBuilder = XmppUtil.getConfigBuilder(instance);
    configBuilder.setServiceName(instance.registeredXmppServer.getHostname())
                 .setPort(registeredXmppServer.getPort())
                 .setUsernameAndPassword(instance.registeredXmppServer.getUsername(), instance.registeredXmppServer.getPassword())
                 .setSendPresence(true)
                 .setDebuggerEnabled(false);
    instance.connection = new XMPPTCPConnection(configBuilder.build());

    instance.connection.setPacketReplyTimeout(XmppUtil.XMPP_STANZA_TIMEOUT);

    instance.connection.setUseStreamManagement(true);
    instance.connection.setUseStreamManagementDefault(true);
    instance.connection.setUseStreamManagementResumption(true);
    instance.connection.setUseStreamManagementResumptionDefault(true);
    instance.connection.addConnectionListener(new XmppServiceConnectionListener(instance));

    DeliveryReceiptManager deliveryReceiptManager = DeliveryReceiptManager.getInstanceFor(instance.connection);
    deliveryReceiptManager.autoAddDeliveryReceiptRequests();
    deliveryReceiptManager.addReceiptReceivedListener(new XmppServiceDeliveryReceiptListener(instance));
    instance.connection.addPacketListener(new XmppServiceDeliveryReceiptDemandListener(instance), new PacketTypeFilter(Message.class));

    reconnectionManager = ReconnectionManager.getInstanceFor(instance.connection);
    reconnectionManager.enableAutomaticReconnection();
    reconnectionManager.setEnabledPerDefault(true);
    reconnectionManager.setFixedDelay(RECONNECTION_DELAY);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      AlarmManager alarmManager = ServiceUtil.getAlarmManager(this);
      Intent intent = new Intent(this, IdleReceiver.class);
      alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + IDLE, PendingIntent.getBroadcast(this, 0, intent, 0));
    }

    connect();
  }

  private void connect() {
    Log.w(TAG, "Connecting...");
    try {
      instance.updateXmppStatus(CONNECTING);
      connection.connect();
      Log.w(TAG, "Connected!");
      connection.login();
      Log.w(TAG, "Authenticated!");

      ServerPingWithAlarmManager.getInstanceFor(connection).setEnabled(true);

      Roster.setDefaultSubscriptionMode(Roster.SubscriptionMode.accept_all);
      roster = Roster.getInstanceFor(instance.connection);
      roster.reload();
      roster.addRosterListener(new XmppRosterListener(instance));
      roster.setSubscriptionMode(Roster.SubscriptionMode.accept_all);

      ServiceDiscoveryManager serviceDiscoveryManager = ServiceDiscoveryManager.getInstanceFor(instance.connection);

      List<String> services = serviceDiscoveryManager.findServices("urn:xmpp:http:upload", true, true);

      if (!services.isEmpty()) {
        String httpUploadUrl = services.get(0);
        if (!httpUploadUrl.isEmpty()) {
          Log.w(TAG, "Found upload service at: " + httpUploadUrl);
          DataForm data = DataForm.from(serviceDiscoveryManager.discoverInfo(httpUploadUrl));
          for (FormField field : data.getFields()) {
            if (field.getVariable().equals("max-file-size")){
              long maxFileSize = Long.parseLong(field.getValues().get(0));
              httpUploadService = new HttpUploadService(httpUploadUrl, maxFileSize);
              Log.w(TAG, "max-file-size: " + maxFileSize);
            }
          }
        }
      }

      Log.w(TAG, "isSmAvailable(): " + connection.isSmAvailable());
      Log.w(TAG, "isSmEnabled(): " + connection.isSmEnabled());
    } catch (Exception e) {
      Log.w(TAG, e);
      if (!isConnected()) {
        instance.updateXmppStatus(XmppService.DISCONNECTED);
        instance.displayFailureNotification();
        try {
          Thread.sleep(RECONNECTION_DELAY*1000);
        } catch (Exception ex) {
          Thread.currentThread().interrupt();
        }
        connect();
      }
    }
  }

  public void reconnect() {
    if (connection != null && !connection.isAuthenticated() && getConnectionStatus().equals(DISCONNECTED)) {
      new AsyncTask<Void, Void, Void>() {
        @Override
        protected Void doInBackground(Void... params) {
          connect();
          return null;
        }
      }.execute();
    }
  }

  @Override
  public void onDestroy() {
    Log.w(TAG, "onDestroy()");
    if (connection != null) connection.disconnect();
    ServerPingWithAlarmManager.onDestroy();
    instance = null;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  public static @Nullable XmppService getInstance() {
    Log.w(TAG, "getInstance()");
    return instance;
  }

  public Integer getConnectionStatus() {
    return connectionStatus;
  }

  public boolean isConnected() {
    return getConnectionStatus().equals(CONNECTED);
  }

  public void setHasBeenConnected() {
    instance.hasBeenConnected = true;
  }

  public void setHasNotBeenConnected() {
    instance.hasBeenConnected = false;
  }

  public boolean hasBeenConnected() {
    Log.w(TAG, "hasBeenConnected(): " + instance.hasBeenConnected);
    return instance.hasBeenConnected;
  }

  private void displayFailureNotification() {
    if (!SilencePreferences.isXmppRegistered((Context) instance)) return;
    if (!hasBeenConnected()) return;

    NotificationCompat.Builder builder = new NotificationCompat.Builder(this);

    Intent       targetIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
    Notification notification = new NotificationCompat.Builder(this)
                                    .setSmallIcon(R.drawable.ic_warning_dark)
                                    .setContentTitle(getString(R.string.XmppService_xmpp_connection_failed))
                                    .setContentText(getString(R.string.XmppService_xmpp_features_in_silence_are_disabled))
                                    .setAutoCancel(true)
                                    .setContentIntent(PendingIntent.getActivity(this, 0,
                                                                                targetIntent,
                                                                                PendingIntent.FLAG_UPDATE_CURRENT))
                                    .setPriority(Notification.PRIORITY_DEFAULT)
                                    .build();

    ServiceUtil.getNotificationManager(this).notify(SERVICE_RUNNING_ID, notification);
  }

  private boolean isXmppRecipient(Recipient recipient) {
    return recipient.getXmppJid() != null;
  }

  public String send(String body, Recipient recipient)
    throws UndeliverableMessageException
  {
    if (!isXmppRecipient(recipient)) throw new UndeliverableMessageException("JID for " + recipient.getNumber() + " is null");

    ChatManager chatmanager = ChatManager.getInstanceFor(connection);
    Chat        chat        = chatmanager.createChat(recipient.getXmppJid(), null);

    try {
      Message message = new Message(recipient.getXmppJid(), body);
      String deliveryReceiptId = null;
      if (SilencePreferences.isSmsDeliveryReportsEnabled(this)) {
        deliveryReceiptId = DeliveryReceiptRequest.addTo(message);
      }
    	chat.sendMessage(message);
      return deliveryReceiptId;
    }
    catch (Exception e) {
    	throw new UndeliverableMessageException(e);
    }
  }

  public void sendMedia(final MasterSecret masterSecret, String message, Attachment attachement, final long messageId, final Recipients recipients, final byte[] pduBytes)
    throws UndeliverableMessageException
  {
    if (!isXmppRecipient(recipients.getPrimaryRecipient())) throw new UndeliverableMessageException("JID for " + recipients.getPrimaryRecipient().getNumber() + " is null");
    if (httpUploadService == null) throw new UndeliverableMessageException("No upload service available");

    final MediaType contentType = MediaType.parse(attachement.getContentType());

    try {
      if (contentType == null) throw new UndeliverableMessageException("Invalid Content-Type!");
      Log.w(TAG, "length: " + pduBytes.length);
      new XmppHttpUpload(connection).requestSlot(httpUploadService.getHostname(), messageId, pduBytes.length, attachement.getContentType(), new SlotGrantedListener() {
        @Override
        public void slotGranted(String put, String get){
          sendFileAndNotify(masterSecret, messageId, pduBytes, contentType, recipients, put, get);
        };

        @Override
        public void slotDenied(String error) {
          Log.w(TAG, "Slot denied! " + error);
          markFileAsErrored(messageId);
        };
      });
    } catch(Exception e) {
      throw new UndeliverableMessageException(e);
    }
  }

  private void sendFileAndNotify(MasterSecret masterSecret, long messageId, byte[] pduBytes, MediaType contentType, Recipients recipients, String put, String get) {
    Log.w(TAG, "sendFileAndNotify()");
    Log.w(TAG, "Sending message ID " + messageId + " to " + put);

    try {
      MmsDatabase           mmsDatabase = DatabaseFactory.getMmsDatabase(instance);
      EncryptingSmsDatabase smsDatabase = DatabaseFactory.getEncryptingSmsDatabase(instance);

      OkHttpClient client = new OkHttpClient().newBuilder().connectTimeout(XMPP_SEND_MEDIA_TIMEOUT, TimeUnit.SECONDS)
      .writeTimeout(XMPP_SEND_MEDIA_TIMEOUT, TimeUnit.SECONDS)
      .readTimeout(XMPP_SEND_MEDIA_TIMEOUT, TimeUnit.SECONDS)
      .build();

      RequestBody body     = RequestBody.create(contentType, pduBytes);
      Request     request  = new Request.Builder().header("User-Agent", USER_AGENT).url(put).put(body).build();
      Response    response = client.newCall(request).execute();

      if (response.code() == 200 || response.code() == 201) {
        OutgoingTextMessage message = new SmsCipher(new SilenceAxolotlStore(instance, masterSecret)).encrypt(new OutgoingEncryptedMessage(recipients, get, -1));

        MultipartSmsMessageHandler multipartMessageHandler = new MultipartSmsMessageHandler();
        ArrayList<String>          messages                = multipartMessageHandler.divideMessage(message, true);

        StringBuilder stringBuilder = new StringBuilder();
        for (String string : messages) {
          stringBuilder.append(string.trim());
        }

        String deliveryReceiptId = send(stringBuilder.toString(), recipients.getPrimaryRecipient());
        Log.w(TAG, "deliveryReceiptId: " + deliveryReceiptId);

        smsDatabase.updateXmppId(messageId, deliveryReceiptId);
        mmsDatabase.markAsSent(messageId);
      } else {
        Log.w(TAG, "Received " + response.code() + " code...");
        markFileAsErrored(messageId);
      }

    } catch (Exception e) {
      Log.w(TAG, e);
      markFileAsErrored(messageId);
    }


  }

  private void markFileAsErrored(long messageId) {
    Log.w(TAG, "markFileAsErrored()");
    MmsDatabase database = DatabaseFactory.getMmsDatabase(instance);
    database.markAsSentFailed(messageId);
    MmsSendJob.notifyMediaMessageDeliveryFailed(instance, messageId);
  }

  private void updateXmppStatus(Integer status) {
    Integer oldStatus = getConnectionStatus();
    connectionStatus = status;
    if (hasBeenConnected()) broadcastXmppConnectivityEvent(this);
  }

  private static void broadcastXmppConnectivityEvent(Context context) {
    Intent intent = new Intent(XMPP_CONNECTIVITY_EVENT);
    intent.setPackage(context.getPackageName());
    context.sendBroadcast(intent);
  }

  public void subscribe(String jid) {
    Presence subscribe = new Presence(Presence.Type.subscribe);
    subscribe.setTo(jid);
    try {
      connection.sendPacket(subscribe);
    } catch (Exception e) {
      Log.w(TAG, "Impossible NotConnectedException?");
    }
  }

  public void deleteAccount() {

    new AsyncTask<Void, Void, Void>() {
      @Override
      protected Void doInBackground(Void... params) {
        if (connection != null) {
          reconnectionManager.disableAutomaticReconnection();
          AccountManager accountManager = AccountManager.getInstance(connection);
          try {
            accountManager.deleteAccount();
          } catch (Exception e) {}
          for (Recipient xmppRecipient : RecipientFactory.getXmppRecipients(instance, false)){
            xmppRecipient.setXmppJid(null);
          }
          SilencePreferences.disableXmpp((Context) instance);
          SilencePreferences.setXmppUsername((Context) instance, "");
          SilencePreferences.setXmppPassword((Context) instance, "");
          SilencePreferences.setXmppHostname((Context) instance, "");
          SilencePreferences.setXmppPort((Context) instance, 0);
          XmppUtil.stopService((Context) instance);
        } else {
          Log.w(TAG, "connection is null, cannot delete account");
        }
        return null;
      }
    }.execute();
  }

  public Presence getRecipientPresence(String jid) {
    if (roster == null) {
      Log.w(TAG, "Returning null Presence for " + jid);
      return null;
    }
    return roster.getPresence(jid);
  }

  private class XmppServiceConnectionListener implements ConnectionListener {
    private Context     context;
    private XmppService instance;

    public XmppServiceConnectionListener(XmppService instance) {
      this.context  = (Context) instance;
      this.instance = instance;
    }

    @Override
    public void authenticated(XMPPConnection connection, boolean resumed) {
      instance.setHasBeenConnected();
      instance.updateXmppStatus(XmppService.CONNECTED);
      ServiceUtil.getNotificationManager(context).cancel(XmppService.SERVICE_RUNNING_ID);
    }

    @Override
    public void connected(XMPPConnection connection) {
      ChatManager chatManager = ChatManager.getInstanceFor(connection);
      if (chatManager.getChatListeners().size() < 1) {
        chatManager.addChatListener(new ChatManagerListener() {
          @Override
          public void chatCreated(Chat chat, boolean createdLocally) {
            if (!createdLocally) chat.addMessageListener(new XmppServiceChatMessageListener(context));
          }
        });
      }
    }

    @Override
    public void connectionClosed() {
      Log.w(TAG, "connectionClosed()");

      if (!SilencePreferences.isXmppRegistered(getApplicationContext())) {
        Log.w(TAG, "Xmpp is disabled.");
      } else {
        instance.updateXmppStatus(XmppService.DISCONNECTED);
        instance.displayFailureNotification();
        instance.setHasNotBeenConnected();
        connect();
      }
    }

    @Override
    public void connectionClosedOnError(Exception e) {
      Log.w(TAG, "connectionClosedOnError()");
      Log.w(TAG, e);

      if (!SilencePreferences.isXmppRegistered(getApplicationContext())) {
        Log.w(TAG, "Xmpp is disabled.");
      } else {
        instance.updateXmppStatus(XmppService.DISCONNECTED);
        instance.displayFailureNotification();
        instance.setHasNotBeenConnected();
        connect();
      }
    }

    @Override
    public void reconnectingIn(int seconds) {
      Log.w(TAG, "reconnectingIn(" + seconds + ")");
    }

    @Override
    public void reconnectionFailed(Exception e) {
      Log.w(TAG, "reconnectionFailed()");
      instance.updateXmppStatus(XmppService.DISCONNECTED);
    }

    @Override
    public void reconnectionSuccessful() {
      Log.w(TAG, "reconnectionSuccessful()");
      instance.updateXmppStatus(XmppService.CONNECTED);
      ServiceUtil.getNotificationManager(context).cancel(XmppService.SERVICE_RUNNING_ID);
    }
  }

  private class XmppServiceChatMessageListener implements ChatMessageListener {
    private final Context context;

    public XmppServiceChatMessageListener (Context context) {
      Log.w(TAG, "Starting XMPP messages receiver...");
      this.context = context;
    }

    @Override
    public void processMessage(Chat chat, Message message){
      String senderJid = chat.getParticipant().replaceAll("\\/.*$","");

      Log.w(TAG, "Message recieved from " + senderJid);
      ArrayList<String> xmpp = new ArrayList<String>();
                        xmpp.add(senderJid);

      Recipient sender = RecipientFactory.getRecipientsFromXmpp(context, xmpp, false).getPrimaryRecipient();
      if (sender != null) {
        for (Message.Body body : message.getBodies()) {
          if (body.getMessage() != null) ApplicationContext.getInstance(context).getJobManager().add(new SmsReceiveJob(context, body.getMessage().trim(), sender.getNumber(), -1));
        }
      }
    }
  }

  private class XmppServiceDeliveryReceiptDemandListener implements PacketListener {
    XmppService instance;

    public XmppServiceDeliveryReceiptDemandListener(XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void processPacket(Stanza packet) {
      if (packet instanceof Message && packet.getExtension("request", DeliveryReceipt.NAMESPACE) != null) {
        try {
          Message response = new Message(packet.getFrom().replaceAll("\\/.*$",""), Message.Type.normal);
          response.addExtension(new DeliveryReceipt(packet.getPacketID()));
          instance.connection.sendPacket(response);
        } catch (Exception e) {
          Log.w(TAG, e);
        }
      }
    }
  }

  private class XmppServiceDeliveryReceiptListener implements ReceiptReceivedListener {
    XmppService instance;

    public XmppServiceDeliveryReceiptListener (XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void onReceiptReceived(String fromJid, String toJid, String deliveryReceiptId, Stanza stanza) {
      Log.d(TAG, "onReceiptReceived: from: " + fromJid + " to: " + toJid + " deliveryReceiptId: " + deliveryReceiptId + " stanza: " + stanza);
      EncryptingSmsDatabase database = DatabaseFactory.getEncryptingSmsDatabase(instance);
      long messageId = database.getMessageIdFromXmpp(deliveryReceiptId);
      if (messageId > -1) {
        ApplicationContext.getInstance(instance).getJobManager()
                          .add(new SmsSentJob(instance, messageId, SmsDeliveryListener.DELIVERED_SMS_ACTION, Activity.RESULT_OK));

      } else {
        Log.w(TAG, "Received delivery receipt for an unknown message");
      }
    }
  }

  private class XmppRosterListener implements RosterListener {
    private XmppService instance;

    public XmppRosterListener (XmppService instance) {
      this.instance = instance;
    }

    @Override
    public void entriesAdded(Collection<String> addresses) {}

    @Override
    public void entriesDeleted(Collection<String> addresses) {}

    @Override
    public void entriesUpdated(Collection<String> addresses) {}

    @Override
    public void presenceChanged(Presence presence) {
      instance.broadcastXmppConnectivityEvent((Context) instance);
    }
  }

  public class IdleReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {

    }
  }

  private class HttpUploadService {
    private String hostname;
    private long   maxFileSize;

    public HttpUploadService(String hostname, long maxFileSize) {
      this.hostname    = hostname;
      this.maxFileSize = maxFileSize;
    }

    public String getHostname() {
      return hostname;
    }

    public long getMaxFileSize() {
      return maxFileSize;
    }
  }
}

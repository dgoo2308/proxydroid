/* proxydroid - Global / Individual Proxy App for Android
 * Copyright (C) 2011 Max Lv <max.c.lv@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 *                            ___====-_  _-====___
 *                      _--^^^#####//      \\#####^^^--_
 *                   _-^##########// (    ) \\##########^-_
 *                  -############//  |\^^/|  \\############-
 *                _/############//   (@::@)   \\############\_
 *               /#############((     \\//     ))#############\
 *              -###############\\    (oo)    //###############-
 *             -#################\\  / VV \  //#################-
 *            -###################\\/      \//###################-
 *           _#/|##########/\######(   /\   )######/\##########|\#_
 *           |/ |#/\#/\#/\/  \#/\##\  |  |  /##/\#/  \/\#/\#/\#| \|
 *           `  |/  V  V  `   V  \#\| |  | |/#/  V   '  V  V  \|  '
 *              `   `  `      `   / | |  | | \   '      '  '   '
 *                               (  | |  | |  )
 *                              __\ | |  | | /__
 *                             (vvv(VVV)(VVV)vvv)
 *
 *                              HERE BE DRAGONS
 *
 */

package org.proxydroid;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

import com.flurry.android.FlurryAgent;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.RemoteViews;

public class ProxyDroidService extends Service {

	private Notification notification;
	private NotificationManager notificationManager;
	private Intent intent;
	private PendingIntent pendIntent;

	public static final String BASE = "/data/data/org.proxydroid/";
	private static final int MSG_CONNECT_START = 0;
	private static final int MSG_CONNECT_FINISH = 1;
	private static final int MSG_CONNECT_SUCCESS = 2;
	private static final int MSG_CONNECT_FAIL = 3;

	final static String CMD_IPTABLES_REDIRECT_ADD_HTTP = BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 80 -j REDIRECT --to 8123\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j REDIRECT --to 8124\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j REDIRECT --to 8124\n";

	final static String CMD_IPTABLES_DNAT_ADD_HTTP = BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 80 -j DNAT --to-destination 127.0.0.1:8123\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 443 -j DNAT --to-destination 127.0.0.1:8124\n"
			+ BASE
			+ "iptables -t nat -A OUTPUT -p tcp --dport 5228 -j DNAT --to-destination 127.0.0.1:8124\n";

	final static String CMD_IPTABLES_REDIRECT_ADD_SOCKS = BASE
			+ "iptables -t nat -A OUTPUT -p tcp -j REDIRECT --to 8123\n";

	final static String CMD_IPTABLES_DNAT_ADD_SOCKS = BASE
			+ "iptables -t nat -A OUTPUT -p tcp -j DNAT --to-destination 127.0.0.1:8123\n";

	private static final String TAG = "ProxyDroidService";

	private String host;
	private int port;
	private String intranetAddr = "";
	private String user;
	private String password;
	private String domain;
	private String proxyType = "http";
	private String auth = "false";
	private boolean isAuth = false;
	private boolean isNTLM = false;
	private boolean isDNSProxy = false;

	private DNSProxy dnsServer = null;
	private int dnsPort = 0;

	Process NTLMProcess;

	private SharedPreferences settings = null;

	private boolean hasRedirectSupport = true;
	private boolean isAutoSetProxy = false;
	private String localIp;

	private ProxyedApp apps[];

	private static final Class<?>[] mStartForegroundSignature = new Class[] {
			int.class, Notification.class };
	private static final Class<?>[] mStopForegroundSignature = new Class[] { boolean.class };

	private Method mStartForeground;
	private Method mStopForeground;

	private Object[] mStartForegroundArgs = new Object[2];
	private Object[] mStopForegroundArgs = new Object[1];

	void invokeMethod(Method method, Object[] args) {
		try {
			method.invoke(this, mStartForegroundArgs);
		} catch (InvocationTargetException e) {
			// Should not happen.
			Log.w("ApiDemos", "Unable to invoke method", e);
		} catch (IllegalAccessException e) {
			// Should not happen.
			Log.w("ApiDemos", "Unable to invoke method", e);
		}
	}

	private void initHasRedirectSupported() {
		Process process = null;
		DataOutputStream os = null;
		DataInputStream es = null;

		String command;
		String line = null;

		command = "/data/data/org.proxydroid/iptables -t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to-ports 8153";

		try {
			process = Runtime.getRuntime().exec("su");
			es = new DataInputStream(process.getErrorStream());
			os = new DataOutputStream(process.getOutputStream());
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			process.waitFor();

			while (null != (line = es.readLine())) {
				Log.d(TAG, line);
				if (line.contains("No chain/target/match")) {
					this.hasRedirectSupport = false;
					break;
				}
			}
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				if (es != null)
					es.close();
				process.destroy();
			} catch (Exception e) {
				// nothing
			}
		}

		// flush the check command
		runRootCommand(command.replace("-A", "-D"));
	}

	/**
	 * This is a wrapper around the new startForeground method, using the older
	 * APIs if it is not available.
	 */
	void startForegroundCompat(int id, Notification notification) {
		// If we have the new startForeground API, then use it.
		if (mStartForeground != null) {
			mStartForegroundArgs[0] = Integer.valueOf(id);
			mStartForegroundArgs[1] = notification;
			invokeMethod(mStartForeground, mStartForegroundArgs);
			return;
		}

		// Fall back on the old API.
		setForeground(true);
		notificationManager.notify(id, notification);
	}

	/**
	 * This is a wrapper around the new stopForeground method, using the older
	 * APIs if it is not available.
	 */
	void stopForegroundCompat(int id) {
		// If we have the new stopForeground API, then use it.
		if (mStopForeground != null) {
			mStopForegroundArgs[0] = Boolean.TRUE;
			try {
				mStopForeground.invoke(this, mStopForegroundArgs);
			} catch (InvocationTargetException e) {
				// Should not happen.
				Log.w("ApiDemos", "Unable to invoke stopForeground", e);
			} catch (IllegalAccessException e) {
				// Should not happen.
				Log.w("ApiDemos", "Unable to invoke stopForeground", e);
			}
			return;
		}

		// Fall back on the old API. Note to cancel BEFORE changing the
		// foreground state, since we could be killed at that point.
		notificationManager.cancel(id);
		setForeground(false);
	}

	public static boolean runRootCommand(String command) {
		Process process = null;
		DataOutputStream os = null;
		Log.d(TAG, command);
		try {
			process = Runtime.getRuntime().exec("su");
			os = new DataOutputStream(process.getOutputStream());
			os.writeBytes(command + "\n");
			os.writeBytes("exit\n");
			os.flush();
			process.waitFor();
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
			return false;
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				process.destroy();
			} catch (Exception e) {
				// nothing
			}
		}
		return true;
	}

	public boolean runNTLMProxy(String command) {
		NTLMProcess = null;
		DataOutputStream os = null;
		Log.d(TAG, command);
		try {
			NTLMProcess = Runtime.getRuntime().exec(command);
			os = new DataOutputStream(NTLMProcess.getOutputStream());
			os.flush();
			NTLMProcess.waitFor();
		} catch (Exception e) {
			Log.e(TAG, e.getMessage());
			return false;
		} finally {
			try {
				if (os != null) {
					os.close();
				}
				NTLMProcess.destroy();
			} catch (Exception e) {
				// nothing
			}
		}
		return true;
	}

	/**
	 * Internal method to request actual PTY terminal once we've finished
	 * authentication. If called before authenticated, it will just fail.
	 */
	private void enableProxy() {

		try {
			Log.e(TAG, "Forward Successful");

			if (isAuth && isNTLM) {
				runRootCommand(BASE
						+ "tproxy -P /data/data/org.proxydroid/tproxy.pid -s 8125 127.0.0.1 8025");
				runRootCommand(BASE
						+ "proxy.sh start http 127.0.0.1 8025 false");
				new Thread() {
					@Override
					public void run() {
						runNTLMProxy(BASE
								+ "cntlm -P "
								+ BASE
								+ "cntlm.pid -l 8025 -u "
								+ user
								+ (!domain.equals("") ? "@" + domain : "@local")
								+ " -p " + password + " " + host + ":" + port);
					}
				}.start();
			} else {
				runRootCommand(BASE + "proxy.sh start" + " " + proxyType + " "
						+ host + " " + port + " " + auth + " \"" + user
						+ "\" \"" + password + "\"");
			}

			StringBuffer cmd = new StringBuffer();

			if (isDNSProxy) {
				dnsServer = new DNSProxy("DNS Server", dnsPort);
				dnsServer.setBasePath("/data/data/org.proxydroid/");
				dnsPort = dnsServer.init();

				Thread dnsThread = new Thread(dnsServer);
				dnsThread.setDaemon(true);
				dnsThread.start();

				if (hasRedirectSupport)
					cmd.append(BASE
							+ "iptables "
							+ "-t nat -A OUTPUT -p udp --dport 53 -j REDIRECT --to "
							+ dnsPort + "\n");
				else
					cmd.append(BASE
							+ "iptables "
							+ "-t nat -A OUTPUT -p udp --dport 53 -j DNAT --to-destination 127.0.0.1:"
							+ dnsPort + "\n");

			}

			String redirectCmd = CMD_IPTABLES_REDIRECT_ADD_HTTP;
			String dnatCmd = CMD_IPTABLES_DNAT_ADD_HTTP;

			if (!proxyType.equals("http")) {
				redirectCmd = CMD_IPTABLES_REDIRECT_ADD_SOCKS;
				dnatCmd = CMD_IPTABLES_DNAT_ADD_SOCKS;
			}

			if (isAutoSetProxy) {
				cmd.append(hasRedirectSupport ? redirectCmd : dnatCmd);
			} else {
				// for host specified apps
				if (apps == null || apps.length <= 0)
					apps = AppManager.getProxyedApps(this);

				for (int i = 0; i < apps.length; i++) {
					if (apps[i].isProxyed()) {
						cmd.append((hasRedirectSupport ? redirectCmd : dnatCmd)
								.replace("-t nat",
										"-t nat -m owner --uid-owner "
												+ apps[i].getUid()));
					}
				}
			}

			String rules = cmd.toString();

			if (intranetAddr == null || intranetAddr.equals("")) {
				// Reference:
				// http://en.wikipedia.org/wiki/Private_network#Private_IPv4_address_spaces
				if (localIp != null) {
					String[] prefix = localIp.split("\\.");
					if (prefix.length == 4) {
						String intranet = localIp;
						if (localIp.startsWith("192.168."))
							intranet = "192.168.0.0/16";
						else if (localIp.startsWith("10."))
							intranet = "10.0.0.0/8";
						else if (localIp.startsWith("172.")) {
							int prefix2 = Integer.valueOf(prefix[1]);
							if (prefix2 <= 31 && prefix2 >= 16) {
								intranet = "172.16.0.0/12";
							}
						}
						rules = rules.replace("-p tcp", "-p tcp " + "! -d "
								+ intranet);
					}
				}
			} else {
				rules = rules.replace("-p tcp", "-p tcp " + "! -d "
						+ intranetAddr);
			}

			if (proxyType.equals("http") && isNTLM)
				runRootCommand(rules.replace("8123", "8125"));
			else if (proxyType.equals("http"))
				runRootCommand(rules);
			else
				runRootCommand(rules.replace("-p tcp", "-p tcp" + " ! --dport "
						+ port));

		} catch (Exception e) {
			Log.e(TAG, "Error setting up port forward during connect", e);
		}

	}

	/** Called when the activity is first created. */
	public boolean handleCommand() {

		enableProxy();

		return true;
	}

	private void initSoundVibrateLights(Notification notification) {
		final String ringtone = settings.getString(
				"settings_key_notif_ringtone", null);
		AudioManager audioManager = (AudioManager) this
				.getSystemService(Context.AUDIO_SERVICE);
		if (audioManager.getStreamVolume(AudioManager.STREAM_RING) == 0) {
			notification.sound = null;
		} else if (ringtone != null)
			notification.sound = Uri.parse(ringtone);
		else
			notification.defaults |= Notification.DEFAULT_SOUND;

		if (settings.getBoolean("settings_key_notif_vibrate", false)) {
			long[] vibrate = { 0, 1000, 500, 1000, 500, 1000 };
			notification.vibrate = vibrate;
		}

		notification.defaults |= Notification.DEFAULT_LIGHTS;
	}

	private void notifyAlert(String title, String info) {
		notification.icon = R.drawable.stat;
		notification.tickerText = title;
		notification.flags = Notification.FLAG_ONGOING_EVENT;
		initSoundVibrateLights(notification);
		// notification.defaults = Notification.DEFAULT_SOUND;
		notification.setLatestEventInfo(this, getString(R.string.app_name)
				+ " | " + getProfileName(), info, pendIntent);
		startForegroundCompat(1, notification);
	}

	private void notifyAlert(String title, String info, int flags) {
		notification.icon = R.drawable.stat;
		notification.tickerText = title;
		notification.flags = flags;
		initSoundVibrateLights(notification);
		notification.setLatestEventInfo(this, getString(R.string.app_name)
				+ " | " + getProfileName(), info, pendIntent);
		notificationManager.notify(0, notification);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		settings = PreferenceManager.getDefaultSharedPreferences(this);
		notificationManager = (NotificationManager) this
				.getSystemService(NOTIFICATION_SERVICE);

		intent = new Intent(this, ProxyDroid.class);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
		pendIntent = PendingIntent.getActivity(this, 0, intent, 0);
		notification = new Notification();

		try {
			mStartForeground = getClass().getMethod("startForeground",
					mStartForegroundSignature);
			mStopForeground = getClass().getMethod("stopForeground",
					mStopForegroundSignature);
		} catch (NoSuchMethodException e) {
			// Running on an older platform.
			mStartForeground = mStopForeground = null;
		}
	}

	/** Called when the activity is closed. */
	@Override
	public void onDestroy() {

		stopForegroundCompat(1);
		
		FlurryAgent.onEndSession(this);

		notifyAlert(getString(R.string.forward_stop),
				getString(R.string.service_stopped),
				Notification.FLAG_AUTO_CANCEL);

		// Make sure the connection is closed, important here
		onDisconnect();

		try {
			if (dnsServer != null) {
				dnsServer.close();
				dnsServer = null;
			}
		} catch (Exception e) {
			Log.e(TAG, "DNS Server close unexpected");
		}

		// for widget, maybe exception here
		try {
			RemoteViews views = new RemoteViews(getPackageName(),
					R.layout.proxydroid_appwidget);
			views.setImageViewResource(R.id.serviceToggle, R.drawable.off);
			AppWidgetManager awm = AppWidgetManager.getInstance(this);
			awm.updateAppWidget(awm.getAppWidgetIds(new ComponentName(this,
					ProxyDroidWidgetProvider.class)), views);
		} catch (Exception ignore) {
			// Nothing
		}

		Editor ed = settings.edit();
		ed.putBoolean("isRunning", false);
		ed.commit();

		try {
			notificationManager.cancel(0);
		} catch (Exception ignore) {
			// Nothing
		}

		super.onDestroy();
	}

	private void onDisconnect() {

		runRootCommand(BASE + "iptables -t nat -F OUTPUT");

		if (isNTLM) {
			runRootCommand("kill -9 `cat /data/data/org.proxydroid/tproxy.pid`");
			runRootCommand("kill -9 `cat /data/data/org.proxydroid/cntlm.pid`");
		}

		runRootCommand(BASE + "proxy.sh stop");

	}

	final Handler handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			Editor ed = settings.edit();
			switch (msg.what) {
			case MSG_CONNECT_START:
				ed.putBoolean("isConnecting", true);
				break;
			case MSG_CONNECT_FINISH:
				ed.putBoolean("isConnecting", false);
				break;
			case MSG_CONNECT_SUCCESS:
				ed.putBoolean("isRunning", true);
				break;
			case MSG_CONNECT_FAIL:
				ed.putBoolean("isRunning", false);
				break;
			}
			ed.commit();
			super.handleMessage(msg);
		}
	};

	// Local Ip address
	public String getLocalIpAddress() {
		try {
			for (Enumeration<NetworkInterface> en = NetworkInterface
					.getNetworkInterfaces(); en.hasMoreElements();) {
				NetworkInterface intf = en.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = intf
						.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress inetAddress = enumIpAddr.nextElement();
					if (!inetAddress.isLoopbackAddress()) {
						return inetAddress.getHostAddress().toString();
					}
				}
			}
		} catch (SocketException ex) {
			Log.e(TAG, ex.toString());
		}
		return null;
	}

	private boolean getAddress() {

		String tmp = host;

		try {
			host = InetAddress.getByName(host).getHostAddress();
		} catch (UnknownHostException e) {
			host = tmp;
			return false;
		}

		return true;
	}

	private String getProfileName() {
		SharedPreferences settings = PreferenceManager
				.getDefaultSharedPreferences(this);
		return settings.getString(
				"profile" + settings.getString("profile", "1"),
				getString(R.string.profile_base) + " "
						+ settings.getString("profile", "1"));
	}

	// This is the old onStart method that will be called on the pre-2.0
	// platform. On 2.0 or later we override onStartCommand() so this
	// method will not be called.
	@Override
	public void onStart(Intent intent, int startId) {

		super.onStart(intent, startId);
		
		FlurryAgent.onStartSession(this, "AV372I7R5YYD52NWPUPE");

		Log.d(TAG, "Service Start");

		Bundle bundle = intent.getExtras();
		host = bundle.getString("host");
		intranetAddr = bundle.getString("intranetAddr");
		proxyType = bundle.getString("proxyType");
		port = bundle.getInt("port");
		isAutoSetProxy = bundle.getBoolean("isAutoSetProxy");
		isAuth = bundle.getBoolean("isAuth");
		isNTLM = bundle.getBoolean("isNTLM");
		isDNSProxy = bundle.getBoolean("isDNSProxy");

		if (isAuth) {
			auth = "true";
			user = bundle.getString("user");
			password = bundle.getString("password");
		} else {
			auth = "false";
			user = "";
			password = "";
		}

		if (isNTLM)
			domain = bundle.getString("domain");
		else
			domain = "";

		localIp = getLocalIpAddress();

		Log.e(TAG, "GAE Proxy: " + host);
		Log.e(TAG, "Local Port: " + port);

		new Thread(new Runnable() {
			public void run() {

				handler.sendEmptyMessage(MSG_CONNECT_START);

				initHasRedirectSupported();

				if (getAddress() && handleCommand()) {
					// Connection and forward successful
					notifyAlert(getString(R.string.forward_success) + " | "
							+ getProfileName(),
							getString(R.string.service_running));

					handler.sendEmptyMessage(MSG_CONNECT_SUCCESS);

					// for widget, maybe exception here
					try {
						RemoteViews views = new RemoteViews(getPackageName(),
								R.layout.proxydroid_appwidget);
						views.setImageViewResource(R.id.serviceToggle,
								R.drawable.on);
						AppWidgetManager awm = AppWidgetManager
								.getInstance(ProxyDroidService.this);
						awm.updateAppWidget(awm
								.getAppWidgetIds(new ComponentName(
										ProxyDroidService.this,
										ProxyDroidWidgetProvider.class)), views);
					} catch (Exception ignore) {
						// Nothing
					}

				} else {
					// Connection or forward unsuccessful
					notifyAlert(getString(R.string.forward_fail),
							getString(R.string.service_failed));

					stopSelf();
					handler.sendEmptyMessage(MSG_CONNECT_FAIL);
				}

				handler.sendEmptyMessage(MSG_CONNECT_FINISH);

			}
		}).start();
	}

}

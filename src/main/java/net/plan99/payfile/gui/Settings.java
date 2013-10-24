package net.plan99.payfile.gui;

import com.google.common.net.HostAndPort;
import net.plan99.payfile.client.PayFileClient;

import java.util.prefs.Preferences;

public class Settings {
    private static Preferences preferences = Preferences.userNodeForPackage(Settings.class);

    public static void setLastServer(HostAndPort serverName) {
        preferences.put("lastServer", serverName.toString());
    }

    public static HostAndPort getLastServer() {
        return HostAndPort.fromString(preferences.get("lastServer", "")).withDefaultPort(PayFileClient.PORT);
    }

    public static void setLastPaidServer(HostAndPort serverName) {
        preferences.put("lastPaidServer", serverName.toString());
    }

    public static HostAndPort getLastPaidServer() {
        final String str = preferences.get("lastPaidServer", "");
        return str == null ? null : HostAndPort.fromString(str).withDefaultPort(PayFileClient.PORT);
    }
}

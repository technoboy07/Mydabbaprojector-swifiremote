package com.m2smartremote;

import com.m2smartremote.core.OriginPolicy;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

/** Finds the projector's current private LAN IPv4 address (Wi-Fi or Ethernet). */
public final class NetworkUtils {
    private NetworkUtils() {
    }

    /**
     * Returns the best private IPv4 address of an up, non-loopback interface, preferring Wi-Fi
     * (wlan*) and then Ethernet (eth*). Public addresses are never returned, so the server can
     * never be bound to an internet-facing interface.
     */
    public static String findLanIpv4() {
        String wifi = null;
        String eth = null;
        String other = null;
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual() || nif.isPointToPoint()) {
                    continue;
                }
                String name = nif.getName() == null ? "" : nif.getName();
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) {
                        continue;
                    }
                    String ip = addr.getHostAddress();
                    if (!OriginPolicy.isPrivateIpv4(ip) || ip.startsWith("169.254.")) {
                        continue;
                    }
                    if (name.startsWith("wlan")) {
                        wifi = wifi == null ? ip : wifi;
                    } else if (name.startsWith("eth")) {
                        eth = eth == null ? ip : eth;
                    } else if (!name.startsWith("p2p")) {
                        other = other == null ? ip : other;
                    }
                }
            }
        } catch (SocketException | NullPointerException e) {
            return null;
        }
        if (wifi != null) {
            return wifi;
        }
        return eth != null ? eth : other;
    }
}

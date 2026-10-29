package com.m2smartremote.core;

/**
 * Host / Origin / remote-address checks applied before any routing.
 *
 * <ul>
 *   <li>Host must be exactly the bound LAN address and port. This defeats DNS rebinding, where a
 *       hostile site points its own hostname at the projector's IP.</li>
 *   <li>Origin, when present, must be our own origin. Browsers always send it on POST.</li>
 *   <li>Sec-Fetch-Site, when present, must be same-origin or none.</li>
 *   <li>The peer must have a private / link-local / loopback IPv4 address.</li>
 * </ul>
 */
public final class OriginPolicy {
    private final String expectedHost;
    private final String expectedOrigin;

    public OriginPolicy(String boundIp, int port) {
        this.expectedHost = boundIp + ":" + port;
        this.expectedOrigin = "http://" + expectedHost;
    }

    public boolean hostAllowed(String host) {
        return expectedHost.equals(host);
    }

    /** @param requireOrigin true for state-changing requests. */
    public boolean originAllowed(String origin, boolean requireOrigin) {
        if (origin == null) {
            return !requireOrigin;
        }
        return expectedOrigin.equals(origin);
    }

    public static boolean fetchSiteAllowed(String secFetchSite) {
        return secFetchSite == null || "same-origin".equals(secFetchSite) || "none".equals(secFetchSite);
    }

    /** True for 10/8, 172.16/12, 192.168/16, 169.254/16 and 127/8 dotted-quad IPv4 strings. */
    public static boolean isPrivateIpv4(String ip) {
        int[] o = parseIpv4(ip);
        if (o == null) {
            return false;
        }
        return o[0] == 10
                || (o[0] == 172 && o[1] >= 16 && o[1] <= 31)
                || (o[0] == 192 && o[1] == 168)
                || (o[0] == 169 && o[1] == 254)
                || o[0] == 127;
    }

    static int[] parseIpv4(String ip) {
        if (ip == null) {
            return null;
        }
        if (ip.startsWith("::ffff:")) {
            ip = ip.substring(7);
        }
        String[] parts = ip.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            for (int j = 0; j < p.length(); j++) {
                if (!Character.isDigit(p.charAt(j))) {
                    return null;
                }
            }
            out[i] = Integer.parseInt(p);
            if (out[i] > 255) {
                return null;
            }
        }
        return out;
    }
}

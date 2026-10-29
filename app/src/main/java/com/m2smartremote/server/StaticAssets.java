package com.m2smartremote.server;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the phone UI from a fixed allowlist of bundled files. URL paths are never mapped onto
 * the filesystem, so path traversal is impossible.
 */
final class StaticAssets {
    static final class Asset {
        final String mime;
        final byte[] bytes;

        Asset(String mime, byte[] bytes) {
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    private static final Map<String, String[]> ROUTES;

    static {
        Map<String, String[]> m = new HashMap<>();
        m.put("/", new String[]{"www/index.html", "text/html; charset=utf-8"});
        m.put("/index.html", new String[]{"www/index.html", "text/html; charset=utf-8"});
        m.put("/styles.css", new String[]{"www/styles.css", "text/css; charset=utf-8"});
        m.put("/crypto.js", new String[]{"www/crypto.js", "application/javascript; charset=utf-8"});
        m.put("/app.js", new String[]{"www/app.js", "application/javascript; charset=utf-8"});
        m.put("/icon.svg", new String[]{"www/icon.svg", "image/svg+xml"});
        m.put("/manifest.webmanifest", new String[]{"www/manifest.webmanifest", "application/manifest+json"});
        ROUTES = Collections.unmodifiableMap(m);
    }

    private final AssetManager assets;
    private final Map<String, Asset> cache = new HashMap<>();

    StaticAssets(AssetManager assets) {
        this.assets = assets;
    }

    static boolean isAsset(String path) {
        return ROUTES.containsKey(path);
    }

    synchronized Asset get(String path) throws IOException {
        Asset cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        String[] route = ROUTES.get(path);
        if (route == null) {
            return null;
        }
        Asset asset = new Asset(route[1], read(route[0]));
        cache.put(path, asset);
        return asset;
    }

    private byte[] read(String name) throws IOException {
        InputStream in = assets.open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }
}

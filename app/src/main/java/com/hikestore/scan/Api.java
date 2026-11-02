package com.hikestore.scan;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Talks to HikeOne (app.hikestore.in). Logs in like the website does (password, then
 * Authenticator or email code), keeps the session cookie, and sends the security token
 * (X-CSRF-Token) with every change.
 */
public final class Api {
    public static final String DEFAULT_SERVER = "https://app.hikestore.in";
    public static final String SECTION = "/return_scan";

    private static SharedPreferences prefs;
    private static String server = DEFAULT_SERVER;
    private static final Map<String, String> cookies = new LinkedHashMap<>();
    private static String csrf = "";
    private static String deviceToken = "";
    private static SSLSocketFactory sslFactory;

    public static class LoginRequired extends IOException {
        public LoginRequired() { super("Please log in again"); }
    }

    public static class Resp {
        public int code;
        public String body = "";
        public String location = "";
        public JSONObject json() throws IOException {
            try { return new JSONObject(body); }
            catch (JSONException e) { throw new IOException("Server answer was not readable (HTTP " + code + ")"); }
        }
    }

    private Api() {}

    public static synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getSharedPreferences("api", Context.MODE_PRIVATE);
        server = prefs.getString("server", DEFAULT_SERVER);
        csrf = prefs.getString("csrf", "");
        deviceToken = prefs.getString("device_token", "");
        cookies.clear();
        for (String part : prefs.getString("cookies", "").split(";")) {
            int i = part.indexOf('=');
            if (i > 0) cookies.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
        }
        try { sslFactory = buildSsl(ctx); } catch (Exception e) { sslFactory = null; }
    }

    public static String server() { return server; }

    public static synchronized void setServer(String s) {
        s = s.trim();
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (!s.startsWith("http")) s = "https://" + s;
        server = s;
        prefs.edit().putString("server", s).apply();
    }

    /** this phone is connected (QR code) - workers never log in with a user */
    public static boolean loggedIn() { return deviceToken.length() > 0; }

    public static String deviceName() { return prefs.getString("device_name", ""); }

    public static synchronized void setCsrf(String c) { csrf = c == null ? "" : c; prefs.edit().putString("csrf", csrf).apply(); }

    public static synchronized void logout() {
        cookies.clear(); csrf = ""; deviceToken = "";
        prefs.edit().remove("cookies").remove("csrf").remove("device_token").remove("device_name").apply();
    }

    private static synchronized void saveCookies() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        prefs.edit().putString("cookies", sb.toString()).apply();
    }

    private static synchronized String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static synchronized void takeCookies(HttpURLConnection c) {
        Map<String, List<String>> h = c.getHeaderFields();
        if (h == null) return;
        boolean changed = false;
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() == null || !e.getKey().equalsIgnoreCase("Set-Cookie")) continue;
            for (String v : e.getValue()) {
                String first = v.split(";", 2)[0];
                int i = first.indexOf('=');
                if (i <= 0) continue;
                String name = first.substring(0, i).trim(), val = first.substring(i + 1).trim();
                boolean expired = v.toLowerCase().contains("max-age=0") || v.toLowerCase().contains("expires=thu, 01 jan 1970");
                if (val.isEmpty() || expired) cookies.remove(name); else cookies.put(name, val);
                changed = true;
            }
        }
        if (changed) saveCookies();
    }

    // ------------------------------------------------------------------ HTTP
    public static Resp request(String method, String path, byte[] body, String contentType, int readTimeoutMs) throws IOException {
        URL url = new URL(path.startsWith("http") ? path : server + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection && sslFactory != null) ((HttpsURLConnection) c).setSSLSocketFactory(sslFactory);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(readTimeoutMs > 0 ? readTimeoutMs : 45000);
        c.setRequestMethod(method);
        c.setRequestProperty("User-Agent", "HikeScan-Android/" + AppInfo.VERSION_NAME);
        c.setRequestProperty("Accept", "application/json, text/html");
        String ck = cookieHeader();
        if (!ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        if (!csrf.isEmpty()) c.setRequestProperty("X-CSRF-Token", csrf);
        if (!deviceToken.isEmpty()) c.setRequestProperty("X-Device-Token", deviceToken);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(body.length);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
        }
        Resp r = new Resp();
        try {
            r.code = c.getResponseCode();
            takeCookies(c);
            String loc = c.getHeaderField("Location");
            r.location = loc == null ? "" : loc;
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
        } finally {
            c.disconnect();
        }
        return r;
    }

    static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static String readAll(InputStream in) throws IOException {
        return new String(readBytes(in), "UTF-8");
    }

    /** worker API of the section: /return_scan/w/api/... with this device's key */
    public static final String W = SECTION + "/w";

    /** JSON GET/POST; throws LoginRequired when this device was removed. */
    public static JSONObject call(String method, String path, JSONObject data) throws IOException {
        byte[] body = data == null ? null : data.toString().getBytes("UTF-8");
        Resp r = request(method, W + path, body, "application/json", 0);
        if (r.code == 400 && r.body.contains("expired")) {         // security token refreshed on the server
            loadMe();
            r = request(method, W + path, body, "application/json", 0);
        }
        if (r.code == 401) throw new LoginRequired();
        return r.json();
    }

    public static JSONObject get(String path) throws IOException { return call("GET", path, null); }

    public static JSONObject post(String path, JSONObject data) throws IOException { return call("POST", path, data); }

    /** multipart upload of one file (field "files"). */
    public static JSONObject upload(String path, String fileName, String mime, byte[] data) throws IOException {
        String boundary = "----hikescan" + System.currentTimeMillis();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" +
                fileName.replace("\"", "") + "\"\r\nContent-Type: " + mime + "\r\n\r\n";
        bo.write(head.getBytes("UTF-8"));
        bo.write(data);
        bo.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
        Resp r = request("POST", W + path, bo.toByteArray(), "multipart/form-data; boundary=" + boundary, 180000);
        if (r.code == 401) throw new LoginRequired();
        return r.json();
    }

    /** multipart upload streamed from the file (videos: never the whole file in memory). */
    public static JSONObject uploadStream(String path, String fileName, String mime, InputStream src) throws IOException {
        String boundary = "----hikescan" + System.currentTimeMillis();
        URL url = new URL(server + W + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        if (c instanceof HttpsURLConnection && sslFactory != null) ((HttpsURLConnection) c).setSSLSocketFactory(sslFactory);
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(15000);
        c.setReadTimeout(300000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(256 * 1024);
        c.setRequestProperty("User-Agent", "HikeScan-Android/" + AppInfo.VERSION_NAME);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Cookie", cookieHeader());
        c.setRequestProperty("X-CSRF-Token", csrf);
        c.setRequestProperty("X-Device-Token", deviceToken);
        c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            OutputStream os = c.getOutputStream();
            os.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" +
                    fileName.replace("\"", "") + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes("UTF-8"));
            byte[] buf = new byte[65536];
            int n;
            while ((n = src.read(buf)) > 0) os.write(buf, 0, n);
            src.close();
            os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
            os.close();
            Resp r = new Resp();
            r.code = c.getResponseCode();
            if (r.code == 401) throw new LoginRequired();
            InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
            r.body = in == null ? "" : readAll(in);
            return r.json();
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ connect this device (QR code)
    /** Read the code (and server) from what the scanner / camera read: the QR holds
     *  https://app.hikestore.in/return_scan/w/pair?c=ABCD2345 ; the code can also be typed. */
    public static String[] parsePairText(String text) {
        String t = text == null ? "" : text.trim();
        String code = t, srv = null;
        int q = t.indexOf("c=");
        if (t.contains("://") && q > 0) {
            code = t.substring(q + 2).split("[&#\\s]")[0];
            int s = t.indexOf("s=");
            if (t.startsWith("hikescan://") && s > 0) {
                try { srv = java.net.URLDecoder.decode(t.substring(s + 2).split("&")[0], "UTF-8"); } catch (Exception ignored) { }
            } else if (t.startsWith("http")) {
                int p = t.indexOf("/", t.indexOf("://") + 3);
                srv = p > 0 ? t.substring(0, p) : t;
            }
        }
        code = code.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return new String[]{code, srv};
    }

    /** Connect with the code from the QR; returns null when OK, else the message to show. */
    public static String pair(String code, String name, String model) throws IOException {
        synchronized (Api.class) { cookies.clear(); csrf = ""; deviceToken = ""; }
        loadMe();                                    // gets the session cookie + security token
        JSONObject b = new JSONObject();
        try { b.put("code", code); b.put("name", name); b.put("model", model); } catch (JSONException ignored) { }
        Resp r = request("POST", W + "/api/pair", b.toString().getBytes("UTF-8"), "application/json", 0);
        JSONObject j = r.json();
        if (!j.optBoolean("ok")) return j.optString("error", "Could not connect");
        synchronized (Api.class) { deviceToken = j.optString("token"); }
        prefs.edit().putString("device_token", deviceToken).putString("device_name", j.optString("name")).apply();
        JSONObject me = loadMe();
        return me != null && me.optJSONObject("device") != null ? null : "Connected, but the server did not accept the device key.";
    }

    /** settings + security token; also tells whether this device is still connected */
    public static JSONObject loadMe() throws IOException {
        Resp r = request("GET", W + "/api/me", null, null, 0);
        if (r.code == 404) throw new IOException("Return scanning is not installed on " + server);
        JSONObject j = r.json();
        setCsrf(j.optString("csrf", ""));
        if (!deviceToken.isEmpty() && j.optJSONObject("device") == null) throw new LoginRequired();
        prefs.edit().putString("me", j.toString()).apply();
        return j;
    }

    public static JSONObject cachedMe() {
        try { return new JSONObject(prefs.getString("me", "{}")); } catch (JSONException e) { return new JSONObject(); }
    }

    public static String encode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (IOException e) { return s; }
    }

    public static Uri webUrl(String path) { return Uri.parse(server + path); }

    // ------------------------------------------------------------------ TLS for old Android
    /** Android 5-7.0 do not know Let's Encrypt's ISRG roots: trust the system list plus those two. */
    private static SSLSocketFactory buildSsl(Context ctx) throws Exception {
        TrustManagerFactory sysF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        sysF.init((KeyStore) null);
        final X509TrustManager sys = firstX509(sysF.getTrustManagers());
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        InputStream in = ctx.getResources().openRawResource(R.raw.isrg_roots);
        Collection<? extends Certificate> certs = cf.generateCertificates(in);
        in.close();
        int i = 0;
        for (Certificate c : certs) ks.setCertificateEntry("isrg" + (i++), c);
        TrustManagerFactory extraF = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        extraF.init(ks);
        final X509TrustManager extra = firstX509(extraF.getTrustManagers());
        X509TrustManager both = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                sys.checkClientTrusted(chain, authType);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                try { sys.checkServerTrusted(chain, authType); }
                catch (CertificateException e) { extra.checkServerTrusted(chain, authType); }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return sys.getAcceptedIssuers(); }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, new TrustManager[]{both}, null);
        return sc.getSocketFactory();
    }

    private static X509TrustManager firstX509(TrustManager[] tms) {
        for (TrustManager t : tms) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }
}

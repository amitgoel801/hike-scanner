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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    public static boolean loggedIn() { return cookies.containsKey("hikeone") && csrf.length() > 0; }

    public static synchronized void setCsrf(String c) { csrf = c == null ? "" : c; prefs.edit().putString("csrf", csrf).apply(); }

    public static synchronized void logout() {
        cookies.clear(); csrf = "";
        prefs.edit().remove("cookies").remove("csrf").apply();
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

    /** JSON GET/POST to the section; throws LoginRequired when the session ended. */
    public static JSONObject call(String method, String path, JSONObject data) throws IOException {
        byte[] body = data == null ? null : data.toString().getBytes("UTF-8");
        Resp r = request(method, SECTION + path, body, "application/json", 0);
        if (r.code == 401 || (r.code == 302 && r.location.contains("/login"))) throw new LoginRequired();
        if (r.code == 400 && r.body.contains("expired")) throw new LoginRequired();
        JSONObject j = r.json();
        return j;
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
        Resp r = request("POST", SECTION + path, bo.toByteArray(), "multipart/form-data; boundary=" + boundary, 180000);
        if (r.code == 401) throw new LoginRequired();
        return r.json();
    }

    /** multipart upload streamed from the file (videos: never the whole file in memory). */
    public static JSONObject uploadStream(String path, String fileName, String mime, InputStream src) throws IOException {
        String boundary = "----hikescan" + System.currentTimeMillis();
        URL url = new URL(server + SECTION + path);
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

    // ------------------------------------------------------------------ login (same steps as the website)
    private static final Pattern CSRF = Pattern.compile("name=\"csrf\" value=\"([^\"]+)\"");
    private static final Pattern FLASH = Pattern.compile("class=\"flash error\">([^<]+)<");
    private static final Pattern METHOD = Pattern.compile("method=(\\w+)\"[^>]*>([^<]+)</a>");

    static String csrfOf(String html) {
        Matcher m = CSRF.matcher(html);
        return m.find() ? m.group(1) : "";
    }

    static String flashOf(String html, String fallback) {
        Matcher m = FLASH.matcher(html);
        return m.find() ? m.group(1).replace("&#39;", "'").replace("&amp;", "&") : fallback;
    }

    static byte[] form(String... kv) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString().getBytes("UTF-8");
    }

    public static class LoginStep {
        public boolean done;
        public String error;
        public List<String[]> methods = new ArrayList<>();   // {id, label}
        public String pageCsrf = "";
        public String info = "";
    }

    /** Step 1: username + password. Returns the second-step methods (Authenticator / email). */
    public static LoginStep loginPassword(String user, String pass) throws IOException {
        synchronized (Api.class) { cookies.clear(); csrf = ""; }
        LoginStep st = new LoginStep();
        Resp r = request("GET", "/login", null, null, 0);
        String tok = csrfOf(r.body);
        r = request("POST", "/login", form("csrf", tok, "next", "", "username", user, "password", pass),
                "application/x-www-form-urlencoded", 0);
        if (r.code != 302 || !r.location.contains("/login/verify")) {
            st.error = flashOf(r.body, "Wrong username or password.");
            return st;
        }
        return verifyPage(st, "");
    }

    private static LoginStep verifyPage(LoginStep st, String method) throws IOException {
        Resp r = request("GET", "/login/verify" + (method.isEmpty() ? "" : "?method=" + method), null, null, 0);
        if (r.code == 302 && r.location.contains("enroll")) {
            st.error = "This user has no Authenticator yet. Log in once on app.hikestore.in in a browser to set it up, then use the app.";
            return st;
        }
        if (r.code == 302 && r.location.contains("/login")) {
            st.error = "Login expired - try again.";
            return st;
        }
        st.pageCsrf = csrfOf(r.body);
        Matcher m = METHOD.matcher(r.body);
        while (m.find()) st.methods.add(new String[]{m.group(1), m.group(2).trim()});
        if (st.methods.isEmpty()) {
            if (r.body.contains("Send code to")) st.methods.add(new String[]{"email", "Email code"});
            else st.methods.add(new String[]{"totp", "Authenticator app"});
        }
        return st;
    }

    /** Ask the server to email a code. */
    public static String sendEmailCode() throws IOException {
        LoginStep st = verifyPage(new LoginStep(), "email");
        if (st.error != null) return st.error;
        Resp r = request("POST", "/login/verify", form("csrf", st.pageCsrf, "method", "email", "action", "send"),
                "application/x-www-form-urlencoded", 0);
        Resp page = request("GET", "/login/verify?method=email", null, null, 0);
        Matcher ok = Pattern.compile("class=\"flash ok\">([^<]+)<").matcher(page.body);
        if (ok.find()) return ok.group(1);
        return flashOf(page.body, "Code sent - check the email.");
    }

    /** Step 2: the 6-digit code. On success the session is ready and /api/me is loaded. */
    public static LoginStep loginCode(String method, String code) throws IOException {
        LoginStep st = verifyPage(new LoginStep(), method);
        if (st.error != null) return st;
        Resp r = request("POST", "/login/verify", form("csrf", st.pageCsrf, "method", method, "code", code.trim()),
                "application/x-www-form-urlencoded", 0);
        if (r.code != 302) {
            st.error = flashOf(r.body, "Wrong code.");
            return st;
        }
        if (r.location.contains("/account/password")) {
            st.error = "This user still has the starting password. Change it once on app.hikestore.in in a browser, then log in here.";
            return st;
        }
        JSONObject me = loadMe();
        if (me == null) {
            st.error = "Logged in, but this user has no access to Return scanning. Ask the admin to tick it in Users & access.";
            return st;
        }
        st.done = true;
        return st;
    }

    /** /api/me gives the security token for changes and all settings. */
    public static JSONObject loadMe() throws IOException {
        Resp r = request("GET", SECTION + "/api/me", null, null, 0);
        if (r.code == 403 || r.code == 404) return null;
        if (r.code == 401 || r.code == 302) throw new LoginRequired();
        JSONObject j = r.json();
        setCsrf(j.optString("csrf", ""));
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

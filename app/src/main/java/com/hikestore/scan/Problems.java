package com.hikestore.scan;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** A problem packet (wrong / empty / damaged ...) with photos and videos from the phone, for the claim. */
public final class Problems {
    static final int REQ_PHOTO = 7101, REQ_VIDEO = 7102, REQ_GALLERY = 7103, REQ_PERM = 7104;
    private static AlertDialog open;
    private static String pendingAction = "";

    private Problems() {}

    private static SharedPreferences prefs(Activity a) { return a.getSharedPreferences("problem", Activity.MODE_PRIVATE); }

    /** Step 1: which packet + what is wrong. */
    public static void start(final MainActivity a, final String loc, final String day, String tracking, final String code, JSONObject me) {
        LinearLayout box = Ui.col(a);
        Ui.pad(box, 16);
        box.addView(Ui.text(a, "Tracking ID / scanned code", 12, Ui.INK2, true));
        final EditText trk = new EditText(a);
        trk.setText(tracking);
        trk.setSingleLine(true);
        box.addView(trk);
        box.addView(Ui.text(a, "Problem", 12, Ui.INK2, true));
        final Spinner type = new Spinner(a);
        JSONArray t = me.optJSONArray("issue_types");
        List<String> types = new ArrayList<>();
        if (t != null) for (int i = 0; i < t.length(); i++) types.add(t.optString(i));
        if (types.isEmpty()) types.add("Other");
        type.setAdapter(new ArrayAdapter<>(a, android.R.layout.simple_spinner_dropdown_item, types));
        box.addView(type);
        box.addView(Ui.text(a, "Note (what is wrong)", 12, Ui.INK2, true));
        final EditText note = new EditText(a);
        note.setMinLines(2);
        box.addView(note);
        new AlertDialog.Builder(a).setTitle("⚠ Report a problem packet").setView(box)
                .setPositiveButton("Next: photos & video", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        final String tr = trk.getText().toString().trim().toUpperCase().replaceAll("\\s+", "");
                        if (tr.isEmpty()) { a.toast("Scan or type the tracking ID"); return; }
                        final String ty = String.valueOf(type.getSelectedItem()), nt = note.getText().toString();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                try {
                                    JSONObject b = new JSONObject();
                                    b.put("loc", loc); b.put("day", day); b.put("tracking_id", tr); b.put("code", code.isEmpty() ? tr : code);
                                    b.put("type", ty); b.put("note", nt);
                                    final JSONObject r = Api.post("/api/issue", b);
                                    a.runOnUiThread(new Runnable() {
                                        @Override public void run() {
                                            if (!r.optBoolean("ok")) { a.toast(r.optString("error")); return; }
                                            if (r.optBoolean("existing")) a.toast("This packet already has a problem - opening it");
                                            JSONObject is = r.optJSONObject("issue");
                                            try {
                                                JSONObject mini = new JSONObject();
                                                mini.put("id", is.optLong("id")); mini.put("type", is.optString("type")); mini.put("status", is.optString("status"));
                                                a.problemSaved(is.optString("tracking_id"), mini);
                                            } catch (Exception ignored) { }
                                            media(a, is);
                                        }
                                    });
                                } catch (Api.LoginRequired e) { a.toastBg("Login expired - log in again from the menu"); }
                                catch (Exception e) { a.toastBg("Needs network to save a problem - try again"); }
                            }
                        }).start();
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Step 2: photos / videos for this problem. */
    static void media(final MainActivity a, final JSONObject issue) {
        final long id = issue.optLong("id");
        prefs(a).edit().putLong("issue", id).apply();
        JSONArray m = issue.optJSONArray("media");
        int ph = 0, vi = 0;
        if (m != null) for (int i = 0; i < m.length(); i++) { if ("video".equals(m.optJSONObject(i).optString("kind"))) vi++; else ph++; }
        LinearLayout box = Ui.col(a);
        Ui.pad(box, 16);
        JSONObject p = issue.optJSONObject("packet");
        String sku = "";
        if (p != null && p.optJSONArray("items") != null && p.optJSONArray("items").length() > 0) sku = p.optJSONArray("items").optJSONObject(0).optString("sku") + " · ";
        box.addView(Ui.text(a, sku + issue.optString("tracking_id"), 14, Ui.INK, true));
        box.addView(Ui.text(a, issue.optString("type") + " · status " + issue.optString("status"), 12, Ui.INK2, false));
        TextView cnt = Ui.text(a, "📷 " + ph + " photos · 🎥 " + vi + " videos uploaded", 14, Ui.OK, true);
        cnt.setPadding(0, Ui.dp(8), 0, Ui.dp(8));
        box.addView(cnt);
        Button photo = Ui.button(a, "📷 Take photo", true);
        Button video = Ui.button(a, "🎥 Record video", true);
        Button gallery = Ui.button(a, "🖼 From gallery", false);
        box.addView(photo, Ui.lpm(-1, Ui.dp(46), 0, 4, 0, 4));
        box.addView(video, Ui.lpm(-1, Ui.dp(46), 0, 4, 0, 4));
        box.addView(gallery, Ui.lpm(-1, Ui.dp(46), 0, 4, 0, 4));
        TextView hint = Ui.text(a, "Take photos of the label, the open packet and the product, and a video of opening it. " +
                "Claim text, ticket ID and status are on the full page (menu → Open full page).", 12, Ui.INK2, false);
        hint.setPadding(0, Ui.dp(8), 0, 0);
        box.addView(hint);
        if (open != null && open.isShowing()) open.dismiss();
        open = new AlertDialog.Builder(a).setTitle("Problem #" + id + " - photos & video").setView(box).setPositiveButton("Done", null).show();
        photo.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { capture(a, "photo"); } });
        video.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { capture(a, "video"); } });
        gallery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                a.startActivityForResult(Intent.createChooser(i, "Pick photos / videos"), REQ_GALLERY);
            }
        });
    }

    private static void capture(MainActivity a, String kind) {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29 &&
                a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = kind;
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM);
            return;
        }
        boolean video = "video".equals(kind);
        ContentValues v = new ContentValues();
        String name = "HikeScan_" + System.currentTimeMillis() + (video ? ".mp4" : ".jpg");
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, video ? "video/mp4" : "image/jpeg");
        if (Build.VERSION.SDK_INT >= 29) v.put(MediaStore.MediaColumns.RELATIVE_PATH, (video ? "Movies" : "Pictures") + "/HikeScan");
        Uri out;
        try {
            out = a.getContentResolver().insert(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        } catch (Exception e) { out = null; }
        Intent i = new Intent(video ? MediaStore.ACTION_VIDEO_CAPTURE : MediaStore.ACTION_IMAGE_CAPTURE);
        if (out != null) {
            i.putExtra(MediaStore.EXTRA_OUTPUT, out);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            prefs(a).edit().putString("out", out.toString()).apply();
        } else prefs(a).edit().remove("out").apply();
        if (video) i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1);
        try { a.startActivityForResult(i, video ? REQ_VIDEO : REQ_PHOTO); }
        catch (Exception e) { a.toast("No camera app found"); }
    }

    static void onPermission(MainActivity a, int req, int[] grants) {
        if (req != REQ_PERM) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED && !pendingAction.isEmpty()) capture(a, pendingAction);
        else a.toast("Storage permission is needed to save the photo");
        pendingAction = "";
    }

    static void onResult(final MainActivity a, int req, int res, Intent data) {
        if (req != REQ_PHOTO && req != REQ_VIDEO && req != REQ_GALLERY) return;
        final long id = prefs(a).getLong("issue", 0);
        String out = prefs(a).getString("out", null);
        final List<Uri> uris = new ArrayList<>();
        if (res != Activity.RESULT_OK) {
            if (out != null && req != REQ_GALLERY) try { a.getContentResolver().delete(Uri.parse(out), null, null); } catch (Exception ignored) { }
            return;
        }
        if (req == REQ_GALLERY && data != null) {
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
        } else {
            if (data != null && data.getData() != null && req == REQ_VIDEO) uris.add(data.getData());
            else if (out != null) uris.add(Uri.parse(out));
        }
        if (uris.isEmpty() || id == 0) return;
        final AlertDialog wait = new AlertDialog.Builder(a).setTitle("Uploading…").setMessage("Sending " + uris.size() + " file(s). Keep the app open.")
                .setCancelable(false).show();
        new Thread(new Runnable() {
            @Override public void run() {
                JSONObject last = null;
                String err = null;
                int okN = 0;
                for (int k = 0; k < uris.size(); k++) {
                    final int n = k + 1;
                    a.runOnUiThread(new Runnable() { @Override public void run() { wait.setMessage("Sending file " + n + " of " + uris.size() + "…"); } });
                    try {
                        JSONObject r = send(a, id, uris.get(k));
                        if (r.optBoolean("ok")) { okN++; last = r.optJSONObject("issue"); }
                        else err = r.optString("error");
                    } catch (Exception e) { err = e.getMessage(); }
                }
                final JSONObject issue = last;
                final String e = err;
                final int done = okN;
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        wait.dismiss();
                        if (e != null) {
                            new AlertDialog.Builder(a).setTitle("Upload problem").setMessage(done + " of " + uris.size() + " sent.\n" + e +
                                    "\n\nPhotos taken with the camera are also in the phone gallery (HikeScan folder) - add them again with 'From gallery'.")
                                    .setPositiveButton("OK", null).show();
                        } else a.toast(done + " file(s) added");
                        if (issue != null) media(a, issue);
                    }
                });
            }
        }).start();
    }

    private static JSONObject send(Activity a, long id, Uri u) throws Exception {
        ContentResolver cr = a.getContentResolver();
        String mime = cr.getType(u);
        if (mime == null) mime = u.toString().endsWith(".mp4") ? "video/mp4" : "image/jpeg";
        String name = "file";
        long size = -1;
        Cursor c = null;
        try {
            c = cr.query(u, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME), si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { } finally { if (c != null) c.close(); }
        if (mime.startsWith("image/") && (size < 0 || size > 900 * 1024)) {
            InputStream in = cr.openInputStream(u);
            byte[] raw = Api.readBytes(in);
            Bitmap bm = Images.decode(raw, 2000);
            if (bm != null) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                bm.compress(Bitmap.CompressFormat.JPEG, 86, bo);
                if (bo.size() < raw.length) return Api.upload("/api/issue/" + id + "/media", name.replaceAll("\\.\\w+$", "") + ".jpg", "image/jpeg", bo.toByteArray());
            }
            return Api.upload("/api/issue/" + id + "/media", name, mime, raw);
        }
        return Api.uploadStream("/api/issue/" + id + "/media", name, mime, cr.openInputStream(u));
    }
}

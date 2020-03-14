package com.antest1.gotobrowser.Helpers;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.antest1.gotobrowser.Activity.SettingsActivity;
import com.antest1.gotobrowser.BuildConfig;
import com.antest1.gotobrowser.R;
import com.antest1.gotobrowser.Subtitle.SubtitleCheck;
import com.crashlytics.android.Crashlytics;
import com.google.android.material.snackbar.Snackbar;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

import static com.antest1.gotobrowser.Constants.CACHE_SIZE_BYTES;

public class KcUtils {
    public static void showToast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }

    public static void showToast(Context context, int resource_id) {
        Toast.makeText(context, context.getString(resource_id), Toast.LENGTH_LONG).show();
    }

    public static String getStringFromException(Exception ex) {
        StringWriter errors = new StringWriter();
        ex.printStackTrace(new PrintWriter(errors));
        return errors.toString().replaceAll("\n", " / ").replaceAll("\t", "");
    }

    public static void reportException(Exception e) {
        e.printStackTrace();
        Crashlytics.logException(e);
    }

    public static void reportException(ExceptionInInitializerError e) {
        e.printStackTrace();
        Crashlytics.logException(e);
    }

    public static Request getDownloadRequest(String url, String userAgent, String mimetype) {
        Request request = new Request.Builder().url(url)
                .addHeader("User-Agent", userAgent)
                .addHeader("Content-Type", mimetype)
                .addHeader("Cache-Control", "no-cache")
                .build();
        return request;
    }

    public static boolean checkIsPlaying (MediaPlayer player) {
        if (player == null) return false;
        try {
            return player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static InputStream getEmptyStream() {
        return new InputStream() {
            @Override
            public int read() {
                return -1;
            }
        };
    }

    public static DisplayMetrics getActivityDimension(Activity activity) {
        DisplayMetrics dimension = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dimension);
        return dimension;
    }

    public static boolean unzipResource(Context context, InputStream is, String path, VersionDatabase db, String version) {
        String cache_path = context.getFilesDir().getAbsolutePath().concat("/cache");
        File dir = new File(cache_path + path);
        if (!dir.exists()) dir.mkdirs();

        JsonObject prefixInfo = null;
        ZipInputStream zis;
        try {
            String filename;
            zis = new ZipInputStream(new BufferedInputStream(is));
            ZipEntry ze;
            byte[] buffer = new byte[1024];
            int count;
            while ((ze = zis.getNextEntry()) != null) {
                filename = ze.getName();
                Log.e("GOTO", "zip - " + filename);
                if (path == null) continue;
                if (ze.isDirectory()) {
                    File fmd = new File(cache_path + path + filename);
                    if (!fmd.exists()) fmd.mkdirs();
                    continue;
                } else if (filename.contains("version.txt")) {
                    prefixInfo = new JsonObject();
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    while ((count = zis.read(buffer)) != -1) {
                        baos.write(buffer, 0, count);
                    }
                    String content = baos.toString();
                    Log.e("GOTO", content);
                    String[] content_list = content.split("\\n", -1);
                    for (String item: content_list) {
                        if (item.trim().length() > 0) {
                            String[] item_v = item.split("\\t");
                            String key = item_v[0];
                            String value = item_v[1].trim().replace("_", "");
                            if (value.length() > 0) prefixInfo.addProperty(key, value);
                        }
                    }
                    baos.close();
                    Log.e("GOTO", prefixInfo.toString());
                    db.overrideByPrefix(prefixInfo);
                } else {
                    FileOutputStream fout = new FileOutputStream(cache_path + path + filename);
                    while ((count = zis.read(buffer)) != -1) {
                        fout.write(buffer, 0, count);
                    }
                    if (version != null) db.putValue(path + filename, version);
                    Log.e("GOTO", "cache resource " + path + filename +  ": " + version);
                    fout.close();
                }
                zis.closeEntry();
            }
            zis.close();
        } catch(IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static String getProcessName(Context context) {
        if (context == null) return null;
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : manager.getRunningAppProcesses()) {
            if (processInfo.pid == android.os.Process.myPid()) {
                return processInfo.processName;
            }
        }
        return null;
    }

    public static void clearApplicationCache(Context context, File file) {
        File dir = null;
        if (file == null) {
            dir = context.getCacheDir();
        } else {
            dir = file;
        }
        if (dir == null) return;
        File[] children = dir.listFiles();
        try {
            for (File child : children)
                if (child.isDirectory()) clearApplicationCache(context, child);
                else child.delete();
        } catch (Exception e) {
            Crashlytics.logException(e);
        }
    }

    public static String downloadResource(OkHttpClient client, String fullpath, String last_modified, File file) {
        Request.Builder builder = new Request.Builder().url(fullpath);
        if (last_modified != null && !VersionDatabase.isDefaultValue(last_modified)) {
            builder.addHeader("If-Modified-Since", last_modified);
            Log.e("GOTO", "If-Modified-Since: " + last_modified);
        } else {
            builder.addHeader("Cache-Control", "no-cache");
        }
        Log.e("GOTO", "download " + fullpath);
        Request request = builder.build();
        try {
            Response response = client.newCall(request).execute();
            if (response.code() == 200) {
                Log.e("GOTO", "200 OK " + fullpath);
                String new_last_modified = response.header("Last-Modified", "none");
                ResponseBody body = response.body();
                if (body != null) {
                    InputStream in = body.byteStream();
                    byte[] buffer = new byte[8 * 1024];
                    int bytes;
                    file.getParentFile().mkdirs();
                    FileOutputStream fos = new FileOutputStream(file);
                    while ((bytes = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytes);
                    }
                    fos.close();
                    body.close();
                }
                return new_last_modified;
            } else if (response.code() == 304) {
                Log.e("GOTO", "304 Not Modified " + fullpath);
                return "304";
            }
        } catch (Exception e) {
            KcUtils.reportException(e);
            return null;
        }
        return null;
    }

    public static Retrofit getRetrofitAdapter(Context context, String baseUrl) {
        OkHttpClient.Builder builder = new OkHttpClient().newBuilder();
        builder.cache(
                new Cache(context.getCacheDir(), CACHE_SIZE_BYTES));
        OkHttpClient client = builder.build();
        Retrofit.Builder retrofitBuilder = new Retrofit.Builder();
        retrofitBuilder
                .addConverterFactory(GsonConverterFactory.create())
                .baseUrl(baseUrl)
                .client(client);
        return retrofitBuilder.build();
    }

    public static String joinStr(List<String> list, String delim) {
        String resultStr = "";
        if (list.size() > 0) {
            int i;
            for (i = 0; i < list.size() - 1; i++) {
                resultStr = resultStr.concat(list.get(i));
                resultStr = resultStr.concat(delim);
            }
            resultStr = resultStr.concat(list.get(i));
        }
        return resultStr;
    }

    public static JsonObject readJsonObjectFromFile(String path) {
        try {
            final Gson gson = new Gson();
            final BufferedReader reader = new BufferedReader(new FileReader(path));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) sb.append(line);
            return gson.fromJson(sb.toString(), JsonObject.class);
        } catch (IOException e) {
            KcUtils.reportException(e);
        }
        return null;
    }

    public static byte[] gzipcompress(String value) throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        GZIPOutputStream gzipOutStream = new GZIPOutputStream(
                new BufferedOutputStream(byteArrayOutputStream));
        gzipOutStream.write(value.getBytes());
        gzipOutStream.finish();
        gzipOutStream.close();

        return byteArrayOutputStream.toByteArray();
    }

    public static byte[] decompress(byte[] bytes) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            GZIPInputStream gunzip = new GZIPInputStream(in);
            byte[] buffer = new byte[256];
            int n;
            while ((n = gunzip.read(buffer)) >= 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static void requestLatestAppVersion(Activity ac, SubtitleCheck updateCheck, boolean show_toast) {
        Call<JsonObject> call = updateCheck.version();
        call.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, retrofit2.Response<JsonObject> response) {
                Log.e("GOTO", response.headers().toString());
                if (response.code() == 200) {
                    checkAppUpdate(ac, response, show_toast);
                } else {
                    String message = "HTTP: " + response.code();
                    if (response.code() == 404) message = "No update found.";
                    Snackbar.make(ac.findViewById(R.id.main_container), message, Snackbar.LENGTH_LONG).show();
                }
            }
            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                Snackbar.make(ac.findViewById(R.id.main_container), String.valueOf(t.getLocalizedMessage()), Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private static void checkAppUpdate(Activity ac, retrofit2.Response<JsonObject> response, boolean show_toast) {
        JsonObject version_info = response.body();
        if (version_info != null && version_info.has("tag_name")) {
            Log.e("GOTO", version_info.toString());
            String tag = version_info.get("tag_name").getAsString().substring(1);
            String latest_file = String.format(Locale.US, "http://18.176.189.52/GotoBrowser/files/gotobrowser-%s-release.apk", tag);
            if (BuildConfig.VERSION_NAME.equals(tag)) {
                if (show_toast) Snackbar.make(ac.findViewById(R.id.main_container), R.string.setting_latest_version, Snackbar.LENGTH_LONG).show();
            } else {
                showAppUpdateDownloadDialog(ac, tag, latest_file);
            }
        }
    }

    private static void showAppUpdateDownloadDialog(Activity ac, String tag, String latest_file) {
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                ac);
        alertDialogBuilder.setTitle(ac.getString(R.string.app_name));
        alertDialogBuilder
                .setCancelable(false)
                .setMessage(String.format(Locale.US, ac.getString(R.string.setting_latest_download), tag))
                .setPositiveButton(R.string.action_ok,
                        (dialog, id) -> {
                            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(latest_file));
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ac.startActivity(intent);
                        })
                .setNegativeButton(R.string.action_cancel,
                        (dialog, id) -> dialog.cancel());
        AlertDialog alertDialog = alertDialogBuilder.create();
        alertDialog.show();
    }
}


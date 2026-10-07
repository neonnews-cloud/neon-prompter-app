package cloud.neonnews.prompter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity implements RecognitionListener {

    private static final int REQ_MIC = 17;
    private static final String MODEL_VERSION = "vosk-model-small-en-us-0.15";

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());

    private Model model;
    private SpeechService speech;
    private boolean modelLoading = false;
    private boolean wantListening = false;
    private boolean pageReady = false;
    private String pendingJs = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setBackgroundColor(0xFF050A1F);
        web.addJavascriptInterface(new Bridge(), "NNAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingJs != null) {
                    web.evaluateJavascript(pendingJs, null);
                    pendingJs = null;
                }
            }
        });
        setContentView(web);
        goFullscreen();

        String hash = hashFromIntent(getIntent());
        web.loadUrl("file:///android_asset/index.html" + (hash != null ? "#" + hash : ""));
        handleShare(getIntent());
        loadModel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String hash = hashFromIntent(intent);
        if (hash != null) {
            pageReady = false;
            web.loadUrl("file:///android_asset/index.html#" + hash);
        }
        handleShare(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goFullscreen();
    }

    private void goFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /* script coming in from a desk link or the share menu */

    private String hashFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return null;
        Uri u = intent.getData();
        if (u == null) return null;
        String f = u.getEncodedFragment();
        return (f == null || f.isEmpty()) ? null : f;
    }

    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.trim().isEmpty()) return;
        runJs("window.nnLoadScript && window.nnLoadScript(" + JSONObject.quote(text) + ")");
    }

    private void runJs(String js) {
        main.post(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
            else pendingJs = js;
        });
    }

    private void toPage(String kind, String text) {
        runJs("window.nnVoice && window.nnVoice(" + JSONObject.quote(kind) + "," + JSONObject.quote(text) + ")");
    }

    /* offline speech model */

    private void loadModel() {
        if (model != null || modelLoading) return;
        modelLoading = true;
        new Thread(() -> {
            try {
                File root = new File(getFilesDir(), "model");
                File marker = new File(root, MODEL_VERSION + ".ok");
                if (!marker.exists()) {
                    deleteRecursive(root);
                    root.mkdirs();
                    unzipAsset("model.zip", root);
                    marker.createNewFile();
                }
                File dir = findModelDir(root);
                if (dir == null) throw new Exception("Speech model files not found in the app.");
                Model m = new Model(dir.getAbsolutePath());
                main.post(() -> {
                    model = m;
                    modelLoading = false;
                    if (wantListening) startListening();
                });
            } catch (Exception e) {
                main.post(() -> {
                    modelLoading = false;
                    toPage("error", "Speech model failed to load: " + e.getMessage());
                });
            }
        }).start();
    }

    private File findModelDir(File root) {
        if (new File(root, "am").isDirectory() || new File(root, "conf").isDirectory()) return root;
        File[] kids = root.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (k.isDirectory() && (new File(k, "am").isDirectory() || new File(k, "conf").isDirectory())) return k;
        }
        return null;
    }

    private void unzipAsset(String name, File target) throws Exception {
        byte[] buf = new byte[65536];
        try (InputStream in = getAssets().open(name); ZipInputStream zin = new ZipInputStream(in)) {
            ZipEntry e;
            String base = target.getCanonicalPath() + File.separator;
            while ((e = zin.getNextEntry()) != null) {
                File out = new File(target, e.getName());
                if (!out.getCanonicalPath().startsWith(base)) continue;
                if (e.isDirectory()) { out.mkdirs(); continue; }
                out.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
        }
    }

    private void deleteRecursive(File f) {
        if (!f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    /* listening */

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (model == null) {
            toPage("status", "Loading the speech model, first start takes a few seconds...");
            loadModel();
            return;
        }
        if (speech != null) return;
        try {
            Recognizer rec = new Recognizer(model, 16000.0f);
            speech = new SpeechService(rec, 16000.0f);
            speech.startListening(this);
            toPage("status", "Listening. Start reading.");
        } catch (Exception e) {
            speech = null;
            toPage("error", "Could not start the microphone: " + e.getMessage());
        }
    }

    private void stopListening() {
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (wantListening) startListening();
        } else {
            wantListening = false;
            toPage("error", "No microphone permission. Allow it in Android settings for Neon Prompter.");
        }
    }

    @Override public void onPartialResult(String hypothesis) { toPage("partial", field(hypothesis, "partial")); }
    @Override public void onResult(String hypothesis) { toPage("final", field(hypothesis, "text")); }
    @Override public void onFinalResult(String hypothesis) { toPage("final", field(hypothesis, "text")); }
    @Override public void onError(Exception e) { wantListening = false; stopListening(); toPage("error", "Speech recognition error: " + e.getMessage()); }
    @Override public void onTimeout() { }

    private String field(String json, String key) {
        try { return new JSONObject(json).optString(key, ""); } catch (Exception e) { return ""; }
    }

    @Override
    protected void onDestroy() {
        stopListening();
        if (model != null) model.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.nnBack ? window.nnBack() : false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    private class Bridge {
        @JavascriptInterface public void start() { main.post(() -> { wantListening = true; startListening(); }); }
        @JavascriptInterface public void stop() { main.post(() -> { wantListening = false; stopListening(); }); }
    }
}

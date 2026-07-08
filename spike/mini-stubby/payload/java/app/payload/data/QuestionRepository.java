package app.payload.data;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads assets/questions.json off the main thread and posts the parsed result
 * back through a main-looper Handler (stressors 10 + 11). The AssetManager is
 * the HOST activity's — the shell's ResourcesLoader merged the payload apk's
 * assets into it, so open("questions.json") serves the payload's file.
 */
public final class QuestionRepository {

    /** Result callback; both methods are invoked on the main thread. */
    public interface Callback {
        void onLoaded(String surveyTitle, List<Question> questions);

        void onError(String message);
    }

    private QuestionRepository() {}

    public static void loadAsync(AssetManager assets, Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                String json = readFully(assets.open("questions.json"));
                JSONObject root = new JSONObject(json);
                String title = root.optString("survey", "Survey");
                JSONArray arr = root.getJSONArray("questions");
                List<Question> out = new ArrayList<>(arr.length());
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject q = arr.getJSONObject(i);
                    out.add(new Question(
                            q.getString("id"),
                            q.optString("category", ""),
                            q.getString("text")));
                }
                main.post(() -> cb.onLoaded(title, out));
            } catch (Throwable t) {
                main.post(() -> cb.onError(String.valueOf(t)));
            }
        }, "fieldsurvey-questions").start();
    }

    private static String readFully(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

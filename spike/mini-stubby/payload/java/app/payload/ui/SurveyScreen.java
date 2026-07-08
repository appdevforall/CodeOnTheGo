package app.payload.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RatingBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.payload.App;
import app.payload.R;
import app.payload.Screen;
import app.payload.data.Question;
import app.payload.data.QuestionRepository;
import app.payload.data.Response;

/**
 * Survey capture: questions arrive asynchronously from assets/questions.json
 * (background thread + Handler), rows are built programmatically, ratings are
 * preserved across hot reloads via onSaveState/restore.
 */
public final class SurveyScreen extends Screen {

    private final App app;
    private final String siteName;
    /** Ratings restored from a previous generation, applied once questions load. */
    private final int[] restoredRatings;

    private final List<RatingBar> bars = new ArrayList<>();
    private TextView loading;
    private LinearLayout questionBox;
    private Button save;

    public SurveyScreen(App app, String siteName) {
        this(app, siteName, null);
    }

    private SurveyScreen(App app, String siteName, int[] restoredRatings) {
        this.app = app;
        this.siteName = siteName;
        this.restoredRatings = restoredRatings;
    }

    /** Rebuilds an in-progress survey from the cross-generation state Bundle. */
    public static SurveyScreen restore(App app, Bundle saved) {
        return new SurveyScreen(app,
                saved.getString("survey.site", "Unnamed site"),
                saved.getIntArray("survey.answers"));
    }

    @Override
    public String route() {
        return ROUTE_SURVEY;
    }

    @Override
    public void onSaveState(Bundle out) {
        out.putString("survey.site", siteName);
        out.putIntArray("survey.answers", currentRatings());
    }

    @Override
    protected View createView() {
        Activity host = app.host();
        View v = LayoutInflater.from(host).inflate(R.layout.screen_survey, null);

        ((TextView) v.findViewById(R.id.survey_site)).setText(siteName);
        loading = v.findViewById(R.id.survey_loading);
        questionBox = v.findViewById(R.id.survey_questions);
        save = v.findViewById(R.id.survey_save);

        Button cancel = v.findViewById(R.id.survey_cancel);
        cancel.setOnClickListener(x -> app.nav().pop());
        save.setOnClickListener(x -> saveVisit());

        // Anonymous inner class on purpose (stressor 12) — two-method callback.
        QuestionRepository.loadAsync(host.getAssets(), new QuestionRepository.Callback() {
            @Override
            public void onLoaded(String surveyTitle, List<Question> questions) {
                loading.setVisibility(View.GONE);
                buildQuestionRows(questions);
                save.setEnabled(true);
            }

            @Override
            public void onError(String message) {
                loading.setText(host.getString(R.string.fs_load_error) + message);
            }
        });
        return v;
    }

    private void buildQuestionRows(List<Question> questions) {
        Activity host = app.host();
        bars.clear();
        questionBox.removeAllViews();
        float density = host.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);

        for (int i = 0; i < questions.size(); i++) {
            Question q = questions.get(i);

            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setPadding(pad, pad, pad, pad);

            TextView category = new TextView(host);
            category.setText(q.category.toUpperCase(Locale.getDefault()));
            category.setTextSize(11);
            category.setTextColor(host.getColor(R.color.fs_muted));
            row.addView(category);

            TextView text = new TextView(host);
            text.setText(q.text);
            text.setTextSize(15);
            text.setTextColor(host.getColor(R.color.fs_fg));
            row.addView(text);

            RatingBar bar = new RatingBar(host);
            bar.setNumStars(5);
            bar.setStepSize(1f);
            if (restoredRatings != null && i < restoredRatings.length) {
                bar.setRating(restoredRatings[i]);
            }
            row.addView(bar, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            bars.add(bar);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(8 * density);
            questionBox.addView(row, lp);
        }
    }

    /** Current 1..5 per question; before questions load, echo the restored state. */
    private int[] currentRatings() {
        if (bars.isEmpty()) {
            return restoredRatings != null ? restoredRatings.clone() : new int[0];
        }
        int[] out = new int[bars.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = Math.round(bars.get(i).getRating());
        }
        return out;
    }

    private void saveVisit() {
        Activity host = app.host();
        int[] ratings = currentRatings();
        for (int r : ratings) {
            if (r == 0) {
                Toast.makeText(host, R.string.fs_rate_all, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        app.store().add(new Response(System.currentTimeMillis(), siteName, ratings));
        Toast.makeText(host, R.string.fs_saved, Toast.LENGTH_SHORT).show();
        app.nav().pop();
    }
}

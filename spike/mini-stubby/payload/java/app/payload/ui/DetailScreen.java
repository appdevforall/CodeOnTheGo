package app.payload.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import app.payload.App;
import app.payload.R;
import app.payload.Screen;
import app.payload.data.Question;
import app.payload.data.QuestionRepository;
import app.payload.data.Response;

/**
 * Visit detail: metadata, per-question answers (labels resolved from the async
 * question load), and the confirm-delete AlertDialog flow (stressor 7b).
 */
public final class DetailScreen extends Screen {

    private final App app;
    private final long responseId;

    public DetailScreen(App app, long responseId) {
        this.app = app;
        this.responseId = responseId;
    }

    @Override
    public String route() {
        return ROUTE_DETAIL;
    }

    @Override
    public void onSaveState(Bundle out) {
        out.putLong("detail.id", responseId);
    }

    @Override
    protected View createView() {
        Activity host = app.host();
        Response r = app.store().byId(responseId);
        if (r == null) {
            // Deleted between navigation and render (or a stale restore).
            TextView gone = new TextView(host);
            gone.setText(R.string.fs_gone);
            gone.setTextColor(host.getColor(R.color.fs_muted));
            int pad = Math.round(24 * host.getResources().getDisplayMetrics().density);
            gone.setPadding(pad, pad, pad, pad);
            return gone;
        }

        View v = LayoutInflater.from(host).inflate(R.layout.screen_detail, null);
        ((TextView) v.findViewById(R.id.detail_site)).setText(r.siteName);
        ((TextView) v.findViewById(R.id.detail_date)).setText(r.dateLabel());
        ((TextView) v.findViewById(R.id.detail_score)).setText(r.scorePercent() + "%");

        LinearLayout answers = v.findViewById(R.id.detail_answers);
        TextView loading = new TextView(host);
        loading.setText(R.string.fs_loading);
        loading.setTextColor(host.getColor(R.color.fs_muted));
        answers.addView(loading);

        QuestionRepository.loadAsync(host.getAssets(), new QuestionRepository.Callback() {
            @Override
            public void onLoaded(String surveyTitle, List<Question> questions) {
                buildAnswerRows(answers, questions, r);
            }

            @Override
            public void onError(String message) {
                loading.setText(host.getString(R.string.fs_load_error) + message);
            }
        });

        v.findViewById(R.id.detail_back).setOnClickListener(x -> app.nav().pop());
        v.findViewById(R.id.detail_delete).setOnClickListener(x -> confirmDelete());
        return v;
    }

    private void buildAnswerRows(LinearLayout answers, List<Question> questions, Response r) {
        Activity host = app.host();
        answers.removeAllViews();
        float density = host.getResources().getDisplayMetrics().density;
        int pad = Math.round(12 * density);

        for (int i = 0; i < r.ratings.length; i++) {
            LinearLayout row = new LinearLayout(host);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBackgroundResource(R.drawable.bg_card);
            row.setPadding(pad, pad, pad, pad);

            TextView label = new TextView(host);
            label.setText(i < questions.size() ? questions.get(i).text : "Question " + (i + 1));
            label.setTextSize(14);
            label.setTextColor(host.getColor(R.color.fs_fg));
            row.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView stars = new TextView(host);
            stars.setText(starString(r.ratings[i]));
            stars.setTextSize(14);
            stars.setTextColor(host.getColor(R.color.fs_accent));
            row.addView(stars);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Math.round(8 * density);
            answers.addView(row, lp);
        }
    }

    private static String starString(int rating) {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) {
            sb.append(i <= rating ? '★' : '☆');
        }
        return sb.toString();
    }

    /** Confirm-delete AlertDialog flow. */
    private void confirmDelete() {
        Activity host = app.host();
        new AlertDialog.Builder(host)
                .setTitle(R.string.fs_delete_title)
                .setMessage(R.string.fs_delete_msg)
                .setPositiveButton(R.string.fs_delete, (d, w) -> {
                    app.store().delete(responseId);
                    Toast.makeText(host, R.string.fs_deleted, Toast.LENGTH_SHORT).show();
                    app.nav().pop();
                })
                .setNegativeButton(R.string.fs_cancel, null)
                .show();
    }
}

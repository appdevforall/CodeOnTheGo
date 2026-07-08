package app.payload;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Stand-in for the user's app: a tiny "My Notes" starter. Rendered by the
 * Mini-Stubby shell via reflection: Main.render(hostActivity). Uses its OWN
 * resources (R.* linked at a shell-assigned package id) through the host
 * activity's Resources, which the shell augmented with a ResourcesLoader over
 * this same apk.
 *
 * Notes live in memory only (inside the rendered view) — a hot reload rebuilds
 * the screen from scratch, which is fine for this demo.
 */
public final class Main {

    private Main() {}

    public static View render(Activity host) {
        View root = LayoutInflater.from(host).inflate(R.layout.payload_main, null);

        EditText input = root.findViewById(R.id.note_input);
        Button addButton = root.findViewById(R.id.add_button);
        Button clearButton = root.findViewById(R.id.clear_button);
        TextView emptyHint = root.findViewById(R.id.empty_hint);
        LinearLayout notesList = root.findViewById(R.id.notes_list);

        clearButton.setVisibility(View.GONE);

        addButton.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            notesList.addView(makeNoteRow(host, text), 0);
            input.setText("");
            emptyHint.setVisibility(View.GONE);
            clearButton.setVisibility(View.VISIBLE);
        });

        clearButton.setOnClickListener(v -> {
            notesList.removeAllViews();
            emptyHint.setVisibility(View.VISIBLE);
            clearButton.setVisibility(View.GONE);
        });

        return root;
    }

    /** Builds one note row: a small card-like TextView appended to the list. */
    private static View makeNoteRow(Activity host, String text) {
        TextView note = new TextView(host);
        note.setText("• " + text);
        note.setTextSize(16);
        note.setTextColor(host.getColor(R.color.payload_fg));
        note.setBackgroundColor(host.getColor(R.color.payload_card));
        note.setTypeface(Typeface.SANS_SERIF);
        int pad = dp(host, 14);
        note.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(host, 8);
        note.setLayoutParams(lp);
        return note;
    }

    private static int dp(Activity host, int dp) {
        return Math.round(dp * host.getResources().getDisplayMetrics().density);
    }
}

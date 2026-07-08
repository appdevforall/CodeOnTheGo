package app.payload;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Stand-in for the user's app. Rendered by the Mini-Stubby shell via
 * reflection: Main.render(hostActivity). Uses its OWN resources (R.* linked
 * at package-id 0x80) through the host activity's Resources, which the shell
 * augmented with a ResourcesLoader over this same apk.
 */
public final class Main {

    private Main() {}

    public static View render(Activity host) {
        View root = LayoutInflater.from(host).inflate(R.layout.payload_main, null);

        TextView assetNote = root.findViewById(R.id.asset_note);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                host.getAssets().open("payload_note.txt")))) {
            assetNote.setText(r.readLine());
        } catch (Exception e) {
            assetNote.setText("asset read failed: " + e);
        }

        Button button = root.findViewById(R.id.counter_button);
        final int[] taps = {0};
        button.setText(host.getString(R.string.payload_button, 0));
        button.setOnClickListener(v ->
                button.setText(host.getString(R.string.payload_button, taps[0] += 10)));

        return root;
    }
}

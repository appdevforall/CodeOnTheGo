package app.payload;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;

/** Material3 payload. Entry point matches the shell contract. */
public final class Main {
    private Main() {}
    public static View render(Activity host) {
        View root = LayoutInflater.from(host).inflate(R.layout.main, null);
        MaterialButton btn = root.findViewById(R.id.btn);
        final int[] n = {0};
        btn.setOnClickListener(v ->
            Snackbar.make(root, "Material tap " + (++n[0]), Snackbar.LENGTH_SHORT).show());
        return root;
    }
    public static int themeResId() { return R.style.Theme_Payload; }
}

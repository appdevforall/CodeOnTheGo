/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.editor.ui;

import static com.itsaky.androidide.editor.R.attr;

import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.itsaky.androidide.lsp.models.SignatureHelp;
import com.itsaky.androidide.lsp.models.SignatureInformation;
import com.itsaky.androidide.utils.ContextUtilsKt;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.widget.base.EditorPopupWindow;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An {@link EditorPopupWindow} used to show signature help.
 *
 * @author Akash Yadav
 */
public class SignatureHelpWindow extends BaseEditorWindow {

  private static final Logger LOG = LoggerFactory.getLogger(SignatureHelpWindow.class);

  /**
   * Create a signature help popup window for editor
   *
   * @param editor The editor.
   */
  public SignatureHelpWindow(@NonNull IDEEditor editor) {
    super(editor);

    editor.subscribeEvent(
        SelectionChangeEvent.class,
        (event, unsubscribe) -> {
          if (isShowing()) {
            dismiss();
          }
        });
  }

  public void setupAndDisplay(SignatureHelp signature) {
    if (signature == null || signature.getSignatures().isEmpty()) {
      if (isShowing()) {
        dismiss();
      }

      return;
    }

    final var signatureText = createSignatureText(signature);

    if (signatureText == null) {
      return;
    }

    this.text.setText(signatureText);
    displayWindow();
  }

  @Nullable
  private CharSequence createSignatureText(@NonNull SignatureHelp signature) {
    final var signatures = signature.getSignatures();
    final var activeSignature = signature.getActiveSignature();
    final var activeParameter = signature.getActiveParameter();
    final SpannableStringBuilder sb = new SpannableStringBuilder();

    if (activeSignature < 0 || activeParameter < 0) {
      LOG.debug("activeSignature: {}, activeParameter: {}", activeSignature, activeParameter);
      return null;
    }

    var count = signatures.size();
    if (activeSignature >= count) {
      LOG.debug("Active signature is invalid. Size is {}", count);
      return null;
    }

    // keep only applicable signatures (does not mutate the input list)
    final var applicable = applicableSignatures(signatures, activeParameter);

    count = applicable.size();
    for (var i = 0; i < count; i++) {
      final var info = applicable.get(i);
      formatSignature(info, activeParameter, sb);
      if (i != count - 1) {
        sb.append('\n');
      }
    }

    return sb;
  }

  /** Returns the function name portion of a signature label (text before the first '('), or the
   *  whole label if it contains no '('. */
  static String signatureName(String label) {
    final int paren = label.indexOf('(');
    return paren < 0 ? label : label.substring(0, paren);
  }

  /** Returns a new list containing only the signatures that have enough parameters for the given
   *  active parameter index. Never mutates the input list. */
  static List<SignatureInformation> applicableSignatures(
      List<SignatureInformation> signatures, int activeParameter) {
    final List<SignatureInformation> result = new ArrayList<>(signatures.size());
    for (final SignatureInformation info : signatures) {
      if (activeParameter < info.getParameters().size()) {
        result.add(info);
      }
    }
    return result;
  }

  /**
   * Formats (highlights) a method signature
   *
   * @param signature  Signature information
   * @param paramIndex Currently active parameter index
   * @param result     The builder to append spanned text to.
   */
  private void formatSignature(
      @NonNull SignatureInformation signature,
      int paramIndex,
      SpannableStringBuilder result) {

    final String name = signatureName(signature.getLabel());

    final var foreground = ContextUtilsKt.resolveAttr(getEditor().getContext(),
        attr.colorOnSecondaryContainer);
    final var paramSelected = 0xffff6060;
    final var operators = 0xff4fc3f7;

    result.append(
        name, new ForegroundColorSpan(foreground), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    result.append(
        "(", new ForegroundColorSpan(operators), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);

    var params = signature.getParameters();
    for (int i = 0; i < params.size(); i++) {
      int color = i == paramIndex ? paramSelected : foreground;
      final var info = params.get(i);
      if (i == params.size() - 1) {
        result.append(
            info.getLabel(),
            new ForegroundColorSpan(color),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else {
        result.append(
            info.getLabel(),
            new ForegroundColorSpan(color),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.append(
            ",",
            new ForegroundColorSpan(operators),
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.append(" ");
      }
    }
    result.append(
        ")", new ForegroundColorSpan(0xff4fc3f7), SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
  }
}

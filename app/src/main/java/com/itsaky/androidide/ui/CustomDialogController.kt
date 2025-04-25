import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import com.itsaky.androidide.R

class CustomDialogController(
    private val context: Context,
    private val listener: BasicDialogListener
) {

    interface BasicDialogListener {
        fun onAction1()
        fun onAction2()
    }

    private lateinit var dialog: Dialog

    fun showDialog(
        title: String,
        message: String,
        action1Text: String = "Yes",
        action2Text: String = "No"
    ) {
        dialog = Dialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.custom_dialog, null)

        val titleView = view.findViewById<TextView>(R.id.custom_dialog_title)
        val messageView = view.findViewById<TextView>(R.id.custom_dialog_description)
        val buttonAction1 = view.findViewById<Button>(R.id.custom_dialog_action_1)
        val buttonAction2 = view.findViewById<Button>(R.id.custom_dialog_action_2)

        titleView.text = title
        messageView.text = message
        buttonAction1.text = action1Text
        buttonAction2.text = action2Text

        buttonAction1.setOnClickListener {
            listener.onAction1()
            dialog.dismiss()
        }

        buttonAction2.setOnClickListener {
            listener.onAction2()
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.setCancelable(true)
        dialog.show()
    }

    fun dismiss() {
        if (::dialog.isInitialized && dialog.isShowing) {
            dialog.dismiss()
        }
    }
}

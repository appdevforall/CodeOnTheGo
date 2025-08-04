package com.example.sampleplugin.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment

class GoBotFragment : Fragment() {

    private lateinit var chatContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private var typingIndicatorView: TextView? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        val titleView = TextView(context).apply {
            text = "🤖 GoBot Chat"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }

        scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        chatContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(chatContainer)

        val inputLayout = createInputLayout(context)

        rootLayout.addView(titleView)
        rootLayout.addView(scrollView)
        rootLayout.addView(inputLayout)

        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("Hi, I'm GoBot. How can I help you today?", isUser = false)
        }, 400)

        rootLayout.viewTreeObserver.addOnGlobalLayoutListener {
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }

        return rootLayout
    }

    private fun createInputLayout(context: Context): LinearLayout {
        val inputLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            setPadding(0, 8, 0, 8)
        }

        val editTextHeight = 56

        val inputEditText = EditText(context).apply {
            hint = "Type a message..."
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(context, editTextHeight), 1f).apply {
                marginEnd = 8
            }
            setPadding(24, 0, 24, 0)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#F0F0F0"))
            }
            gravity = Gravity.CENTER_VERTICAL
        }

        val sendButton = Button(context).apply {
            text = "Send"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(context, editTextHeight)
            )
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#4CAF50"))
            }
            setPadding(32, 0, 32, 0)
        }

        sendButton.setOnClickListener {
            val userMessage = inputEditText.text.toString().trim()
            if (userMessage.isNotEmpty()) {
                addMessage(userMessage, isUser = true)
                inputEditText.text.clear()
                simulateBotReply()
            }
        }

        inputLayout.addView(inputEditText)
        inputLayout.addView(sendButton)

        return inputLayout
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val context = requireContext()

        val messageView = TextView(context).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 32f
                setColor(if (isUser) Color.parseColor("#DCF8C6") else Color.parseColor("#E3F2FD"))
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(context, 16)
                gravity = if (isUser) Gravity.END else Gravity.START
            }
            layoutParams = params
        }

        chatContainer.addView(messageView)
        scrollView.post {
            scrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun simulateBotReply() {
        showTypingIndicator()

        Handler(Looper.getMainLooper()).postDelayed({
            removeTypingIndicator()
            addMessage("I'm here to assist you with anything.", isUser = false)
        }, 1500)
    }

    private fun showTypingIndicator() {
        val context = requireContext()

        if (typingIndicatorView == null) {
            typingIndicatorView = TextView(context).apply {
                text = "GoBot is typing..."
                setTextColor(Color.GRAY)
                setTypeface(null, Typeface.ITALIC)
                setPadding(16, 12, 16, 12)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                    topMargin = 8
                }
            }
            chatContainer.addView(typingIndicatorView)
            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    private fun removeTypingIndicator() {
        typingIndicatorView?.let {
            chatContainer.removeView(it)
            typingIndicatorView = null
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return (dp * context.resources.displayMetrics.density).toInt()
    }
}
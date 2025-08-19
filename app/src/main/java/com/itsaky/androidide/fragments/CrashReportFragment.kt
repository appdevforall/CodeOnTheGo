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
package com.itsaky.androidide.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import com.itsaky.androidide.buildinfo.BuildInfo
import com.itsaky.androidide.databinding.LayoutCrashReportBinding
import com.itsaky.androidide.resources.R

class CrashReportFragment : Fragment() {

    private var _binding: LayoutCrashReportBinding? = null
    private val binding get() = _binding!!

    private var closeAppOnClick = true

    companion object {
        const val KEY_CLOSE_APP_ON_CLICK = "close_on_app_click"

        @JvmStatic
        fun newInstance(): CrashReportFragment {
            return newInstance(true)
        }

        @JvmStatic
        fun newInstance(
            closeAppOnClick: Boolean
        ): CrashReportFragment {
            val frag = CrashReportFragment()
            val args = Bundle().apply {
                putBoolean(KEY_CLOSE_APP_ON_CLICK, closeAppOnClick)
            }
            frag.arguments = args
            return frag
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutCrashReportBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val args = requireArguments()
        closeAppOnClick = args.getBoolean(KEY_CLOSE_APP_ON_CLICK)
        val title = getString(R.string.msg_ide_crashed)

        binding.apply {
            crashTitle.text = title
            crashSubtitle.apply {
                setCrashInfoText()
            }

            closeButton.setOnClickListener {
                finishActivity()
            }

            btnOkay.setOnClickListener { finishActivity() }

            // Handle system back button
            requireActivity().onBackPressedDispatcher.addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        finishActivity()
                    }
                }
            )

        }
    }

    fun TextView.setCrashInfoText() {
        // Get crash message with placeholder
        val supportText = context.getString(R.string.contact_support_team)
        val fullText = context.getString(R.string.msg_crash_info, supportText)

        val spannable = SpannableString(fullText)

        // Find clickable phrase in text
        val start = fullText.indexOf(supportText)
        if (start != -1) {
            spannable.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val email = "feedback@appdevforall.org"
                        val subject = Uri.encode(context.getString(R.string.crash_email_subject))
                        val body = Uri.encode(
                            context.getString(
                                R.string.crash_email_body,
                                BuildInfo.VERSION_NAME_SIMPLE
                            )
                        )

                        val uri = "mailto:$email?subject=$subject&body=$body".toUri()

                        val intent = Intent(Intent.ACTION_SENDTO, uri)
                        context.startActivity(intent)

                    }

                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.color =
                            ContextCompat.getColor(context, R.color.primary_blue) // your blue
                        ds.isUnderlineText = true
                    }
                },
                start,
                start + supportText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        text = spannable
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun finishActivity() {
        if (closeAppOnClick) {
            requireActivity().finishAffinity()
        } else {
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
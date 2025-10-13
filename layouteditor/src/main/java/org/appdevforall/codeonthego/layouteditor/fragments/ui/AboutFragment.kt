package org.appdevforall.codeonthego.layouteditor.fragments.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.appdevforall.codeonthego.layouteditor.BuildConfig
import org.appdevforall.codeonthego.layouteditor.R.string
import org.appdevforall.codeonthego.layouteditor.databinding.FragmentAboutBinding
import org.appdevforall.codeonthego.layouteditor.utils.Constants

class AboutFragment : Fragment() {
  private lateinit var binding: FragmentAboutBinding

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
  ): View {
    binding = FragmentAboutBinding.inflate(inflater, container, false)
    return binding.root
  }

  @SuppressLint("SetTextI18n")
  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    binding.details.text =
      getString(string.share_description, Constants.GITHUB_URL)
    //binding.version.text = "v${BuildConfig.VERSION_NAME}"
  }
}

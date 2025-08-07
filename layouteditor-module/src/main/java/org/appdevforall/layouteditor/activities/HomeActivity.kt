package com.itsvks.layouteditor.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.FitWindowsFrameLayout
import androidx.core.app.ShareCompat.IntentBuilder
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.preference.PreferenceManager
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import com.google.android.material.navigation.NavigationView
import com.itsvks.layouteditor.BaseActivity
import com.itsvks.layouteditor.R
import com.itsvks.layouteditor.databinding.ActivityHomeBinding
import com.itsvks.layouteditor.fragments.ui.AboutFragment
import com.itsvks.layouteditor.fragments.ui.HomeFragment
import com.itsvks.layouteditor.fragments.ui.PreferencesFragment
import com.itsvks.layouteditor.utils.Constants

class HomeActivity : BaseActivity() {
  private lateinit var binding: ActivityHomeBinding

  private lateinit var drawerLayout: DrawerLayout
  private lateinit var navigationView: NavigationView

  @SuppressLint("RestrictedApi")
  private var contentView: FitWindowsFrameLayout? = null

  private lateinit var actionBarDrawerToggle: ActionBarDrawerToggle
  private var prefs: SharedPreferences? = null

  private val onBackPressedCallback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
      if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
        if (prefs!!.getString("fragment", "home") == "home") {
          finishAffinity()
        } else {
          goToHome()
          navigationView.setCheckedItem(R.id.nav_home)
        }
      } else drawerLayout.closeDrawer(GravityCompat.START)
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    binding = ActivityHomeBinding.inflate(layoutInflater)
    prefs = PreferenceManager.getDefaultSharedPreferences(this)

    setContentView(binding.root)
    setSupportActionBar(binding.topAppBar)
    onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

    drawerLayout = binding.drawer
    navigationView = binding.navigationView

    contentView = binding.content
    goToHome()
    navigationView.setCheckedItem(R.id.nav_home)

    navigationDrawer()

    actionBarDrawerToggle =
      ActionBarDrawerToggle(
        this,
        drawerLayout,
        binding.topAppBar,
        R.string.navigation_drawer_open,
        R.string.navigation_drawer_close
      )

    drawerLayout.addDrawerListener(actionBarDrawerToggle)
    actionBarDrawerToggle.syncState()
  }

  @SuppressLint("NonConstantResourceId")
  private fun navigationDrawer() {
    navigationView.bringToFront()
    navigationView.setNavigationItemSelectedListener {
      val id = it.itemId
      when (id) {
        R.id.nav_home -> {
          drawerLayout.closeDrawer(GravityCompat.START)
          goToHome()
          navigationView.setCheckedItem(R.id.nav_home)
          true
        }
        R.id.nav_preference -> {
          drawerLayout.closeDrawer(GravityCompat.START)
          goToPreference()
          navigationView.setCheckedItem(R.id.nav_preference)
          true
        }
        R.id.nav_about -> {
          drawerLayout.closeDrawer(GravityCompat.START)
          goToAbout()
          navigationView.setCheckedItem(R.id.nav_about)
          true
        }
        R.id.nav_licence -> {
          startActivity(Intent(this, OssLicensesMenuActivity::class.java))
          true
        }
        R.id.nav_github -> {
          openUrl(Constants.GITHUB_URL)
          true
        }
        R.id.nav_share -> {
          val shareIntent = IntentBuilder(this)
          shareIntent.setType("text/plain")
          shareIntent.setChooserTitle(getString(R.string.app_name))
          shareIntent.setText(getString(R.string.share_description, Constants.GITHUB_URL))
          shareIntent.startChooser()
          true
        }
        else -> false
      }
    }
    navigationView.setCheckedItem(R.id.nav_home)

    animateNavigationDrawer()
  }

  private fun animateNavigationDrawer() {
    drawerLayout.addDrawerListener(
      object : DrawerLayout.SimpleDrawerListener() {
        override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
          val diffScaledOffset = slideOffset * (1 - END_SCALE)
          val offsetScale = 1 - diffScaledOffset
          contentView!!.scaleX = offsetScale
          contentView!!.scaleY = offsetScale

          val xOffset = drawerView.width * slideOffset
          val xOffsetDiff = contentView!!.width * diffScaledOffset / 2
          val xTranslation = xOffset - xOffsetDiff
          contentView!!.translationX = xTranslation
        }
      })
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    val id = item.itemId
    if (id == android.R.id.home) {
      drawerLayout.openDrawer(GravityCompat.START)
      return true
    }
    return actionBarDrawerToggle.onOptionsItemSelected(item)
  }

  override fun onConfigurationChanged(config: Configuration) {
    super.onConfigurationChanged(config)
    goToHome()
    actionBarDrawerToggle.onConfigurationChanged(config)
  }

  override fun onPostCreate(savedInstanceState: Bundle?) {
    super.onPostCreate(savedInstanceState)
    actionBarDrawerToggle.syncState()
  }

  private fun replaceFragment(fragment: Fragment) {
    supportFragmentManager
      .beginTransaction()
      .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
      .replace(R.id.main_fragment, fragment)
      .commit()
  }

  private fun goToHome() {
    replaceFragment(HomeFragment() as Fragment)
    supportActionBar?.title = getString(R.string.projects)
  }

  private fun goToPreference() {
    replaceFragment(PreferencesFragment() as Fragment)
    supportActionBar?.title = getString(R.string.preference)
  }

  private fun goToAbout() {
    replaceFragment(AboutFragment() as Fragment)
    supportActionBar?.title = getString(R.string.about)
  }

  companion object {
    private const val END_SCALE = 0.7f
  }
}

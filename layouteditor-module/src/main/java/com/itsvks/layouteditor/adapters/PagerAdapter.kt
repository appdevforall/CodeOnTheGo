package com.itsvks.layouteditor.adapters

import android.graphics.drawable.Drawable
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class PagerAdapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) {
  private val adapter: Adapter

  private var pager: ViewPager2? = null
  private var layout: TabLayout? = null

  private val fragmentList: MutableList<Fragment> = ArrayList()
  private val fragmentTitleList: MutableList<CharSequence?> = ArrayList()
  private val fragmentIconList: MutableList<Drawable> = ArrayList()

  private inner class Adapter(fragmentManager: FragmentManager, lifecycle: Lifecycle) :
    FragmentStateAdapter(fragmentManager, lifecycle) {
    override fun getItemCount(): Int {
      return fragmentList.size
    }

    override fun createFragment(position: Int): Fragment {
      return fragmentList[position]
    }
  }

  init {
    adapter = Adapter(fragmentManager, lifecycle)
  }

  fun setup(pager: ViewPager2?, layout: TabLayout?) {
    this.pager = pager
    this.layout = layout
  }

  fun addFragmentToAdapter(fragment: Fragment, title: CharSequence?) {
    fragmentList.add(fragment)
    fragmentTitleList.add(title)
  }

  fun addFragmentToAdapter(fragment: Fragment, title: CharSequence?, icon: Drawable) {
    fragmentList.add(fragment)
    fragmentTitleList.add(title)
    fragmentIconList.add(icon)
  }

  val fragmentsCount: Int
    get() = fragmentList.size

  fun getFragmentAt(position: Int): Fragment {
    return fragmentList[position]
  }

  fun getFragmentTitleAt(position: Int): CharSequence? {
    if (fragmentTitleList[position] == null) return fragmentTitleList[0]
    return fragmentTitleList[position]
  }

  fun getFragmentIconAt(position: Int): Drawable {
    return fragmentIconList[position]
  }

  fun getFragmentWithTitle(title: CharSequence): Fragment {
    for (i in 0 until fragmentsCount) {
      if (title === getFragmentTitleAt(i)) {
        return getFragmentAt(i)
      }
    }
    return fragmentList[0]
  }

  fun getFragmentPosition(fragment: Fragment): Int {
    for (i in 0 until fragmentsCount) {
      if (fragment === fragmentList[i]) {
        return i
      }
    }
    return 0
  }

  fun setupPager(orientation: Int) {
    pager!!.orientation = orientation
    pager!!.adapter = adapter
  }

  fun setupMediatorWithIcon() {
    val mediator = TabLayoutMediator(layout!!, pager!!) { tab, position ->
      tab.setText(getFragmentTitleAt(position))
      tab.setIcon(getFragmentIconAt(position))
    }
    mediator.attach()
  }

  fun setupMediatorWithoutIcon() {
    val mediator = TabLayoutMediator(layout!!, pager!!) { tab: TabLayout.Tab, position: Int ->
      tab.setText(getFragmentTitleAt(position))
    }
    mediator.attach()
  }
}

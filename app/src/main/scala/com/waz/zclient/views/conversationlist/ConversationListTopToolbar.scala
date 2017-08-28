/**
 * Wire
 * Copyright (C) 2017 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.views.conversationlist

import android.content.Context
import android.util.{AttributeSet, TypedValue}
import android.view.View
import android.view.View.{OnClickListener, OnLayoutChangeListener}
import android.widget.{FrameLayout, ImageView}
import com.waz.ZLog
import com.waz.model.{AssetId, TeamData}
import com.waz.service.ZMessaging
import com.waz.utils.NameParts
import com.waz.utils.events.{EventStream, Signal}
import com.waz.zclient.common.views.ChatheadView
import com.waz.zclient.controllers.UserAccountsController
import com.waz.zclient.drawables.{ListSeparatorDrawable, TeamIconDrawable}
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.ui.views.CircleView
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{RichView, UiStorage, UserSignal}
import com.waz.zclient.views.ImageAssetDrawable.{RequestBuilder, ScaleType}
import com.waz.zclient.views.ImageController.{ImageSource, WireImage}
import com.waz.zclient.views.{AccountTabButton, AccountTabsView, GlyphButton, ImageAssetDrawable}
import com.waz.zclient.{R, ViewHelper}


abstract class ConversationListTopToolbar(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends FrameLayout(context, attrs, defStyleAttr) with ViewHelper {

  private implicit val logTag = ZLog.logTagFor[ConversationListTopToolbar]

  inflate(R.layout.view_conv_list_top)

  val bottomBorder = findById[View](R.id.conversation_list__border)
  val profileButton = findById[ImageView](R.id.conversation_list_settings)
  val closeButton = findById[GlyphButton](R.id.conversation_list_close)
  val title = findById[TypefaceTextView](R.id.conversation_list_title)
  val settingsIndicator = findById[CircleView](R.id.conversation_list_settings_indicator)
  val tabsContainer = findById[AccountTabsView](R.id.team_tabs_container)

  val onRightButtonClick = EventStream[View]()

  protected var scrolledToTop = true
  protected val separatorDrawable = new ListSeparatorDrawable(getColor(R.color.white_24))
  protected val animationDuration = getResources.getInteger(R.integer.team_tabs__animation_duration)

  setClipChildren(false)
  bottomBorder.setBackground(separatorDrawable)

  closeButton.setOnClickListener(new OnClickListener {
    override def onClick(v: View) = {
      onRightButtonClick ! v
    }
  })

  profileButton.setOnClickListener(new OnClickListener {
    override def onClick(v: View) = {
      onRightButtonClick ! v
    }
  })

  def setScrolledToTop(scrolledToTop: Boolean): Unit = {
    if (this.scrolledToTop == scrolledToTop) {
      return
    }
    this.scrolledToTop = scrolledToTop
    if (!scrolledToTop) {
      separatorDrawable.animateCollapse()
    } else {
      separatorDrawable.animateExpand()
    }
  }

  override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = {
    val tv = new TypedValue()
    val height =
      if (context.getTheme.resolveAttribute(R.attr.actionBarSize, tv, true))
        TypedValue.complexToDimensionPixelSize(tv.data, getResources.getDisplayMetrics)
      else
        getDimenPx(R.dimen.teams_tab_default_height)(context)
    val heightOffset = getDimenPx(R.dimen.teams_tab_bottom_offset)(context)

    val heightSpec = View.MeasureSpec.makeMeasureSpec(height + heightOffset, View.MeasureSpec.EXACTLY)
    super.onMeasure(widthMeasureSpec, heightSpec)
  }
}

class NormalTopToolbar(override val context: Context, override val attrs: AttributeSet, override val defStyleAttr: Int)  extends ConversationListTopToolbar(context, attrs, defStyleAttr){
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  val zms = inject[Signal[ZMessaging]]
  val controller = inject[UserAccountsController]
  implicit val uiStorage = inject[UiStorage]

  val drawable = new TeamIconDrawable()
  val info = for {
    z <- zms
    user <- UserSignal(z.selfUserId)
    team <- z.teams.selfTeam
  } yield (user, team)

  info.onUi {
    case (user, Some(team)) =>
      drawable.assetId ! None
      drawable.setInfo(NameParts.maybeInitial(team.name).getOrElse(""), TeamIconDrawable.TeamCorners, selected = false)
    case (user, _) =>
      drawable.assetId ! user.picture
      drawable.setInfo(NameParts.maybeInitial(user.displayName).getOrElse(""), TeamIconDrawable.UserCorners, selected = false)
  }
  profileButton.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
  profileButton.setImageDrawable(drawable)
  profileButton.setVisible(true)
  closeButton.setVisible(false)
  tabsContainer.setVisible(false)
  title.setVisible(true)
  separatorDrawable.setDuration(0)
  onTabsChanged(tabsContainer)

  tabsContainer.addOnLayoutChangeListener(new OnLayoutChangeListener {
    override def onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int): Unit = {
      onTabsChanged(v)
    }
  })

  private def onTabsChanged(v: View): Unit = {
    val maxValue = if (v.isVisible) v.getWidth.toFloat / getWidth.toFloat else 1.0f
    separatorDrawable.setMinMax(0f, maxValue)
    if (scrolledToTop) {
      separatorDrawable.setClip(maxValue)
    } else {
      separatorDrawable.setClip(0f)
    }
  }

  override def setScrolledToTop(scrolledToTop: Boolean): Unit = {
    if (this.scrolledToTop == scrolledToTop) {
      return
    }
    super.setScrolledToTop(scrolledToTop)
    if (tabsContainer.isVisible) {
      (0 until tabsContainer.getChildCount).map(tabsContainer.getChildAt).foreach{
        case child: AccountTabButton =>
          if (scrolledToTop)
            child.animateExpand()
          else
            child.animateCollapse()
        case _ =>
      }
    }
  }

  def setIndicatorVisible(visible: Boolean): Unit = {
    settingsIndicator.setVisible(visible)
  }

  def setIndicatorColor(color: Int): Unit = {
    settingsIndicator.setAccentColor(color)
  }
}


class ArchiveTopToolbar(override val context: Context, override val attrs: AttributeSet, override val defStyleAttr: Int)  extends ConversationListTopToolbar(context, attrs, defStyleAttr){
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  profileButton.setVisible(false)
  closeButton.setVisible(true)
  settingsIndicator.setVisible(false)
  tabsContainer.setVisible(false)
  title.setVisible(true)
  separatorDrawable.setDuration(0)
  separatorDrawable.animateExpand()
}

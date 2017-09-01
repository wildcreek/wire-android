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
import android.util.AttributeSet
import android.view.View
import android.view.View.OnClickListener
import android.widget.{FrameLayout, ImageView}
import com.waz.ZLog
import com.waz.service.ZMessaging
import com.waz.utils.NameParts
import com.waz.utils.events.{EventStream, Signal}
import com.waz.zclient.controllers.UserAccountsController
import com.waz.zclient.drawables.{ListSeparatorDrawable, TeamIconDrawable}
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.ui.views.CircleView
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.{RichView, UiStorage, UserSignal}
import com.waz.zclient.views.GlyphButton
import com.waz.zclient.{R, ViewHelper}


abstract class ConversationListTopToolbar(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends FrameLayout(context, attrs, defStyleAttr) with ViewHelper {

  private implicit val logTag = ZLog.logTagFor[ConversationListTopToolbar]

  inflate(R.layout.view_conv_list_top)

  val buttonContainer = findById[View](R.id.button_container)
  val bottomBorder = findById[View](R.id.conversation_list__border)
  val profileButton = findById[ImageView](R.id.conversation_list_settings)
  val closeButton = findById[GlyphButton](R.id.conversation_list_close)
  val title = findById[TypefaceTextView](R.id.conversation_list_title)
  val settingsIndicator = findById[CircleView](R.id.conversation_list_settings_indicator)

  val onRightButtonClick = EventStream[View]()

  protected var scrolledToTop = true
  protected val separatorDrawable = new ListSeparatorDrawable(getColor(R.color.white_24))
  protected val animationDuration = getResources.getInteger(R.integer.team_tabs__animation_duration)

  setClipChildren(false)
  bottomBorder.setBackground(separatorDrawable)

  buttonContainer.setOnClickListener(new OnClickListener {
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
  title.setVisible(true)
  separatorDrawable.setDuration(0)
  separatorDrawable.setMinMax(0f, 1.0f)
  separatorDrawable.setClip(1.0f)

  override def setScrolledToTop(scrolledToTop: Boolean): Unit = {
    if (this.scrolledToTop == scrolledToTop) {
      return
    }
    super.setScrolledToTop(scrolledToTop)
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
  title.setVisible(true)
  separatorDrawable.setDuration(0)
  separatorDrawable.animateExpand()
}

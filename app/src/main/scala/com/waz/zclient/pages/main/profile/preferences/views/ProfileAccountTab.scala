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
package com.waz.zclient.pages.main.profile.preferences.views

import android.content.Context
import android.util.AttributeSet
import android.view.{View, ViewGroup}
import android.widget.FrameLayout.LayoutParams
import android.widget.{FrameLayout, ImageView}
import com.waz.api.impl.AccentColor
import com.waz.model.{AccountId, TeamData, UserData}
import com.waz.service.ZMessaging
import com.waz.utils.NameParts
import com.waz.utils.events.Signal
import com.waz.zclient.controllers.UserAccountsController
import com.waz.zclient.controllers.global.AccentColorController
import com.waz.zclient.drawables.TeamIconDrawable
import com.waz.zclient.ui.views.CircleView
import com.waz.zclient.utils.{RichView, UiStorage, UserSignal}
import com.waz.zclient.{R, ViewHelper}


class ProfileAccountTab(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends FrameLayout(context, attrs, defStyleAttr) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  inflate(R.layout.view_account_tab)
  setLayoutParams(new LayoutParams(context.getResources.getDimensionPixelSize(R.dimen.teams_tab_width), ViewGroup.LayoutParams.MATCH_PARENT))

  private implicit val uiStorage = inject[UiStorage]
  private val userAccountsController = inject[UserAccountsController]
  private val accentColor = inject[AccentColorController].accentColor

  val icon            = findById[ImageView](R.id.team_icon)
  val iconContainer   = findById[FrameLayout](R.id.team_icon_container)
  val unreadIndicator = findById[CircleView](R.id.unread_indicator_icon)

  val drawable = new TeamIconDrawable

  private val accountId = Signal[AccountId]()

  val account = accountId.flatMap(ZMessaging.currentAccounts.storage.signal).disableAutowiring()

  val selected = (for {
    acc    <- accountId
    active <- ZMessaging.currentAccounts.activeAccountPref.signal
  } yield active.contains(acc))
    .disableAutowiring()

  val teamOrUser: Signal[Either[TeamData, UserData]] = account.flatMap { acc =>
    acc.teamId match {
      case Right(Some(t)) => ZMessaging.currentGlobal.teamsStorage.signal(t).map(Left(_))
      case _ => (acc.userId match {
        case Some(id) => UserSignal(id)
        case None     => Signal.empty[UserData]
      }).map(Right(_))
    }
  }

  private val unreadCount = for {
    accountId <- accountId
    count  <- userAccountsController.unreadCount.map(_.get(accountId))
  } yield count.getOrElse(0)

  (for {
    s   <- selected
    tOu <- teamOrUser
    currentAccent <- accentColor
  } yield (tOu, s, currentAccent)).onUi {
    case (Right(user), s, _) =>
      drawable.setBorderColor(AccentColor(user.accent).getColor)
      drawable.setInfo(NameParts.maybeInitial(user.displayName).getOrElse(""), TeamIconDrawable.UserCorners, s)
      drawable.assetId ! user.picture
    case (Left(team), s, currentAccent) =>
      drawable.setBorderColor(currentAccent.getColor)
      drawable.setInfo(NameParts.maybeInitial(team.name).getOrElse(""), TeamIconDrawable.TeamCorners, s)
      // TODO use team icon when ready
      drawable.assetId ! None
  }

  Signal(unreadCount, selected).onUi {
    case (c, false) if c > 0 =>
      unreadIndicator.setVisible(true)
    case _ =>
      unreadIndicator.setVisible(false)
  }

  icon.setImageDrawable(drawable)
  setLayerType(View.LAYER_TYPE_SOFTWARE, null)

  def setAccount(id: AccountId) = accountId ! id

}

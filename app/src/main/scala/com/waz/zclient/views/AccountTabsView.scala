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
package com.waz.zclient.views

import android.content.Context
import android.support.v7.widget.{LinearLayoutManager, RecyclerView}
import android.util.AttributeSet
import android.view.View.OnClickListener
import android.view.{View, ViewGroup}
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.error
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.utils.events.{EventContext, EventStream}
import com.waz.zclient.controllers.UserAccountsController
import com.waz.zclient.pages.main.profile.preferences.views.ProfileAccountTab
import com.waz.zclient.{Injectable, Injector, ViewHelper}

class AccountTabsView(val context: Context, val attrs: AttributeSet, val defStyleAttr: Int) extends RecyclerView(context, attrs, defStyleAttr) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null)

  val onTabClick = EventStream[Either[UserData, TeamData]]()

  setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false){
    override def canScrollHorizontally = true
    override def canScrollVertically = false
  })
  setOverScrollMode(View.OVER_SCROLL_NEVER)
  private val adapter = new AccountTabsAdapter(context)
  setAdapter(adapter)
}

class AccountTabViewHolder(view: ProfileAccountTab) extends RecyclerView.ViewHolder(view){
  def bind(accountId: AccountId): Unit = view.setAccount(accountId)
}

class AccountTabsAdapter(context: Context)(implicit injector: Injector, eventContext: EventContext) extends RecyclerView.Adapter[AccountTabViewHolder] with Injectable {
  val controller         = inject[UserAccountsController]

  val onItemClick = EventStream[AccountData]()

  onItemClick{ account =>
    ZMessaging.currentAccounts.switchAccount(account.id)
  }

  private var accounts = Seq.empty[AccountId]
  controller.accounts.map(_.map(_.id)).onUi { accs =>
    accounts = accs
    notifyDataSetChanged()
  }

  override def getItemCount = accounts.size

  override def onBindViewHolder(holder: AccountTabViewHolder, position: Int) = {
    getItem(position) match {
      case Some(id) => holder.bind(id)
      case _        => error("Invalid get item index")
    }
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int) = {
    val view = new ProfileAccountTab(context)
    view.setOnClickListener(new OnClickListener {
      override def onClick(v: View) = {
        Option(v.asInstanceOf[ProfileAccountTab]).flatMap(_.account.currentValue).foreach(onItemClick ! _)
      }
    })
    new AccountTabViewHolder(view)
  }

  def getItem(position: Int): Option[AccountId] = accounts.lift(position)
}

package com.waz.zclient.conversationlist

import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream, Signal}
import com.waz.zclient.conversationlist.ConversationListController.MembersCache
import com.waz.zclient.views.conversationlist.ConversationAvatarView
import com.waz.zclient.{Injectable, Injector}

class ConversationListController(implicit inj: Injector, ec: EventContext) extends Injectable {

  val zms = inject[Signal[ZMessaging]]
  val membersCache = zms map { new MembersCache(_) }

  def members(conv: ConvId) = membersCache.flatMap(_.apply(conv))
}

object ConversationListController {

  // Maintains a short list of members for each conversation.
  // Only keeps up to 4 users other than self user, this list is to be used for avatar in conv list.
  // We keep this always in memory to avoid reloading members list for every list row view (caused performance issues)
  class MembersCache(zms: ZMessaging)(implicit inj: Injector, ec: EventContext) extends Injectable {
    private implicit val dispatcher = new SerialDispatchQueue(name = "MembersCache")

    private def entries(convMembers: Seq[ConversationMemberData]) =
      convMembers.groupBy(_.convId).map { case (convId, ms) =>
        val otherUsers = ms.collect { case ConversationMemberData(user, _) if user != zms.selfUserId => user }
        convId -> ConversationAvatarView.shuffle(otherUsers, convId).take(4)
      }

    val updatedEntries = EventStream.union(
      zms.membersStorage.onAdded.map(_.map(_.convId).toSet),
      zms.membersStorage.onDeleted.map(_.map(_._2).toSet)
    ) mapAsync { convs =>
      zms.membersStorage.getByConvs(convs) map entries
    }

    val members = new AggregatingSignal[Map[ConvId, Seq[UserId]], Map[ConvId, Seq[UserId]]](updatedEntries, zms.membersStorage.list() map entries, _ ++ _)

    def apply(conv: ConvId) : Signal[Seq[UserId]] = members.map(_.getOrElse(conv, Seq.empty[UserId]))
  }
}

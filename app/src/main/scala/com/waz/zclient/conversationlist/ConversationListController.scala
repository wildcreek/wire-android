package com.waz.zclient.conversationlist

import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{AggregatingSignal, EventContext, EventStream, Signal}
import com.waz.zclient.conversationlist.ConversationListController.{LastMessageCache, MembersCache}
import com.waz.zclient.views.conversationlist.ConversationAvatarView
import com.waz.zclient.{Injectable, Injector}

import scala.collection.mutable

class ConversationListController(implicit inj: Injector, ec: EventContext) extends Injectable {

  val zms = inject[Signal[ZMessaging]]
  val membersCache = zms map { new MembersCache(_) }
  val lastMessageCache = zms map { new LastMessageCache(_) }

  def members(conv: ConvId) = membersCache.flatMap(_.apply(conv))

  def lastMessage(conv: ConvId) = lastMessageCache.flatMap(_.apply(conv))
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


  // Keeps last message for each conversation, this is needed because MessagesStorage is not
  // supposed to be used for multiple conversations at the same time, as it loads an index of all conv messages.
  // Using MessagesStorage with multiple/all conversations forces it to reload full msgs index on every conv switch.
  class LastMessageCache(zms: ZMessaging)(implicit inj: Injector, ec: EventContext) extends Injectable {

    private val cache = new mutable.HashMap[ConvId, Signal[Option[MessageData]]]

    private val changeEvents = zms.messagesStorage.onChanged map { msgs => msgs.groupBy(_.convId).mapValues(_.maxBy(_.time)) }

    private def updateEvents(conv: ConvId) = changeEvents.map(_.get(conv)).collect { case Some(m) => m }

    private def getLastMessage(conv: ConvId) = zms.storage.db { MessageData.MessageDataDao.last(conv)(_) }

    def apply(conv: ConvId): Signal[Option[MessageData]] = cache.getOrElseUpdate(conv,
      new AggregatingSignal[MessageData, Option[MessageData]](updateEvents(conv), getLastMessage(conv), {
        case (res @ Some(last), update) if last.time.isAfter(update.time) => res
        case (_, update) => Some(update)
      }))
  }

}

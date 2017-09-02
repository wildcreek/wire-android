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
package com.waz.zclient.tracking

import android.content.Context
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.api.NetworkMode
import com.waz.model.ConvId
import com.waz.service.call.Avs.ClosedReason.{Interrupted, Normal}
import com.waz.service.call.CallInfo.CallState
import com.waz.service.call.CallInfo.CallState._
import com.waz.threading.Threading
import com.waz.utils.RichInstant
import com.waz.utils.events.EventContext
import com.waz.zclient.calling.controllers.GlobalCallingController
import com.waz.zclient.core.controllers.tracking.attributes.CompletedMediaType
import com.waz.zclient.core.controllers.tracking.events.media.CompletedMediaActionEvent
import com.waz.zclient.{Injectable, Injector}
import org.threeten.bp.Instant

import scala.concurrent.Future

class CallingTrackingController(implicit injector: Injector, ctx: Context, ec: EventContext) extends Injectable {

  import CallingTrackingController._
  import GlobalTrackingController._
  import Threading.Implicits.Background

  val global = inject[GlobalTrackingController]

  import global._

  val callController = inject[GlobalCallingController]
  import callController._

  /**
    * For now, we are only interested in tracking the "active" call. Since any incoming calls will be ignored by the
    * CallingControllers for now (i.e., incoming calls won't affect the calling signals), we can be certain that any
    * updates received here are only for that first and "most active" call, so there's no need to worry about keeping
    * track of multiple calls.
    *
    * However, this does feel a bit precarious - if the underlying implementations of calling change to handle multiple
    * calls, then we'll need to handle that better here.
    */
  private var startedJoining = Option.empty[Instant]
  private var callEstablished = Option.empty[Instant]
  private var prevInfo = Option.empty[CallingTrackingInfo]


  callStateOpt.onChanged {
    case Some(st) =>
      verbose(s"Call state changed to: $st")
      //Calculate times now in case information gathering is delayed
      if (st == SelfJoining)
        startedJoining = Some(Instant.now)

      if (st == SelfConnected)
        callEstablished = Some(Instant.now)

      val estDuration = startedJoining.getOrElse(Instant.now).until(Instant.now)

      getCallTrackingInfo(st) map {
        case info@CallingTrackingInfo(_, _, v3Call, isVideoCall, isGroupCall, wasUiActive, withOtto, incoming, convMemCount) =>
          st match {
            case OtherCalling =>
              tagEvent(ReceivedCallEvent(v3Call, isVideoCall, isGroupCall, wasUiActive, withOtto))

            case SelfCalling =>
              //The extra CompletedMediaActionEvent is here to simplify contributor events on localytics
              import CompletedMediaType._
              tagEvent(new CompletedMediaActionEvent(if (isVideoCall) VIDEO_CALL else AUDIO_CALL, if (isGroupCall) "GROUP" else "ONE_TO_ONE", withOtto, false, ""))
              tagEvent(StartedCallEvent(v3Call, isVideoCall, isGroupCall, withOtto))

            case SelfJoining => //For calling v3, this will only ever be for incoming calls
              tagEvent(JoinedCallEvent(v3Call, isVideoCall, isGroupCall, convMemCount, incoming, wasUiActive, withOtto))

            case SelfConnected =>
              tagEvent(EstablishedCallEvent(v3Call, isVideoCall, isGroupCall, convMemCount, incoming, wasUiActive, withOtto, estDuration))
              startedJoining = None

            case _ => //
          }
          prevInfo = Some(info)
      }

    case st =>
      val callDuration = callEstablished.getOrElse(Instant.now).until(Instant.now)
      prevInfo.foreach { p =>
        if (p.state == SelfConnected || p.state == SelfJoining) {

          (for {
            z <- zms.head
            Some(info) <- z.calling.previousCall.head
            cause <- info match {
                case call if call.closedReason == Normal => Future.successful(if (call.hangupRequested) "SELF" else "OTHER")
                case call if call.closedReason == Interrupted => Future.successful("gsm_call")
                case _ => z.network.networkMode.head.map(networkModeString)
              }
            callParticipants = info.maxParticipants
          } yield (cause, callParticipants)).map {
            case (cause, callParticipants) =>
              tagEvent(EndedCallEvent(p.isV3Call, p.isVideoCall, cause = cause, p.isGroupCall, p.convMemCount, callParticipants, p.isIncoming, p.wasUiActive, p.withOtto, callDuration))
          }
        }
      }

      callEstablished = None
      prevInfo = None
  }
  /**
    * If there is no active call, then a lot of these signals will potentially be empty and this future will never complete.
    * This also means we need to keep track of the previous info for the case in which we move from an active call to an inactive state.
    */
  private def getCallTrackingInfo(st: CallState) = for {
    zms         <- zMessaging.head
    conv        <- conversation.head
    withOtto    <- isOtto(conv, zms.usersStorage)
    video       <- videoCall.head
    isGroup     <- groupCall.head
    incoming    <- incomingCall.head
    convMembers <- zms.membersStorage.getActiveUsers(conv.id)
    wasUiActive = wasUiActiveOnCallStart
  } yield CallingTrackingInfo(st, conv.id, isV3Call = true, isVideoCall = video, isGroupCall = isGroup, wasUiActive = wasUiActive, withOtto = withOtto, isIncoming = prevInfo.exists(_.isIncoming) || incoming, convMembers.size)
}

object CallingTrackingController {

  private def networkModeString(networkMode: NetworkMode) = networkMode match {
    case NetworkMode.WIFI    => "drop_wifi"
    case NetworkMode._4G     => "drop_4g"
    case NetworkMode._3G     => "drop_3g"
    case NetworkMode.EDGE    => "drop_EDGE"
    case NetworkMode._2G     => "drop_2g"
    case NetworkMode.OFFLINE => "offline"
    case _ => ""
  }

  case class CallingTrackingInfo(state:        CallState,
                                 convId:       ConvId,
                                 isV3Call:     Boolean,
                                 isVideoCall:  Boolean,
                                 isGroupCall:  Boolean,
                                 wasUiActive:  Boolean,
                                 withOtto:     Boolean,
                                 isIncoming:   Boolean,
                                 convMemCount: Int)
}

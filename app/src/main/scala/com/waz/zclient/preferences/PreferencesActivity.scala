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
package com.waz.zclient.preferences

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.content.{Context, Intent}
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.support.annotation.Nullable
import android.support.v4.app.{Fragment, FragmentManager, FragmentTransaction}
import android.support.v4.widget.TextViewCompat
import android.support.v7.widget.{AppCompatTextView, Toolbar}
import android.view.{MenuItem, View, ViewGroup}
import android.widget.{TextSwitcher, TextView, Toast, ViewSwitcher}
import com.waz.ZLog.ImplicitTag._
import com.waz.api.ImageAsset
import com.waz.content.GlobalPreferences.CurrentAccountPref
import com.waz.content.{GlobalPreferences, UserPreferences}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.utils.returning
import com.waz.zclient.controllers.accentcolor.AccentColorChangeRequester
import com.waz.zclient.controllers.global.AccentColorController
import com.waz.zclient.core.controllers.tracking.events.settings.ChangedProfilePictureEvent
import com.waz.zclient.pages.main.profile.camera.{CameraContext, CameraFragment}
import com.waz.zclient.pages.main.profile.preferences.pages.{DevicesBackStackKey, OptionsView, ProfileBackStackKey}
import com.waz.zclient.tracking.GlobalTrackingController
import com.waz.zclient.utils.{BackStackNavigator, LayoutSpec, RingtoneUtils, ViewUtils}
import com.waz.zclient.{ActivityHelper, BaseActivity, MainActivity, R}

class PreferencesActivity extends BaseActivity
  with ActivityHelper
  with CameraFragment.Container {

  import PreferencesActivity._

  private lazy val toolbar: Toolbar        = findById(R.id.toolbar)

  private lazy val backStackNavigator = inject[BackStackNavigator]
  private lazy val zms = inject[Signal[ZMessaging]]

  private lazy val actionBar = returning(getSupportActionBar) { ab =>
    ab.setDisplayHomeAsUpEnabled(true)
    ab.setDisplayShowCustomEnabled(true)
    ab.setDisplayShowTitleEnabled(false)
  }

  private lazy val titleSwitcher = returning(new TextSwitcher(toolbar.getContext)) { ts =>
    ts.setFactory(new ViewSwitcher.ViewFactory() {
      def makeView: View = {
        val tv: TextView = new AppCompatTextView(toolbar.getContext)
        TextViewCompat.setTextAppearance(tv, R.style.TextAppearance_AppCompat_Widget_ActionBar_Title)
        tv
      }
    })
    ts.setCurrentText(getTitle)
    actionBar.setCustomView(ts)
    ts.setInAnimation(this, R.anim.abc_fade_in)
    ts.setOutAnimation(this, R.anim.abc_fade_out)
  }

  lazy val currentAccountPref = inject[GlobalPreferences].preference(CurrentAccountPref)
  lazy val accentColor = inject[AccentColorController].accentColor

  @SuppressLint(Array("PrivateResource"))
  override def onCreate(@Nullable savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    setSupportActionBar(toolbar)
    titleSwitcher //initialise title switcher

    if (LayoutSpec.isPhone(this)) ViewUtils.lockScreenOrientation(Configuration.ORIENTATION_PORTRAIT, this)
    if (savedInstanceState == null) {
      backStackNavigator.setup(findViewById(R.id.content).asInstanceOf[ViewGroup])

      if(Option(getIntent.getExtras).exists(_.getBoolean(ShowOtrDevices, false))) {
        backStackNavigator.goTo(DevicesBackStackKey())
      } else {
        backStackNavigator.goTo(ProfileBackStackKey())
      }

      backStackNavigator.currentState.on(Threading.Ui){ state =>
        setTitle(state.nameId)
      }
    } else {
      backStackNavigator.onRestore(findViewById(R.id.content).asInstanceOf[ViewGroup], savedInstanceState)
    }

    //TODO: Investigate why the onChanged doesn't work
    val currentAccount = currentAccountPref.signal.currentValue.flatten
    currentAccountPref.signal.onUi { acc =>
      if (acc != currentAccount) {
        //startActivity(returning(new Intent(this, classOf[MainActivity]))(_.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)))
        //finish()
      }
    }

    accentColor.on(Threading.Ui) { color =>
      getControllerFactory.getUserPreferencesController.setLastAccentColor(color.getColor())
      getControllerFactory.getAccentColorController.setColor(AccentColorChangeRequester.REMOTE, color.getColor())
    }
  }


  override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
    backStackNavigator.onSaveState(outState)
  }

  override def onStart(): Unit = {
    super.onStart()
    getControllerFactory.getCameraController.addCameraActionObserver(this)
  }

  override def onStop(): Unit = {
    super.onStop()
    getControllerFactory.getCameraController.removeCameraActionObserver(this)
  }

  override protected def onTitleChanged(title: CharSequence, color: Int) = {
    super.onTitleChanged(title, color)
    titleSwitcher.setText(title)
  }

  override def getBaseTheme: Int = R.style.Theme_Dark_Preferences

  override protected def onActivityResult(requestCode: Int, resultCode: Int, data: Intent) = {
    super.onActivityResult(requestCode, resultCode, data)
    val fragment: Fragment = getSupportFragmentManager.findFragmentById(R.id.fl__root__camera)
    if (fragment != null) fragment.onActivityResult(requestCode, resultCode, data)

    if (resultCode == Activity.RESULT_OK && Seq(OptionsView.RingToneResultId, OptionsView.TextToneResultId, OptionsView.PingToneResultId).contains(requestCode)) {

      val pickedUri = Option(data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI).asInstanceOf[Uri])
      val key = requestCode match {
        case OptionsView.RingToneResultId => UserPreferences.RingTone
        case OptionsView.TextToneResultId => UserPreferences.TextTone
        case OptionsView.PingToneResultId => UserPreferences.PingTone
      }
      zms.head.flatMap(_.userPrefs.preference(key).update(pickedUri.fold(RingtoneUtils.getSilentValue)(_.toString)))(Threading.Ui)
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    item.getItemId match {
      case android.R.id.home =>
        onBackPressed()
      case _ =>
    }
    super.onOptionsItemSelected(item)
  }


  override def onBackPressed() = {
    Option(getSupportFragmentManager.findFragmentByTag(CameraFragment.TAG).asInstanceOf[CameraFragment]).fold{
      if (!backStackNavigator.back())
        finish()
    }{ _.onBackPressed() }
  }

  //TODO do we need to check internet connectivity here?
  override def onBitmapSelected(imageAsset: ImageAsset, imageFromCamera: Boolean, cameraContext: CameraContext) =
    if (cameraContext == CameraContext.SETTINGS) {
      inject[Signal[ZMessaging]].head.map { zms =>
        zms.users.updateSelfPicture(imageAsset)
        inject[GlobalTrackingController].tagEvent(new ChangedProfilePictureEvent)
      } (Threading.Background)
      getSupportFragmentManager.popBackStack(CameraFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

  override def onCameraNotAvailable() =
    Toast.makeText(this, "Camera not available", Toast.LENGTH_SHORT).show()

  override def onOpenCamera(cameraContext: CameraContext) = {
    Option(getSupportFragmentManager.findFragmentByTag(CameraFragment.TAG)) match {
      case None =>
        getSupportFragmentManager
          .beginTransaction
          .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
          .add(R.id.fl__root__camera, CameraFragment.newInstance(cameraContext), CameraFragment.TAG)
          .addToBackStack(CameraFragment.TAG)
          .commit
      case Some(_) => //do nothing
    }
  }

  def onCloseCamera(cameraContext: CameraContext) =
    getSupportFragmentManager.popBackStack(CameraFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)

}

object PreferencesActivity {
  val ShowOtrDevices   = "SHOW_OTR_DEVICES"
  val ShowAccount      = "SHOW_ACCOUNT"

  def getDefaultIntent(context: Context): Intent =
    new Intent(context, classOf[PreferencesActivity])

  def getOtrDevicesPreferencesIntent(context: Context): Intent =
    returning(getDefaultIntent(context))(_.putExtra(ShowOtrDevices, true))

}
